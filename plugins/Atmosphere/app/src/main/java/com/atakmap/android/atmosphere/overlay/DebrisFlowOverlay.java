package com.atakmap.android.atmosphere.overlay;

import android.content.Context;

import com.atakmap.android.atmosphere.compat.GeneratedFiles;
import com.atakmap.android.atmosphere.data.DebrisFlow;
import com.atakmap.android.atmosphere.data.TornadoPaths;
import com.atakmap.android.atmosphere.net.EgressPolicy;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.maps.assets.Icon;
import com.atakmap.coremap.maps.coords.GeoBounds;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.map.AtakMapView;
import com.atakmap.map.layer.feature.AttributeSet;
import com.atakmap.map.layer.feature.geometry.Geometry;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Post-fire debris-flow hazard on the map, from the USGS: every fire assessed in the
 * last three years as a marker, and, zoomed in, the fire's perimeter with each
 * stream basin filled Low, Moderate or High in USGS's own yellow, orange and red.
 * Beside the river gauges and the rain, because a debris flow is a flood of rock
 * and mud and the trigger is a burst of rain over a burn (operator, 2026-10-11:
 * "isnt that similar to flooding? they are related").
 *
 * <p>The fires are one national request. The basins are 86,000 polygons
 * nationally, so they are asked for the map's extent only, padded, and asked again
 * when the map settles somewhere outside what was asked. <b>The map's extent
 * leaves the device; the position does not.</b> Over about four degrees wide only
 * the markers draw and the status says to zoom in.
 */
public final class DebrisFlowOverlay extends OutlookOverlay {

    public static final String LAYER_ID = "debrisflow";
    public static final String HOST = DebrisFlow.HOST;
    private static final String TAG = "AtmosphereDebrisFlow";
    /** Wider than this, only the fire markers are drawn. */
    private static final double MAX_SPAN_LON = 4.0;
    private static final long SETTLE_MS = 800;

    private static final String FIRES = "Fires Assessed", PERIMETER = "Fire Perimeter";

    /** The key. */
    public static final String[][] LEGEND = {
            { "Fire assessed by the USGS (last three years)", String.valueOf(DebrisFlow.FIRE_COLOR) },
            { "High: likely, and a lot of material", String.valueOf(DebrisFlow.COLORS[2]) },
            { "Moderate", String.valueOf(DebrisFlow.COLORS[1]) },
            { "Low: unlikely, or little material", String.valueOf(DebrisFlow.COLORS[0]) } };

    private final DotIcons icons;
    private final List<String> urls = new CopyOnWriteArrayList<>();
    /** What the basins were last asked for: west, south, east, north; null for none. */
    private volatile double[] asked;
    private volatile boolean zoomedOut = true;
    private volatile boolean dirty;
    private volatile int received;
    /** Written on the worker as the fires arrive, read there as the basins do. */
    private List<DebrisFlow.Fire> fires = new ArrayList<>();
    private volatile int firesNational;

    private final Runnable settled = new Runnable() {
        @Override
        public void run() {
            if (!isOn())
                return;
            if (needsNewAsk())
                refresh(true);
        }
    };

    private final AtakMapView.OnMapMovedListener moved = new AtakMapView.OnMapMovedListener() {
        @Override
        public void onMapMoved(AtakMapView v, boolean animate) {
            // The GL thread; nothing of the map or the pane is touched here.
            mapView.removeCallbacks(settled);
            mapView.postDelayed(settled, SETTLE_MS);
        }
    };

    public DebrisFlowOverlay(MapView mapView, Context pluginContext, EgressPolicy egress) {
        super(mapView, pluginContext, egress, TAG, LAYER_ID, "Debris Flow");
        icons = new DotIcons(GeneratedFiles.icons("dots"));
        urls.add(DebrisFlow.FIRES_URL);
    }

    @Override
    public void start() {
        super.start();
        mapView.addOnMapMovedListener(moved);
    }

    @Override
    public void stop() {
        mapView.removeOnMapMovedListener(moved);
        mapView.removeCallbacks(settled);
        super.stop();
    }

    @Override
    public void setOn(boolean value) {
        super.setOn(value);
        if (!value) {
            asked = null;
            dirty = false;
        }
    }

    /** What a fire's marker carries so its details page can bring the map to it. */
    static String fireRef(DebrisFlow.Fire f) {
        return "fire:" + (f.id.isEmpty() ? String.format(Locale.US, "%.4f,%.4f", f.lat, f.lon) : f.id);
    }

    /** Screen meters per pixel that puts a burn's basins on screen: a 5 to 10 mile scale bar. */
    private static final double FIRE_VIEW_RESOLUTION = 30d;

    /**
     * Brings the map to a fire from its details page, close enough that the layer
     * fetches and draws its perimeter and basins once the map settles (operator,
     * 2026-10-12: "at first it is just the icon ... then in there you have a button
     * for show the area for that incident").
     */
    public boolean showFire(String ref) {
        if (ref == null || !isOn())
            return false;
        for (DebrisFlow.Fire f : fires)
            if (ref.equals(fireRef(f))) {
                try {
                    mapView.getMapController().panZoomTo(new GeoPoint(f.lat, f.lon),
                            mapView.mapResolutionAsMapScale(FIRE_VIEW_RESOLUTION), true);
                } catch (LinkageError | RuntimeException e) {
                    mapView.getMapController().panTo(new GeoPoint(f.lat, f.lon), true);
                }
                return true;
            }
        return false;
    }

    /** The map's extent, {west, south, east, north}, or null when it cannot be read. */
    private double[] extent() {
        final GeoBounds b = mapView.getBounds();
        if (b == null)
            return null;
        final double w = b.getWest(), s = b.getSouth(), e = b.getEast(), n = b.getNorth();
        if (Double.isNaN(w) || Double.isNaN(s) || Double.isNaN(e) || Double.isNaN(n) || e <= w
                || n <= s)
            return null;
        return new double[] { w, s, e, n };
    }

    private boolean needsNewAsk() {
        final double[] x = extent();
        if (x == null)
            return false;
        final boolean wide = x[2] - x[0] > MAX_SPAN_LON;
        if (wide != zoomedOut)
            return true;
        if (wide)
            return false;
        final double[] a = asked;
        return a == null || x[0] < a[0] || x[1] < a[1] || x[2] > a[2] || x[3] > a[3];
    }

    @Override
    protected void onRefreshStart() {
        urls.clear();
        urls.add(DebrisFlow.FIRES_URL);
        received = 0;
        dirty = false;
        final double[] x = extent();
        zoomedOut = x == null || x[2] - x[0] > MAX_SPAN_LON;
        if (zoomedOut) {
            asked = null;
            return;
        }
        // Half the view again on every side, so a nudge does not ask again.
        final double padX = (x[2] - x[0]) * 0.5, padY = (x[3] - x[1]) * 0.5;
        final double[] box = { Math.max(-180, x[0] - padX), Math.max(-89, x[1] - padY),
                Math.min(180, x[2] + padX), Math.min(89, x[3] + padY) };
        asked = box;
        final String since = TornadoPaths.ymd(System.currentTimeMillis() - DebrisFlow.KEEP_MS);
        urls.add(DebrisFlow.perimetersUrl(box[0], box[1], box[2], box[3], since));
        urls.add(DebrisFlow.basinsUrl(box[0], box[1], box[2], box[3], since, 0));
    }

    @Override
    protected void onRefreshDone() {
        if (dirty) {
            dirty = false;
            refresh(true);
        }
    }

    @Override
    public void refresh(boolean force) {
        // A move that lands while an answer is on its way is not lost: it is asked
        // for once this one is drawn.
        if (force && isOn() && needsNewAsk())
            dirty = true;
        super.refresh(force);
    }

    @Override
    protected String[] urls() {
        return urls.toArray(new String[0]);
    }

    @Override
    protected boolean optional(int index) {
        return index > 0;
    }

    @Override
    protected boolean large(int index) {
        return index >= 2;
    }

    @Override
    protected long pollMs() {
        return 60 * 60 * 1000L;
    }

    @Override
    public int days() {
        return 1;
    }

    @Override
    public boolean allDays() {
        return false;
    }

    @Override
    protected String setName(Area a) {
        return a.label;
    }

    @Override
    protected int fillAlpha(Area a) {
        return PERIMETER.equals(a.label) ? 0 : 0x66;
    }

    @Override
    protected List<Area> parse(int index, String body) {
        final List<Area> out = new ArrayList<>();
        final long now = System.currentTimeMillis();
        if (index == 0) {
            final List<DebrisFlow.Fire> all = DebrisFlow.parseFires(body);
            final List<DebrisFlow.Fire> kept = new ArrayList<>();
            for (DebrisFlow.Fire f : all)
                if (f.start > 0 && now - f.start <= DebrisFlow.KEEP_MS)
                    kept.add(f);
            fires = kept;
            firesNational = kept.size();
            for (DebrisFlow.Fire f : kept) {
                final String title = f.name + " (" + year(f.start) + ")";
                final StringBuilder d = new StringBuilder();
                d.append(f.name).append(" fire, ").append(year(f.start));
                if (!f.location.isEmpty())
                    d.append("\nWhere: ").append(f.location);
                d.append("\nStarted: ").append(date(f.start))
                        .append("\nAssessed: ").append(date(f.assessed));
                if (!f.status.isEmpty())
                    d.append("\nUSGS: ").append(f.status);
                if (!f.rainfall.isEmpty())
                    d.append("\nDesign storm: a 15-minute burst at ")
                            .append(DebrisFlow.rainfallWords(f.rainfall));
                d.append("\nShow the area draws the perimeter and the basins, each filled by how"
                        + " likely a debris flow is and how much it would carry in that storm.");
                if (!f.url.isEmpty())
                    d.append("\nUSGS record: ").append(f.url);
                d.append("\nFrom: USGS Landslide Hazards Program, emergency assessment");
                final JSONObject g = new JSONObject();
                try {
                    g.put("type", "Point");
                    g.put("coordinates", new JSONArray().put(f.lon).put(f.lat));
                } catch (Exception e) {
                    continue;
                }
                out.add(new Area(1, title, FIRES, DebrisFlow.FIRE_COLOR, g, d.toString(),
                        fireRef(f), f));
            }
            return out;
        }
        if (index == 1) {
            for (DebrisFlow.Perimeter p : DebrisFlow.parsePerimeters(body)) {
                final double[] at = DebrisFlow.firstPoint(p.geometry);
                final DebrisFlow.Fire f = at == null ? null
                        : DebrisFlow.fireFor(fires, p.start, p.assessed, at[0], at[1]);
                final String name = f == null ? p.fireId : f.name + " (" + year(f.start) + ")";
                out.add(new Area(1, name + " perimeter", PERIMETER, DebrisFlow.FIRE_COLOR,
                        p.geometry, "Burned area the USGS assessed: " + name
                                + "\nFrom: USGS Landslide Hazards Program", "", p));
            }
            return out;
        }
        final List<DebrisFlow.Basin> got = DebrisFlow.parseBasins(body);
        received += got.size();
        if (!got.isEmpty() && com.atakmap.android.atmosphere.data.StormReports.exceeded(body)) {
            final double[] a = asked;
            if (a != null)
                urls.add(DebrisFlow.basinsUrl(a[0], a[1], a[2], a[3],
                        TornadoPaths.ymd(now - DebrisFlow.KEEP_MS), received));
        }
        for (DebrisFlow.Basin b : got) {
            if (b.hazard < 0)
                continue;
            final double[] at = DebrisFlow.firstPoint(b.geometry);
            final DebrisFlow.Fire f = at == null ? null
                    : DebrisFlow.fireFor(fires, b.start, b.assessed, at[0], at[1]);
            final String hazard = DebrisFlow.HAZARDS[b.hazard];
            final StringBuilder d = new StringBuilder();
            d.append("Combined debris-flow hazard: ").append(hazard);
            if (!b.likelihood.isEmpty())
                d.append("\nLikelihood of a debris flow: ").append(b.likelihood);
            if (!b.volume.isEmpty())
                d.append("\nVolume if one runs: ").append(b.volume).append(" cubic meters");
            d.append("\nFor a 15-minute burst of rain at ")
                    .append(f == null || f.rainfall.isEmpty() ? "the design intensity"
                            : DebrisFlow.rainfallWords(f.rainfall))
                    .append("; a harder burst means more");
            if (f != null)
                d.append("\nFire: ").append(f.name).append(", started ").append(date(f.start))
                        .append("; assessed ").append(date(f.assessed));
            else
                d.append("\nFire: ").append(b.fireId).append(", assessed ").append(date(b.assessed));
            d.append("\nFrom: USGS Landslide Hazards Program, emergency assessment, preliminary");
            out.add(new Area(1, hazard + " hazard basin", hazard, DebrisFlow.COLORS[b.hazard],
                    b.geometry, d.toString(), "", b));
        }
        return out;
    }

    @Override
    protected AtmosphereFeatures.Drawn drawn(Area a, Geometry g, AttributeSet s) {
        if (a.extra instanceof DebrisFlow.Fire) {
            final Icon icon = icons.labelled(a.title, a.color, 0xFFFFFFFF, 14);
            if (icon == null)
                return null;
            return new AtmosphereFeatures.Drawn(setName(a), a.title, g,
                    AtmosphereFeatures.icon(icon.getImageUri(Icon.STATE_DEFAULT), icon.getWidth(),
                            icon.getHeight()), s);
        }
        if (a.extra instanceof DebrisFlow.Perimeter)
            return new AtmosphereFeatures.Drawn(setName(a), a.title, g,
                    AtmosphereFeatures.area(a.color, 2.5f, 0), s);
        // A basin: filled in its hazard color, no label; hundreds share a view.
        final int fill = (fillAlpha(a) << 24) | (a.color & 0x00FFFFFF);
        return new AtmosphereFeatures.Drawn(setName(a), a.title, g,
                AtmosphereFeatures.area(a.color, 1.2f, fill), s);
    }

    @Override
    protected String summary(List<Area> areas) {
        final StringBuilder b = new StringBuilder();
        final Set<String> inView = new LinkedHashSet<>();
        int[] counts = new int[3];
        int basins = 0;
        final double[] a = asked;
        for (Area x : areas) {
            if (x.extra instanceof DebrisFlow.Basin) {
                basins++;
                counts[((DebrisFlow.Basin) x.extra).hazard]++;
            } else if (x.extra instanceof DebrisFlow.Fire && a != null) {
                final DebrisFlow.Fire f = (DebrisFlow.Fire) x.extra;
                if (f.lon >= a[0] && f.lon <= a[2] && f.lat >= a[1] && f.lat <= a[3])
                    inView.add(f.name);
            }
        }
        if (zoomedOut)
            b.append("Zoom in to see the basins. ").append(firesNational)
                    .append(" fires assessed in the last three years are marked.");
        else if (basins == 0)
            b.append("No post-fire assessment here. ").append(firesNational)
                    .append(" fires assessed in the last three years are marked.");
        else {
            if (!inView.isEmpty()) {
                b.append("Fires here: ");
                int i = 0;
                for (String n : inView) {
                    if (i++ == 4) {
                        b.append(" and ").append(inView.size() - 4).append(" more");
                        break;
                    }
                    b.append(i > 1 ? ", " : "").append(n);
                }
                b.append(". ");
            }
            b.append(basins).append(" basins: ").append(counts[2]).append(" high, ")
                    .append(counts[1]).append(" moderate, ").append(counts[0]).append(" low.");
        }
        b.append("\nHazard for one 15-minute burst of rain at the rate the USGS set for each fire;"
                + " the burn stays hazardous for two to five years.");
        return b.toString();
    }

    @Override
    protected String noun() {
        return "debris-flow hazard";
    }

    private static String year(long millis) {
        return millis <= 0 ? "" : new SimpleDateFormat("yyyy", Locale.US).format(new Date(millis));
    }

    private static String date(long millis) {
        return millis <= 0 ? "unknown"
                : new SimpleDateFormat("MMM d, yyyy", Locale.US).format(new Date(millis));
    }
}

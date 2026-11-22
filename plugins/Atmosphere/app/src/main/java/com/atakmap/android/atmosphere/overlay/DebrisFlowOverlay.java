package com.atakmap.android.atmosphere.overlay;

import android.content.Context;
import com.atakmap.android.atmosphere.compat.MapCompat;
import android.content.SharedPreferences;

import com.atakmap.android.atmosphere.compat.GeneratedFiles;
import com.atakmap.android.atmosphere.data.DebrisFlow;
import com.atakmap.android.atmosphere.net.EgressPolicy;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.maps.assets.Icon;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.map.layer.feature.AttributeSet;
import com.atakmap.map.layer.feature.geometry.Geometry;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
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
 * <p>The fires are one national request and the only thing the layer draws on its
 * own. The basins are 86,000 polygons nationally and come in for one fire at a
 * time, when its page's Show the area asks for them, by the fire's id; the page
 * hides them again. Nothing about the phone leaves (operator, 2026-10-12: "these
 * do not come on automatically by any means no zoom etc ... just the dots show up").
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
    private static final String PREF_SHOWN = "weather.layer.debrisflow.shown";
    /** The fires whose perimeter and basins are drawn, by id, in the order asked. */
    private final List<String> shown = new CopyOnWriteArrayList<>();
    /** Basins received so far per fire, for the service's 2,000-row pages. */
    private final Map<String, Integer> received = new ConcurrentHashMap<>();
    /** Written on the worker as the fires arrive, read there as the basins do. */
    private List<DebrisFlow.Fire> fires = new ArrayList<>();
    private volatile int firesNational;

    public DebrisFlowOverlay(MapView mapView, Context pluginContext, EgressPolicy egress) {
        super(mapView, pluginContext, egress, TAG, LAYER_ID, "Debris Flow");
        icons = new DotIcons(GeneratedFiles.icons("dots"));
        urls.add(DebrisFlow.FIRES_URL);
        final SharedPreferences prefs = MapCompat.prefs();
        final String saved = prefs == null ? "" : prefs.getString(PREF_SHOWN, "");
        for (String id : saved.split("\\|"))
            if (!id.isEmpty())
                shown.add(id);
    }

    private void rememberShown() {
        final SharedPreferences prefs = MapCompat.prefs();
        if (prefs == null)
            return;
        final StringBuilder b = new StringBuilder();
        for (String id : shown)
            b.append(b.length() > 0 ? "|" : "").append(id);
        prefs.edit().putString(PREF_SHOWN, b.toString()).apply();
    }

    /** What a fire's marker carries so its details page can show or hide its area. */
    static String fireRef(DebrisFlow.Fire f) {
        return "fire:" + (f.id.isEmpty() ? String.format(Locale.US, "%.4f,%.4f", f.lat, f.lon) : f.id);
    }

    /** Screen meters per pixel that puts a burn's basins on screen: a 5 to 10 mile scale bar. */
    private static final double FIRE_VIEW_RESOLUTION = 30d;

    private DebrisFlow.Fire fireFor(String ref) {
        if (ref == null)
            return null;
        for (DebrisFlow.Fire f : fires)
            if (ref.equals(fireRef(f)))
                return f;
        return null;
    }

    /** Whether a fire's area is drawn, for its page's button. */
    public boolean isFireShown(String ref) {
        final DebrisFlow.Fire f = fireFor(ref);
        return f != null && shown.contains(f.id);
    }

    /**
     * Show the area, from a fire's page: the map goes to the burn and its perimeter
     * and basins are fetched by the fire's id and drawn, beside any other fire already
     * shown. Hide the area takes them off again. The set is remembered.
     */
    public boolean showFire(String ref, boolean show) {
        final DebrisFlow.Fire f = fireFor(ref);
        if (f == null || f.id.isEmpty() || !isOn())
            return false;
        if (show && !shown.contains(f.id)) {
            shown.add(f.id);
            try {
                mapView.getMapController().panZoomTo(new GeoPoint(f.lat, f.lon),
                        mapView.mapResolutionAsMapScale(FIRE_VIEW_RESOLUTION), true);
            } catch (LinkageError | RuntimeException e) {
                mapView.getMapController().panTo(new GeoPoint(f.lat, f.lon), true);
            }
        } else if (!show)
            shown.remove(f.id);
        rememberShown();
        refresh(true);
        return true;
    }

    @Override
    protected void onRefreshStart() {
        urls.clear();
        received.clear();
        urls.add(DebrisFlow.FIRES_URL);
        for (String id : shown) {
            urls.add(DebrisFlow.perimetersForFireUrl(id));
            urls.add(DebrisFlow.basinsForFireUrl(id, 0));
        }
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
        return index > 0;
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
                d.append("\nShow the area draws this fire's perimeter and basins, each filled by"
                        + " how likely a debris flow is and how much it would carry in that storm;"
                        + " Hide the area takes them off.");
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
        final String askedUrl = index < urls.size() ? urls.get(index) : "";
        if (askedUrl.contains("/9/query")) {
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
        final String fireId = DebrisFlow.fireIdOf(askedUrl);
        final int have = (received.containsKey(fireId) ? received.get(fireId) : 0) + got.size();
        received.put(fireId, have);
        if (!got.isEmpty() && !fireId.isEmpty()
                && com.atakmap.android.atmosphere.data.StormReports.exceeded(body))
            urls.add(DebrisFlow.basinsForFireUrl(fireId, have));
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
        final Set<String> names = new LinkedHashSet<>();
        int[] counts = new int[3];
        int basins = 0;
        for (Area x : areas)
            if (x.extra instanceof DebrisFlow.Basin) {
                basins++;
                counts[((DebrisFlow.Basin) x.extra).hazard]++;
            }
        for (DebrisFlow.Fire f : fires)
            if (shown.contains(f.id))
                names.add(f.name);
        b.append(firesNational).append(" fires assessed in the last three years are marked.");
        if (names.isEmpty())
            b.append(" Tap a fire, then Show the area for its basins.");
        else {
            b.append(" Showing ");
            int i = 0;
            for (String n : names)
                b.append(i++ > 0 ? ", " : "").append(n);
            b.append(": ").append(basins).append(" basins, ").append(counts[2]).append(" high, ")
                    .append(counts[1]).append(" moderate, ").append(counts[0])
                    .append(" low. A fire's page hides its area again.");
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

package com.atakmap.android.atmosphere.overlay;

import android.content.Context;

import com.atakmap.android.atmosphere.compat.GeneratedFiles;
import com.atakmap.android.atmosphere.data.UsgsRain;
import com.atakmap.android.atmosphere.net.EgressPolicy;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.maps.assets.Icon;
import com.atakmap.coremap.maps.coords.GeoBounds;
import com.atakmap.map.AtakMapView;
import com.atakmap.map.layer.feature.AttributeSet;
import com.atakmap.map.layer.feature.geometry.Geometry;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * USGS rain gauges on the map: a dot per gauge in the color of its last 24 hours,
 * the same bins and colors the Rain Totals picture uses, with the total in the
 * pill. Asked for the map's extent, padded, and asked again when the map settles
 * outside it; over about eight degrees wide the status says to zoom in. The
 * map's extent leaves the device, the position does not.
 *
 * <p>Sparse where the USGS runs few gauges; the NWS office's own rainfall report,
 * on the Rain Gauges page, is the fuller table, and the two are shown together.
 */
public final class RainGaugesOverlay extends OutlookOverlay {

    public static final String LAYER_ID = "raingauges";
    public static final String HOST = UsgsRain.HOST;
    private static final String TAG = "AtmosphereRainGauges";
    private static final double MAX_SPAN_LON = 8.0;
    private static final long SETTLE_MS = 800;

    /** The key: the Rain Totals colors at a few steps. */
    public static final String[][] LEGEND = {
            { "No rain in 24 h", "#FFB0BEC5" }, { "Under 0.25 in", "#FF00A0FF" },
            { "0.25 to 1 in", "#FF00DC00" }, { "1 to 2 in", "#FF008D00" },
            { "2 to 4 in", "#FFE7B400" }, { "4 in or more", "#FFE60000" } };

    public static int color(double inches) {
        if (inches < 0.005)
            return 0xFFB0BEC5;
        if (inches < 0.25)
            return 0xFF00A0FF;
        if (inches < 1)
            return 0xFF00DC00;
        if (inches < 2)
            return 0xFF008D00;
        if (inches < 4)
            return 0xFFE7B400;
        return 0xFFE60000;
    }

    private final DotIcons icons;
    private volatile String url = "";
    private volatile double[] asked;
    private volatile boolean zoomedOut = true;
    private volatile boolean dirty;
    private List<UsgsRain.Gauge> gauges = new ArrayList<>();

    private final Runnable settled = new Runnable() {
        @Override
        public void run() {
            if (isOn() && needsNewAsk())
                refresh(true);
        }
    };

    private final AtakMapView.OnMapMovedListener moved = new AtakMapView.OnMapMovedListener() {
        @Override
        public void onMapMoved(AtakMapView v, boolean animate) {
            mapView.removeCallbacks(settled);
            mapView.postDelayed(settled, SETTLE_MS);
        }
    };

    public RainGaugesOverlay(MapView mapView, Context pluginContext, EgressPolicy egress) {
        super(mapView, pluginContext, egress, TAG, LAYER_ID, "Rain Gauges");
        icons = new DotIcons(GeneratedFiles.icons("dots"));
    }

    /** The gauges held, most rain first. Main thread. */
    public List<UsgsRain.Gauge> gauges() {
        final List<UsgsRain.Gauge> out = new ArrayList<>(gauges);
        Collections.sort(out, new Comparator<UsgsRain.Gauge>() {
            @Override
            public int compare(UsgsRain.Gauge a, UsgsRain.Gauge b) {
                return Double.compare(b.total, a.total);
            }
        });
        return out;
    }

    public boolean zoomedOut() {
        return zoomedOut;
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
            gauges = new ArrayList<>();
        }
    }

    private double[] extent() {
        final GeoBounds b = mapView.getBounds();
        if (b == null)
            return null;
        final double w = b.getWest(), s = b.getSouth(), e = b.getEast(), n = b.getNorth();
        if (Double.isNaN(w) || Double.isNaN(s) || Double.isNaN(e) || Double.isNaN(n) || e <= w || n <= s)
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
        dirty = false;
        final double[] x = extent();
        zoomedOut = x == null || x[2] - x[0] > MAX_SPAN_LON;
        if (zoomedOut) {
            asked = null;
            url = "";
            return;
        }
        final double padX = (x[2] - x[0]) * 0.5, padY = (x[3] - x[1]) * 0.5;
        final double[] box = { Math.max(-180, x[0] - padX), Math.max(-89, x[1] - padY),
                Math.min(180, x[2] + padX), Math.min(89, x[3] + padY) };
        asked = box;
        url = UsgsRain.url(box[0], box[1], box[2], box[3]);
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
        if (force && isOn() && needsNewAsk())
            dirty = true;
        super.refresh(force);
    }

    @Override
    protected String[] urls() {
        final String u = url;
        return u.isEmpty() ? new String[0] : new String[] { u };
    }

    @Override
    protected long pollMs() {
        return 15 * 60 * 1000L;
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
        return "USGS Rain Gauges";
    }

    @Override
    protected List<Area> parse(int index, String body) {
        final List<Area> out = new ArrayList<>();
        final List<UsgsRain.Gauge> got = UsgsRain.parse(body);
        gauges = got;
        for (UsgsRain.Gauge g : got) {
            final JSONObject geom = new JSONObject();
            try {
                geom.put("type", "Point");
                geom.put("coordinates", new JSONArray().put(g.lon).put(g.lat));
            } catch (Exception e) {
                continue;
            }
            out.add(new Area(1, inches(g.total) + " - " + g.name, "USGS", color(g.total), geom,
                    details(g), "", g));
        }
        return out;
    }

    /** What a tap shows: the total, then the day hour by hour, oldest first. */
    public static String details(UsgsRain.Gauge g) {
        final StringBuilder d = new StringBuilder();
        d.append("Last 24 h: ").append(inches(g.total)).append('\n').append(g.name);
        if (g.latest > 0)
            d.append("\nNewest reading: ").append(when(g.latest));
        if (g.hourly.length > 0) {
            d.append("\nBy the hour:");
            for (int i = 0; i < g.hourly.length; i++)
                if (g.hourly[i] > 0.004)
                    d.append('\n').append(new SimpleDateFormat("EEE h a", Locale.US)
                            .format(new Date(g.hourStarts[i]))).append("  ").append(inches(g.hourly[i]));
        }
        d.append("\nUSGS site ").append(g.site).append('\n').append(g.url())
                .append("\nFrom: USGS National Water Information System, provisional");
        return d.toString();
    }

    public static String inches(double v) {
        return v < 0.005 ? "0.00 in" : String.format(Locale.US, "%.2f in", v);
    }

    @Override
    protected AtmosphereFeatures.Drawn drawn(Area a, Geometry g, AttributeSet s) {
        if (!(a.extra instanceof UsgsRain.Gauge))
            return null;
        final UsgsRain.Gauge gauge = (UsgsRain.Gauge) a.extra;
        final Icon icon = icons.labelled(inches(gauge.total), a.color, 0xFFFFFFFF, 14);
        if (icon == null)
            return null;
        return new AtmosphereFeatures.Drawn(setName(a), a.title, g,
                AtmosphereFeatures.icon(icon.getImageUri(Icon.STATE_DEFAULT), icon.getWidth(),
                        icon.getHeight()), s);
    }

    @Override
    protected String summary(List<Area> areas) {
        if (zoomedOut)
            return "Zoom in to see the rain gauges.";
        int n = 0;
        UsgsRain.Gauge top = null;
        for (Area a : areas)
            if (a.extra instanceof UsgsRain.Gauge) {
                n++;
                final UsgsRain.Gauge g = (UsgsRain.Gauge) a.extra;
                if (top == null || g.total > top.total)
                    top = g;
            }
        if (n == 0)
            return "No USGS rain gauges around here. The NWS office's report on the Rain Gauges page"
                    + " may list others.";
        final StringBuilder b = new StringBuilder();
        b.append(n).append(n == 1 ? " USGS gauge" : " USGS gauges").append(" around the map, last 24 h.");
        if (top != null && top.total > 0.004)
            b.append(" Most: ").append(inches(top.total)).append(" at ").append(top.name).append('.');
        b.append("\nUSGS runs few gauges in some areas; the office's report lists the rest.");
        return b.toString();
    }

    @Override
    protected String noun() {
        return "rain gauges";
    }

    static String when(long millis) {
        return new SimpleDateFormat("EEE h:mm a", Locale.US).format(new Date(millis));
    }
}

package com.atakmap.android.atmosphere.overlay;

import android.content.Context;

import com.atakmap.android.atmosphere.compat.GeneratedFiles;
import com.atakmap.android.atmosphere.data.CocoRahs;
import com.atakmap.android.atmosphere.data.States;
import com.atakmap.android.atmosphere.data.UsgsRain;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;
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
import java.util.concurrent.CopyOnWriteArrayList;
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
            { "2 to 4 in", "#FFE7B400" }, { "4 in or more", "#FFE60000" },
            { "White ring: USGS gauge, last 24 h. Dark ring: the office's report. Cyan ring:"
                    + " CoCoRaHS volunteer, 24 h to about 7 am", "0" } };
    private static final int COCORAHS_RING = 0xFF00E5FF;

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

    /** A gauge from the forecast office's rainfall report, placed by the Rain Gauges page. */
    public static final class ReportGauge {
        public final String name, details;
        public final double lat, lon, inches;

        public ReportGauge(String name, double lat, double lon, double inches, String details) {
            this.name = name;
            this.lat = lat;
            this.lon = lon;
            this.inches = inches;
            this.details = details;
        }
    }

    private volatile List<ReportGauge> reportGauges = new ArrayList<>();
    private volatile String reportOffice = "";
    private final DotIcons icons;
    private volatile String url = "";
    /** The requests of one refresh: the USGS box, then CoCoRaHS today and yesterday. */
    private final List<String> chain = new CopyOnWriteArrayList<>();
    private final Context pluginContext;
    private States states;
    private volatile String state = "";
    private volatile int cocorahsCount;
    private List<CocoRahs.Report> cocorahsSeen = new ArrayList<>();
    private volatile double[] asked;
    private volatile boolean zoomedOut = true;
    private volatile boolean dirty;
    private List<UsgsRain.Gauge> gauges = new ArrayList<>();

    private final Runnable settled = new Runnable() {
        @Override
        public void run() {
            if (isOn() && (needsNewAsk() || stateChanged()))
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
        this.pluginContext = pluginContext;
        icons = new DotIcons(GeneratedFiles.icons("dots"));
    }

    private States loadStates() {
        try (java.io.InputStream in = pluginContext.getAssets().open("us_states.json")) {
            final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            final byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0)
                out.write(buf, 0, n);
            return States.parse(out.toString("UTF-8"));
        } catch (Exception e) {
            Log.w(TAG, "state outlines unreadable", e);
            return null;
        }
    }

    private static final int MAX_STATES = 4;

    /**
     * The states under the map: the center first, then the corners of the view, so a
     * map on a state line draws both sides (operator, 2026-10-12: "we need gauges for
     * all 50 states"). At most four; "" at sea or abroad. Comma-joined so one string
     * compares.
     */
    private String statesUnderMap() {
        if (states == null)
            states = loadStates();
        if (states == null)
            return "";
        final List<String> out = new ArrayList<>();
        final GeoPoint c = mapView.getCenterPoint().get();
        if (c != null && c.isValid()) {
            final String s = states.at(c.getLatitude(), c.getLongitude());
            if (s != null && !s.isEmpty())
                out.add(s);
        }
        final double[] x = extent();
        if (x != null && x[2] - x[0] <= MAX_SPAN_LON) {
            final double[][] corners = { { x[3], x[0] }, { x[3], x[2] }, { x[1], x[0] }, { x[1], x[2] } };
            for (double[] k : corners) {
                final String s = states.at(k[0], k[1]);
                if (s != null && !s.isEmpty() && !out.contains(s) && out.size() < MAX_STATES)
                    out.add(s);
            }
        }
        final StringBuilder b = new StringBuilder();
        for (String s : out)
            b.append(b.length() > 0 ? "," : "").append(s);
        return b.toString();
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

    /** The office's report gauges, placed; drawn beside the USGS ones while the layer is on. */
    public void setReportGauges(String office, List<ReportGauge> gauges) {
        reportOffice = office == null ? "" : office;
        reportGauges = new ArrayList<>(gauges);
        redraw();
    }

    @Override
    protected List<Area> extraAreas() {
        final List<Area> out = new ArrayList<>();
        for (ReportGauge g : reportGauges) {
            final JSONObject geom = new JSONObject();
            try {
                geom.put("type", "Point");
                geom.put("coordinates", new JSONArray().put(g.lon).put(g.lat));
            } catch (Exception e) {
                continue;
            }
            final double v = Double.isNaN(g.inches) ? 0 : g.inches;
            out.add(new Area(1, (Double.isNaN(g.inches) ? "Trace" : inches(g.inches)) + " - " + g.name,
                    "NWS Report", color(v), geom, g.details, "", g));
        }
        return out;
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
        cocorahsSeen = new ArrayList<>();
        chain.clear();
        final double[] x = extent();
        zoomedOut = x == null || x[2] - x[0] > MAX_SPAN_LON;
        if (zoomedOut) {
            asked = null;
            url = "";
        } else {
            final double padX = (x[2] - x[0]) * 0.5, padY = (x[3] - x[1]) * 0.5;
            final double[] box = { Math.max(-180, x[0] - padX), Math.max(-89, x[1] - padY),
                    Math.min(180, x[2] + padX), Math.min(89, x[3] + padY) };
            asked = box;
            url = UsgsRain.url(box[0], box[1], box[2], box[3]);
            chain.add(url);
        }
        // The volunteers' reports for the state under the map, today's and yesterday's:
        // a report is read around 7 am, so before mid-morning today's file is thin.
        final String st = statesUnderMap();
        state = st;
        if (!st.isEmpty()) {
            final long now = System.currentTimeMillis();
            for (String one : st.split(",")) {
                chain.add(CocoRahs.url(one, new Date(now)));
                chain.add(CocoRahs.url(one, new Date(now - 24L * 60 * 60 * 1000)));
            }
        }
    }

    /** Whether the map has moved over another set of states since the last ask. */
    private boolean stateChanged() {
        final String st = statesUnderMap();
        return !st.isEmpty() && !st.equals(state);
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
        if (force && isOn() && (needsNewAsk() || stateChanged()))
            dirty = true;
        super.refresh(force);
    }

    @Override
    protected String[] urls() {
        return chain.toArray(new String[0]);
    }

    @Override
    protected boolean optional(int index) {
        return true;
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
        return a.extra instanceof ReportGauge ? "NWS Report Gauges"
                : a.extra instanceof CocoRahs.Report ? "CoCoRaHS Gauges" : "USGS Rain Gauges";
    }

    @Override
    protected List<Area> parse(int index, String body) {
        final List<Area> out = new ArrayList<>();
        final String asked = index < chain.size() ? chain.get(index) : "";
        if (asked.contains(CocoRahs.HOST))
            return parseCocoRahs(body);
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

    /**
     * Today's file then yesterday's: the second answer only adds stations the first did
     * not have, so a station that reported both mornings draws today's.
     */
    private List<Area> parseCocoRahs(String body) {
        final List<Area> out = new ArrayList<>();
        final List<CocoRahs.Report> got = CocoRahs.parse(body);
        final java.util.Set<String> have = new java.util.HashSet<>();
        for (CocoRahs.Report r : cocorahsSeen)
            have.add(r.station);
        for (CocoRahs.Report r : got) {
            if (!have.add(r.station))
                continue;
            cocorahsSeen.add(r);
            final JSONObject geom = new JSONObject();
            try {
                geom.put("type", "Point");
                geom.put("coordinates", new JSONArray().put(r.lon).put(r.lat));
            } catch (Exception e) {
                continue;
            }
            final String amount = r.trace ? "Trace" : inches(r.inches);
            final StringBuilder d = new StringBuilder();
            d.append(amount).append(" in the 24 hours to ").append(r.time).append(' ').append(r.date);
            d.append('\n').append(r.name).append(" (").append(r.station).append(')');
            d.append("\nRead by a CoCoRaHS volunteer from a 4-inch gauge; the network asks that"
                    + " its reports be credited.");
            d.append("\nFrom: CoCoRaHS, the Community Collaborative Rain, Hail and Snow Network");
            out.add(new Area(1, amount + " - " + r.name, "CoCoRaHS", color(r.inches), geom,
                    d.toString(), "", r));
        }
        cocorahsCount = cocorahsSeen.size();
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
        if (a.extra instanceof ReportGauge) {
            final ReportGauge r = (ReportGauge) a.extra;
            final Icon icon = icons.labelled(Double.isNaN(r.inches) ? "T" : inches(r.inches), a.color,
                    0xFF202020, 12);
            if (icon == null)
                return null;
            return new AtmosphereFeatures.Drawn(setName(a), a.title, g,
                    AtmosphereFeatures.icon(icon.getImageUri(Icon.STATE_DEFAULT), icon.getWidth(),
                            icon.getHeight()), s);
        }
        if (a.extra instanceof CocoRahs.Report) {
            final CocoRahs.Report r = (CocoRahs.Report) a.extra;
            final Icon icon = icons.labelled(r.trace ? "T" : inches(r.inches), a.color,
                    COCORAHS_RING, 14);
            if (icon == null)
                return null;
            return new AtmosphereFeatures.Drawn(setName(a), a.title, g,
                    AtmosphereFeatures.icon(icon.getImageUri(Icon.STATE_DEFAULT), icon.getWidth(),
                            icon.getHeight()), s);
        }
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
        int n = 0, volunteers = 0;
        UsgsRain.Gauge top = null;
        CocoRahs.Report wettest = null;
        for (Area a : areas) {
            if (a.extra instanceof CocoRahs.Report) {
                volunteers++;
                final CocoRahs.Report r = (CocoRahs.Report) a.extra;
                if (wettest == null || r.inches > wettest.inches)
                    wettest = r;
            }
            if (a.extra instanceof UsgsRain.Gauge) {
                n++;
                final UsgsRain.Gauge g = (UsgsRain.Gauge) a.extra;
                if (top == null || g.total > top.total)
                    top = g;
            }
        }
        final int fromReport = reportGauges.size();
        final StringBuilder b = new StringBuilder();
        if (volunteers > 0) {
            b.append(volunteers).append(" CoCoRaHS volunteer gauges in ")
                    .append(state.replace(",", " and ")).append(", 24 h to about 7 am, cyan-ringed.");
            if (wettest != null && wettest.inches > 0.004)
                b.append(" Most: ").append(inches(wettest.inches)).append(" at ")
                        .append(wettest.name).append('.');
            b.append(' ');
        } else if (!state.isEmpty())
            b.append("No CoCoRaHS volunteer reports in ").append(state.replace(",", " or "))
                    .append(" today or yesterday. ");
        if (zoomedOut)
            b.append("Zoom in for the USGS gauges.");
        else if (n == 0)
            b.append("No USGS rain gauges around here.");
        else {
            b.append(n).append(n == 1 ? " USGS gauge" : " USGS gauges").append(" around the map, last 24 h.");
            if (top != null && top.total > 0.004)
                b.append(" Most: ").append(inches(top.total)).append(" at ").append(top.name).append('.');
        }
        if (fromReport > 0)
            b.append(" Plus ").append(fromReport).append(" from the ").append(reportOffice)
                    .append(" office's report, dark-ringed.");
        else
            b.append("\nOpen the Rain Gauges page and the office's report gauges draw here too.");
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

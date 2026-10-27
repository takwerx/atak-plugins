package com.atakmap.android.atmosphere.overlay;

import android.content.Context;
import android.content.SharedPreferences;

import com.atakmap.android.atmosphere.compat.GeneratedFiles;
import com.atakmap.android.atmosphere.compat.MapCompat;
import com.atakmap.android.atmosphere.data.Acis;
import com.atakmap.android.atmosphere.data.Iem;
import com.atakmap.android.atmosphere.data.IemRelay;
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

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Locale;

/**
 * Rain gauges on the map: a dot per gauge in the color of its total, the same bins and
 * colors the Rain Totals picture uses, with the total in the pill.
 *
 * <p>Hours ending at a moment (the last 24 h, or before a date and time) are exact:
 * every USGS gauge's readings, the airports' hourly reports and the county, RAWS and
 * agency gauges of the NWS feed, from the Iowa Environmental Mesonet through takwerx's
 * relay, which keeps their hours 45 days; before that the agency gauges show whole days
 * and the airports are left out. A run of days,
 * from the last three to since January 1 or one day picked, is the airports and NWS
 * observers of the climate record for the states under the map, added up by the
 * Regional Climate Centers, the mesonet's daily totals for the agency gauges, and the
 * USGS gauges: every one from its readings for a few days or one day, the ones that
 * keep daily totals for a month or more (operator, 2026-10-13: "storm ended two days
 * ago i cant get a look ... even to pick a day and say how much fell that day"). No
 * volunteer gauges ("i cant be having vounteers"), and no gauge that missed part of
 * the time or disagrees with every gauge around it: its total reads as dry ground or a
 * downpour where it is a gap or a fault.
 *
 * <p>The USGS gauges are asked for the map's extent, padded, and asked again when the map
 * settles outside it; over about eight degrees wide the status says to zoom in. The
 * map's extent leaves the device, the position does not.
 */
public final class RainGaugesOverlay extends OutlookOverlay {

    public static final String LAYER_ID = "raingauges";
    public static final String HOST = UsgsRain.HOST;
    private static final String TAG = "AtmosphereRainGauges";
    private static final double MAX_SPAN_LON = 8.0;
    private static final long SETTLE_MS = 800;
    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    /** The runs of days offered; the last opens a date. */
    public static final String[] WINDOWS = { "Last 24 h", "Last 3 days", "Last 7 days", "Last 30 days",
            "Since Oct 1", "Since Jan 1", "One day", "Before a date and time" };
    public static final int WINDOW_24H = 0;
    public static final int WINDOW_ONE_DAY = 6;
    /** Hours ending at a moment picked: the rain before a fire started. */
    public static final int WINDOW_BEFORE = 7;
    /** The stretches offered before a moment, in hours. */
    public static final int[] BEFORE_HOURS = { 24, 72, 168 };
    private static final String PREF_END = "weather.layer.raingauges.end";
    private static final String PREF_HOURS = "weather.layer.raingauges.hours";
    private static final String PREF_WINDOW = "weather.layer.raingauges.window";
    private static final String PREF_DATE = "weather.layer.raingauges.date";

    private static final String NO_RAIN = "#FFB0BEC5";

    /**
     * The key for the last 24 hours: the Rain Totals picture's own, so a dot is the
     * color of the ground under it (operator, 2026-10-14, the two keys side by side:
     * "i feel like its not right"). No rain is grey, where the picture is clear.
     */
    public static final String[][] LEGEND = withRings(RainTotalsOverlay.LEGEND_MRMS, "No rain in 24 h", RINGS());

    private static String RINGS() {
        return RINGS;
    }

    private static String[][] withRings(String[][] key, String none, String rings) {
        final String[][] out = new String[key.length + 2][];
        out[0] = new String[] { none, NO_RAIN };
        System.arraycopy(key, 0, out, 1, key.length);
        out[key.length + 1] = new String[] { rings, "#00000000" };
        return out;
    }
    private static final String RINGS = "White ring: USGS gauge. Dark ring: airport, NWS observer, county flood"
            + " control, RAWS or other agency gauge. Each gauge's own total; a gauge that missed part of it, or"
            + " disagrees with every gauge around it, is left off.";
    /** How wide the map may be for the USGS readings over a few days: a week of a dense box is near the 8 MB cap. */
    private static final double MAX_SPAN_LON_READINGS = 3.0;
    private static final int STATION_RING = 0xFF202020;

    /** A 24-hour total in the Rain Totals key; grey for none. */
    public static int color(double inches) {
        final Integer c = RainTotalsOverlay.colorFor(24, false, inches);
        return c == null ? (int) Long.parseLong(NO_RAIN.substring(1), 16) : c;
    }

    /** One gauge as the Rain Gauges page lists it, whatever it came from. */
    public static final class Reading {
        public static final int USGS = 0, STATION = 2;
        public final int kind;
        public final String name, amount, details;
        /** A line under the name on the page's list, or empty. */
        public final String note;
        public final double lat, lon, inches;
        public final int color;

        Reading(int kind, String name, String amount, String details, String note, double lat, double lon,
                double inches, int color) {
            this.kind = kind;
            this.name = name;
            this.amount = amount;
            this.details = details;
            this.note = note;
            this.lat = lat;
            this.lon = lon;
            this.inches = inches;
            this.color = color;
        }
    }

    private final DotIcons icons;
    private volatile String url = "";
    /** The requests of one refresh, in the order their answers are read; some add more. */
    private final List<String> chain = new CopyOnWriteArrayList<>();
    private final Context pluginContext;
    private States states;
    private volatile String state = "";
    private volatile double[] asked;
    private volatile boolean zoomedOut = true;
    private volatile boolean dirty;

    private volatile int window;
    /** The day picked for {@link #WINDOW_ONE_DAY}, yyyy-MM-dd. */
    private volatile String pickedDate = "";
    /** The run of days the refresh in hand asked for, yyyy-MM-dd, and the phone's today then. */
    private volatile String runStart = "", runEnd = "", runToday = "";
    private volatile int runDays = 1;
    /** The USGS daily totals, held until the readings after them arrive. */
    private volatile Map<String, UsgsRain.Daily> heldDaily = new HashMap<>();
    /**
     * The climate record is asked twice per state: the run's whole days, then today.
     * A gauge's report for today is often not in yet, and asked as one run that read
     * as a missing day at 228 of 894 California gauges (2026-10-13), so a missing day
     * is counted over whole days only. Which part a request is, by its URL.
     */
    private final Map<String, String> acisPart = new java.util.concurrent.ConcurrentHashMap<>();
    /** A state's whole days, held until its today arrives. */
    private final Map<String, List<Acis.Station>> heldRun = new java.util.concurrent.ConcurrentHashMap<>();
    /** The moment picked for {@link #WINDOW_BEFORE}, UTC millis, and the hours before it. */
    private volatile long endMs;
    private volatile int beforeHours = 24;
    /** The stretch of hours the refresh in hand asked for, for {@link #WINDOW_BEFORE}. */
    private volatile long runStartMs, runEndMs;
    /** The center of the map at the ask, for choosing the agency gauges nearest it. */
    private volatile double[] askedCenter;
    /** Network lists from the relay ("CA_DCP"), kept a week on the phone. */
    private final Map<String, List<Iem.Station>> networks = new java.util.concurrent.ConcurrentHashMap<>();
    /** Which network list a relay URL is: "CA|list" for the gauges, "CA|alist" for the airports. */
    private final Map<String, String> iemPart = new java.util.concurrent.ConcurrentHashMap<>();
    /** The relay files of a part that comes in several, by URL. */
    private final Map<String, Gather> gathers = new java.util.concurrent.ConcurrentHashMap<>();
    /** States whose gauge list is still coming; the gauges are asked for once none is. */
    private final java.util.Set<String> listsDue = java.util.Collections.synchronizedSet(
            new java.util.HashSet<String>());
    private volatile boolean agencyAsked;
    /** Every state's gauges asked for, together: the agency gauges' names and places. */
    private volatile List<Iem.Station> agencyList = new ArrayList<>();
    /**
     * Hours older than the relay keeps: the agency gauges show the whole days from the
     * start's day to the end's, the phone's days, and the airports are left out.
     */
    private volatile String fallStart = "", fallEnd = "";
    /** A month of daily totals was not on the relay: not harvested yet, or before it begins. */
    private volatile boolean relayMissing;
    /** Gauges placed this refresh with their totals, {lat, lon, inches}: the agency gauges' neighbors. */
    private final List<double[]> placed = new CopyOnWriteArrayList<>();
    /** USGS gauges' places, so the agency feed's copy of one is not drawn twice. */
    private final List<double[]> usgsPlaces = new CopyOnWriteArrayList<>();
    /** The agency gauges were more than the phone takes: the nearest the center were asked. */
    private volatile int agencyCapped;
    private volatile boolean iemFailed;
    /** The USGS site list, held until the readings arrive. */
    private volatile Map<String, UsgsRain.Site> heldSites = new HashMap<>();
    /** The USGS readings could not be fetched, too many for the phone most likely. */
    private volatile boolean readingsFailed;
    /** Gauges left off this refresh because they missed a day of the run. */
    private final java.util.concurrent.atomic.AtomicInteger leftOff = new java.util.concurrent.atomic.AtomicInteger();
    /** States whose whole days could not be fetched: said so, never shown as no gauges. */
    private final java.util.Set<String> acisFailed = java.util.Collections.synchronizedSet(
            new java.util.HashSet<String>());

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
        final SharedPreferences p = MapCompat.prefs();
        if (p != null) {
            window = Math.max(0, Math.min(WINDOWS.length - 1, p.getInt(PREF_WINDOW, WINDOW_24H)));
            pickedDate = validDate(p.getString(PREF_DATE, ""));
            endMs = p.getLong(PREF_END, 0L);
            beforeHours = p.getInt(PREF_HOURS, 24);
        }
        if (pickedDate.isEmpty())
            window = window == WINDOW_ONE_DAY ? WINDOW_24H : window;
        if (!validMoment(endMs, beforeHours))
            window = window == WINDOW_BEFORE ? WINDOW_24H : window;
    }

    // ---- the run of days --------------------------------------------------------------------

    public int window() {
        return window;
    }

    /** The day picked, yyyy-MM-dd, or empty. */
    public String pickedDate() {
        return pickedDate;
    }

    /** What the window button reads: "Last 7 days", or the day picked, "Sun Oct 11". */
    public String windowLabel() {
        if (window == WINDOW_ONE_DAY && !pickedDate.isEmpty())
            return dayName(pickedDate, sameYear(pickedDate) ? "EEE MMM d" : "EEE MMM d, yyyy");
        if (window == WINDOW_BEFORE)
            return hoursWords(beforeHours) + " before " + momentName(endMs);
        return WINDOWS[window];
    }

    /**
     * Another run of days, or another day for {@link #WINDOW_ONE_DAY}. What is drawn
     * is for the old run, so it comes off at once and the new one is asked for.
     */
    public void setWindow(int index, String date) {
        int v = Math.max(0, Math.min(WINDOWS.length - 1, index));
        final String d = validDate(date);
        if (v == WINDOW_ONE_DAY && d.isEmpty())
            v = WINDOW_24H;
        if (v == WINDOW_BEFORE && !validMoment(endMs, beforeHours))
            v = WINDOW_24H;
        if (v == window && (v != WINDOW_ONE_DAY || d.equals(pickedDate)))
            return;
        window = v;
        if (v == WINDOW_ONE_DAY)
            pickedDate = d;
        final SharedPreferences p = MapCompat.prefs();
        if (p != null)
            p.edit().putInt(PREF_WINDOW, v).putString(PREF_DATE, pickedDate).apply();
        restart();
    }

    /**
     * The hours before a moment: the 24 hours before a fire started. A moment in the
     * future is taken as now.
     */
    public void setMoment(long millis, int hours) {
        final long m = Math.min(millis, System.currentTimeMillis());
        if (!validMoment(m, hours))
            return;
        if (window == WINDOW_BEFORE && m == endMs && hours == beforeHours)
            return;
        window = WINDOW_BEFORE;
        endMs = m;
        beforeHours = hours;
        final SharedPreferences p = MapCompat.prefs();
        if (p != null)
            p.edit().putInt(PREF_WINDOW, window).putLong(PREF_END, m).putInt(PREF_HOURS, hours).apply();
        restart();
    }

    /** The moment picked, UTC millis, or 0. */
    public long moment() {
        return endMs;
    }

    public int beforeHours() {
        return beforeHours;
    }

    private static boolean validMoment(long millis, int hours) {
        boolean known = false;
        for (int h : BEFORE_HOURS)
            known |= h == hours;
        // The USGS readings go back to 2007; the mesonet's gauge archive to about 2010.
        return known && millis > 1_262_304_000_000L && millis <= System.currentTimeMillis() + 60_000L;
    }

    public static String hoursWords(int hours) {
        return hours == 24 ? "24 h" : hours % 24 == 0 ? hours / 24 + " days" : hours + " h";
    }

    /** "Sun Oct 11, 2:15 pm", the phone's time; the year when it is not this one. */
    public static String momentName(long millis) {
        final Calendar c = Calendar.getInstance();
        final int year = c.get(Calendar.YEAR);
        c.setTimeInMillis(millis);
        return new SimpleDateFormat(c.get(Calendar.YEAR) == year ? "EEE MMM d, h:mm a" : "EEE MMM d yyyy, h:mm a",
                Locale.US).format(new Date(millis)).replace("AM", "am").replace("PM", "pm");
    }

    /** The legend for the window: the 24 h key, or the run's colors and rings. */
    public String[][] legend() {
        return withRings(RainTotalsOverlay.legendFor(runHours(), season()), "No rain", RINGS);
    }

    /** The stretch the window covers in hours, for the key: a day, three, a week, a month. */
    private int runHours() {
        switch (window) {
            case 1:
                return 72;
            case 2:
                return 168;
            case 3:
                return 720;
            case WINDOW_BEFORE:
                return beforeHours;
            default:
                return 24;
        }
    }

    /** Since Oct 1 or Jan 1: the season's key. */
    private boolean season() {
        return window == 4 || window == 5;
    }

    /** A few days or one day: every USGS gauge, added up from its readings. */
    private boolean readingsRun() {
        return window == 1 || window == 2 || window == WINDOW_ONE_DAY;
    }

    /**
     * Hours ending at a moment, exact readings and never whole days: a moment picked,
     * or now for the last 24 h, which shows the same gauges as every other window
     * (operator, 2026-10-14: one list, not the office's report beside the map's).
     */
    private boolean exactRun() {
        return window == WINDOW_BEFORE || window == WINDOW_24H;
    }

    /** How wide the map may be before the USGS gauges wait for a zoom. */
    private double usgsSpan() {
        return window == 1 || window == 2 || (window == WINDOW_BEFORE && beforeHours > 24) ? MAX_SPAN_LON_READINGS
                : MAX_SPAN_LON;
    }

    /** A total in the key of the Rain Totals picture for the same stretch. */
    private int colorFor(double inches) {
        final Integer c = RainTotalsOverlay.colorFor(runHours(), season(), inches);
        return c == null ? (int) Long.parseLong(NO_RAIN.substring(1), 16) : c;
    }

    /** "Sun Oct 11 to now", "on Sun Oct 11", "today so far": the run in words. */
    public String runWords() {
        final String start = runStart.isEmpty() ? startOf(today()) : runStart;
        final String end = runEnd.isEmpty() ? endOf(today()) : runEnd;
        final String today = runToday.isEmpty() ? today() : runToday;
        if (window == WINDOW_24H)
            return "in the last 24 h";
        if (window == WINDOW_BEFORE)
            return "in the " + hoursWords(beforeHours) + " before " + momentName(endMs);
        if (start.equals(end))
            return end.equals(today) ? "today so far" : "on " + dayName(end, "EEE MMM d" + (sameYear(end) ? "" : ", yyyy"));
        return dayName(start, "EEE MMM d" + (sameYear(start) ? "" : ", yyyy")) + (end.equals(today) ? " to now"
                : " to " + dayName(end, "EEE MMM d"));
    }

    /** The first day of the run for the window, as of {@code today}. */
    private String startOf(String today) {
        final Calendar c = calendar(today);
        switch (window) {
            case 1:
                c.add(Calendar.DAY_OF_MONTH, -2);
                break;
            case 2:
                c.add(Calendar.DAY_OF_MONTH, -6);
                break;
            case 3:
                c.add(Calendar.DAY_OF_MONTH, -29);
                break;
            case 4:
                // The water year, October 1 through September 30.
                if (c.get(Calendar.MONTH) < Calendar.OCTOBER)
                    c.add(Calendar.YEAR, -1);
                c.set(Calendar.MONTH, Calendar.OCTOBER);
                c.set(Calendar.DAY_OF_MONTH, 1);
                break;
            case 5:
                c.set(Calendar.MONTH, Calendar.JANUARY);
                c.set(Calendar.DAY_OF_MONTH, 1);
                break;
            case WINDOW_ONE_DAY:
                return pickedDate.compareTo(today) > 0 ? today : pickedDate;
            default:
                break;
        }
        return format(c);
    }

    private String endOf(String today) {
        if (window == WINDOW_ONE_DAY)
            return pickedDate.compareTo(today) > 0 ? today : pickedDate;
        return today;
    }

    private static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    private static Calendar calendar(String day) {
        final Calendar c = Calendar.getInstance();
        try {
            c.setTime(new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(day));
        } catch (ParseException e) {
            // Today, as got.
        }
        return c;
    }

    private static String format(Calendar c) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(c.getTime());
    }

    private static String plusDays(String day, int n) {
        final Calendar c = calendar(day);
        c.add(Calendar.DAY_OF_MONTH, n);
        return format(c);
    }

    private static int daysBetween(String start, String end) {
        final long a = calendar(start).getTimeInMillis(), b = calendar(end).getTimeInMillis();
        return (int) Math.max(1, Math.round((b - a) / (double) DAY_MS) + 1);
    }

    /** A yyyy-MM-dd that parses and is not in the future, else empty. */
    static String validDate(String day) {
        if (day == null || !day.matches("\\d{4}-\\d{2}-\\d{2}"))
            return "";
        final SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setLenient(false);
        try {
            f.parse(day);
        } catch (ParseException e) {
            return "";
        }
        return day.compareTo(today()) > 0 ? today() : day;
    }

    private static boolean sameYear(String day) {
        return day.length() >= 4 && day.substring(0, 4).equals(today().substring(0, 4));
    }

    public static String dayName(String day, String pattern) {
        try {
            return new SimpleDateFormat(pattern, Locale.US)
                    .format(new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(day));
        } catch (ParseException e) {
            return day;
        }
    }

    // ---- the states and the box ---------------------------------------------------------------

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

    /**
     * Every gauge held, as the page lists it, most rain first: whatever the window,
     * the gauges the map draws, the office's report aside (the page shows it whole).
     */
    public List<Reading> readings() {
        final List<Reading> out = new ArrayList<>();
        for (Area a : areas()) {
            if (a.extra instanceof Reading)
                out.add((Reading) a.extra);
        }
        Collections.sort(out, new Comparator<Reading>() {
            @Override
            public int compare(Reading a, Reading b) {
                return Double.compare(b.inches, a.inches);
            }
        });
        return out;
    }

    public boolean zoomedOut() {
        return zoomedOut;
    }

    private static JSONObject point(double lat, double lon) {
        final JSONObject geom = new JSONObject();
        try {
            geom.put("type", "Point");
            geom.put("coordinates", new JSONArray().put(lon).put(lat));
        } catch (Exception e) {
            return null;
        }
        return geom;
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
        final boolean wide = x[2] - x[0] > usgsSpan();
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
        heldDaily = new HashMap<>();
        acisPart.clear();
        heldRun.clear();
        acisFailed.clear();
        heldSites = new HashMap<>();
        readingsFailed = false;
        leftOff.set(0);
        iemPart.clear();
        gathers.clear();
        listsDue.clear();
        agencyAsked = false;
        agencyList = new ArrayList<>();
        fallStart = "";
        fallEnd = "";
        relayMissing = false;
        placed.clear();
        usgsPlaces.clear();
        agencyCapped = 0;
        iemFailed = false;
        chain.clear();
        runEndMs = window == WINDOW_24H ? System.currentTimeMillis() : endMs;
        runStartMs = runEndMs - (window == WINDOW_24H ? 24 : beforeHours) * 3_600_000L;
        final String today = today();
        runToday = today;
        runStart = window == WINDOW_24H ? "" : startOf(today);
        runEnd = window == WINDOW_24H ? "" : endOf(today);
        runDays = window == WINDOW_24H ? 1 : daysBetween(runStart, runEnd);
        final double[] x = extent();
        zoomedOut = x == null || x[2] - x[0] > usgsSpan();
        double[] box = null;
        if (zoomedOut) {
            asked = null;
            url = "";
        } else {
            final double padX = (x[2] - x[0]) * 0.5, padY = (x[3] - x[1]) * 0.5;
            box = new double[] { Math.max(-180, x[0] - padX), Math.max(-89, x[1] - padY),
                    Math.min(180, x[2] + padX), Math.min(89, x[3] + padY) };
            asked = box;
        }
        askedCenter = x == null ? null : new double[] { (x[1] + x[3]) / 2, (x[0] + x[2]) / 2 };
        final String st = statesUnderMap();
        state = st;
        if (exactRun()) {
            // Exact hours: every USGS gauge's readings, each state's airports' hourly
            // reports, and the agency gauges' readings, nearest the center first.
            if (box != null) {
                chain.add(UsgsRain.sitesUrl(box[0], box[1], box[2], box[3]));
                url = UsgsRain.readingsUrl(box[0], box[1], box[2], box[3], Iem.z(runStartMs), Iem.z(runEndMs));
                chain.add(url);
            }
            // The relay keeps hours 45 days; older, the agency gauges' whole days.
            final boolean hoursHeld = runStartMs >= IemRelay.hoursSince(System.currentTimeMillis());
            if (!hoursHeld) {
                fallStart = localDay(runStartMs);
                fallEnd = localDay(runEndMs);
            }
            if (!st.isEmpty() && hoursHeld)
                for (String one : st.split(",")) {
                    if (network(one, "ASOS") == null) {
                        final String l = IemRelay.networkUrl(one, "ASOS");
                        iemPart.put(l, one + "|alist");
                        chain.add(l);
                    }
                    final List<String> days = IemRelay.utcDays(runStartMs, runEndMs + 15 * 60_000L);
                    final Gather g = new Gather(one, null, null, new IemRelay.Reports(runStartMs, runEndMs),
                            days.size());
                    for (String day : days)
                        ask(IemRelay.asosUrl(day, one), g);
                }
            if (box != null && !st.isEmpty())
                askAgencyLists(st);
            return;
        }
    // Every daily gauge of the states under the map, added up for the run, then
        // the USGS gauges that keep daily totals; a week before the run is asked too,
        // so a gauge whose newest total is a day old is still known to keep them.
        final String yesterday = plusDays(today, -1);
        if (!st.isEmpty())
            for (String one : st.split(",")) {
                if (runStart.compareTo(yesterday) <= 0) {
                    final String u = Acis.url(one, runStart, runEnd.compareTo(yesterday) < 0 ? runEnd : yesterday);
                    acisPart.put(u, one + "|run");
                    chain.add(u);
                }
                if (runEnd.equals(today)) {
                    final String u = Acis.url(one, today, today);
                    acisPart.put(u, one + "|today");
                    chain.add(u);
                }
            }
        if (box != null && readingsRun()) {
            // From midnight at the earliest standard time in the country to the
            // latest, so every gauge's own days are inside; trimmed to the day after.
            chain.add(UsgsRain.sitesUrl(box[0], box[1], box[2], box[3]));
            url = UsgsRain.readingsUrl(box[0], box[1], box[2], box[3], runStart + "T00:00-04:00",
                    runEnd.equals(today) ? "" : plusDays(runEnd, 1) + "T00:00-10:00");
            chain.add(url);
        } else if (box != null) {
            url = UsgsRain.dailyUrl(box[0], box[1], box[2], box[3], plusDays(runStart, -7), runEnd);
            chain.add(url);
        }
        if (box != null && !st.isEmpty())
            askAgencyLists(st);
    }

    /**
     * The agency gauges of the states under the map: each state's list from the phone
     * when held, else asked for first, and the gauges' totals asked for once every list
     * is in hand, the nearest the map's center first across the states.
     */
    private void askAgencyLists(String st) {
        for (String one : st.split(","))
            if (network(one, "DCP") == null) {
                final String u = IemRelay.networkUrl(one, "DCP");
                iemPart.put(u, one + "|list");
                listsDue.add(one);
                chain.add(u);
            }
        if (listsDue.isEmpty())
            askAgency();
    }

    /** A state's list came, or could not: the gauges are asked for after the last. */
    private void listDone(String st) {
        listsDue.remove(st);
        if (listsDue.isEmpty())
            askAgency();
    }

    /**
     * The relay files the agency gauges' totals are in: the readings by UTC day and
     * 2-degree tile for hours, the daily totals by state and month for days.
     */
    private void askAgency() {
        if (agencyAsked)
            return;
        agencyAsked = true;
        final List<Iem.Station> all = new ArrayList<>();
        final Map<String, String> stateOf = new HashMap<>();
        for (String one : state.split(",")) {
            final List<Iem.Station> list = one.isEmpty() ? null : network(one, "DCP");
            if (list == null)
                continue;
            all.addAll(list);
            for (Iem.Station s : list)
                stateOf.put(s.id, one);
        }
        final List<String> ids = agencyIds(all);
        if (ids.isEmpty())
            return;
        agencyList = all;
        final java.util.Set<String> want = new java.util.HashSet<>(ids);
        if (exactRun() && fallStart.isEmpty()) {
            final List<Iem.Station> asked = new ArrayList<>();
            for (Iem.Station s : all)
                if (want.contains(s.id))
                    asked.add(s);
            final List<String> urls = new ArrayList<>();
            for (String day : IemRelay.utcDays(runStartMs, runEndMs))
                for (String tile : IemRelay.tiles(asked))
                    urls.add(IemRelay.hadsUrl(day, tile));
            final Gather g = new Gather("", null, new IemRelay.Readings(want, runStartMs, runEndMs), null,
                    urls.size());
            for (String u : urls)
                ask(u, g);
            return;
        }
        final String start = exactRun() ? fallStart : runStart, end = exactRun() ? fallEnd : runEnd;
        final java.util.Set<String> states = new java.util.TreeSet<>();
        for (String id : ids)
            states.add(stateOf.get(id));
        final List<String> urls = new ArrayList<>();
        for (String one : states)
            for (String month : IemRelay.months(start, end)) {
                if (month.compareTo(IemRelay.DAILY_SINCE.substring(0, 7)) < 0) {
                    relayMissing = true;
                    continue;
                }
                urls.add(IemRelay.dailyUrl(one, month));
            }
        if (urls.isEmpty())
            return;
        final Gather g = new Gather("", new IemRelay.Days(want, start, end), null, null, urls.size());
        for (String u : urls)
            ask(u, g);
    }

    private void ask(String url, Gather g) {
        gathers.put(url, g);
        chain.add(url);
    }

    /**
     * A part of the relay's answer that comes in several files -- a state's months,
     * the readings' days and tiles, an airport day -- read as each comes and finished
     * at the last, answered or not.
     */
    private static final class Gather {
        /** The state, for the airports. */
        final String st;
        final IemRelay.Days days;
        final IemRelay.Readings readings;
        final IemRelay.Reports reports;
        private int left;

        Gather(String st, IemRelay.Days days, IemRelay.Readings readings, IemRelay.Reports reports, int files) {
            this.st = st;
            this.days = days;
            this.readings = readings;
            this.reports = reports;
            this.left = files;
        }

        /** One file read or passed over; true at the last. */
        synchronized boolean done() {
            return --left == 0;
        }

        synchronized void add(String body) {
            if (days != null)
                days.add(body);
            else if (readings != null)
                readings.add(body);
            else if (reports != null)
                reports.add(body);
        }
    }

    /** The phone's day of a moment, yyyy-MM-dd. */
    private static String localDay(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(millis));
    }

    /**
     * The gauges of a list inside the box asked, nearest the map's center first, as
     * many as the phone takes: about 6 MB of readings, 12 KB a gauge a day; of daily
     * totals, 30 bytes a gauge a day.
     */
    private List<String> agencyIds(List<Iem.Station> list) {
        final double[] box = asked, c = askedCenter;
        final List<String> out = new ArrayList<>();
        if (box == null || c == null)
            return out;
        final List<Iem.Station> in = new ArrayList<>();
        for (Iem.Station s : list)
            if (s.lon >= box[0] && s.lon <= box[2] && s.lat >= box[1] && s.lat <= box[3])
                in.add(s);
        Collections.sort(in, new Comparator<Iem.Station>() {
            @Override
            public int compare(Iem.Station a, Iem.Station b) {
                return Double.compare(Math.hypot(a.lat - c[0], a.lon - c[1]), Math.hypot(b.lat - c[0], b.lon - c[1]));
            }
        });
        final int days = exactRun() ? Math.max(1, (window == WINDOW_24H ? 24 : beforeHours) / 24) : Math.max(1, runDays);
        final int cap = exactRun() ? Math.max(20, 500 / days) : Math.max(100, 180_000 / days);
        final int n = Math.min(in.size(), Math.min(cap, 800));
        agencyCapped += in.size() > n ? in.size() - n : 0;
        for (int i = 0; i < n; i++)
            out.add(in.get(i).id);
        return out;
    }

    /** A mesonet network list: from memory, else from the phone if under a week old, else null. */
    private List<Iem.Station> network(String st, String kind) {
        final String key = st + "_" + kind;
        final List<Iem.Station> have = networks.get(key);
        if (have != null)
            return have;
        final java.io.File f = networkFile(key);
        if (f == null || !f.isFile() || System.currentTimeMillis() - f.lastModified() > 7 * DAY_MS)
            return null;
        try {
            final byte[] b = new byte[(int) Math.min(f.length(), 4_000_000L)];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                int n = 0;
                while (n < b.length) {
                    final int k = in.read(b, n, b.length - n);
                    if (k < 0)
                        break;
                    n += k;
                }
            }
            final List<Iem.Station> list = Iem.fromLines(new String(b, "UTF-8"));
            if (list.isEmpty())
                return null;
            networks.put(key, list);
            return list;
        } catch (Exception e) {
            Log.w(TAG, "network list unreadable: " + key, e);
            return null;
        }
    }

    private static java.io.File networkFile(String key) {
        final java.io.File root = GeneratedFiles.root();
        return root == null || !key.matches("[A-Z]{2}_[A-Z]{3,4}") ? null
                : new java.io.File(new java.io.File(root, "gauges"), key + ".txt");
    }

    /** Kept a week, written whole and renamed so a cut-off write is never read. */
    private static void saveNetwork(String key, List<Iem.Station> list) {
        final java.io.File f = networkFile(key);
        if (f == null)
            return;
        try {
            final java.io.File dir = f.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs())
                return;
            final java.io.File tmp = new java.io.File(dir, key + ".tmp");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                out.write(Iem.toLines(list).getBytes("UTF-8"));
            }
            if (!tmp.renameTo(f))
                tmp.delete();
        } catch (Exception e) {
            Log.w(TAG, "network list not saved: " + key, e);
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
        // The page says it is fetching from the moment it starts (operator,
        // 2026-10-14: "we need something that indicates its thinking or fetching").
        if (busy())
            notifyData();
    }

    /** "Getting the rain for Last 30 days…" */
    @Override
    protected String fetchingWords() {
        return "Getting the rain " + (window == WINDOW_24H ? "of the last 24 h" : "for " + windowLabel()) + "…";
    }

    @Override
    protected String updatingWords() {
        return fetchingWords();
    }

    /**
     * Every request of a refresh is asked for at once, the slowest setting the wait
     * rather than the sum of them (30 days took about 40 s one after another,
     * 2026-10-14). The mesonet had to be asked one at a time, as it turns away an
     * address that asks fast; the relay's files need not be.
     */
    @Override
    protected boolean prefetch(int index) {
        return true;
    }

    /**
     * The request at a place in the chain, or "" when a restart emptied the chain
     * under an answer still being read (security review, 2026-10-13).
     */
    private String asked(int index) {
        try {
            return chain.get(index);
        } catch (IndexOutOfBoundsException e) {
            return "";
        }
    }

    @Override
    protected String[] urls() {
        return chain.toArray(new String[0]);
    }

    @Override
    protected boolean optional(int index) {
        return true;
    }

    /** A state's run of days, and the USGS daily totals, can take tens of seconds. */
    @Override
    protected boolean large(int index) {
        final String asked = asked(index);
        return asked.contains(Acis.HOST) || asked.contains("/nwis/dv/")
                || asked.contains("format=rdb");
    }

    /** Every feed here compresses five to twenty times over; Http unpacks it under its cap. */
    @Override
    protected Map<String, String> headers(int index) {
        final Map<String, String> h = new HashMap<>();
        h.put("Accept-Encoding", "gzip");
        return h;
    }

    /**
     * A request that failed: the readings after the USGS daily totals, so the daily
     * totals alone; a state's today, so its whole days alone; a state's whole days, so
     * nothing for that state and a status that says it could not be fetched.
     */
    @Override
    protected List<Area> skipped(int index) {
        return skipped(index, asked(index));
    }

    /**
     * A relay file that is not there: a day with no readings in a tile, or airports
     * with no reports, is a quiet day; a month of daily totals is one not harvested
     * yet. Anything else did not answer.
     */
    @Override
    protected List<Area> skipped(int index, String asked, String error) {
        final boolean absent = error != null && error.contains("HTTP 404");
        final Gather g = gathers.get(asked);
        if (g != null) {
            if (!absent)
                iemFailed = true;
            else if (g.days != null)
                relayMissing = true;
            return g.done() ? finish(g) : Collections.<Area>emptyList();
        }
        final String part = iemPart.get(asked);
        if (part != null) {
            if (!absent)
                iemFailed = true;
            if (part.endsWith("|list"))
                listDone(part.substring(0, part.indexOf('|')));
            return Collections.emptyList();
        }
        return skipped(index, asked);
    }

    @Override
    protected List<Area> skipped(int index, String asked) {
        if (asked.contains("/nwis/iv/") && asked.contains("format=rdb")) {
            // The USGS answers 404 for a box with no gauges; only a box with gauges
            // whose readings did not come is a box with too many.
            readingsFailed = !heldSites.isEmpty();
            return Collections.emptyList();
        }
        if (asked.contains("/nwis/iv/"))
            return spans(null);
        final String part = acisPart.get(asked);
        if (part != null) {
            final String st = part.substring(0, part.indexOf('|'));
            if (part.endsWith("|run")) {
                acisFailed.add(st);
                return Collections.emptyList();
            }
            final List<Acis.Station> run = heldRun.remove(st);
            if (run != null)
                return acisAreas(run, Collections.<Acis.Station>emptyList());
        }
        return Collections.emptyList();
    }

    /** The run's whole days, today aside. */
    private int wholeDays() {
        return runEnd.equals(runToday) ? runDays - 1 : runDays;
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
        if (a.extra instanceof Reading) {
            final int k = ((Reading) a.extra).kind;
            return k == Reading.USGS ? "USGS Rain Gauges"
                    : "NWS Observer Gauges";
        }
        return "USGS Rain Gauges";
    }

    @Override
    protected List<Area> parse(int index, String body) {
        return parse(index, asked(index), body);
    }

    /** Which request this answers is the URL it went out with, never the chain's place now. */
    @Override
    protected List<Area> parse(int index, String asked, String body) {
        final List<Area> out = new ArrayList<>();
        if (asked.contains(Acis.HOST)) {
            final String part = acisPart.get(asked);
            if (part == null)
                return out;
            final String st = part.substring(0, part.indexOf('|'));
            final List<Acis.Station> got = Acis.parse(body);
            if (part.endsWith("|run")) {
                if (runEnd.equals(runToday)) {
                    heldRun.put(st, got);
                    return out;
                }
                return acisAreas(got, null);
            }
            final List<Acis.Station> run = heldRun.remove(st);
            if (wholeDays() > 0 && run == null)
                return out;
            return acisAreas(run, got);
        }
        if (asked.contains(IemRelay.HOST))
            return parseIem(asked, body);
        if (asked.contains("/nwis/site/")) {
            heldSites = UsgsRain.parseSites(body);
            for (UsgsRain.Site site : heldSites.values())
                usgsPlaces.add(new double[] { site.lat, site.lon });
            return out;
        }
        if (asked.contains("/nwis/iv/") && asked.contains("format=rdb"))
            return exactRun() ? windowAreas(UsgsRain.parseRdbWindow(body, runStartMs, runEndMs))
                    : readingAreas(UsgsRain.parseRdbByDay(body));
        if (asked.contains("/nwis/dv/")) {
            heldDaily = UsgsRain.parseDaily(body);
            for (UsgsRain.Daily d : heldDaily.values())
                usgsPlaces.add(new double[] { d.lat, d.lon });
            // Today, and early in the morning yesterday, are not added up yet: ask for
            // the readings of the gauges that keep daily totals, by name.
            if (!heldDaily.isEmpty() && runEnd.compareTo(plusDays(runToday, -1)) >= 0) {
                final List<String> sites = new ArrayList<>(heldDaily.keySet());
                chain.add(UsgsRain.recentUrl(sites.size() > 200 ? sites.subList(0, 200) : sites));
                return out;
            }
            return spans(null);
        }
        return spans(UsgsRain.parseByDay(body));
    }

    /** Whether a gauge has no record for a whole day of the run; today may still be coming. */
    private boolean missedADay(Map<String, Double> byDay) {
        return missedADay(byDay, runStart, runEnd);
    }

    private boolean missedADay(Map<String, Double> byDay, String start, String end) {
        final String lastWhole = end.equals(runToday) ? plusDays(runToday, -1) : end;
        for (String d = start; d.compareTo(lastWhole) <= 0; d = plusDays(d, 1))
            if (!byDay.containsKey(d))
                return true;
        return false;
    }

    /** A gauge's total from the agency feed, whichever way it was added up. */
    private static final class Agg {
        double total;
        boolean complete;
        java.util.TreeMap<String, Double> byDay = new java.util.TreeMap<>();
    }

    /** What a relay file is, by the part its URL was asked as. */
    private List<Area> parseIem(String asked, String body) {
        final Gather g = gathers.get(asked);
        if (g != null) {
            g.add(body);
            return g.done() ? finish(g) : new ArrayList<Area>();
        }
        final String part = iemPart.get(asked);
        if (part == null)
            return new ArrayList<>();
        final String st = part.substring(0, part.indexOf('|'));
        final String key = st + (part.endsWith("|list") ? "_DCP" : "_ASOS");
        final List<Iem.Station> list = IemRelay.parseNetwork(body);
        if (!list.isEmpty()) {
            networks.put(key, list);
            saveNetwork(key, list);
        }
        if (part.endsWith("|list"))
            listDone(st);
        return new ArrayList<>();
    }

    /** A part's files are all in: its gauges, added up. */
    private List<Area> finish(Gather g) {
        if (g.reports != null)
            return airportAreas(g.st, g.reports.totals());
        final Map<String, Agg> got = new java.util.LinkedHashMap<>();
        if (g.readings != null) {
            for (Iem.Total t : g.readings.totals().values()) {
                final Agg a = new Agg();
                a.total = t.inches;
                a.complete = t.complete;
                got.put(t.id, a);
            }
            return agencyAreas(agencyList, got, runWords());
        }
        final boolean whole = !fallStart.isEmpty();
        final String start = whole ? fallStart : runStart, end = whole ? fallEnd : runEnd;
        for (Map.Entry<String, java.util.TreeMap<String, Double>> e : g.days.byGauge().entrySet()) {
            final Agg a = new Agg();
            for (Map.Entry<String, Double> d : e.getValue().entrySet()) {
                a.byDay.put(d.getKey(), d.getValue());
                a.total += d.getValue();
            }
            a.complete = !missedADay(a.byDay, start, end);
            got.put(e.getKey(), a);
        }
        return agencyAreas(agencyList, got, whole ? fallWords() : runWords());
    }

    /**
     * "Fri Oct 9 to Sat Oct 10, in whole days", or "Fri Oct 16 to now": what the agency
     * gauges add up when their hours are not kept.
     */
    private String fallWords() {
        final String pattern = "EEE MMM d" + (sameYear(fallStart) ? "" : ", yyyy");
        final boolean now = fallEnd.equals(runToday);
        if (fallStart.equals(fallEnd))
            return now ? "today so far" : "on " + dayName(fallStart, pattern) + ", the whole day";
        return dayName(fallStart, pattern) + " to " + (now ? "now" : dayName(fallEnd, "EEE MMM d"))
                + ", in whole days";
    }

    /**
     * The agency gauges: whole over the run, not a second copy of a gauge already
     * drawn, and in line with the gauges around them. The archive is not checked, and
     * a gauge that read inches on a dry day or nothing through a storm would otherwise
     * be on the map as fact.
     */
    private List<Area> agencyAreas(List<Iem.Station> list, Map<String, Agg> got, String words) {
        final Map<String, Iem.Station> byId = new HashMap<>();
        for (Iem.Station s : list)
            byId.put(s.id, s);
        final List<Iem.Station> keep = new ArrayList<>();
        final List<double[]> around = new ArrayList<>(placed);
        for (Map.Entry<String, Agg> e : got.entrySet()) {
            final Iem.Station s = byId.get(e.getKey());
            if (s == null)
                continue;
            if (!e.getValue().complete) {
                leftOff.incrementAndGet();
                continue;
            }
            if (near(s.lat, s.lon, usgsPlaces) || near(s.lat, s.lon, placed))
                continue;
            keep.add(s);
            around.add(new double[] { s.lat, s.lon, e.getValue().total });
        }
        final List<Area> out = new ArrayList<>();
        for (Iem.Station s : keep) {
            final Agg a = got.get(s.id);
            if (Iem.disagrees(a.total, s.lat, s.lon, around)) {
                leftOff.incrementAndGet();
                continue;
            }
            final JSONObject geom = point(s.lat, s.lon);
            if (geom == null)
                continue;
            final String amount = inches(a.total);
            final StringBuilder b = new StringBuilder();
            b.append(amount).append(' ').append(words).append('\n').append(s.name).append(" (").append(s.id)
                    .append(')');
            if (a.byDay.size() > 1 && a.byDay.size() <= 31) {
                b.append("\nBy the day:");
                for (Map.Entry<String, Double> d : a.byDay.entrySet())
                    if (d.getValue() > 0.004)
                        b.append('\n').append(dayName(d.getKey(), "EEE MMM d")).append("  ").append(inches(d.getValue()));
            }
            b.append("\nA county flood control, RAWS, Forest Service, state or other agency gauge, from the NWS"
                    + " gauge feed. Nobody checks this feed: Atmosphere leaves off a gauge that missed part of the"
                    + " time or disagrees with every gauge around it.");
            b.append("\nFrom: Iowa Environmental Mesonet, Iowa State University");
            final int c = colorFor(a.total);
            placed.add(new double[] { s.lat, s.lon, a.total });
            out.add(new Area(1, amount + " - " + s.name, "Agency", c, geom, b.toString(), "",
                    new Reading(Reading.STATION, s.name, amount, b.toString(), "", s.lat, s.lon, a.total, c)));
        }
        return out;
    }

    /** The airports over hours ending at the moment picked, from their hourly reports. */
    private List<Area> airportAreas(String st, Map<String, Iem.Total> got) {
        final List<Iem.Station> list = networks.get(st + "_ASOS");
        final Map<String, String> names = new HashMap<>();
        if (list != null)
            for (Iem.Station s : list)
                names.put(s.id, s.name);
        final List<Area> out = new ArrayList<>();
        for (Iem.Total t : got.values()) {
            if (!t.complete) {
                leftOff.incrementAndGet();
                continue;
            }
            final JSONObject geom = point(t.lat, t.lon);
            if (geom == null)
                continue;
            final String name = names.containsKey(t.id) ? names.get(t.id) + " (" + t.id + ")" : t.id + " airport";
            final String amount = inches(t.inches);
            final String details = amount + " " + runWords() + "\n" + name
                    + "\nAn airport's weather station, from its hourly reports."
                    + "\nFrom: Iowa Environmental Mesonet, Iowa State University, from the NWS airport reports";
            final int c = colorFor(t.inches);
            placed.add(new double[] { t.lat, t.lon, t.inches });
            out.add(new Area(1, amount + " - " + name, "Airport", c, geom, details, "",
                    new Reading(Reading.STATION, name, amount, details, "", t.lat, t.lon, t.inches, c)));
        }
        return out;
    }

    /** Every USGS gauge in the box over hours ending at the moment picked. */
    private List<Area> windowAreas(Map<String, double[]> got) {
        final List<Area> out = new ArrayList<>();
        final Map<String, UsgsRain.Site> sites = heldSites;
        for (Map.Entry<String, double[]> e : got.entrySet()) {
            final UsgsRain.Site site = sites.get(e.getKey());
            if (site == null)
                continue;
            if (e.getValue()[1] < 1) {
                leftOff.incrementAndGet();
                continue;
            }
            final JSONObject geom = point(site.lat, site.lon);
            if (geom == null)
                continue;
            final double total = e.getValue()[0];
            final String amount = inches(total);
            final String details = amount + " " + runWords() + "\n" + site.name
                    + "\nEvery reading inside the time counted, most every 15 minutes."
                    + "\nUSGS site " + site.site + "\n" + site.url()
                    + "\nFrom: USGS National Water Information System, provisional";
            final int c = colorFor(total);
            placed.add(new double[] { site.lat, site.lon, total });
            out.add(new Area(1, amount + " - " + site.name, "USGS", c, geom, details, "",
                    new Reading(Reading.USGS, site.name, amount, details, "", site.lat, site.lon, total, c)));
        }
        return out;
    }

    /** Whether a place is within 300 m of one of these: the same gauge in two feeds. */
    private static boolean near(double lat, double lon, List<double[]> places) {
        for (double[] p : places)
            if (Math.abs(p[0] - lat) < 0.003 && Math.abs(p[1] - lon) < 0.004
                    && com.atakmap.android.atmosphere.data.Spot.distanceMeters(lat, lon, p[0], p[1]) <= 300)
                return true;
        return false;
    }

    /** Every USGS gauge in the box over the run, from its readings. */
    private List<Area> readingAreas(Map<String, Map<String, Double>> byDay) {
        final List<Area> out = new ArrayList<>();
        final Map<String, UsgsRain.Site> sites = heldSites;
        for (Map.Entry<String, Map<String, Double>> e : byDay.entrySet()) {
            final UsgsRain.Site site = sites.get(e.getKey());
            if (site == null)
                continue;
            final java.util.TreeMap<String, Double> days = new java.util.TreeMap<>();
            double total = 0;
            for (Map.Entry<String, Double> d : e.getValue().entrySet())
                if (d.getKey().compareTo(runStart) >= 0 && d.getKey().compareTo(runEnd) <= 0) {
                    days.put(d.getKey(), d.getValue());
                    total += d.getValue();
                }
            if (missedADay(days)) {
                leftOff.incrementAndGet();
                continue;
            }
            final JSONObject geom = point(site.lat, site.lon);
            if (geom == null)
                continue;
            final String amount = inches(total);
            final StringBuilder b = new StringBuilder();
            b.append(amount).append(' ').append(runWords()).append('\n').append(site.name);
            if (days.size() > 1) {
                b.append("\nBy the day:");
                for (Map.Entry<String, Double> d : days.entrySet())
                    b.append('\n').append(dayName(d.getKey(), "EEE MMM d")).append("  ").append(inches(d.getValue()));
            }
            b.append("\nA day is the gauge's own, midnight to midnight standard time.");
            b.append("\nUSGS site ").append(site.site).append('\n').append(site.url())
                    .append("\nFrom: USGS National Water Information System, provisional");
            final int c = colorFor(total);
            placed.add(new double[] { site.lat, site.lon, total });
            out.add(new Area(1, amount + " - " + site.name, "USGS", c, geom, b.toString(), "",
                    new Reading(Reading.USGS, site.name, amount, b.toString(), "", site.lat, site.lon, total, c)));
        }
        return out;
    }

    /** The USGS gauges that keep daily totals, each over the run. */
    private List<Area> spans(Map<String, Map<String, Double>> recent) {
        final List<Area> out = new ArrayList<>();
        for (UsgsRain.Daily d : heldDaily.values()) {
            final UsgsRain.Span s = UsgsRain.span(d, recent == null ? null : recent.get(d.site), runStart, runEnd,
                    runDays);
            if (s == null)
                continue;
            final JSONObject geom = point(d.lat, d.lon);
            if (geom == null)
                continue;
            final String amount = inches(s.total);
            if (missedADay(s.byDay)) {
                leftOff.incrementAndGet();
                continue;
            }
            final StringBuilder b = new StringBuilder();
            b.append(amount).append(' ').append(runWords());
            b.append('\n').append(d.name);
            if (s.byDay.size() > 1 && s.byDay.size() <= 31) {
                b.append("\nBy the day:");
                for (Map.Entry<String, Double> e : s.byDay.entrySet())
                    if (e.getValue() > 0.004)
                        b.append('\n').append(dayName(e.getKey(), "EEE MMM d")).append("  ").append(inches(e.getValue()));
            }
            b.append("\nA day is the gauge's own, midnight to midnight standard time.");
            b.append("\nUSGS site ").append(d.site).append('\n').append(d.url())
                    .append("\nFrom: USGS National Water Information System, provisional");
            final int c = colorFor(s.total);
            placed.add(new double[] { d.lat, d.lon, s.total });
            out.add(new Area(1, amount + " - " + d.name, "USGS", c, geom, b.toString(), "",
                    new Reading(Reading.USGS, d.name, amount, b.toString(), "", d.lat, d.lon, s.total, c)));
        }
        return out;
    }

    /** One gauge's run: its whole days and today added. */
    private static final class Merged {
        Acis.Station station;
        double inches;
        boolean trace, todayIn;
        int missing;
    }

    /**
     * The climate record's daily gauges for one state, each with its total over the
     * run: the whole days, null when the run is today alone, and today, null when the
     * run ends before it. A gauge whose today is not in is not short a day: its report
     * comes in the morning.
     */
    private List<Area> acisAreas(List<Acis.Station> run, List<Acis.Station> today) {
        final Map<String, Merged> by = new java.util.LinkedHashMap<>();
        final int whole = wholeDays();
        if (run != null)
            for (Acis.Station s : run) {
                final Merged m = new Merged();
                m.station = s;
                m.inches = s.inches;
                m.trace = s.trace;
                m.missing = Math.min(whole, s.missing);
                by.put(key(s), m);
            }
        if (today != null)
            for (Acis.Station s : today) {
                Merged m = by.get(key(s));
                if (m == null) {
                    // No report on any whole day, a report today.
                    m = new Merged();
                    m.station = s;
                    m.missing = whole;
                    by.put(key(s), m);
                }
                m.inches += s.inches;
                m.trace = m.inches < 0.005 && (m.trace || s.trace);
                m.todayIn = true;
            }
        final List<Area> out = new ArrayList<>();
        for (Merged m : by.values()) {
            final Acis.Station s = m.station;
            if (s.volunteer)
                continue;
            if (m.missing > 0) {
                leftOff.incrementAndGet();
                continue;
            }
            final JSONObject geom = point(s.lat, s.lon);
            if (geom == null)
                continue;
            final String amount = m.trace ? "Trace" : inches(m.inches);
            final StringBuilder b = new StringBuilder();
            b.append(amount).append(' ').append(runWords());
            if (today != null && !m.todayIn)
                b.append("\nToday's report is not in yet.");
            b.append('\n').append(s.name).append(s.id.isEmpty() ? "" : " (" + s.id + ")");
            b.append("\nAn airport's gauge counts midnight to midnight; an NWS weather observer reads once a"
                    + " day at a set hour, and a day's report holds the 24 hours before it.");
            b.append("\nFrom: NOAA Regional Climate Centers (ACIS)");
            final int c = colorFor(m.inches);
            placed.add(new double[] { s.lat, s.lon, m.inches });
            out.add(new Area(1, amount + " - " + s.name, "NWS", c, geom, b.toString(), "",
                    new Reading(Reading.STATION, s.name, amount, b.toString(), "", s.lat, s.lon, m.inches, c)));
        }
        return out;
    }

    private static String key(Acis.Station s) {
        return s.id + "|" + s.name;
    }

    public static String inches(double v) {
        return v < 0.005 ? "0.00 in" : String.format(Locale.US, "%.2f in", v);
    }

    @Override
    protected AtmosphereFeatures.Drawn drawn(Area a, Geometry g, AttributeSet s) {
        final Icon icon;
        if (a.extra instanceof Reading) {
            final Reading r = (Reading) a.extra;
            final int ring = r.kind == Reading.USGS ? 0xFFFFFFFF
                    : STATION_RING;
            icon = icons.labelled(pill(r.name, r.amount.equals("Trace") ? "T" : r.amount), a.color, ring,
                    r.kind == Reading.STATION ? 12 : 14);
        } else {
            return null;
        }
        if (icon == null)
            return null;
        return new AtmosphereFeatures.Drawn(setName(a), a.title, g,
                AtmosphereFeatures.icon(icon.getImageUri(Icon.STATE_DEFAULT), icon.getWidth(),
                        icon.getHeight()), s);
    }

    /**
     * The pill: the gauge's name then the inches, so two gauges with the same amount
     * read apart on the map (operator, 2026-10-12: "it has to zoom way in if you're not
     * going to attach a name to the label"). Long USGS names are cut to the first words.
     */
    static String pill(String name, String amount) {
        String n = name == null ? "" : name.trim();
        if (n.length() > 24) {
            final int sp = n.lastIndexOf(' ', 24);
            n = (sp > 8 ? n.substring(0, sp) : n.substring(0, 24)).trim();
        }
        return n.isEmpty() ? amount : n + " " + amount;
    }
    @Override
    protected String summary(List<Area> areas) {
        return runSummary(areas);
    }

    /** "Rain Sun Oct 11 to now: 212 gauges. Most: 3.06 in at Henshaw Dam. 24 that missed ... left off." */
    private String runSummary(List<Area> areas) {
        int n = 0;
        Reading top = null;
        for (Area a : areas) {
            if (!(a.extra instanceof Reading))
                continue;
            final Reading r = (Reading) a.extra;
            n++;
            if (top == null || r.inches > top.inches)
                top = r;
        }
        final StringBuilder b = new StringBuilder("Rain ").append(runWords()).append(": ");
        final List<String> failed;
        synchronized (acisFailed) {
            failed = new ArrayList<>(acisFailed);
        }
        if (!failed.isEmpty()) {
            Collections.sort(failed);
            final StringBuilder f = new StringBuilder();
            for (String one : failed)
                f.append(f.length() > 0 ? " and " : "").append(one);
            b.insert(0, "Could not get the airports and NWS observers for " + f
                    + " just now; asking again in 15 minutes. ");
        }
        if (n == 0)
            b.append(state.isEmpty() && zoomedOut ? "no gauges here." : "no gauge with a full record here.");
        else
            b.append(n).append(n == 1 ? " gauge." : " gauges.");
        if (top != null && top.inches > 0.004)
            b.append(" Most: ").append(top.amount).append(" at ").append(top.name).append('.');
        final int off = leftOff.get();
        if (off > 0)
            b.append(' ').append(off).append(off == 1 ? " gauge" : " gauges")
                    .append(" that missed part of it, or disagree with the gauges around them, left off.");
        if (zoomedOut)
            b.append(" Zoom in for the USGS, county and RAWS gauges.");
        else if (readingsFailed)
            b.append(" Too many USGS gauges here for the phone over this time: zoom in.");
        if (agencyCapped > 0)
            b.append(" The county and RAWS gauges nearest the map center are shown; zoom in for the rest.");
        if (iemFailed)
            b.append(" The county, RAWS or airport gauges did not answer just now; asking again in 15 minutes.");
        if (!fallStart.isEmpty() && !zoomedOut)
            b.append(" Hours go back to ").append(momentName(IemRelay.hoursSince(System.currentTimeMillis())))
                    .append(" only, so the county and RAWS gauges show ").append(fallWords())
                    .append(", and the airports are left out.");
        if (relayMissing && !zoomedOut)
            b.append(" The county and RAWS gauges for part of this time are not ready yet.");
        if (!zoomedOut && !readingsRun() && !exactRun())
            b.append(" For a month or more, only the USGS gauges that keep daily totals show.");
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

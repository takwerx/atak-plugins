package com.atakmap.android.firecast.model;

import com.atakmap.android.firecast.Esri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * NASA's Fire Event Data Suite (Chen et al. 2022): a perimeter for every fire VIIRS
 * sees in the lower 48, drawn by an algorithm from the 375 m detections every 12 hours,
 * day pass and night pass. Not the official perimeter, which incident GIS draws; it is
 * there for the fire nobody has mapped yet and between their updates, and it is pixel
 * built, so its edges are 375 m steps.
 *
 * <p>Served open on NASA VEDA as OGC API Features. The snapshot collection holds each
 * fire's latest perimeter.
 */
public final class Feds {

    /** f=geojson, never f=json: this server answers f=json with a flat list and WKT geometry. */
    private static final String ITEMS = "https://openveda.cloud/api/features/collections/"
            + "public.eis_fire_snapshot_perimeter_nrt/items";
    /** A fire whose latest outline is older than this has gone out or gone quiet; it is left off the list. */
    public static final long RECENT_MS = 72L * 3600_000L;
    /** "CONUS|97224|2026-10-30T12:00:00": what an item id looks like, and all it may be. */
    private static final java.util.regex.Pattern ID = java.util.regex.Pattern.compile("[A-Z]{2,12}\\|[0-9.]{1,12}\\|[0-9T:-]{10,25}");
    /** The most features and vertices read from one answer; a real box holds a few dozen fires. */
    private static final int MAX_FEATURES = 200, MAX_VERTICES = 100_000;
    /**
     * The most corners a part keeps. NASA's outlines step along 375 m pixels; they are
     * simplified by distance (Douglas-Peucker), never by skipping every Nth corner, which
     * cut the tips off fingers (operator, 2026-10-30).
     */
    private static final int RING_VERTICES = 1500;
    /** Corners closer than this to the line that replaces them go: a tenth of a pixel. */
    private static final double SIMPLIFY_M = 30;

    /** One fire in a list: its box and numbers, not its outline. */
    public static final class Fire {
        /** The service's item id, "CONUS|fireid|step". */
        public final String id;
        public final double areaKm2, burningDays;
        /** The 12-hour step its outline was drawn at, epoch ms UTC. */
        public final long atMs;
        public final double south, west, north, east;

        public Fire(String id, double areaKm2, double burningDays, long atMs, double s, double w, double n, double e) {
            this.id = id;
            this.areaKm2 = areaKm2;
            this.burningDays = burningDays;
            this.atMs = atMs;
            south = s;
            west = w;
            north = n;
            east = e;
        }

        public double lat() {
            return (south + north) / 2d;
        }

        public double lon() {
            return (west + east) / 2d;
        }
    }

    /** One fire's outline, as the run's perimeter. */
    public static final class Perimeter {
        /** The largest part's outer ring, [lat, lon] pairs, not closed. */
        public final double[][] ring;
        /** Every part, the largest first: NASA maps one fire as several pieces. */
        public double[][][] rings;
        /** The 12-hour step it was drawn at, epoch ms UTC. */
        public final long atMs;
        /** Days between its first and last detection at that step. */
        public final double burningDays;

        Perimeter(double[][] ring, long atMs, double burningDays) {
            this.ring = ring;
            this.atMs = atMs;
            this.burningDays = burningDays;
        }
    }

    private Feds() {
    }

    /**
     * The fires in a box whose outline is under {@link #RECENT_MS} old, biggest first:
     * each one's box and numbers only, a few hundred bytes a fire, so a wide view lists
     * them without fetching their outlines. Worker thread. Throws when the service
     * cannot be read.
     */
    public static List<Fire> inBox(double s, double w, double n, double e) throws Exception {
        if (!(s >= -90 && n <= 90 && s < n && w >= -180 && e <= 180 && w < e))
            throw new IllegalArgumentException("box out of range");
        final String url = ITEMS + "?f=geojson&bbox-only=true&properties=fireid,farea,t,duration&sortby=-t&limit="
                + MAX_FEATURES + "&bbox=" + String.format(Locale.US, "%.4f,%.4f,%.4f,%.4f", w, s, e, n);
        final JSONObject o = new JSONObject(Esri.get(url));
        final JSONArray features = o.optJSONArray("features");
        final List<Fire> out = new ArrayList<>();
        if (features == null)
            return out;
        final long cutoff = System.currentTimeMillis() - RECENT_MS;
        final List<double[]> seen = new ArrayList<>();
        for (int f = 0; f < features.length() && f < MAX_FEATURES; f++) {
            final JSONObject feature = features.optJSONObject(f);
            if (feature == null)
                continue;
            final String id = feature.optString("id", "");
            final JSONObject props = feature.optJSONObject("properties");
            if (props == null || !ID.matcher(id).matches())
                continue;
            final long at = parseTime(props.optString("t", ""));
            if (at < cutoff || !com.atakmap.android.firecast.FedsSource.newest(feature.optJSONObject("geometry"), at, seen))
                continue;
            final List<double[][]> rings = outerRings(feature.optJSONObject("geometry"));
            if (rings.isEmpty())
                continue;
            double ss = 90, ww = 180, nn = -90, ee = -180;
            for (double[] p : rings.get(0)) {
                ss = Math.min(ss, p[0]);
                nn = Math.max(nn, p[0]);
                ww = Math.min(ww, p[1]);
                ee = Math.max(ee, p[1]);
            }
            // FEDS duration is days between the first and last detection, half a day a step.
            final double km2 = props.optDouble("farea", Double.NaN), days = props.optDouble("duration", Double.NaN);
            out.add(new Fire(id, km2 >= 0 && km2 < 1e6 ? km2 : Double.NaN, days >= 0 && days < 1e5 ? days : Double.NaN,
                    at, ss, ww, nn, ee));
        }
        java.util.Collections.sort(out, new java.util.Comparator<Fire>() {
            @Override
            public int compare(Fire a, Fire b) {
                return Double.compare(Double.isNaN(b.areaKm2) ? 0 : b.areaKm2, Double.isNaN(a.areaKm2) ? 0 : a.areaKm2);
            }
        });
        return out;
    }

    /** One fire's outline, every part, largest first. Worker thread. Throws when it cannot be read. */
    public static Perimeter outline(Fire fire) throws Exception {
        if (!ID.matcher(fire.id).matches())
            throw new IllegalArgumentException("not a FEDS id");
        final JSONObject feature = new JSONObject(Esri.get(ITEMS + "/" + Esri.enc(fire.id).replace("+", "%20") + "?f=geojson"));
        final List<double[][]> parts = new ArrayList<>();
        int vertices = 0;
        for (double[][] ring : outerRings(feature.optJSONObject("geometry"))) {
            vertices += ring.length;
            if (vertices > MAX_VERTICES)
                throw new IllegalStateException("satellite perimeter too large to read");
            parts.add(thin(ring));
        }
        if (parts.isEmpty())
            throw new IllegalStateException("no outline in the answer");
        java.util.Collections.sort(parts, new java.util.Comparator<double[][]>() {
            @Override
            public int compare(double[][] a, double[][] b) {
                return Double.compare(Spread.ringAcres(b), Spread.ringAcres(a));
            }
        });
        final JSONObject props = feature.optJSONObject("properties");
        final long at = props == null ? fire.atMs : Math.max(fire.atMs, parseTime(props.optString("t", "")));
        final Perimeter p = new Perimeter(parts.get(0), at, fire.burningDays);
        p.rings = parts.toArray(new double[0][][]);
        return p;
    }

    /**
     * "Rio Arriba County, NM" for a point, from the Census Bureau's geocoder; null off the
     * US or when it does not say. Worker thread. Throws when it cannot be read.
     */
    public static String place(double lat, double lon) throws Exception {
        if (!(Math.abs(lat) <= 90) || !(Math.abs(lon) <= 180))
            return null;
        final JSONObject o = new JSONObject(Esri.get("https://geocoding.geo.census.gov/geocoder/geographies/coordinates?x="
                + String.format(Locale.US, "%.5f&y=%.5f", lon, lat)
                + "&benchmark=Public_AR_Current&vintage=Current_Current&layers=Counties,States&format=json"));
        final JSONObject result = o.optJSONObject("result");
        final JSONObject g = result == null ? null : result.optJSONObject("geographies");
        if (g == null)
            return null;
        final String county = first(g.optJSONArray("Counties"), "NAME"), state = first(g.optJSONArray("States"), "STUSAB");
        if (county == null && state == null)
            return null;
        return county == null ? state : state == null ? county : county + ", " + state;
    }

    private static String first(JSONArray a, String key) {
        final JSONObject o = a == null ? null : a.optJSONObject(0);
        final String v = o == null ? "" : o.optString(key, "").trim();
        // Names only: letters, spaces and the punctuation county names carry, nothing longer than a name.
        return v.isEmpty() || v.length() > 60 || !v.matches("[\\p{L} .'\\-]+") ? null : v;
    }

    /** The outer ring of each polygon in a Polygon or MultiPolygon, [lat, lon]. */
    private static List<double[][]> outerRings(JSONObject g) {
        final List<double[][]> out = new ArrayList<>();
        if (g == null)
            return out;
        final String type = g.optString("type", "");
        final JSONArray c = g.optJSONArray("coordinates");
        if (c == null)
            return out;
        if ("Polygon".equals(type)) {
            final double[][] r = ring(c.optJSONArray(0));
            if (r != null)
                out.add(r);
        } else if ("MultiPolygon".equals(type)) {
            for (int i = 0; i < c.length(); i++) {
                final JSONArray poly = c.optJSONArray(i);
                final double[][] r = poly == null ? null : ring(poly.optJSONArray(0));
                if (r != null)
                    out.add(r);
            }
        }
        return out;
    }

    /** GeoJSON [lon, lat] positions to [lat, lon], dropping the closing repeat; null when not a usable ring. */
    private static double[][] ring(JSONArray a) {
        if (a == null || a.length() < 4 || a.length() > MAX_VERTICES)
            return null;
        final List<double[]> pts = new ArrayList<>(a.length());
        for (int i = 0; i < a.length(); i++) {
            final JSONArray p = a.optJSONArray(i);
            if (p == null || p.length() < 2)
                return null;
            final double lon = p.optDouble(0, Double.NaN), lat = p.optDouble(1, Double.NaN);
            if (!(Math.abs(lat) <= 90) || !(Math.abs(lon) <= 180))
                return null;
            pts.add(new double[] { lat, lon });
        }
        final double[] first = pts.get(0), last = pts.get(pts.size() - 1);
        if (first[0] == last[0] && first[1] == last[1])
            pts.remove(pts.size() - 1);
        return pts.size() < 3 ? null : pts.toArray(new double[0][]);
    }

    /**
     * Fewer corners, the same shape: Douglas-Peucker at {@link #SIMPLIFY_M}, the tolerance
     * doubled until the part fits {@link #RING_VERTICES}. A tip is a corner far from the
     * line that would replace it, so it is the last thing to go.
     */
    public static double[][] thin(double[][] ring) {
        if (ring.length <= 8)
            return ring;
        final double lat = ring[0][0];
        final double mLat = 111320d, mLon = 111320d * Math.max(0.02d, Math.cos(Math.toRadians(lat)));
        final double[] x = new double[ring.length], y = new double[ring.length];
        for (int i = 0; i < ring.length; i++) {
            x[i] = ring[i][1] * mLon;
            y[i] = ring[i][0] * mLat;
        }
        double tol = SIMPLIFY_M;
        boolean[] keep;
        int kept;
        do {
            keep = new boolean[ring.length];
            keep[0] = true;
            keep[ring.length - 1] = true;
            // The farthest corner from the first splits the ring into two open runs.
            int far = 0;
            double best = -1;
            for (int i = 1; i < ring.length; i++) {
                final double d = (x[i] - x[0]) * (x[i] - x[0]) + (y[i] - y[0]) * (y[i] - y[0]);
                if (d > best) {
                    best = d;
                    far = i;
                }
            }
            keep[far] = true;
            simplify(x, y, 0, far, tol, keep);
            simplify(x, y, far, ring.length - 1, tol, keep);
            kept = 0;
            for (boolean k : keep)
                if (k)
                    kept++;
            tol *= 2;
        } while (kept > RING_VERTICES && tol < 5000);
        final double[][] out = new double[kept][];
        for (int i = 0, j = 0; i < ring.length; i++)
            if (keep[i])
                out[j++] = ring[i];
        return out;
    }

    /** Keeps the corner farthest from the chord when it is past the tolerance, and recurses; no recursion depth to blow. */
    private static void simplify(double[] x, double[] y, int a, int b, double tol, boolean[] keep) {
        final java.util.ArrayDeque<int[]> todo = new java.util.ArrayDeque<>();
        todo.push(new int[] { a, b });
        while (!todo.isEmpty()) {
            final int[] r = todo.pop();
            final int i0 = r[0], i1 = r[1];
            if (i1 - i0 < 2)
                continue;
            final double dx = x[i1] - x[i0], dy = y[i1] - y[i0], len = Math.sqrt(dx * dx + dy * dy);
            int far = -1;
            double best = tol;
            for (int i = i0 + 1; i < i1; i++) {
                final double d = len == 0 ? Math.hypot(x[i] - x[i0], y[i] - y[i0])
                        : Math.abs(dy * (x[i] - x[i0]) - dx * (y[i] - y[i0])) / len;
                if (d > best) {
                    best = d;
                    far = i;
                }
            }
            if (far < 0)
                continue;
            keep[far] = true;
            todo.push(new int[] { i0, far });
            todo.push(new int[] { far, i1 });
        }
    }

    /** "2026-10-30T12:00:00", UTC with or without a zone; 0 when unreadable. */
    static long parseTime(String t) {
        if (t == null || t.length() < 19)
            return 0;
        try {
            final SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            return f.parse(t.substring(0, 19)).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    /** Across the widest way of every part together, meters. */
    public static double spanM(double[][][] rings) {
        final java.util.List<double[]> all = new ArrayList<>();
        for (double[][] r : rings)
            java.util.Collections.addAll(all, r);
        return spanM(all.toArray(new double[0][]));
    }

    /** Across the widest way, meters: whether the model's square can hold it. */
    public static double spanM(double[][] ring) {
        double s = 90, n = -90, w = 180, e = -180;
        for (double[] p : ring) {
            s = Math.min(s, p[0]);
            n = Math.max(n, p[0]);
            w = Math.min(w, p[1]);
            e = Math.max(e, p[1]);
        }
        final double mLon = 111320d * Math.max(0.02d, Math.cos(Math.toRadians((s + n) / 2)));
        return Math.max((n - s) * 111320d, (e - w) * mLon);
    }
}

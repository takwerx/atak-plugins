package com.atakmap.android.firecast.model;

import com.atakmap.android.firecast.Esri;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.log.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Fuel moisture at a fire, from the people who measure it.
 *
 * <p><b>Dead fuel, by size class.</b> The Forest Service's fire danger system (NFDRS
 * 2016, served by FEMS) works out 1, 10, 100 and 1000 hour moisture every hour at each
 * of about 2,000 RAWS, from the station's own weather with the sun and the rain in it,
 * observed up to the last hour and forecast a week ahead. That is what the humidity
 * estimate it replaces could not see. When FEMS does not answer, the 10 and 100 hour
 * come from the Fire Lab's 10 km grid of the same numbers (WFAS), four bytes read out
 * of a GeoTIFF. Measured 2026-10-17: a station's two days of hours are 7 KB.
 *
 * <p><b>Live fuel.</b> The National Fuel Moisture Database, now inside FEMS: brush,
 * grass and conifer needles clipped and weighed every two to four weeks at about 1,500
 * sites. The nearest site with a recent sample gives the brush and the grass; where
 * none is near, the station's seasonal figure (from its growing season index), and
 * where there is no station, the dry-season defaults.
 *
 * <p>Every answer is kept on disk under {@code tools/firecast/moisture}, so a run with
 * no network uses the last one, the station's forecast hours included. FEMS's anonymous
 * endpoints are the ones its own web pages call and are not a published API; anything
 * that does not parse is a fallback, never a guess. Worker thread for all of it.
 */
public final class FuelMoisture {

    private static final String TAG = "FireCast";
    private static final String FEMS = "https://fems.fs2c.usda.gov/";
    private static final String WFAS = "https://www.wfas.net/data/Current/";
    private static final long HOUR = 3_600_000L, DAY = 24 * HOUR;
    /** Farthest a station or a sample site is taken from, meters. */
    static final double REACH_M = 50_000;
    /** Oldest live sample taken. */
    static final long SAMPLE_AGE = 45 * DAY;

    private FuelMoisture() {
    }

    /** A fire danger station. */
    public static final class Station {
        public final int id;
        public final String name;
        public final double lat, lon;
        public final int elevFt;
        public double distanceM;

        Station(int id, String name, double lat, double lon, int elevFt) {
            this.id = id;
            this.name = name;
            this.lat = lat;
            this.lon = lon;
            this.elevFt = elevFt;
        }
    }

    /** One hour at a station, observed or forecast. Percent. */
    public static final class Hour {
        public final long at;
        public final boolean observed;
        public final double m1, m10, m100, m1000, herb, woody;

        Hour(long at, boolean observed, double m1, double m10, double m100, double m1000, double herb, double woody) {
            this.at = at;
            this.observed = observed;
            this.m1 = m1;
            this.m10 = m10;
            this.m100 = m100;
            this.m1000 = m1000;
            this.herb = herb;
            this.woody = woody;
        }
    }

    /** A station's hours around a run, oldest first. */
    public static final class Dead {
        public final Station station;
        public final List<Hour> hours;

        Dead(Station station, List<Hour> hours) {
            this.station = station;
            this.hours = hours;
        }

        /** The hour nearest a moment, within 90 minutes, an observed one over a forecast of the same hour; null for none. */
        public Hour at(long when) {
            Hour best = null;
            long bestD = Long.MAX_VALUE;
            for (Hour h : hours) {
                final long d = Math.abs(h.at - when);
                if (d > 90 * 60_000L)
                    continue;
                if (d < bestD || (d == bestD && h.observed && best != null && !best.observed)) {
                    best = h;
                    bestD = d;
                }
            }
            return best;
        }
    }

    /** One kind of live fuel, measured: the site's latest sampling of it. */
    public static final class Sample {
        public final String site;
        public final double distanceM;
        public final long sampledAt;
        /** The driest plant's average that day, percent of dry weight. */
        public final double value;
        /** That plant, lower case: "chamise", "mountain big sagebrush". */
        public final String what;
        /** How many kinds of plant were sampled there that day. */
        public final int kinds;

        Sample(String site, double distanceM, long sampledAt, double value, String what, int kinds) {
            this.site = site;
            this.distanceM = distanceM;
            this.sampledAt = sampledAt;
            this.value = value;
            this.what = what;
            this.kinds = kinds;
        }
    }

    /** The nearest recent measurements, each null when there is none in reach. */
    public static final class Live {
        public Sample brush, grass, needles;
    }

    // ---- dead fuel ------------------------------------------------------------------

    /**
     * The best station's hours around a moment: by distance and by height, the way the
     * weather stations are picked, and a station named {@code prefer} first (the RAWS
     * the run's weather came from) when it is in reach. The first of the nearest three
     * that has an hour within 90 minutes of {@code when}. Null when none has.
     */
    public static Dead dead(double lat, double lon, double elevFt, String prefer, long when) throws Exception {
        final List<Station> near = new ArrayList<>();
        for (Station s : stations()) {
            s.distanceM = meters(lat, lon, s.lat, s.lon);
            if (s.distanceM <= REACH_M)
                near.add(s);
        }
        final double startFt = elevFt;
        Collections.sort(near, new Comparator<Station>() {
            @Override
            public int compare(Station a, Station b) {
                return Double.compare(score(a), score(b));
            }

            private double score(Station s) {
                if (prefer != null && prefer.equalsIgnoreCase(s.name))
                    return -1;
                return s.distanceM / 1609.344d
                        + (Double.isNaN(startFt) || s.elevFt <= 0 ? 0 : Math.abs(s.elevFt - startFt) / 100d);
            }
        });
        for (int i = 0; i < near.size() && i < 3; i++) {
            final Station s = near.get(i);
            try {
                final Dead d = new Dead(s, hours(s, when));
                if (d.at(when) != null)
                    return d;
                Log.d(TAG, "moisture: " + s.name + " has no hour near the run");
            } catch (Exception e) {
                Log.w(TAG, "moisture: " + s.name + " failed: " + e);
            }
        }
        return null;
    }

    /** Every active station, from disk when the copy is under a week old. */
    static List<Station> stations() throws Exception {
        final File f = new File(dir(), "stations.json");
        String body = fresh(f, 7 * DAY) ? read(f) : null;
        if (body == null) {
            try {
                // The query is a constant. Apollo refuses a GET without the preflight
                // header, and answers one that would take HTML with its landing page.
                body = Esri.get(FEMS + "api/climatology/graphql/?query="
                        + java.net.URLEncoder.encode("query { stationMetaData(returnAll:true){ data{ station_id "
                                + "station_name latitude longitude elevation station_status } } }", "UTF-8"),
                        "apollo-require-preflight", "true", "Accept", "application/json");
                parseStations(body);   // a page that does not parse is not kept
                write(f, body);
            } catch (Exception e) {
                if (!f.isFile())
                    throw e;
                Log.w(TAG, "moisture: station list fetch failed, using the copy on disk: " + e);
                body = read(f);
            }
        }
        return parseStations(body);
    }

    static List<Station> parseStations(String body) throws Exception {
        final JSONArray a = new JSONObject(body).getJSONObject("data").getJSONObject("stationMetaData")
                .getJSONArray("data");
        final List<Station> out = new ArrayList<>(a.length());
        for (int i = 0; i < a.length(); i++) {
            final JSONObject o = a.optJSONObject(i);
            if (o == null || !"A".equals(o.optString("station_status")))
                continue;
            final int id = o.optInt("station_id", -1);
            final double la = o.optDouble("latitude", Double.NaN), lo = o.optDouble("longitude", Double.NaN);
            if (id <= 0 || Double.isNaN(la) || Double.isNaN(lo) || Math.abs(la) > 90 || Math.abs(lo) > 180)
                continue;
            out.add(new Station(id, o.optString("station_name", String.valueOf(id)), la, lo, o.optInt("elevation", 0)));
        }
        if (out.isEmpty())
            throw new IllegalStateException("no stations in the list");
        return out;
    }

    /**
     * A station's hours from three before {@code when} to two days after, from disk
     * when that copy is under an hour old or the network is gone.
     */
    static List<Hour> hours(Station s, long when) throws Exception {
        final File f = new File(dir(), "nfdr_" + s.id + ".csv");
        if (fresh(f, HOUR)) {
            final List<Hour> h = parseHours(read(f));
            if (covers(h, when))
                return h;
        }
        try {
            final SimpleDateFormat iso = utc("yyyy-MM-dd'T'HH:00:00'Z'");
            final String body = Esri.get(FEMS + "api/climatology/download-nfdr?stationIds=" + s.id
                    + "&startDate=" + iso.format(new Date(when - 3 * HOUR))
                    + "&endDate=" + iso.format(new Date(when + 48 * HOUR))
                    + "&dataset=all&dataFormat=csv&fuelModels=Y");
            final List<Hour> h = parseHours(body);
            if (!h.isEmpty())
                write(f, body);
            return h;
        } catch (Exception e) {
            if (!f.isFile())
                throw e;
            Log.w(TAG, "moisture: " + s.name + " fetch failed, using the copy on disk: " + e);
            return parseHours(read(f));
        }
    }

    private static boolean covers(List<Hour> hours, long when) {
        for (Hour h : hours)
            if (Math.abs(h.at - when) <= 90 * 60_000L)
                return true;
        return false;
    }

    /** The NFDR CSV, read by its header so a column added or moved does not shift the numbers. */
    static List<Hour> parseHours(String body) {
        final List<String[]> rows = csv(body);
        final List<Hour> out = new ArrayList<>();
        if (rows.isEmpty())
            return out;
        final String[] head = rows.get(0);
        final int cT = col(head, "observationTime"), cType = col(head, "NFDRType"),
                c1 = col(head, "oneHR_TL_FuelMoisture"), c10 = col(head, "tenHR_TL_FuelMoisture"),
                c100 = col(head, "hundredHR_TL_FuelMoisture"), c1000 = col(head, "thousandHR_TL_FuelMoisture"),
                cW = col(head, "woodyLFI_fuelMoisture"), cH = col(head, "herbaceousLFI_fuelMoisture");
        if (cT < 0 || cType < 0 || c1 < 0 || c10 < 0 || c100 < 0)
            return out;
        final SimpleDateFormat t = utc("yyyy-MM-dd'T'HH:mm:ss'Z'");
        for (int i = 1; i < rows.size(); i++) {
            final String[] r = rows.get(i);
            try {
                final long at = t.parse(cell(r, cT)).getTime();
                final double m1 = num(cell(r, c1)), m10 = num(cell(r, c10)), m100 = num(cell(r, c100));
                // Outside what dead fuel can hold is a broken row, not a wet day.
                if (!dead(m1) || !dead(m10) || !dead(m100))
                    continue;
                final double m1000 = num(cell(r, c1000));
                final double woody = num(cell(r, cW)), herb = num(cell(r, cH));
                out.add(new Hour(at, "O".equals(cell(r, cType)), m1, m10, m100, dead(m1000) ? m1000 : Double.NaN,
                        live(herb) ? herb : Double.NaN, live(woody) ? woody : Double.NaN));
            } catch (Exception ignored) {
            }
        }
        Collections.sort(out, new Comparator<Hour>() {
            @Override
            public int compare(Hour a, Hour b) {
                return Long.compare(a.at, b.at);
            }
        });
        return out;
    }

    private static boolean dead(double m) {
        return !Double.isNaN(m) && m >= 1 && m <= 60;
    }

    private static boolean live(double m) {
        return !Double.isNaN(m) && m >= 20 && m <= 400;
    }

    // ---- the 10 km grid, when FEMS is not there -------------------------------------

    /**
     * Today's 10 and 100 hour at a point from the Fire Lab's grid, {m10, m100}, the
     * day's driest; null outside it or when it does not answer.
     */
    public static double[] grid(double lat, double lon) {
        try {
            final double m10 = gridValue(WFAS + "MC10_Current_F_0.tif", lat, lon);
            final double m100 = gridValue(WFAS + "MC100_Current_F_0.tif", lat, lon);
            if (dead(m10) && dead(m100))
                return new double[] { m10, m100 };
        } catch (Exception e) {
            Log.w(TAG, "moisture: grid failed: " + e);
        }
        return null;
    }

    /**
     * One cell of an uncompressed float GeoTIFF in plain degrees, read without the
     * rest of it: the header, then the four bytes under the point. Anything else
     * (compression, tiles, a projection, another sample type) is refused.
     */
    static double gridValue(String url, double lat, double lon) throws Exception {
        final byte[] head = range(url, 0, 4095);
        final ByteBuffer b = ByteBuffer.wrap(head);
        if (head[0] == 'I' && head[1] == 'I')
            b.order(ByteOrder.LITTLE_ENDIAN);
        else if (head[0] == 'M' && head[1] == 'M')
            b.order(ByteOrder.BIG_ENDIAN);
        else
            throw new IllegalStateException("not a TIFF");
        if ((b.getShort(2) & 0xFFFF) != 42)
            throw new IllegalStateException("not a classic TIFF");
        final int ifd = b.getInt(4);
        if (ifd < 8 || ifd + 2 > head.length)
            throw new IllegalStateException("header past the first read");
        final int n = b.getShort(ifd) & 0xFFFF;
        int width = 0, height = 0, bits = 0, compression = 0, format = 1, spp = 1, planar = 1, rps = 0;
        int stripType = 0, stripCount = 0, stripAt = 0;
        double scaleX = 0, scaleY = 0, tieX = Double.NaN, tieY = Double.NaN;
        for (int i = 0; i < n; i++) {
            final int e = ifd + 2 + 12 * i;
            if (e + 12 > head.length)
                throw new IllegalStateException("header past the first read");
            final int tag = b.getShort(e) & 0xFFFF, type = b.getShort(e + 2) & 0xFFFF, count = b.getInt(e + 4);
            final int small = type == 3 ? b.getShort(e + 8) & 0xFFFF : b.getInt(e + 8);
            switch (tag) {
                case 256: width = small; break;
                case 257: height = small; break;
                case 258: bits = small; break;
                case 259: compression = small; break;
                case 277: spp = small; break;
                case 278: rps = small; break;
                case 284: planar = small; break;
                case 339: format = small; break;
                case 273:
                    // The offsets sit in the entry itself when they fit in its four bytes.
                    stripType = type;
                    stripCount = count;
                    stripAt = (long) count * (type == 3 ? 2 : 4) <= 4 ? e + 8 : b.getInt(e + 8);
                    break;
                case 33550:
                    if (type == 12 && count >= 2) {
                        final int at = b.getInt(e + 8);
                        if (at < 0 || at + 16 > head.length)
                            throw new IllegalStateException("scale past the first read");
                        scaleX = b.getDouble(at);
                        scaleY = b.getDouble(at + 8);
                    }
                    break;
                case 33922:
                    if (type == 12 && count >= 6) {
                        final int at = b.getInt(e + 8);
                        if (at < 0 || at + 48 > head.length)
                            throw new IllegalStateException("tie point past the first read");
                        if (b.getDouble(at) != 0 || b.getDouble(at + 8) != 0)
                            throw new IllegalStateException("tie point not at the corner");
                        tieX = b.getDouble(at + 24);
                        tieY = b.getDouble(at + 32);
                    }
                    break;
                default:
                    break;
            }
        }
        if (compression != 1 || bits != 32 || format != 3 || spp != 1 || planar != 1)
            throw new IllegalStateException("not an uncompressed float grid");
        if (width <= 0 || height <= 0 || width > 100_000 || height > 100_000 || !(scaleX > 0) || !(scaleY > 0)
                || Double.isNaN(tieX) || Double.isNaN(tieY))
            throw new IllegalStateException("no usable georeferencing");
        if (rps <= 0 || rps > height)
            rps = height;
        final int col = (int) Math.floor((lon - tieX) / scaleX), row = (int) Math.floor((tieY - lat) / scaleY);
        if (col < 0 || row < 0 || col >= width || row >= height)
            return Double.NaN;
        final int strip = row / rps;
        if (stripCount != (height + rps - 1) / rps || (stripType != 3 && stripType != 4))
            throw new IllegalStateException("strip table does not match the grid");
        final int size = stripType == 3 ? 2 : 4, at = stripAt + strip * size;
        if (stripAt < 8 || at + size > head.length)
            throw new IllegalStateException("strip table past the first read");
        final long stripOffset = stripType == 3 ? b.getShort(at) & 0xFFFF : b.getInt(at) & 0xFFFFFFFFL;
        final long offset = stripOffset + ((long) (row % rps) * width + col) * 4L;
        final byte[] v = range(url, offset, offset + 3);
        final ByteBuffer vb = ByteBuffer.wrap(v).order(b.order());
        final float f = vb.getFloat(0);
        return Float.isNaN(f) || Float.isInfinite(f) ? Double.NaN : f;
    }

    /** Bytes {@code from} to {@code to} of a file; refuses a server that sends the whole thing. */
    private static byte[] range(String url, long from, long to) throws Exception {
        if (!url.startsWith("https://"))
            throw new IllegalStateException("not https");
        final HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "FireCast (ATAK plugin)");
        c.setRequestProperty("Range", "bytes=" + from + "-" + to);
        try {
            if (c.getResponseCode() != 206)
                throw new IllegalStateException("WFAS answered " + c.getResponseCode() + " to a range");
            if (!"https".equals(c.getURL().getProtocol()))
                throw new IllegalStateException("not https");
            final int want = (int) (to - from + 1);
            final byte[] out = new byte[want];
            final InputStream in = c.getInputStream();
            try {
                int off = 0, r;
                while (off < want && (r = in.read(out, off, want - off)) > 0)
                    off += r;
                if (off < want && want <= 4)
                    throw new IllegalStateException("short read");
                return off == want ? out : java.util.Arrays.copyOf(out, off);
            } finally {
                in.close();
            }
        } finally {
            c.disconnect();
        }
    }

    // ---- live fuel ------------------------------------------------------------------

    /**
     * The nearest recent measurements of brush, grass and conifer needles: each from
     * the nearest site within reach whose latest sampling of it is under 45 days old.
     * Up to four sites are asked, nearest first. Never throws; a kind with nothing in
     * reach is null.
     */
    public static Live live(double lat, double lon, long now) {
        final Live out = new Live();
        final List<Object[]> near = new ArrayList<>();
        try {
            for (String[] r : sites()) {
                final double d = meters(lat, lon, num(r[2]), num(r[3]));
                if (d <= REACH_M)
                    near.add(new Object[] { d, Integer.parseInt(r[0]), r[1] });
            }
        } catch (Exception e) {
            Log.w(TAG, "moisture: sample sites unavailable: " + e);
            return out;
        }
        Collections.sort(near, new Comparator<Object[]>() {
            @Override
            public int compare(Object[] a, Object[] b) {
                return Double.compare((Double) a[0], (Double) b[0]);
            }
        });
        for (int i = 0; i < near.size() && i < 4 && (out.brush == null || out.grass == null || out.needles == null); i++) {
            final double d = (Double) near.get(i)[0];
            final int id = (Integer) near.get(i)[1];
            final String name = (String) near.get(i)[2];
            final List<String[]> rows;
            try {
                rows = samples(id, now);
            } catch (Exception e) {
                Log.w(TAG, "moisture: samples for site " + id + " failed: " + e);
                continue;
            }
            if (out.brush == null)
                out.brush = latest(rows, "brush", name, d, now);
            if (out.grass == null)
                out.grass = latest(rows, "grass", name, d, now);
            if (out.needles == null)
                out.needles = latest(rows, "needles", name, d, now);
        }
        return out;
    }

    /** The site list rows, [id, name, lat, lon], active sites only; from disk when under a week old. */
    static List<String[]> sites() throws Exception {
        final File f = new File(dir(), "sites.csv");
        String body = fresh(f, 7 * DAY) ? read(f) : null;
        if (body == null) {
            try {
                body = Esri.get(FEMS + "fuelmodel/siteMetadataDownload/download");
                if (parseSites(body).isEmpty())
                    throw new IllegalStateException("no sites in the list");
                write(f, body);
            } catch (Exception e) {
                if (!f.isFile())
                    throw e;
                Log.w(TAG, "moisture: site list fetch failed, using the copy on disk: " + e);
                body = read(f);
            }
        }
        return parseSites(body);
    }

    static List<String[]> parseSites(String body) {
        final List<String[]> rows = csv(body), out = new ArrayList<>();
        if (rows.isEmpty())
            return out;
        final String[] h = rows.get(0);
        final int cId = col(h, "Site ID"), cName = col(h, "Site Name"), cLat = col(h, "Latitude"),
                cLon = col(h, "Longitude"), cStatus = col(h, "Status");
        if (cId < 0 || cLat < 0 || cLon < 0)
            return out;
        for (int i = 1; i < rows.size(); i++) {
            final String[] r = rows.get(i);
            if (cStatus >= 0 && !"Active".equalsIgnoreCase(cell(r, cStatus)))
                continue;
            final String id = cell(r, cId);
            if (!id.matches("[0-9]{1,9}"))
                continue;
            final double la = num(cell(r, cLat)), lo = num(cell(r, cLon));
            if (Double.isNaN(la) || Double.isNaN(lo) || Math.abs(la) > 90 || Math.abs(lo) > 180)
                continue;
            out.add(new String[] { id, cell(r, cName), cell(r, cLat), cell(r, cLon) });
        }
        return out;
    }

    /** A site's samples over the last 60 days, [date, kind, value, what]; from disk when under 12 hours old or offline. */
    static List<String[]> samples(int site, long now) throws Exception {
        final File f = new File(dir(), "nfmd_" + site + ".csv");
        if (fresh(f, 12 * HOUR))
            return parseSamples(read(f));
        try {
            final SimpleDateFormat iso = utc("yyyy-MM-dd'T'00:00:00.000'Z'");
            // Every parameter must be present, empty or not, or the service answers 400.
            final String body = Esri.get(FEMS + "fuelmodel/sample/download?returnAll=true&responseFormat=csv"
                    + "&siteId=" + site + "&sampleId="
                    + "&startDate=" + iso.format(new Date(now - 60 * DAY))
                    + "&endDate=" + iso.format(new Date(now + DAY))
                    + "&filterByFuelId=&filterByStatus=All&filterByCategory=All&filterBySubCategory=All"
                    + "&filterByMethod=All&sortBy=&sortOrder=");
            final List<String[]> rows = parseSamples(body);
            write(f, body);
            return rows;
        } catch (Exception e) {
            if (!f.isFile())
                throw e;
            Log.w(TAG, "moisture: site " + site + " fetch failed, using the copy on disk: " + e);
            return parseSamples(read(f));
        }
    }

    static List<String[]> parseSamples(String body) {
        final List<String[]> rows = csv(body), out = new ArrayList<>();
        if (rows.isEmpty())
            return out;
        final String[] h = rows.get(0);
        int cDate = -1;
        for (int i = 0; i < h.length; i++)
            if (h[i].startsWith("Date-Time"))
                cDate = i;
        final int cFuel = col(h, "Fuel Type"), cCat = col(h, "Category"), cVal = col(h, "Sample Avg Value"),
                cStatus = col(h, "Sample Status");
        if (cDate < 0 || cFuel < 0 || cCat < 0 || cVal < 0)
            return out;
        for (int i = 1; i < rows.size(); i++) {
            final String[] r = rows.get(i);
            final String status = cell(r, cStatus);
            // Drafts are half-entered and archived ones withdrawn.
            if (cStatus >= 0 && !"Submitted".equals(status) && !"Edited".equals(status))
                continue;
            final String kind = kind(cell(r, cCat), cell(r, cFuel));
            if (kind == null)
                continue;
            out.add(new String[] { cell(r, cDate), kind, cell(r, cVal), cell(r, cFuel) });
        }
        return out;
    }

    /** "brush", "grass" or "needles" for a sample the model can use; null for dead fuel and broadleaf trees. */
    static String kind(String category, String fuel) {
        if ("Shrub".equalsIgnoreCase(category))
            return "brush";
        if ("Grass".equalsIgnoreCase(category) || "Forb".equalsIgnoreCase(category))
            return "grass";
        if ("Tree".equalsIgnoreCase(category)) {
            final String f = fuel.toLowerCase(Locale.US);
            for (String c : new String[] { "pine", "fir", "spruce", "hemlock", "cedar", "juniper", "pinyon", "larch",
                    "tamarack", "redwood", "cypress" })
                if (f.contains(c))
                    return "needles";
        }
        return null;
    }

    /** The site's latest sampling of a kind, averaged over that day; null when there is none or it is too old. */
    static Sample latest(List<String[]> rows, String kind, String site, double distanceM, long now) {
        final SimpleDateFormat t = utc("yyyy-MM-dd'T'HH:mm'Z'");
        long last = 0;
        for (String[] r : rows)
            if (kind.equals(r[1]))
                try {
                    last = Math.max(last, t.parse(r[0]).getTime());
                } catch (Exception ignored) {
                }
        if (last == 0 || now - last > SAMPLE_AGE)
            return null;
        // Each plant averaged over the day (old and new growth chamise are one plant),
        // then the driest of them: chamise at 75% beside black sage at 100% is a fire in
        // the chamise, and an average of the two is a plant that is not there.
        final java.util.Map<String, double[]> byPlant = new java.util.LinkedHashMap<>();
        for (String[] r : rows) {
            if (!kind.equals(r[1]))
                continue;
            try {
                if (Math.abs(t.parse(r[0]).getTime() - last) > 12 * HOUR)
                    continue;
            } catch (Exception e) {
                continue;
            }
            final double v = num(r[2]);
            if (!live(v))
                continue;
            final String plant = plain(r[3]);
            double[] s = byPlant.get(plant);
            if (s == null)
                byPlant.put(plant, s = new double[2]);
            s[0] += v;
            s[1]++;
        }
        String driest = null;
        double low = Double.MAX_VALUE;
        for (java.util.Map.Entry<String, double[]> e : byPlant.entrySet()) {
            final double v = e.getValue()[0] / e.getValue()[1];
            if (v < low) {
                low = v;
                driest = e.getKey();
            }
        }
        if (driest == null)
            return null;
        return new Sample(site, distanceM, last, low, driest, byPlant.size());
    }

    /** "Sagebrush, Mountain Big" to "mountain big sagebrush"; "Chamise, New Growth" to "chamise". */
    static String plain(String fuel) {
        final String f = fuel.trim();
        final int comma = f.indexOf(',');
        if (comma < 0)
            return f.toLowerCase(Locale.US);
        final String head = f.substring(0, comma).trim(), tail = f.substring(comma + 1).trim();
        if (tail.toLowerCase(Locale.US).contains("growth"))
            return head.toLowerCase(Locale.US);
        return (tail + " " + head).toLowerCase(Locale.US);
    }

    // ---- plumbing -------------------------------------------------------------------

    /** CSV with quoted fields, doubled quotes and line breaks inside quotes (the site remarks have them). */
    static List<String[]> csv(String text) {
        final List<String[]> rows = new ArrayList<>();
        final List<String> row = new ArrayList<>();
        final StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            final char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n')
                    i++;
                row.add(cell.toString());
                cell.setLength(0);
                if (row.size() > 1 || !row.get(0).isEmpty())
                    rows.add(row.toArray(new String[0]));
                row.clear();
            } else {
                cell.append(ch);
            }
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row.toArray(new String[0]));
        }
        return rows;
    }

    private static int col(String[] head, String name) {
        for (int i = 0; i < head.length; i++)
            if (name.equalsIgnoreCase(head[i].trim()))
                return i;
        return -1;
    }

    private static String cell(String[] r, int c) {
        return c >= 0 && c < r.length ? r[c].trim() : "";
    }

    private static double num(String s) {
        try {
            final double v = Double.parseDouble(s.trim());
            return Double.isInfinite(v) ? Double.NaN : v;
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    private static SimpleDateFormat utc(String pattern) {
        final SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f;
    }

    static double meters(double lat1, double lon1, double lat2, double lon2) {
        if (Double.isNaN(lat2) || Double.isNaN(lon2))
            return Double.MAX_VALUE;
        final double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        final double dp = p2 - p1, dl = Math.toRadians(lon2 - lon1);
        final double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * 6_371_000d * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    private static File dir() {
        final File d = FileSystemUtils.getItem("tools/firecast/moisture");
        if (!d.isDirectory() && !d.mkdirs())
            Log.w(TAG, "could not create " + d);
        return d;
    }

    private static boolean fresh(File f, long maxAge) {
        return f.isFile() && f.length() > 0 && System.currentTimeMillis() - f.lastModified() < maxAge;
    }

    private static String read(File f) throws Exception {
        if (f.length() > 8 << 20)
            throw new IllegalStateException(f.getName() + " is too large");
        final byte[] d = new byte[(int) f.length()];
        final InputStream in = new FileInputStream(f);
        try {
            int off = 0, r;
            while (off < d.length && (r = in.read(d, off, d.length - off)) > 0)
                off += r;
        } finally {
            in.close();
        }
        return new String(d, "UTF-8");
    }

    /** Into a temp file, then into place, so a crash mid-write leaves the old copy. */
    private static void write(File f, String body) throws Exception {
        final File tmp = new File(f.getPath() + ".tmp");
        final FileOutputStream out = new FileOutputStream(tmp);
        try {
            out.write(body.getBytes("UTF-8"));
        } finally {
            out.close();
        }
        if (!tmp.renameTo(f)) {
            if (!f.delete() || !tmp.renameTo(f))
                Log.w(TAG, "could not replace " + f);
        }
    }
}

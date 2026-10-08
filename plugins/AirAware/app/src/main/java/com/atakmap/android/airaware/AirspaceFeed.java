package com.atakmap.android.airaware;

import com.atakmap.android.airaware.net.Http;
import com.atakmap.coremap.log.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Class airspace and Special Use Airspace from the FAA's own feature service.
 *
 * <p>Keyless and public, published by {@code AeronauticalInformationServices_FAA} -- the
 * same ArcGIS organization behind the Digital Obstacle File and the FAA's UDDS viewer.
 * Nationally it holds about 16,800 polygons: 5,543 class shelves (B 550, C 1,942, D 1,879,
 * E 4,757, A 471, G 174) and 4,865 special use areas, plus center and Mode C boundaries we
 * do not draw. Verified live 2026-10-08.
 *
 * <p>Two things about it decide how this is written.
 *
 * <p><b>It is generous with vertices.</b> A plain box query over Los Angeles returned 47
 * rows and 43,857 vertices -- 1.6 MB of JSON for one metro area, which is not something to
 * pull on a pan. {@code maxAllowableOffset} makes the server generalize before it sends:
 * the same box at 0.002 degrees came back as 4,744 vertices and 106 KB, fifteen times
 * smaller, with every shelf still present. The offset is tied to how much map is on
 * screen, so the detail always matches what can be seen.
 *
 * <p><b>It is rate limited, and the quota is not ours alone.</b> The service answers
 * {@code HTTP 200} with an error body reading "API calls quota exceeded (… request units)!
 * maximum allowed request units (6000) per Minute" -- hit on 2026-10-08 after eight
 * queries, so the budget is shared with every other caller in the world. That rules out
 * fetching on every map move. Airspace changes on the 56-day publication cycle, so
 * {@link TfrManager} holds a long cache and this never retries a refusal: a 429 leaves the
 * last good picture alone.
 */
public final class AirspaceFeed {

    private static final String TAG = "AirspaceFeed";

    private static final String URL = "https://services6.arcgis.com/ssFJjBXIUyZDrSYZ"
            + "/arcgis/rest/services/Airspace/FeatureServer/0/query";

    /** Only what we draw. Dropping ARTCC, MODE-C, FIR and the rest halves the payload. */
    private static final String TYPES = "TYPE_CODE IN ('CLASS','R','P','W','A','MOA')";

    private static final String FIELDS = "OBJECTID,IDENT_TXT,NAME_TXT,CLASS_CODE,TYPE_CODE,"
            + "LOCALTYPE_TXT,DISTVERTLOWER_VAL,DISTVERTLOWER_UOM,DISTVERTLOWER_CODE,"
            + "DISTVERTUPPER_VAL,DISTVERTUPPER_UOM,DISTVERTUPPER_CODE,WORKHR_CODE";

    /** The service's own page size. */
    private static final int PAGE = 2000;
    /** Past this the fetch stops and the caller says the view holds more than was asked for. */
    private static final int MAX_ROWS = 4000;

    /** Thrown when the shared quota is spent, so the caller can keep what it has and wait. */
    public static class RateLimited extends IOException {
        public RateLimited(String message) {
            super(message);
        }
    }

    private AirspaceFeed() {
    }

    /**
     * Every shelf intersecting a box. Worker thread only.
     *
     * @param degreesOnScreen how much latitude the view spans, which sets how finely the
     *            server is asked to draw the boundaries
     */
    public static List<Airspace> inBox(double south, double west, double north, double east,
            double degreesOnScreen) throws IOException {
        final List<Airspace> out = new ArrayList<>();
        int offset = 0;
        while (out.size() < MAX_ROWS) {
            final JSONObject root = page(south, west, north, east, degreesOnScreen, offset);
            final JSONArray features = root.optJSONArray("features");
            if (features == null || features.length() == 0)
                break;
            for (int i = 0; i < features.length(); i++) {
                final Airspace a = one(features.optJSONObject(i));
                if (a != null && a.drawable() && !a.layer().isEmpty())
                    out.add(a);
            }
            if (!root.optBoolean("exceededTransferLimit", false))
                break;
            offset += PAGE;
        }
        return out;
    }

    private static JSONObject page(double south, double west, double north, double east,
            double degreesOnScreen, int offset) throws IOException {
        // The envelope is JSON: the comma form is accepted but silently drops the spatial
        // reference, and this service then matches nothing.
        final String envelope = String.format(Locale.US,
                "{\"xmin\":%.5f,\"ymin\":%.5f,\"xmax\":%.5f,\"ymax\":%.5f,"
                        + "\"spatialReference\":{\"wkid\":4326}}",
                west, south, east, north);
        final StringBuilder u = new StringBuilder(URL);
        u.append("?f=json&inSR=4326&outSR=4326&returnGeometry=true");
        u.append("&geometryType=esriGeometryEnvelope&spatialRel=esriSpatialRelIntersects");
        u.append("&geometry=").append(enc(envelope));
        u.append("&where=").append(enc(TYPES));
        u.append("&outFields=").append(enc(FIELDS));
        u.append("&maxAllowableOffset=").append(
                String.format(Locale.US, "%.6f", generalization(degreesOnScreen)));
        u.append("&resultRecordCount=").append(PAGE);
        if (offset > 0)
            u.append("&resultOffset=").append(offset);

        // Well under the limit where a long ArcGIS GET starts answering 404, because the
        // envelope is five numbers and the field list is fixed.
        final byte[] body = Http.get(u.toString());
        final JSONObject root;
        try {
            root = new JSONObject(new String(body, Charset.forName("UTF-8")));
        } catch (Exception e) {
            throw new IOException("the airspace was not readable");
        }
        // The service reports its own failures inside a 200, so the HTTP status says
        // nothing. The quota message is the one worth telling apart from a real error.
        final JSONObject error = root.optJSONObject("error");
        if (error != null) {
            final String message = error.optString("message", "the service refused the query");
            if (error.optInt("code", 0) == 429 || message.toLowerCase(Locale.US).contains("too many"))
                throw new RateLimited("the FAA airspace service is busy");
            throw new IOException(message);
        }
        return root;
    }

    /**
     * How coarsely the server may draw a boundary, in degrees.
     *
     * <p>Roughly a quarter of a percent of what is on screen, which is well under a pixel
     * at any zoom, with a floor so a close-in view does not ask for every published vertex
     * and a ceiling so a continent-wide view still has recognizable shapes.
     */
    private static double generalization(double degreesOnScreen) {
        if (!(degreesOnScreen > 0))
            return 0.002d;
        return Math.max(0.0004d, Math.min(0.02d, degreesOnScreen / 400d));
    }

    private static Airspace one(JSONObject f) {
        if (f == null)
            return null;
        final JSONObject at = f.optJSONObject("attributes");
        final JSONObject geom = f.optJSONObject("geometry");
        if (at == null || geom == null)
            return null;

        final Airspace a = new Airspace();
        a.id = at.isNull("OBJECTID") ? "" : Long.toString(at.optLong("OBJECTID", 0L));
        a.ident = at.optString("IDENT_TXT", "").trim();
        a.name = at.optString("NAME_TXT", "").trim();
        a.classCode = at.optString("CLASS_CODE", "").trim();
        a.typeCode = at.optString("TYPE_CODE", "").trim();
        a.localType = at.optString("LOCALTYPE_TXT", "").trim();
        a.workHours = at.optString("WORKHR_CODE", "").trim();
        vert(a.floor, at, "DISTVERTLOWER_VAL", "DISTVERTLOWER_UOM", "DISTVERTLOWER_CODE");
        vert(a.ceiling, at, "DISTVERTUPPER_VAL", "DISTVERTUPPER_UOM", "DISTVERTUPPER_CODE");

        final JSONArray rings = geom.optJSONArray("rings");
        if (rings == null)
            return null;
        readRings(a, rings);
        return a;
    }

    /**
     * The boundary.
     *
     * <p>ArcGIS puts every ring of a polygon in one flat list and distinguishes a hole
     * from an outline only by winding: outlines run one way, holes the other. So the sign
     * of the first ring's area is taken as "outline", and a later ring matching it starts
     * a new part while one against it is punched out of the part before. All 47 rows over
     * Los Angeles were single-ring, but Class E around a field inside a larger shelf is
     * the case this exists for, and a hole drawn solid would claim airspace that is not
     * there.
     */
    private static void readRings(Airspace a, JSONArray rings) {
        Boolean outlineSign = null;
        Airspace.Part current = null;
        for (int i = 0; i < rings.length(); i++) {
            final JSONArray ring = rings.optJSONArray(i);
            if (ring == null || ring.length() < 4)
                continue;
            final List<double[]> pts = new ArrayList<>(ring.length());
            double twice = 0;
            for (int j = 0; j < ring.length(); j++) {
                final JSONArray p = ring.optJSONArray(j);
                if (p == null || p.length() < 2)
                    continue;
                final double lon = p.optDouble(0, Double.NaN);
                final double lat = p.optDouble(1, Double.NaN);
                if (Double.isNaN(lat) || Double.isNaN(lon))
                    continue;
                pts.add(new double[] { lat, lon });
            }
            if (pts.size() < 3)
                continue;
            for (int j = 0, k = pts.size() - 1; j < pts.size(); k = j++)
                twice += pts.get(k)[1] * pts.get(j)[0] - pts.get(j)[1] * pts.get(k)[0];
            final boolean positive = twice >= 0;

            if (outlineSign == null) {
                outlineSign = positive;
                current = new Airspace.Part();
                current.outer.addAll(pts);
                a.parts.add(current);
            } else if (positive == outlineSign.booleanValue()) {
                current = new Airspace.Part();
                current.outer.addAll(pts);
                a.parts.add(current);
            } else if (current != null) {
                current.holes.add(pts);
            }
        }
    }

    /** The file's "no value" marker, which arrives as a number. See {@link #vert}. */
    private static final double NO_VALUE = -9998d;

    /**
     * One vertical limit, read into the same shape the TFR parser produces.
     *
     * <p>Two things here are not what they look like, and both were found by reading the
     * live answer rather than the schema.
     *
     * <p><b>{@code -9998} is "not published", not a height.</b> It arrives as an ordinary
     * number with no unit and no code beside it, and eleven of the forty-four shelves over
     * Los Angeles carried it -- every Class E transition area, whose top is the bottom of
     * whatever is above it and so is not a number the file can hold. Taken at face value
     * it is a ceiling 9,998 feet below the sea, and a shelf with that ceiling can never
     * contain anybody.
     *
     * <p><b>{@code SFC} means measured from the surface, not at the surface.</b> Burbank
     * Class E5 is published as {@code 700 SFC}: it begins 700 feet above the ground, with
     * Class G underneath it -- which is precisely the airspace a drone is flying in. Read
     * as "the surface" it would paint controlled airspace all the way down and hide the
     * uncontrolled layer that matters most. Only a value of zero is the surface itself.
     */
    private static void vert(TfrArea.Vert v, JSONObject at, String valKey, String uomKey,
            String codeKey) {
        final String uom = at.optString(uomKey, "").trim().toUpperCase(Locale.US);
        final String code = at.optString(codeKey, "").trim().toUpperCase(Locale.US);
        final double value = at.optDouble(valKey, Double.NaN);
        // A real limit always carries a code saying what it is measured from; the
        // sentinel never does.
        if (Double.isNaN(value) || value <= NO_VALUE || code.isEmpty())
            return;

        v.present = true;
        v.raw = (trimNumber(value) + " " + uom + " " + code).trim();
        if ("FL".equals(uom) || "STD".equals(code)) {
            v.flightLevel = true;
            v.feet = (int) Math.round(value * 100d);
            v.agl = false;
            return;
        }
        v.feet = (int) Math.round(value);
        v.agl = "SFC".equals(code) || "AGL".equals(code) || "HEI".equals(code);
        v.surface = v.agl && v.feet == 0;
    }

    private static String trimNumber(double d) {
        if (d == Math.rint(d))
            return Long.toString((long) d);
        return String.format(Locale.US, "%.1f", d);
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            // UTF-8 is always present; this is unreachable on Android.
            return s;
        }
    }

    static void debug(String message) {
        Log.d(TAG, message);
    }
}

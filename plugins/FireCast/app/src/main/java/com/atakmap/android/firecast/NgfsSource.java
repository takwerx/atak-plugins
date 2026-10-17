package com.atakmap.android.firecast;

import com.atakmap.map.layer.feature.geometry.Geometry;
import com.atakmap.map.layer.feature.geometry.LineString;
import com.atakmap.map.layer.feature.geometry.Polygon;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * GOES, straight from NOAA's Next Generation Fire System portal.
 *
 * <p>NIFC's GOES layer is this same system republished, and on 2026-10-17 the two were
 * equally fresh (both ending at the 04:31Z scan at 04:40Z). What the portal adds is
 * what the pixel actually is: its four ground corners as NOAA computed them, where the
 * NIFC copy carries only a center and a view angle and the footprint had to be
 * derived; a quality flag (1 robust, 3 persistence); NOAA's class as a number; and the
 * LANDFIRE fuel and land cover under the pixel. Both satellites, both CONUS scenes.
 * The service is an OGC API, keyless, and labeled an experimental beta, so NIFC's copy
 * stays as the fallback when it does not answer.
 */
public class NgfsSource implements DetectionSource {

    private static final String API = "https://fire.data.nesdis.noaa.gov/api/ogc/detections/collections/";
    private static final String[] SCENES = { "ngfs_schema.ngfs_detections_scene_west_conus", "ngfs_schema.ngfs_detections_scene_east_conus" };
    private static final int PAGE = 500;
    private final GoesSource nifc = new GoesSource();

    @Override
    public String id() {
        return "goes";
    }

    @Override
    public String title() {
        return "GOES";
    }

    @Override
    public String summary() {
        return "GOES-18 and GOES-19 from NOAA, each pixel's own four corners, a scan every few minutes";
    }

    @Override
    public boolean defaultOn() {
        return true;
    }

    private static String iso(long millis) {
        final java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return f.format(new java.util.Date(millis));
    }

    private static String items(String scene, Esri.Box box, long sinceMillis, int limit, String extra) {
        return API + scene + "/items?f=geojson&limit=" + limit
                + "&bbox=" + String.format(Locale.US, "%.4f,%.4f,%.4f,%.4f", box.west, box.south, box.east, box.north)
                + "&datetime=" + iso(sinceMillis) + "/.." + extra;
    }

    @Override
    public int count(Esri.Box box, long sinceMillis) throws Exception {
        try {
            int n = 0;
            for (String scene : SCENES)
                n += new JSONObject(Esri.get(items(scene, box, sinceMillis, 1, ""))).optInt("numberMatched", 0);
            return n;
        } catch (Exception e) {
            return nifc.count(box, sinceMillis);
        }
    }

    @Override
    public List<HeatDetection> fetch(Esri.Box box, long sinceMillis, int max) throws Exception {
        final List<HeatDetection> out = new ArrayList<>();
        try {
            for (String scene : SCENES) {
                String url = items(scene, box, sinceMillis, PAGE, "&sortby=-acq_date_time");
                int guard = 0;
                while (url != null && guard++ < 40 && (max <= 0 || out.size() < max)) {
                    final JSONObject page = new JSONObject(Esri.get(url));
                    final JSONArray feats = page.optJSONArray("features");
                    if (feats == null || feats.length() == 0)
                        break;
                    for (int i = 0; i < feats.length() && (max <= 0 || out.size() < max); i++) {
                        final JSONObject f = feats.getJSONObject(i);
                        final JSONObject a = f.optJSONObject("properties"), geom = f.optJSONObject("geometry");
                        if (a == null || geom == null)
                            continue;
                        final JSONObject esri = esriRings(geom);
                        final Geometry g = esri == null ? null : Esri.fromEsriJson(esri);
                        final HeatDetection d = g == null ? null : read(a, g, esri);
                        if (d != null)
                            out.add(d);
                    }
                    url = null;
                    final JSONArray links = page.optJSONArray("links");
                    for (int i = 0; links != null && i < links.length(); i++) {
                        final JSONObject l = links.getJSONObject(i);
                        if ("next".equals(l.optString("rel")) && l.optString("href", "").startsWith(API))
                            url = l.optString("href");
                    }
                }
            }
            return out;
        } catch (Exception e) {
            if (!out.isEmpty())
                return out;
            return nifc.fetch(box, sinceMillis, max);
        }
    }

    /** A GeoJSON polygon as the Esri rings the rest of the plugin saves and reads back. */
    static JSONObject esriRings(JSONObject geojson) {
        try {
            if (!"Polygon".equals(geojson.optString("type")))
                return null;
            final JSONArray rings = geojson.optJSONArray("coordinates");
            if (rings == null || rings.length() == 0)
                return null;
            return new JSONObject().put("rings", rings);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public HeatDetection read(JSONObject a, Geometry g, JSONObject raw) {
        if (g == null)
            return null;
        // A NIFC record (the fallback) comes through its own reader.
        if (a.has("total_frp") && !a.has("frp_transmittance"))
            return nifc.read(a, g, raw);
        final Double lat = Fields.number(a, "latitude"), lon = Fields.number(a, "longitude");
        if (lat == null || lon == null)
            return null;
        final String satellite = Fields.text(a, "satellite");
        final Double zenith = Fields.number(a, "satellite_zenith_angle");
        final Footprint fp = Footprint.pixel(g, lat, lon, zenith);
        final long when = isoMillis(Fields.text(a, "acq_date_time"));
        final HeatDetection d = new HeatDetection(id(), title(), satellite != null ? satellite : "GOES", lat, lon, when, fp);
        d.record = PolarSource.record(a, raw);
        // feature_frp is the whole fire NOAA is tracking; frp is this pixel. The one an
        // operator sizes a fire by is the whole.
        d.frpMw = Fields.number(a, "feature_frp");
        if (d.frpMw == null)
            d.frpMw = Fields.number(a, "frp");
        d.brightnessK = Fields.number(a, "bright_t7");
        final String dn = Fields.text(a, "daynight");
        if (dn != null)
            d.day = dn.trim().toUpperCase(Locale.US).startsWith("D");
        final Integer q = Fields.integer(a, "quality_flag");
        if (q != null && q == 1) {
            d.confidence = HeatDetection.Confidence.HIGH;
            d.confidenceText = "robust (NOAA quality 1)";
        } else if (q != null) {
            d.confidence = HeatDetection.Confidence.NOMINAL;
            d.confidenceText = "persistence: seen before, weaker now (NOAA quality " + q + ")";
        }
        d.classification = Fields.text(a, "type_description");
        d.incidentName = Fields.text(a, "known_incident_name");
        d.trackingId = Fields.text(a, "feature_tracking_id");
        d.persistenceHours = Fields.number(a, "feature_detection_duration");
        final Integer type = Fields.integer(a, "type");
        d.classedNonFire = (type != null && type >= 2)
                || (d.classification != null && (d.classification.toLowerCase(Locale.US).contains("solar")
                        || d.classification.toLowerCase(Locale.US).contains("reflective")
                        || d.classification.toLowerCase(Locale.US).contains("industrial")));
        final List<String> caveats = new ArrayList<>();
        caveats.add("The box is this pixel's own ground, as NOAA computed its corners, where the heat was at this scan; "
                + "a fire running since can be outside it. GOES says a fire started and roughly where, not where its edge is.");
        caveats.add("This pixel is " + fp.sizeText() + ". A crew sent to its middle can be a kilometer or more from the fire.");
        if (d.persistenceHours != null && d.persistenceHours < 0.5)
            caveats.add("Detected for under half an hour. A single hot scan can be a flare, a glint or a short-lived heat source as easily as a new fire.");
        if (q != null && q != 1)
            caveats.add("NOAA's persistence flag: the signal is weaker than its first look here; the fire may be cooling, or cloud is in the way.");
        if (d.classedNonFire)
            caveats.add("NOAA classed this as \"" + d.classification + "\" - its own reading is that this is probably not a wildfire.");
        final String fuel = Fields.text(a, "fuel");
        if (fuel != null && !fuel.isEmpty())
            caveats.add("Fuel under the pixel, from LANDFIRE: " + fuelWords(fuel) + ".");
        caveats.add("Cloud between the satellite and the ground suppresses or weakens a detection; the power reading is a floor, not a measurement of the fire.");
        d.caveats = caveats.toArray(new String[0]);
        d.raw = Esri.toAttributes(a);
        return d;
    }

    /** "FBFM10:49,FBFM5:17,Urban:3" as "timber litter 49%, brush 17%, urban 3%". */
    static String fuelWords(String fuel) {
        final StringBuilder sb = new StringBuilder();
        for (String part : fuel.split(",")) {
            final String[] kv = part.trim().split(":");
            if (kv.length != 2)
                continue;
            if (sb.length() > 0)
                sb.append(", ");
            sb.append(fuelName(kv[0].trim())).append(' ').append(kv[1].trim()).append('%');
        }
        return sb.toString();
    }

    private static String fuelName(String code) {
        switch (code.toUpperCase(Locale.US)) {
            case "FBFM1": return "short grass";
            case "FBFM2": return "timber grass";
            case "FBFM3": return "tall grass";
            case "FBFM4": return "chaparral";
            case "FBFM5": return "brush";
            case "FBFM6": return "dormant brush";
            case "FBFM7": return "southern rough";
            case "FBFM8": return "closed timber litter";
            case "FBFM9": return "hardwood litter";
            case "FBFM10": return "timber litter";
            case "FBFM11": return "light slash";
            case "FBFM12": return "medium slash";
            case "FBFM13": return "heavy slash";
            case "URBAN": return "urban";
            case "WATER": return "water";
            case "BARREN": return "bare";
            case "AGRICULTURE": return "farmed";
            default: return code.toLowerCase(Locale.US);
        }
    }

    /** "2026-10-17T04:31:17.000000Z" to epoch millis; 0 when it cannot be read. */
    static long isoMillis(String s) {
        if (s == null || s.length() < 19)
            return 0;
        try {
            final java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            return f.parse(s.substring(0, 19)).getTime();
        } catch (Exception e) {
            return 0;
        }
    }
}

package com.atakmap.android.firecast;

import com.atakmap.map.layer.feature.geometry.Geometry;
import com.atakmap.map.layer.feature.geometry.Point;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * VIIRS and MODIS, which NIFC serves with the same field set.
 *
 * <p>These two are the reason the plugin draws footprints rather than pins: they carry
 * {@code Scan} and {@code Track}, the sensor's own across-scan and along-track size for
 * that individual pixel. Off nadir those grow - MODIS is 1 km at nadir and reaches past
 * 4 km at the edge of the swath - so a nominal-resolution table would be wrong for most
 * of the detections on any given screen.
 *
 * <p>Both fields come as text with the unit in them ({@code "0.4 km"}), which is why
 * everything numeric goes through {@link Fields}.
 */
public class PolarSource implements DetectionSource {

    private final String id, title, summary, service;
    private final boolean defaultOn;
    /** VIIRS carries a word ("Nominal"); MODIS only a percentage. */
    private final boolean textConfidence;
    private final String[] caveats;

    private PolarSource(String id, String title, String summary, String service, boolean defaultOn,
            boolean textConfidence, String[] caveats) {
        this.id = id;
        this.title = title;
        this.summary = summary;
        this.service = service;
        this.defaultOn = defaultOn;
        this.textConfidence = textConfidence;
        this.caveats = caveats;
    }

    /** Common to both: what a polar-orbiting scanner's detection does not mean. */
    private static final String[] POLAR_CAVEATS = {
            "The box is where the heat was at the time of the pass, not where the fire is now. A fire that has been running since can be well outside it.",
            "This is one look from one pass. These satellites pass a few times a day, so a fire that started since the last pass is not on the map, and one that was put out may still be.",
            "Cloud, thick smoke and heavy canopy hide fires from this sensor. Empty map does not mean empty ground.",
            "Hot bare ground, flares, industry and some metal roofs read as heat. The industrial filter catches the known ones only.",
    };

    private static String[] with(String extra) {
        final String[] out = new String[POLAR_CAVEATS.length + 1];
        System.arraycopy(POLAR_CAVEATS, 0, out, 0, POLAR_CAVEATS.length);
        out[POLAR_CAVEATS.length] = extra;
        return out;
    }

    public static PolarSource viirs() {
        return new PolarSource("viirs", "VIIRS",
                "Suomi-NPP, NOAA-20 and NOAA-21, 375 m pixels, several passes a day",
                Sources.VIIRS, true, true,
                with("VIIRS resamples to a constant 375 m across the swath, so these boxes stay near 375 m even far off nadir."));
    }

    public static PolarSource modis() {
        return new PolarSource("modis", "MODIS",
                "Terra and Aqua, 1 km at nadir and coarser off it, several passes a day",
                Sources.MODIS, true, false,
                with("A MODIS pixel is 1 km at nadir and grows past 4 km at the edge of the swath. This one's real size is the footprint above."));
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String title() {
        return title;
    }

    @Override
    public String summary() {
        return summary;
    }

    @Override
    public boolean defaultOn() {
        return defaultOn;
    }

    private static String where(long sinceMillis) {
        return "DetectionDate >= timestamp '" + Sources.utc(sinceMillis) + "'";
    }

    @Override
    public int count(Esri.Box box, long sinceMillis) throws Exception {
        return Esri.count(service, where(sinceMillis), box);
    }

    @Override
    public List<HeatDetection> fetch(Esri.Box box, long sinceMillis, int max) throws Exception {
        final List<HeatDetection> out = new ArrayList<>();
        Esri.query(service, where(sinceMillis), box, "DetectionDate DESC", 1000, max, new Esri.FeatureSink() {
            @Override
            public void feature(JSONObject a, Geometry g, JSONObject raw) {
                final HeatDetection d = read(a, g, raw);
                if (d != null)
                    out.add(d);
            }
        });
        return out;
    }

    @Override
    public HeatDetection read(JSONObject a, Geometry g, JSONObject raw) {
        if (!(g instanceof Point))
            return null;
        final Point p = (Point) g;
        final HeatDetection d = read(a, p.getY(), p.getX());
        if (d != null)
            d.record = record(a, raw);
        return d;
    }

    static JSONObject record(JSONObject a, JSONObject raw) {
        final JSONObject r = new JSONObject();
        try {
            r.put("attributes", a);
            r.put("geometry", raw);
        } catch (Exception ignored) {
        }
        return r;
    }

    private HeatDetection read(JSONObject a, double lat, double lon) {
        // Scan and Track are this pixel's own size. Without them there is no honest
        // footprint, so the sensor's nominal one is used and the panel says so.
        final Double scan = Fields.distanceMeters(a, "Scan", 1000d);
        final Double track = Fields.distanceMeters(a, "Track", 1000d);
        final String sensor = Fields.text(a, "Sensor");
        final Footprint fp;
        if (scan != null && track != null) {
            fp = Footprint.measured(scan, track);
        } else {
            final double nominal = sensor != null && sensor.toUpperCase(Locale.US).contains("VIIRS") ? 375d : 1000d;
            fp = Footprint.nominal(nominal,
                    "the record carried no pixel size, so this is the sensor's size at nadir - the real pixel is this or larger");
        }
        final long when = Fields.epochMillis(a, "DetectionDate");
        final HeatDetection d = new HeatDetection(id, title,
                sensor != null ? sensor : title, lat, lon, when, fp);
        d.frpMw = Fields.number(a, "FRP");
        d.brightnessK = Fields.number(a, "Brightness");
        final String dayNight = Fields.text(a, "Day_Night");
        if (dayNight != null)
            d.day = Fields.is(dayNight, "Day");
        final Integer pct = Fields.integer(a, "Confidence");
        final String word = Fields.text(a, "Raw_Confidence");
        if (textConfidence && word != null) {
            d.confidence = level(word);
            d.confidenceText = word;
        } else if (pct != null) {
            // FIRMS' own MODIS classes: under 30 low, 30 to 80 nominal, 80 and over high.
            d.confidence = pct < 30 ? HeatDetection.Confidence.LOW
                    : pct < 80 ? HeatDetection.Confidence.NOMINAL : HeatDetection.Confidence.HIGH;
            d.confidenceText = pct + "%";
        }
        d.caveats = caveats;
        d.raw = Esri.toAttributes(a);
        return d;
    }

    private static HeatDetection.Confidence level(String word) {
        final String w = word.trim().toLowerCase(Locale.US);
        if (w.startsWith("h"))
            return HeatDetection.Confidence.HIGH;
        if (w.startsWith("l"))
            return HeatDetection.Confidence.LOW;
        if (w.startsWith("n"))
            return HeatDetection.Confidence.NOMINAL;
        return HeatDetection.Confidence.UNKNOWN;
    }
}

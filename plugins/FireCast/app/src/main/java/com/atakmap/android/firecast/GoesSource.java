package com.atakmap.android.firecast;

import com.atakmap.map.layer.feature.geometry.Geometry;
import com.atakmap.map.layer.feature.geometry.Point;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * GOES, from NOAA's Next Generation Fire System via NIFC.
 *
 * <p>The trade against the polar scanners is cadence for size: GOES looks at the same
 * ground every few minutes rather than a few times a day, and its fire pixels are 2 km
 * at nadir. That 2 km is the smallest they ever are. The feed carries no footprint
 * field, so it is computed from {@code satellite_zenith_angle} - see
 * {@link Footprint#geostationary} - and over the western United States, where the angle
 * runs near 58 degrees, one pixel is about 4 km by 2 km.
 *
 * <p>Two things this feed carries that no other does, and both are on the detail panel:
 * {@code feature_detection_duration} with {@code feature_tracking_id}, which is the
 * difference between one hot scan and something that has been burning for four hours;
 * and {@code type_description}, NOAA's own class, which explicitly calls out solar
 * farms and reflective structures.
 */
public class GoesSource implements DetectionSource {

    /** ABI's fire product resolution at the sub-satellite point. */
    private static final double NOMINAL_M = 2000d;

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
        return "GOES-18 and GOES-19, 2 km at nadir and larger off it, a scan every few minutes";
    }

    @Override
    public boolean defaultOn() {
        return true;
    }

    private static String where(long sinceMillis) {
        return "acq_date_time >= timestamp '" + Sources.utc(sinceMillis) + "'";
    }

    @Override
    public int count(Esri.Box box, long sinceMillis) throws Exception {
        return Esri.count(Sources.GOES, where(sinceMillis), box);
    }

    @Override
    public List<HeatDetection> fetch(Esri.Box box, long sinceMillis, int max) throws Exception {
        final List<HeatDetection> out = new ArrayList<>();
        Esri.query(Sources.GOES, where(sinceMillis), box, "acq_date_time DESC", 1000, max, new Esri.FeatureSink() {
            @Override
            public void feature(JSONObject a, Geometry g) {
                if (!(g instanceof Point))
                    return;
                final Point p = (Point) g;
                out.add(read(a, p.getY(), p.getX()));
            }
        });
        return out;
    }

    private HeatDetection read(JSONObject a, double lat, double lon) {
        final String satellite = Fields.text(a, "satellite");
        final Double zenith = Fields.number(a, "satellite_zenith_angle");
        final Footprint fp = zenith != null
                ? Footprint.geostationary(NOMINAL_M, zenith, satellite, lat, lon)
                : Footprint.nominal(NOMINAL_M,
                        "the record carried no view angle, so this is the 2 km pixel at nadir - off nadir it is larger");
        final long when = Fields.epochMillis(a, "acq_date_time");
        final HeatDetection d = new HeatDetection(id(), title(),
                satellite != null ? satellite : "GOES", lat, lon, when, fp);
        // total_frp is the whole detected feature; maximum_frp is its hottest pixel. The
        // one an operator is sizing a fire by is the total.
        d.frpMw = Fields.number(a, "total_frp");
        d.pixelCount = Fields.integer(a, "pixel_count");
        final String dn = Fields.text(a, "daynight");
        if (dn != null)
            d.day = dn.trim().toUpperCase(Locale.US).startsWith("D");
        final String conf = Fields.text(a, "confidence");
        if (conf != null) {
            d.confidenceText = conf;
            final String c = conf.toLowerCase(Locale.US);
            d.confidence = c.startsWith("h") ? HeatDetection.Confidence.HIGH
                    : c.startsWith("l") ? HeatDetection.Confidence.LOW
                            : c.startsWith("n") ? HeatDetection.Confidence.NOMINAL
                                    : HeatDetection.Confidence.UNKNOWN;
        }
        d.classification = Fields.text(a, "type_description");
        d.incidentName = Fields.text(a, "known_incident_name");
        d.trackingId = Fields.text(a, "feature_tracking_id");
        d.persistenceHours = Fields.number(a, "feature_detection_duration");
        // NOAA's own classes name the two false-positive families outright, so a
        // detection does not have to be inside the static mask to be flagged.
        if (d.classification != null) {
            final String c = d.classification.toLowerCase(Locale.US);
            d.classedNonFire = c.contains("solar") || c.contains("reflective");
        }
        final List<String> caveats = new ArrayList<>();
        caveats.add("The fire is somewhere inside this box, which is kilometers across. GOES says a fire started and roughly where, not where its edge is.");
        caveats.add("This box is " + fp.sizeText() + ". A crew sent to the middle of it can be a kilometer or more from the fire.");
        if (d.persistenceHours != null && d.persistenceHours < 0.5)
            caveats.add("Detected for under half an hour. A single hot scan can be a flare, a glint or a short-lived heat source as easily as a new fire.");
        if (d.classedNonFire)
            caveats.add("NOAA classed this as \"" + d.classification + "\" - its own reading is that this is probably not a wildfire.");
        caveats.add("Cloud between the satellite and the ground suppresses or weakens a detection; the power reading is a floor, not a measurement of the fire.");
        d.caveats = caveats.toArray(new String[0]);
        d.raw = Esri.toAttributes(a);
        return d;
    }
}

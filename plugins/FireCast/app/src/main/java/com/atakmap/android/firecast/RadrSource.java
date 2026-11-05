package com.atakmap.android.firecast;

import com.atakmap.map.layer.feature.geometry.Envelope;
import com.atakmap.map.layer.feature.geometry.Geometry;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * RADR: burn areas mapped from Landsat and Sentinel imagery, last fourteen days.
 *
 * <p>The odd one out, and worth having for exactly that reason. Every other feed here
 * is a hot pixel at a moment; RADR is a shape - the service serves real polygons, one
 * per mapped area, sometimes dozens of rings for one fire - classed {@code burned}
 * (scar) or {@code intense heat} (radiating at the pass). Nothing is derived: the
 * footprint is the geometry the service gave.
 *
 * <p>Which also makes it the feed most easily misread. A burn scar is where fire has
 * been, at the time of a satellite pass that may be days old, and an operator who reads
 * it as current fire has the picture backwards.
 */
public class RadrSource implements DetectionSource {

    @Override
    public String id() {
        return "radr";
    }

    @Override
    public String title() {
        return "RADR";
    }

    @Override
    public String summary() {
        return "Burn areas mapped from Landsat and Sentinel passes, last 14 days";
    }

    @Override
    public boolean defaultOn() {
        return false;
    }

    private static String where(long sinceMillis) {
        return "datetime >= timestamp '" + Sources.utc(sinceMillis) + "'";
    }

    @Override
    public int count(Esri.Box box, long sinceMillis) throws Exception {
        return Esri.count(Sources.RADR, where(sinceMillis), box);
    }

    @Override
    public List<HeatDetection> fetch(Esri.Box box, long sinceMillis, int max) throws Exception {
        final List<HeatDetection> out = new ArrayList<>();
        Esri.query(Sources.RADR, where(sinceMillis), box, "datetime DESC", 200, max, new Esri.FeatureSink() {
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
        final Envelope e = g == null ? null : g.getEnvelope();
        if (e == null || Double.isNaN(e.minX))
            return null;
        final HeatDetection d = read(a, g, (e.minY + e.maxY) / 2d, (e.minX + e.maxX) / 2d);
        if (d != null)
            d.record = PolarSource.record(a, raw);
        return d;
    }

    private HeatDetection read(JSONObject a, Geometry g, double lat, double lon) {
        final String sensor = Fields.text(a, "sensor");
        final HeatDetection d = new HeatDetection(id(), title(),
                sensor != null ? sensor : "RADR", lat, lon,
                Fields.epochMillis(a, "datetime"), Footprint.mapped(g, sensor));
        d.classification = Fields.text(a, "class_name");
        d.incidentName = Fields.text(a, "name");
        // The feed states no confidence of its own; an area someone mapped from imagery
        // is not the same kind of claim as a thresholded pixel, so it is left unknown
        // rather than given a number nobody measured.
        d.confidenceText = null;
        final boolean intense = d.classification != null
                && d.classification.toLowerCase(Locale.US).contains("intense");
        final List<String> caveats = new ArrayList<>();
        caveats.add("This is a mapped area, not a heat pixel: it is where fire has been as of that satellite pass, which can be days ago.");
        if (intense)
            caveats.add("Classed \"intense heat\": actively radiating at the moment of the pass. That moment has passed - the fire has moved since.");
        else
            caveats.add("Classed \"burned\": scar, not active fire. Ground inside it has already burned and is usually the safer ground.");
        caveats.add("Imagery satellites pass a given place every few days, so a fire that started since the last pass has no shape here at all.");
        d.caveats = caveats.toArray(new String[0]);
        d.raw = Esri.toAttributes(a);
        return d;
    }
}

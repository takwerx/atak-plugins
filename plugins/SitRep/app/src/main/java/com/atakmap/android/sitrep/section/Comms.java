package com.atakmap.android.sitrep.section;

import com.atakmap.android.sitrep.Area;
import com.atakmap.android.sitrep.net.Http;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Radio sites from the Comms plugin's catalog (PLAN 4.8): how many repeater sites are
 * in the area and within Nearby. A count only (operator, 2026-11-14): much of the
 * catalog comes from sources that may be shown only inside Comms, so no site name, net
 * or tone is read here and none goes into a report. Comms shows the detail.
 *
 * <p>A site counts when the catalog gives it at least one net, as Comms draws it; the
 * USFS permit layer lists sites with nothing known on them.
 */
public final class Comms extends Section {

    static final String CATALOG = "https://mapdepot.takwerx.org/comms/catalog.json";
    /** No site this close means the catalog does not cover the place yet. */
    private static final double COVERED_M = 100 * 1609.344;

    public Comms() {
        super("comms", "Radio sites", false,
                "The Comms plugin's catalog of repeater sites with known nets.");
        seeIn = "See the sites, their nets and tones in Comms.";
        openName = "Comms";
        openPackage = "com.atakmap.android.comms.plugin";
        openAction = null;
    }

    @Override
    public Result fetch(Area area) throws Exception {
        return fetch(area, 0);
    }

    @Override
    public Result fetch(Area area, int nearbyMiles) throws Exception {
        // Fixed text on a parse failure: Android's org.json puts the input into its
        // message, and the manager logs the exception, so catalog rows would reach logcat.
        final JSONObject cat;
        try {
            cat = new JSONObject(Http.get(CATALOG));
        } catch (org.json.JSONException e) {
            throw new java.io.IOException("the catalog is not valid JSON");
        }
        final JSONArray sites = cat.optJSONArray("sites"), channels = cat.optJSONArray("channels");
        if (sites == null || channels == null)
            throw new java.io.IOException("no sites in the catalog");
        final Set<String> withNets = new HashSet<>();
        for (int i = 0; i < channels.length(); i++) {
            final JSONObject c = channels.optJSONObject(i);
            if (c != null && !c.optString("site", "").isEmpty())
                withNets.add(c.optString("site"));
        }
        final double reach = nearbyMiles * 1609.344;
        int inside = 0, near = 0;
        boolean covered = false;
        for (int i = 0; i < sites.length(); i++) {
            final JSONObject s = sites.optJSONObject(i);
            if (s == null || !withNets.contains(s.optString("id", "")))
                continue;
            final double lat = s.optDouble("lat", Double.NaN), lon = s.optDouble("lon", Double.NaN);
            if (!(Math.abs(lat) <= 90) || !(Math.abs(lon) <= 180))
                continue;
            if (area.contains(lat, lon)) {
                inside++;
                covered = true;
                continue;
            }
            final double d = area.distanceMeters(lat, lon);
            if (d <= COVERED_M)
                covered = true;
            if (d <= reach)
                near++;
        }
        if (!covered) {
            final Result r = new Result("Not published here", null);
            r.notPublishedHere = true;
            return r.line("Comms has no radio sites near this area yet.");
        }
        final String within = nearbyMiles > 0 ? " within " + nearbyMiles + " mi" : "";
        final String value;
        if (inside == 0 && near == 0)
            value = nearbyMiles > 0 ? "None in or" + within : "None in the area";
        else if (near == 0)
            value = count(inside) + " in the area";
        else if (inside == 0)
            value = count(near) + within;
        else
            value = count(inside) + " in the area, " + count(near) + " more" + within;
        return new Result(value, null)
                .line("Repeater sites with known nets.");
    }

    private static String count(int n) {
        return String.format(Locale.US, "%,d", n);
    }
}

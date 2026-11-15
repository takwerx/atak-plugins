package com.atakmap.android.sitrep.section;

import com.atakmap.android.sitrep.Area;
import com.atakmap.android.sitrep.Units;
import com.atakmap.android.sitrep.net.Esri;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Wildfires and prescribed burns in the area and within Nearby, from WFIGS current
 * incident locations (PLAN 4.1, the source Feature Layer uses). Not yet contained
 * first, then by size. A fire is placed by its point of origin.
 */
public final class Fires extends Section {

    static final String LAYER = "https://services3.arcgis.com/T4QMspbfLg3qTGWY/arcgis/rest/services/"
            + "WFIGS_Incident_Locations_Current/FeatureServer/0";

    public Fires() {
        super("fires", "Fires", true,
                "NIFC WFIGS current incidents: every wildfire and prescribed burn reported to IRWIN, placed by its point of origin.");
        seeIn = "See them on the map in Feature Layer.";
        openName = "Feature Layer";
        openPackage = "com.atakmap.android.featurelayer.plugin";
        openAction = "com.atakmap.android.featurelayer.SHOW";
    }

    @Override
    public Result fetch(Area area) throws Exception {
        return fetch(area, 0);
    }

    @Override
    public Result fetch(Area area, int nearbyMiles) throws Exception {
        final List<JSONObject> rows = Esri.points(LAYER, "IncidentTypeCategory IN ('WF','RX','CX')", area,
                "IncidentName,IncidentSize,PercentContained,IncidentTypeCategory,FireDiscoveryDateTime,"
                        + "FireBehaviorGeneral,FireCauseGeneral,POOCounty,POOState",
                nearbyMiles);
        final List<JSONObject> fires = new ArrayList<>(), burns = new ArrayList<>();
        int inside = 0;
        for (JSONObject a : rows) {
            final double d = area.distanceMeters(a.optDouble("_lat"), a.optDouble("_lon"));
            try {
                a.put("_d", d);
            } catch (org.json.JSONException e) {
                continue;
            }
            if ("RX".equals(a.optString("IncidentTypeCategory")))
                burns.add(a);
            else {
                fires.add(a);
                if (d == 0)
                    inside++;
            }
        }
        final Comparator<JSONObject> order = new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject a, JSONObject b) {
                final boolean ao = contained(a) < 100, bo = contained(b) < 100;
                if (ao != bo)
                    return ao ? -1 : 1;
                return Double.compare(b.optDouble("IncidentSize", 0), a.optDouble("IncidentSize", 0));
            }
        };
        Collections.sort(fires, order);
        Collections.sort(burns, order);

        final String within = nearbyMiles > 0 ? " within " + nearbyMiles + " mi" : "";
        final String value;
        if (fires.isEmpty())
            value = "No wildfires" + (nearbyMiles > 0 ? " in or" + within : " in the area");
        else
            value = inside + " in the area" + (nearbyMiles > 0 ? ", " + (fires.size() - inside) + within : "");
        final Result r = new Result(burns.isEmpty() ? value
                : value + "; " + burns.size() + (burns.size() == 1 ? " prescribed burn" : " prescribed burns"),
                fires.isEmpty() ? null : fires.size() + (fires.size() == 1 ? " fire" : " fires"));
        for (JSONObject a : fires)
            add(r, a, area);
        if (!burns.isEmpty()) {
            r.line(GROUP + "Prescribed burns: " + burns.size());
            for (JSONObject a : burns)
                r.line("  " + name(a) + ", " + where(a, area) + size(a));
        }
        if (fires.isEmpty() && burns.isEmpty())
            r.line("No wildfire or prescribed burn is reported" + (nearbyMiles > 0 ? " in the area or" + within : " in the area") + ".");
        return r;
    }

    private static void add(Result r, JSONObject a, Area area) {
        final int pct = contained(a);
        r.line(GROUP + name(a) + ": " + (size(a).isEmpty() ? "size not reported" : size(a).substring(2))
                + (pct >= 0 ? ", " + pct + "% contained" : ""));
        r.line("  " + capital(where(a, area)) + ".");
        final long found = a.optLong("FireDiscoveryDateTime", 0);
        if (found > 0)
            r.line("  Started " + new SimpleDateFormat("MMM d HH:mm", Locale.US).format(new Date(found)) + ".");
        final String behavior = text(a, "FireBehaviorGeneral");
        if (behavior != null)
            r.line("  Behavior: " + behavior + ".");
        final String cause = text(a, "FireCauseGeneral");
        if (cause != null)
            r.line("  Cause: " + cause + ".");
        if ("CX".equals(a.optString("IncidentTypeCategory")))
            r.line("  Complex.");
    }

    private static String name(JSONObject a) {
        final String n = text(a, "IncidentName");
        return n == null ? "Unnamed" : n.length() > 60 ? n.substring(0, 60) : n;
    }

    private static String where(JSONObject a, Area area) {
        final double d = a.optDouble("_d", 0);
        if (d == 0)
            return "in the area";
        return Units.distance(d) + " " + area.directionTo(a.optDouble("_lat"), a.optDouble("_lon")) + " of the area";
    }

    /** {@code ", 1,240 acres"}, or empty when no size is reported. */
    private static String size(JSONObject a) {
        final double ac = a.optDouble("IncidentSize", Double.NaN);
        if (!(ac > 0))
            return "";
        return ", " + Units.area(ac * 4046.8564224);
    }

    private static int contained(JSONObject a) {
        if (a.isNull("PercentContained"))
            return -1;
        return Math.max(0, Math.min(100, (int) Math.round(a.optDouble("PercentContained", -1))));
    }

    private static String text(JSONObject a, String k) {
        if (a.isNull(k))
            return null;
        final String s = a.optString(k, "").trim();
        return s.isEmpty() ? null : s.length() > 80 ? s.substring(0, 80) : s;
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}

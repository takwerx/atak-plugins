package com.atakmap.android.atmosphere.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Public Safety Power Shutoffs in California, from Cal OES by way of takwerx's relay:
 * the counties a utility has told Cal OES may have a shutoff, and the areas where the
 * power has been cut for public safety. PG&E, SCE and SDG&E report to Cal OES;
 * PacifiCorp, Liberty and Bear Valley do not reach its public layers (survey
 * 2026-10-01, NOTES-atmosphere-psps-sources-west).
 *
 * <p>Through 0.16 the phone asked Cal OES itself. On 2026-10-16, during a PG&E
 * shutoff of 528 areas, the PSPS areas view it read went behind a login, answered
 * "Token Required" in a 200, and the plugin said "Power off: none". Now one file on
 * mapdepot carries both parts, each with the time Cal OES last rewrote it, and a
 * moved source is fixed on the relay ({@code tools/psps_relay.py} in the notes repo)
 * instead of in a release. The relay cleans every ring before it publishes.
 *
 * <p>Cal OES rewrites both parts every 15 minutes. A part past {@link #STALE_MS},
 * whether Cal OES or the relay stopped, is unknown, never "none".
 *
 * <p>No Android types; tested.
 */
public final class Psps {

    public static final String HOST = "mapdepot.takwerx.org";
    /** About 1 MB for 528 areas, 250 KB gzipped (2026-10-16). */
    public static final String URL = "https://" + HOST + "/atmosphere/psps/psps.json";

    /** Past this Cal OES has missed three of its 15-minute rewrites: status unknown. */
    public static final long STALE_MS = 45L * 60 * 1000;

    /**
     * What each kind of area is called on the map and in the key, and its color. An
     * area off is called by its cause, not "Power Off": the outage map holds every
     * outage, and only those Cal OES files as PUBLIC SAFETY are shutoffs (operator,
     * 2026-10-16: "power being off vs public power shutoff are two things").
     */
    public static final String POSSIBLE = "Shutoff Possible", OFF = "PSPS";
    public static final String[] KINDS = { POSSIBLE, OFF };
    public static final int[] COLORS = { 0xFFFFB300, 0xFFD50000 };

    public static int color(String kind) {
        for (int i = 0; i < KINDS.length; i++)
            if (KINDS[i].equals(kind))
                return COLORS[i];
        return COLORS[1];
    }

    /** Text on the kind's tile: black on amber, white on red. */
    public static int textColor(String kind) {
        return OFF.equals(kind) ? 0xFFFFFFFF : 0xFF000000;
    }

    /**
     * A flagged county or a shutoff area. Customers and the two times are the area's;
     * 0 when Cal OES did not say (one area of 528 had no restore estimate).
     */
    public static final class Area {
        public final String kind, county, utility;
        public final int customers;
        public final long since, restore;
        public final JSONObject geometry;

        Area(String kind, String county, String utility, int customers, long since,
                long restore, JSONObject geometry) {
            this.kind = kind;
            this.county = county;
            this.utility = utility;
            this.customers = customers;
            this.since = since;
            this.restore = restore;
            this.geometry = geometry;
        }
    }

    /**
     * The relay's file. A part's {@code updated} is when Cal OES last rewrote it, UTC
     * ms, 0 when it never has been read; {@code rows} is how many county rows Cal OES
     * stamped (58 when whole).
     */
    public static final class Feed {
        public final long countiesUpdated, areasUpdated;
        public final int countyRows;
        public final List<Area> areas;

        Feed(long countiesUpdated, int countyRows, long areasUpdated, List<Area> areas) {
            this.countiesUpdated = countiesUpdated;
            this.countyRows = countyRows;
            this.areasUpdated = areasUpdated;
            this.areas = areas;
        }
    }

    /**
     * Reads the relay's file. Anything else, an error body or a page from a proxy,
     * comes back null, so the caller reports a failed request rather than an empty
     * map.
     */
    public static Feed parse(String body) {
        if (body == null || body.isEmpty())
            return null;
        final JSONObject root;
        try {
            root = new JSONObject(body);
        } catch (Exception e) {
            return null;
        }
        final JSONObject meta = root.optJSONObject("psps");
        final JSONArray fs = root.optJSONArray("features");
        if (meta == null || fs == null)
            return null;
        final JSONObject c = meta.optJSONObject("counties");
        final JSONObject a = meta.optJSONObject("areas");
        final List<Area> out = new ArrayList<>();
        for (int i = 0; i < fs.length(); i++) {
            final JSONObject f = fs.optJSONObject(i);
            final JSONObject p = f == null ? null : f.optJSONObject("properties");
            final JSONObject g = f == null ? null : f.optJSONObject("geometry");
            if (p == null || g == null)
                continue;
            final String k = p.optString("k", "");
            if (k.equals("possible"))
                out.add(new Area(POSSIBLE, p.optString("county", ""), "", 0, 0, 0, g));
            else if (k.equals("off"))
                out.add(new Area(OFF, p.optString("county", ""), p.optString("utility", ""),
                        p.optInt("customers", 0), p.isNull("since") ? 0 : p.optLong("since", 0),
                        p.isNull("restore") ? 0 : p.optLong("restore", 0), g));
        }
        return new Feed(c == null ? 0 : c.optLong("updated", 0), c == null ? 0 : c.optInt("rows", 0),
                a == null ? 0 : a.optLong("updated", 0), out);
    }

    private Psps() {
    }
}

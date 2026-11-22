package com.atakmap.android.atmosphere.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Public Safety Power Shutoffs in California by way of takwerx's relay: the counties a
 * utility has told Cal OES may have a shutoff, the circuits SCE and SDG&E are watching,
 * and the areas shut off. PG&E's areas come from Cal OES's outage map, SCE's and
 * SDG&E's from their own maps, which the operator accepted on 2026-10-16 when SCE had
 * 413 segments off that Cal OES did not carry. PacifiCorp, Liberty and Bear Valley do
 * not publish to either (survey 2026-10-01, NOTES-atmosphere-psps-sources-west).
 *
 * <p>Through 0.16 the phone asked Cal OES itself. On 2026-10-16, during a PG&E
 * shutoff of 528 areas, the PSPS areas view it read went behind a login, answered
 * "Token Required" in a 200, and the plugin said "Power off: none". Now one file on
 * mapdepot carries both parts, each with the time Cal OES last rewrote it, and a
 * moved source is fixed on the relay ({@code tools/psps_relay.py} in the notes repo)
 * instead of in a release. The relay cleans every ring before it publishes.
 *
 * <p>Each part carries a time: Cal OES's own rewrite (every 15 minutes) for the
 * counties and PG&E, the relay's last good read for SCE and SDG&E, whose maps change
 * only when something does. A part past {@link #STALE_MS}, whether its source or the
 * relay stopped, is unknown, never "none".
 *
 * <p>No Android types; tested.
 */
public final class Psps {

    public static final String HOST = "mapdepot.takwerx.org";
    /** 2.1 MB for 1,400 areas, 460 KB gzipped (2026-10-16). */
    public static final String URL = "https://" + HOST + "/atmosphere/psps/psps.json";

    /** Past this Cal OES has missed three of its 15-minute rewrites: status unknown. */
    public static final long STALE_MS = 45L * 60 * 1000;

    /**
     * What each kind of area is called on the map and in the key, and its color. An
     * area off is called by its cause, not "Power Off": the outage map holds every
     * outage, and only those Cal OES files as PUBLIC SAFETY are shutoffs (operator,
     * 2026-10-16: "power being off vs public power shutoff are two things"). A watch is
     * one circuit, where the possible shutoff is a whole county.
     */
    public static final String POSSIBLE = "Shutoff Possible", WATCH = "PSPS Watch",
            OFF = "PSPS";
    public static final String[] KINDS = { POSSIBLE, WATCH, OFF };
    public static final int[] COLORS = { 0xFFFFB300, 0xFFFF6D00, 0xFFD50000 };

    public static int color(String kind) {
        for (int i = 0; i < KINDS.length; i++)
            if (KINDS[i].equals(kind))
                return COLORS[i];
        return COLORS[1];
    }

    /** Text on the kind's tile: black on amber and orange, white on red. */
    public static int textColor(String kind) {
        return OFF.equals(kind) ? 0xFFFFFFFF : 0xFF000000;
    }

    /**
     * A flagged county, a watched circuit or a shutoff area. Text is "" and a number 0
     * when the source did not say (one PG&E area of 528 had no restore estimate). The
     * times are UTC ms: off since, the utility's shutoff window, expected back on.
     * SDG&E gives its times as its own strings, kept as they are.
     */
    public static final class Area {
        public final String kind, county, community, utility, circuit, sinceText, restoreText;
        public final int customers;
        public final long since, start, end, restore;
        public final JSONObject geometry;

        Area(String kind, JSONObject p, JSONObject geometry) {
            this.kind = kind;
            this.county = p.optString("county", "");
            this.community = p.optString("community", "");
            this.utility = p.optString("utility", "");
            this.circuit = p.optString("circuit", "");
            this.sinceText = p.optString("since_text", "");
            this.restoreText = p.optString("restore_text", "");
            this.customers = p.optInt("customers", 0);
            this.since = time(p, "since");
            this.start = time(p, "start");
            this.end = time(p, "end");
            this.restore = time(p, "restore");
            this.geometry = geometry;
        }

        private static long time(JSONObject p, String key) {
            return p.isNull(key) ? 0 : Math.max(0, p.optLong(key, 0));
        }
    }

    /** One source of shutoff areas, "PG&E", "SCE" or "SDG&E", and its time. */
    public static final class Source {
        public final String name;
        public final long updated;

        Source(String name, long updated) {
            this.name = name;
            this.updated = updated;
        }
    }

    /**
     * The relay's file. A part's {@code updated} is UTC ms, 0 when it never has been
     * read; {@code countyRows} is how many county rows Cal OES stamped (58 when whole).
     */
    public static final class Feed {
        public final long countiesUpdated;
        public final int countyRows;
        public final List<Source> sources;
        public final List<Area> areas;

        Feed(long countiesUpdated, int countyRows, List<Source> sources, List<Area> areas) {
            this.countiesUpdated = countiesUpdated;
            this.countyRows = countyRows;
            this.sources = sources;
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
        final JSONArray src = meta.optJSONArray("sources");
        if (c == null || src == null)
            return null;
        final List<Source> sources = new ArrayList<>();
        for (int i = 0; i < src.length(); i++) {
            final JSONObject o = src.optJSONObject(i);
            if (o != null && !o.optString("name", "").isEmpty())
                sources.add(new Source(o.optString("name", ""), Math.max(0, o.optLong("updated", 0))));
        }
        final List<Area> out = new ArrayList<>();
        for (int i = 0; i < fs.length(); i++) {
            final JSONObject f = fs.optJSONObject(i);
            final JSONObject p = f == null ? null : f.optJSONObject("properties");
            final JSONObject g = f == null ? null : f.optJSONObject("geometry");
            if (p == null || g == null)
                continue;
            switch (p.optString("k", "")) {
                case "possible":
                    out.add(new Area(POSSIBLE, p, g));
                    break;
                case "watch":
                    out.add(new Area(WATCH, p, g));
                    break;
                case "off":
                    out.add(new Area(OFF, p, g));
                    break;
                default:
                    break;
            }
        }
        return new Feed(Math.max(0, c.optLong("updated", 0)), c.optInt("rows", 0), sources, out);
    }

    private Psps() {
    }
}

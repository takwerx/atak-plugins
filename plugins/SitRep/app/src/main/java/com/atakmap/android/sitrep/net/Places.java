package com.atakmap.android.sitrep.net;

import com.atakmap.coremap.maps.coords.GeoPoint;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Cities, towns, unincorporated communities and counties by name, and their outlines,
 * from Census TIGERweb (PLAN decision 13).
 */
public final class Places {

    static final String BASE = "https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/";
    /** Incorporated places, census designated places, counties. */
    static final String[] LAYERS = {
            "Places_CouSub_ConCity_SubMCD/MapServer/4",
            "Places_CouSub_ConCity_SubMCD/MapServer/5",
            "State_County/MapServer/1" };

    /** One answer: what to show, and where to fetch the outline. */
    public static final class Place {
        public final String label;
        final String layer, geoid;
        public final String state;
        final double areaLand;
        /** A named town with no Census boundary (GNIS): its point; NaN otherwise. */
        final double lat, lon;
        /** How it matched: 0 the start of the name, 1 inside it, 2 a near spelling. */
        int tier;
        /** The bare name, as {@link #canon}: to match a nickname. */
        String base = "";

        Place(String label, String layer, String geoid, String state, double areaLand) {
            this(label, layer, geoid, state, areaLand, Double.NaN, Double.NaN);
        }

        Place(String label, String layer, String geoid, String state, double areaLand, double lat, double lon) {
            this.label = label;
            this.layer = layer;
            this.geoid = geoid;
            this.state = state;
            this.areaLand = areaLand;
            this.lat = lat;
            this.lon = lon;
        }
    }

    private Places() {
    }

    /**
     * Places whose name starts with what was typed, the given state first, then the
     * largest. At most 40.
     */
    public static List<Place> search(String typed, final String preferState) throws Exception {
        return search(typed, preferState, null);
    }

    /** As above, nearest to the map's center first when it is given. */
    public static List<Place> search(String typed, final String preferState, final GeoPoint center) throws Exception {
        // "Española" typed or "Espanola": compared without accents from here on.
        String t = plain(typed == null ? "" : typed).trim().toUpperCase(Locale.US).replaceAll("[^A-Z0-9 .,'-]", "");
        // "bayview idaho", "Bayview, ID": the state off the end, as a filter (2026-11-13:
        // the whole string was matched as a name and found nothing).
        String fips = null;
        final String[] typedState = stateAtEnd(t);
        if (typedState != null) {
            fips = typedState[0];
            t = typedState[1];
        }
        final String q = t.replace(",", " ").replaceAll("\\s+", " ").trim();
        // "Orange County", "City of Miami", "Jefferson Parish": Census keeps the kind
        // apart from the name (BASENAME "Orange"), so the name is searched with and
        // without the kind word; a county word lists counties first.
        String kindless = null;
        boolean wantCounty = false;
        final java.util.regex.Matcher lead = java.util.regex.Pattern
                .compile("^(CITY|TOWN|VILLAGE|BOROUGH|TOWNSHIP|COUNTY) OF (.{2,})$").matcher(q);
        final java.util.regex.Matcher tail = java.util.regex.Pattern
                .compile("^(.{2,}?) (COUNTY|CNTY|PARISH|BOROUGH|CITY|TOWN|VILLAGE|TOWNSHIP|TWP|CDP)\\.?$").matcher(q);
        if (lead.matches()) {
            kindless = lead.group(2);
            wantCounty = "COUNTY".equals(lead.group(1));
        } else if (tail.matches()) {
            kindless = tail.group(1);
            wantCounty = tail.group(2).matches("COUNTY|CNTY|PARISH|BOROUGH");
        }
        final List<Place> out = new ArrayList<>();
        if (q.replace(" ", "").length() < 2)
            return out;
        // Names crews use: "NYC", "Philly", "New York City".
        final String[] nickTo = NICK.get(q) != null ? NICK.get(q) : kindless == null ? null : NICK.get(kindless);
        final List<String> spellings = new ArrayList<>();
        for (String x : spellings(q))
            add(spellings, x);
        if (kindless != null)
            for (String x : spellings(kindless))
                add(spellings, x);
        // "Mc Allen", "De Kalb", "Winston Salem": also run together and hyphenated.
        final String typedName = kindless != null ? kindless : q;
        if (typedName.contains(" ") && !typedName.contains(".")) {
            add(spellings, typedName.replace(" ", ""));
            add(spellings, typedName.replace(" ", "-"));
        }
        while (spellings.size() > 10)
            spellings.remove(spellings.size() - 1);
        final List<String> sql = new ArrayList<>();
        for (String x : spellings)
            sql.add(x.replace("'", "''"));
        final String inState = fips == null ? "" : " AND STATE='" + fips + "'";
        final java.util.Set<String> named = new java.util.HashSet<>();
        // A nickname is asked for on its own, in its state: in with the other spellings
        // ("LA" and "LOS ANGELES") the 25-name limit filled with La Habra and the like.
        if (nickTo != null)
            census("UPPER(BASENAME) = '" + nickTo[0].replace("'", "''") + "' AND STATE='" + fipsOf(nickTo[1]) + "'",
                    5, null, named, out);
        int census = census(likeAny("UPPER(BASENAME)", sql, false) + inState, 25, null, named, out);
        int mark = out.size();
        // A typo, or a letter with an accent ("Espanola" for Española), finds nothing by
        // exact start: names starting with the same three letters, kept when they are a
        // few letters from what was typed (2026-11-14, "Seatle").
        final String bareQ = squash(kindless != null ? kindless : q);
        final boolean fuzzy = bareQ.length() >= 4 && bareQ.substring(0, 3).matches("[A-Z0-9]{3}");
        if (census == 0 && fuzzy) {
            // The map's state first: the country-wide list stops at 400 names.
            final String home = fips != null ? null : fipsOf(preferState);
            if (home != null)
                census += census("UPPER(BASENAME) LIKE '" + bareQ.substring(0, 3) + "%' AND STATE='" + home + "'",
                        400, bareQ, named, out);
            census += census("UPPER(BASENAME) LIKE '" + bareQ.substring(0, 3) + "%'" + inState, 400, bareQ, named,
                    out);
            mark = out.size();
        }
        // Towns with no Census boundary (Bayview, Idaho): USGS GNIS populated places,
        // a point each; the report's area is a circle around it. GET: the USGS
        // firewall refuses a POST whose LIKE has "+" for spaces (403, 2026-11-13).
        final String stp = fips == null ? null : POSTAL.get(fips);
        try {
            int found = gnis(likeAny("UPPER(gaz_name)", sql, false), stp, null, named, out);
            mark = out.size();
            // Fuzzy: few answers, so every town starting with the same three letters,
            // kept when it is close to what was typed with spaces ignored ("bay view",
            // "bayveiw" -> Bayview).
            if (found < 3 && fuzzy)
                gnis("UPPER(gaz_name) LIKE '" + bareQ.substring(0, 3) + "%'", stp, bareQ, named, out);
        } catch (Exception e) {
            // Census answers alone are still a result.
        }
        mark = out.size();
        // A word from inside the name: "Lauderdale" for Fort Lauderdale, "Vegas" for
        // Las Vegas. Only when the start found little, and only Census (GNIS is too
        // large to scan this way).
        if (out.size() < 5 && bareQ.length() >= 4) {
            final List<String> inner = new ArrayList<>();
            for (String x : sql)
                if (x.replace(" ", "").length() >= 4 && inner.size() < 4)
                    inner.add(x);
            try {
                if (!inner.isEmpty())
                    census(likeAny("UPPER(BASENAME)", inner, true) + inState, 25, null, named, out);
            } catch (Exception e) {
                // what the start found is still a result
            }
            tier(out, mark, 1);
        }
        // Nearest to the map first (2026-11-13: "bayview" listed 40 Bayviews and cut
        // the one in Idaho the operator was looking at); without a center, the map's
        // state first, then the largest. "Orange County" puts counties first.
        // Before distance: the city a nickname means ("NYC": New York, NY, not New York,
        // NM), then the kind typed ("Jefferson Parish": the parish), then how the name
        // matched (start, inside, near spelling).
        final boolean counties = wantCounty;
        // "Ft Myers": Fort Myers before Fort Myers Beach.
        final java.util.Set<String> exact = new java.util.HashSet<>();
        for (String x : spellings)
            exact.add(canon(x));
        final String kindWord = !wantCounty ? null
                : (tail.matches() ? tail.group(2) : "COUNTY").replace("CNTY", "COUNTY");
        Collections.sort(out, new Comparator<Place>() {
            @Override
            public int compare(Place a, Place b) {
                if (nickTo != null) {
                    final boolean an = isNick(a, nickTo), bn = isNick(b, nickTo);
                    if (an != bn)
                        return an ? -1 : 1;
                }
                if (counties) {
                    final int ac = kindRank(a, kindWord), bc = kindRank(b, kindWord);
                    if (ac != bc)
                        return ac - bc;
                }
                if (a.tier != b.tier)
                    return a.tier - b.tier;
                final boolean ae = exact.contains(a.base), be = exact.contains(b.base);
                if (ae != be)
                    return ae ? -1 : 1;
                if (center != null) {
                    final double da = away(a, center), db = away(b, center);
                    if (da != db)
                        return Double.compare(da, db);
                }
                final boolean ah = a.state.equals(preferState), bh = b.state.equals(preferState);
                if (ah != bh)
                    return ah ? -1 : 1;
                return Double.compare(b.areaLand, a.areaLand);
            }
        });
        return out.size() > 40 ? new ArrayList<>(out.subList(0, 40)) : out;
    }

    static final String COUNTY_LAYER = "State_County/MapServer/1";

    /** Sets the tier of every place added since {@code from}; returns the new end. */
    private static int tier(List<Place> out, int from, int tier) {
        for (int i = from; i < out.size(); i++)
            out.get(i).tier = tier;
        return out.size();
    }

    /** The Census place (not a town point) a nickname means, in its state. */
    private static boolean isNick(Place p, String[] to) {
        return !"gnis".equals(p.layer) && !COUNTY_LAYER.equals(p.layer) && to[1].equals(p.state)
                && canon(to[0]).equals(p.base);
    }

    /** 0: a county named with the word typed (Parish); 1: another county; 2: anything else. */
    private static int kindRank(Place p, String word) {
        if (!COUNTY_LAYER.equals(p.layer))
            return 2;
        return p.label.toUpperCase(Locale.US).contains(" " + word + ",") ? 0 : 1;
    }

    private static void add(List<String> list, String x) {
        if (x != null && !x.isEmpty() && !list.contains(x))
            list.add(x);
    }

    /** Without accents: Española -> Espanola. */
    static String plain(String s) {
        return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
    }

    /**
     * A name as one key whatever its spelling: GNIS writes "Saint Petersburg" and
     * Census "St. Petersburg", and one city was listed twice.
     */
    static String canon(String s) {
        final String u = " " + plain(s).toUpperCase(Locale.US).replaceAll("[^A-Z0-9 ]", " ") + " ";
        return squash(u.replace(" SAINTE ", " STE ").replace(" SAINT ", " ST ").replace(" FORT ", " FT ")
                .replace(" MOUNT ", " MT "));
    }

    /** Letters and digits only, no accents, upper case: for comparing names. */
    static String squash(String s) {
        return plain(s).toUpperCase(Locale.US).replaceAll("[^A-Z0-9]", "");
    }

    private static String fipsOf(String postal) {
        if (postal == null || postal.isEmpty())
            return null;
        for (Map.Entry<String, String> e : POSTAL.entrySet())
            if (e.getValue().equals(postal))
                return e.getKey();
        return null;
    }

    /** Names crews use for a place, to the name Census gives it. */
    private static final Map<String, String[]> NICK = new HashMap<>();
    static {
        final String[] n = { "NYC=NEW YORK|NY", "NEW YORK CITY=NEW YORK|NY", "NY CITY=NEW YORK|NY",
                "LA=LOS ANGELES|CA", "L.A.=LOS ANGELES|CA", "SF=SAN FRANCISCO|CA", "SAN FRAN=SAN FRANCISCO|CA",
                "FRISCO=SAN FRANCISCO|CA", "PHILLY=PHILADELPHIA|PA", "NOLA=NEW ORLEANS|LA",
                "NAWLINS=NEW ORLEANS|LA", "KC=KANSAS CITY|MO", "SLC=SALT LAKE CITY|UT", "OKC=OKLAHOMA CITY|OK",
                "ABQ=ALBUQUERQUE|NM", "VEGAS=LAS VEGAS|NV", "DC=WASHINGTON|DC", "D.C.=WASHINGTON|DC",
                "INDY=INDIANAPOLIS|IN", "SACTO=SACRAMENTO|CA", "SD=SAN DIEGO|CA", "JAX=JACKSONVILLE|FL",
                "CHITOWN=CHICAGO|IL", "CHI-TOWN=CHICAGO|IL", "PDX=PORTLAND|OR", "ATL=ATLANTA|GA",
                "BMORE=BALTIMORE|MD", "CINCY=CINCINNATI|OH", "STL=ST. LOUIS|MO", "SAINT LOUIS=ST. LOUIS|MO",
                "SAINT PAUL=ST. PAUL|MN", "SAINT PETE=ST. PETERSBURG|FL", "ST PETE=ST. PETERSBURG|FL",
                "ST. PETE=ST. PETERSBURG|FL" };
        for (String x : n) {
            final int eq = x.indexOf('='), bar = x.indexOf('|');
            NICK.put(x.substring(0, eq), new String[] { x.substring(eq + 1, bar), x.substring(bar + 1) });
        }
    }

    /** Other spellings of the short words in place names, by the word without its dot. */
    private static final Map<String, String[]> SHORT = new HashMap<>();
    static {
        final String[] fort = { "FORT", "FT." }, saint = { "ST.", "SAINT" }, sainte = { "STE.", "SAINTE" },
                mount = { "MOUNT", "MT." }, point = { "PORT", "POINT", "PT." };
        SHORT.put("FT", fort);
        SHORT.put("FORT", fort);
        SHORT.put("ST", saint);
        SHORT.put("SAINT", saint);
        SHORT.put("STE", sainte);
        SHORT.put("SAINTE", sainte);
        SHORT.put("MT", mount);
        SHORT.put("MOUNT", mount);
        SHORT.put("PT", point);
        SHORT.put("N", new String[] { "NORTH" });
        SHORT.put("S", new String[] { "SOUTH" });
        SHORT.put("E", new String[] { "EAST" });
        SHORT.put("W", new String[] { "WEST" });
        SHORT.put("NO", new String[] { "NORTH" });
        SHORT.put("SO", new String[] { "SOUTH" });
        SHORT.put("LK", new String[] { "LAKE" });
        SHORT.put("HTS", new String[] { "HEIGHTS" });
        SHORT.put("HGTS", new String[] { "HEIGHTS" });
        SHORT.put("SPGS", new String[] { "SPRINGS" });
        SHORT.put("SPRGS", new String[] { "SPRINGS" });
        SHORT.put("BCH", new String[] { "BEACH" });
        SHORT.put("VLG", new String[] { "VILLAGE" });
        SHORT.put("VLY", new String[] { "VALLEY" });
        SHORT.put("CTR", new String[] { "CENTER" });
        SHORT.put("JCT", new String[] { "JUNCTION" });
        SHORT.put("PK", new String[] { "PARK" });
    }

    /** Short words that are never the start of a longer one, so they change at the end too. */
    private static final java.util.Set<String> WHOLE = new java.util.HashSet<>(java.util.Arrays.asList(
            "LK", "HTS", "HGTS", "SPGS", "SPRGS", "BCH", "VLG", "VLY", "CTR", "JCT"));

    /**
     * What was typed, then the same with Ft/St/Mt/Pt and Fort/Saint/Mount spelled each
     * other way. At most 8. The last word is left alone unless it is a whole word
     * already (a dot after it, or more words follow), so "St" still finds Stockton.
     */
    static List<String> spellings(String q) {
        final String[] words = q.split(" ");
        List<String> out = new ArrayList<>();
        out.add("");
        for (int i = 0; i < words.length; i++) {
            final String w = words[i];
            final String bare = w.endsWith(".") ? w.substring(0, w.length() - 1) : w;
            final String[] alt = SHORT.get(bare);
            final List<String> forms = new ArrayList<>();
            forms.add(w);
            if (alt != null && (i < words.length - 1 || w.endsWith(".") || WHOLE.contains(bare)))
                for (String a : alt)
                    if (!forms.contains(a))
                        forms.add(a);
            final List<String> next = new ArrayList<>();
            for (String head : out)
                for (String f : forms)
                    if (next.size() < 8)
                        next.add(head.isEmpty() ? f : head + " " + f);
            out = next;
        }
        return out;
    }

    /** {@code field LIKE 'a%' OR field LIKE 'b%'} (or '%a%' when inside), in brackets. */
    static String likeAny(String field, List<String> starts, boolean inside) {
        final StringBuilder b = new StringBuilder("(");
        for (int i = 0; i < starts.size(); i++)
            b.append(i == 0 ? "" : " OR ").append(field).append(" LIKE '").append(inside ? "%" : "")
                    .append(starts.get(i)).append("%'");
        return b.append(')').toString();
    }

    /** Rough meters from the center to a place's point; places with none go last. */
    private static double away(Place p, GeoPoint c) {
        if (!(Math.abs(p.lat) <= 90) || !(Math.abs(p.lon) <= 180))
            return Double.MAX_VALUE;
        final double dy = (p.lat - c.getLatitude()) * 111_320d;
        final double dx = (p.lon - c.getLongitude()) * 111_320d * Math.cos(Math.toRadians(c.getLatitude()));
        return Math.sqrt(dx * dx + dy * dy);
    }

    private static double num(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    /** The city, community and county at a point, smallest first. */
    public static List<Place> at(GeoPoint p) throws Exception {
        final List<Place> out = new ArrayList<>();
        final String geom = Esri.enc(String.format(Locale.US, "%.6f,%.6f", p.getLongitude(), p.getLatitude()));
        for (String layer : LAYERS) {
            final String form = "f=json&returnGeometry=false&where=1%3D1"
                    + "&outFields=" + Esri.enc("NAME,STATE,GEOID,AREALAND")
                    + "&geometry=" + geom + "&geometryType=esriGeometryPoint&inSR=4326"
                    + "&spatialRel=esriSpatialRelIntersects";
            final JSONArray f = Esri.parse(Http.post(BASE + layer + "/query", form)).optJSONArray("features");
            for (int i = 0; f != null && i < f.length(); i++) {
                final JSONObject a = f.optJSONObject(i) == null ? null : f.optJSONObject(i).optJSONObject("attributes");
                if (a == null)
                    continue;
                final String geoid = a.optString("GEOID", "");
                final String st = POSTAL.get(a.optString("STATE", ""));
                if (!geoid.matches("\\d{5,7}") || st == null)
                    continue;
                out.add(new Place(a.optString("NAME", "").replace(" CDP", " (community)") + ", " + st,
                        layer, geoid, st, a.optDouble("AREALAND", 0)));
            }
        }
        Collections.sort(out, new Comparator<Place>() {
            @Override
            public int compare(Place a, Place b) {
                return Double.compare(a.areaLand, b.areaLand);
            }
        });
        return out;
    }

    /** Postal code of the state a point is in (NWS county layer); empty when not known. */
    public static String stateAt(GeoPoint p) {
        if (p == null || !(Math.abs(p.getLatitude()) <= 90) || !(Math.abs(p.getLongitude()) <= 180))
            return "";
        try {
            final String form = "f=json&returnGeometry=false&outFields=state&where=1%3D1"
                    + "&geometry=" + Esri.enc(String.format(Locale.US, "%.6f,%.6f", p.getLongitude(), p.getLatitude()))
                    + "&geometryType=esriGeometryPoint&inSR=4326&spatialRel=esriSpatialRelIntersects";
            final JSONArray f = Esri.parse(Http.post(NWS_COUNTIES + "/query", form)).optJSONArray("features");
            final JSONObject a = f == null || f.length() == 0 || f.optJSONObject(0) == null ? null
                    : f.optJSONObject(0).optJSONObject("attributes");
            final String st = a == null ? "" : a.optString("state", "");
            return st.matches("[A-Z]{2}") ? st : "";
        } catch (Exception e) {
            return "";   // the order is a convenience; the search still answers
        }
    }

    static final String NWS_COUNTIES = "https://mapservices.weather.noaa.gov/static/rest/services/"
            + "nws_reference_maps/nws_reference_map/MapServer/2";

    /** The place's outline: its largest ring, thinned by the server. */
    /**
     * GNIS populated places for a where clause, added to out unless Census already
     * named them; with near, only names within a few letters of it. Returns how many.
     */
    private static int gnis(String like, String postal, String near, java.util.Set<String> named,
            List<Place> out) throws Exception {
        final String where = like + (postal == null ? "" : " AND state_alpha='" + postal + "'");
        final String url = GNIS + "/query?f=json&returnGeometry=true&outSR=4326&resultRecordCount="
                + (near != null ? 400 : postal == null ? 200 : 25) + "&where=" + Esri.enc(where)
                + "&outFields=" + Esri.enc("gaz_id,gaz_name,state_alpha,county_name");
        final JSONArray f = Esri.parse(Http.get(url)).optJSONArray("features");
        final List<Object[]> picked = new ArrayList<>();
        for (int i = 0; f != null && i < f.length(); i++) {
            final JSONObject ft = f.optJSONObject(i);
            final JSONObject a = ft == null ? null : ft.optJSONObject("attributes");
            final JSONObject g = ft == null ? null : ft.optJSONObject("geometry");
            final JSONArray pts = g == null ? null : g.optJSONArray("points");
            final JSONArray xy = pts != null && pts.length() > 0 ? pts.optJSONArray(0) : null;
            final double lon = xy != null ? xy.optDouble(0) : g == null ? Double.NaN : g.optDouble("x"),
                    lat = xy != null ? xy.optDouble(1) : g == null ? Double.NaN : g.optDouble("y");
            if (a == null || !(Math.abs(lat) <= 90) || !(Math.abs(lon) <= 180))
                continue;
            final String st = a.optString("state_alpha", "");
            final String name = a.optString("gaz_name", "").trim();
            final String key = canon(name) + "|" + st;
            if (name.isEmpty() || !POSTAL.containsValue(st) || !named.add(key))
                continue;
            int d = 0;
            if (near != null) {
                final String n = squash(name);
                d = edits(near, n.length() > near.length() + 3 ? n.substring(0, near.length() + 3) : n);
                if (d > Math.max(1, near.length() / 4)) {
                    named.remove(key);
                    continue;
                }
            }
            final String county = a.optString("county_name", "").trim();
            final Place p = new Place(name + ", " + st + " (town, no boundary"
                    + (county.isEmpty() ? "" : ", " + county + " County") + ")",
                    "gnis", String.valueOf(a.optLong("gaz_id")), st, 0, lat, lon);
            p.base = canon(name);
            p.tier = d == 0 ? 0 : 2;
            picked.add(new Object[] { d, p });
        }
        Collections.sort(picked, new Comparator<Object[]>() {
            @Override
            public int compare(Object[] x, Object[] y) {
                return (Integer) x[0] - (Integer) y[0];
            }
        });
        for (int i = 0; i < picked.size(); i++)
            out.add((Place) picked.get(i)[1]);
        return picked.size();
    }

    /**
     * Census incorporated places and communities matching {@code where}; with
     * {@code near}, only names within a few edits of it (spaces ignored), closest first.
     */
    private static int census(String where, int max, String near, java.util.Set<String> named, List<Place> out)
            throws Exception {
        final List<Object[]> picked = new ArrayList<>();
        // The three layers at once: one after the other made a search take 5 to 7 s.
        final String form = "f=json&returnGeometry=false&resultRecordCount=" + max
                + "&where=" + Esri.enc(where)
                + "&outFields=" + Esri.enc("NAME,BASENAME,STATE,GEOID,AREALAND,INTPTLAT,INTPTLON");
        final String[] body = new String[LAYERS.length];
        final Exception[] fail = new Exception[1];
        final Thread[] th = new Thread[LAYERS.length];
        for (int k = 0; k < LAYERS.length; k++) {
            final int n = k;
            th[k] = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        body[n] = Http.post(BASE + LAYERS[n] + "/query", form);
                    } catch (Exception e) {
                        synchronized (fail) {
                            fail[0] = e;
                        }
                    }
                }
            }, "sitrep-places");
            th[k].start();
        }
        for (Thread x : th)
            x.join();
        if (fail[0] != null)
            throw fail[0];
        for (int k = 0; k < LAYERS.length; k++) {
            final String layer = LAYERS[k];
            final JSONObject o = Esri.parse(body[k]);
            final JSONArray f = o.optJSONArray("features");
            if (f == null)
                continue;
            for (int i = 0; i < f.length(); i++) {
                final JSONObject a = f.optJSONObject(i) == null ? null : f.optJSONObject(i).optJSONObject("attributes");
                if (a == null)
                    continue;
                final String geoid = a.optString("GEOID", "");
                final String st = POSTAL.get(a.optString("STATE", ""));
                if (!geoid.matches("\\d{5,7}") || st == null)
                    continue;
                final String base = a.optString("BASENAME", "").toUpperCase(Locale.US);
                // The same place from an earlier query (start, then fuzzy or inside).
                if (named.contains(layer + "#" + geoid))
                    continue;
                int d = 0;
                if (near != null) {
                    final String n = squash(base);
                    d = edits(near, n.length() > near.length() + 3 ? n.substring(0, near.length() + 3) : n);
                    if (d > Math.max(1, near.length() / 4))
                        continue;
                }
                final String name = a.optString("NAME", "").replace(" CDP", " (community)");
                final Place p = new Place(name + ", " + st, layer, geoid, st, a.optDouble("AREALAND", 0),
                        num(a.optString("INTPTLAT", "")), num(a.optString("INTPTLON", "")));
                p.base = canon(base);
                p.tier = d == 0 ? 0 : 2;
                picked.add(new Object[] { d, p });
                named.add(p.base + "|" + st);
                named.add(layer + "#" + geoid);
            }
        }
        Collections.sort(picked, new Comparator<Object[]>() {
            @Override
            public int compare(Object[] x, Object[] y) {
                return (Integer) x[0] - (Integer) y[0];
            }
        });
        for (Object[] x : picked)
            out.add((Place) x[1]);
        return picked.size();
    }

    /** Levenshtein distance, for the fuzzy town match. */
    static int edits(String a, String b) {
        final int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++)
            prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++)
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1),
                        prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            System.arraycopy(cur, 0, prev, 0, cur.length);
        }
        return prev[b.length()];
    }

    /** A town with no boundary is a circle this far around its point (2 mi). */
    static final double TOWN_RADIUS_M = 3_218.69;

    public static GeoPoint[] outline(Place p) throws Exception {
        if ("gnis".equals(p.layer)) {
            if (!(Math.abs(p.lat) <= 90) || !(Math.abs(p.lon) <= 180))
                throw new java.io.IOException("no point for that town");
            final GeoPoint[] ring = new GeoPoint[48];
            final double mLat = 111_320d, mLon = 111_320d * Math.cos(Math.toRadians(p.lat));
            for (int i = 0; i < ring.length; i++) {
                final double a = 2 * Math.PI * i / ring.length;
                ring[i] = new GeoPoint(p.lat + TOWN_RADIUS_M * Math.cos(a) / mLat,
                        p.lon + TOWN_RADIUS_M * Math.sin(a) / mLon);
            }
            return ring;
        }
        final String form = "f=json&returnGeometry=true&outSR=4326&maxAllowableOffset=0.0005"
                + "&where=" + Esri.enc("GEOID='" + p.geoid + "'") + "&outFields=GEOID";
        final JSONObject o = Esri.parse(Http.post(BASE + p.layer + "/query", form));
        final JSONArray f = o.optJSONArray("features");
        final JSONObject g = f == null || f.length() == 0 || f.optJSONObject(0) == null ? null
                : f.optJSONObject(0).optJSONObject("geometry");
        final JSONArray rings = g == null ? null : g.optJSONArray("rings");
        if (rings == null || rings.length() == 0)
            throw new java.io.IOException("no outline for that place");
        JSONArray best = null;
        double bestArea = -1;
        for (int i = 0; i < rings.length(); i++) {
            final JSONArray r = rings.optJSONArray(i);
            final double a = r == null ? 0 : Math.abs(shoelace(r));
            if (a > bestArea) {
                bestArea = a;
                best = r;
            }
        }
        final List<GeoPoint> pts = new ArrayList<>();
        for (int i = 0; best != null && i < best.length(); i++) {
            final JSONArray p2 = best.optJSONArray(i);
            if (p2 == null)
                continue;
            final double lon = p2.optDouble(0), lat = p2.optDouble(1);
            if (Math.abs(lat) <= 90 && Math.abs(lon) <= 180)
                pts.add(new GeoPoint(lat, lon));
        }
        if (pts.size() < 3)
            throw new java.io.IOException("the outline has fewer than three points");
        return pts.toArray(new GeoPoint[0]);
    }

    private static double shoelace(JSONArray r) {
        double s = 0;
        for (int i = 0; i < r.length(); i++) {
            final JSONArray a = r.optJSONArray(i), b = r.optJSONArray((i + 1) % r.length());
            if (a != null && b != null)
                s += a.optDouble(0) * b.optDouble(1) - b.optDouble(0) * a.optDouble(1);
        }
        return s / 2;
    }

    static final String GNIS = "https://carto.nationalmap.gov/arcgis/rest/services/geonames/MapServer/3";

    /**
     * A state name or postal code at the end of what was typed: {fips, the rest}, or
     * null. "BAYVIEW IDAHO", "BAYVIEW, ID", "SALEM NEW YORK".
     */
    static String[] stateAtEnd(String t) {
        final String s = t.trim();
        for (Map.Entry<String, String> e : NAMES.entrySet()) {
            final String name = e.getKey();
            if (s.length() > name.length() + 1 && s.endsWith(name)) {
                final char before = s.charAt(s.length() - name.length() - 1);
                if (before == ' ' || before == ',')
                    return new String[] { e.getValue(), s.substring(0, s.length() - name.length()).trim() };
            }
        }
        final java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(.+?)[ ,]+([A-Z]{2})$").matcher(s);
        if (m.matches())
            for (Map.Entry<String, String> e : POSTAL.entrySet())
                if (e.getValue().equals(m.group(2)))
                    return new String[] { e.getKey(), m.group(1).trim() };
        return null;
    }

    /** State names to FIPS, longest first so "WEST VIRGINIA" wins over "VIRGINIA". */
    private static final Map<String, String> NAMES = new java.util.TreeMap<>(new Comparator<String>() {
        @Override
        public int compare(String a, String b) {
            return a.length() != b.length() ? b.length() - a.length() : a.compareTo(b);
        }
    });

    static {
        final String[] n = { "01ALABAMA", "02ALASKA", "04ARIZONA", "05ARKANSAS", "06CALIFORNIA", "08COLORADO",
                "09CONNECTICUT", "10DELAWARE", "11DISTRICT OF COLUMBIA", "12FLORIDA", "13GEORGIA", "15HAWAII",
                "16IDAHO", "17ILLINOIS", "18INDIANA", "19IOWA", "20KANSAS", "21KENTUCKY", "22LOUISIANA", "23MAINE",
                "24MARYLAND", "25MASSACHUSETTS", "26MICHIGAN", "27MINNESOTA", "28MISSISSIPPI", "29MISSOURI",
                "30MONTANA", "31NEBRASKA", "32NEVADA", "33NEW HAMPSHIRE", "34NEW JERSEY", "35NEW MEXICO",
                "36NEW YORK", "37NORTH CAROLINA", "38NORTH DAKOTA", "39OHIO", "40OKLAHOMA", "41OREGON",
                "42PENNSYLVANIA", "44RHODE ISLAND", "45SOUTH CAROLINA", "46SOUTH DAKOTA", "47TENNESSEE", "48TEXAS",
                "49UTAH", "50VERMONT", "51VIRGINIA", "53WASHINGTON", "54WEST VIRGINIA", "55WISCONSIN", "56WYOMING",
                "72PUERTO RICO" };
        for (String x : n)
            NAMES.put(x.substring(2), x.substring(0, 2));
    }

    /** State FIPS to postal code. */
    private static final Map<String, String> POSTAL = new HashMap<>();

    static {
        final String[] t = { "01AL", "02AK", "04AZ", "05AR", "06CA", "08CO", "09CT", "10DE", "11DC",
                "12FL", "13GA", "15HI", "16ID", "17IL", "18IN", "19IA", "20KS", "21KY", "22LA", "23ME",
                "24MD", "25MA", "26MI", "27MN", "28MS", "29MO", "30MT", "31NE", "32NV", "33NH", "34NJ",
                "35NM", "36NY", "37NC", "38ND", "39OH", "40OK", "41OR", "42PA", "44RI", "45SC", "46SD",
                "47TN", "48TX", "49UT", "50VT", "51VA", "53WA", "54WV", "55WI", "56WY", "60AS", "66GU",
                "69MP", "72PR", "78VI" };
        for (String x : t)
            POSTAL.put(x.substring(0, 2), x.substring(2));
    }
}

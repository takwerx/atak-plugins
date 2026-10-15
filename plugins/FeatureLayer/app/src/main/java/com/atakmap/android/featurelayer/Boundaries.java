package com.atakmap.android.featurelayer;

import com.atakmap.map.layer.feature.AttributeSet;
import com.atakmap.map.layer.feature.style.Style;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The built-in Boundaries source: county, city and tribal land boundaries from the
 * Census Bureau's TIGERweb services, public, no sign-in, one or more states at a time
 * (operator, 2026-10-12: "give me these counties or cities or all county or cities for
 * a state or states as a feature layer"). Reference data: fetched once, kept on the
 * phone, back after a restart with no network, refetched only by a Refresh tap.
 *
 * <p>Sources found with the help of Joseph Elfelt, mappingsupport.com. The endpoints
 * are fixed strings here, like every source in {@link Sources}; nothing a server
 * answers ever becomes an address.
 *
 * <p>Field names are TIGERweb's exactly: the legal-area code is {@code LSADC}, not
 * {@code LSAD}, and the service answers an unknown field with a bare 400 and nothing
 * else (two queries lost to it on 2026-10-12).
 */
public final class Boundaries {
    private Boundaries() {
    }

    static final String TIGER = "https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb";
    /** States (layer 0) and Counties (layer 1), current vintage; 19, 37 and 55 are older vintages of the same. */
    static final String STATE_COUNTY = TIGER + "/State_County/MapServer";
    /** Incorporated Places (layer 4) and Census Designated Places (layer 5). */
    static final String PLACES = TIGER + "/Places_CouSub_ConCity_SubMCD/MapServer";
    /** Federal reservations (2), off-reservation trust lands (3), state reservations (4), Hawaiian home lands (5). */
    static final String AIANNHA = TIGER + "/AIANNHA/MapServer";

    public static final String ID_PREFIX = "boundaries:";
    public static final String KIND_COUNTIES = "counties", KIND_CITIES = "cities", KIND_COMMUNITIES = "communities",
            KIND_TRIBAL = "tribal";
    /**
     * Cities are the incorporated places only; the unincorporated communities the Census
     * draws are a kind of their own, in their own color (operator, 2026-10-13: "i want to
     * just have incorporated cities, and then ... communities as a different layer ...
     * all different colors").
     */
    /**
     * The land layers (operator, 2026-10-14: "blm, usfs, then for like california SRA vs
     * FRA ... DPA direct protection area ... how ONX hunt has a lot of data on like
     * private vs public lands"): who answers the fire, who owns the ground, whether the
     * public may enter. Mosaics of a whole state, so they are fetched for what is in
     * view and drawn in the publisher's own colors with the fill on.
     */
    public static final String KIND_RESPONSIBILITY = "responsibility", KIND_DPA = "dpa", KIND_OWNERSHIP = "ownership",
            KIND_PUBLIC_LAND = "publicland", KIND_ACCESS = "access";
    public static final String[] KINDS = { KIND_COUNTIES, KIND_CITIES, KIND_COMMUNITIES, KIND_TRIBAL,
            KIND_RESPONSIBILITY, KIND_DPA, KIND_OWNERSHIP, KIND_PUBLIC_LAND, KIND_ACCESS };

    /** CAL FIRE FRAP's Responsibility Areas: SRA, LRA, FRA, the whole state. */
    static final String CA_RESPONSIBILITY = "https://services1.arcgis.com/jUJYIo9tSA7EHvfZ/arcgis/rest/services/State_Responsibility_Area/FeatureServer";
    /** California Wildland Fire Direct Protection Areas, NIFC's authoritative copy. */
    static final String CA_DPA = "https://services3.arcgis.com/T4QMspbfLg3qTGWY/arcgis/rest/services/CA_WF_Direct_Protection_Areas_Public/FeatureServer";
    /** CAL FIRE FRAP's Public Land Ownership, the public view on the CAL FIRE org. */
    static final String CA_OWNERSHIP = "https://jUJYIo9tSA7EHvfZ.svcs1.arcgis.com/jUJYIo9tSA7EHvfZ/arcgis/rest/services/Public_Land_Ownership_view/FeatureServer";
    /** USGS PAD-US federal fee managers, nationwide, by managing agency. */
    static final String PADUS_MANAGERS = "https://services.arcgis.com/v01gqwM5QqNysAAi/arcgis/rest/services/Federal_Fee_Managers_Authoritative_PADUS/FeatureServer";
    /** USGS PAD-US public access: open, restricted, closed. */
    static final String PADUS_ACCESS = "https://services.arcgis.com/v01gqwM5QqNysAAi/ArcGIS/rest/services/PADUS_Public_Access/FeatureServer";

    /** Whether a kind is a whole-state mosaic fetched for what is in view, not named shapes. */
    public static boolean mosaic(String kind) {
        return KIND_RESPONSIBILITY.equals(kind) || KIND_DPA.equals(kind) || KIND_OWNERSHIP.equals(kind)
                || KIND_PUBLIC_LAND.equals(kind) || KIND_ACCESS.equals(kind);
    }

    /** Whether a kind's features carry a name pill on the map (counties, cities, communities, tribal lands). */
    public static boolean labelled(String kind) {
        return !mosaic(kind);
    }

    /** Whether a kind exists only for California (CAL FIRE's layers). */
    static boolean californiaOnly(String kind) {
        return KIND_RESPONSIBILITY.equals(kind) || KIND_DPA.equals(kind) || KIND_OWNERSHIP.equals(kind);
    }

    /** The kinds the picked states can have: CAL FIRE's three only when California is among them. */
    public static String[] kindsFor(String[] states) {
        final List<String> out = new ArrayList<>();
        for (String k : KINDS)
            if (!californiaOnly(k) || has(states, "06"))
                out.add(k);
        return out.toArray(new String[0]);
    }

    /** The field a mosaic's features are typed and colored by, the publisher's own renderer field. */
    static String setField(String kind) {
        switch (kind == null ? "" : kind) {
            case KIND_RESPONSIBILITY:
                return "SRA";
            case KIND_DPA:
                return "DPA_AGENCY";
            case KIND_OWNERSHIP:
                return "Own_Group";
            case KIND_PUBLIC_LAND:
                return "WebServiceMangName";
            case KIND_ACCESS:
                return "Pub_Access";
            default:
                return null;
        }
    }
    /** The most states one layer holds: a nationwide county layer is 3,143 polygons and a store rewrite the phone does not need. */
    public static final int MAX_STATES = 5;
    /** About 50 m: the detail the lines are fetched at. Texas counties are 25 MB at full detail and 950 KB at this. */
    static final double OFFSET_DEG = 0.0005;
    /** The credit the page and the guide carry. */
    public static final String CREDIT = "Sources found with the help of Joseph Elfelt, mappingsupport.com";

    /** One outline color per kind, so two boundary layers on one ground can be told apart. */
    public static final int COUNTY_RGB = 0xB040FF, CITY_RGB = 0x2962FF, COMMUNITY_RGB = 0xFFD600, TRIBAL_RGB = 0xFF4FA3;

    static final double MILE = 1609.344;
    /** Counties and tribal lands label from the 20-mile scale, cities and communities from the 5-mile scale. */
    static final double LABEL_GSD_20MI = 20 * MILE / ScaleBar.FALLBACK_BAR_PIXELS;
    /**
     * Cities, communities and tribal lands draw from the 20-mile scale in; counties at
     * every zoom. Tribal lands drew at every zoom until 2026-10-13 (operator: "tribes are
     * staying on expanding out ... should be same zoom gate to start as cities").
     */
    static final double GATE_GSD_20MI = LABEL_GSD_20MI;
    /** The style version that brought the tribal gate; a layer saved before it takes the gate once. */
    static final int TRIBAL_GATE_SINCE = 62;

    /** A state or territory: FIPS code, postal abbreviation, name, and the Census center for sorting by distance. */
    public static final class State {
        public final String fips, abbr, name;
        public final double lat, lon;

        State(String fips, String abbr, String name, double lat, double lon) {
            this.fips = fips;
            this.abbr = abbr;
            this.name = name;
            this.lat = lat;
            this.lon = lon;
        }
    }

    /** The 50 states, DC and the territories, from the States layer on 2026-10-12. */
    public static final State[] STATES = {
            new State("01", "AL", "Alabama", 32.744, -86.847),
            new State("02", "AK", "Alaska", 63.414, -152.879),
            new State("60", "AS", "American Samoa", -14.223, -170.335),
            new State("04", "AZ", "Arizona", 34.225, -111.635),
            new State("05", "AR", "Arkansas", 34.886, -92.465),
            new State("06", "CA", "California", 37.200, -119.528),
            new State("08", "CO", "Colorado", 38.994, -105.537),
            new State("69", "MP", "Commonwealth of the Northern Mariana Islands", 15.077, 145.608),
            new State("09", "CT", "Connecticut", 41.580, -72.733),
            new State("10", "DE", "Delaware", 39.004, -75.512),
            new State("11", "DC", "District of Columbia", 38.911, -77.014),
            new State("12", "FL", "Florida", 28.456, -82.457),
            new State("13", "GA", "Georgia", 32.663, -83.432),
            new State("66", "GU", "Guam", 13.439, 144.779),
            new State("15", "HI", "Hawaii", 20.254, -156.359),
            new State("16", "ID", "Idaho", 44.350, -114.661),
            new State("17", "IL", "Illinois", 40.073, -89.158),
            new State("18", "IN", "Indiana", 39.904, -86.279),
            new State("19", "IA", "Iowa", 42.081, -93.489),
            new State("20", "KS", "Kansas", 38.496, -98.390),
            new State("21", "KY", "Kentucky", 37.527, -85.293),
            new State("22", "LA", "Louisiana", 30.928, -91.421),
            new State("23", "ME", "Maine", 45.335, -69.246),
            new State("24", "MD", "Maryland", 38.979, -76.700),
            new State("25", "MA", "Massachusetts", 42.242, -71.591),
            new State("26", "MI", "Michigan", 44.330, -85.418),
            new State("27", "MN", "Minnesota", 46.329, -94.300),
            new State("28", "MS", "Mississippi", 32.699, -89.664),
            new State("29", "MO", "Missouri", 38.351, -92.449),
            new State("30", "MT", "Montana", 47.043, -109.642),
            new State("31", "NE", "Nebraska", 41.542, -99.820),
            new State("32", "NV", "Nevada", 39.359, -116.633),
            new State("33", "NH", "New Hampshire", 43.676, -71.579),
            new State("34", "NJ", "New Jersey", 40.192, -74.670),
            new State("35", "NM", "New Mexico", 34.420, -106.108),
            new State("36", "NY", "New York", 42.924, -75.580),
            new State("37", "NC", "North Carolina", 35.542, -79.137),
            new State("38", "ND", "North Dakota", 47.439, -100.465),
            new State("39", "OH", "Ohio", 40.419, -82.711),
            new State("40", "OK", "Oklahoma", 35.577, -97.498),
            new State("41", "OR", "Oregon", 43.969, -120.549),
            new State("42", "PA", "Pennsylvania", 40.874, -77.790),
            new State("72", "PR", "Puerto Rico", 18.220, -66.407),
            new State("44", "RI", "Rhode Island", 41.587, -71.526),
            new State("45", "SC", "South Carolina", 33.898, -80.863),
            new State("46", "SD", "South Dakota", 44.436, -100.230),
            new State("47", "TN", "Tennessee", 35.858, -86.342),
            new State("48", "TX", "Texas", 31.447, -99.277),
            new State("78", "VI", "United States Virgin Islands", 18.308, -64.883),
            new State("49", "UT", "Utah", 39.360, -111.670),
            new State("50", "VT", "Vermont", 44.062, -72.669),
            new State("51", "VA", "Virginia", 37.515, -78.804),
            new State("53", "WA", "Washington", 47.405, -120.526),
            new State("54", "WV", "West Virginia", 38.642, -80.615),
            new State("55", "WI", "Wisconsin", 44.620, -89.996),
            new State("56", "WY", "Wyoming", 42.996, -107.552),
    };

    private static final Map<String, State> BY_FIPS = new HashMap<>();
    static {
        for (State s : STATES)
            BY_FIPS.put(s.fips, s);
    }

    public static State state(String fips) {
        return fips == null ? null : BY_FIPS.get(fips);
    }

    /** The states nearest a point first, by Census center: the one under the map center is usually first. */
    public static List<State> statesNear(final double lat, final double lon) {
        final List<State> out = new ArrayList<>(Arrays.asList(STATES));
        if (Double.isNaN(lat) || Double.isNaN(lon))
            return out;
        Collections.sort(out, new Comparator<State>() {
            @Override
            public int compare(State a, State b) {
                return Double.compare(distanceM(lat, lon, a.lat, a.lon), distanceM(lat, lon, b.lat, b.lon));
            }
        });
        return out;
    }

    /** Which state a point is in, asked of the States layer; null offline or at sea. Worker thread. */
    public static State stateAt(double lat, double lon) {
        try {
            final String url = STATE_COUNTY + "/0/query?geometry=" + Esri.enc(lon + "," + lat)
                    + "&geometryType=esriGeometryPoint&inSR=4326&spatialRel=esriSpatialRelIntersects"
                    + "&outFields=STATE&returnGeometry=false&f=json";
            final JSONObject d = new JSONObject(Esri.get(url));
            final JSONArray f = d.optJSONArray("features");
            if (f == null || f.length() == 0)
                return null;
            return state(f.getJSONObject(0).getJSONObject("attributes").optString("STATE", null));
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean handles(LayerSpec spec) {
        return spec != null && "boundaries".equals(spec.iconSet) && spec.kind != null;
    }

    /** "Counties", "Cities", "Communities", "Tribal Lands". */
    public static String kindTitle(String kind) {
        switch (kind == null ? "" : kind) {
            case KIND_CITIES:
                return "Cities";
            case KIND_COMMUNITIES:
                return "Communities";
            case KIND_TRIBAL:
                return "Tribal Lands";
            case KIND_RESPONSIBILITY:
                return "Responsibility Areas";
            case KIND_DPA:
                return "Protection Areas";
            case KIND_OWNERSHIP:
                return "Land Ownership";
            case KIND_PUBLIC_LAND:
                return "Public Land";
            case KIND_ACCESS:
                return "Public Access";
            default:
                return "Counties";
        }
    }

    /** What the Kind row says under its name. */
    public static String kindNote(String kind) {
        switch (kind == null ? "" : kind) {
            case KIND_CITIES:
                return "Incorporated cities, towns and villages";
            case KIND_COMMUNITIES:
                return "Unincorporated communities the Census draws a line around";
            case KIND_TRIBAL:
                return "Federal and state reservations and off-reservation trust lands";
            case KIND_RESPONSIBILITY:
                return "Who has fire protection responsibility: state (SRA), local (LRA) or federal (FRA), from CAL FIRE";
            case KIND_DPA:
                return "Direct Protection Areas: which agency answers a wildland fire, CAL FIRE, USFS, BLM and the rest";
            case KIND_OWNERSHIP:
                return "Public land by owner, federal, state and local, from CAL FIRE; private land is what stays uncolored";
            case KIND_PUBLIC_LAND:
                return "Federal land by managing agency, from the USGS Protected Areas Database";
            case KIND_ACCESS:
                return "Where the public may enter: open, restricted or closed, from the USGS Protected Areas Database";
            default:
                return "County and county-equivalent boundaries";
        }
    }

    /**
     * The colors a Boundaries layer can be drawn in, name and RGB, in the picker's order
     * (operator, 2026-10-13: "could we make it where the user could choose their color
     * of preference?"). Each kind starts in its own; the pick is per layer and saved.
     */
    public static final Object[][] COLORS = {
            { "Purple", 0xB040FF }, { "Royal Blue", 0x2962FF }, { "Yellow", 0xFFD600 }, { "Pink", 0xFF4FA3 },
            { "Red", 0xFF1744 }, { "Green", 0x00C853 }, { "Teal", 0x00BFA5 }, { "Orange", 0xFF9100 },
            { "White", 0xFFFFFF }, { "Black", 0x000000 },
    };

    /** The color a layer draws in: the operator's pick, else its kind's. */
    public static int hue(LayerSpec s) {
        return s != null && s.outlineRgb != 0 ? s.outlineRgb & 0x00FFFFFF : hue(s == null ? null : s.kind);
    }

    /** "Purple": the picker's name for a color, or its hex when it is not one of them. */
    public static String colorName(int rgb) {
        for (Object[] c : COLORS)
            if (((Integer) c[1] & 0x00FFFFFF) == (rgb & 0x00FFFFFF))
                return (String) c[0];
        return String.format(Locale.US, "#%06X", rgb & 0x00FFFFFF);
    }

    public static int hue(String kind) {
        switch (kind == null ? "" : kind) {
            case KIND_CITIES:
                return CITY_RGB;
            case KIND_COMMUNITIES:
                return COMMUNITY_RGB;
            case KIND_TRIBAL:
                return TRIBAL_RGB;
            default:
                return COUNTY_RGB;
        }
    }

    /** The service a kind reads. */
    static String base(String kind) {
        switch (kind == null ? "" : kind) {
            case KIND_CITIES:
            case KIND_COMMUNITIES:
                return PLACES;
            case KIND_TRIBAL:
                return AIANNHA;
            case KIND_RESPONSIBILITY:
                return CA_RESPONSIBILITY;
            case KIND_DPA:
                return CA_DPA;
            case KIND_OWNERSHIP:
                return CA_OWNERSHIP;
            case KIND_PUBLIC_LAND:
                return PADUS_MANAGERS;
            case KIND_ACCESS:
                return PADUS_ACCESS;
            default:
                return STATE_COUNTY;
        }
    }

    /** The source layers a kind reads, in draw order. */
    static int[] layerIds(String kind, String[] states) {
        if (mosaic(kind))
            return new int[] { 0 };
        switch (kind == null ? "" : kind) {
            case KIND_CITIES:
                return new int[] { 4 }; // Incorporated Places
            case KIND_COMMUNITIES:
                return new int[] { 5 }; // Census Designated Places
            case KIND_TRIBAL:
                return has(states, "15") ? new int[] { 2, 3, 4, 5 } : new int[] { 2, 3, 4 };
            default:
                return new int[] { 1 };
        }
    }

    /** The type a source layer's features are listed under in the Features list. */
    public static String setName(String kind, int layerId) {
        switch (kind == null ? "" : kind) {
            case KIND_CITIES:
                return "City";
            case KIND_COMMUNITIES:
                return "Community";
            case KIND_TRIBAL:
                return layerId == 3 ? "Trust land" : layerId == 4 ? "State reservation"
                        : layerId == 5 ? "Hawaiian home land" : "Federal reservation";
            default:
                return "County";
        }
    }

    private static boolean has(String[] a, String v) {
        if (a == null)
            return false;
        for (String s : a)
            if (v.equals(s))
                return true;
        return false;
    }

    /** The types a kind always offers, each with a line saying what it is. */
    /** The classes a mosaic always offers, as the publisher labels them, with a line each where one helps. */
    private static final String[][] MOSAIC_TYPES = {
            { KIND_RESPONSIBILITY, "State Responsibility Area (SRA)", "CAL FIRE has fire protection responsibility" },
            { KIND_RESPONSIBILITY, "Local Responsibility Area (LRA)", "A city, county or district has it" },
            { KIND_RESPONSIBILITY, "Federal Responsibility Area (FRA)", "A federal agency has it" },
            { KIND_ACCESS, "Open Access", "The public may enter" },
            { KIND_ACCESS, "Restricted Access", "Entry with a permit or at set times" },
            { KIND_ACCESS, "Closed Access", "No public entry" },
    };

    static void notes(LayerSpec s) {
        s.setNotes.clear();
        if (mosaic(s.kind)) {
            for (String[] t : MOSAIC_TYPES)
                if (t[0].equals(s.kind)) {
                    s.setNotes.put(t[1], t[2]);
                    s.setKind.put(t[1], "polygon");
                }
            return;
        }
        for (int id : layerIds(s.kind, s.states)) {
            final String n = setName(s.kind, id);
            s.setNotes.put(n, KIND_CITIES.equals(s.kind) ? "Cities, towns and villages"
                    : KIND_COMMUNITIES.equals(s.kind) ? "Unincorporated communities with a Census boundary"
                    : KIND_TRIBAL.equals(s.kind)
                    ? (id == 3 ? "Land held in trust off a reservation" : id == 4 ? "Reservations a state recognizes"
                            : id == 5 ? "Hawaiian Homes Commission lands" : "Reservations the federal government recognizes")
                    : "Counties, parishes, boroughs and independent cities");
            s.setKind.put(n, "polygon");
        }
    }

    /** "Nevada Counties", "Elko, Humboldt Counties (NV)", "Nevada, Utah Tribal Lands". */
    public static String title(String kind, String[] states, String[] pickNames) {
        final StringBuilder sb = new StringBuilder();
        if (pickNames != null && pickNames.length > 0) {
            final int shown = Math.min(3, pickNames.length);
            for (int i = 0; i < shown; i++)
                sb.append(i > 0 ? ", " : "").append(pickNames[i]);
            if (pickNames.length > shown)
                sb.append(" +").append(pickNames.length - shown);
            sb.append(' ').append(kindTitle(kind)).append(" (");
            for (int i = 0; i < states.length; i++) {
                final State st = state(states[i]);
                sb.append(i > 0 ? ", " : "").append(st == null ? states[i] : st.abbr);
            }
            return sb.append(')').toString();
        }
        for (int i = 0; i < states.length; i++) {
            final State st = state(states[i]);
            sb.append(i > 0 ? ", " : "").append(st == null ? states[i] : st.name);
        }
        return sb.append(' ').append(kindTitle(kind)).toString();
    }

    /** "Nevada", "Nevada, Utah": the state row every kind of one set of states folds under. */
    public static String groupTitle(String[] states) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; states != null && i < states.length; i++) {
            final State st = state(states[i]);
            sb.append(i > 0 ? ", " : "").append(st == null ? states[i] : st.name);
        }
        return sb.toString();
    }

    /** The key layers fold under in the list: their states, in order. */
    public static String groupKey(LayerSpec s) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; s.states != null && i < s.states.length; i++)
            sb.append(i > 0 ? "-" : "").append(s.states[i]);
        return sb.toString();
    }

    /** What a layer is called inside its state's row: "Counties", "Elko, Humboldt Counties". */
    public static String shortTitle(LayerSpec s) {
        if (s.picks == null || s.picks.length == 0)
            return kindTitle(s.kind);
        final String t = s.title == null ? kindTitle(s.kind) : s.title;
        final int paren = t.lastIndexOf(" (");
        return paren > 0 ? t.substring(0, paren) : t;
    }

    /** A stable id for what was picked; the file name is derived from it. */
    static String id(String kind, String[] states, String[] picks) {
        final StringBuilder sb = new StringBuilder(ID_PREFIX).append(kind).append(':');
        final String[] st = states.clone();
        Arrays.sort(st);
        for (int i = 0; i < st.length; i++)
            sb.append(i > 0 ? "-" : "").append(st[i]);
        if (picks != null && picks.length > 0) {
            final String[] p = picks.clone();
            Arrays.sort(p);
            sb.append(':');
            if (p.length <= 6) {
                for (int i = 0; i < p.length; i++)
                    sb.append(i > 0 ? "-" : "").append(p[i]);
            } else {
                int h = 17;
                for (String g : p)
                    h = h * 31 + g.hashCode();
                sb.append("pick").append(p.length).append('-').append(Integer.toHexString(h));
            }
        }
        return sb.toString();
    }

    /**
     * The layer for a kind over one to five states, every boundary in them or only the
     * named ones ({@code picks} are GEOIDs, {@code pickNames} their names for the title).
     */
    public static LayerSpec layer(String kind, String[] states, String[] picks, String[] pickNames) {
        final LayerSpec s = new LayerSpec();
        s.kind = kind;
        s.states = states.clone();
        s.picks = picks == null || picks.length == 0 ? null : picks.clone();
        s.id = id(kind, states, picks);
        s.title = title(kind, states, pickNames);
        s.subtitle = subtitle(kind);
        fixed(s);
        if (mosaic(kind)) {
            // An area classification: the fill is the information, half see-through so
            // the ground reads through it; no name pills, the class is on the Features
            // list and on tap.
            s.fillAlpha = 0x80;
            s.labels = false;
            s.labelGsd = LABEL_GSD_20MI;
            s.gateGsd = Double.MAX_VALUE;
            s.scopeRadiusM = 40000;
            return s;
        }
        s.fillAlpha = 0; // outline only; the fill control adds a tint
        s.labels = true;
        final boolean places = KIND_CITIES.equals(kind) || KIND_COMMUNITIES.equals(kind);
        s.labelGsd = places ? LayerSpec.DEFAULT_LABEL_GSD_WIDE : LABEL_GSD_20MI;
        s.gateGsd = places || KIND_TRIBAL.equals(kind) ? GATE_GSD_20MI : Double.MAX_VALUE;
        return s;
    }

    /** Who published it, for the row: the Census, CAL FIRE, NIFC, USGS. */
    static String subtitle(String kind) {
        switch (kind == null ? "" : kind) {
            case KIND_RESPONSIBILITY:
            case KIND_OWNERSHIP:
                return "CAL FIRE";
            case KIND_DPA:
                return "NIFC";
            case KIND_PUBLIC_LAND:
            case KIND_ACCESS:
                return "USGS PAD-US";
            default:
                return "Census";
        }
    }

    /** The parts the plugin owns, applied on creation and again on every restore ({@link Sources#migrate}). */
    static void fixed(LayerSpec s) {
        s.iconSet = "boundaries";
        s.portal = null;
        s.orgName = null;
        s.base = base(s.kind);
        s.layerIds = layerIds(s.kind, s.states);
        s.layerTitle = kindTitle(s.kind);
        s.geojson = false;
        s.profile = LayerSpec.Profile.GENERIC;
        s.live = false;
        s.refreshMinutes = 0;
        s.maxFeatures = 0;
        s.timeField = null;
        s.setField = setField(s.kind);
        if (mosaic(s.kind)) {
            // What is in view, simplified to the zoom, the Fire History pattern: a
            // state's responsibility areas are 20,000 polygons and never wanted whole.
            s.scopeKind = "view";
            s.generalize = true;
            // Ownership around Santa Barbara is 2,200 polygons in a 100-mile box and the
            // map's own bounds run wider than the pane shows; a 3,000 cap cut the Los
            // Padres block out of the first fetch (2026-10-14).
            s.maxFeatures = 8000;
        } else {
            s.scopeKind = null;
            s.generalize = false;
        }
        s.where = where(s);
        notes(s);
        // A tribal layer saved with the old "every zoom" takes the new gate, once: the
        // style version moves on after its next fetch, so a gate set later stays.
        if (KIND_TRIBAL.equals(s.kind) && s.styleVersion < TRIBAL_GATE_SINCE && s.gateGsd == Double.MAX_VALUE)
            s.gateGsd = GATE_GSD_20MI;
    }

    /** The where clause: the states by FIPS, or the picked GEOIDs. Tribal lands have no state field; their state is a shape ({@link #passes}). */
    static String where(LayerSpec s) {
        if (mosaic(s.kind)) {
            // PAD-US managers carry the state by postal code; CAL FIRE's layers are one
            // state, and PAD-US access has no state field. The view does the scoping.
            if (!KIND_PUBLIC_LAND.equals(s.kind))
                return "1=1";
            final List<String> ab = new ArrayList<>();
            for (String f : s.states) {
                final State st = state(f);
                if (st != null)
                    ab.add(st.abbr);
            }
            return ab.isEmpty() ? "1=1" : "State_Nm IN (" + list(ab.toArray(new String[0])) + ")";
        }
        if (s.picks != null && s.picks.length > 0)
            return "GEOID IN (" + list(s.picks) + ")";
        if (KIND_TRIBAL.equals(s.kind))
            return "1=1";
        return "STATE IN (" + list(s.states) + ")";
    }

    private static String list(String[] v) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length; i++)
            sb.append(i > 0 ? "," : "").append(Esri.sql(v[i]));
        return sb.toString();
    }

    /** The whole globe, simplified: a scope that costs nothing but carries the detail setting (and goes by POST). */
    private static Esri.Scope world() {
        return Esri.Scope.box(-90, -180, 90, 180).simplified(OFFSET_DEG);
    }

    /**
     * The spatial filters a fetch runs, in turn: one pass for counties, cities, communities and any
     * picked names; one pass per state for every tribal land in a state, each the
     * state's own outline POSTed as the filter (the AIANNHA layers carry no state
     * field). Worker thread; a state outline that cannot be fetched throws.
     */
    static List<Esri.Scope> passes(LayerSpec s) throws Exception {
        final List<Esri.Scope> out = new ArrayList<>();
        if (mosaic(s.kind)) {
            out.add(null); // the layer's own view scope, simplified to the zoom
            return out;
        }
        if (!KIND_TRIBAL.equals(s.kind) || (s.picks != null && s.picks.length > 0)) {
            out.add(world());
            return out;
        }
        for (String fips : s.states)
            out.add(Esri.Scope.polygon(stateRings(fips)).simplified(OFFSET_DEG));
        return out;
    }

    private static final Map<String, String> RINGS = new HashMap<>();

    /** A state's outline as Esri JSON rings, simplified to about a kilometer (Nevada is 52 vertices). Worker thread. */
    static synchronized String stateRings(String fips) throws Exception {
        final String have = RINGS.get(fips);
        if (have != null)
            return have;
        final String url = STATE_COUNTY + "/0/query?where=" + Esri.enc("STATE=" + Esri.sql(fips))
                + "&outFields=STATE&returnGeometry=true&outSR=4326&maxAllowableOffset=0.01&f=json";
        final JSONObject d = new JSONObject(Esri.get(url));
        if (d.has("error"))
            throw new IllegalStateException(d.getJSONObject("error").optString("message"));
        final JSONArray f = d.optJSONArray("features");
        if (f == null || f.length() == 0)
            throw new IllegalStateException("no outline for state " + fips);
        final JSONArray rings = f.getJSONObject(0).getJSONObject("geometry").getJSONArray("rings");
        final String out = rings.toString();
        RINGS.put(fips, out);
        return out;
    }

    // ---- what a feature says ------------------------------------------------------

    /** The name on the map: "Elko County" for a county; the bare name ("Reno") for a city or a tribal land. */
    public static String label(String kind, JSONObject p) {
        final String base = Esri.firstNonEmpty(p.optString("BASENAME", null));
        final String name = Esri.firstNonEmpty(p.optString("NAME", null));
        if (KIND_COUNTIES.equals(kind))
            return name != null ? name : base;
        return base != null ? base : name;
    }

    /** The Census interior point, where the label goes; null when a row has none. */
    static com.atakmap.map.layer.feature.geometry.Point interiorPoint(JSONObject p) {
        try {
            final String la = p.optString("INTPTLAT", null), lo = p.optString("INTPTLON", null);
            if (la == null || lo == null || la.isEmpty() || lo.isEmpty())
                return null;
            final double lat = Double.parseDouble(la.trim()), lon = Double.parseDouble(lo.trim());
            if (Double.isNaN(lat) || Double.isNaN(lon) || lat < -90 || lat > 90 || lon < -180 || lon > 180)
                return null;
            if (Math.abs(lat) < 1e-6 && Math.abs(lon) < 1e-6)
                return null;
            return new com.atakmap.map.layer.feature.geometry.Point(lon, lat);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The FIPS state of a row: its own field, or the first two digits of its GEOID. */
    static String stateOf(JSONObject p) {
        final String st = Esri.firstNonEmpty(p.optString("STATE", null));
        if (st != null)
            return st;
        final String g = Esri.firstNonEmpty(p.optString("GEOID", null));
        return g != null && g.length() >= 2 ? g.substring(0, 2) : null;
    }

    /** The state's FIPS code on every feature, for the "You are in" line; "_" keeps it out of the details. */
    static final String ATTR_STATE = "_state";

    /** The lines the details page shows, in place of the Census row's thirty codes. */
    static AttributeSet attributes(String kind, String setName, JSONObject p) {
        final AttributeSet a = new AttributeSet();
        if (mosaic(kind)) {
            // The publisher's own words for what this ground is, and nothing else.
            switch (kind) {
                case KIND_RESPONSIBILITY:
                    a.setAttribute("Responsibility", setName);
                    break;
                case KIND_DPA:
                    a.setAttribute("Protection", setName);
                    put(a, "Group", p, "DPA_GROUP");
                    put(a, "Unit", p, "NWCG_UNITID");
                    put(a, "Agreements", p, "AGREEMENTS");
                    break;
                case KIND_OWNERSHIP:
                    a.setAttribute("Owner group", setName);
                    put(a, "Owner", p, "Own_Agency");
                    put(a, "Level", p, "Own_Level");
                    break;
                case KIND_PUBLIC_LAND:
                    a.setAttribute("Manager", setName);
                    put(a, "Unit", p, "Unit_Nm");
                    put(a, "Owner", p, "Own_Name");
                    put(a, "Access", p, "Pub_Access");
                    break;
                default:
                    a.setAttribute("Access", setName);
                    put(a, "Unit", p, "Unit_Nm");
                    put(a, "Manager", p, "MngNm_Desc");
                    break;
            }
            return a;
        }
        final String fips = stateOf(p);
        if (fips != null)
            a.setAttribute(ATTR_STATE, fips);
        final String name = Esri.firstNonEmpty(p.optString("NAME", null));
        if (name != null)
            a.setAttribute("Name", name);
        final State st = state(stateOf(p));
        if (st != null)
            a.setAttribute("State", st.name);
        a.setAttribute("Kind", setName);
        final Object land = p.opt("AREALAND");
        if (land instanceof Number && ((Number) land).doubleValue() > 0)
            a.setAttribute("Land area", area(((Number) land).doubleValue()));
        final String geoid = Esri.firstNonEmpty(p.optString("GEOID", null));
        if (geoid != null)
            a.setAttribute("GEOID", geoid);
        return a;
    }

    /**
     * A publisher's fill color made to read over satellite imagery: CAL FIRE and NIFC
     * draw their responsibility and protection areas in pastels for a white basemap
     * (SRA is 255,255,190), and at half opacity over tan terrain those vanished
     * (2026-10-14). The hue is kept, so a legend still matches; the color is pushed to
     * at least mid saturation and off pure white. A grey or white class stays a grey.
     */
    static int strongFill(int rgb) {
        final float[] hsv = new float[3];
        android.graphics.Color.colorToHSV(0xFF000000 | rgb, hsv);
        if (hsv[1] < 0.12f)
            return 0x9E9E9E;
        hsv[1] = Math.max(hsv[1], 0.75f);
        hsv[2] = Math.min(Math.max(hsv[2], 0.55f), 0.92f);
        return android.graphics.Color.HSVToColor(hsv) & 0x00FFFFFF;
    }

    private static void put(AttributeSet a, String key, JSONObject p, String field) {
        final String v = p.isNull(field) ? null : Esri.firstNonEmpty(p.optString(field, null));
        if (v != null)
            a.setAttribute(key, v);
    }

    /** Square meters in the operator's units: square miles or square kilometers. */
    static String area(double m2) {
        final boolean metric = Units.type() == com.atakmap.coremap.conversions.Span.METRIC;
        final double n = metric ? m2 / 1e6 : m2 / (MILE * MILE);
        final String num = n < 10 ? String.format(Locale.US, "%.1f", n) : String.format(Locale.US, "%,.0f", n);
        return num + (metric ? " sq km" : " sq mi");
    }

    /** The outline in the layer's color, filled at {@code alpha} (0 = outline only). */
    static Style style(LayerSpec s, int alpha) {
        final int rgb = hue(s);
        final Style edge = NwcgStyles.solid(0xFF000000 | rgb, 2f);
        if (alpha <= 0)
            return edge;
        return NwcgStyles.over(new com.atakmap.map.layer.feature.style.BasicFillStyle(
                (Math.min(255, alpha) << 24) | rgb), edge);
    }

    /**
     * The same style in another color: every stroke and fill in it takes {@code rgb},
     * each keeping its own width or opacity; the name pill and labels are untouched.
     */
    static Style recolored(Style s, int rgb) {
        if (s instanceof com.atakmap.map.layer.feature.style.BasicStrokeStyle) {
            final com.atakmap.map.layer.feature.style.BasicStrokeStyle b = (com.atakmap.map.layer.feature.style.BasicStrokeStyle) s;
            return new com.atakmap.map.layer.feature.style.BasicStrokeStyle(0xFF000000 | rgb, b.getStrokeWidth());
        }
        if (s instanceof com.atakmap.map.layer.feature.style.BasicFillStyle) {
            final int a = ((com.atakmap.map.layer.feature.style.BasicFillStyle) s).getColor() & 0xFF000000;
            return new com.atakmap.map.layer.feature.style.BasicFillStyle(a | (rgb & 0x00FFFFFF));
        }
        if (s instanceof com.atakmap.map.layer.feature.style.CompositeStyle) {
            final com.atakmap.map.layer.feature.style.CompositeStyle c = (com.atakmap.map.layer.feature.style.CompositeStyle) s;
            final Style[] parts = new Style[c.getNumStyles()];
            for (int i = 0; i < parts.length; i++)
                parts[i] = recolored(c.getStyle(i), rgb);
            return new com.atakmap.map.layer.feature.style.CompositeStyle(parts);
        }
        return s;
    }

    // ---- the pick-names list -------------------------------------------------------

    /** One boundary by name, for the Pick names list. */
    public static final class Named {
        public final String geoid, name, type, state;
        /** The kind this name belongs to, so a pick across kinds makes one layer per kind. */
        public String kind;
        /** The name without its legal suffix ("Elko" for Elko County), for a layer title. */
        public final String shortName;
        public final double lat, lon;
        public double distanceM;

        Named(String geoid, String name, String shortName, String type, String state, double lat, double lon) {
            this.geoid = geoid;
            this.name = name;
            this.shortName = shortName == null || shortName.isEmpty() ? name : shortName;
            this.type = type;
            this.state = state;
            this.lat = lat;
            this.lon = lon;
        }

        /** "Elko County", "Reno (Community, NV)". */
        public String label(boolean oneState, boolean oneType) {
            final StringBuilder sb = new StringBuilder(name);
            final String stAbbr = state == null ? null : state(state) == null ? null : state(state).abbr;
            if (!oneType || !oneState) {
                sb.append(" (");
                if (!oneType)
                    sb.append(type);
                if (!oneState && stAbbr != null)
                    sb.append(oneType ? "" : ", ").append(stAbbr);
                sb.append(')');
            }
            return sb.toString();
        }
    }

    /**
     * Every boundary of the kinds in the states, names only, nearest the point first
     * (17 rows for Nevada counties, 1,864 for Texas cities and communities). Worker
     * thread.
     */
    public static List<Named> names(String[] kinds, String[] states, final double lat, final double lon) throws Exception {
        final Map<String, Named> out = new LinkedHashMap<>();
        for (String kind : kinds) {
            if (mosaic(kind))
                continue; // a classification has no names to pick
            final String fields = "NAME,BASENAME,GEOID,INTPTLAT,INTPTLON" + (KIND_TRIBAL.equals(kind) ? "" : ",STATE");
            final Map<String, Named> mine = new LinkedHashMap<>();
            for (int layerId : layerIds(kind, states)) {
                final String type = setName(kind, layerId);
                if (KIND_TRIBAL.equals(kind)) {
                    for (String fips : states)
                        namesPage(kind, layerId, type, fips, fields, "1=1", stateRings(fips), mine);
                } else {
                    namesPage(kind, layerId, type, null, fields, "STATE IN (" + list(states) + ")", null, mine);
                }
            }
            for (Named n : mine.values()) {
                n.kind = kind;
                out.put(kind + ":" + n.geoid, n);
            }
        }
        final List<Named> list = new ArrayList<>(out.values());
        for (Named n : list)
            n.distanceM = Double.isNaN(lat) || Double.isNaN(n.lat) ? Double.MAX_VALUE : distanceM(lat, lon, n.lat, n.lon);
        Collections.sort(list, new Comparator<Named>() {
            @Override
            public int compare(Named a, Named b) {
                final int d = Double.compare(a.distanceM, b.distanceM);
                return d != 0 ? d : a.name.compareToIgnoreCase(b.name);
            }
        });
        return list;
    }

    private static void namesPage(String kind, int layerId, String type, String stateFips, String fields, String where,
            String rings, Map<String, Named> out) throws Exception {
        int offset = 0;
        final int page = 2000;
        while (true) {
            final String params = "where=" + Esri.enc(where) + "&outFields=" + fields
                    + "&returnGeometry=false&resultRecordCount=" + page + "&resultOffset=" + offset + "&f=json"
                    + (rings == null ? "" : Esri.Scope.polygon(rings).params());
            final String url = base(kind) + "/" + layerId + "/query";
            final JSONObject d = new JSONObject(rings == null ? Esri.get(url + "?" + params) : Esri.post(url, params, null));
            if (d.has("error"))
                throw new IllegalStateException(d.getJSONObject("error").optString("message"));
            final JSONArray feats = d.optJSONArray("features");
            if (feats == null || feats.length() == 0)
                break;
            for (int i = 0; i < feats.length(); i++) {
                final JSONObject a = feats.getJSONObject(i).optJSONObject("attributes");
                if (a == null)
                    continue;
                final String geoid = Esri.firstNonEmpty(a.optString("GEOID", null));
                final String name = label(kind, a);
                if (geoid == null || name == null || out.containsKey(geoid))
                    continue;
                final com.atakmap.map.layer.feature.geometry.Point at = interiorPoint(a);
                out.put(geoid, new Named(geoid, name, Esri.firstNonEmpty(a.optString("BASENAME", null)), type,
                        stateFips != null ? stateFips : stateOf(a),
                        at == null ? Double.NaN : at.getY(), at == null ? Double.NaN : at.getX()));
            }
            if (!d.optBoolean("exceededTransferLimit", false) && feats.length() < page)
                break;
            offset += feats.length();
        }
    }

    static double distanceM(double lat1, double lon1, double lat2, double lon2) {
        final double r = 6371000d, dLat = Math.toRadians(lat2 - lat1), dLon = Math.toRadians(lon2 - lon1);
        final double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}

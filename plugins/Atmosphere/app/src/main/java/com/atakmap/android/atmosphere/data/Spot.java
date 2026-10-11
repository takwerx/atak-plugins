package com.atakmap.android.atmosphere.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NWS spot forecasts: every open spot request in the country, and the forecast NWS
 * issued for it. Pure request building and response reading; the page fetches.
 *
 * <h3>Where it comes from, measured 2026-09-24</h3>
 *
 * The list is NWS's published "Fire Weather Spot Web Services" map service,
 * {@code nws_fire_weather_spot/MapServer/0}: no key, "updated every 15 minutes", one
 * row per open request (423 that morning, 24 KB gzipped for all of them) with its
 * name, type, point, office, status and times.
 *
 * <p>It was not the first source. {@code spot.weather.gov/cms/api/1.0/requests}
 * answered 200 without a key for an hour and was built on; it is keyed. Akamai caches
 * that URL for three minutes under a cache key that ignores the Authorization header,
 * so an unauthenticated request was being handed a copy of the Spot Monitor's own
 * authorized fetch. Nothing here touches that API, and the web app's key is never used.
 *
 * <p>The service carries no state, region or agency. Region comes from the office
 * ({@link #OFFICES}, built from api.weather.gov/offices); state from the point
 * ({@link States}). The issued forecast is the public FWS product on api.weather.gov:
 * the office's FWS list, then the product issued within a quarter hour of the
 * request's fill time whose first line is "Spot Forecast for &lt;name&gt;".
 */
public final class Spot {

    public static final String HOST = "mapservices.weather.noaa.gov";
    public static final String LIST_URL = "https://" + HOST
            + "/vector/rest/services/fire_weather/nws_fire_weather_spot/MapServer/0/query"
            + "?where=1%3D1&outFields=snumunum,name,type,tid,lat,lon,rmade,rfill,stat,"
            + "stattext,wfo,deliverdtg&returnGeometry=false&f=json";
    /** Where a request is made, and the program's own page: its web app, in the browser. */
    public static final String NEW_REQUEST_URL = "https://spot.weather.gov/new-request";
    public static final String MONITOR_URL = "https://spot.weather.gov/";
    public static final String FWS_HOST = "api.weather.gov";
    /** How far a product's issuance may sit from the request's fill time. */
    static final long MATCH_WINDOW_MS = 15 * 60_000L;

    /**
     * Office id, NWS region and the name the office goes by, for the 123 forecast
     * offices api.weather.gov lists (2026-09-24) and the two national centers that
     * issue spot forecasts at sea.
     */
    static final String OFFICES =
            "ABQ:SR:Albuquerque;ABR:CR:Aberdeen;AFC:AR:Anchorage;AFG:AR:Fairbanks;"
            + "AJK:AR:Juneau;AKQ:ER:Wakefield;ALU:AR:Anchorage West;ALY:ER:Albany;"
            + "AMA:SR:Amarillo;APX:CR:Gaylord;ARX:CR:La Crosse;BGM:ER:Binghamton;"
            + "BIS:CR:Bismarck;BMX:SR:Birmingham;BOI:WR:Boise;BOU:CR:Denver/Boulder;"
            + "BOX:ER:Boston / Norton;BRO:SR:Brownsville/Rio Grande Valley;BTV:ER:Burlington;"
            + "BUF:ER:Buffalo;BYZ:WR:Billings;CAE:ER:Columbia;CAR:ER:Caribou;CHS:ER:Charleston;"
            + "CLE:ER:Cleveland;CRP:SR:Corpus Christi;CTP:ER:State College;CYS:CR:Cheyenne;"
            + "DDC:CR:Dodge City;DLH:CR:Duluth;DMX:CR:Des Moines;DTX:CR:Detroit/Pontiac;"
            + "DVN:CR:Quad Cities;EAX:CR:Kansas City/Pleasant Hill;EKA:WR:Eureka;"
            + "EPZ:SR:El Paso;EWX:SR:Austin/San Antonio;FFC:SR:Atlanta/Peachtree City;"
            + "FGF:CR:Grand Forks;FGZ:WR:NWS Flagstaff;FSD:CR:Sioux Falls;"
            + "FWD:SR:Fort Worth/Dallas;GGW:WR:Glasgow;GID:CR:Hastings;GJT:CR:Grand Junction;"
            + "GLD:CR:Goodland;GRB:CR:Green Bay;GRR:CR:Grand Rapids;"
            + "GSP:ER:Greenville-Spartanburg;GUM:PR:Tiyan;GYX:ER:Gray - Portland;"
            + "HFO:PR:Honolulu;HGX:SR:Houston/Galveston;HNX:WR:San Joaquin Valley;"
            + "HUN:SR:Huntsville;ICT:CR:Wichita;ILM:ER:NWS Wilmington;ILN:ER:Wilmington;"
            + "ILX:CR:Central Illinois;IND:CR:Indianapolis;IWX:CR:Northern Indiana;"
            + "JAN:SR:Jackson;JAX:SR:Jacksonville;JKL:CR:Jackson;KEY:SR:Key West;"
            + "LBF:CR:North Platte;LCH:SR:Lake Charles;LIX:SR:New Orleans/Baton Rouge;"
            + "LKN:WR:Elko;LMK:CR:Louisville;LOT:CR:Chicago;LOX:WR:Los Angeles;"
            + "LSX:CR:St. Louis;LUB:SR:Lubbock;LWX:ER:Baltimore/Washington;LZK:SR:Little Rock;"
            + "MAF:SR:Midland/Odessa;MEG:SR:Memphis;MFL:SR:Miami - South Florida;"
            + "MFR:WR:Medford;MHX:ER:Newport/Morehead City;MKX:CR:Milwaukee/Sullivan;"
            + "MLB:SR:Melbourne;MOB:SR:Mobile/Pensacola;MPX:CR:Twin Cities;MQT:CR:Marquette;"
            + "MRX:SR:Morristown;MSO:WR:Missoula;MTR:WR:San Francisco Bay Area;"
            + "OAX:CR:Omaha/Valley;OHX:SR:Nashville;OKX:ER:New York;OTX:WR:Spokane;"
            + "OUN:SR:Norman;PAH:CR:Paducah;PBZ:ER:Pittsburgh;PDT:WR:Pendleton;"
            + "PHI:ER:Philadelphia/Mt Holly;PIH:WR:Pocatello;PQR:WR:Portland;"
            + "PSR:WR:NWS Phoenix;PUB:CR:Pueblo;RAH:ER:Raleigh;REV:WR:Reno;"
            + "RIW:CR:Western and Central Wyoming;RLX:ER:Charleston;RNK:ER:Blacksburg;"
            + "SEW:WR:Seattle/Tacoma;SGF:CR:Springfield;SGX:WR:San Diego;SHV:SR:Shreveport;"
            + "SJT:SR:San Angelo;SJU:SR:San Juan;SLC:WR:Salt Lake City;STO:WR:Sacramento;"
            + "TAE:SR:Tallahassee;TBW:SR:Tampa Bay Area;TFX:WR:Great Falls;TOP:CR:Topeka;"
            + "TSA:SR:Tulsa;TWC:WR:NWS Tucson Arizona;UNR:CR:Rapid City;VEF:WR:Las Vegas;"
            + "NHC:NC:National Hurricane Center;OPC:NC:Ocean Prediction Center";

    private static final Map<String, String[]> OFFICE_TABLE = new HashMap<>();

    static {
        for (String row : OFFICES.split(";")) {
            final String[] f = row.split(":", 3);
            if (f.length == 3)
                OFFICE_TABLE.put(f[0], new String[] { f[1], f[2].replaceFirst("^NWS ", "") });
        }
    }

    /** The service's type id to what it is; measured against the Monitor's own names. */
    private static final Map<String, String> KINDS = new HashMap<>();

    static {
        KINDS.put("0", "Wildfire");
        KINDS.put("1", "Prescribed Fire");
        KINDS.put("2", "Marine");
        KINDS.put("4", "HAZMAT");
        KINDS.put("5", "Search and Rescue, inland water");
        KINDS.put("6", "Search and Rescue");
        KINDS.put("7", "Special event");
        KINDS.put("9", "Search and Rescue at sea");
    }

    /** One spot request, the fields a crew reads. */
    public static final class Request {
        public final String id;
        public final String project;
        /** "Wildfire", "Prescribed Fire", "HAZMAT"... */
        public final String kind;
        /** The office's id ("EKA") and the name it goes by ("Eureka"). */
        public final String office, officeName;
        /** NWS region code: WR, SR, CR, ER, AR, PR, NC; "" when the office is unknown. */
        public final String region;
        /** Two-letter state from the point, or "" at sea. */
        public final String state;
        public final double lat, lon;
        public final long requestedAt, filledAt, deliverAt;
        /** True while NWS has the request open: not yet filled, or an update asked for. */
        public final boolean pending;

        Request(JSONObject a, States states) {
            id = a.optString("snumunum", "").trim();
            project = clean(a.optString("name", ""));
            final String k = KINDS.get(a.optString("tid", ""));
            kind = k != null ? k : kindFromType(a.optString("type", ""));
            office = a.optString("wfo", "").trim().toUpperCase(Locale.US);
            final String[] o = OFFICE_TABLE.get(office);
            region = o == null ? "" : o[0];
            officeName = o == null ? office : o[1];
            lat = number(a.optString("lat", ""));
            lon = number(a.optString("lon", ""));
            state = states == null || !hasPosition() ? "" : states.at(lat, lon);
            requestedAt = localTime(a.optString("rmade", ""));
            filledAt = localTime(a.optString("rfill", ""));
            deliverAt = localTime(a.optString("deliverdtg", ""));
            pending = "P".equals(a.optString("stat", ""));
        }

        /**
         * Where the request stands, in words. Measured: C "Completed" (388 of 423) and
         * P "Request pending", which is either never filled (the fill time reads
         * "Incomplete") or an update asked for after one was.
         */
        public String status() {
            if (filledAt <= 0)
                return "Waiting for the forecast";
            return pending ? "Update requested" : "Forecast issued";
        }

        public boolean hasPosition() {
            return !Double.isNaN(lat) && !Double.isNaN(lon)
                    && Math.abs(lat) <= 90 && Math.abs(lon) <= 180;
        }
    }

    private Spot() {
    }

    /** Every request in a {@link #LIST_URL} answer; one without an id or a point is skipped. */
    public static List<Request> parse(String body, States states) throws Exception {
        final JSONArray fs = new JSONObject(body).optJSONArray("features");
        final List<Request> out = new ArrayList<>();
        for (int i = 0; fs != null && i < fs.length(); i++) {
            final JSONObject f = fs.optJSONObject(i);
            final JSONObject a = f == null ? null : f.optJSONObject("attributes");
            if (a == null)
                continue;
            final Request q = new Request(a, states);
            if (!q.id.isEmpty() && q.hasPosition())
                out.add(q);
        }
        return out;
    }

    private static final Pattern LOCAL = Pattern.compile(
            "(\\d{4})-(\\d{2})-(\\d{2}) (\\d{2}):(\\d{2}):(\\d{2}) [AP]M ([A-Za-z]+)");

    /** Hours from UTC. Seen 2026-09-24: AKDT CDT EDT GMT HST MDT MST PDT; the rest by name. */
    private static final Map<String, Integer> ZONES = new HashMap<>();

    static {
        final Object[] z = { "GMT", 0, "UTC", 0, "EDT", -4, "EST", -5, "CDT", -5, "CST", -6,
                "MDT", -6, "MST", -7, "PDT", -7, "PST", -8, "AKDT", -8, "AKST", -9,
                "HDT", -9, "HST", -10, "AST", -4, "ADT", -3, "ChST", 10, "SST", -11 };
        for (int i = 0; i + 1 < z.length; i += 2)
            ZONES.put((String) z[i], (Integer) z[i + 1]);
    }

    /**
     * The service's times: "2026-09-18 14:00:18 PM MDT". The hour is already on a
     * 24-hour clock and the AM/PM beside it is decoration ("20:13:14 PM PDT" is 8 pm,
     * checked against the fill time the Monitor recorded); the zone is an abbreviation.
     * 0 for "Incomplete" or anything else unreadable.
     */
    static long localTime(String s) {
        final Matcher m = LOCAL.matcher(s == null ? "" : s.trim());
        if (!m.matches())
            return 0;
        final Integer offset = ZONES.get(m.group(7));
        if (offset == null)
            return 0;
        final Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)) - 1,
                Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)),
                Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6)));
        return c.getTimeInMillis() - offset * 3_600_000L;
    }

    private static final Pattern TYPE_DATE = Pattern.compile("^(.*?)\\s+\\d{4}-\\d{2}-\\d{2}");

    /** "Fire 2026-09-17 15:00:00 PM MDT" to "Fire": the type with its date taken off. */
    private static String kindFromType(String type) {
        final Matcher m = TYPE_DATE.matcher(type == null ? "" : type);
        return clean(m.find() ? m.group(1) : type);
    }

    private static double number(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    // ---- filters -----------------------------------------------------------------

    /** Within a distance of a point, nearest first. */
    public static List<Request> near(List<Request> all, double lat, double lon,
            double meters) {
        final List<Request> out = new ArrayList<>();
        for (Request r : all)
            if (distanceMeters(lat, lon, r.lat, r.lon) <= meters)
                out.add(r);
        sortByDistance(out, lat, lon);
        return out;
    }

    /** Inside a box, nearest the box's center first. */
    public static List<Request> inBox(List<Request> all, double north, double west,
            double south, double east) {
        final List<Request> out = new ArrayList<>();
        for (Request r : all)
            if (r.lat <= north && r.lat >= south && r.lon >= west && r.lon <= east)
                out.add(r);
        sortByDistance(out, (north + south) / 2, (east + west) / 2);
        return out;
    }

    /** In a state; "" is at sea. */
    public static List<Request> inState(List<Request> all, String state) {
        final List<Request> out = new ArrayList<>();
        for (Request r : all)
            if (r.state.equalsIgnoreCase(state))
                out.add(r);
        sortNewest(out);
        return out;
    }

    public static List<Request> inRegion(List<Request> all, String region) {
        final List<Request> out = new ArrayList<>();
        for (Request r : all)
            if (r.region.equalsIgnoreCase(region))
                out.add(r);
        sortNewest(out);
        return out;
    }

    /** Newest request first: the one most likely still being worked. */
    public static void sortNewest(List<Request> list) {
        Collections.sort(list, new Comparator<Request>() {
            @Override
            public int compare(Request a, Request b) {
                return Long.compare(b.requestedAt, a.requestedAt);
            }
        });
    }

    public static void sortByDistance(List<Request> list, final double lat, final double lon) {
        Collections.sort(list, new Comparator<Request>() {
            @Override
            public int compare(Request a, Request b) {
                return Double.compare(distanceMeters(lat, lon, a.lat, a.lon),
                        distanceMeters(lat, lon, b.lat, b.lon));
            }
        });
    }

    /** How many requests each state has, states in name order, at sea last. */
    public static Map<String, Integer> countByState(List<Request> all) {
        final Map<String, Integer> out = new java.util.TreeMap<>(new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                if (a.isEmpty() != b.isEmpty())
                    return a.isEmpty() ? 1 : -1;
                return stateName(a).compareToIgnoreCase(stateName(b));
            }
        });
        for (Request r : all)
            out.put(r.state, out.containsKey(r.state) ? out.get(r.state) + 1 : 1);
        return out;
    }

    /** How many requests each region has, in the order NWS lists them. */
    public static Map<String, Integer> countByRegion(List<Request> all) {
        final Map<String, Integer> out = new java.util.LinkedHashMap<>();
        for (String code : REGION_ORDER)
            out.put(code, 0);
        for (Request r : all)
            if (out.containsKey(r.region))
                out.put(r.region, out.get(r.region) + 1);
        final java.util.Iterator<Map.Entry<String, Integer>> it = out.entrySet().iterator();
        while (it.hasNext())
            if (it.next().getValue() == 0)
                it.remove();
        return out;
    }

    /** Great-circle distance, meters. */
    public static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        final double r = 6_371_008.8;
        final double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        final double dp = p2 - p1, dl = Math.toRadians(lon2 - lon1);
        final double a = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * r * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    // ---- the issued forecast -------------------------------------------------------

    /** The office's FWS products, newest first, about a week of them. */
    public static String fwsListUrl(String office) {
        return "https://" + FWS_HOST + "/products/types/FWS/locations/"
                + office.replaceAll("[^A-Za-z0-9]", "");
    }

    /**
     * The product URLs in an FWS list whose issuance is within a quarter hour of the
     * fill time, nearest first. Usually one; the title check settles a tie.
     */
    public static List<String> candidates(String listJson, long filledAt) throws Exception {
        final JSONObject root = new JSONObject(listJson);
        final JSONArray g = root.optJSONArray("@graph");
        final List<String> ids = new ArrayList<>();
        final Map<String, Long> gap = new HashMap<>();
        for (int i = 0; g != null && i < g.length(); i++) {
            final JSONObject p = g.optJSONObject(i);
            if (p == null)
                continue;
            final long t = IsoTime.parse(p.optString("issuanceTime", ""));
            final String url = p.optString("@id", "");
            if (t <= 0 || url.isEmpty() || !url.startsWith("https://" + FWS_HOST + "/"))
                continue;
            final long d = Math.abs(t - filledAt);
            if (d <= MATCH_WINDOW_MS) {
                ids.add(url);
                gap.put(url, d);
            }
        }
        Collections.sort(ids, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return Long.compare(gap.get(a), gap.get(b));
            }
        });
        return ids;
    }

    /** The oldest issuance in an FWS list, so a miss can say it is older than that. */
    public static long oldestListed(String listJson) {
        try {
            final JSONArray g = new JSONObject(listJson).optJSONArray("@graph");
            long oldest = 0;
            for (int i = 0; g != null && i < g.length(); i++) {
                final JSONObject p = g.optJSONObject(i);
                final long t = p == null ? 0 : IsoTime.parse(p.optString("issuanceTime", ""));
                if (t > 0 && (oldest == 0 || t < oldest))
                    oldest = t;
            }
            return oldest;
        } catch (Exception e) {
            return 0;
        }
    }

    /** A product's text from its {@code /products/{id}} answer, or "". */
    public static String productText(String productJson) {
        try {
            return new JSONObject(productJson).optString("productText", "");
        } catch (Exception e) {
            return "";
        }
    }

    private static final Pattern TITLE = Pattern.compile("Spot Forecast for ([^\\n]*)");

    /**
     * True when a product is the forecast for this project: its first line names it.
     * Compared on letters and digits only, because the product text is uppercase in
     * places and punctuation is rewritten on the way into it.
     */
    public static boolean isFor(String productText, String project) {
        final Matcher m = TITLE.matcher(productText == null ? "" : productText);
        if (!m.find())
            return false;
        final String title = squash(m.group(1));
        final String want = squash(project);
        if (want.isEmpty())
            return false;
        return title.contains(want.substring(0, Math.min(want.length(), 20)));
    }

    // ---- names -----------------------------------------------------------------------

    static final String[] REGION_ORDER = { "WR", "SR", "CR", "ER", "AR", "PR", "NC" };

    /** "Western", "Southern"... the way NWS names its regions. */
    public static String regionName(String code) {
        switch (code == null ? "" : code) {
            case "WR": return "Western";
            case "SR": return "Southern";
            case "CR": return "Central";
            case "ER": return "Eastern";
            case "AR": return "Alaska";
            case "PR": return "Pacific";
            case "NC": return "National centers";
            default: return code == null ? "" : code;
        }
    }

    private static final Map<String, String> STATES = new HashMap<>();

    static {
        final String[] pairs = {
                "AL", "Alabama", "AK", "Alaska", "AZ", "Arizona", "AR", "Arkansas",
                "CA", "California", "CO", "Colorado", "CT", "Connecticut", "DE", "Delaware",
                "DC", "District of Columbia", "FL", "Florida", "GA", "Georgia", "HI", "Hawaii",
                "ID", "Idaho", "IL", "Illinois", "IN", "Indiana", "IA", "Iowa", "KS", "Kansas",
                "KY", "Kentucky", "LA", "Louisiana", "ME", "Maine", "MD", "Maryland",
                "MA", "Massachusetts", "MI", "Michigan", "MN", "Minnesota", "MS", "Mississippi",
                "MO", "Missouri", "MT", "Montana", "NE", "Nebraska", "NV", "Nevada",
                "NH", "New Hampshire", "NJ", "New Jersey", "NM", "New Mexico", "NY", "New York",
                "NC", "North Carolina", "ND", "North Dakota", "OH", "Ohio", "OK", "Oklahoma",
                "OR", "Oregon", "PA", "Pennsylvania", "RI", "Rhode Island", "SC", "South Carolina",
                "SD", "South Dakota", "TN", "Tennessee", "TX", "Texas", "UT", "Utah",
                "VT", "Vermont", "VA", "Virginia", "WA", "Washington", "WV", "West Virginia",
                "WI", "Wisconsin", "WY", "Wyoming", "PR", "Puerto Rico",
        };
        for (int i = 0; i + 1 < pairs.length; i += 2)
            STATES.put(pairs[i], pairs[i + 1]);
    }

    /** "California" for "CA"; "At sea" for no state. */
    public static String stateName(String code) {
        if (code == null || code.isEmpty())
            return "At sea";
        final String n = STATES.get(code.toUpperCase(Locale.US));
        return n != null ? n : code;
    }

    private static String squash(String s) {
        return s == null ? "" : s.toLowerCase(Locale.US).replaceAll("[^a-z0-9]", "");
    }

    /** Requesters type tabs and runs of spaces; the list shows one line. */
    private static String clean(String s) {
        return s == null || "null".equals(s) ? "" : s.replaceAll("\\s+", " ").trim();
    }
}

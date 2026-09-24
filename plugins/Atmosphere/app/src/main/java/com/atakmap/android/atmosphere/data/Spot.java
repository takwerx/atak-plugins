package com.atakmap.android.atmosphere.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NWS spot forecasts: every open spot request in the country, and the forecast NWS
 * issued for it. Pure request building and response reading; the page fetches.
 *
 * <h3>Where it lives, measured 2026-09-24</h3>
 *
 * {@code weather.gov/spot} is 404. The program is the Spot Forecast Monitor at
 * {@code spot.weather.gov}, a web app over {@code /cms/api/1.0}. Exactly one URL of
 * that API answers without the app's own key: {@link #LIST_URL}, every open request
 * (435 that night, 717 KB, 79 KB gzipped). Any added parameter is refused, so all
 * filtering happens here. The key the web app carries is the web app's, not ours,
 * and nothing here uses it.
 *
 * <p>The issued forecast is the public FWS product on api.weather.gov: the office's
 * FWS list, then the product whose issuance is within a quarter hour of the
 * request's {@code request_filled_at} and whose first line is "Spot Forecast for
 * &lt;project&gt;". That matched 77 of 77 forecasts inside the week api.weather.gov keeps.
 */
public final class Spot {

    public static final String HOST = "spot.weather.gov";
    public static final String LIST_URL = "https://" + HOST + "/cms/api/1.0/requests?isArchived=false";
    /** Where a request is made: the program's own form, in the browser. */
    public static final String NEW_REQUEST_URL = "https://" + HOST + "/new-request";
    public static final String FWS_HOST = "api.weather.gov";
    /** How far a product's issuance may sit from the request's fill time. */
    static final long MATCH_WINDOW_MS = 15 * 60_000L;

    /** One forecast NWS issued against a request; a request can carry several updates. */
    public static final class Issued {
        public final long id;
        public final int update;
        /** When NWS filled it, UTC millis; 0 when not stated. */
        public final long filledAt;

        Issued(long id, int update, long filledAt) {
            this.id = id;
            this.update = update;
            this.filledAt = filledAt;
        }
    }

    /** One spot request, the fields a crew reads. */
    public static final class Request {
        public final String id;
        public final String project;
        public final String agency;
        /** "Wildfire", "Prescribed Fire", "HAZMAT Land", "SAR Land"... */
        public final String kind;
        /** The office's id ("EKA") and its name ("Eureka"). */
        public final String office, officeName;
        /** NWS region code: WR, SR, CR, ER, AR, PR, NC. */
        public final String region;
        /** Two-letter state, or "OC" for ocean. */
        public final String state;
        public final double lat, lon;
        public final int topFt, bottomFt;
        public final String aspect, fuel, sheltering, drainage, remarks;
        public final int acres;
        public final long submittedAt, deliverAt;
        public final String actionStatus;
        /** Oldest first. */
        public final List<Issued> issued;

        Request(JSONObject r) {
            id = r.optString("id", "");
            project = clean(r.optString("projectName", ""));
            agency = clean(r.optString("requesterAgency", ""));
            final JSONObject inc = r.optJSONObject("incident");
            kind = inc == null ? "" : clean(inc.optString("name", ""));
            final JSONObject off = r.optJSONObject("office");
            office = off == null ? "" : off.optString("nativeSiteId", "");
            officeName = off == null ? "" : clean(off.optString("name", ""));
            region = off == null ? "" : off.optString("region", "");
            state = r.optString("state", "");
            lat = r.optDouble("latitude", Double.NaN);
            lon = r.optDouble("longitude", Double.NaN);
            topFt = r.optInt("topElevation", 0);
            bottomFt = r.optInt("bottomElevation", 0);
            aspect = clean(r.optString("aspect", ""));
            fuel = clean(r.optString("fuelType", ""));
            sheltering = clean(r.optString("sheltering", ""));
            drainage = clean(r.optString("drainage", ""));
            remarks = clean(r.optString("remarks", ""));
            acres = r.optInt("size", 0);
            submittedAt = IsoTime.parse(r.optString("submittedAt", ""));
            deliverAt = IsoTime.parse(r.optString("deliverAt", ""));
            actionStatus = r.optString("actionStatus", "");
            final List<Issued> out = new ArrayList<>();
            final JSONArray fs = r.optJSONArray("spotForecasts");
            for (int i = 0; fs != null && i < fs.length(); i++) {
                final JSONObject f = fs.optJSONObject(i);
                if (f == null)
                    continue;
                out.add(new Issued(f.optLong("id", 0), f.optInt("update_number", 0),
                        IsoTime.parse(f.optString("request_filled_at", ""))));
            }
            Collections.sort(out, new Comparator<Issued>() {
                @Override
                public int compare(Issued a, Issued b) {
                    return Long.compare(a.filledAt, b.filledAt);
                }
            });
            issued = Collections.unmodifiableList(out);
        }

        /** The newest forecast issued, or null while NWS has not filled it. */
        public Issued latest() {
            return issued.isEmpty() ? null : issued.get(issued.size() - 1);
        }

        /**
         * Where the request stands, in words. Measured statuses: ST_COMPLETE with a
         * forecast (395 of 435), ST_STARTRQST and ST_CHANGERQST with or without one,
         * AS_NEWOBS and AS_FEEDBACK after one.
         */
        public String status() {
            if (issued.isEmpty())
                return "Waiting for the forecast";
            if ("ST_STARTRQST".equals(actionStatus) || "ST_CHANGERQST".equals(actionStatus))
                return "Update requested";
            return "Forecast issued";
        }

        public boolean hasPosition() {
            return !Double.isNaN(lat) && !Double.isNaN(lon)
                    && Math.abs(lat) <= 90 && Math.abs(lon) <= 180;
        }
    }

    private Spot() {
    }

    /** Every request in a {@link #LIST_URL} answer; one that will not read is skipped. */
    public static List<Request> parse(String body) throws Exception {
        final JSONArray a = new JSONArray(body);
        final List<Request> out = new ArrayList<>(a.length());
        for (int i = 0; i < a.length(); i++) {
            final JSONObject r = a.optJSONObject(i);
            if (r == null)
                continue;
            final Request q = new Request(r);
            if (!q.id.isEmpty() && q.hasPosition())
                out.add(q);
        }
        return out;
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
                return Long.compare(b.submittedAt, a.submittedAt);
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

    /** How many requests each state has, states in name order. */
    public static Map<String, Integer> countByState(List<Request> all) {
        final Map<String, Integer> out = new java.util.TreeMap<>(new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return stateName(a).compareToIgnoreCase(stateName(b));
            }
        });
        for (Request r : all)
            if (!r.state.isEmpty())
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
                "WI", "Wisconsin", "WY", "Wyoming", "PR", "Puerto Rico", "GU", "Guam",
                "VI", "Virgin Islands", "AS", "American Samoa", "MP", "Northern Mariana Islands",
                "OC", "At sea",
        };
        for (int i = 0; i + 1 < pairs.length; i += 2)
            STATES.put(pairs[i], pairs[i + 1]);
    }

    /** "California" for "CA"; the code itself when it is not one NWS uses here. */
    public static String stateName(String code) {
        final String n = STATES.get(code == null ? "" : code.toUpperCase(Locale.US));
        return n != null ? n : code == null ? "" : code;
    }

    private static String squash(String s) {
        return s == null ? "" : s.toLowerCase(Locale.US).replaceAll("[^a-z0-9]", "");
    }

    /** Requesters type tabs, runs of spaces and stray whitespace; the list shows one line. */
    private static String clean(String s) {
        return s == null || "null".equals(s) ? "" : s.replaceAll("\\s+", " ").trim();
    }
}

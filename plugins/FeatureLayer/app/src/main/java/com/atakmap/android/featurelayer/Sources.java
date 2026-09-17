package com.atakmap.android.featurelayer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The prefilled sources: NIFS (archive and live) and SARCOP training, plus their searches. */
public final class Sources {
    private Sources() {
    }

    public static final String NIFC_PORTAL = "https://nifc.maps.arcgis.com";
    public static final String NAPSG_PORTAL = "https://napsg.maps.arcgis.com";

    static final String NIFS_ARCHIVE = "https://services3.arcgis.com/T4QMspbfLg3qTGWY/arcgis/rest/services/Operational_Data_Archive_2025/FeatureServer";
    static final String NIFS_LIVE = "https://services3.arcgis.com/T4QMspbfLg3qTGWY/arcgis/rest/services/MobileView_NIFS_2026/FeatureServer";
    static final String WFIGS = "https://services3.arcgis.com/T4QMspbfLg3qTGWY/arcgis/rest/services/WFIGS_Incident_Locations_YearToDate/FeatureServer/0";
    static final String SARCOP_SANDBOX = "https://services.arcgis.com/0ZRg6WRC7mxSLyKX/arcgis/rest/services/Sandbox_v10_Mobile_Edit_View/FeatureServer";

    /** NIFS Event Polygon, Perimeter Line, Event Line, Event Point: polygons first so they draw under. */
    /** The archive carries the four Event layers; the live view all eight. */
    private static final int[] NIFS_ARCHIVE_LAYERS = { 6, 5, 4, 2 };
    static final int[] NIFS_LIVE_LAYERS = { 7, 6, 5, 4, 3, 2, 1, 0 };
    /** SARCOP: Incident Area, Branches, Divisions, Search Segments, Tracklog, Logistics, Worksites, Waypoints. */
    private static final int[] SARCOP_LAYERS = { 7, 6, 5, 4, 3, 2, 1, 0 };

    /** A fire from the public incident index. */
    public static class Fire {
        public String name, irwinId, state, county;
        public double acres, contained, lat, lon;

        public String label() {
            return name + " (" + (state == null ? "" : state.replace("US-", "") + ", ")
                    + (county == null ? "" : county + ", ")
                    + String.format(Locale.US, "%,.0f ac", acres)
                    + (contained > 0 ? String.format(Locale.US, ", %.0f%%", contained) : "") + ")";
        }
    }

    /** A SARCOP incident (event) from the Incident Area layer. */
    public static class SarcopEvent {
        public String eventName, label, type;
        public double lat, lon;

        public String label() {
            return eventName + (type == null || type.isEmpty() ? "" : " (" + type.replace('_', ' ') + ")");
        }
    }

    public static LayerSpec nifsArchiveDemo() {
        final LayerSpec s = new LayerSpec();
        s.id = "nifs-archive:Dragon Bravo";
        s.title = "Dragon Bravo";
        s.subtitle = "NIFS Archive 2025";
        s.portal = null;
        s.base = NIFS_ARCHIVE;
        s.layerIds = NIFS_ARCHIVE_LAYERS;
        s.where = "IncidentName='Dragon Bravo' AND GDB_TO_DATE IS NULL";
        s.geojson = true;
        s.profile = LayerSpec.Profile.NWCG;
        s.lat = 36.4133;
        s.lon = -112.0217;
        s.live = false;
        s.refreshMinutes = 0;
        return s;
    }

    public static LayerSpec nifsLive(Fire f) {
        final LayerSpec s = new LayerSpec();
        s.id = "nifs-live:" + (f.irwinId != null ? f.irwinId : f.name);
        s.title = f.name;
        s.subtitle = "NIFS Live";
        s.portal = NIFC_PORTAL;
        s.orgName = "NIFC";
        s.base = NIFS_LIVE;
        s.layerIds = NIFS_LIVE_LAYERS;
        s.where = (f.irwinId != null && !f.irwinId.isEmpty())
                ? "(IRWINID=" + Esri.sql(f.irwinId) + " OR UPPER(IncidentName)=UPPER(" + Esri.sql(f.name) + "))"
                : "UPPER(IncidentName)=UPPER(" + Esri.sql(f.name) + ")";
        s.geojson = false;
        s.profile = LayerSpec.Profile.NWCG;
        s.lat = f.lat;
        s.lon = f.lon;
        s.live = true;
        return s;
    }

    public static LayerSpec sarcopTraining(SarcopEvent e) {
        final LayerSpec s = new LayerSpec();
        s.id = "sarcop-training:" + e.eventName;
        s.title = e.eventName;
        s.subtitle = "SARCOP Training";
        s.portal = null; // the sandbox is public
        s.base = SARCOP_SANDBOX;
        s.layerIds = SARCOP_LAYERS;
        s.where = "event_name=" + Esri.sql(e.eventName);
        s.geojson = false;
        s.profile = LayerSpec.Profile.GENERIC;
        s.lat = e.lat;
        s.lon = e.lon;
        s.live = true;
        s.iconSet = "sarcop";
        return s;
    }

    // ---- DART: NIFC personnel and vehicle last known locations ----------------------

    /**
     * The two DART feeds, both shared to the whole NIFC org. `Dart_AVLs` (no `_view`) is
     * the Parent service, whose own item says it must not be shared beyond the DART
     * Group; these are the internal views NIFC shares to the org and to EGP's WildFireSA
     * Advanced. Read-only, and never republished as CoT: they are internal-to-NIFC
     * positions of people, and a CoT reaches every EUD on the server.
     */
    static final String DART_PERSONNEL = "https://services3.arcgis.com/T4QMspbfLg3qTGWY/arcgis/rest/services/DART_Personnel_EGP/FeatureServer";
    static final String DART_VEHICLES = "https://services3.arcgis.com/T4QMspbfLg3qTGWY/arcgis/rest/services/Dart_AVLs_view/FeatureServer";

    /** Default scope: what is within this of the operator, until they pick an area. */
    static final double DART_DEFAULT_RADIUS_M = 40000;

    private static LayerSpec dart(String id, String title, String layerTitle, String base, String labelField,
            String setField, String timeField) {
        final LayerSpec s = new LayerSpec();
        s.id = id;
        s.title = title;
        s.subtitle = "DART";
        s.portal = NIFC_PORTAL; // the same portal as NIFS live, so one sign-in covers both
        s.orgName = "NIFC";
        s.base = base;
        s.layerIds = new int[] { 0 };
        s.where = "1=1";
        s.geojson = false;
        s.profile = LayerSpec.Profile.GENERIC;
        s.labelField = labelField;
        s.layerTitle = layerTitle;
        s.setField = setField;
        s.timeField = timeField;
        s.live = true;
        // A last known location moves about every 60 s, so a slower refresh draws the
        // past. The change check is a few hundred bytes and the fetch only follows a
        // change, so this is cheap even on a thin link.
        s.refreshMinutes = 1;
        s.sinceHours = 24; // both services are 24-hour views; asking for more returns nothing
        s.iconSet = "dart"; // EGP's glyphs; see DartStyles
        s.scopeKind = "me";
        s.scopeRadiusM = DART_DEFAULT_RADIUS_M;
        // Nationally 387 people and 3,981 vehicles, measured 2026-09-17. A scope keeps a
        // fetch in the tens, and the cap is what stops a wide area drawing the country.
        s.maxFeatures = 300;
        return s;
    }

    /**
     * People sharing a last known location: Field Maps "My Tracks", WFTAK and Garmin
     * inReach, deduplicated by NIFC into one 24-hour view. Labeled by callsign, which
     * only about a quarter of them have submitted; typed by resource_type, which is the
     * field that carries IHC / ENG / OPS. Not `category`, which holds a cohort code.
     */
    public static LayerSpec dartPersonnel() {
        return dart("dart-personnel", "Personnel", "Personnel", DART_PERSONNEL, "call_sign",
                "resource_type", "location_timestamp");
    }

    /** USFS and DOI fire vehicles, every row inside 24 hours and most inside the hour. */
    public static LayerSpec dartVehicles() {
        return dart("dart-vehicles", "Vehicles", "Vehicle", DART_VEHICLES, "ResourceName", "ResourceType",
                "DateTime");
    }

    /** A whole feature service from a user's own org: every layer, generic symbology, capped. */
    /** CA Air Intel: statewide fire perimeters from FIRIS, CAL FIRE intel flights, USFS, NIFC and WFIGS, public. */
    static final String CA_AIR_INTEL = "https://services1.arcgis.com/jUJYIo9tSA7EHvfZ/arcgis/rest/services/CA_Perimeters_NIFC_FIRIS_public_view/FeatureServer";

    public static LayerSpec caAirIntel() {
        final LayerSpec s = new LayerSpec();
        s.id = "ca-air-intel";
        s.title = "CA Air Intel perimeters";
        s.subtitle = "FIRIS, CAL FIRE, USFS, NIFC, WFIGS";
        s.portal = null;
        s.base = CA_AIR_INTEL;
        s.layerIds = new int[] { 0 };
        s.where = "displayStatus = 'Active'"; // an inactive perimeter is yesterday's news
        s.labelField = "incident_name";        // the service labels by mission number
        s.geojson = false;
        s.profile = LayerSpec.Profile.GENERIC;
        s.lat = 37.2;
        s.lon = -119.5;
        s.live = true;
        s.refreshMinutes = 1; // a change check is a few hundred bytes; the fetch only follows a change
        s.maxFeatures = 5000;
        s.timeField = "poly_DateCurrent";
        s.sinceHours = 72;
        s.setField = "source";
        // One perimeter per fire per source: the latest flight, not every flight in the window.
        s.latestBy = new String[] { "source", "incident_name|mission" };
        // Every source the service carries, what it is, all on: read from the data 2026-09-08.
        s.setNotes.put("CAL FIRE INTEL FLIGHT DATA", "CAL FIRE intel aircraft heat perimeters, the most current");
        s.setNotes.put("FIRIS", "Cal OES FIRIS aircraft real-time heat perimeters");
        s.setNotes.put("USFS", "Forest Service infrared heat perimeters");
        s.setNotes.put("NIFC", "NIFS daily fire perimeter drawn by the incident GISS");
        s.setNotes.put("WFIGS", "National interagency daily fire perimeter");
        s.setNotes.put("EGP", "NIFC Enterprise Geospatial Portal perimeters");
        for (String n : s.setNotes.keySet())
            s.setKind.put(n, "polygon");
        return s;
    }

    public static LayerSpec customService(String portal, String orgName, Esri.Item item, int[] layerIds) {
        final LayerSpec s = new LayerSpec();
        s.id = "custom:" + item.id;
        s.title = item.title;
        s.subtitle = orgName; // the org, by name; an operator never needs its address
        s.orgName = orgName;
        s.portal = portal;
        s.base = item.url.replaceAll("/+$", "");
        s.layerIds = layerIds.length > 12 ? java.util.Arrays.copyOf(layerIds, 12) : layerIds;
        s.where = "1=1";
        s.geojson = false;
        s.profile = LayerSpec.Profile.GENERIC;
        s.live = true;
        s.maxFeatures = 5000;
        return s;
    }

    /** A contains-match on a name field, or everything when the text is empty. */
    private static String nameWhere(String field, String text) {
        final String t = text == null ? "" : text.trim();
        if (t.isEmpty())
            return "1=1";
        return "UPPER(" + field + ") LIKE UPPER(" + Esri.sql("%" + t + "%") + ")";
    }

    /** This year's fires whose name contains the text, most recently updated first. Worker thread. */
    public static List<Fire> searchFires(String text) throws Exception {
        final String where = nameWhere("IncidentName", text);
        final String url = WFIGS + "/query?where=" + Esri.enc(where)
                + "&outFields=IncidentName,IrwinID,POOState,POOCounty,IncidentSize,PercentContained,ModifiedOnDateTime_dt"
                + "&orderByFields=" + Esri.enc("ModifiedOnDateTime_dt DESC")
                + "&returnGeometry=true&outSR=4326&resultRecordCount=25&f=json";
        final JSONObject page = new JSONObject(Esri.get(url));
        if (page.has("error"))
            throw new IllegalStateException(page.getJSONObject("error").optString("message"));
        final List<Fire> out = new ArrayList<>();
        final JSONArray feats = page.optJSONArray("features");
        for (int i = 0; feats != null && i < feats.length(); i++) {
            final JSONObject a = feats.getJSONObject(i).optJSONObject("attributes");
            final JSONObject g = feats.getJSONObject(i).optJSONObject("geometry");
            if (a == null || g == null)
                continue;
            final Fire f = new Fire();
            f.name = a.optString("IncidentName", "?");
            f.irwinId = a.isNull("IrwinID") ? null : a.optString("IrwinID", null);
            f.state = a.isNull("POOState") ? null : a.optString("POOState", null);
            f.county = a.isNull("POOCounty") ? null : a.optString("POOCounty", null);
            f.acres = a.optDouble("IncidentSize", 0);
            f.contained = a.optDouble("PercentContained", 0);
            f.lat = g.optDouble("y", 0);
            f.lon = g.optDouble("x", 0);
            out.add(f);
        }
        return out;
    }

    /** SARCOP sandbox incidents whose event name contains the text, newest first. Worker thread. */
    public static List<SarcopEvent> searchSarcop(String text) throws Exception {
        final String where = nameWhere("event_name", text);
        final String url = SARCOP_SANDBOX + "/7/query?where=" + Esri.enc(where)
                + "&outFields=event_name,label,incident_type,EditDate"
                + "&orderByFields=" + Esri.enc("EditDate DESC")
                + "&returnGeometry=false&returnCentroid=true&outSR=4326&resultRecordCount=80&f=json";
        final JSONObject page = new JSONObject(Esri.get(url));
        if (page.has("error"))
            throw new IllegalStateException(page.getJSONObject("error").optString("message"));
        final Map<String, SarcopEvent> out = new LinkedHashMap<>();
        final JSONArray feats = page.optJSONArray("features");
        for (int i = 0; feats != null && i < feats.length(); i++) {
            final JSONObject a = feats.getJSONObject(i).optJSONObject("attributes");
            final JSONObject c = feats.getJSONObject(i).optJSONObject("centroid");
            if (a == null || a.isNull("event_name"))
                continue;
            final String name = a.optString("event_name");
            if (out.containsKey(name))
                continue;
            final SarcopEvent e = new SarcopEvent();
            e.eventName = name;
            e.label = a.isNull("label") ? null : a.optString("label", null);
            e.type = a.isNull("incident_type") ? null : a.optString("incident_type", null);
            e.lat = c == null ? Double.NaN : c.optDouble("y", Double.NaN);
            e.lon = c == null ? Double.NaN : c.optDouble("x", Double.NaN);
            out.put(name, e);
        }
        return new ArrayList<>(out.values());
    }
}

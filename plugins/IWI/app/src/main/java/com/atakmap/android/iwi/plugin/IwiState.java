package com.atakmap.android.iwi.plugin;

import android.content.SharedPreferences;

import com.atakmap.coremap.maps.coords.GeoPoint;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * The one IWI this phone is running, and the Medical Incident Report typed for it.
 *
 * <p>Everything is saved the moment it changes, in ATAK's own preferences, so a
 * half-typed report survives a pane close, a base map switch, a plugin reload and a
 * restart. Losing a half-typed report is the worst bug this plugin can have (PLAN-IWI,
 * "Background and interruptions").
 */
public final class IwiState implements RadioScript.Source {

    public static final String SOURCE_SELF = "self";
    public static final String SOURCE_MAP = "map";

    /** The five the form names, in its order. */
    public static final String[] EVAC = {
            "Air Ambulance", "Short-Haul", "Hoist", "Ground Ambulance", "Other"
    };
    public static final String ROLE_PARAMEDIC = "Paramedic";
    public static final String ROLE_EMT = "EMT";

    // Form keys, in MIR order.
    public static final String CONTACT_TO = "contact_to";
    public static final String CONTACT_FROM = "contact_from";
    public static final String RED = "red";
    public static final String YELLOW = "yellow";
    public static final String GREEN = "green";
    public static final String BLACK = "black";
    public static final String NATURE = "nature";
    public static final String EVAC_REQUEST = "evac";
    public static final String EVAC_OTHER = "evac_other";
    public static final String LOCATION = "location_description";
    public static final String INCIDENT = "incident_name";
    public static final String IC = "onscene_ic";
    public static final String CARE_ROLE = "care_role";
    public static final String CARE_NAME = "care_name";
    public static final String WEIGHT = "patient_weight";
    public static final String RADIO_CONTACT = "radio_contact";
    public static final String ASSESSMENT = "assessment";
    public static final String TREATMENT = "treatment";
    public static final String EVAC_LOCATION = "evac_location";
    public static final String ETA = "patient_eta";
    public static final String HELISPOT = "helispot";
    public static final String RESOURCES = "resources";
    public static final String CONTINGENCY = "contingency";
    public static final String ADDITIONAL = "additional_info";

    /** The three rows of item 6, and the five columns of each. */
    public static final String[] COMMS_ROWS = {"cmd", "ag", "tac"};
    public static final String[] COMMS_ROW_NAMES = {"Command", "Air-to-ground", "Tactical"};
    public static final String[] COMMS_COLS = {"channel", "rx", "rx_tone", "tx", "tx_tone"};

    /** Items 1 and 6 belong to the incident, not the patient, and outlive a Clear. */
    private static final List<String> KEEP_ON_CLEAR = Arrays.asList(CONTACT_TO, CONTACT_FROM);

    private static final String K_LIVE = "iwi.live";
    private static final String K_UID = "iwi.uid";
    private static final String K_LAT = "iwi.lat";
    private static final String K_LON = "iwi.lon";
    private static final String K_SOURCE = "iwi.source";
    private static final String K_DECLARED = "iwi.declaredMs";
    private static final String FIELD = "iwi.f.";

    private final SharedPreferences prefs;

    public IwiState(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    public static String commsKey(String row, String col) {
        return row + "_" + col;
    }

    // ---- the IWI itself ----

    public boolean isLive() {
        return prefs.getBoolean(K_LIVE, false) && point() != null;
    }

    public String uid() {
        return prefs.getString(K_UID, "");
    }

    public GeoPoint point() {
        try {
            final double lat = Double.parseDouble(prefs.getString(K_LAT, ""));
            final double lon = Double.parseDouble(prefs.getString(K_LON, ""));
            if (!TraumaCenter.valid(lat, lon))
                return null;
            return new GeoPoint(lat, lon);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public String source() {
        return prefs.getString(K_SOURCE, SOURCE_SELF);
    }

    public long declaredMs() {
        return prefs.getLong(K_DECLARED, 0L);
    }

    /** A new IWI at this point. A new UID, so it is a new marker. */
    public void declare(GeoPoint p, String source) {
        prefs.edit()
                .putBoolean(K_LIVE, true)
                .putString(K_UID, "IWI-" + UUID.randomUUID())
                .putString(K_LAT, Double.toString(p.getLatitude()))
                .putString(K_LON, Double.toString(p.getLongitude()))
                .putString(K_SOURCE, source)
                .putLong(K_DECLARED, System.currentTimeMillis())
                .apply();
    }

    /** The same IWI, somewhere else. The declared time does not change. */
    public void move(GeoPoint p, String source) {
        prefs.edit()
                .putString(K_LAT, Double.toString(p.getLatitude()))
                .putString(K_LON, Double.toString(p.getLongitude()))
                .putString(K_SOURCE, source)
                .apply();
    }

    /** Forget the IWI and the patient's half of the report. */
    public void clear() {
        final SharedPreferences.Editor e = prefs.edit()
                .remove(K_LIVE).remove(K_UID).remove(K_LAT).remove(K_LON)
                .remove(K_SOURCE).remove(K_DECLARED);
        for (String key : prefs.getAll().keySet()) {
            if (!key.startsWith(FIELD))
                continue;
            final String field = key.substring(FIELD.length());
            if (KEEP_ON_CLEAR.contains(field) || isComms(field))
                continue;
            e.remove(key);
        }
        e.apply();
    }

    private static boolean isComms(String field) {
        for (String row : COMMS_ROWS)
            if (field.startsWith(row + "_"))
                return true;
        return false;
    }

    // ---- the report ----

    @Override
    public String text(String key) {
        final String v = prefs.getString(FIELD + key, "");
        return v == null ? "" : v.trim();
    }

    /** The field as typed, untrimmed, for putting back in its box. */
    public String raw(String key) {
        final String v = prefs.getString(FIELD + key, "");
        return v == null ? "" : v;
    }

    public void set(String key, String value) {
        prefs.edit().putString(FIELD + key, value == null ? "" : value).apply();
    }

    @Override
    public int count(String key) {
        try {
            return Math.max(0, prefs.getInt(FIELD + key, 0));
        } catch (ClassCastException e) {
            return 0;
        }
    }

    public void setCount(String key, int n) {
        prefs.edit().putInt(FIELD + key, Math.max(0, Math.min(99, n))).apply();
    }

    public int patients() {
        return count(RED) + count(YELLOW) + count(GREEN) + count(BLACK);
    }
}

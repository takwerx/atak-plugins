package com.atakmap.android.iwi.plugin;

import android.content.SharedPreferences;

import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
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
    /** The patient cards, as one JSON array. Under FIELD, so a Clear takes them. */
    private static final String K_PATIENTS = FIELD + "patients";

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

    /** This phone's callsign, from ATAK: the reporter, and the IC unless one is named. */
    @Override
    public String callsign() {
        try {
            final com.atakmap.android.maps.MapView mv =
                    com.atakmap.android.maps.MapView.getMapView();
            final String c = mv == null ? null : mv.getDeviceCallsign();
            return c == null ? "" : c.trim();
        } catch (RuntimeException e) {
            return "";
        }
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

    /** How many patient cards are this color. The cards are the only count there is. */
    @Override
    public int count(String color) {
        int n = 0;
        for (Patient p : patients())
            if (p.color.equals(color))
                n++;
        return n;
    }

    public int patientCount() {
        return patients().size();
    }

    // ---- patients ----

    /** Every card, in the order they were added. */
    @Override
    public List<Patient> patients() {
        final List<Patient> out = new ArrayList<>();
        final String json = prefs.getString(K_PATIENTS, "");
        if (json == null || json.isEmpty())
            return out;
        try {
            final JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                final JSONObject o = a.optJSONObject(i);
                if (o == null)
                    continue;
                final Patient p = new Patient();
                p.n = o.optInt("n", i + 1);
                p.color = o.optString("color", RED);
                if (Patient.rank(p.color) == 3 && !BLACK.equals(p.color))
                    p.color = RED;
                final JSONObject picks = o.optJSONObject("v");
                if (picks != null) {
                    final java.util.Iterator<String> keys = picks.keys();
                    while (keys.hasNext()) {
                        final String k = keys.next();
                        p.v.put(k, picks.optString(k, ""));
                    }
                }
                p.updatedMs = o.optLong("updated", 0L);
                p.tag = o.optString("tag", "");
                p.tagN = o.optInt("tagN", 0);
                out.add(p);
            }
        } catch (JSONException e) {
            Log.w("IWI-State", "the patient cards are unreadable", e);
        }
        // Cards from before patients had names get one: their color, numbered in the
        // order they were added.
        for (Patient p : out) {
            if (p.tag.isEmpty() || p.tagN <= 0) {
                p.tag = p.color;
                p.tagN = nextNumber(out, p.color);
            }
        }
        return out;
    }

    /** The next number in a color: one past the highest that color has used. */
    private static int nextNumber(List<Patient> list, String color) {
        int max = 0;
        for (Patient p : list)
            if (color.equals(p.tag) && p.tagN > 0)
                max = Math.max(max, p.tagN);
        return max + 1;
    }

    /** Most severe first, then in the order they were added. */
    public List<Patient> patientsBySeverity() {
        final List<Patient> list = patients();
        Collections.sort(list, new Comparator<Patient>() {
            @Override
            public int compare(Patient a, Patient b) {
                final int r = Patient.rank(a.color) - Patient.rank(b.color);
                return r != 0 ? r : a.n - b.n;
            }
        });
        return list;
    }

    private void save(List<Patient> list) {
        final JSONArray a = new JSONArray();
        try {
            for (Patient p : list) {
                final JSONObject o = new JSONObject();
                o.put("n", p.n);
                o.put("color", p.color);
                final JSONObject picks = new JSONObject();
                for (java.util.Map.Entry<String, String> e : p.v.entrySet())
                    picks.put(e.getKey(), e.getValue());
                o.put("v", picks);
                o.put("updated", p.updatedMs);
                o.put("tag", p.tag);
                o.put("tagN", p.tagN);
                a.put(o);
            }
        } catch (JSONException e) {
            Log.w("IWI-State", "could not save the patient cards", e);
            return;
        }
        prefs.edit().putString(K_PATIENTS, a.toString()).apply();
    }

    /** A new card of this color, numbered after the highest so far. */
    public Patient addPatient(String color) {
        final List<Patient> list = patients();
        int max = 0;
        for (Patient p : list)
            max = Math.max(max, p.n);
        final Patient p = new Patient();
        p.n = max + 1;
        p.color = color;
        p.tag = color;
        p.tagN = nextNumber(list, color);
        list.add(p);
        save(list);
        return p;
    }

    /**
     * Take away the newest card of this color that nobody has written on.
     *
     * @return the number taken away, or -1 when every card of that color has notes
     */
    public int removeNewestBlank(String color) {
        final List<Patient> list = patients();
        for (int i = list.size() - 1; i >= 0; i--) {
            final Patient p = list.get(i);
            if (p.color.equals(color) && !p.hasNotes()) {
                list.remove(i);
                save(list);
                return p.n;
            }
        }
        return -1;
    }

    public void removePatient(int n) {
        final List<Patient> list = patients();
        for (int i = 0; i < list.size(); i++)
            if (list.get(i).n == n) {
                list.remove(i);
                break;
            }
        save(list);
    }

    public Patient patient(int n) {
        for (Patient p : patients())
            if (p.n == n)
                return p;
        return null;
    }

    /** One field of one card: color, assessment, treatment or weight. */
    public void updatePatient(int n, String field, String value) {
        final List<Patient> list = patients();
        for (Patient p : list) {
            if (p.n != n)
                continue;
            final String v = value == null ? "" : value;
            if ("color".equals(field))
                p.color = v;
            else
                p.v.put(field, v);
            p.updatedMs = System.currentTimeMillis();
        }
        save(list);
    }
}

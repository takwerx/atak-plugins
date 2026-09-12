package com.atakmap.android.featurelayer;

import com.atakmap.map.layer.feature.style.BasicFillStyle;
import com.atakmap.map.layer.feature.style.Style;

import org.json.JSONObject;

import java.util.Locale;
import java.util.Map;

/**
 * SARCOP symbology, as NAPSG draws it. The Sandbox service's own renderers are pastel
 * placeholders; the real look lives in NAPSG's web maps (Sandbox v10 Field Maps Online,
 * Internal, Offline; the VBSAR Hurricane Training copy) and was read from their layer
 * definitions on 2026-09-08: tracklog color and dash per mission type, outline color per
 * division and segment status, a dashed blue incident area with no fill, and the US&R
 * symbol library (github.com/NAPSG/USR-Symbology) for points, bundled under assets/sarcop.
 */
public final class SarcopStyles {
    private SarcopStyles() {
    }

    // Tracklog, by mission_type.
    private static final int RECON = 0xFFFA0000, WATER = 0xFF7A8EF5, HASTY = 0xFFDE6B14, PRIMARY = 0xFFFFFB05,
            SECONDARY_HIGH = 0xFF00AD51, SECONDARY_LOW = 0xFF0073BF, TARGETED = 0xFFBD5396, CANINE = 0xFFC76B10,
            DAMAGE_OBS = 0xFF4B0073, OTHER = 0xFFAAAAAA;
    // Divisions and search segments, by status.
    private static final int DIV_COMPLETE = 0xFF3E750D, DIV_COMPLETE_FILL = 0x336ACC14, DIV_ACTIVE = 0xFFFFD700,
            DIV_NOT_ACTIVE = 0xFF8000FF, EXCLUDED = 0xFF999999, EXCLUDED_FILL = 0x80999999,
            SEG_NOT_ACTIVE = 0xFF6300C7, SEG_ACTIVE = 0xFFE6C300, SEG_COMPLETE = 0xFF00AF52, SEG_COMPLETE_FILL = 0x26A3FF73,
            BRANCH = 0xFF000000, INCIDENT_AREA = 0xFF004DA8;
    private static final short DASH_9_6 = (short) 0xFFC0; // 10 on, 6 off

    /** Which SARCOP layer a service layer is, from its name; null when it is none of them. */
    private static String kind(String layerName) {
        final String n = layerName == null ? "" : layerName.toLowerCase(Locale.US);
        if (n.contains("waypoint"))
            return "waypoints";
        if (n.contains("worksite"))
            return "worksites";
        if (n.contains("logistic"))
            return "logistics";
        if (n.contains("tracklog") || n.contains("track log"))
            return "tracklog";
        if (n.contains("segment"))
            return "segments";
        if (n.contains("division"))
            return "divisions";
        if (n.contains("branch"))
            return "branches";
        if (n.contains("incident area"))
            return "incident";
        return null;
    }

    private static String str(JSONObject props, String key) {
        return props.isNull(key) ? null : props.optString(key, null);
    }

    /**
     * The style for one SARCOP feature, or null to keep the service's own.
     *
     * @param sarcop bundled US&R symbols by "waypoint:<code>", "worksite:<code>", "logistics:<code>"
     * @param nwcg   NWCG symbols by category, for the logistics categories US&R has none for
     */
    public static Style style(String layerName, JSONObject props, boolean point, boolean line,
            Map<String, String> sarcop, Map<String, String> nwcg) {
        final String k = kind(layerName);
        if (k == null)
            return null;
        switch (k) {
            case "waypoints": {
                final String v = str(props, "waypoint");
                final String uri = v == null ? null : sarcop.get("waypoint:" + v);
                if (uri == null)
                    return null;
                // NAPSG draws structure damage observations at 7.5 pt against 18 pt for the
                // rest: small colored dots, not full symbols.
                final boolean damage = v.equals("unaffected") || v.equals("affected") || v.equals("minor")
                        || v.equals("major") || v.equals("destroyed") || v.equals("unknown");
                return NwcgStyles.point(uri, damage ? 0.55f : 1f);
            }
            case "worksites": {
                final String v = str(props, "followup_status");
                final String uri = v == null ? null : sarcop.get("worksite:" + v);
                return uri == null ? null : NwcgStyles.point(uri);
            }
            case "logistics": {
                final String v = str(props, "Feature_Category");
                String uri = v == null ? null : sarcop.get("logistics:" + v);
                if (uri == null && v != null)
                    uri = nwcg.get(v.replace('_', ' '));
                if (uri == null)
                    uri = nwcg.get("Other");
                return uri == null ? null : NwcgStyles.point(uri);
            }
            case "tracklog":
                return tracklog(str(props, "mission_type"));
            case "segments":
                return segment(str(props, "completion_status"));
            case "divisions":
                return division(str(props, "Status"));
            case "branches":
                return NwcgStyles.solid(BRANCH, 3.5f);
            case "incident":
                return NwcgStyles.dashed(INCIDENT_AREA, DASH_9_6, 2, 4.5f);
            default:
                return null;
        }
    }

    /** The hue the fill control uses for a SARCOP area, or 0 when the layer is not one. */
    public static int fillHue(String layerName, JSONObject props) {
        final String k = kind(layerName);
        if (k == null)
            return 0;
        switch (k) {
            case "segments": {
                final String s = norm(str(props, "completion_status"));
                return 0xFF000000 | (s.contains("complete") ? 0xA3FF73 : s.contains("exclud") ? 0x999999
                        : (s.contains("active") && !s.contains("not")) || s.contains("underway") ? 0xE6C300 : 0x6300C7);
            }
            case "divisions": {
                final String s = norm(str(props, "Status"));
                return 0xFF000000 | (s.contains("complete") ? 0x6ACC14 : s.contains("exclud") ? 0x999999
                        : s.contains("not") ? 0x8000FF : 0xFFD700);
            }
            case "branches":
                return 0xFF808080;
            case "incident":
                return 0xFF000000 | (INCIDENT_AREA & 0xFFFFFF);
            default:
                return 0;
        }
    }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.US);
    }

    private static Style tracklog(String missionType) {
        switch (norm(missionType)) {
            case "recon":
                return NwcgStyles.solid(RECON, 3.5f);
            case "aerial_recon":
                return NwcgStyles.dashed(RECON, NwcgStyles.DASH, 2, 2.5f);
            case "water_recon":
                return NwcgStyles.dashed(WATER, NwcgStyles.DASH, 2, 2.5f);
            case "hasty":
                return NwcgStyles.solid(HASTY, 3.5f);
            case "primary":
                return NwcgStyles.solid(PRIMARY, 3.5f);
            case "secondary_high":
                return NwcgStyles.solid(SECONDARY_HIGH, 3.5f);
            case "secondary_low":
                return NwcgStyles.solid(SECONDARY_LOW, 3.5f);
            case "targeted":
                return NwcgStyles.solid(TARGETED, 4f);
            case "cst_lf":
                return NwcgStyles.solid(CANINE, 3.5f);
            case "cst_hrd":
                return NwcgStyles.dashed(CANINE, NwcgStyles.DASH_LONG, 3, 2.5f);
            case "damage_observation":
                return NwcgStyles.solid(DAMAGE_OBS, 3.5f);
            default:
                return NwcgStyles.solid(OTHER, 3.5f);
        }
    }

    private static Style segment(String status) {
        final String s = norm(status);
        final int edge, fill;
        if (s.contains("complete")) {
            edge = SEG_COMPLETE;
            fill = SEG_COMPLETE_FILL;
        } else if (s.contains("exclud")) {
            edge = EXCLUDED;
            fill = EXCLUDED_FILL;
        } else if (s.contains("underway") || (s.contains("active") && !s.contains("not"))) {
            edge = SEG_ACTIVE;
            fill = 0;
        } else { // Not Active, Assigned
            edge = SEG_NOT_ACTIVE;
            fill = 0;
        }
        final Style outline = NwcgStyles.dashed(edge, NwcgStyles.DASH, 2, 3.5f);
        return fill == 0 ? outline : NwcgStyles.over(new BasicFillStyle(fill), outline);
    }

    private static Style division(String status) {
        final String s = norm(status);
        final int edge, fill;
        if (s.contains("complete")) {
            edge = DIV_COMPLETE;
            fill = DIV_COMPLETE_FILL;
        } else if (s.contains("exclud")) {
            edge = EXCLUDED;
            fill = EXCLUDED_FILL;
        } else if (s.contains("not")) {
            edge = DIV_NOT_ACTIVE;
            fill = 0;
        } else {
            edge = DIV_ACTIVE;
            fill = 0;
        }
        final Style outline = NwcgStyles.solid(edge, 4.5f);
        return fill == 0 ? outline : NwcgStyles.over(new BasicFillStyle(fill), outline);
    }
}

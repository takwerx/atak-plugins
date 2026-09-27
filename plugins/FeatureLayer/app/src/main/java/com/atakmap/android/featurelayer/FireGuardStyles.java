package com.atakmap.android.featurelayer;

import org.json.JSONObject;

import java.util.Locale;

/**
 * FireGuard detection areas, as EGP draws them.
 *
 * <p>The rule is EGP's, read from the "National FireGuard Detections (Areas)" layer of the
 * "EGP - WildFireSA Advanced" web map (item 6d9c43056ddc4b8e96e4daddfd185024, 2026-09-18):
 * a solid fill by the detection's age since {@code CreationDate}, maroon inside 30 minutes,
 * red to 75, orange to two hours, yellow to a day, grey-yellow to four days, grey beyond,
 * no outline. EGP's window is fourteen days of {@code EditDate} with {@code delete_feature
 * <> 'Yes'}; the plugin's time window control sets the days, the delete flag is fixed.
 */
public final class FireGuardStyles {

    private FireGuardStyles() {
    }

    public static boolean handles(LayerSpec spec) {
        return spec != null && "fireguard".equals(spec.iconSet);
    }

    /** EGP's classes: the upper bound in minutes and the fill, opaque, in order. */
    private static final long[] MINUTES = { 15, 30, 45, 60, 75, 90, 105, 120, 240, 480, 960, 1440, 2880, 5760 };
    private static final int[] FILL = {
            0xFF730D00, 0xFFA31300,                         // maroon: < 15, < 30
            0xFFD41900, 0xFFFF1E00, 0xFFFF7361,             // red: < 45, < 60, < 75
            0xFFF79143, 0xFFF5AA71, 0xFFFFCAA1,             // orange: < 90, < 105, < 120
            0xFFFFF700, 0xFFFCF649, 0xFFFFFB87, 0xFFFFFDB5, // yellow: < 4 h, < 8 h, < 16 h, < 24 h
            0xFFE8E7C8, 0xFFE6E5DC };                       // grey-yellow: < 2 d, < 4 d
    private static final int FILL_OLD = 0xFF999999;         // grey: 4 days and more

    /** The fill for a detection created at {@code creationMs}, judged now; opaque ARGB. */
    public static int fillFor(long creationMs, long nowMs) {
        if (creationMs <= 0)
            return FILL_OLD;
        final long minutes = Math.max(0, (nowMs - creationMs) / 60000L);
        for (int i = 0; i < MINUTES.length; i++)
            if (minutes < MINUTES[i])
                return FILL[i];
        return FILL_OLD;
    }

    /** The fill for a feature's properties, or 0 when it carries no creation time. */
    public static int fillHue(JSONObject props) {
        final Object v = props == null ? null : props.opt("CreationDate");
        return v instanceof Number ? fillFor(((Number) v).longValue(), System.currentTimeMillis()) : 0;
    }

    /**
     * What a detection is called: its incident name when the analyst gave one, else its
     * type, with the acreage; "URGENT" first when the report is flagged so. A serial number
     * ("ID-20260918-NSR-2015Z") is what the feed has as a name and says nothing at a glance.
     */
    public static String title(JSONObject props, String fallback) {
        if (props == null)
            return fallback;
        final String name = props.optString("IncidentName", "").trim();
        final String type = props.optString("IncidentType", "").trim();
        final String what = !name.isEmpty() && !"null".equalsIgnoreCase(name) ? name
                : !type.isEmpty() && !"null".equalsIgnoreCase(type) ? type : fallback;
        final StringBuilder b = new StringBuilder();
        if ("yes".equalsIgnoreCase(props.optString("UrgentReportFlag", "")))
            b.append("URGENT ");
        b.append(what == null ? "" : what);
        final int acres = props.optInt("Acres", -1);
        if (acres >= 0)
            b.append(String.format(Locale.US, " \u00b7 %d ac", acres));
        return b.toString().trim();
    }
}

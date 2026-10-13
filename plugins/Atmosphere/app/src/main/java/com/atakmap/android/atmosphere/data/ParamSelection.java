package com.atakmap.android.atmosphere.data;

import android.content.SharedPreferences;

import com.atakmap.android.atmosphere.compat.MapCompat;
import com.atakmap.android.atmosphere.source.WxParam;
import com.atakmap.android.atmosphere.source.WxSourceDef;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Which variables the operator wants from a source.
 *
 * <p>Persisted per source id against ATAK's own preferences (see
 * {@link MapCompat#prefs()}), so a selection survives a restart — and so does the
 * decision to <i>stop</i> asking for a variable, which is also a decision about what
 * leaves the device.
 */
public final class ParamSelection {

    private static final String PREF_PREFIX = "weather.params.";

    private ParamSelection() {
    }

    /** Selected keys, falling back to the definition's {@code defaultOn} set. */
    public static List<WxParam> selected(WxSourceDef def) {
        final List<WxParam> out = new ArrayList<>();
        if (def == null)
            return out;

        final Set<String> keys = selectedKeys(def);
        for (WxParam p : def.params) {
            if (keys.contains(p.key))
                out.add(p);
        }
        return out;
    }

    public static Set<String> selectedKeys(WxSourceDef def) {
        final Set<String> out = new HashSet<>();
        if (def == null)
            return out;

        final SharedPreferences prefs = MapCompat.prefs();
        final String stored = prefs == null ? null : prefs.getString(key(def), null);
        if (stored == null) {
            for (WxParam p : def.params) {
                if (p.defaultOn)
                    out.add(p.key);
            }
            return out;
        }
        if (stored.isEmpty())
            return out;
        out.addAll(Arrays.asList(stored.split(",")));
        return out;
    }

    public static void setSelected(WxSourceDef def, Set<String> keys) {
        final SharedPreferences prefs = MapCompat.prefs();
        if (prefs == null || def == null)
            return;

        final StringBuilder sb = new StringBuilder();
        for (WxParam p : def.params) {           // definition order, not set order
            if (!keys.contains(p.key))
                continue;
            if (sb.length() > 0)
                sb.append(',');
            sb.append(p.key);
        }
        prefs.edit().putString(key(def), sb.toString()).apply();
    }

    private static String key(WxSourceDef def) {
        return PREF_PREFIX + def.id;
    }
}

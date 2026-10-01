
package com.atakmap.android.cursorwerx.plugin;

import android.annotation.SuppressLint;
import android.content.Context;

import com.atakmap.android.preference.PluginPreferenceFragment;

/**
 * Cursorwerx under ATAK's Settings, Tool Preferences. It holds one setting, the wheel
 * zoom step, declared in {@code res/xml/preferences.xml} as ATAK's own list preference;
 * ATAK stores the choice in its default preferences under
 * {@link com.atakmap.android.cursorwerx.PointerRouter#PREF_WHEEL_ZOOM}, where the pane's
 * Wheel zoom row writes it too and the wheel reads it on every step. So nothing here
 * needs to listen for a change.
 */
public class CursorwerxPreferenceFragment extends PluginPreferenceFragment {

    private static Context pluginContext;

    /** For Android's own re-creation of the fragment; the context was set on first use. */
    public CursorwerxPreferenceFragment() {
        super(pluginContext, R.xml.preferences);
    }

    @SuppressLint("ValidFragment")
    public CursorwerxPreferenceFragment(Context context) {
        super(context, R.xml.preferences);
        pluginContext = context;
    }

    @Override
    public String getSubTitle() {
        return getSubTitle("Tool Preferences", "Cursorwerx");
    }
}

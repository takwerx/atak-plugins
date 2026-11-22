package com.atakmap.android.atmosphere.plugin;

import android.content.Context;
import android.preference.Preference;

import com.atakmap.android.preference.PluginPreferenceFragment;
import com.atakmap.android.util.PdfHelper;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.log.Log;

/**
 * The Tool Preferences entry, which is the only route to the manual.
 *
 * <p>Three pieces are needed and this is one: {@code res/xml/preferences.xml} with a
 * {@code PanPreference} keyed "manual", this fragment to open it, and the
 * {@code ToolsPreferenceFragment.register} call in the plugin's onStart.
 * {@code samples/dsmmanager} is the reference; IPAWS carries the same three.
 */
public class AtmospherePreferenceFragment extends PluginPreferenceFragment {

    private static final String TAG = "Atmosphere";
    private static Context staticPluginContext;

    public AtmospherePreferenceFragment() {
        super(staticPluginContext, R.xml.preferences);
    }

    @SuppressWarnings("ValidFragment")
    public AtmospherePreferenceFragment(final Context pluginContext) {
        super(pluginContext, R.xml.preferences);
        staticPluginContext = pluginContext;
    }

    @Override
    public void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final Preference manual = findPreference("manual");
        if (manual == null)
            return;
        manual.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                try {
                    PdfHelper.extractAndShow(staticPluginContext, getActivity(),
                            "usermanual.pdf",
                            FileSystemUtils.getItem("tools/atmosphere").getAbsolutePath()
                                    + "/usermanual.pdf",
                            true);
                } catch (Exception e) {
                    Log.w(TAG, "could not open the manual", e);
                }
                return true;
            }
        });
    }

    @Override
    public String getSubTitle() {
        return getSubTitle("Tool Preferences", "Atmosphere");
    }
}

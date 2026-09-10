package com.atakmap.android.featurelayer.plugin;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Bundle;
import android.preference.Preference;

import com.atakmap.android.preference.PluginPreferenceFragment;
import com.atakmap.android.util.PdfHelper;
import com.atakmap.coremap.filesystem.FileSystemUtils;

import java.io.File;

/**
 * The plugin's entry under ATAK's Tool Preferences, and the only way to reach the
 * user manual. The manual is built by {@code gradle/typst.gradle} into
 * {@code assets/usermanual.pdf}; an asset is not reachable by anyone, so without
 * this screen the PDF would ship inside the APK with no way to open it.
 */
public class FeatureLayerPreferenceFragment extends PluginPreferenceFragment {

    private static final String USER_GUIDE = "usermanual.pdf";

    /** Extracted under the plugin's own folder, named for what it is: the name a file picker shows. */
    private static final String USER_GUIDE_PATH = FileSystemUtils.getRoot()
            + File.separator + "tools" + File.separator + "featurelayer"
            + File.separator + "Feature Layer User Guide.pdf";

    private static Context pluginContext;

    /**
     * PdfHelper re-extracts the PDF only when this number changes. versionCode is
     * derived from PLUGIN_VERSION and rises per release, but versionName also
     * names the ATAK target, so a rebuild for another target refreshes it too.
     */
    private static long manualVersion() {
        try {
            return pluginContext.getString(R.string.versionName).hashCode() & 0xFFFFFFFFL;
        } catch (RuntimeException noResource) {
            return System.currentTimeMillis();
        }
    }

    public FeatureLayerPreferenceFragment() {
        super(pluginContext, R.xml.preferences);
    }

    @SuppressLint("ValidFragment")
    public FeatureLayerPreferenceFragment(Context context) {
        super(context, R.xml.preferences);
        pluginContext = context;
    }

    @Override
    public String getSubTitle() {
        return getSubTitle("Tool Preferences", "Feature Layer");
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final Preference manual = findPreference("manual");
        if (manual == null)
            return;
        manual.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                PdfHelper.extractAndShow(pluginContext, getActivity(), USER_GUIDE, manualVersion(),
                        USER_GUIDE_PATH, true);
                return true;
            }
        });
    }
}

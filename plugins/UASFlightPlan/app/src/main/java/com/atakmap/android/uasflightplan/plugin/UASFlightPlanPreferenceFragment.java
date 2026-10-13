package com.atakmap.android.uasflightplan.plugin;

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
 * user manual.
 *
 * <p>The manual is built by {@code gradle/typst.gradle} into
 * {@code assets/usermanual.pdf}, but an asset is not reachable by anyone: ATAK
 * surfaces a plugin's documentation through this screen, so without it the PDF
 * ships inside the APK and no pilot can open it.
 *
 * <p>Nothing else lives on this screen on purpose. Everything the pilot sets is
 * on the pane's Settings page, next to what it affects.
 */
public class UASFlightPlanPreferenceFragment extends PluginPreferenceFragment {

    private static final String USER_GUIDE = "usermanual.pdf";

    /**
     * Where the PDF is extracted before a viewer is handed it. Named for what it
     * is rather than for the asset, because this is the name a pilot sees in a
     * file picker.
     */
    private static final String USER_GUIDE_PATH = FileSystemUtils.getRoot()
            + File.separator + "tools" + File.separator + "uasflightplan"
            + File.separator + "UAS Flight Plan User Guide.pdf";

    private static Context pluginContext;

    /**
     * A number that changes with every release, for PdfHelper's cache.
     *
     * <p>{@code versionName} carries PLUGIN_VERSION and the ATAK target and is
     * correct in a tak.gov-signed build, where the git hash is blank. Hashed
     * rather than parsed, because a rebuild for a different ATAK target should
     * refresh the manual too.
     */
    private static long manualVersion() {
        try {
            final String name = pluginContext.getString(R.string.versionName);
            return name.hashCode() & 0xFFFFFFFFL;
        } catch (RuntimeException noResource) {
            // Never let the manual fail to open over its own cache key.
            return System.currentTimeMillis();
        }
    }

    public UASFlightPlanPreferenceFragment() {
        super(pluginContext, R.xml.preferences);
    }

    @SuppressLint("ValidFragment")
    public UASFlightPlanPreferenceFragment(Context context) {
        super(context, R.xml.preferences);
        pluginContext = context;
    }

    @Override
    public String getSubTitle() {
        return getSubTitle("Tool Preferences", "UAS Flight Plan");
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final Preference manual = findPreference("manual");
        if (manual == null)
            return;
        manual.setOnPreferenceClickListener(
                new Preference.OnPreferenceClickListener() {
                    @Override
                    public boolean onPreferenceClick(Preference preference) {
                        PdfHelper.extractAndShow(pluginContext, getActivity(),
                                USER_GUIDE, manualVersion(), USER_GUIDE_PATH,
                                true);
                        return true;
                    }
                });
    }
}

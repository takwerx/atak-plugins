package com.atakmap.android.dozercountry.plugin;

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
 * ships inside the APK and no operator can open it. That shipped once in this repo
 * undetected, because the PDF genuinely was in the APK.
 *
 * <p>Nothing else lives on this screen on purpose. The standard is a readable
 * asset rather than a settings page, and the area, the overlay and the opacity are
 * all decided on the panel next to what they affect.
 */
public class DozerCountryPreferenceFragment extends PluginPreferenceFragment {

    private static final String USER_GUIDE = "usermanual.pdf";

    /**
     * Where the PDF is extracted before a viewer is handed it. Named for what it
     * is rather than for the asset, because this is the name an operator sees in a
     * file picker.
     */
    private static final String USER_GUIDE_PATH = FileSystemUtils.getRoot()
            + File.separator + "tools" + File.separator + "dozercountry"
            + File.separator + "Dozer Country User Guide.pdf";

    private static Context pluginContext;

    /**
     * A number that changes with every release, for PdfHelper's cache.
     *
     * <p>The obvious key is {@code versionCode}, and it does not work here.
     * {@code getVersionCode()} derives from git and tak.gov builds from a source
     * zip with no {@code .git}, so a signed build falls back to 1 every time. The
     * stored value would never change, the PDF would never be re-extracted, and
     * the first manual a user ever opened would be the one they kept — with the
     * plugin manager reporting one version and the manual showing another.
     *
     * <p>{@code versionName} does not have that problem: it carries PLUGIN_VERSION
     * and is correct in a signed build. Hashed rather than parsed, because it
     * reads "0.2 () - [5.8.0]" and a rebuild for a different ATAK target should
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

    public DozerCountryPreferenceFragment() {
        super(pluginContext, R.xml.preferences);
    }

    @SuppressLint("ValidFragment")
    public DozerCountryPreferenceFragment(Context context) {
        super(context, R.xml.preferences);
        pluginContext = context;
    }

    @Override
    public String getSubTitle() {
        return getSubTitle("Tool Preferences", "Dozer Country");
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

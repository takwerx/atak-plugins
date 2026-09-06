package com.atakmap.android.fobs.plugin;

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
 * ships inside the APK and no operator can open it. That shipped once in this
 * repo, undetected, because the PDF genuinely was in the APK.
 *
 * <p>Nothing else lives on this screen on purpose. The default style is on the
 * pane, next to the tiles it applies to, and the feed is chosen per track.
 */
public class FobsPreferenceFragment extends PluginPreferenceFragment {

    private static final String USER_GUIDE = "usermanual.pdf";

    /**
     * Where the PDF is extracted to before a viewer is handed it. Under the
     * plugin's own folder in ATAK's tree, named for what it is rather than for the
     * asset, because this is the name the operator sees in a file picker.
     */
    private static final String USER_GUIDE_PATH = FileSystemUtils.getRoot()
            + File.separator + "tools" + File.separator + "fobs"
            + File.separator + "FOBS User Guide.pdf";

    private static Context pluginContext;

    /**
     * A number that changes with every release, for PdfHelper's cache.
     *
     * <p>The five-argument {@code extractAndShow} reads the APK's
     * {@code versionCode} and re-extracts the PDF only when it differs from the one
     * it stored. {@code getVersionCode()} in {@code app/build.gradle} derives from
     * git, and tak.gov builds from a source zip with no {@code .git} in it, so every
     * signed release has {@code versionCode=1}: the stored value never differs, the
     * PDF is never re-extracted, and the first manual a user ever opens is the one
     * they keep. {@code versionName} carries PLUGIN_VERSION and is correct in a
     * signed build ("0.3 () - [5.8.0]"), so the six-argument overload is handed a
     * number derived from that instead. Found on Map Depot, 2026-08-31.
     */
    private static long manualVersion() {
        try {
            final String name = pluginContext.getString(R.string.versionName);
            // hashCode, not a parse: the ATAK target is in the string too, so a
            // build for a different target also refreshes the manual.
            return name.hashCode() & 0xFFFFFFFFL;
        } catch (RuntimeException noResource) {
            // Never let the manual fail to open over its own cache key.
            return System.currentTimeMillis();
        }
    }

    public FobsPreferenceFragment() {
        super(pluginContext, R.xml.preferences);
    }

    @SuppressLint("ValidFragment")
    public FobsPreferenceFragment(Context context) {
        super(context, R.xml.preferences);
        pluginContext = context;
    }

    @Override
    public String getSubTitle() {
        return getSubTitle("Tool Preferences", "FOBS");
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

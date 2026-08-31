
package com.atakmap.android.plss.plugin;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceManager;

import com.atakmap.android.maps.MapView;
import com.atakmap.android.plss.plugin.R;
import com.atakmap.android.preference.PluginPreferenceFragment;
import com.atakmap.android.util.PdfHelper;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.log.Log;

import java.io.File;

/**
 * The plugin's entry under ATAK's Tool Preferences, and the only way to reach
 * the user manual.
 *
 * The manual is built by {@code gradle/typst.gradle} into
 * {@code assets/usermanual.pdf}, but an asset is not reachable by anyone: ATAK
 * surfaces a plugin's documentation through this screen, so without it the PDF
 * shipped inside the APK and no operator could open it. PLSS Grid 0.3 did
 * exactly that.
 *
 * There is nothing else on this screen on purpose. PLSS Grid has no settings --
 * the grid draws from whichever packs are installed, and every other choice is
 * made in the plugin's own pane.
 */
public class PlssPreferenceFragment extends PluginPreferenceFragment {

    public static final String TAG = "PlssPrefs";

    private static final String USER_GUIDE = "usermanual.pdf";

    /**
     * Where the PDF is extracted to before a viewer is handed it. Under the
     * plugin's own folder in ATAK's tree, named for what it is rather than for
     * the asset, because this is the name the operator sees in a file picker.
     */
    private static final String USER_GUIDE_PATH = FileSystemUtils.getRoot()
            + File.separator + "tools" + File.separator + "plss"
            + File.separator + "PLSS Grid User Guide.pdf";

    /**
     * The build whose manual is currently sitting at {@link #USER_GUIDE_PATH}.
     * On the MapView context, not the plugin's: plugin-context preferences do
     * not survive the plugin being reloaded, and a marker that forgets itself
     * on every update would defeat the point of having one.
     */
    private static final String PREF_MANUAL_VERSION = "plss.manualVersion";

    private static Context pluginContext;

    public PlssPreferenceFragment() {
        super(pluginContext, R.xml.preferences);
    }

    @SuppressLint("ValidFragment")
    public PlssPreferenceFragment(Context context) {
        super(context, R.xml.preferences);
        pluginContext = context;
    }

    @Override
    public String getSubTitle() {
        return getSubTitle("Tool Preferences", "PLSS Grid");
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
                        showManual();
                        return true;
                    }
                });
    }

    /**
     * Opens the manual, having first thrown away one left by an earlier build.
     *
     * {@link PdfHelper#extractAndShow} copies the asset out only when the
     * plugin's <em>version code</em> differs from the one it recorded the last
     * time. That is sound where version codes increase, and ours never do:
     * {@code getVersionCode()} reads the git revision, tak.gov builds from a
     * source zip with no {@code .git}, and so every signed release reports
     * {@code versionCode=1}. Measured on a Map Depot zip built both ways:
     *
     * <pre>
     * with .git : versionCode=1788195463  versionName='1.2 (4391c747) - [5.8.0]'
     * no .git   : versionCode=1           versionName='1.2 () - [5.8.0]'
     * </pre>
     *
     * The consequence is not subtle: the first time an operator opens the
     * manual, that PDF is frozen on the device for good, and every later
     * release ships a manual nobody sees.
     *
     * The version <em>name</em> does survive the trip, being assembled from
     * {@code PLUGIN_VERSION} rather than from git, so it is what decides here.
     * Delete the stale file and PdfHelper copies the current one, having
     * nothing left to reuse.
     */
    private void showManual() {
        final String version = versionName();
        final SharedPreferences prefs = prefs();

        if (version != null && prefs != null
                && !version.equals(prefs.getString(PREF_MANUAL_VERSION, null)))
            discardExtractedManual();

        final boolean shown = PdfHelper.extractAndShow(pluginContext,
                getActivity(), USER_GUIDE, USER_GUIDE_PATH, true);

        // Recorded only once the manual is actually out of the APK. A failed
        // extraction leaves no file behind, and PdfHelper always copies when
        // the destination is missing, so a failure here simply retries.
        if (shown && version != null && prefs != null)
            prefs.edit().putString(PREF_MANUAL_VERSION, version).apply();
    }

    /** Removes a manual extracted by an earlier build, if one is there. */
    private static void discardExtractedManual() {
        final File extracted = new File(USER_GUIDE_PATH);
        if (!extracted.exists())
            return;
        if (extracted.delete())
            Log.d(TAG, "discarded the manual left by an earlier build");
        else
            Log.w(TAG, "could not remove the stale manual at " + USER_GUIDE_PATH
                    + "; the operator may still be shown an old one");
    }

    /**
     * This build's version name, e.g. {@code 0.4 () - [5.8.0]}. Empty
     * parentheses are expected on anything tak.gov built -- that is the git
     * revision, which a source zip does not carry.
     */
    private static String versionName() {
        if (pluginContext == null)
            return null;
        try {
            final PackageInfo info = pluginContext.getPackageManager()
                    .getPackageInfo(pluginContext.getPackageName(), 0);
            return info == null ? null : info.versionName;
        } catch (Exception noSuchPackage) {
            Log.w(TAG, "could not read the plugin version: " + noSuchPackage);
            return null;
        }
    }

    private static SharedPreferences prefs() {
        final MapView mv = MapView.getMapView();
        return mv == null ? null
                : PreferenceManager.getDefaultSharedPreferences(mv.getContext());
    }
}

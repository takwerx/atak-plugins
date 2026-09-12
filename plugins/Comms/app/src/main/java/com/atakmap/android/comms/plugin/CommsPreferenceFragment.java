package com.atakmap.android.comms.plugin;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Bundle;
import android.preference.Preference;

import com.atakmap.android.preference.PluginPreferenceFragment;
import com.atakmap.android.util.PdfHelper;
import com.atakmap.coremap.filesystem.FileSystemUtils;

import java.io.File;

/**
 * The plugin's Tool Preferences screen: one row, which opens the user manual.
 *
 * <p>The manual is compiled into {@code assets/usermanual.pdf}, and an asset is not
 * reachable by anyone; this row is the only way an operator gets to it.
 */
public class CommsPreferenceFragment extends PluginPreferenceFragment {

    private static final String USER_GUIDE = "usermanual.pdf";
    private static final String USER_GUIDE_PATH = FileSystemUtils.getRoot()
            + File.separator + "tools" + File.separator + "comms"
            + File.separator + "Comms User Guide.pdf";

    private static Context pluginContext;

    /**
     * The cache key for the extracted PDF. versionName is "0.1 () - [5.8.0]", so the
     * ATAK target is in there too and a rebuild for a different target refreshes the
     * manual. Sign-extended into a long so a negative hash stays distinct.
     */
    private static long manualVersion() {
        try {
            final String name = pluginContext.getString(R.string.versionName);
            return name.hashCode() & 0xFFFFFFFFL;
        } catch (RuntimeException noResource) {
            return System.currentTimeMillis();
        }
    }

    public CommsPreferenceFragment() {
        super(pluginContext, R.xml.preferences);
    }

    @SuppressLint("ValidFragment")
    public CommsPreferenceFragment(Context context) {
        super(context, R.xml.preferences);
        pluginContext = context;
    }

    @Override
    public String getSubTitle() {
        return getSubTitle("Tool Preferences", "Comms");
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

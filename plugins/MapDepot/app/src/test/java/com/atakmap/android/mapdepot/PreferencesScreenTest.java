package com.atakmap.android.mapdepot;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Tool Preferences must not hold a dialog preference. EditTextPreference and
 * its kin build their dialog on the context that inflated the screen, which is
 * the plugin's, and ATAK dies with a BadTokenException the moment the row is
 * tapped (found on ATAK 5.8.0.5 with the CARTO key row, ops #595). A row that
 * needs a dialog is a plain Preference whose click builds an AlertDialog on the
 * fragment's host activity.
 */
public class PreferencesScreenTest {

    private static String screen() throws Exception {
        final File f = new File("src/main/res/xml/preferences.xml");
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)
                .replaceAll("(?s)<!--.*?-->", "");
    }

    @Test
    public void noDialogPreferenceOnTheSettingsScreen() throws Exception {
        final String xml = screen();
        for (String bad : new String[] { "EditTextPreference",
                "ListPreference", "MultiSelectListPreference",
                "DialogPreference" })
            assertFalse(bad + " builds its dialog on the plugin context",
                    xml.contains("<" + bad));
    }

    @Test
    public void theCartoKeyRowIsStillThere() throws Exception {
        assertTrue(screen().contains("android:key=\"key_carto\""));
    }
}

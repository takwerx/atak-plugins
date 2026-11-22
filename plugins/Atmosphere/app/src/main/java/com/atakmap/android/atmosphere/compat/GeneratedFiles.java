package com.atakmap.android.atmosphere.compat;

import android.content.Context;

import java.io.File;

/**
 * Where Atmosphere keeps the pictures it draws for itself: the station, buoy, gauge,
 * SNOTEL, spot and storm symbols, the fire zone pills and the chooser swatch.
 *
 * <p><b>In ATAK's own private storage, not on the card.</b> They are caches the plugin
 * composes and ATAK reads back by {@code file://} URI in this same process, so nothing
 * outside ATAK needs them. Under {@code /atak/tools/atmosphere} they were thousands of
 * ordinary PNGs any app could list and a gallery could index as photos
 * (takwerx/atmosphere#2). Here they go when ATAK's data is cleared or ATAK is removed.
 *
 * <p><b>ATAK's context, never the plugin's.</b> The plugin package's private
 * directory belongs to another uid than the process this code runs in, and
 * {@code mkdirs} there fails, which leaves every icon unwritten and every symbol
 * invisible.
 */
public final class GeneratedFiles {

    /**
     * What earlier versions composed under {@code tools/atmosphere} on the card, by
     * name. Swept by name only: that folder also holds the layers' feature stores
     * and the extracted manual, which are not ours to delete here.
     */
    public static final String[] LEGACY = {
            "buoy-icons", "gauge-icons", "snotel-icons", "spot-icons",
            "station-icons", "storm-icons", "zone-pills", "swatch_v1.png"
    };

    /** A folder name StationIcons sets a past session's icons aside under. */
    public static final String LEGACY_SET_ASIDE = "station-icons.old-";

    private GeneratedFiles() {
    }

    /** {@code <ATAK files>/atmosphere}, or null before ATAK's map exists. */
    public static File root() {
        final Context atak = MapCompat.atakContext();
        return atak == null ? null : new File(atak.getFilesDir(), "atmosphere");
    }

    /**
     * {@code <ATAK files>/atmosphere/icons/<kind>}, not yet created: the caller makes
     * it, because one of them (the stations) moves the last session's folder aside
     * first. Null before ATAK's map exists.
     */
    public static File icons(String kind) {
        final File root = root();
        return root == null ? null : new File(new File(root, "icons"), kind);
    }
}

package com.atakmap.android.atmosphere.overlay;

/** The overlay package's shared threads, for the plugin to stop with itself. */
public final class OverlayPools {

    /** Called at the end of the plugin's onStop; a new generation makes its own. */
    public static void shutdown() {
        AtmosphereFeatures.shutdownAttach();
    }

    private OverlayPools() {
    }
}

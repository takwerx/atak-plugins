package com.atakmap.android.ipaws.data;

import android.os.Handler;
import android.os.Looper;

/** One main-thread handler, so the pieces that need to hop back share it. */
public final class MainThread {

    private static final Handler H = new Handler(Looper.getMainLooper());

    private MainThread() {
    }

    public static void post(Runnable r) {
        H.post(r);
    }

    public static void postDelayed(Runnable r, long delayMs) {
        H.postDelayed(r, delayMs);
    }

    /** Cancels a pending {@link #postDelayed}; the timer must not outlive the plugin. */
    public static void remove(Runnable r) {
        H.removeCallbacks(r);
    }
}

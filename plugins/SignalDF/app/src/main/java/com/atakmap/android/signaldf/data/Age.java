package com.atakmap.android.signaldf.data;

/**
 * How old something is, in words.
 *
 * <p>Its own class rather than a method on {@code Units} because {@code Units}
 * is copied verbatim between plugins and stays byte-identical but for its
 * package line -- a local addition there is a divergence that the next copy
 * forward silently loses.
 *
 * <p>Age is the number this plugin has to keep in front of the operator. A
 * bearing from two minutes ago draws the same line as one from this second, so
 * every place that shows a bearing shows how old it is.
 */
public final class Age {

    private Age() {
    }

    /** Seconds under a minute, then minutes, then hours. Never a bare number. */
    public static String format(long ms) {
        if (ms < 0)
            ms = 0;
        long s = ms / 1000;
        if (s < 60)
            return s + " s";
        long m = s / 60;
        if (m < 60)
            return m + " min";
        return (m / 60) + " h";
    }
}

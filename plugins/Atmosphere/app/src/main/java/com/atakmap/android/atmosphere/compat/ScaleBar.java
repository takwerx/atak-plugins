package com.atakmap.android.atmosphere.compat;

import com.atakmap.android.maps.MapView;
import com.atakmap.android.widgets.AbstractParentWidget;
import com.atakmap.android.widgets.MapWidget;
import com.atakmap.android.widgets.ScaleWidget;
import com.atakmap.coremap.conversions.Span;
import com.atakmap.coremap.conversions.SpanUtilities;
import com.atakmap.coremap.log.Log;

/**
 * Reads the same number as ATAK's scale bar in the lower left.
 *
 * <p>The point is to give the operator one reference instead of two. A zoom threshold
 * expressed in meters per pixel, or as an invented band like "county level", is a
 * second scale they have to learn and reconcile against the bar already on screen.
 * Quoting the bar means the panel and the map agree by construction.
 *
 * <p>Copied forward from Cam Depot, which is how a helper travels between these
 * plugins -- there is no shared module -- with only its package, its log tag and its
 * unit lookup changed.
 *
 * <p>ATAK's {@code ScaleWidget} implements the stable {@code IScaleWidget2} interface,
 * but there is no stable way to <em>find</em> it — the widget tree is reached through
 * {@code MapView.getComponentExtra("rootLayoutWidget")}, which is internal and
 * obfuscated, and CLAUDE.md is explicit that internals decide whether a plugin
 * survives an ATAK upgrade. So the lookup is best-effort and cached, and there is a
 * pure-arithmetic fallback that is close enough to be useful if the widget ever moves.
 */
public final class ScaleBar {

    private static final String TAG = "AtmosphereScaleBar";

    /**
     * Roughly the bar's own width.
     *
     * <p>Public, and used for <b>both</b> ends of a threshold: turning a preset like
     * "5 mi" into a resolution, and turning a stored resolution back into text. It
     * has to be the same constant both ways. Describing a fixed gate through the
     * live bar width instead makes the button's own label change as the operator
     * zooms, which reads as the setting drifting on its own.
     */
    public static final double FALLBACK_BAR_PIXELS = 200;

    private static ScaleWidget cached;
    private static boolean lookupFailed;

    private ScaleBar() {
    }

    private static ScaleWidget widget(MapView mv) {
        if (cached != null || lookupFailed || mv == null)
            return cached;
        try {
            final Object root = mv.getComponentExtra("rootLayoutWidget");
            if (root instanceof AbstractParentWidget)
                cached = find((AbstractParentWidget) root, 0);
        } catch (LinkageError | RuntimeException e) {
            Log.w(TAG, "scale widget lookup failed; using arithmetic instead", e);
        }
        if (cached == null)
            lookupFailed = true;        // do not re-walk the tree on every frame
        return cached;
    }

    private static ScaleWidget find(AbstractParentWidget parent, int depth) {
        if (depth > 6)
            return null;                // the tree is shallow; this is a cycle guard
        for (int i = 0; i < parent.getChildCount(); i++) {
            final MapWidget w = parent.getChildAt(i);
            if (w instanceof ScaleWidget)
                return (ScaleWidget) w;
            if (w instanceof AbstractParentWidget) {
                final ScaleWidget found = find((AbstractParentWidget) w, depth + 1);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    /**
     * What the scale bar currently reads, e.g. {@code "5 mi"}.
     *
     * @return the bar's own text, or an equivalent computed value if it cannot be read
     */
    public static String text(MapView mv) {
        final ScaleWidget w = widget(mv);
        if (w != null) {
            try {
                final String t = w.getText();
                if (t != null && !t.trim().isEmpty())
                    return t.trim();
            } catch (RuntimeException e) {
                // fall through to arithmetic
            }
        }
        return approximate(mv == null ? 0 : mv.getMapResolution());
    }

    /** The distance the bar spans, in meters — what a threshold is compared against. */
    public static double meters(MapView mv) {
        final ScaleWidget w = widget(mv);
        if (w != null) {
            try {
                final double s = w.getScale();
                if (s > 0)
                    return s;
            } catch (RuntimeException e) {
                // fall through
            }
        }
        return (mv == null ? 1 : mv.getMapResolution()) * FALLBACK_BAR_PIXELS;
    }

    /** Format a bar-width distance the way ATAK would, in the operator's units. */
    public static String describe(double barMeters) {
        try {
            return SpanUtilities.formatType(rangeUnits(), barMeters, Span.METER);
        } catch (RuntimeException e) {
            return Math.round(barMeters) + " m";
        }
    }

    /**
     * The unit ATAK is set to show ranges in.
     *
     * <p>Note that {@code Span.ENGLISH} is 0 and {@code METRIC} is 1; assuming the
     * obvious ordering gets every distance in the plugin exactly backwards.
     */
    private static int rangeUnits() {
        try {
            final MapView mv = MapView.getMapView();
            if (mv != null) {
                final android.content.SharedPreferences p = android.preference
                        .PreferenceManager.getDefaultSharedPreferences(mv.getContext());
                return Integer.parseInt(p.getString("rab_rng_units_pref",
                        String.valueOf(Span.ENGLISH)));
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "could not read the range unit preference", e);
        }
        return Span.ENGLISH;
    }

    /**
     * The big distance unit ATAK is set to, as a label: "mi", "km" or "NM".
     *
     * <p>Note that {@code Span.ENGLISH} is 0 and {@code METRIC} is 1. Assuming the
     * obvious ordering gets every distance in the plugin exactly backwards.
     */
    public static String bigLabel() {
        final int units = rangeUnits();
        if (units == Span.METRIC)
            return "km";
        return units == Span.NM ? "NM" : "mi";
    }

    /**
     * A distance in that big unit, in meters.
     *
     * <p>Its own method because the obvious shortcut is wrong: the plugin's
     * {@code Units.toCanonical} with no unit named hands the number straight back, so
     * a five mile preset silently becomes five meters and every gate built from the
     * ladder is off by a factor of sixteen hundred.
     */
    public static double bigToMeters(double big) {
        final int units = rangeUnits();
        if (units == Span.METRIC)
            return big * 1000d;
        return units == Span.NM ? big * 1852d : big * 1609.344d;
    }

    private static String approximate(double metersPerPixel) {
        return describe(metersPerPixel * FALLBACK_BAR_PIXELS);
    }
}

package com.atakmap.android.iwi.plugin;

import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.conversions.Span;
import com.atakmap.coremap.conversions.SpanUtilities;

/**
 * Distances in whatever units the operator has already told ATAK they want.
 *
 * <p>AirAware's {@code Units}, carried forward (there is no shared module). ATAK keeps
 * the choice in {@code rab_rng_units_pref}, and the stored value is the {@link Span}
 * constant itself: "0" is {@link Span#ENGLISH}, "1" {@link Span#METRIC}, "2"
 * {@link Span#NM}. Note that 0 is English, not metric; assuming the obvious ordering
 * gets it exactly backwards. Read on each call, because it can change in ATAK's
 * settings while the pane is open.
 */
public final class Units {

    private Units() {
    }

    /** @return one of {@link Span#ENGLISH}, {@link Span#METRIC}, {@link Span#NM} */
    public static int type() {
        try {
            final MapView mv = MapView.getMapView();
            if (mv != null) {
                final SharedPreferences p = PreferenceManager
                        .getDefaultSharedPreferences(mv.getContext());
                return Integer.parseInt(p.getString("rab_rng_units_pref",
                        String.valueOf(Span.ENGLISH)));
            }
        } catch (RuntimeException e) {
            // A malformed preference must not stop the pane drawing.
        }
        return Span.ENGLISH;
    }

    /** A distance in meters, written the way ATAK's own range tool writes it. */
    public static String distance(double meters) {
        try {
            return SpanUtilities.formatType(type(), meters, Span.METER);
        } catch (RuntimeException e) {
            return Math.round(meters) + " m";
        }
    }
}

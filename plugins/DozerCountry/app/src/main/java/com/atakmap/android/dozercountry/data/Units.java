package com.atakmap.android.dozercountry.data;

import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.conversions.Span;
import com.atakmap.coremap.conversions.SpanUtilities;

/**
 * Distances in whatever units the operator has already told ATAK they want.
 *
 * <p>ATAK keeps this in {@code rab_rng_units_pref} (Range &amp; Bearing range units),
 * and the stored value <em>is</em> the {@link Span} type constant, so it maps straight
 * through:
 *
 * <pre>
 *   "0" -&gt; Span.ENGLISH (feet / miles)     &lt;- ATAK's default
 *   "1" -&gt; Span.METRIC  (meters / km)
 *   "2" -&gt; Span.NM      (nautical miles)
 * </pre>
 *
 * <p>Note that 0 is <em>English</em>, not metric. Assuming the obvious ordering gets it
 * exactly backwards.
 *
 * <p>Read on each call rather than cached: it can change in ATAK's settings while the
 * pane is open, and a stale unit is worse than a cheap lookup.
 *
 * <p>Percent slope is never put through here. It is not a distance, and the NWCG tables
 * are written in percent — converting it to anything else would be inventing a unit the
 * standard does not use.
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

    /** Format a distance in metres the way ATAK would, e.g. "12.4 mi" or "20 km". */
    public static String format(double meters) {
        try {
            return SpanUtilities.formatType(type(), meters, Span.METER);
        } catch (RuntimeException e) {
            return Math.round(meters) + " m";
        }
    }
}

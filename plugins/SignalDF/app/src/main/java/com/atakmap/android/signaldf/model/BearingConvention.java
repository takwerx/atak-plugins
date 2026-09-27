package com.atakmap.android.signaldf.model;

import com.atakmap.android.signaldf.data.Angles;

/**
 * How one feed writes a direction of arrival on the wire.
 *
 * <p>The KrakenSDR exports the same DoA, from the same frame, as two different
 * numbers depending on which interface is read: the Kraken App CSV writes
 * {@code 360 - theta_0} while the XML and the WebSocket JSON write
 * {@code theta_0}. There is a comment at the CSV line in {@code krakensdr_doa}
 * -- "Change to this, once we upload new Android APK" -- so the mirror is
 * deliberate and it is version dependent.
 *
 * <p>A mirrored bearing looks entirely plausible on a map, which is why this is
 * an explicit property of each feed adapter rather than a constant somewhere in
 * the drawing code. When the bench proves the operator's build differs from the
 * source that was read, the fix is the one line in that adapter, and everything
 * downstream is unaffected.
 */
public enum BearingConvention {

    /** The feed writes theta_0 as-is: doa.xml and the WebSocket JSON. */
    DIRECT("theta as-is"),

    /** The feed writes 360 - theta_0: DOA_value.html, the Kraken App CSV. */
    MIRRORED("360 - theta");

    private final String label;

    BearingConvention(String label) {
        this.label = label;
    }

    /** Short words for the pane, so the convention in use is never a guess. */
    public String label() {
        return label;
    }

    /**
     * Converts what the wire said into an array-relative bearing (theta_0),
     * degrees clockwise from the antenna array's own zero, wrapped to [0, 360).
     *
     * <p>Note that the mirror is its own inverse, so this doubles as the
     * conversion back out to the wire.
     */
    public double toArrayRelative(double reportedDeg) {
        if (Double.isNaN(reportedDeg))
            return Double.NaN;
        return this == DIRECT
                ? Angles.norm360(reportedDeg)
                : Angles.norm360(360.0 - reportedDeg);
    }
}

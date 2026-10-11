package com.atakmap.android.signaldf.model;

import com.atakmap.android.signaldf.data.Angles;

/**
 * How one feed writes a direction of arrival on the wire.
 *
 * <p><b>The canonical value is the compass-convention bearing</b> -- degrees
 * clockwise from the antenna array's zero -- which is {@code 360 - theta_0},
 * not {@code theta_0}. That is not a preference; it is what the radio's own
 * code does. {@code kraken_sdr_signal_processor.py} computes a geolocated
 * endpoint as {@code theta = my_bearing + (360 - doa)} where {@code doa} is raw
 * {@code theta_0} (calculate_end_lat_lng, called at line 841), and the GUI
 * converts for display with {@code (360 - theta + compass_offset) % 360}
 * whenever {@code doa_measure} is Compass. {@code theta_0} runs
 * counter-clockwise in the array's own scan frame.
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

    /**
     * The feed already writes the compass-convention bearing, so nothing is
     * applied: {@code DOA_value.html}, the Kraken App CSV, which writes
     * {@code 360 - theta_0} at the CSV line in the DSP.
     */
    DIRECT("compass bearing as-is"),

    /**
     * The feed writes raw {@code theta_0} and has to be mirrored:
     * {@code doa.xml} and the WebSocket JSON, both of which are handed
     * {@code theta_0_list[j]} unmodified.
     */
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
     * Converts what the wire said into the canonical array-relative bearing:
     * degrees clockwise from the antenna array's own zero, wrapped to
     * [0, 360). Add the array heading to get a bearing to true north.
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

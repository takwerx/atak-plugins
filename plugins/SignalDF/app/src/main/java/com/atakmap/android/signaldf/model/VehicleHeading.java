package com.atakmap.android.signaldf.model;

import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;

/**
 * Which way the vehicle is going, from ATAK's own self marker.
 *
 * <p>This is the answer to the normal case, and the plugin shipped 0.1 without
 * it. Both antenna templates tell the operator to point antenna 0 down the
 * vehicle -- KrakenRF's arm, and the 3D template's instruction "use the
 * positioning arm's orientation to align the KrakenSDR's channel 0 to your
 * driving direction". Once that is done, the array's heading and the vehicle's
 * heading are the same number, and ATAK already knows the vehicle's. Asking
 * the operator to type it is asking them for something the phone in their hand
 * is holding.
 *
 * <p><b>Only while moving.</b> A GPS course is derived from successive fixes,
 * so a stationary receiver produces a course that wanders at random. Reading it
 * anyway would put a bearing line on the map that swings while the truck is
 * parked, which is the same class of silent wrong answer as the radio's
 * ambiguous zero -- see {@link ArrayHeading}. So a course is only taken above
 * {@link #MIN_SPEED_MPS}, and the last good one is held for
 * {@link #HOLD_MS} afterwards so that a stop at a light does not drop every
 * bearing off the map.
 *
 * <p>After that it goes stale and says so rather than getting older quietly. A
 * vehicle that has been parked for two minutes may have been turned around.
 */
public final class VehicleHeading {

    /**
     * Below this, a GPS course is noise, in meters per second.
     *
     * <p>2 m/s is about 4.5 mph: walking pace. Under it a consumer GPS's
     * successive fixes differ by less than their own error, so the course
     * between them is the direction of the error rather than of travel.
     */
    public static final double MIN_SPEED_MPS = 2.0;

    /**
     * How long a course stays good after the vehicle stops, in milliseconds.
     *
     * <p>A minute. Long enough to cover a traffic light, a gate, or a pause to
     * look at the screen, all of which leave the truck pointing the way it was.
     * Short enough that it cannot survive a three-point turn in a pullout.
     */
    public static final long HOLD_MS = 60_000L;

    private static VehicleHeading instance;

    /** The one tracker, or null before plugin start and after plugin stop. */
    public static VehicleHeading get() {
        return instance;
    }

    private final MapView mapView;

    private double lastGoodDeg;
    private long lastGoodAt = -1;

    public VehicleHeading(MapView mapView) {
        this.mapView = mapView;
        instance = this;
    }

    public void dispose() {
        if (instance == this)
            instance = null;
    }

    /**
     * The vehicle's heading in degrees true, or null when nothing recent
     * enough is known.
     *
     * <p>Reads on demand rather than on a timer: every caller asks while
     * handling a frame or redrawing the pane, both of which happen about once
     * a second, and a timer would be a second thing to stop on plugin unload
     * for no gain.
     */
    public Double degrees() {
        Marker self = mapView == null ? null : mapView.getSelfMarker();
        if (self != null) {
            double speed = self.getTrackSpeed();
            double heading = self.getTrackHeading();
            // NaN is what ATAK reports before it has a track at all.
            if (!Double.isNaN(speed) && !Double.isNaN(heading)
                    && speed >= MIN_SPEED_MPS) {
                lastGoodDeg = heading;
                lastGoodAt = android.os.SystemClock.elapsedRealtime();
            }
        }
        if (lastGoodAt < 0)
            return null;
        long age = android.os.SystemClock.elapsedRealtime() - lastGoodAt;
        return age <= HOLD_MS ? Double.valueOf(lastGoodDeg) : null;
    }

    /** True when the answer is being held from a stop rather than measured. */
    public boolean isHeld() {
        if (lastGoodAt < 0)
            return false;
        Marker self = mapView == null ? null : mapView.getSelfMarker();
        if (self == null)
            return true;
        double speed = self.getTrackSpeed();
        return Double.isNaN(speed) || speed < MIN_SPEED_MPS;
    }
}

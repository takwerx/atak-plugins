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
 * heading are the same number, and ATAK already knows the vehicle's.
 *
 * <p><b>Everything here is read off ATAK rather than worked out again.</b>
 * {@code LocationMapComponent} solved this exact problem for its own self
 * marker, and its answers are on the marker for anyone to read:
 *
 * <ul>
 * <li>{@code getTrackHeading()} is set <em>only</em> from the GPS bearing.
 *     When ATAK cannot use a GPS bearing it falls back to the magnetometer for
 *     the map's orientation, and deliberately does not push that into the
 *     track. So a stale track heading is the last GPS-derived course and never
 *     a compass reading -- which matters enormously here, because the phone's
 *     compass points wherever the phone is lying, not where the array points.
 * <li>{@code avgSpeed30} is a 30-sample rolling average of GPS speed. An
 *     instantaneous speed is too noisy to gate on.
 * <li>{@code driving} is ATAK's own verdict on whether a GPS bearing is
 *     currently usable, including its hold after a stop.
 * </ul>
 *
 * <p><b>Do not gate on {@code getTrackSpeed()}.</b> The first version of this
 * class did, and it could never have worked: {@code setLocationTrackHeading}
 * calls {@code setTrack(estimate, 0d)}, so the self marker's track speed is
 * always exactly zero. The speed lives in the marker's metadata instead.
 *
 * <p><b>Only while moving, and ATAK's numbers for what that means.</b> A GPS
 * course comes from successive fixes, so a stationary receiver produces a
 * course that wanders with its own error -- the same class of silent wrong
 * answer as the radio's ambiguous zero, which is why {@link ArrayHeading}
 * exists at all. ATAK's thresholds, copied rather than invented:
 * {@code GPS_BEARING_SPEED = 0.44704 * 5}, five miles an hour, and
 * {@code VALID_TIME = 5 * 60000}, five minutes of hold after the last time the
 * average was above it. ATAK puts a "driving" widget on screen for the
 * operator while it is holding; this says so on the button instead.
 */
public final class VehicleHeading {

    /**
     * Below this average speed a GPS course is noise, in meters per second.
     *
     * <p>ATAK's {@code SpeedComputer.GPS_BEARING_SPEED}, to the digit:
     * {@code 0.44704 * 5}, five miles an hour. It is the speed at which ATAK
     * itself stops believing the magnetometer and starts believing the GPS.
     */
    public static final double MIN_SPEED_MPS = 0.44704 * 5;

    /**
     * How long a course stays good after the vehicle stops, milliseconds.
     *
     * <p>ATAK's {@code SpeedComputer.VALID_TIME}: five minutes. Long enough to
     * cover a traffic light, a gate, or parking up to listen for a while,
     * which is a normal thing to do while hunting a signal. Matching ATAK
     * rather than picking a number means the plugin holds a heading for
     * exactly as long as the self marker's own arrow is trusted.
     */
    public static final long HOLD_MS = 5 * 60_000L;

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

    /** ATAK's 30-sample average GPS speed, or NaN when it has none. */
    private static double averageSpeed(Marker self) {
        double v = self.getMetaDouble("avgSpeed30", Double.NaN);
        if (!Double.isNaN(v))
            return v;
        // Before the first average, the instantaneous value is all there is.
        return self.getMetaDouble("Speed", Double.NaN);
    }

    /**
     * The vehicle's heading in degrees true, or null when nothing recent
     * enough is known.
     *
     * <p>Reads on demand rather than on a timer: every caller asks while
     * handling a frame or redrawing the pane, both about once a second, and a
     * timer would be a second thing to stop on plugin unload for no gain.
     */
    public Double degrees() {
        Marker self = mapView == null ? null : mapView.getSelfMarker();
        if (self != null) {
            double heading = self.getTrackHeading();
            if (!Double.isNaN(heading)
                    && averageSpeed(self) >= MIN_SPEED_MPS) {
                lastGoodDeg = heading;
                lastGoodAt = android.os.SystemClock.elapsedRealtime();
            }
            // ATAK's own verdict, which carries its hold. It is not maintained
            // when the map is in magnetic-up mode, so it can only extend our
            // own answer, never shorten it.
            if (lastGoodAt > 0 && self.getMetaBoolean("driving", false))
                return Double.valueOf(lastGoodDeg);
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
        double v = averageSpeed(self);
        return Double.isNaN(v) || v < MIN_SPEED_MPS;
    }

    /** How long the held answer has been held, in seconds; 0 when moving. */
    public long heldSeconds() {
        if (!isHeld() || lastGoodAt < 0)
            return 0;
        return (android.os.SystemClock.elapsedRealtime() - lastGoodAt) / 1000L;
    }
}

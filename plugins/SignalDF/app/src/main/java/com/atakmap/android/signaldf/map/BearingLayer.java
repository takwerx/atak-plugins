package com.atakmap.android.signaldf.map;

import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;

import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.android.drawing.mapItems.DrawingShape;
import com.atakmap.android.signaldf.data.Age;
import com.atakmap.android.signaldf.data.ScaleBar;
import com.atakmap.android.signaldf.data.Units;
import com.atakmap.android.signaldf.model.ArrayHeading;
import com.atakmap.android.signaldf.model.Bearing;
import com.atakmap.coremap.maps.coords.GeoCalculations;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.coremap.maps.coords.GeoPointMetaData;
import com.atakmap.map.layer.feature.Feature;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The bearing on the map: one line per VFO, from the receiver out along the
 * true bearing.
 *
 * <p>0.1 draws exactly this and nothing else. No power lobe, no averaged
 * bearing, no breadcrumbs, no fix -- the line has to be provably right before
 * anything built on top of it means anything.
 *
 * <p><b>Clamped to the ground, always.</b> A bearing carries no altitude of its
 * own, so the endpoints are built without one; drawn at an inherited altitude
 * the line sinks under higher terrain and disappears as the operator zooms in,
 * which reads as the plugin having stopped.
 *
 * <p><b>Its own map group, not ATAK's drawing group.</b> These lines rewrite
 * themselves about once a second. In {@code DrawingToolsMapComponent}'s group
 * they would fill the operator's own drawing list and persist across restarts,
 * so they live in a group of their own, are never archived and are never
 * persisted. Nothing here leaves the phone: a bearing is local until 0.4 adds
 * explicit sharing, because a published CoT lands on every phone in the fleet
 * while a local overlay lands on none.
 *
 * <p><b>Length follows the map, and is recomputed only when a frame arrives.</b>
 * A fixed five-kilometer line reads as a measurement of where the emitter is,
 * which it is not; scaling it to the scale bar keeps it a direction. The
 * recompute deliberately does not hang off map movement: {@code onMapMoved}
 * runs on the GL thread, and touching a map item there is a native crash with
 * no Java stack trace. At a poll a second the line catches up with a zoom
 * within a frame, which is cheap and cannot crash.
 *
 * <p><b>Stale lines stop looking live.</b> A bearing from two minutes ago draws
 * the same line as one from this second, so a timer grays the line and puts the
 * age in its title once the link has gone quiet. This is the same class of
 * failure as a silent HTTP 204: the picture stays plausible while the fact
 * behind it has gone.
 */
public final class BearingLayer {


    /**
     * The UID a bearing line carries, locally and when shared. One UID, so an
     * echo of a shared bearing lands on the line that is already there:
     * ATAK resolves an incoming event against the whole root group by UID.
     */
    public static final String UID_PREFIX = "signaldf.bearing.";

    /** Bright enough to read over imagery; distinct from ATAK's own lines. */
    private static final int LIVE_COLOR = Color.rgb(0x00, 0xE5, 0xFF);
    private static final int STALE_COLOR = Color.rgb(0x80, 0x80, 0x80);

    /**
     * A bearing the radio produced but the quality gate rejected.
     *
     * <p>Drawn, and drawn faint. Weak is not the same as wrong: a
     * low-confidence bearing is a real measurement that happens to be poor,
     * and hiding it would leave the operator staring at an empty map while the
     * radio is plainly hearing something. What it must not do is look like the
     * good ones, or go into anything built on top of it -- the fix and the
     * team's feed both refuse it.
     */
    private static final int WEAK_COLOR = Color.argb(0x70, 0x00, 0xE5, 0xFF);
    private static final double STROKE_WEAK = 1.5;

    private static final double STROKE_LIVE = 3.0;
    private static final double STROKE_STALE = 2.0;

    /** After this long with no new frame the line is no longer current. */
    private static final long STALE_AFTER_MS = 5_000;

    /** How often staleness is re-checked while anything is drawn. */
    private static final long TICK_MS = 1_000;

    /**
     * The line runs this many scale bars from the receiver, clamped. Long
     * enough to leave the screen so it reads as a direction rather than a
     * distance, short enough that it does not wrap the globe when zoomed out.
     */
    private static final double LENGTH_SCALE_BARS = 4.0;
    private static final double MIN_LENGTH_M = 500.0;
    private static final double MAX_LENGTH_M = 40_000.0;

    private final MapView mapView;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<Integer, DrawingShape> lines = new HashMap<>();

    private long lastDrawRealtime = -1;
    private boolean ticking;

    public BearingLayer(MapView mapView) {
        this.mapView = mapView;
    }

    /**
     * Plugin stop. Every line this drew comes off the map.
     *
     * <p>The group itself stays: it is shared with {@link FixLayer} and with
     * Overlay Manager, and ATAK hit-tests by walking the root group's
     * children, so pulling a group out from under live items is how a plugin
     * ends up with markers that cannot be tapped. {@link SignalDfGroup}
     * handles the Overlay Manager row on plugin stop.
     */
    public void dispose() {
        stopTicking();
        clear();
    }

    /** Take every line off the map. */
    public void clear() {
        for (DrawingShape s : new ArrayList<>(lines.values()))
            s.removeFromGroup();
        lines.clear();
        lastDrawRealtime = -1;
        stopTicking();
    }

    /**
     * Draws the newest bearing for each VFO.
     *
     * @param bearings the newest bearing per VFO
     * @param heading  what the array heading is believed to be, and why. When
     *                 it is not {@link ArrayHeading#isKnown()} the line is still
     *                 drawn -- a direction relative to the antenna is real
     *                 information -- but its label says so, because a line on a
     *                 north-up map otherwise reads as a bearing to north.
     * @param fallback the receiver's position when the radio reports none,
     *                 normally the phone's own. Null means do not draw.
     */
    public void draw(List<Bearing> bearings, ArrayHeading heading, GeoPoint fallback) {
        draw(bearings, heading, fallback, Double.NaN, Double.NaN);
    }

    /** As above, dimming anything the quality gate would reject. */
    public void draw(List<Bearing> bearings, ArrayHeading heading,
            GeoPoint fallback, double minConfidence, double minPowerDb) {
        if (bearings == null || bearings.isEmpty()) {
            clear();
            return;
        }

        // No heading, no line. Operator's call, 2026-09-28, and the right one.
        //
        // This used to draw the bearing anyway, treating the array's zero as
        // north and labeling the line "47 deg rel" so nobody was lied to in
        // writing. But a line on a map is a claim about a direction on the
        // earth, and that one is wrong by however far the array is turned --
        // pointing at the exact opposite of the transmitter when the vehicle
        // happens to be heading south. Four small words beside it are no
        // defense against a glance at a screen while driving, and the whole
        // reason ArrayHeading is a type rather than a double is that a wrong
        // heading never looks wrong.
        //
        // The relative bearing is still real information and the pane still
        // lists it. It is only the map that stays empty, and the heading line
        // above the list says why and what to do about it.
        if (!heading.isKnown()) {
            clear();
            return;
        }

        final double lengthM = lineLength();
        final List<Integer> drawn = new ArrayList<>(bearings.size());

        for (Bearing b : bearings) {
            GeoPoint from = receiverPoint(b, fallback);
            if (from == null)
                continue;
            double trueDeg = b.trueBearing(heading.degrees());
            GeoPoint to = GeoCalculations.pointAtDistance(from, trueDeg, lengthM);
            if (to == null)
                continue;
            drawLine(b, from, to, trueDeg, heading,
                    com.atakmap.android.signaldf.data.Quality.usable(
                            b, minConfidence, minPowerDb));
            drawn.add(b.vfo);
        }

        // A VFO the radio has stopped reporting must not leave its last bearing
        // on the map looking current.
        for (Integer vfo : new ArrayList<>(lines.keySet())) {
            if (!drawn.contains(vfo)) {
                DrawingShape s = lines.remove(vfo);
                if (s != null)
                    s.removeFromGroup();
            }
        }

        if (drawn.isEmpty()) {
            lastDrawRealtime = -1;
            stopTicking();
            return;
        }
        lastDrawRealtime = android.os.SystemClock.elapsedRealtime();
        startTicking();
    }

    /**
     * Where the bearing starts.
     *
     * <p>The radio's own position first -- it is the thing that took the
     * measurement -- and the phone's only as a fallback, which is right on a
     * vehicle where both are bolted to the same truck and wrong nowhere that
     * 0.1 supports. Null when neither is available, and then nothing is drawn
     * rather than a line from a guess.
     */
    public static GeoPoint receiverPoint(Bearing b, GeoPoint fallback) {
        if (b.positionReported)
            return new GeoPoint(b.latitude, b.longitude);
        return fallback;
    }

    /** The self marker's point, or null if ATAK has no position yet. */
    public static GeoPoint selfPoint(MapView mapView) {
        Marker self = mapView.getSelfMarker();
        if (self == null)
            return null;
        GeoPoint p = self.getPoint();
        return p != null && p.isValid() ? p : null;
    }

    private double lineLength() {
        double bar = ScaleBar.meters(mapView);
        if (bar <= 0 || Double.isNaN(bar))
            bar = MIN_LENGTH_M;
        return Math.max(MIN_LENGTH_M, Math.min(MAX_LENGTH_M, bar * LENGTH_SCALE_BARS));
    }

    private void drawLine(Bearing b, GeoPoint from, GeoPoint to, double trueDeg,
            ArrayHeading heading, boolean good) {
        DrawingShape shape = lines.get(b.vfo);
        if (shape == null) {
            shape = new DrawingShape(mapView, group(), UID_PREFIX + b.vfo);
            shape.setClosed(false);
            // See the class comment: no altitude on a bearing, so no altitude
            // on the line, or it sinks under terrain on zoom.
            shape.setAltitudeMode(Feature.AltitudeMode.ClampToGround);
            shape.setMetaBoolean("archive", false);
            shape.setMetaBoolean("editable", false);
            shape.setMovable(false);
            lines.put(b.vfo, shape);
        }

        List<GeoPointMetaData> pts = new ArrayList<>(2);
        pts.add(GeoPointMetaData.wrap(from));
        pts.add(GeoPointMetaData.wrap(to));
        shape.setPoints(pts, new android.util.SparseArray<com.atakmap.android.maps.PointMapItem>());
        shape.setStrokeColor(good ? LIVE_COLOR : WEAK_COLOR);
        shape.setStrokeWeight(good ? STROKE_LIVE : STROKE_WEAK);
        shape.setTitle(label(b, trueDeg, heading) + (good ? "" : "  weak"));

        if (shape.getGroup() == null)
            group().addItem(shape);
    }

    /**
     * What the line calls itself. It names the frequency, because two VFOs are
     * two emitters and never one; and it says "rel" when the heading is
     * unknown, because a number of degrees on a north-up map otherwise reads as
     * a bearing to north when it is a bearing to an antenna.
     */
    private String label(Bearing b, double trueDeg, ArrayHeading heading) {
        String deg = heading.isKnown()
                ? String.format(Locale.US, "%.0f deg", trueDeg)
                : String.format(Locale.US, "%.0f deg rel", b.arrayRelativeDeg);
        return String.format(Locale.US, "%.4f MHz  %s", b.frequencyMHz(), deg);
    }

    private MapGroup group() {
        return SignalDfGroup.get(mapView);
    }

    // ---- staleness ---------------------------------------------------------

    private void startTicking() {
        if (ticking)
            return;
        ticking = true;
        handler.postDelayed(tick, TICK_MS);
    }

    private void stopTicking() {
        ticking = false;
        handler.removeCallbacks(tick);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!ticking)
                return;
            applyStaleness();
            handler.postDelayed(this, TICK_MS);
        }
    };

    /**
     * Greys a line and puts its age in the title once the frame behind it is
     * old. The line is not removed: the last known direction is still worth
     * seeing, as long as nothing about it claims to be current.
     */
    private void applyStaleness() {
        if (lastDrawRealtime < 0 || lines.isEmpty())
            return;
        long age = android.os.SystemClock.elapsedRealtime() - lastDrawRealtime;
        if (age < STALE_AFTER_MS)
            return;
        for (DrawingShape s : lines.values()) {
            s.setStrokeColor(STALE_COLOR);
            s.setStrokeWeight(STROKE_STALE);
            String title = s.getTitle();
            if (title == null)
                continue;
            int cut = title.indexOf("  (");
            if (cut > 0)
                title = title.substring(0, cut);
            s.setTitle(title + String.format(Locale.US, "  (%s old)", Age.format(age)));
        }
    }

    /** For the pane: how the line's length is being chosen, in words. */
    public String lengthDescription() {
        return "Line length " + Units.format(lineLength()) + ", scaled to the map";
    }
}

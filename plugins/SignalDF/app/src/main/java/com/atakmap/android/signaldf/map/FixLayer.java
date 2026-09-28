package com.atakmap.android.signaldf.map;

import android.content.Context;
import android.graphics.Color;

import com.atakmap.android.drawing.mapItems.DrawingShape;
import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.android.signaldf.data.Angles;
import com.atakmap.android.signaldf.data.SampleLog;
import com.atakmap.android.icons.UserIcon;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.coremap.maps.coords.GeoPointMetaData;
import com.atakmap.map.layer.feature.Feature;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The answer on the map: where the transmitter is, how sure that is, where the
 * bearings were taken from, and where to drive next.
 *
 * <p>Four things are drawn and each one answers a question the others cannot:
 *
 * <ul>
 * <li><b>The fix</b> -- a marker on the estimate. "It is here."
 * <li><b>The 95% ellipse</b> -- a translucent polygon round it. "This sure."
 *     Drawn always, never suppressed when it is embarrassingly large, because
 *     a marker on its own is a claim of precision nobody made.
 * <li><b>The collection track</b> -- a dot at every position a bearing was
 *     kept from, colored by received power. This is the coverage picture: the
 *     arc you have driven is visible and so, by its absence, is the arc you
 *     have not. KrakenRF's app has no equivalent -- its grid shows where the
 *     signal points, not where you have been.
 * <li><b>The next move</b> -- a waypoint across the long axis of the ellipse.
 *     The fix's own advice in map form.
 * </ul>
 *
 * <p>Everything is clamped to the ground. A shape carrying altitude sinks
 * under terrain the moment somebody zooms, and none of this has an altitude
 * that means anything: a bearing is a direction and a fix is a place on a map.
 *
 * <p>One set per VFO, because two frequencies are two emitters and a fix that
 * averaged them would sit between two real transmitters, where nothing is.
 */
public final class FixLayer {

    private static final String UID_FIX = "signaldf.fix.";
    private static final String UID_ELLIPSE = "signaldf.fix.ellipse.";
    private static final String UID_TRACK = "signaldf.track.";
    private static final String UID_TRACE = "signaldf.trace.";
    private static final String UID_NEXT = "signaldf.next.";

    /**
     * Both markers are ATAK <b>spot map</b> markers, {@code b-m-p-s-m}, which
     * is what ATAK's own point dropper makes.
     *
     * <p>The operator asked for them by name, and the reason is the one that
     * matters in a truck: a spot marker carries ATAK's whole marker menu, so
     * Bloodhound will navigate to it. A custom icon on a custom type looks
     * better and does nothing, and the thing an operator wants to do with
     * "the transmitter is here" and "drive here" is drive to them.
     *
     * <p>The colours are ATAK's own palette, to the digit, so they match what
     * the point dropper's swatches produce:
     * {@code SpotMapPalletFragment} sets orange as {@code argb(255,255,119,0)}
     * and magenta as {@code Color.MAGENTA}. Orange reads on every basemap,
     * which is why the operator picked it for the waypoint. Magenta for the
     * fix because it is the same colour as its own error ellipse -- marker and
     * uncertainty read as one object -- and because ATAK uses it for nothing
     * else, so it cannot be confused with a hostile track, an alert or a
     * route. Red would also read as "the target" and is left free for things
     * that actually are one.
     */
    private static final String SPOT_TYPE = "b-m-p-s-m";
    private static final String SPOT_ICONSET = "COT_MAPPING_SPOTMAP";

    private static final int FIX_COLOR = Color.MAGENTA;
    private static final int ELLIPSE_STROKE = Color.argb(0xC0, 0xFF, 0x3D, 0xD7);
    private static final int ELLIPSE_FILL = Color.argb(0x33, 0xFF, 0x3D, 0xD7);
    /** ATAK's own spot orange: argb(255, 255, 119, 0). */
    private static final int NEXT_COLOR = Color.argb(255, 255, 119, 0);

    /**
     * How many collected bearings are drawn behind the live one.
     *
     * <p>Forty. Enough to see the fan converge and to spot the one line that
     * misses the crowd -- a bearing that arrived off a hillside or a metal
     * building rather than from the transmitter, which is the single biggest
     * source of wrong answers in direction finding and which no number on the
     * screen can point at. Few enough that the map does not turn to spaghetti:
     * half an hour of driving keeps around five hundred samples, and five
     * hundred lines is a picture of nothing.
     *
     * <p>Deliberately not a control. The operator has been clear about the
     * cost of a screen full of settings, and a cap nobody has complained about
     * does not need one.
     */
    private static final int TRACE_MAX = 40;

    /** Faintest and brightest a trace line gets, as an alpha. */
    private static final int TRACE_ALPHA_OLD = 0x22;
    private static final int TRACE_ALPHA_NEW = 0x8C;

    /**
     * How far past the fix a trace line is drawn, as a multiple of its own
     * range to it.
     *
     * <p>A quarter over, so the lines visibly cross rather than stopping at
     * the crossing point in a way that looks deliberate. Lines that all end
     * exactly at a point read as a drawing of a point; lines that pass
     * through it read as evidence for it.
     */
    private static final double TRACE_OVERSHOOT = 1.25;

    /** Points around the ellipse. Enough that it never reads as a polygon. */
    private static final int ELLIPSE_POINTS = 48;

    /**
     * How far to send the operator, as a fraction of the fix's long axis.
     *
     * <p>Half. The crossing angle gained by moving {@code d} perpendicular to
     * a target at range {@code R} is about {@code d/R}, so the useful distance
     * <b>scales with range</b> -- two kilometers is decisive against a
     * transmitter a kilometer away and nearly worthless against one ten
     * kilometers off. The ellipse's long axis is the best range proxy
     * available, and half of it buys roughly thirty degrees of crossing, which
     * turns a cigar into something round.
     */
    private static final double NEXT_FRACTION = 0.5;

    /**
     * And clamped, because the operator has to be able to drive there.
     *
     * <p>Nothing under 500 m is worth a detour; nothing over 5 km is a
     * suggestion somebody will follow. The waypoint is a hint rather than a
     * survey point -- it will land in a field or a reservoir as often as not,
     * and turn-by-turn will route to the nearest road regardless, so precision
     * here is wasted effort.
     */
    private static final double NEXT_MAX_M = 5000.0;

    /** And never closer than this, or the waypoint lands under the truck. */
    private static final double NEXT_MIN_M = 500.0;

    /**
     * Close enough to count as having got there, meters.
     *
     * <p>Arriving is what makes a waypoint stale: the whole reason it was
     * there was that nothing had been sampled from that side, and standing on
     * it fixes that.
     */
    private static final double NEXT_REACHED_M = 250.0;

    /**
     * How far the fix must move before the waypoint is reconsidered, meters.
     *
     * <p><b>A waypoint that moves while you drive toward it is worse than no
     * waypoint.</b> The first version recomputed this from scratch on every
     * frame, and it had four separate reasons to jump: the fix moves, the
     * ellipse changes length, its axis rotates, and the side-of-the-line
     * choice flips as the vehicle drives past. The operator watched it
     * wandering about the map within a minute of the demo starting.
     *
     * <p>So it is placed once and held. It is only reconsidered when the
     * picture has actually changed -- the fix has jumped half a kilometer, or
     * the vehicle has arrived, or the geometry has come good and no waypoint
     * is needed at all. Somewhere slightly sub-optimal that stays put is worth
     * far more to somebody driving than the ideal spot recomputed every
     * second.
     */
    private static final double NEXT_RECOMPUTE_M = 500.0;

    private final MapView mapView;
    private final Context plugin;
    private final Map<Integer, Marker> fixes = new HashMap<>();
    private final Map<Integer, DrawingShape> ellipses = new HashMap<>();
    private final Map<Integer, DrawingShape> tracks = new HashMap<>();
    private final Map<String, DrawingShape> traces = new HashMap<>();
    /** Sample count each VFO's trace was last built at. */
    private final Map<Integer, Integer> traceBuiltAt = new HashMap<>();
    private final Map<Integer, Marker> nexts = new HashMap<>();
    /** Where the fix was when each waypoint was placed. */
    private final Map<Integer, GeoPoint> nextAnchors = new HashMap<>();


    public FixLayer(MapView mapView, Context pluginContext) {
        this.mapView = mapView;
        this.plugin = pluginContext;
    }

    /**
     * Make a marker one of ATAK's spot map markers, in a colour.
     *
     * <p>Three pieces, and all three are needed: the type, so ATAK treats it
     * as a spot and gives it the marker menu Bloodhound reaches through; the
     * iconset path, which is where ATAK looks up the glyph; and the colour
     * metadata, which is what tints it. Setting an {@code Icon} instead loses
     * to ATAK's own rendering for a typed marker, which is how the fix spent
     * an afternoon drawing as a plain crosshair.
     */
    private static void spot(Marker m, int color) {
        m.setType(SPOT_TYPE);
        m.setMetaString(UserIcon.IconsetPath,
                SPOT_ICONSET + "/" + SPOT_TYPE + "/" + color);
        m.setMetaInteger("color", color);
    }

    public void dispose() {
        clear();
    }

    /** Take everything off the map. */
    public void clear() {
        removeAll(fixes);
        removeAll(ellipses);
        removeAll(tracks);
        removeAll(nexts);
        nextAnchors.clear();
        for (DrawingShape sh : traces.values())
            if (sh != null)
                sh.removeFromGroup();
        traces.clear();
        traceBuiltAt.clear();
    }

    /** Take one VFO's answer off the map, leaving the others. */
    public void clear(int vfo) {
        remove(fixes, vfo);
        remove(ellipses, vfo);
        remove(tracks, vfo);
        remove(nexts, vfo);
        nextAnchors.remove(vfo);
        clearTrace(vfo);
    }

    /**
     * Draw one emitter's answer.
     *
     * @param vfo    which emitter
     * @param label  what to call it on the map, normally the frequency
     * @param log    the bearings collected, for the coverage track
     * @param result the fix, or null when there is not one yet -- the track is
     *               still drawn, because "here is everywhere you have looked
     *               and it is not enough yet" is worth seeing
     */
    public void draw(int vfo, String label, SampleLog log,
            SampleLog.Result result) {
        drawTrack(vfo, log);
        drawTrace(vfo, log, result);
        if (result == null) {
            remove(fixes, vfo);
            remove(ellipses, vfo);
            remove(nexts, vfo);
            return;
        }

        GeoPoint at = new GeoPoint(result.lat, result.lon);
        double[] e = result.fix.ellipse();
        drawEllipse(vfo, at, e[0], e[1], e[2]);
        drawFix(vfo, label, at, result);
        drawNext(vfo, at, e, log);
    }

    // ---- the fix ------------------------------------------------------------

    private void drawFix(int vfo, String label, GeoPoint at,
            SampleLog.Result r) {
        Marker m = fixes.get(vfo);
        if (m == null) {
            m = new Marker(at, UID_FIX + vfo);
            spot(m, FIX_COLOR);
            m.setMetaBoolean("archive", false);
            m.setMetaBoolean("editable", false);
            m.setMovable(false);
            m.setMetaBoolean("removable", true);
            fixes.put(vfo, m);
        }
        m.setPoint(at);
        m.setTitle(label);
        // The number an operator acts on is how big the uncertainty is, so it
        // goes in the callsign where the map shows it without a tap.
        m.setMetaString("callsign", String.format(Locale.US, "%s  +/- %s",
                label, com.atakmap.android.signaldf.data.Units.format(
                        r.fix.accuracyM())));
        StringBuilder remarks = new StringBuilder(String.format(Locale.US,
                "Signal DF fix from %d bearings.\n"
                        + "95%% ellipse %s by %s, long axis %03.0f.\n"
                        + "Bearings span %.0f degrees.",
                r.fix.count,
                com.atakmap.android.signaldf.data.Units.format(
                        r.fix.ellipse()[0]),
                com.atakmap.android.signaldf.data.Units.format(
                        r.fix.ellipse()[1]),
                r.fix.ellipse()[2], r.fix.spreadDeg));
        String advice = r.fix.advice();
        if (advice != null)
            remarks.append('\n').append(advice);
        // A marker saying "the transmitter is here, within 200 m" is a far
        // stronger claim than a line, and the ellipse is a claim about how
        // wrong it can be -- which is exactly what is not yet known.
        remarks.append("\n\n").append(
                com.atakmap.android.signaldf.data.Caveat.UNVERIFIED_BEARING);
        m.setMetaString("remarks", remarks.toString());
        if (m.getGroup() == null) {
            group().addItem(m);
            com.atakmap.coremap.log.Log.d("SignalDF.FixLayer",
                    "fix marker on the map at " + at.getLatitude() + ","
                            + at.getLongitude() + " icon="
                            + (m.getIcon() != null));
        }
    }

    // ---- the uncertainty ----------------------------------------------------

    private void drawEllipse(int vfo, GeoPoint at, double semiMajorM,
            double semiMinorM, double majorDeg) {
        DrawingShape s = ellipses.get(vfo);
        if (s == null) {
            s = new DrawingShape(mapView, group(), UID_ELLIPSE + vfo);
            s.setClosed(true);
            s.setAltitudeMode(Feature.AltitudeMode.ClampToGround);
            s.setMetaBoolean("archive", false);
            s.setMetaBoolean("editable", false);
            s.setMovable(false);
            ellipses.put(vfo, s);
        }

        List<GeoPointMetaData> pts = new ArrayList<>(ELLIPSE_POINTS);
        double rot = Math.toRadians(majorDeg);
        for (int i = 0; i < ELLIPSE_POINTS; i++) {
            double t = 2 * Math.PI * i / ELLIPSE_POINTS;
            // In the ellipse's own frame: u along the major axis, v across.
            double u = semiMajorM * Math.cos(t);
            double v = semiMinorM * Math.sin(t);
            // Rotate into east/north. majorDeg is a compass azimuth, so the
            // major axis direction is (sin, cos) in east/north.
            double east = u * Math.sin(rot) + v * Math.cos(rot);
            double north = u * Math.cos(rot) - v * Math.sin(rot);
            double range = Math.hypot(east, north);
            double az = Math.toDegrees(Math.atan2(east, north));
            GeoPoint p = com.atakmap.coremap.maps.coords.GeoCalculations
                    .pointAtDistance(at, az, range);
            if (p != null)
                pts.add(GeoPointMetaData.wrap(p));
        }
        if (pts.size() < 3)
            return;

        s.setPoints(pts, new android.util.SparseArray<com.atakmap.android.maps.PointMapItem>());
        s.setStrokeColor(ELLIPSE_STROKE);
        s.setFillColor(ELLIPSE_FILL);
        s.setStrokeWeight(2.0);
        s.setTitle("95% area");
        if (s.getGroup() == null) {
            group().addItem(s);
            com.atakmap.coremap.log.Log.d("SignalDF.FixLayer",
                    "ellipse on the map, " + Math.round(semiMajorM) + " by "
                            + Math.round(semiMinorM) + " m");
        }
    }

    // ---- where you have been ------------------------------------------------

    /**
     * Every position a bearing was kept from, as one line.
     *
     * <p>Deliberately a single polyline rather than a dot per sample. A
     * hundred markers is a hundred map items to hit-test on every touch and it
     * buries the fix in clutter; the shape of the drive is what carries the
     * information, and a line carries it.
     */
    private void drawTrack(int vfo, SampleLog log) {
        List<SampleLog.Sample> samples = log == null ? null : log.samples();
        if (samples == null || samples.size() < 2) {
            remove(tracks, vfo);
            return;
        }
        DrawingShape s = tracks.get(vfo);
        if (s == null) {
            s = new DrawingShape(mapView, group(), UID_TRACK + vfo);
            s.setClosed(false);
            s.setAltitudeMode(Feature.AltitudeMode.ClampToGround);
            s.setMetaBoolean("archive", false);
            s.setMetaBoolean("editable", false);
            s.setMovable(false);
            tracks.put(vfo, s);
        }
        List<GeoPointMetaData> pts = new ArrayList<>(samples.size());
        for (SampleLog.Sample sm : samples)
            pts.add(GeoPointMetaData.wrap(new GeoPoint(sm.lat, sm.lon)));
        s.setPoints(pts, new android.util.SparseArray<com.atakmap.android.maps.PointMapItem>());
        s.setStrokeColor(Color.argb(0xB0, 0xFF, 0xC1, 0x07));
        s.setStrokeWeight(2.0);
        s.setTitle(String.format(Locale.US, "collected from %d places",
                samples.size()));
        if (s.getGroup() == null)
            group().addItem(s);
    }

    /**
     * Every collected bearing, drawn faint, fading with age.
     *
     * <p>This is the picture a number cannot give. Each line runs from where
     * it was taken, along the direction the radio reported, far enough to pass
     * through the fix. Good bearings pile up on each other at the transmitter;
     * a bearing that came off a reflection goes somewhere else entirely and is
     * the one line obviously missing the crowd. Seeing which one is wrong, and
     * where it was taken, is what lets an operator learn that the readings by
     * the substation are worthless -- something the ellipse can only report as
     * a slightly larger number.
     *
     * <p>Rebuilt only when a new sample is kept, which the movement gate makes
     * every fifty meters rather than every frame. Forty shapes redrawn once a
     * second would be a rendering cost for a picture that had not changed.
     */
    private void drawTrace(int vfo, SampleLog log, SampleLog.Result result) {
        List<SampleLog.Sample> all = log == null ? null : log.samples();
        if (all == null || all.size() < 2) {
            clearTrace(vfo);
            return;
        }
        Integer builtAt = traceBuiltAt.get(vfo);
        if (builtAt != null && builtAt == all.size())
            return;
        traceBuiltAt.put(vfo, all.size());

        int from = Math.max(0, all.size() - TRACE_MAX);
        int shown = all.size() - from;
        GeoPoint fix = result == null ? null
                : new GeoPoint(result.lat, result.lon);

        for (int i = 0; i < shown; i++) {
            SampleLog.Sample sm = all.get(from + i);
            GeoPoint at = new GeoPoint(sm.lat, sm.lon);
            double len = fix == null ? 8000.0
                    : Math.max(800.0, com.atakmap.coremap.maps.coords
                            .GeoCalculations.distanceTo(at, fix)
                            * TRACE_OVERSHOOT);
            GeoPoint to = com.atakmap.coremap.maps.coords.GeoCalculations
                    .pointAtDistance(at, sm.degTrue, len);
            if (to == null)
                continue;

            String key = vfo + ":" + i;
            DrawingShape sh = traces.get(key);
            if (sh == null) {
                sh = new DrawingShape(mapView, group(), UID_TRACE + key);
                sh.setClosed(false);
                sh.setAltitudeMode(Feature.AltitudeMode.ClampToGround);
                sh.setMetaBoolean("archive", false);
                sh.setMetaBoolean("editable", false);
                sh.setMovable(false);
                // Not tappable, and not in any list. Forty thin lines fanning
                // out from a road is the single biggest thing on the map by
                // area, and leaving them hit-testable meant a tap anywhere
                // near the fan opened ATAK's "Select Item" picker full of
                // signaldf.trace.0:17 rows -- burying the fix marker the
                // operator was actually reaching for. They are a backdrop,
                // not objects: read with the eye, never touched.
                sh.setClickable(false);
                sh.setMetaBoolean("addToObjList", false);
                sh.setMetaBoolean("ignoreOffscreen", true);
                traces.put(key, sh);
            }
            List<GeoPointMetaData> pts = new ArrayList<>(2);
            pts.add(GeoPointMetaData.wrap(at));
            pts.add(GeoPointMetaData.wrap(to));
            sh.setPoints(pts,
                    new android.util.SparseArray<com.atakmap.android.maps.PointMapItem>());
            // Oldest faintest, newest brightest, so the fan reads as a
            // history rather than a tangle.
            double age = shown <= 1 ? 1.0 : (double) i / (shown - 1);
            int alpha = (int) Math.round(TRACE_ALPHA_OLD
                    + age * (TRACE_ALPHA_NEW - TRACE_ALPHA_OLD));
            sh.setStrokeColor(Color.argb(alpha, 0x00, 0xE5, 0xFF));
            sh.setStrokeWeight(1.0);
            sh.setTitle("");
            if (sh.getGroup() == null)
                group().addItem(sh);
        }

        // Anything left over from a longer trace.
        for (int i = shown; i < TRACE_MAX; i++) {
            DrawingShape sh = traces.remove(vfo + ":" + i);
            if (sh != null)
                sh.removeFromGroup();
        }
    }

    private void clearTrace(int vfo) {
        for (int i = 0; i < TRACE_MAX; i++) {
            DrawingShape sh = traces.remove(vfo + ":" + i);
            if (sh != null)
                sh.removeFromGroup();
        }
        traceBuiltAt.remove(vfo);
    }

    // ---- where to go next ---------------------------------------------------

    /**
     * A waypoint across the long axis of the uncertainty.
     *
     * <p>The single most useful thing this plugin can put on a map. An
     * operator with a poor fix does not need to be told the fix is poor; they
     * need somewhere to drive. The ellipse already says which direction is
     * worst known, and moving across that direction is what shortens it -- so
     * the waypoint goes there, at a distance that is a real drive and a real
     * improvement.
     *
     * <p>Placed on whichever side is further from the ground already covered,
     * because driving back down the road you came is the one direction that
     * adds nothing.
     */
    private void drawNext(int vfo, GeoPoint at, double[] e, SampleLog log) {
        if (log == null || log.samples().size() < 2) {
            remove(nexts, vfo);
            nextAnchors.remove(vfo);
            return;
        }
        // Only worth suggesting while the geometry is actually poor.
        if (e[1] > 0 && e[0] / e[1] < 3.0) {
            remove(nexts, vfo);
            nextAnchors.remove(vfo);
            return;
        }
        SampleLog.Sample last = log.samples().get(log.samples().size() - 1);
        GeoPoint from = new GeoPoint(last.lat, last.lon);

        // Held, not recomputed, and no longer anchored to the fix at all.
        // Arriving is the only thing that makes it stale: the reason it was
        // there was that nothing had been sampled from that side, and standing
        // on it fixes that. The fix moving does NOT invalidate it -- early in
        // a search the fix slides kilometers along its own cigar between
        // updates, and a waypoint that followed it would re-route Bloodhound
        // every few seconds.
        Marker held = nexts.get(vfo);
        if (held != null
                && com.atakmap.coremap.maps.coords.GeoCalculations
                        .distanceTo(from, held.getPoint()) > NEXT_REACHED_M)
            return;

        // Built on the bearing direction, which is pinned from the first few
        // samples, rather than on the fix's position, which is not.
        double meanBearing = log.meanBearingDeg(20);
        if (Double.isNaN(meanBearing)) {
            remove(nexts, vfo);
            nextAnchors.remove(vfo);
            return;
        }
        double dist = Math.max(NEXT_MIN_M,
                Math.min(NEXT_MAX_M, e[0] * NEXT_FRACTION));

        // Either perpendicular opens the crossing angle equally well, so take
        // the one nearer the way the vehicle is already pointing: no U-turn
        // when a turn will do.
        double travel = log.travelBearingDeg();
        double left = meanBearing - 90.0, right = meanBearing + 90.0;
        double across = right;
        if (!Double.isNaN(travel)) {
            double dl = Math.abs(Angles.diff180(travel, left));
            double dr = Math.abs(Angles.diff180(travel, right));
            across = dl < dr ? left : right;
        }

        GeoPoint pick = com.atakmap.coremap.maps.coords.GeoCalculations
                .pointAtDistance(from, across, dist);
        if (pick == null) {
            remove(nexts, vfo);
            nextAnchors.remove(vfo);
            return;
        }
        nextAnchors.put(vfo, from);

        Marker m = nexts.get(vfo);
        if (m == null) {
            m = new Marker(pick, UID_NEXT + vfo);
            spot(m, NEXT_COLOR);
            m.setMetaBoolean("archive", false);
            m.setMetaBoolean("editable", false);
            m.setMovable(false);
            nexts.put(vfo, m);
        }
        m.setPoint(pick);
        m.setTitle("Drive here");
        m.setMetaString("callsign", "Drive here");
        m.setMetaString("remarks", String.format(Locale.US,
                "Signal DF: drive here to tighten the fix. It is %s away "
                        + "across your bearings, which all run about %03.0f. "
                        + "Anywhere near it will do -- the point is to get off "
                        + "the line you have been on, and the roads decide the "
                        + "rest. This waypoint stays put until you reach it.",
                com.atakmap.android.signaldf.data.Units.format(dist),
                meanBearing));
        if (m.getGroup() == null)
            group().addItem(m);
    }

    // ---- plumbing -----------------------------------------------------------

    private MapGroup group() {
        return SignalDfGroup.get(mapView);
    }

    private static <T extends com.atakmap.android.maps.MapItem> void remove(
            Map<Integer, T> m, int vfo) {
        T item = m.remove(vfo);
        if (item != null)
            item.removeFromGroup();
    }

    private static <T extends com.atakmap.android.maps.MapItem> void removeAll(
            Map<Integer, T> m) {
        for (T item : m.values())
            if (item != null)
                item.removeFromGroup();
        m.clear();
    }
}

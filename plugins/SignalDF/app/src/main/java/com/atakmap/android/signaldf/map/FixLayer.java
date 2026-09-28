package com.atakmap.android.signaldf.map;

import android.content.Context;
import android.graphics.Color;

import com.atakmap.android.drawing.mapItems.DrawingShape;
import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
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

    /** Points around the ellipse. Enough that it never reads as a polygon. */
    private static final int ELLIPSE_POINTS = 48;

    /**
     * How far along the suggested heading the next waypoint is placed, as a
     * fraction of the ellipse's long axis.
     *
     * <p>It has to be somewhere a vehicle can plausibly get to and far enough
     * that going there actually changes the crossing angle. Three quarters of
     * the current uncertainty is both: if the fix is a two kilometer cigar,
     * moving fifteen hundred meters across it is a real improvement and a
     * couple of minutes' drive.
     */
    private static final double NEXT_FRACTION = 0.75;

    /** And never closer than this, or the waypoint lands under the truck. */
    private static final double NEXT_MIN_M = 400.0;

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
    }

    /** Take one VFO's answer off the map, leaving the others. */
    public void clear(int vfo) {
        remove(fixes, vfo);
        remove(ellipses, vfo);
        remove(tracks, vfo);
        remove(nexts, vfo);
        nextAnchors.remove(vfo);
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

        // Held, not recomputed. See NEXT_RECOMPUTE_M.
        GeoPoint pick = null;
        Marker held = nexts.get(vfo);
        GeoPoint anchor = nextAnchors.get(vfo);
        if (held != null && anchor != null) {
            boolean arrived = com.atakmap.coremap.maps.coords.GeoCalculations
                    .distanceTo(from, held.getPoint()) < NEXT_REACHED_M;
            boolean moved = com.atakmap.coremap.maps.coords.GeoCalculations
                    .distanceTo(anchor, at) > NEXT_RECOMPUTE_M;
            if (!arrived && !moved)
                return;
        }

        double dist = Math.max(NEXT_MIN_M, e[0] * NEXT_FRACTION);
        double across = e[2] + 90.0;

        GeoPoint a = com.atakmap.coremap.maps.coords.GeoCalculations
                .pointAtDistance(at, across, dist);
        GeoPoint b = com.atakmap.coremap.maps.coords.GeoCalculations
                .pointAtDistance(at, across + 180.0, dist);
        if (a == null || b == null) {
            remove(nexts, vfo);
            nextAnchors.remove(vfo);
            return;
        }
        // The side further from everywhere already sampled.
        pick = com.atakmap.coremap.maps.coords.GeoCalculations
                .distanceTo(from, a) > com.atakmap.coremap.maps.coords
                        .GeoCalculations.distanceTo(from, b) ? a : b;
        nextAnchors.put(vfo, at);

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
        m.setMetaString("remarks",
                "Signal DF: the fix is long and thin, and bearings from here "
                        + "would cross the ones you already have. Anywhere "
                        + "along this side of the ellipse will do -- the "
                        + "point is to get off the line you have been on.");
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

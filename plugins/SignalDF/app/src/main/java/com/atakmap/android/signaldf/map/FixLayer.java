package com.atakmap.android.signaldf.map;

import android.content.Context;
import android.graphics.Color;

import com.atakmap.android.drawing.mapItems.DrawingShape;
import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.android.maps.Polyline;
import com.atakmap.android.maps.Shape;
import com.atakmap.android.signaldf.data.Angles;
import com.atakmap.android.signaldf.data.SampleLog;
import com.atakmap.android.util.NotificationUtil;
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
 * The answer on the map: where the transmitter is, how sure that is, where the
 * bearings were taken from, and where to drive next.
 *
 * <p>Four things are drawn and each one answers a question the others cannot:
 *
 * <ul>
 * <li><b>The 95% ellipse</b> -- a translucent polygon on the estimate,
 *     carrying the frequency as its label. It is the whole answer to "where
 *     is it": a marker says "here" and an ellipse says "here, this sure", and
 *     only one of those is a claim anybody made. Drawn always, never
 *     suppressed when it is embarrassingly large.
 * <li><b>The collection track</b> -- a dot at every position a bearing was
 *     kept from, colored by received power. This is the coverage picture: the
 *     arc you have driven is visible and so, by its absence, is the arc you
 *     have not. KrakenRF's app has no equivalent -- its grid shows where the
 *     signal points, not where you have been.
 * <li><b>The band</b> -- the arc still to be driven round the emitter, at
 *     the range already being stood at. Not a waypoint: "anywhere in here"
 *     is the truth, and a dot was a precision the advice never had.
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

    private static final String UID_ELLIPSE = "signaldf.fix.ellipse.";
    private static final String UID_TRACK = "signaldf.track.";
    private static final String UID_TRACE = "signaldf.trace.";
    private static final String UID_BAND = "signaldf.band.";

    /**
     * The fix: ATAK's own magenta, outlined solid, filled at half.
     *
     * <p>Not chosen here. The operator drew the ellipse they wanted with
     * ATAK's drawing tool and left it on the screen -- pure magenta from the
     * palette, the opacity slider on 50%, solid line -- so these are those
     * values and nothing else. Two goes at picking a pink myself both read as
     * washed out over a crowd of bearing lines, which is exactly where it has
     * to hold up.
     */
    private static final int ELLIPSE_STROKE = Color.MAGENTA;
    private static final int ELLIPSE_FILL = Color.argb(0x80, 0xFF, 0x00, 0xFF);

    /**
     * The band, in ATAK's own spot orange: {@code argb(255, 255, 119, 0)}.
     *
     * <p>The operator picked that orange for the old waypoint because it reads
     * on every basemap, and the band inherits it for the same reason. Magenta
     * stays with the ellipse so the answer and its uncertainty read as one
     * object; orange is the thing to go and do about it.
     */
    private static final int BAND_STROKE = Color.argb(0xE0, 0xFF, 0x77, 0x00);
    private static final int BAND_FILL = Color.argb(0x3A, 0xFF, 0x77, 0x00);

    /**
     * Where you have been: lime, deliberately nowhere near the band's orange.
     *
     * <p>The track started out amber, which sat one step from the band on the
     * colour wheel -- so the ground already covered and the ground still to
     * cover were nearly the same colour, which is the single most confusing
     * pairing these two could have had. Lime is far enough round to tell apart
     * at a glance on a moving map, and it is not cyan, so it does not read as
     * one more bearing line either.
     */
    private static final int TRACK_STROKE = Color.argb(0xC8, 0xAE, 0xFF, 0x00);

    /**
     * Labels vanish past this resolution, in meters per pixel.
     *
     * <p>ATAK's default for a polyline is 10, which is about four times zoomed
     * in from where a search is actually driven, so the frequency disappears
     * exactly when the operator pulls back to see the whole picture.
     */
    private static final double LABEL_MAX_RES = 1000.0;

    /** Map label colours, matching the pane's on_green and white. */
    private static final int LABEL_GOOD = Color.argb(255, 0x4C, 0xD9, 0x64);
    private static final int LABEL_WORKING = Color.WHITE;

    /**
     * How much angle the bearings should end up spanning, degrees.
     *
     * <p>This is the one number the whole band is built on, and it is not
     * ours. Radio-telemetry practice calls it <b>angle width</b> -- the angle
     * between the outermost bearings, which for a single receiver driving
     * round an emitter is just the arc it has travelled round it. Haskell and
     * Ballard (2007) found a strong quadratic effect of angle width and
     * recommended 90-100 degrees to maximize accuracy; Bauder and Barnhart
     * (2014) report a median of 105 degrees in the field, from three or four
     * bearings. {@code BearingFix.spreadDeg} already computes exactly this
     * quantity, so the guidance is the published measure rather than a proxy
     * for it.
     */
    private static final double TARGET_SPREAD_DEG =
            com.atakmap.android.signaldf.data.Guidance.TARGET_SPREAD_DEG;

    /**
     * Where the geometry is called good and the operator is told once.
     *
     * <p>The bottom of the same recommended range. Deliberately a statement
     * about the <em>geometry</em>, not about the ellipse: Bauder and Barnhart
     * found that 95% confidence ellipses "rarely contained the true location",
     * consistent with Nams and Boutin (1991) and Withey et al. (2001). So the
     * plugin announces a spread it measured, not a containment it cannot
     * promise.
     */
    private static final double GOOD_SPREAD_DEG =
            com.atakmap.android.signaldf.data.Guidance.GOOD_SPREAD_DEG;

    /**
     * The band runs between these fractions of the current range -- so it sits
     * <b>inside</b> where the vehicle is now, and going to it closes distance
     * as well as swinging angle.
     *
     * <p>The first version put the band at the current range, on the reasoning
     * that moving along a circle centred on the emitter is pure sideways
     * motion and therefore the fastest bearing change per metre. That is true
     * and it is half the problem. Bauder and Barnhart (2014) found that
     * <i>"distance to estimated location had the strongest effect on linear
     * error"</i>, and that angular error is magnified with distance -- while
     * in their own data there was "relatively little support for angle width".
     * The two findings are not in conflict; they are two terms of the same
     * objective, and a band pinned to the range already being stood at
     * optimizes only one of them. The operator spotted it before the
     * literature did: the arc has to close in.
     *
     * <p>So the band is drawn between roughly half and three quarters of the
     * way in. Reaching it shortens the range, which shrinks the next band in
     * turn, and the result over a search is the inward spiral a foxhunter
     * drives by instinct.
     */
    private static final double BAND_INNER = 0.45;
    private static final double BAND_OUTER = 0.75;

    /**
     * How much of the fix's own uncertainty is added to the band's depth.
     *
     * <p>The operator's question: "why doesn't the arc go big right off the
     * bat, showing all the spots I should go?" It should, and the reason it
     * did not is worth writing down. The arc is centred on the emitter,
     * because the thing being improved is the angle <em>as seen from the
     * emitter</em> -- but early in a drive nobody knows where that is. The
     * band was drawn at a fixed fraction of the range to a fix that was still
     * sliding along a thirteen-kilometre ellipse, so it was a confident little
     * arc round a guess.
     *
     * <p>So the band's depth carries the range uncertainty. When the fix is
     * undetermined the ellipse is long, the band is deep, and it honestly
     * covers everywhere worth being. As the fix tightens the band narrows and
     * draws in, which is the shrinking the operator drew. One rule, and the
     * early picture and the late one both fall out of it.
     */
    private static final double BAND_UNCERTAINTY = 0.5;

    /** However uncertain the fix, the band stops here, meters. */
    private static final double BAND_MAX_RADIUS_M = 20000.0;

    /**
     * Below this range to the fix, say nothing, meters.
     *
     * <p>Close in, the band would be a few hundred meters of ring drawn over
     * the truck, and the answer to "where do I drive" stops being a direction
     * and becomes the fix itself.
     */
    private static final double BAND_MIN_RADIUS_M = 150.0;

    /** Never draw a sliver: below this the band is not a place, degrees. */
    private static final double BAND_MIN_ARC_DEG = 10.0;

    /** Polygon resolution along the arc, degrees. */
    private static final double BAND_STEP_DEG = 3.0;

    /**
     * Who draws in front of whom.
     *
     * <p>Forty bearing lines all crossing at the answer will bury the answer,
     * which is what the operator saw: the fix sat under the very lines that
     * produced it. So the stack is stated rather than left to whatever order
     * the items happened to be added in -- the ellipse in front, because it is
     * the thing being looked for; then the band, which is what to do about it;
     * then the track; and the bearing lines at the back, where they belong,
     * since individually they say much less than their crowd does.
     *
     * <p>ATAK's {@code ZORDER_DEFAULT} is 1.0 and its javadoc says only
     * "ascending order", without saying which end is the front. It is the
     * larger number that draws in front: the first attempt here had the signs
     * the other way round and put the ellipse behind the very bearing lines
     * that produced it. Settled by putting it on a phone and looking, which is
     * the only way this was ever going to be settled.
     */
    private static final double Z_ELLIPSE = 30.0;
    private static final double Z_BAND = 20.0;
    private static final double Z_TRACK = 10.0;
    private static final double Z_TRACE = -10.0;

    /** Notification ids, one per VFO. */
    private static final int NOTIFY_BASE_ID = 0x5DF0;

    /**
     * How wide a view the jump-to-the-answer leaves, meters across the screen.
     *
     * <p>The first version sized this at six times the ellipse's long axis,
     * which sounded reasonable and was badly wrong: by the time the geometry
     * comes good that axis is a few tens of meters, so it zoomed into a box
     * the size of a car park. The operator reported the fix "moving around
     * like crazy" -- and measuring it showed the fix moving a median of one
     * metre per frame. Nothing was moving. The map had simply been zoomed in
     * until an unchanged picture filled the screen.
     *
     * <p>So the view is sized to keep the operator and the answer both in it,
     * with a floor that leaves real context. Being told "it is here" is only
     * useful alongside "and you are there".
     */
    private static final double ZOOM_MIN_SPAN_M = 1500.0;
    private static final double ZOOM_MAX_SPAN_M = 15000.0;
    /** Enough margin that neither end sits on the edge of the screen. */
    private static final double ZOOM_OPERATOR_MARGIN = 2.4;
    private static final double ZOOM_ELLIPSE_MARGIN = 8.0;

    /**
     * And a closer look when the operator asks for one.
     *
     * <p>The automatic jump and the "Go to" button want different things. The
     * automatic one fires unasked, so it keeps the operator and the answer
     * both in frame -- being told "it is here" is only useful beside "and you
     * are there". A deliberate tap means the opposite: show me the thing. So
     * this drops the operator out of the sizing entirely and pulls in far
     * closer, down to a couple of hundred meters across.
     */
    private static final double ZOOM_TIGHT_MARGIN = 4.0;

    /**
     * ...but not closer than this, meters across the screen.
     *
     * <p>"As far in as it will fit" is the right rule and it runs out at the
     * bottom: by the time the geometry is good the ellipse is a few tens of
     * meters across, and fitting that to the screen puts the operator in
     * somebody's back garden with no road visible to drive there by. So the
     * fit governs while the ellipse is big and this floor takes over when it
     * is small. 400 m reads as roughly a 300 ft scale bar, which is the view
     * the operator picked off their own screen as the one that works.
     */
    private static final double ZOOM_TIGHT_MIN_SPAN_M = 400.0;
    private static final double ZOOM_TIGHT_MAX_SPAN_M = 6000.0;
    private static final double ZOOM_FALLBACK_RES = 10.0;

    /**
     * How many collected bearings are drawn behind the live one.    /**
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

    private final MapView mapView;
    private final Context plugin;
    private final Map<Integer, DrawingShape> ellipses = new HashMap<>();
    private final Map<Integer, DrawingShape> tracks = new HashMap<>();
    private final Map<String, DrawingShape> traces = new HashMap<>();
    /** Sample count each VFO's trace was last built at. */
    private final Map<Integer, Integer> traceBuiltAt = new HashMap<>();
    private final Map<Integer, DrawingShape> bands = new HashMap<>();
    /**
     * How far each VFO has got through its two announcements: absent or 0 for
     * nothing said, 1 once the first fix was called, 2 once the geometry came
     * good. Reset when the fix goes away, so a new emitter is news again.
     */
    private final Map<Integer, Integer> announced = new HashMap<>();

    /**
     * What the operator wants drawn. Forty bearing lines are what make a
     * reflection visible as the one line missing the crowd, and they are also
     * what buries the answer once the answer is found -- so which of them is
     * on the map is the operator's call, not a fixed decision made here.
     */
    private boolean showLobs = true;
    private boolean showFix = true;

    /** The newest fix per VFO, so "Go to" has somewhere to go. */
    private final Map<Integer, GeoPoint> lastFix = new HashMap<>();
    private final Map<Integer, Double> lastSemiMajor = new HashMap<>();
    private final Map<Integer, GeoPoint> lastOperator = new HashMap<>();

    public FixLayer(MapView mapView, Context pluginContext) {
        this.mapView = mapView;
        this.plugin = pluginContext;
    }

    /** Draw the bearing lines, or do not. */
    public void setShowLobs(boolean on) {
        showLobs = on;
        if (!on) {
            for (DrawingShape sh : traces.values())
                if (sh != null)
                    sh.removeFromGroup();
            traces.clear();
            traceBuiltAt.clear();
        }
    }

    /** Draw the fix ellipse, or do not. */
    public void setShowFix(boolean on) {
        showFix = on;
        if (!on)
            removeAll(ellipses);
    }

    public boolean isShowingLobs() {
        return showLobs;
    }

    public boolean isShowingFix() {
        return showFix;
    }

    /**
     * Take the map to a VFO's fix because the operator asked, rather than
     * because the geometry crossed a threshold. Returns false when there is
     * nothing to go to yet.
     */
    public boolean goToFix(int vfo) {
        GeoPoint at = lastFix.get(vfo);
        if (at == null)
            return false;
        Double semi = lastSemiMajor.get(vfo);
        goTo(at, semi == null ? 0.0 : semi, null, true);
        return true;
    }

    /** The newest fix on any VFO, for a pane with one button. */
    public boolean goToAnyFix() {
        for (Integer vfo : lastFix.keySet())
            if (goToFix(vfo))
                return true;
        return false;
    }

    public void dispose() {
        clear();
    }

    /** Take everything off the map. */
    public void clear() {
        removeAll(ellipses);
        removeAll(tracks);
        removeAll(bands);
        announced.clear();
        lastFix.clear();
        lastSemiMajor.clear();
        lastOperator.clear();
        for (DrawingShape sh : traces.values())
            if (sh != null)
                sh.removeFromGroup();
        traces.clear();
        traceBuiltAt.clear();
    }

    /** Take one VFO's answer off the map, leaving the others. */
    public void clear(int vfo) {
        remove(ellipses, vfo);
        remove(tracks, vfo);
        remove(bands, vfo);
        announced.remove(vfo);
        lastFix.remove(vfo);
        lastSemiMajor.remove(vfo);
        lastOperator.remove(vfo);
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
        if (showLobs)
            drawTrace(vfo, log, result);
        if (result == null) {
            remove(ellipses, vfo);
            remove(bands, vfo);
            announced.remove(vfo);
            return;
        }

        GeoPoint at = new GeoPoint(result.lat, result.lon);
        double[] e = result.fix.ellipse();
        if (showFix)
            drawEllipse(vfo, label, at, result, e[0], e[1], e[2]);
        else
            remove(ellipses, vfo);
        drawBand(vfo, at, e, log, result.fix.spreadDeg);
        List<SampleLog.Sample> ss = log == null ? null : log.samples();
        GeoPoint me = ss == null || ss.isEmpty() ? null
                : new GeoPoint(ss.get(ss.size() - 1).lat,
                        ss.get(ss.size() - 1).lon);
        lastFix.put(vfo, at);
        lastSemiMajor.put(vfo, e[0]);
        lastOperator.put(vfo, me);
        announce(vfo, label, result.fix.spreadDeg, at, e[0], me);
    }

    // ---- the uncertainty ----------------------------------------------------

    private void drawEllipse(int vfo, String label, GeoPoint at,
            SampleLog.Result r, double semiMajorM, double semiMinorM,
            double majorDeg) {        DrawingShape s = ellipses.get(vfo);
        if (s == null) {
            s = new DrawingShape(mapView, group(), UID_ELLIPSE + vfo);
            s.setClosed(true);
            s.setAltitudeMode(Feature.AltitudeMode.ClampToGround);
            s.setMetaBoolean("archive", false);
            s.setMetaBoolean("editable", false);
            s.setMovable(false);
            s.setZOrder(Z_ELLIPSE);
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
        // Close the ring in the geometry as well as in the style. "Closed" is
        // a third Polyline style bit, separate from fill and stroke, and an
        // open polygon does not fill whatever colour it carries -- which is
        // the whole reason two rounds of raising the opacity changed nothing.
        // Repeating the first vertex makes it closed no matter what the bit
        // does.
        pts.add(pts.get(0));

        s.setPoints(pts, new android.util.SparseArray<com.atakmap.android.maps.PointMapItem>());
        s.setStrokeColor(ELLIPSE_STROKE);
        s.setFillColor(ELLIPSE_FILL);
        // Closing the ring is not enough. ATAK keeps stroke and fill as
        // separate style bits and a shape carries only STROKE by default, so
        // setFillColor on its own is stored and never drawn -- which is why
        // raising the alpha from a fifth to a half changed nothing on screen.
        s.addStyleBits(Shape.STYLE_FILLED_MASK | Shape.STYLE_STROKE_MASK
                | Polyline.STYLE_CLOSED_MASK);
        s.setStrokeWeight(3.0);

        // The ellipse is the answer, so the ellipse carries the name. The
        // number an operator acts on is how big the uncertainty is, so it
        // rides in the label where the map shows it without a tap.
        String name = String.format(Locale.US, "%s  +/- %s", label,
                com.atakmap.android.signaldf.data.Units.format(
                        r.fix.accuracyM()));
        s.setTitle(name);
        // The label in the middle, and no dot under it.
        //
        // ATAK hangs a shape's centre label off a centre *marker*, so turning
        // the marker off takes the text with it -- which is what happened on
        // the first go, and cost the frequency and the +/- that the operator
        // reads without tapping anything. Marker.ICON_GONE is the way out: the
        // marker stays, carries the label, and draws no icon and takes no
        // space. The dot mattered because a point in the middle of an ellipse
        // reads as "the transmitter is exactly there", which is the one claim
        // this shape exists to avoid making.
        s.setCenterPointVisible(true);
        s.setCenterPointLabelVisible(true);
        Marker centre = s.getShapeMarker();
        if (centre != null) {
            centre.setIconVisibility(Marker.ICON_GONE);
            centre.setMetaBoolean("removable", false);
            centre.setMovable(false);
            centre.setMetaString("callsign", name);
            // The label carries the state the same way the pane does: white
            // while the angle width is still short of the recommended range,
            // green once it is there. One glance at the map answers "is this
            // one done" without reading a number.
            centre.setTextColor(
                    com.atakmap.android.signaldf.data.Guidance.isOptimal(
                            r.fix.spreadDeg) ? LABEL_GOOD : LABEL_WORKING);
        }
        s.setMinLabelRenderResolution(0.0);
        s.setMaxLabelRenderResolution(LABEL_MAX_RES);

        StringBuilder remarks = new StringBuilder(String.format(Locale.US,
                "Signal DF fix from %d bearings.\n"
                        + "95%% ellipse %s by %s, long axis %03.0f.\n"
                        + "Bearings span %.0f degrees; published practice "
                        + "recommends %.0f.",
                r.fix.count,
                com.atakmap.android.signaldf.data.Units.format(semiMajorM),
                com.atakmap.android.signaldf.data.Units.format(semiMinorM),
                majorDeg, r.fix.spreadDeg, TARGET_SPREAD_DEG));
        String advice = r.fix.advice();
        if (advice != null)
            remarks.append('\n').append(advice);
        // An ellipse saying "the transmitter is in here" is a strong claim,
        // and it is the one the field literature says holds up least well --
        // beacon tests find the true location outside the 95% ellipse more
        // often than not. That belongs with the bearing caveat, not buried.
        remarks.append("\n\nThe ellipse describes the geometry of the "
                + "bearings, not a guarantee the emitter is inside it.");
        remarks.append("\n\n").append(
                com.atakmap.android.signaldf.data.Caveat.UNVERIFIED_BEARING);
        s.setMetaString("remarks", remarks.toString());

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
            s.setZOrder(Z_TRACK);
            tracks.put(vfo, s);
        }
        List<GeoPointMetaData> pts = new ArrayList<>(samples.size());
        for (SampleLog.Sample sm : samples)
            pts.add(GeoPointMetaData.wrap(new GeoPoint(sm.lat, sm.lon)));
        s.setPoints(pts, new android.util.SparseArray<com.atakmap.android.maps.PointMapItem>());
        s.setStrokeColor(TRACK_STROKE);
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
                sh.setZOrder(Z_TRACE);
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

    // ---- where to drive next ------------------------------------------------

    /**
     * The arc still to be covered, drawn where it can be driven.
     *
     * <p><b>Why a band and not a waypoint.</b> The waypoint this replaces
     * picked one point perpendicular to the mean bearing, held it until it was
     * reached, and pointed backwards the moment the bearing swung -- on the
     * first simulated drive it ended up 6.1 km behind the vehicle, recommending
     * exactly the U-turn its own comment said it avoided. The deeper problem
     * was that a dot claims a precision the advice never had. "Anywhere in
     * here, the roads decide the rest" was already what its remarks said, so
     * that is what gets drawn.
     *
     * <p><b>Why an arc.</b> Moving round the emitter is what makes bearings
     * cross. Driving straight at it changes the bearing not at all: every
     * bearing taken from along that line is the same line, and lines lying on
     * each other never cross. So the advice is angular, and any point on the
     * arc serves it equally -- which is why this is a region and not a dot.
     *
     * <p><b>Why it is drawn closer in than the vehicle.</b> See
     * {@link #BAND_INNER}. Range is the strongest single driver of error and
     * angle width is the other, so the band asks for both at once: come in,
     * and swing round while doing it.
     *
     * <p><b>How far round.</b> {@link #TARGET_SPREAD_DEG} less the spread
     * already achieved. The arc shrinks from its near end as the vehicle eats
     * into it, and its radius closes in as the fix tightens, so it is smaller
     * in both senses every time it is redrawn.
     */
    private void drawBand(int vfo, GeoPoint at, double[] e, SampleLog log,
            double spreadDeg) {
        List<SampleLog.Sample> pts = log == null ? null : log.samples();
        if (pts == null || pts.size() < 2 || Double.isNaN(spreadDeg)
                || spreadDeg >= TARGET_SPREAD_DEG) {
            if (bands.containsKey(vfo))
                com.atakmap.coremap.log.Log.d("SignalDF.FixLayer",
                        "band off the map: spread=" + Math.round(spreadDeg)
                                + " of " + Math.round(TARGET_SPREAD_DEG));
            remove(bands, vfo);
            return;
        }
        SampleLog.Sample last = pts.get(pts.size() - 1);
        GeoPoint here = new GeoPoint(last.lat, last.lon);
        double radius = GeoCalculations.distanceTo(at, here);
        if (!(radius > BAND_MIN_RADIUS_M)) {
            if (bands.containsKey(vfo))
                com.atakmap.coremap.log.Log.d("SignalDF.FixLayer",
                        "band off the map: standing on it, "
                                + Math.round(radius) + " m");
            // Standing on the answer. "Where do I drive" stops being a
            // direction at this range and becomes the fix itself.
            remove(bands, vfo);
            return;
        }

        // Which way round the emitter the vehicle is going. Taken from the
        // view angles rather than from the heading, because it is the view
        // angle that opens the spread, and through a close pass the two part
        // company for a while.
        double viewNow = GeoCalculations.bearingTo(at, here);
        double turn = 0.0;
        for (int k = pts.size() - 2; k >= 0 && k > pts.size() - 12; k--) {
            SampleLog.Sample q = pts.get(k);
            turn = Angles.diff180(
                    GeoCalculations.bearingTo(at, new GeoPoint(q.lat, q.lon)),
                    viewNow);
            if (Math.abs(turn) >= 1.0)
                break;
        }
        double dir = turn < 0.0 ? -1.0 : 1.0;
        double togo = Math.max(BAND_MIN_ARC_DEG, TARGET_SPREAD_DEG - spreadDeg);

        DrawingShape band = bands.get(vfo);
        if (band == null) {
            band = new DrawingShape(mapView, group(), UID_BAND + vfo);
            band.setClosed(true);
            band.setAltitudeMode(Feature.AltitudeMode.ClampToGround);
            band.setMetaBoolean("archive", false);
            band.setMetaBoolean("editable", false);
            band.setMovable(false);
            band.setZOrder(Z_BAND);
            bands.put(vfo, band);
        }

        // Deep while the range is a guess, thin once it is not.
        double slack = BAND_UNCERTAINTY * Math.max(0.0, e[0]);
        double inner = Math.max(BAND_MIN_RADIUS_M, radius * BAND_INNER - slack);
        double outer = Math.min(BAND_MAX_RADIUS_M, radius * BAND_OUTER + slack);
        if (!(outer > inner)) {
            remove(bands, vfo);
            return;
        }

        int steps = Math.max(2, (int) Math.ceil(togo / BAND_STEP_DEG));
        List<GeoPointMetaData> ring = new ArrayList<>(2 * steps + 2);
        arc(ring, at, viewNow, dir * togo, outer, steps, false);
        arc(ring, at, viewNow, dir * togo, inner, steps, true);
        // Outer edge first, inner edge back, so the two close into a ring
        // segment rather than a bow tie.
        if (ring.size() < 4) {
            remove(bands, vfo);
            return;
        }
        ring.add(ring.get(0));
        band.setPoints(ring,
                new android.util.SparseArray<com.atakmap.android.maps.PointMapItem>());
        band.setStrokeColor(BAND_STROKE);
        band.setFillColor(BAND_FILL);
        band.addStyleBits(Shape.STYLE_FILLED_MASK | Shape.STYLE_STROKE_MASK
                | Polyline.STYLE_CLOSED_MASK);
        band.setStrokeWeight(2.0);

        String name = String.format(Locale.US, "Drive here -- %.0f deg to go",
                togo);
        band.setTitle(name);
        band.setCenterPointVisible(true);
        band.setCenterPointLabelVisible(true);
        Marker bandCentre = band.getShapeMarker();
        if (bandCentre != null) {
            bandCentre.setIconVisibility(Marker.ICON_GONE);
            bandCentre.setMetaBoolean("removable", false);
            bandCentre.setMovable(false);
            bandCentre.setMetaString("callsign", name);
        }
        band.setMinLabelRenderResolution(0.0);
        band.setMaxLabelRenderResolution(LABEL_MAX_RES);
        band.setMetaString("remarks", String.format(Locale.US,
                "Signal DF: get anywhere inside this band.\n\n"
                        + "It asks for two things at once. Come in -- "
                        + "range is the strongest driver of error, and you are "
                        + "%s out. And swing round: your bearings span %.0f "
                        + "degrees, and another %.0f round the emitter reaches "
                        + "the %.0f that gives the best accuracy.\n\n"
                        + "The band is deep while the range is still a guess "
                        + "and narrows as the fix firms up, so how wide it "
                        + "looks is itself the answer to how well this is "
                        + "known.\n\n"
                        + "Driving straight at the emitter adds no crossing at "
                        + "all, which is why this is an arc and not a line to "
                        + "it. Anywhere inside will do; the roads decide the "
                        + "rest, and the band is redrawn closer each time you "
                        + "reach it.",
                com.atakmap.android.signaldf.data.Units.format(radius),
                spreadDeg, togo, TARGET_SPREAD_DEG));
        if (band.getGroup() == null) {
            group().addItem(band);
            com.atakmap.coremap.log.Log.d("SignalDF.FixLayer",
                    "band on the map, " + Math.round(togo) + " deg to go at "
                            + Math.round(radius) + " m");
        }
    }

    /**
     * Append one arc of {@code sweep} degrees from {@code startDeg}, at a fixed
     * range from {@code centre}. Reversed for the inner edge, so that the two
     * together close into a ring segment rather than crossing themselves.
     */
    private static void arc(List<GeoPointMetaData> out, GeoPoint centre,
            double startDeg, double sweep, double range, int steps,
            boolean reverse) {
        for (int i = 0; i <= steps; i++) {
            int n = reverse ? steps - i : i;
            GeoPoint p = GeoCalculations.pointAtDistance(centre,
                    startDeg + sweep * n / steps, range);
            if (p != null)
                out.add(GeoPointMetaData.wrap(p));
        }
    }

    // ---- saying so once -----------------------------------------------------

    /**
     * Two things get said, each once: that there is a first fix and where to
     * drive, and later that the geometry has come good.
     *
     * <p>A notification rather than a dialog, because this fires while
     * somebody is driving and a modal window over the map is the wrong thing
     * to put in front of them.
     *
     * <p>Note what it claims. It reports the <em>spread that was measured</em>,
     * not that the emitter has been found: Bauder and Barnhart's beacon tests
     * found that 95% confidence ellipses "rarely contained the true location",
     * agreeing with Nams and Boutin (1991) and Withey et al. (2001). A popup
     * saying "confident" would be making exactly the claim that literature
     * says does not hold.
     */
    private void announce(int vfo, String label, double spreadDeg,
            GeoPoint at, double semiMajorM, GeoPoint operator) {
        int stage = announced.containsKey(vfo) ? announced.get(vfo) : 0;

        // One: there is something to go on. This is the one that changes what
        // the driver does, and it has to come early -- the operator's own
        // words, "once you have that first sort of fix it needs to give you a
        // popup with the arc, then you can adjust your driving to get in the
        // arc". Saying nothing until the geometry was already good told them
        // the search was over, which is the least useful moment to speak.
        if (stage < 1) {
            announced.put(vfo, 1);
            // No jump here, deliberately. A first fix arrives within
            // seconds of connecting and is often kilometres out, and the
            // operator is mid-drive working out where to go next -- moving
            // their map at that moment takes away the one thing they are
            // using. The notification says a fix exists; the "Go to" button is
            // there when they want to look at it. The map moves by itself only
            // once the angle width is in the recommended range.
            post(vfo, NotificationUtil.GeneralIcon.STATUS_YELLOW,
                    NotificationUtil.YELLOW,
                    "Signal DF: first fix on " + label,
                    "Drive into the orange band to sharpen it",
                    "There is a fix, and it is rough -- the bearings only span "
                            + String.format(Locale.US, "%.0f", spreadDeg)
                            + " degrees so far. The orange band on the map is "
                            + "where to drive: it swings you round the emitter "
                            + "and brings you in, which is what turns a rough "
                            + "fix into a good one. Anywhere inside it will do.");
            return;
        }

        // Two: as good as this drive is going to make it.
        if (stage < 2 && !Double.isNaN(spreadDeg)
                && spreadDeg >= GOOD_SPREAD_DEG) {
            announced.put(vfo, 2);
            goTo(at, semiMajorM, operator, false);
            post(vfo, NotificationUtil.GeneralIcon.STATUS_GREEN,
                    NotificationUtil.GREEN,
                    "Signal DF: geometry is good",
                    String.format(Locale.US,
                            "%s -- bearings now span %.0f degrees", label,
                            spreadDeg),
                    String.format(Locale.US,
                            "%s: your bearings span %.0f degrees, in the "
                                    + "90-100 that gives the best accuracy. "
                                    + "More driving will not sharpen the "
                                    + "geometry much from here. The ellipse "
                                    + "describes that geometry; it is not a "
                                    + "promise the emitter is inside it.",
                            label, spreadDeg));
        }
    }

    /**
     * The notification is the record that waits. The live status lives in the
     * pane -- see {@link com.atakmap.android.signaldf.data.Guidance} -- which
     * is where the operator asked for it, and rightly: a toast is gone in
     * three seconds and neither of us managed to catch one on screen.
     *
     * <p>Not a dialog either. A modal window over the map while somebody is
     * driving is the one form this must not take.
     */
    private void post(int vfo, NotificationUtil.GeneralIcon icon,
            NotificationUtil.NotificationColor color, String title,
            String ticker, String message) {
        try {
            android.content.Intent open = new android.content.Intent(
                    com.atakmap.android.signaldf.plugin.SignalDF.ACTION_SHOW);
            NotificationUtil.getInstance().postNotification(
                    NOTIFY_BASE_ID + vfo, icon.getID(), color, title, ticker,
                    message, open, false);
        } catch (Throwable t) {
            com.atakmap.coremap.log.Log.w("SignalDF.FixLayer",
                    "could not post a notification", t);
        }
    }

    /**
     * Take the map to the answer, once, when the geometry comes good.
     *
     * <p>It fires exactly once per emitter, at the one point in a search where
     * "look here" is worth interrupting for. It deliberately does not follow
     * the fix afterwards: a map that re-centres itself every few seconds while
     * somebody is driving is unusable, which is the lesson the old waypoint
     * already taught once.
     *
     * <p>The zoom is sized from the uncertainty rather than fixed, so the
     * ellipse lands about a third of the way across the screen whether it is
     * 50 m or 500 m across, and it is clamped at both ends -- a very tight fix
     * should not drop the operator into the dirt, and a loose one should not
     * pull back to orbit.
     */
    private void goTo(final GeoPoint at, final double semiMajorM,
            final GeoPoint operator, final boolean tight) {
        if (at == null)
            return;
        mapView.post(new Runnable() {
            @Override
            public void run() {
                int w = mapView.getWidth();
                double span;
                if (tight) {
                    span = Math.max(ZOOM_TIGHT_MIN_SPAN_M,
                            Math.min(ZOOM_TIGHT_MAX_SPAN_M,
                                    ZOOM_TIGHT_MARGIN
                                            * Math.max(semiMajorM, 1.0)));
                } else {
                    span = ZOOM_ELLIPSE_MARGIN * Math.max(semiMajorM, 1.0);
                    if (operator != null)
                        span = Math.max(span, ZOOM_OPERATOR_MARGIN
                                * GeoCalculations.distanceTo(at, operator));
                    span = Math.max(ZOOM_MIN_SPAN_M,
                            Math.min(ZOOM_MAX_SPAN_M, span));
                }
                double res = w > 0 ? span / w : ZOOM_FALLBACK_RES;
                try {
                    mapView.getMapController().panZoomTo(at,
                            mapView.mapResolutionAsMapScale(res), true);
                } catch (LinkageError | RuntimeException e) {
                    com.atakmap.coremap.log.Log.w("SignalDF.FixLayer",
                            "panZoomTo failed; plain pan", e);
                    try {
                        mapView.getMapController().panTo(at, true);
                    } catch (Throwable ignored) {
                        // A map that will not move is not worth a crash.
                    }
                }
            }
        });
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

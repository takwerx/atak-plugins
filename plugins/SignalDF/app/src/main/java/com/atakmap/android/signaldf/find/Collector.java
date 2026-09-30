package com.atakmap.android.signaldf.find;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import com.atakmap.android.maps.MapView;
import com.atakmap.android.signaldf.data.Quality;
import com.atakmap.android.signaldf.data.SampleLog;
import com.atakmap.android.signaldf.map.BearingLayer;
import com.atakmap.android.signaldf.map.FixLayer;
import com.atakmap.android.signaldf.model.ArrayHeading;
import com.atakmap.android.signaldf.model.Bearing;
import com.atakmap.android.signaldf.model.FeedFrame;
import com.atakmap.android.signaldf.net.KrakenLink;
import com.atakmap.coremap.maps.coords.GeoPoint;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Drive around, gather bearings, cross them, and say where to go next.
 *
 * <p>This is the loop the plugin exists for. Everything before it -- reading
 * the radio, sizing the array, resolving a heading -- produces one line on a
 * map, and one line locates nothing. The vendor's own quickstart is blunt
 * about it: "drive around to collect bearing data from multiple locations in
 * order to triangulate the source."
 *
 * <p><b>Plugin-lifetime, like the link and the publisher.</b> Collecting has
 * to outlive the tap that started it by a long way -- it runs for the length
 * of a search, across base map switches, other tools, Back presses and the
 * pane being closed. FOBS 0.4 went to the field with a GPS recording inside a
 * Tool and switching base maps ended the walk; this does not repeat that.
 *
 * <p><b>Four gates, in this order, and each one exists because of a specific
 * way a fix goes wrong:</b>
 *
 * <ol>
 * <li><b>A known heading.</b> Without one the bearing is measured from a piece
 *     of aluminium whose orientation nobody knows, and logging it would poison
 *     every fix computed afterwards. Same rule the map draws by.
 * <li><b>Quality.</b> A twelve percent bearing is a real measurement and a bad
 *     one; it is still shown on the map, dimmed, but it never goes in the log.
 *     See {@link Quality}.
 * <li><b>Movement.</b> Enforced by {@link SampleLog}: bearings from the same
 *     spot add no crossing angle and merely outvote the ones that do.
 * <li><b>Age.</b> Also {@link SampleLog}: old bearings are the valuable ones,
 *     being from somewhere else, but a transmitter that moves makes them
 *     actively harmful, so they expire.
 * </ol>
 *
 * <p>One log and one fix per VFO throughout. Two frequencies are two emitters,
 * and a fix that averaged them would land between two real transmitters, where
 * nothing is.
 */
public final class Collector implements KrakenLink.Listener {

    public static final String PREF_ON = "signaldf.collect_on";
    public static final String PREF_MIN_CONFIDENCE = "signaldf.min_confidence";
    public static final String PREF_MIN_POWER = "signaldf.min_power_db";

    private static Collector instance;

    /** The one collector, or null before plugin start and after plugin stop. */
    public static Collector get() {
        return instance;
    }

    private final MapView mapView;
    private final Context host;
    private final SharedPreferences prefs;
    private final FixLayer layer;

    /** What the pane switches on and off, and asks to jump to. */
    public FixLayer layer() {
        return layer;
    }

    private final Map<Integer, SampleLog> logs = new HashMap<>();
    private final Map<Integer, SampleLog.Result> results = new HashMap<>();
    private final Quality.Tally tally = new Quality.Tally();

    public Collector(MapView mapView, Context pluginContext) {
        this.mapView = mapView;
        this.host = mapView.getContext();
        this.prefs = PreferenceManager.getDefaultSharedPreferences(host);
        this.layer = new FixLayer(mapView, pluginContext);
        instance = this;
    }

    public void dispose() {
        layer.dispose();
        logs.clear();
        results.clear();
        if (instance == this)
            instance = null;
    }

    // ---- settings -----------------------------------------------------------

    public boolean isOn() {
        return prefs.getBoolean(PREF_ON, false);
    }

    /**
     * Start or stop collecting. Stopping deliberately keeps what was
     * gathered: an operator who stops to think has not thrown their afternoon
     * away, and {@link #clear()} is the button that means that.
     */
    public void setOn(boolean on) {
        prefs.edit().putBoolean(PREF_ON, on).apply();
    }

    public double minConfidence() {
        return prefs.getFloat(PREF_MIN_CONFIDENCE,
                (float) Quality.DEFAULT_MIN_CONFIDENCE);
    }

    public void setMinConfidence(double v) {
        prefs.edit().putFloat(PREF_MIN_CONFIDENCE, (float) v).apply();
    }

    /** NaN when power is not being gated on, which is the default. */
    public double minPowerDb() {
        float v = prefs.getFloat(PREF_MIN_POWER, Float.NaN);
        return Float.isNaN(v) ? Double.NaN : v;
    }

    public void setMinPowerDb(double v) {
        prefs.edit().putFloat(PREF_MIN_POWER, (float) v).apply();
    }

    // ---- what was gathered --------------------------------------------------

    public SampleLog log(int vfo) {
        SampleLog l = logs.get(vfo);
        if (l == null) {
            l = new SampleLog();
            logs.put(vfo, l);
        }
        return l;
    }

    public SampleLog.Result result(int vfo) {
        return results.get(vfo);
    }

    public Quality.Tally tally() {
        return tally;
    }

    /** Total bearings kept, across every emitter. */
    public int collected() {
        int n = 0;
        for (SampleLog l : logs.values())
            n += l.size();
        return n;
    }

    /** Throw away everything gathered and wipe the map. */
    public void clear() {
        logs.clear();
        results.clear();
        tally.reset();
        layer.clear();
    }

    /**
     * One line for the pane: what has been gathered and what it amounts to.
     *
     * <p>The house rule is that a panel says what it is not showing, and
     * "collecting" on its own says nothing about whether anything is being
     * kept -- an operator parked at a gate with the toggle on is collecting
     * exactly nothing and should be told so.
     */
    /**
     * How far along the search is, for the pane to colour its status by. The
     * same rule the map draws the band from, so the two cannot disagree.
     */
    public com.atakmap.android.signaldf.data.Guidance.Stage stage() {
        if (!isOn())
            return com.atakmap.android.signaldf.data.Guidance.Stage.HUNTING;
        SampleLog.Result best = null;
        for (SampleLog.Result r : results.values())
            if (r != null && (best == null
                    || r.fix.accuracyM() < best.fix.accuracyM()))
                best = r;
        return com.atakmap.android.signaldf.data.Guidance.stage(
                best == null ? Double.NaN : best.fix.spreadDeg, best != null);
    }

    public String status() {
        if (!isOn()) {
            int n = collected();
            return n == 0
                    ? "Not collecting. Turn this on and drive; bearings from "
                            + "different places cross to give a location."
                    : String.format(Locale.US,
                            "Stopped, holding %d bearings.", n);
        }
        int n = collected();
        if (n == 0)
            return "Collecting. Nothing kept yet -- a bearing is only kept "
                    + "once the receiver has moved "
                    + com.atakmap.android.signaldf.data.Units.format(
                            SampleLog.MIN_MOVE_M) + " from the last one.";

        // One fact to a line. The count used to carry "each from a
        // different place" to head off "7 bearings from 7 places" reading like
        // a bug -- but the movement gate makes that true by construction, so
        // it was explaining an implementation detail to somebody driving.
        StringBuilder s = new StringBuilder(String.format(Locale.US,
                "Collecting. %d bearings.", n));
        SampleLog.Result best = null;
        for (SampleLog.Result r : results.values())
            if (r != null && (best == null
                    || r.fix.accuracyM() < best.fix.accuracyM()))
                best = r;
        if (best == null) {
            s.append("\nNo crossing yet.");
        } else {
            s.append(String.format(Locale.US, "\nFix within %s.",
                    com.atakmap.android.signaldf.data.Units.format(
                            best.fix.accuracyM())));
        }
        // What to do about it, from the same rule the band on the map and the
        // notification use. This replaced BearingFix.advice() here: that text
        // was written for the old perpendicular waypoint and told the operator
        // to "drive across them, toward 129 or 309" while the line under it
        // said to drive into the band. Two sentences, one instruction, and the
        // older one named a heading the map no longer showed.
        s.append('\n').append(com.atakmap.android.signaldf.data.Guidance.line(
                stage(), best == null ? Double.NaN : best.fix.spreadDeg));
        String dropped = tally.describe();
        if (dropped != null)
            s.append('\n').append(dropped);
        return s.toString();
    }

    // ---- the loop -----------------------------------------------------------

    @Override
    public void onLinkChanged() {
        // Nothing to collect on a state change; bearings drive this.
    }

    @Override
    public void onBearings(FeedFrame frame) {
        if (!isOn() || frame == null || frame.bearings == null
                || frame.bearings.isEmpty())
            return;

        // Gate 1: a heading, or nothing at all. The same rule the map draws
        // by, read from the same place, so what is logged and what is shown
        // can never disagree about which way the array was facing.
        ArrayHeading heading = ArrayHeading.resolve(operatorHeading(),
                ArrayHeading.vehicleDegrees(
                        prefs.getBoolean(ArrayHeading.PREF_FORWARD, false)),
                frame.bearings.get(0));
        if (!heading.isKnown())
            return;

        GeoPoint fallback = BearingLayer.selfPoint(mapView);
        double minConf = minConfidence();
        double minPower = minPowerDb();
        long now = System.currentTimeMillis();

        for (Bearing b : frame.bearings) {
            // Gate 2: quality.
            Quality.Verdict v = Quality.judge(b, minConf, minPower);
            if (v != Quality.Verdict.OK) {
                tally.add(v);
                continue;
            }
            GeoPoint from = BearingLayer.receiverPoint(b, fallback);
            if (from == null)
                continue;

            SampleLog log = log(b.vfo);
            log.age(now);
            // Gates 3 and 4 live in here.
            double weight = Double.isNaN(b.confidence) ? 1.0
                    : Math.max(0.05, b.confidence);
            log.offer(from.getLatitude(), from.getLongitude(),
                    b.trueBearing(heading.degrees()), weight, b.powerDb, now);

            results.put(b.vfo, log.solve());
            layer.draw(b.vfo, String.format(Locale.US, "%.4f MHz",
                    b.frequencyMHz()), log, results.get(b.vfo));
        }
    }

    private Double operatorHeading() {
        String s = prefs.getString("signaldf.array_heading", null);
        if (s == null || s.trim().isEmpty())
            return null;
        try {
            return Double.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

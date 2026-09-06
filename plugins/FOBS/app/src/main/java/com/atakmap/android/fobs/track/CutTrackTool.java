package com.atakmap.android.fobs.track;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.util.SparseArray;
import android.view.View;
import android.widget.Toast;

import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.drawing.mapItems.DrawingCircle;
import com.atakmap.android.drawing.mapItems.DrawingShape;
import com.atakmap.android.editableShapes.EditablePolyline;
import com.atakmap.android.editableShapes.Rectangle;
import com.atakmap.android.fobs.plugin.R;
import com.atakmap.android.maps.MapEvent;
import com.atakmap.android.maps.MapEventDispatcher;
import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.android.maps.PointMapItem;
import com.atakmap.android.maps.MultiPolyline;
import com.atakmap.android.maps.Shape;
import com.atakmap.android.toolbar.Tool;
import com.atakmap.android.toolbar.widgets.TextContainer;
import com.atakmap.android.tools.ActionBarReceiver;
import com.atakmap.android.tools.ActionBarView;
import com.atakmap.android.track.maps.TrackPolyline;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.assets.Icon;
import com.atakmap.coremap.maps.coords.GeoCalculations;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.coremap.maps.coords.GeoPointMetaData;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Split track: long press a line exactly where you want it split. A new vertex is
 * dropped onto the line there (the way ATAK's shape editor adds one) and the line
 * becomes two tracks sharing that point, same color, so nothing is lost. That is all
 * it does: the operator deletes whichever half they do not want with the normal
 * radial menu. A first version asked which half to delete and the operator said it
 * made things harder than they needed to be (2026-09-05).
 *
 * <p>Works on any line or closed shape on the map, not only FOBS tracks: freeform
 * lines and polygons, routes, telestration strokes, Track History lines, rectangles,
 * circles. A line becomes two lines; a closed shape opens into one line. The results
 * are FOBS tracks.
 * ATAK has no way to sever a polyline, only to remove single vertices. This is the
 * "I walked around talking to people before I got on the fireline" fix.
 */
public class CutTrackTool extends Tool implements MapEventDispatcher.MapEventDispatchListener,
        View.OnClickListener, com.atakmap.android.maps.MapTouchController.DeconflictionListener {

    public static final String ID = "com.atakmap.android.fobs.CutTrackTool";
    /** Optional: only this track may be cut (from its radial menu). */
    public static final String EXTRA_UID = "uid";

    private static final String TAG = "FOBS.CutTrackTool";
    private static final int FLASH_FIRST = 0xFFFFA000;
    private static final int FLASH_SECOND = 0xFF00E5FF;

    private final MapView mapView;
    private final Context plugin;
    private final Context host;
    private final ActionBarView toolbar;
    private final MapGroup scratch;
    private final Icon cutIcon;

    private gov.tak.api.ui.IHostUIService ui;
    private gov.tak.api.ui.Pane resultPane;

    /** The side pane needs ATAK's UI service; set once at plugin start. */
    public void setUiService(gov.tak.api.ui.IHostUIService ui) {
        this.ui = ui;
    }

    /** White rings on the new ends of every split made in this session of the tool. */
    private final List<Marker> endRings = new ArrayList<>();
    private Icon endIcon;

    private void ring(GeoPointMetaData at) {
        if (endIcon == null)
            endIcon = new Icon.Builder().setImageUri(0, "asset:/icons/outline.png")
                    .setAnchor(24, 24).setColor(0, 0xFFFFFFFF).build();
        Marker m = new Marker(at, UUID.randomUUID().toString());
        m.setType("shape_marker");
        m.setIcon(endIcon);
        m.setMetaBoolean("addToObjList", false);
        m.setMetaBoolean("nevercot", true);
        m.setMetaBoolean("ignoreMenu", true);
        m.setMetaBoolean("ignoreOffscreen", true);
        m.setMetaBoolean("removable", false);
        m.setMetaBoolean("movable", false);
        m.setShowLabel(false);
        m.setClickable(false);
        scratch.addItem(m);
        endRings.add(m);
    }

    private void clearRings() {
        for (Marker m : endRings)
            m.removeFromGroup();
        endRings.clear();
    }

    private String onlyUid;
    private Shape candidateShape;
    /** The cut point goes after this vertex index; -1 when there is no candidate. */
    private int candidateSegment = -1;
    /** The cut point itself, on the line where the operator tapped. */
    private GeoPointMetaData candidatePoint;
    private Marker candidateMarker;

    public CutTrackTool(MapView mapView, Context pluginContext) {
        super(mapView, ID);
        this.mapView = mapView;
        this.plugin = pluginContext;
        this.host = mapView.getContext();

        toolbar = (ActionBarView) PluginLayoutInflater.inflate(pluginContext,
                R.layout.track_toolbar, mapView, false);
        toolbar.setEmbedState(ActionBarView.FLOATING);
        toolbar.showCloseButton(false);
        toolbar.findViewById(R.id.pause).setVisibility(View.GONE);
        toolbar.findViewById(R.id.undo).setVisibility(View.GONE);
        toolbar.findViewById(R.id.dropped).setVisibility(View.GONE);
        toolbar.findViewById(R.id.end).setOnClickListener(this);

        scratch = mapView.getRootGroup().addGroup("FOBS cut");
        scratch.setMetaBoolean("addToObjList", false);
        cutIcon = new Icon.Builder()
                .setImageUri(0, "asset:/icons/outline.png")
                .setAnchor(24, 24)
                .setColor(0, 0xFFFFC400)
                .build();
    }

    @Override
    protected boolean onToolBegin(Bundle extras) {
        onlyUid = extras.getString(EXTRA_UID);
        clearCandidate();
        pushMapListeners();
        clearExtraListeners();
        MapEventDispatcher d = mapView.getMapEventDispatcher();
        d.addMapEventListener(MapEvent.ITEM_CLICK, this);
        d.addMapEventListener(MapEvent.MAP_CLICK, this);
        // ATAK's own shape editor adds a vertex with a long press on the line; the
        // same gesture places the cut point here, and a plain tap works as well.
        d.addMapEventListener(MapEvent.ITEM_LONG_PRESS, this);
        d.addMapEventListener(MapEvent.MAP_LONG_PRESS, this);
        // ATAK sends an item long press only when exactly one item is under the
        // finger; with stacked lines it shows a pick list instead and we hear
        // nothing. This filter narrows a stacked hit to one splittable line.
        mapView.getMapTouchController().addDeconflictionListener(this);
        mapView.getMapTouchController().setToolActive(true);
        ActionBarReceiver.getInstance().setToolView(toolbar);
        TextContainer.getInstance().displayPrompt(plugin.getString(R.string.prompt_cut));
        return true;
    }

    @Override
    public void onConflict(java.util.SortedSet<MapItem> hits) {
        // Prefer the line the menu was opened on, else the longest line under the
        // finger: a two-point stub left by an earlier split is never what was meant.
        MapItem keep = null;
        int keepPoints = -1;
        for (MapItem m : hits) {
            if (!cuttable(m))
                continue;
            if (onlyUid != null && onlyUid.equals(m.getUID())) {
                keep = m;
                break;
            }
            GeoPoint[] p = ((Shape) m).getPoints();
            int n = p == null ? 0 : p.length;
            if (n > keepPoints) {
                keepPoints = n;
                keep = m;
            }
        }
        if (keep != null) {
            hits.clear();
            hits.add(keep);
        }
    }

    @Override
    protected void onToolEnd() {
        popMapListeners();
        mapView.getMapTouchController().removeDeconflictionListener(this);
        mapView.getMapTouchController().setToolActive(false);
        ActionBarReceiver.getInstance().setToolView(null);
        TextContainer.getInstance().closePrompt();
        // A pending choice stays up: both halves exist, Keep both is the default.
        if (resultPane == null)
            clearCandidate();
        clearRings();
        onlyUid = null;
    }

    @Override
    public void dispose() {
        closeResult();
        clearRings();
        mapView.getRootGroup().removeGroup(scratch);
    }

    @Override
    public void onClick(View v) {
        if (v.getId() == R.id.end)
            requestEndTool();
    }

    // ---- taps --------------------------------------------------------------------

    @Override
    public void onMapEvent(MapEvent event) {
        MapItem item = event.getItem();
        boolean longPress = MapEvent.ITEM_LONG_PRESS.equals(event.getType())
                || MapEvent.MAP_LONG_PRESS.equals(event.getType());
        if (!longPress) {
            // The gesture is the long press, as in ATAK's shape editor. A tap only
            // reminds; it must not cut, or a stray touch would sever a track.
            toast(plugin.getString(R.string.toast_long_press_to_cut));
            return;
        }
        // Where the finger was: the event's screen position, inverted through the
        // map. Not the shape's remembered click point and not Tool.findPoint, which
        // returns that click point for shapes: on a circle it was hundreds of
        // kilometers stale and the split landed there (log, 2026-09-05).
        GeoPointMetaData tapped = null;
        android.graphics.PointF screen = event.getPointF();
        if (screen != null)
            tapped = mapView.inverseWithElevation(screen.x, screen.y);
        if (tapped == null || tapped.get() == null || !tapped.get().isValid())
            tapped = findPoint(event);
        if (tapped == null || tapped.get() == null)
            return;
        GeoPoint tap = tapped.get();

        // Which line: not necessarily the one ATAK hit. At a junction a two-point stub
        // from an earlier split sits on top of the long line and ATAK reports the
        // stub; splitting it just makes more stubs (log, 2026-09-05). So look at every
        // line within a finger's width of the press and take the closest, and when
        // two are equally close, the longer one.
        Shape shape = nearestLine(tap, item);
        if (shape == null) {
            toast(plugin.getString(R.string.toast_tap_a_track));
            return;
        }
        if (onlyUid != null && !onlyUid.equals(shape.getUID())) {
            MapItem wanted = mapView.getRootGroup().deepFindUID(onlyUid);
            if (wanted instanceof Shape && cuttable(wanted))
                shape = (Shape) wanted;
            else {
                toast(plugin.getString(R.string.toast_tap_that_track));
                return;
            }
        }
        GeoPointMetaData[] pts = shape.getMetaDataPoints();
        int n = pts == null ? 0 : pts.length;
        if (n < 2) {
            toast(plugin.getString(R.string.toast_cut_too_short));
            return;
        }
        // ATAK records which segment the finger hit on the item it hit. Use that
        // when it is this line; otherwise drop the press onto the nearest segment.
        int bestSeg = -1;
        double bestD = Double.MAX_VALUE;
        GeoPoint bestP = null;
        if (shape == item && "line".equals(shape.getMetaString("hit_type", ""))) {
            int hi = shape.getMetaInteger("hit_index", -1);
            if (hi >= 0 && hi < n - 1 && pts[hi] != null && pts[hi + 1] != null
                    && pts[hi].get() != null && pts[hi + 1].get() != null) {
                bestSeg = hi;
                bestP = project(tap, pts[hi].get(), pts[hi + 1].get());
                bestD = 0;
            }
        }
        int segCount = closed(shape) ? n : n - 1; // a ring closes last -> first
        for (int i = 0; bestSeg < 0 && i < segCount; i++) {
            GeoPointMetaData a = pts[i];
            GeoPointMetaData b = pts[(i + 1) % n];
            if (a == null || b == null || a.get() == null || b.get() == null)
                continue;
            GeoPoint proj = project(tap, a.get(), b.get());
            double d = GeoCalculations.distanceTo(tap, proj);
            if (d < bestD) {
                bestD = d;
                bestSeg = i;
                bestP = proj;
            }
        }
        if (bestSeg < 0 || bestP == null)
            return;
        double dStart = GeoCalculations.distanceTo(bestP, pts[0].get());
        double dEnd = GeoCalculations.distanceTo(bestP, pts[n - 1].get());
        Log.d(TAG, "press on '" + shape.getTitle() + "' " + shape.getClass().getSimpleName()
                + " n=" + n + " event=" + event.getType()
                + " hit_type=" + shape.getMetaString("hit_type", "-")
                + " hit_index=" + shape.getMetaInteger("hit_index", -1)
                + " clickPoint=" + (shape.getClickPoint() != null)
                + " tap=" + tap.getLatitude() + "," + tap.getLongitude()
                + " seg=" + bestSeg + " offLine=" + bestD
                + " dStart=" + dStart + " dEnd=" + dEnd);
        // Too close to an end for a bite to leave a piece on that side: refuse.
        // A ring has no ends.
        if (!closed(shape) && (dStart < gapMeters() / 2 || dEnd < gapMeters() / 2)) {
            toast(plugin.getString(R.string.toast_cut_at_end));
            return;
        }
        GeoPointMetaData cutPoint = GeoPointMetaData.wrap(bestP);
        cutPoint.setGeoPointSource(FobsShapes.SOURCE_USER);
        // Long press is the whole gesture: buzz, mark the spot, cut right there.
        // "Keep both" on the pane that follows is the way back if it was wrong.
        mapView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS,
                android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        showCandidate(shape, bestSeg, cutPoint);
        cut();
    }

    /**
     * The bite taken out of the line, meters. Tiny: just enough to break it. About
     * 4 px at the current zoom so it can be seen, never less than a meter.
     */
    private double gapMeters() {
        double mpp = mapView.getMapResolution();
        if (Double.isNaN(mpp) || mpp <= 0)
            mpp = 1;
        return Math.max(1.0, 4 * mpp);
    }

    /**
     * Put a shape into ATAK's vertex editor, the same broadcast the radial menu's edit
     * button sends. The operator wants the vertices live the moment a split is made.
     */
    private void editShape(final DrawingShape shape) {
        if (shape == null || shape.getGroup() == null)
            return;
        // ATAK's details pane zooms to fit the shape when it opens and offers no way
        // to say no. The operator wants to stay where they were when they split, so
        // remember the view and put it back once the pane is up.
        final GeoPoint center = mapView.getCenterPoint() == null ? null
                : mapView.getCenterPoint().get();
        final double scale = mapView.getMapScale();
        mapView.postDelayed(new Runnable() {
            @Override
            public void run() {
                android.content.Intent i = new android.content.Intent(
                        "com.atakmap.android.maps.DRAWING_DETAILS");
                i.putExtra("uid", shape.getUID());
                i.putExtra("shapeUID", shape.getUID());
                i.putExtra("edit", true);
                com.atakmap.android.ipc.AtakBroadcast.getInstance().sendBroadcast(i);
                if (center != null) {
                    mapView.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            mapView.getMapController().panZoomTo(center, scale, false);
                        }
                    }, 600);
                }
            }
        }, 150);
    }

    /** Meters covered by one finger-width (about 32 px) at the current zoom. */
    private double fingerMeters() {
        double mpp = mapView.getMapResolution();
        if (Double.isNaN(mpp) || mpp <= 0)
            mpp = 1;
        return Math.max(3.0, 32 * mpp);
    }

    /** Distance from p to the nearest segment of the shape, meters. */
    static double distanceToLine(GeoPoint p, Shape shape) {
        GeoPointMetaData[] pts = shape.getMetaDataPoints();
        if (pts == null || pts.length < 2)
            return Double.MAX_VALUE;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < pts.length - 1; i++) {
            if (pts[i] == null || pts[i + 1] == null || pts[i].get() == null
                    || pts[i + 1].get() == null)
                continue;
            double d = GeoCalculations.distanceTo(p, project(p, pts[i].get(), pts[i + 1].get()));
            if (d < best)
                best = d;
        }
        return best;
    }

    /**
     * The splittable line nearest the press, within a finger's width; ties within
     * 2 m go to the line with more points. The item ATAK hit is a candidate too.
     */
    private Shape nearestLine(GeoPoint tap, MapItem hit) {
        double limit = fingerMeters();
        List<MapItem> candidates = new ArrayList<>();
        candidates.addAll(com.atakmap.android.drawing.DrawingToolsMapComponent.getGroup()
                .getItemsRecursive());
        if (hit != null && !candidates.contains(hit))
            candidates.add(hit);
        Shape best = null;
        double bestD = Double.MAX_VALUE;
        int bestN = -1;
        for (MapItem m : candidates) {
            if (!cuttable(m) || m instanceof MultiPolyline)
                continue;
            Shape sh = (Shape) m;
            double d = distanceToLine(tap, sh);
            if (d > limit)
                continue;
            GeoPoint[] p = sh.getPoints();
            int n = p == null ? 0 : p.length;
            boolean closer = d < bestD - 2.0;
            boolean tieButLonger = Math.abs(d - bestD) <= 2.0 && n > bestN;
            if (best == null || closer || tieButLonger) {
                best = sh;
                bestD = d;
                bestN = n;
            }
        }
        return best;
    }

    /**
     * Any open line on the map can be cut: a FOBS track, an ATAK freeform line, a
     * route, a telestration stroke, a Track History line. Closed shapes cannot; a
     * polygon cut in one place is still one ring.
     */
    static boolean cuttable(MapItem item) {
        if (!(item instanceof Shape))
            return false;
        GeoPoint[] p = ((Shape) item).getPoints();
        return p != null && p.length >= 2;
    }

    /** A closed shape: a polygon, a FOBS area, a rectangle, a circle. */
    static boolean closed(Shape shape) {
        return FobsShapes.isArea(shape) || shape instanceof Rectangle
                || shape instanceof DrawingCircle
                || (shape instanceof EditablePolyline && ((EditablePolyline) shape).isClosed());
    }

    /**
     * Closest point on segment ab to p, in a flat frame around a. Altitude is
     * interpolated along the segment so the new vertex sits on the line in 3D too.
     */
    static GeoPoint project(GeoPoint p, GeoPoint a, GeoPoint b) {
        double cos = Math.cos(Math.toRadians(a.getLatitude()));
        double bx = (b.getLongitude() - a.getLongitude()) * cos;
        double by = b.getLatitude() - a.getLatitude();
        double px = (p.getLongitude() - a.getLongitude()) * cos;
        double py = p.getLatitude() - a.getLatitude();
        double len2 = bx * bx + by * by;
        double u = len2 == 0 ? 0 : (px * bx + py * by) / len2;
        u = Math.max(0, Math.min(1, u));
        double lat = a.getLatitude() + u * (b.getLatitude() - a.getLatitude());
        double lon = a.getLongitude() + u * (b.getLongitude() - a.getLongitude());
        double alt = GeoPoint.UNKNOWN;
        if (a.isAltitudeValid() && b.isAltitudeValid())
            alt = a.getAltitude() + u * (b.getAltitude() - a.getAltitude());
        else if (a.isAltitudeValid())
            alt = a.getAltitude();
        return new GeoPoint(lat, lon, alt);
    }

    private void showCandidate(Shape shape, int segment, GeoPointMetaData point) {
        clearCandidate();
        candidateShape = shape;
        candidateSegment = segment;
        candidatePoint = point;
        Marker m = new Marker(point, UUID.randomUUID().toString());
        m.setType("shape_marker");
        m.setTitle(plugin.getString(R.string.cut_here));
        m.setIcon(cutIcon);
        m.setMetaBoolean("addToObjList", false);
        m.setMetaBoolean("nevercot", true);
        m.setMetaBoolean("ignoreMenu", true);
        m.setMetaBoolean("ignoreOffscreen", true);
        m.setMetaBoolean("removable", false);
        m.setMetaBoolean("movable", false);
        m.setShowLabel(false);
        m.setMarkerHitBounds(-32, -32, 32, 32);
        m.setClickable(true);
        scratch.addItem(m);
        candidateMarker = m;
    }

    private void clearCandidate() {
        if (candidateMarker != null) {
            candidateMarker.removeFromGroup();
            candidateMarker = null;
        }
        candidateShape = null;
        candidateSegment = -1;
        candidatePoint = null;
    }

    // ---- the cut -----------------------------------------------------------------

    private void cut() {
        final Shape original = candidateShape;
        final int seg = candidateSegment;
        final GeoPointMetaData at = candidatePoint;
        // The marker stays on the cut point until the choice is made, so the
        // operator can see where the press landed.
        candidateShape = null;
        candidateSegment = -1;
        candidatePoint = null;
        if (original == null || at == null || seg < 0)
            return;
        GeoPointMetaData[] pts = original.getMetaDataPoints();
        if (pts == null || pts.length < 2)
            return;
        if (closed(original)) {
            openRing(original, pts, seg, at.get());
            return;
        }
        if (seg >= pts.length - 1)
            return;
        // Take a tiny bite out of the line around the press so the two pieces are
        // separated by a gap: no shared vertex, no two-point stub, nothing to wonder
        // about (operator, 2026-09-05). Just enough to break it.
        double half = gapMeters() / 2;
        List<GeoPointMetaData> a = new ArrayList<>();
        List<GeoPointMetaData> b = new ArrayList<>();
        bite(pts, seg, at.get(), half, a, b);
        if (a.size() < 2 || b.size() < 2) {
            toast(plugin.getString(R.string.toast_cut_at_end));
            return;
        }

        String title = original.getTitle();
        if (title == null || title.trim().isEmpty())
            title = mapView.getDeviceCallsign();
        // "Track 15" -> "Track 15 1" and "Track 15 2"; splitting "Track 15 1" again
        // gives "Track 15 3" and "Track 15 4", not "Track 15 1 1".
        String base = title.replaceFirst("\\s+\\d+$", "");
        int n1 = FobsShapes.nextSuffix(mapView, base, 1);
        int n2 = FobsShapes.nextSuffix(mapView, base, n1 + 1);
        final DrawingShape first = make(original, base + " " + n1, a);
        final DrawingShape second = make(original, base + " " + n2, b);
        // Show the break: a ring on each new end, either side of the gap.
        clearCandidate();
        ring(a.get(a.size() - 1));
        ring(b.get(0));
        // The operator cut it, so the original goes, except a Track History line,
        // which is the breadcrumb log's view of itself and not ours to delete.
        if (!(original instanceof TrackPolyline))
            original.removeFromGroup();
        Log.d(TAG, "split '" + title + "' at segment " + seg + " gap=" + (2 * half) + "m -> "
                + a.size() + " + " + b.size());

        // Orange and blue until the operator chooses in the side pane; the marker
        // stays on the split point meanwhile so it is clear where the press landed.
        final int keep = first.getStrokeColor();
        first.setStrokeColor(FLASH_FIRST);
        second.setStrokeColor(FLASH_SECOND);
        TextContainer.getInstance().displayPrompt(plugin.getString(R.string.prompt_cut));
        final Runnable restore = new Runnable() {
            @Override
            public void run() {
                if (first.getGroup() != null) {
                    first.setStrokeColor(keep);
                    FobsShapes.persist(mapView, first, CutTrackTool.class);
                }
                if (second.getGroup() != null) {
                    second.setStrokeColor(keep);
                    FobsShapes.persist(mapView, second, CutTrackTool.class);
                }
                clearCandidate();
                closeResult();
            }
        };
        String message = plugin.getString(R.string.cut_done_message,
                first.getTitle(), a.size(), second.getTitle(), b.size());
        showResult(message, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int id = v.getId();
                DrawingShape edit = first;
                if (id == R.id.cut_delete_first) {
                    first.removeFromGroup();
                    edit = second;
                } else if (id == R.id.cut_delete_second) {
                    second.removeFromGroup();
                }
                restore.run();
                // Then straight into ATAK's vertex editor on what is left.
                requestEndTool();
                editShape(edit);
            }
        });
    }

    // ---- result pane -------------------------------------------------------------

    /** The choice, in a side pane so the two colored halves stay visible on the map. */
    private void showResult(String message, View.OnClickListener onChoice) {
        if (ui == null) {
            toast(message);
            return;
        }
        closeResult();
        View v = PluginLayoutInflater.inflate(plugin, R.layout.cut_result, null);
        ((android.widget.TextView) v.findViewById(R.id.cut_message)).setText(message);
        v.findViewById(R.id.cut_delete_first).setOnClickListener(onChoice);
        v.findViewById(R.id.cut_delete_second).setOnClickListener(onChoice);
        v.findViewById(R.id.cut_keep_both).setOnClickListener(onChoice);
        resultPane = new gov.tak.api.ui.PaneBuilder(v)
                .setMetaValue(gov.tak.api.ui.Pane.RELATIVE_LOCATION,
                        gov.tak.api.ui.Pane.Location.Default)
                .setMetaValue(gov.tak.api.ui.Pane.PREFERRED_WIDTH_RATIO, 0.35D)
                .setMetaValue(gov.tak.api.ui.Pane.PREFERRED_HEIGHT_RATIO, 0.4D)
                .build();
        ui.showPane(resultPane, null);
    }

    private void closeResult() {
        if (ui != null && resultPane != null && ui.isPaneVisible(resultPane))
            ui.closePane(resultPane);
        resultPane = null;
    }

    /**
     * A closed shape pressed once: take the bite out of the ring and what is left is
     * one open line, starting just after the gap and running all the way around to
     * just before it. The polygon is gone; the operator can split the line again.
     */
    private void openRing(Shape original, GeoPointMetaData[] pts, int seg, GeoPoint p) {
        int n = pts.length;
        // Rotate so the pressed segment is last: ring[0..n-1] then back to ring[0].
        GeoPointMetaData[] ring = new GeoPointMetaData[n + 1];
        for (int k = 0; k < n; k++)
            ring[k] = pts[(seg + 1 + k) % n];
        ring[n] = pts[(seg + 1) % n]; // the closing vertex, so the last segment is seg
        double half = gapMeters() / 2;
        List<GeoPointMetaData> a = new ArrayList<>();
        List<GeoPointMetaData> b = new ArrayList<>();
        bite(ring, n - 1, p, half, a, b);
        // a: from just after the gap around to just before it. b: the sliver past the
        // gap up to the closing vertex, which is the same place a starts; drop it.
        if (a.size() < 2) {
            toast(plugin.getString(R.string.toast_cut_too_short));
            return;
        }
        // Start the line just after the gap (b's first point) rather than at a vertex.
        if (!b.isEmpty())
            a.add(0, b.get(0));
        String title = original.getTitle();
        if (title == null || title.trim().isEmpty())
            title = mapView.getDeviceCallsign();
        DrawingShape line = make(original, title, a);
        if (!(original instanceof TrackPolyline))
            original.removeFromGroup();
        clearCandidate();
        Log.d(TAG, "opened ring '" + title + "' at segment " + seg + " -> " + a.size() + " points");
        toast(plugin.getString(R.string.toast_ring_opened, title));
        // Straight into ATAK's vertex editor on the new line. That is another tool, so
        // this one ends first.
        requestEndTool();
        editShape(line);
    }

    /**
     * Split the polyline at {@code p} on segment {@code seg}, removing {@code half}
     * meters on each side. {@code a} gets the start up to the gap, {@code b} the rest
     * after it. Either may come back short when the press is near an end.
     */
    static void bite(GeoPointMetaData[] pts, int seg, GeoPoint p, double half,
            List<GeoPointMetaData> a, List<GeoPointMetaData> b) {
        // Walk back from p along the line for `half` meters to find where a ends.
        double remain = half;
        GeoPoint cursor = p;
        int i = seg;
        GeoPoint aEnd = null;
        while (i >= 0) {
            GeoPoint v = pts[i].get();
            double d = GeoCalculations.distanceTo(cursor, v);
            if (d >= remain) {
                aEnd = along(cursor, v, remain / d);
                break;
            }
            remain -= d;
            cursor = v;
            i--;
        }
        if (aEnd != null) {
            for (int k = 0; k <= i; k++)
                a.add(new GeoPointMetaData(pts[k]));
            GeoPointMetaData end = GeoPointMetaData.wrap(aEnd);
            end.setGeoPointSource(FobsShapes.SOURCE_USER);
            a.add(end);
        }
        // Walk forward from p for `half` meters to find where b starts.
        remain = half;
        cursor = p;
        i = seg + 1;
        GeoPoint bStart = null;
        while (i < pts.length) {
            GeoPoint v = pts[i].get();
            double d = GeoCalculations.distanceTo(cursor, v);
            if (d >= remain) {
                bStart = along(cursor, v, remain / d);
                break;
            }
            remain -= d;
            cursor = v;
            i++;
        }
        if (bStart != null) {
            GeoPointMetaData start = GeoPointMetaData.wrap(bStart);
            start.setGeoPointSource(FobsShapes.SOURCE_USER);
            b.add(start);
            for (int k = i; k < pts.length; k++)
                b.add(new GeoPointMetaData(pts[k]));
        }
    }

    /** The point a fraction u of the way from a to b. */
    static GeoPoint along(GeoPoint a, GeoPoint b, double u) {
        double lat = a.getLatitude() + u * (b.getLatitude() - a.getLatitude());
        double lon = a.getLongitude() + u * (b.getLongitude() - a.getLongitude());
        double alt = GeoPoint.UNKNOWN;
        if (a.isAltitudeValid() && b.isAltitudeValid())
            alt = a.getAltitude() + u * (b.getAltitude() - a.getAltitude());
        else if (a.isAltitudeValid())
            alt = a.getAltitude();
        return new GeoPoint(lat, lon, alt);
    }

    private static List<GeoPointMetaData> copy(List<GeoPointMetaData> in) {
        List<GeoPointMetaData> out = new ArrayList<>(in.size());
        for (GeoPointMetaData p : in)
            out.add(new GeoPointMetaData(p));
        return out;
    }

    /** A new FOBS track in the original's style and metadata, with these points. */
    private DrawingShape make(Shape original, String title, List<GeoPointMetaData> pts) {
        String source = original.getMetaString(FobsShapes.META_SOURCE,
                original instanceof TrackPolyline ? FobsShapes.SOURCE_GPS : FobsShapes.SOURCE_USER);
        DrawingShape t = FobsShapes.newTrack(mapView, title, source);
        t.setStrokeColor(original.getStrokeColor());
        t.setStrokeWeight(original.getStrokeWeight());
        if (original instanceof EditablePolyline)
            t.setLineStyle(((EditablePolyline) original).getLineStyle());
        String alt = original.getMetaString(FobsShapes.META_ALTSRC, null);
        if (alt != null)
            t.setMetaString(FobsShapes.META_ALTSRC, alt);
        t.setClosed(false);
        t.setPoints(pts, new SparseArray<PointMapItem>());
        FobsShapes.addToMap(t);
        FobsShapes.persist(mapView, t, getClass());
        return t;
    }

    private void toast(String s) {
        Toast.makeText(host, s, Toast.LENGTH_SHORT).show();
    }
}

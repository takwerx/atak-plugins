
package com.atakmap.android.cursorwerx;

import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;

import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.hittest.MapItemResultFilter;
import com.atakmap.coremap.log.Log;
import com.atakmap.map.MapRenderer2;
import com.atakmap.map.hittest.HitTestQueryParameters;

import java.util.SortedSet;

/**
 * A mouse drag on the map pans it 1:1 under the cursor, the way every desktop map does it,
 * and a mouse click without movement is handed back to ATAK as the tap it was.
 *
 * <p>Mouse only. Touch and pen input never reach the pan logic, so on a phone or tablet
 * this class does nothing.
 *
 * <p>Registered with {@code addOnTouchListenerAt(0, ..)} rather than
 * {@code addOnTouchListener}: {@code MapView}'s touch handler walks the listener queue and
 * breaks on the first that returns true, so anything appended to the end only sees what
 * ATAK's own listeners have already declined. It also skips the queue entirely while
 * {@code getMapTouchController().getInGesture()} -- which is why the button press has to be
 * claimed on ACTION_DOWN, before a gesture can start.
 */
public final class PanControl {

    private static final String TAG = "Cursorwerx";

    /** Drag the map with the pointer rather than against it. Flip to invert. */
    private static final int DIRECTION = -1;

    private final MapView mapView;
    private View.OnTouchListener listener;
    /**
     * How far the pointer must move before a press turns into a pan, in pixels. Small
     * enough that a drag feels immediate, large enough that a click with a twitchy hand
     * is still a click.
     */
    private static final float DRAG_SLOP = 4f;

    private boolean panning;
    private boolean pressed;
    /** Set while a click is handed back to ATAK, so this listener lets it through. */
    private boolean replaying;
    private MotionEvent downCopy;
    private float lastX, lastY;
    private boolean verbose;

    public PanControl(MapView mapView) {
        this.mapView = mapView;
    }

    public void setVerbose(boolean v) {
        this.verbose = v;
    }

    public void attach() {
        if (listener != null)
            return;
        listener = new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                return handle(event);
            }
        };
        mapView.addOnTouchListenerAt(0, listener);
        Log.d(TAG, "pan control attached");
    }

    /** Symmetric with {@link #attach()}; a reload must leave no listener behind. */
    public void detach() {
        if (listener == null)
            return;
        mapView.removeOnTouchListener(listener);
        listener = null;
        panning = false;
        pressed = false;
        releaseDown();
        Log.d(TAG, "pan control detached");
    }

    private boolean handle(MotionEvent event) {
        if (replaying)
            return false;
        final int action = event.getActionMasked();

        if (action == MotionEvent.ACTION_DOWN) {
            // A finger or a pen is ATAK's, untouched: on a phone this listener must not
            // exist. Only a mouse press is taken. Known by its tool type first: under the
            // Android Emulator, ClickRepair re-issues the tablet's presses with the
            // touchscreen source (or no text field could be clicked into) and the mouse
            // tool type is what survives. The source check keeps scrcpy's mouse, whose
            // presses arrive with the mouse source (2026-09-26).
            if (event.getToolType(0) != MotionEvent.TOOL_TYPE_MOUSE
                    && !event.isFromSource(InputDevice.SOURCE_MOUSE)) {
                pressed = false;
                return false;
            }
            // Something ATAK lets you drag is under the pointer -- a range-and-bearing
            // end, a route point, a shape vertex in edit mode: the press is ATAK's, or
            // the drag moves the map instead of the thing (2026-09-26).
            if (draggableUnder(event)) {
                pressed = false;
                if (verbose)
                    Log.d(TAG, "press on a draggable item; left to ATAK");
                return false;
            }
            // The press MUST be consumed. ATAK's long-press handling sets _inGesture, and
            // once that is set MapView routes every touch straight to its own controller
            // and skips plugin listeners entirely -- so a press-and-hold-then-drag was
            // being taken back mid-drag. Owning the press is the only way to keep the
            // whole gesture.
            pressed = true;
            panning = false;
            lastX = event.getX();
            lastY = event.getY();
            if (verbose)
                Log.d(TAG, "DOWN src=0x" + Integer.toHexString(event.getSource())
                        + " tool=" + event.getToolType(0)
                        + " buttons=0x" + Integer.toHexString(event.getButtonState()));
            releaseDown();
            downCopy = MotionEvent.obtain(event);
            return true;
        }

        if (action == MotionEvent.ACTION_MOVE) {
            if (!pressed || event.getPointerCount() > 1)
                return false; // a pinch is ATAK's, not ours
            final float dx = event.getX() - lastX;
            final float dy = event.getY() - lastY;
            if (!panning) {
                if (Math.abs(dx) < DRAG_SLOP && Math.abs(dy) < DRAG_SLOP)
                    return true;
                panning = true;
                if (verbose)
                    Log.d(TAG, "pan start at " + (int) event.getX() + "," + (int) event.getY());
            }
            lastX = event.getX();
            lastY = event.getY();
            if (dx != 0f || dy != 0f) {
                try {
                    mapView.getMapController().panBy(DIRECTION * dx, DIRECTION * dy, false);
                } catch (Exception e) {
                    Log.w(TAG, "panBy failed", e);
                }
            }
            return true;
        }

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            final boolean wasPanning = panning;
            pressed = false;
            panning = false;
            if (wasPanning) {
                if (verbose)
                    Log.d(TAG, "pan end");
            } else if (action == MotionEvent.ACTION_UP) {
                // Never moved, so it was a tap. Hand ATAK the real press and release it
                // never saw, rather than a synthesised click: its own controller then does
                // the hit testing, so tapping a marker still opens the marker.
                replayTap(event);
            }
            releaseDown();
            return true;
        }

        return false;
    }

    /**
     * Gives ATAK back the tap it was denied, through the map view's own touch entry, so it
     * goes down ATAK's listener queue in ATAK's order. Handing it to the map touch
     * controller alone skipped the widget layer: a radial menu opened, and clicking its
     * buttons did nothing (2026-09-26).
     */
    private void replayTap(MotionEvent up) {
        if (downCopy == null)
            return;
        replaying = true;
        try {
            mapView.onTouchEvent(downCopy);
            mapView.onTouchEvent(up);
            if (verbose)
                Log.d(TAG, "tap replayed at " + (int) up.getX() + "," + (int) up.getY());
        } catch (Exception e) {
            Log.w(TAG, "tap replay failed", e);
        } finally {
            replaying = false;
        }
    }

    /**
     * The same query ATAK's own touch controller makes on a press (16 dp around the
     * point, map items only), and the same test it uses for a drag: the "drag" meta.
     */
    private boolean draggableUnder(MotionEvent event) {
        try {
            final HitTestQueryParameters params = new HitTestQueryParameters(
                    mapView.getGLSurface(), event.getX(), event.getY(),
                    16f * mapView.getResources().getDisplayMetrics().density,
                    MapRenderer2.DisplayOrigin.UpperLeft);
            params.limit = 10;
            params.resultFilter = new MapItemResultFilter();
            final SortedSet<MapItem> hits = mapView.getRootGroup().deepHitTest(mapView, params);
            if (hits != null)
                for (MapItem item : hits)
                    if (item.getMetaBoolean("drag", false))
                        return true;
        } catch (Exception e) {
            Log.w(TAG, "hit test failed; treating the press as a pan", e);
        }
        return false;
    }

    private void releaseDown() {
        if (downCopy != null) {
            downCopy.recycle();
            downCopy = null;
        }
    }
}

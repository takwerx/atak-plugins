
package com.atakmap.android.cursorwerx;

import android.view.MotionEvent;
import android.view.View;

import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;

/**
 * Middle-button drag pans the map, the way every desktop map does it.
 *
 * <p>A left drag on the map is a touch drag, which ATAK already uses for its own tools, so
 * the middle button is the one that can be taken without arguing with anything.
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
        Log.d(TAG, "pan control detached");
    }

    private boolean handle(MotionEvent event) {
        final int action = event.getActionMasked();

        if (action == MotionEvent.ACTION_DOWN) {
            // Deliberately NOT consumed. ATAK sees the press, so taps, marker presses and
            // tool interactions all still begin normally; this only remembers where it
            // started, in case the press turns into a drag.
            pressed = true;
            panning = false;
            lastX = event.getX();
            lastY = event.getY();
            return false;
        }

        if (action == MotionEvent.ACTION_MOVE) {
            if (!pressed || event.getPointerCount() > 1)
                return false; // a pinch is ATAK's, not ours
            final float dx = event.getX() - lastX;
            final float dy = event.getY() - lastY;
            if (!panning) {
                if (Math.abs(dx) < DRAG_SLOP && Math.abs(dy) < DRAG_SLOP)
                    return false;
                panning = true;
                if (verbose)
                    Log.d(TAG, "pan start at " + (int) event.getX() + "," + (int) event.getY());
            }
            lastX = event.getX();
            lastY = event.getY();
            if (dx == 0f && dy == 0f)
                return true;
            try {
                mapView.getMapController().panBy(DIRECTION * dx, DIRECTION * dy, false);
            } catch (Exception e) {
                Log.w(TAG, "panBy failed", e);
                panning = false;
                return false;
            }
            // Consuming every move is the point: ATAK's GestureDetector never sees them,
            // so onScroll never fires, so there is no slop to push through on the way in
            // and no onFling inertia on the way out. The map tracks the cursor 1:1.
            return true;
        }

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            final boolean wasPanning = panning;
            pressed = false;
            panning = false;
            if (wasPanning && verbose)
                Log.d(TAG, "pan end");
            // Consumed only after a real drag, so the release does not also register as a
            // tap and select whatever happened to be under the cursor.
            return wasPanning;
        }

        return false;
    }
}

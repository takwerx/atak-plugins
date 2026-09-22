
package com.atakmap.android.cursorwerx;

import android.app.Activity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;

/**
 * Keeps the map from stealing the wheel when the pointer is over ATAK's own UI.
 *
 * <p>Why this is needed. {@code MapActivity.onGenericMotionEvent} forwards any generic
 * motion event the view hierarchy did not report as consumed straight to the map:
 *
 * <pre>return getMapView() != null &amp;&amp; getMapView().onGenericMotionEvent(event)
 *         || super.onGenericMotionEvent(event);</pre>
 *
 * So a wheel tick reaches the map <b>wherever the cursor is</b>. Over the toolbar the
 * operator sees both things happen at once: the toolbar scrolls and the map zooms
 * underneath it (reported 2026-09-22, "it does both actually").
 *
 * <p>Why it can be fixed from a plugin. {@code MapView.onGenericMotionEvent} walks its
 * registered listeners before doing anything itself and returns on the first one that
 * consumes the event. Cursorwerx is such a listener, so returning true here is a veto on
 * the zoom -- and the scrolling the view hierarchy already did earlier in dispatch still
 * stands. No {@code Window.Callback} wrapper, no synthesised drags.
 *
 * <p>This deliberately only vetoes. It does not scroll anything itself: anything that
 * scrolls on the wheel has already scrolled by the time this runs, and scrolling again
 * here would double it.
 */
public final class PointerRouter {

    private static final String TAG = "Cursorwerx";

    private final MapView mapView;
    private final Activity activity;
    private View.OnGenericMotionListener listener;
    private boolean verbose;

    public PointerRouter(MapView mapView) {
        this.mapView = mapView;
        this.activity = (Activity) mapView.getContext();
    }

    /** Logs every wheel tick and where it landed. For bring-up only. */
    public void setVerbose(boolean v) {
        this.verbose = v;
    }

    public void attach() {
        if (listener != null)
            return;
        listener = new View.OnGenericMotionListener() {
            @Override
            public boolean onGenericMotion(View v, MotionEvent event) {
                // Only the wheel. Hover moves arrive here too and must stay cheap:
                // this runs on the UI thread for every pointer sample.
                if (event.getAction() != MotionEvent.ACTION_SCROLL)
                    return false;
                final boolean overUi = isOverUi(event.getRawX(), event.getRawY());
                if (!overUi && zoomAtCursor(event))
                    return true;
                if (verbose)
                    Log.d(TAG, "wheel at " + (int) event.getRawX() + ","
                            + (int) event.getRawY() + " -> "
                            + (overUi ? "UI (map vetoed): " + describe(lastHit) : "map"));
                return overUi;
            }
        };
        mapView.addOnGenericMotionListener(listener);
        Log.d(TAG, "pointer router attached");
    }

    /** Symmetric with {@link #attach()}; a reload must leave no listener behind. */
    public void detach() {
        if (listener == null)
            return;
        mapView.removeOnGenericMotionListener(listener);
        listener = null;
        Log.d(TAG, "pointer router detached");
    }

    /**
     * True when the topmost view under the pointer is not the map.
     *
     * <p>ATAK's toolbar and the dropdown panes are ordinary Android views that sit beside
     * or above {@link MapView} in the hierarchy, so "is the hit inside MapView" is the
     * whole question. Note this cannot see ATAK's GL-drawn widgets, which live inside the
     * map surface -- those are a separate problem.
     */
    private boolean isOverUi(float rawX, float rawY) {
        final View root = activity.findViewById(android.R.id.content);
        if (root == null)
            return false;
        lastHit = null;
        // The map's own ancestors (map_parent, atak_app_nav, content...) contain every
        // point on the map and several carry a background, so they must never count as
        // UI. Collected per event; the chain is a handful of views deep.
        mapChain.clear();
        for (View p = mapView; p != null; ) {
            mapChain.add(p);
            final Object parent = p.getParent();
            p = (parent instanceof View) ? (View) parent : null;
        }
        final View hit = solidAt(root, (int) rawX, (int) rawY);
        lastHit = hit;
        return hit != null;
    }

    /**
     * How far one wheel notch zooms. ATAK's own step is a jump -- the operator's words
     * were "one click and boom" -- so the wheel is taken over here and applied gently.
     * A notch multiplies the map scale by this, so 1.15 is about 15% per click.
     */
    private double zoomStep = 1.15d;

    public void setZoomStep(double step) {
        if (step > 1.0d)
            this.zoomStep = step;
    }

    /**
     * Zooms the map by one gentle step, anchored under the cursor rather than the centre
     * of the screen -- {@code AtakMapController.zoomBy} keeps the pixel at (x,y) in place
     * across the zoom, which is what every desktop map does and ATAK does not.
     *
     * @return true when the wheel was handled here, so ATAK's coarser zoom never runs.
     */
    private boolean zoomAtCursor(MotionEvent event) {
        final float notches = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
        if (notches == 0f)
            return false;
        try {
            final int[] loc = new int[2];
            mapView.getLocationOnScreen(loc);
            // zoomBy wants upper-left view coordinates, so take the cursor back into
            // the map's own space rather than the screen's.
            final float x = event.getRawX() - loc[0];
            final float y = event.getRawY() - loc[1];
            final double factor = Math.pow(zoomStep, notches);
            mapView.getMapController().zoomBy(factor, x, y, false);
            if (verbose)
                Log.d(TAG, "zoom x" + String.format(java.util.Locale.US, "%.3f", factor)
                        + " at " + (int) x + "," + (int) y);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "zoom at cursor failed; leaving it to ATAK", e);
            return false;
        }
    }

    private View lastHit;
    private final java.util.HashSet<View> mapChain = new java.util.HashSet<>();

    /** For the bring-up log: what the pointer actually landed on. */
    private String describe(View v) {
        if (v == null)
            return "nothing";
        String id = "";
        try {
            if (v.getId() != View.NO_ID)
                id = "#" + v.getResources().getResourceEntryName(v.getId());
        } catch (Exception ignored) {
        }
        return v.getClass().getSimpleName() + id;
    }

    /**
     * The deepest visible view at the point that would actually take the event, skipping
     * the map's own subtree entirely.
     *
     * <p>The first cut of this returned the deepest view of any kind, and vetoed the map
     * everywhere: ATAK stacks full-screen transparent containers over the map, and a
     * container that draws nothing and takes no input still contains every point. So a
     * view only counts when it has a background, takes clicks, or scrolls.
     */
    private View solidAt(View v, int x, int y) {
        if (v == mapView || v.getVisibility() != View.VISIBLE)
            return null;
        final int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        if (x < loc[0] || x >= loc[0] + v.getWidth()
                || y < loc[1] || y >= loc[1] + v.getHeight())
            return null;
        if (v instanceof ViewGroup) {
            final ViewGroup g = (ViewGroup) v;
            for (int i = g.getChildCount() - 1; i >= 0; i--) {
                final View hit = solidAt(g.getChildAt(i), x, y);
                if (hit != null)
                    return hit;
            }
        }
        // A view on the map's own parent chain is the map's frame, not UI over it.
        return (!mapChain.contains(v) && solid(v)) ? v : null;
    }

    private static boolean solid(View v) {
        return v.getBackground() != null
                || v.isClickable()
                || v.isLongClickable()
                || v.isScrollContainer()
                || v.canScrollVertically(1) || v.canScrollVertically(-1);
    }
}


package com.atakmap.android.cursorwerx;

import android.app.Activity;
import android.os.Build;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.AbsListView;

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
 * <p>Over UI it also scrolls, but only what nothing else did. MapActivity forwards the
 * wheel here only when the view hierarchy did not consume it, so a list that scrolled
 * itself never gets here. Under the Android Emulator's virtio tablet the panes did not
 * scroll themselves at all (2026-09-26): the log showed every wheel over a pane arriving
 * here, vetoed, and nothing moving. So the nearest ancestor that can move that way is
 * scrolled from here instead.
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
                final String scrolled = overUi ? scrollUnder(event) : null;
                if (verbose)
                    Log.d(TAG, "wheel " + event.getAxisValue(MotionEvent.AXIS_VSCROLL) + " at " + (int) event.getRawX() + ","
                            + (int) event.getRawY() + " -> "
                            + (overUi ? "UI (map vetoed): " + describe(lastHit)
                                    + (scrolled != null ? ", scrolled " + scrolled : ", nothing to scroll")
                                    : "map"));
                return overUi;
            }
        };
        mapView.addOnGenericMotionListener(listener);
        hideGuestPointer(true);
        Log.d(TAG, "pointer router attached");
    }

    /** The Android Emulator's pointer device, which the host draws its own cursor for. */
    private static final String EMULATOR_TABLET = "QEMU Virtio Tablet";

    /**
     * In the Android Emulator the Mac draws its cursor and Android draws another one at
     * the same spot: two arrows (2026-09-26). Setting the window's pointer icon to none
     * removed the arrow but not the icons views choose for themselves, which Android
     * resolves child-first: a hand over ATAK's toolbar buttons and its Plugins list, an
     * I-beam over text fields ("still have that hand under the cursor"). So a transparent
     * view that takes no touch, no hover and no focus is laid over the whole content, on
     * top, with the none icon: it is the first view under the pointer everywhere, and its
     * answer wins. Only under the emulator: on DeX or a Chromebook Android's cursor is the
     * only cursor there is.
     */
    private void hideGuestPointer(boolean hide) {
        if (Build.VERSION.SDK_INT < 24)
            return;
        boolean emulator = false;
        for (int id : InputDevice.getDeviceIds()) {
            final InputDevice d = InputDevice.getDevice(id);
            if (d != null && EMULATOR_TABLET.equals(d.getName()))
                emulator = true;
        }
        if (!emulator)
            return;
        final View decor = activity.getWindow().getDecorView();
        final ViewGroup content = activity.findViewById(android.R.id.content);
        if (hide) {
            decor.setPointerIcon(PointerIcon.getSystemIcon(activity, PointerIcon.TYPE_NULL));
            if (pointerShroud == null && content != null) {
                pointerShroud = new View(activity);
                pointerShroud.setClickable(false);
                pointerShroud.setFocusable(false);
                pointerShroud.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                pointerShroud.setPointerIcon(PointerIcon.getSystemIcon(activity, PointerIcon.TYPE_NULL));
                content.addView(pointerShroud, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
        } else {
            decor.setPointerIcon(null);
            if (pointerShroud != null) {
                if (content != null)
                    content.removeView(pointerShroud);
                pointerShroud = null;
            }
        }
        Log.d(TAG, (hide ? "hid" : "restored") + " Android's pointer (emulator tablet present)");
    }

    private View pointerShroud;

    /** Symmetric with {@link #attach()}; a reload must leave no listener behind. */
    public void detach() {
        if (listener == null)
            return;
        mapView.removeOnGenericMotionListener(listener);
        listener = null;
        hideGuestPointer(false);
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
    /**
     * Wheel travel since the last zoom step, in the device's own units. A mouse click is
     * one event of one notch and zooms at once; a trackpad's two-finger scroll is a
     * stream of small events, and on the laptop every one of them zoomed a step, so a
     * short swipe flew through many levels (2026-09-26). Steps are taken per notch of
     * travel, and no faster than one per {@link #MIN_STEP_MS}.
     */
    private float travel;
    private long lastStepAt;
    private static final long MIN_STEP_MS = 90;

    /**
     * One notch in the device's units. The Android Emulator's virtio tablet reports a
     * mouse click as 8; scrcpy and most Android mice report 1.
     */
    private static float notch(MotionEvent event) {
        final android.view.InputDevice d = event.getDevice();
        return d != null && "QEMU Virtio Tablet".equals(d.getName()) ? 8f : 1f;
    }

    private boolean zoomAtCursor(MotionEvent event) {
        final float axis = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
        if (axis == 0f)
            return false;
        // Direction change resets the travel, so a reversal answers at once.
        if (Math.signum(axis) != Math.signum(travel))
            travel = 0f;
        travel += axis;
        final float n = notch(event);
        final long now = android.os.SystemClock.uptimeMillis();
        if (verbose)
            Log.d(TAG, "wheel axis " + axis + " travel " + travel + " notch " + n
                    + " device " + (event.getDevice() != null ? event.getDevice().getName() : "?"));
        if (Math.abs(travel) < n || now - lastStepAt < MIN_STEP_MS)
            return true; // taken, no step yet
        final float notches = Math.signum(travel);
        travel = 0f;
        lastStepAt = now;
        try {
            // At either zoom limit the zoom does nothing but the cursor anchor still
            // shifts the map, and repeated at the globe it spun the whole earth.
            final double scale = mapView.getMapScale();
            if ((notches > 0f && scale >= mapView.getMaxMapScale() * 0.999d)
                    || (notches < 0f && scale <= mapView.getMinMapScale() * 1.001d))
                return true;
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

    /**
     * Scrolls the nearest view at or above {@link #lastHit} that can move the way the wheel
     * turned, by Android's own step for one notch.
     *
     * @return what was scrolled and by how much, for the log; null when nothing could.
     */
    private String scrollUnder(MotionEvent event) {
        final float notches = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
        if (notches == 0f || lastHit == null)
            return null;
        // Wheel up (positive) brings the content above into view.
        final int dir = notches > 0f ? -1 : 1;
        for (View p = lastHit; p != null && !mapChain.contains(p); ) {
            if (p.canScrollVertically(dir)) {
                final int dy = Math.round(-notches * scrollStep(p));
                if (p instanceof AbsListView)
                    ((AbsListView) p).scrollListBy(dy); // scrollBy would move the list's frame
                else
                    p.scrollBy(0, dy);
                return describe(p) + " by " + dy;
            }
            final Object parent = p.getParent();
            p = (parent instanceof View) ? (View) parent : null;
        }
        return null;
    }

    private static float scrollStep(View v) {
        if (Build.VERSION.SDK_INT >= 26)
            return ViewConfiguration.get(v.getContext()).getScaledVerticalScrollFactor();
        return 64f * v.getResources().getDisplayMetrics().density;
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

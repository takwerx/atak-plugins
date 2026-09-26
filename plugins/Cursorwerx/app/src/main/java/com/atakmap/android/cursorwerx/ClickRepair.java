package com.atakmap.android.cursorwerx;

import android.app.Activity;
import android.os.Build;
import android.view.ActionMode;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SearchEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import com.atakmap.coremap.log.Log;

/**
 * Makes a mouse click land in a text field under the Android Emulator.
 *
 * <p>The emulator's pointer is a virtio tablet that Android classes as MOUSE|STYLUS. A
 * press from it on an {@code EditText} is consumed and never focuses the field, so no
 * keystroke has anywhere to go: "typing in fields not working" (2026-09-26). Buttons and
 * the map were fine. Measured with the same press re-issued in different shapes from this
 * wrapper, on the same field, with ATAK restarted between runs:
 *
 * <ul>
 *   <li>source MOUSE, tool MOUSE, button released on UP: consumed, no focus;</li>
 *   <li>same, with the tablet's hover events withheld: no focus;</li>
 *   <li>source TOUCHSCREEN, tool FINGER: focus, typing works;</li>
 *   <li>source TOUCHSCREEN, tool MOUSE: focus, typing works.</li>
 * </ul>
 *
 * So it is the mouse source bit on the press itself, not the button state, the tool type
 * or hover, and the exact rule in Android's text editing stack was not found. The plugin
 * can't fix that stack; it can hand ATAK a press it accepts. Every press from the tablet
 * (DOWN, MOVE, UP, CANCEL) is re-issued as a touchscreen press that keeps the mouse tool
 * type and the button state, so {@link PanControl} still knows a mouse from a finger and
 * a right button from a left. Hover and the wheel stay mouse events, as a plain mouse
 * without the stylus half, which also takes away the stylus hover icon over text.
 *
 * <p>Nothing here runs for a finger, a pen, or a real mouse: only for events carrying both
 * the mouse and stylus source bits, which is the emulator's tablet and nothing else seen.
 *
 * <p>This is the one place Cursorwerx wraps {@link Window.Callback}. The wrapper delegates
 * everything to the callback it found, so another wrapper installed before or after it
 * keeps working; on detach the original is restored only if this wrapper is still the one
 * installed, otherwise the wrapper stays in the chain as a plain pass-through.
 */
public final class ClickRepair {

    private static final String TAG = "Cursorwerx";

    private final Activity activity;
    private Wrapper wrapper;
    private boolean verbose;

    public ClickRepair(Activity activity) {
        this.activity = activity;
    }

    public void setVerbose(boolean v) {
        this.verbose = v;
    }

    public void attach() {
        if (wrapper != null)
            return;
        final Window w = activity.getWindow();
        final Window.Callback original = w.getCallback();
        if (original == null) {
            Log.w(TAG, "no window callback to wrap; click repair off");
            return;
        }
        wrapper = new Wrapper(original);
        w.setCallback(wrapper);
        Log.d(TAG, "click repair attached");
    }

    /** Symmetric with {@link #attach()}; a reload must leave no wrapper of a dead plugin active. */
    public void detach() {
        if (wrapper == null)
            return;
        final Window w = activity.getWindow();
        if (w.getCallback() == wrapper)
            w.setCallback(wrapper.original);
        else
            wrapper.bypass = true;
        wrapper = null;
        Log.d(TAG, "click repair detached");
    }

    /** True for the emulator's tablet: a mouse that also claims to be a stylus. */
    static boolean fromTablet(MotionEvent e) {
        return e.isFromSource(InputDevice.SOURCE_MOUSE) && e.isFromSource(InputDevice.SOURCE_STYLUS);
    }

    /**
     * The event with another source, the mouse tool type, and no buttons once the pointer
     * is up. Coordinates, timing, pointer ids and flags are kept.
     */
    private static MotionEvent reshaped(MotionEvent e, int source) {
        final int n = e.getPointerCount();
        final MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[n];
        final MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[n];
        for (int i = 0; i < n; i++) {
            props[i] = new MotionEvent.PointerProperties();
            e.getPointerProperties(i, props[i]);
            props[i].toolType = MotionEvent.TOOL_TYPE_MOUSE;
            coords[i] = new MotionEvent.PointerCoords();
            e.getPointerCoords(i, coords[i]);
        }
        final int action = e.getActionMasked();
        final int buttons = (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
                ? 0 : e.getButtonState();
        return MotionEvent.obtain(e.getDownTime(), e.getEventTime(), e.getAction(), n, props, coords,
                e.getMetaState(), buttons, e.getXPrecision(), e.getYPrecision(), e.getDeviceId(),
                e.getEdgeFlags(), source, e.getFlags());
    }

    private String focusNow() {
        try {
            final View f = activity.getWindow().getDecorView().findFocus();
            if (f == null)
                return "nothing";
            String id = "";
            if (f.getId() != View.NO_ID)
                try {
                    id = "#" + f.getResources().getResourceEntryName(f.getId());
                } catch (Exception ignored) {
                }
            return f.getClass().getSimpleName() + id;
        } catch (Exception e) {
            return "?";
        }
    }

    private final class Wrapper implements Window.Callback {
        final Window.Callback original;
        volatile boolean bypass;

        Wrapper(Window.Callback original) {
            this.original = original;
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            if (bypass || !fromTablet(event))
                return original.dispatchTouchEvent(event);
            final MotionEvent fixed = reshaped(event, InputDevice.SOURCE_TOUCHSCREEN);
            try {
                final boolean handled = original.dispatchTouchEvent(fixed);
                if (verbose && event.getActionMasked() == MotionEvent.ACTION_UP)
                    Log.d(TAG, "tablet click at " + (int) event.getX() + "," + (int) event.getY()
                            + " buttons=0x" + Integer.toHexString(event.getButtonState())
                            + " -> handled=" + handled + ", focus: " + focusNow());
                return handled;
            } finally {
                fixed.recycle();
            }
        }

        @Override
        public boolean dispatchGenericMotionEvent(MotionEvent event) {
            if (bypass || !fromTablet(event))
                return original.dispatchGenericMotionEvent(event);
            final MotionEvent fixed = reshaped(event, InputDevice.SOURCE_MOUSE);
            try {
                return original.dispatchGenericMotionEvent(fixed);
            } finally {
                fixed.recycle();
            }
        }

        // Everything below is pass-through.

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            return original.dispatchKeyEvent(event);
        }

        @Override
        public boolean dispatchKeyShortcutEvent(KeyEvent event) {
            return original.dispatchKeyShortcutEvent(event);
        }

        @Override
        public boolean dispatchTrackballEvent(MotionEvent event) {
            return original.dispatchTrackballEvent(event);
        }

        @Override
        public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) {
            return original.dispatchPopulateAccessibilityEvent(event);
        }

        @Override
        public View onCreatePanelView(int featureId) {
            return original.onCreatePanelView(featureId);
        }

        @Override
        public boolean onCreatePanelMenu(int featureId, Menu menu) {
            return original.onCreatePanelMenu(featureId, menu);
        }

        @Override
        public boolean onPreparePanel(int featureId, View view, Menu menu) {
            return original.onPreparePanel(featureId, view, menu);
        }

        @Override
        public boolean onMenuOpened(int featureId, Menu menu) {
            return original.onMenuOpened(featureId, menu);
        }

        @Override
        public boolean onMenuItemSelected(int featureId, MenuItem item) {
            return original.onMenuItemSelected(featureId, item);
        }

        @Override
        public void onWindowAttributesChanged(WindowManager.LayoutParams attrs) {
            original.onWindowAttributesChanged(attrs);
        }

        @Override
        public void onContentChanged() {
            original.onContentChanged();
        }

        @Override
        public void onWindowFocusChanged(boolean hasFocus) {
            original.onWindowFocusChanged(hasFocus);
        }

        @Override
        public void onAttachedToWindow() {
            original.onAttachedToWindow();
        }

        @Override
        public void onDetachedFromWindow() {
            original.onDetachedFromWindow();
        }

        @Override
        public void onPanelClosed(int featureId, Menu menu) {
            original.onPanelClosed(featureId, menu);
        }

        @Override
        public boolean onSearchRequested() {
            return original.onSearchRequested();
        }

        @Override
        public boolean onSearchRequested(SearchEvent searchEvent) {
            return Build.VERSION.SDK_INT >= 23 && original.onSearchRequested(searchEvent);
        }

        @Override
        public ActionMode onWindowStartingActionMode(ActionMode.Callback callback) {
            return original.onWindowStartingActionMode(callback);
        }

        @Override
        public ActionMode onWindowStartingActionMode(ActionMode.Callback callback, int type) {
            return Build.VERSION.SDK_INT >= 23 ? original.onWindowStartingActionMode(callback, type) : null;
        }

        @Override
        public void onActionModeStarted(ActionMode mode) {
            original.onActionModeStarted(mode);
        }

        @Override
        public void onActionModeFinished(ActionMode mode) {
            original.onActionModeFinished(mode);
        }

        @Override
        public void onProvideKeyboardShortcuts(java.util.List<android.view.KeyboardShortcutGroup> data,
                Menu menu, int deviceId) {
            if (Build.VERSION.SDK_INT >= 24)
                original.onProvideKeyboardShortcuts(data, menu, deviceId);
        }

        @Override
        public void onPointerCaptureChanged(boolean hasCapture) {
            if (Build.VERSION.SDK_INT >= 26)
                original.onPointerCaptureChanged(hasCapture);
        }
    }
}

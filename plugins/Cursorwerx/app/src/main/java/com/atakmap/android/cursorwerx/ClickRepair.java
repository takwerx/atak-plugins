package com.atakmap.android.cursorwerx;

import android.app.Activity;
import android.app.Application;
import android.os.Build;
import android.os.Bundle;
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

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Makes a mouse click land in a text field under the Android Emulator, in every window.
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
 * <p>Measured again on 2026-11-17 with real tablet clicks written to the tablet's own evdev
 * node, so nothing re-issued them: the same MOUSE|STYLUS press, DOWN, MOVE, UP, focused a
 * field with no button held and did not with the primary button held (a Mac click always
 * holds it). That was Android's own Settings app as well as ATAK, so the rule is in
 * Android's text editing, most likely a mouse press taken for the start of a drag-select.
 * The plugin can't fix that stack; it can hand the window a press it accepts. Every press
 * from the tablet (DOWN, MOVE, UP, CANCEL) is re-issued as a touchscreen press that keeps
 * the mouse tool type and the button state, so {@link PanControl} still knows a mouse from
 * a finger and a right button from a left. Hover and the wheel stay mouse events, as a
 * plain mouse without the stylus half, which also takes away the stylus hover icon over
 * text.
 *
 * <p>Every window of ATAK's process gets this, not only the map's. Up to 0.4 only the main
 * activity's window was wrapped, and a click in any other window's text field did nothing
 * (operator, 2026-11-17): ATAK's other Activities (Settings, the server list, "Add Streaming
 * CoT Endpoint", which is {@code AddNetInfoActivity}) and every dialog (Go To and its MGRS
 * boxes are one AlertDialog, {@code CoordinateEntryCapability}). Activities are found
 * through the application's lifecycle callbacks. A dialog takes window focus from the
 * window under it, so a wrapped window losing focus is the signal to look for new windows
 * ({@code WindowInspector}, API 29), and the dialog's {@link Window} is read from its decor
 * view by reflection, as Square's Curtains library does. If that read is refused, dialogs
 * stay as they were and the log says so once.
 *
 * <p>Nothing here runs for a finger, a pen, or a real mouse: only for events carrying both
 * the mouse and stylus source bits, which is the emulator's tablet and nothing else seen.
 *
 * <p>This is the one place Cursorwerx wraps {@link Window.Callback}. A wrapper delegates
 * everything to the callback it found, so another wrapper installed before or after it
 * keeps working; on detach a window's original is restored only if our wrapper is still
 * the one installed, otherwise the wrapper stays in the chain as a plain pass-through.
 */
public final class ClickRepair {

    private static final String TAG = "Cursorwerx";

    private final Activity activity;
    /** Every window wrapped so far, weakly: a dismissed dialog takes its window with it. */
    private final Map<Window, Wrapper> wrapped = new WeakHashMap<>();
    private Application application;
    private boolean attached;
    private boolean verbose;
    /** Set once listing windows or reading a dialog's window failed; dialogs are then left alone. */
    private boolean dialogsUnreachable;
    private static Field decorWindowField;

    public ClickRepair(Activity activity) {
        this.activity = activity;
    }

    public void setVerbose(boolean v) {
        this.verbose = v;
    }

    public void attach() {
        if (attached)
            return;
        if (wrap(activity.getWindow(), true) == null) {
            Log.w(TAG, "no window callback to wrap; click repair off");
            return;
        }
        attached = true;
        activity.getWindow().getDecorView().post(clearStrayFocus);
        application = activity.getApplication();
        if (application != null)
            application.registerActivityLifecycleCallbacks(lifecycle);
        scanWindows();
        Log.d(TAG, "click repair attached");
    }

    /** Symmetric with {@link #attach()}; a reload must leave no wrapper of a dead plugin active. */
    public void detach() {
        if (!attached)
            return;
        attached = false;
        if (application != null)
            application.unregisterActivityLifecycleCallbacks(lifecycle);
        application = null;
        for (Map.Entry<Window, Wrapper> e : new ArrayList<>(wrapped.entrySet())) {
            final Window w = e.getKey();
            final Wrapper wr = e.getValue();
            if (w == null || wr == null)
                continue;
            if (w.getCallback() == wr)
                w.setCallback(wr.original);
            else
                wr.bypass = true;
        }
        wrapped.clear();
        Log.d(TAG, "click repair detached");
    }

    /** Wraps one window's callback, once. Null when the window has no callback to wrap. */
    private Wrapper wrap(Window w, boolean main) {
        if (w == null)
            return null;
        final Wrapper have = wrapped.get(w);
        if (have != null)
            return have;
        final Window.Callback original = w.getCallback();
        if (original == null)
            return null;
        final Wrapper wr = new Wrapper(w, original, main);
        w.setCallback(wr);
        wrapped.put(w, wr);
        if (verbose)
            Log.d(TAG, "click repair on " + (main ? "the map window"
                    : original.getClass().getName()));
        return wr;
    }

    /** ATAK's other Activities: Settings, the server list, the server editor. */
    private final Application.ActivityLifecycleCallbacks lifecycle =
            new Application.ActivityLifecycleCallbacks() {
                @Override
                public void onActivityCreated(Activity a, Bundle b) {
                    if (attached)
                        wrap(a.getWindow(), false);
                }

                @Override
                public void onActivityResumed(Activity a) {
                    if (attached) {
                        wrap(a.getWindow(), false);
                        scanWindows();
                    }
                }

                @Override
                public void onActivityStarted(Activity a) {
                }

                @Override
                public void onActivityPaused(Activity a) {
                }

                @Override
                public void onActivityStopped(Activity a) {
                }

                @Override
                public void onActivitySaveInstanceState(Activity a, Bundle b) {
                }

                @Override
                public void onActivityDestroyed(Activity a) {
                }
            };

    /**
     * Wraps every window of the process not wrapped yet, which in practice means dialogs.
     * A root that is not a decor view (a PopupWindow) has no Window.Callback and is left
     * alone.
     */
    private void scanWindows() {
        if (!attached || dialogsUnreachable || Build.VERSION.SDK_INT < 29)
            return;
        final List<View> roots;
        try {
            roots = android.view.inspector.WindowInspector.getGlobalWindowViews();
        } catch (RuntimeException | LinkageError e) {
            dialogsUnreachable = true;
            Log.w(TAG, "cannot list windows; clicks in dialogs stay unrepaired: " + e);
            return;
        }
        for (View root : roots) {
            final Window w = windowOf(root);
            if (w != null)
                wrap(w, false);
        }
    }

    /** The Window behind a decor view. Not public API: read from {@code DecorView.mWindow}. */
    private Window windowOf(View root) {
        if (root == null || !root.getClass().getName().endsWith(".DecorView"))
            return null;
        try {
            if (decorWindowField == null) {
                final Field f = root.getClass().getDeclaredField("mWindow");
                f.setAccessible(true);
                decorWindowField = f;
            }
            final Object w = decorWindowField.get(root);
            return w instanceof Window ? (Window) w : null;
        } catch (Exception | LinkageError e) {
            dialogsUnreachable = true;
            Log.w(TAG, "cannot read a dialog's window; clicks in dialogs stay unrepaired: " + e);
            return null;
        }
    }

    /** Another window took focus from one of ours: wrap it if it is new. */
    private final Runnable rescan = new Runnable() {
        @Override
        public void run() {
            scanWindows();
        }
    };

    /**
     * A window that comes to the front after a keyboard or a programmatic switch is in
     * keyboard mode, and Android then shows whichever button holds focus with a focus
     * rectangle: ATAK's compass, top left, sat in a box after every start (2026-09-26).
     * With a pointer there is no keyboard navigation to preserve, so a focused view that
     * is not a text field is unfocused. Text fields keep it: that is the user typing.
     * The map window only; a dialog keeps its default button.
     */
    private final Runnable clearStrayFocus = new Runnable() {
        @Override
        public void run() {
            try {
                final View f = activity.getWindow().getDecorView().findFocus();
                if (f != null && !(f instanceof android.widget.EditText)) {
                    f.clearFocus();
                    if (verbose)
                        Log.d(TAG, "cleared stray focus from " + f.getClass().getSimpleName());
                }
            } catch (Exception e) {
                Log.w(TAG, "clearing focus failed", e);
            }
        }
    };

    /** True for the emulator's tablet: a mouse that also claims to be a stylus. */
    static boolean fromTablet(MotionEvent e) {
        return e.isFromSource(InputDevice.SOURCE_MOUSE) && e.isFromSource(InputDevice.SOURCE_STYLUS);
    }

    /**
     * The event with another source, the mouse tool type, and no buttons once the pointer
     * is up. Coordinates, timing, pointer ids and flags are kept.
     */
    private static MotionEvent reshaped(MotionEvent e, int source) {
        return reshaped(e, source, MotionEvent.TOOL_TYPE_MOUSE);
    }

    private static MotionEvent reshaped(MotionEvent e, int source, int toolType) {
        final int n = e.getPointerCount();
        final MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[n];
        final MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[n];
        for (int i = 0; i < n; i++) {
            props[i] = new MotionEvent.PointerProperties();
            e.getPointerProperties(i, props[i]);
            props[i].toolType = toolType;
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

    /** Whether a WebView lies under a window point, looking through views that do not
     *  take touches (the pointer shroud, plain overlays), the way ViewGroup dispatch does. */
    private static boolean webViewUnder(Window w, float x, float y) {
        return webViewUnder(w.getDecorView(), x, y, new int[2]);
    }

    private static boolean webViewUnder(View v, float x, float y, int[] loc) {
        if (v.getVisibility() != View.VISIBLE)
            return false;
        v.getLocationInWindow(loc);
        if (x < loc[0] || x >= loc[0] + v.getWidth() || y < loc[1] || y >= loc[1] + v.getHeight())
            return false;
        if (v instanceof android.webkit.WebView)
            return true;
        if (!(v instanceof android.view.ViewGroup))
            return false;
        final android.view.ViewGroup g = (android.view.ViewGroup) v;
        for (int i = g.getChildCount() - 1; i >= 0; i--)
            if (webViewUnder(g.getChildAt(i), x, y, loc))
                return true;
        return false;
    }

    private static String focusNow(Window w) {
        try {
            final View f = w.getDecorView().findFocus();
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
        final Window window;
        final Window.Callback original;
        /** The map's window, the only one where stray focus is cleared. */
        final boolean main;
        volatile boolean bypass;
        /** The press in progress lands on a WebView and goes through as a finger. */
        private boolean pressOnWeb;

        Wrapper(Window window, Window.Callback original, boolean main) {
            this.window = window;
            this.original = original;
            this.main = main;
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            if (bypass || !fromTablet(event))
                return original.dispatchTouchEvent(event);
            final int action = event.getActionMasked();
            // A WebView (Esri's sign-in form in Feature Layer, 2026-09-26) drops a press
            // that is touchscreen-sourced with the mouse tool type: "Show" and "Sign In"
            // did nothing. It takes a finger. Decided on DOWN, kept for the press.
            if (action == MotionEvent.ACTION_DOWN)
                pressOnWeb = webViewUnder(window, event.getX(), event.getY());
            final MotionEvent fixed = reshaped(event, InputDevice.SOURCE_TOUCHSCREEN,
                    pressOnWeb ? MotionEvent.TOOL_TYPE_FINGER : MotionEvent.TOOL_TYPE_MOUSE);
            try {
                final boolean handled = original.dispatchTouchEvent(fixed);
                if (verbose && action == MotionEvent.ACTION_UP)
                    Log.d(TAG, "tablet click at " + (int) event.getX() + "," + (int) event.getY()
                            + " buttons=0x" + Integer.toHexString(event.getButtonState())
                            + (pressOnWeb ? " as finger (WebView)" : "")
                            + (main ? "" : " in " + original.getClass().getSimpleName())
                            + " -> handled=" + handled + ", focus: " + focusNow(window));
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
                    pressOnWeb = false;
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
            if (bypass)
                return;
            if (hasFocus && main)
                window.getDecorView().post(clearStrayFocus);
            else if (!hasFocus)
                window.getDecorView().post(rescan);
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

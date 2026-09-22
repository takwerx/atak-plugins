
package com.atakmap.android.cursorwerx;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;

/**
 * A Back control that actually works, and Escape bound to the same action.
 *
 * <p>Why this exists: ATAK's own faux navigation bar is broken on Android 14.
 * {@code FauxNavBar.backAction} dispatches two hand-built {@link KeyEvent}s straight at
 * the activity. Android only runs {@code onBackPressed()} from {@code Activity.onKeyUp}
 * when the up event reports {@code isTracking()}, and that flag is attached by the real
 * input dispatcher when it links an up event to the down event it already saw. Two loose
 * events are never linked, so the flag is never set and the press goes nowhere. Measured
 * on the bench 2026-09-22: ATAK's button leaves an open pane open; {@code input keyevent 4}
 * through the real input system closes it.
 *
 * <p>So this calls {@link Activity#onBackPressed()} directly, which is exactly where a
 * real Back key ends up, and skips the event plumbing that cannot be faked from inside
 * the process.
 *
 * <p>The button sits on the left edge because ATAK's own (non-working) bar is on the
 * right, and a second control on top of it would be unreadable.
 */
public final class BackControl {

    private static final String TAG = "Cursorwerx";

    private final MapView mapView;
    private final Activity activity;
    private View button;
    private View.OnKeyListener keyListener;

    public BackControl(MapView mapView) {
        this.mapView = mapView;
        this.activity = (Activity) mapView.getContext();
    }

    /** Adds the button and binds Escape. Safe to call once per plugin start. */
    public void attach() {
        mapView.post(new Runnable() {
            @Override
            public void run() {
                addButton();
                bindEscape();
            }
        });
    }

    /**
     * Removes everything this added. Symmetric with {@link #attach()} so a plugin reload
     * leaves nothing behind -- a stale view holding a dead plugin classloader is a leak
     * that compounds on every re-Load.
     */
    public void detach() {
        mapView.post(new Runnable() {
            @Override
            public void run() {
                if (keyListener != null) {
                    mapView.removeOnKeyListener(keyListener);
                    keyListener = null;
                }
                if (button != null) {
                    final ViewGroup parent = (ViewGroup) button.getParent();
                    if (parent != null)
                        parent.removeView(button);
                    button = null;
                }
            }
        });
    }

    /** The one action. A real Back key reaches exactly here, via Activity.onKeyUp. */
    private void back() {
        try {
            Log.d(TAG, "back: calling onBackPressed on " + activity.getClass().getName());
            activity.onBackPressed();
            Log.d(TAG, "back: onBackPressed returned");
        } catch (Exception e) {
            Log.w(TAG, "back failed", e);
        }
    }

    private void addButton() {
        if (button != null)
            return;
        final ViewGroup root = activity.findViewById(android.R.id.content);
        if (root == null) {
            Log.w(TAG, "no content root; back button not added");
            return;
        }
        final Context ctx = activity;
        final TextView b = new TextView(ctx);
        b.setText("‹"); // a single left angle quote reads as Back at any size
        b.setTextColor(Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);

        final GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(6));
        // ATAK's own widget backing: black at 60%, so this reads as part of the app.
        bg.setColor(0x99000000);
        bg.setStroke(dp(1), 0x44FFFFFF);
        b.setBackground(bg);

        final FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(34), dp(54));
        lp.gravity = Gravity.LEFT | Gravity.CENTER_VERTICAL;
        lp.leftMargin = dp(4);
        b.setLayoutParams(lp);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.d(TAG, "back button clicked");
                back();
            }
        });
        root.addView(b);
        button = b;
        Log.d(TAG, "back button attached");
    }

    private void bindEscape() {
        if (keyListener != null)
            return;
        // MapView publishes add/remove for key listeners, so this needs no Window.Callback
        // wrapper and no single slot to fight over.
        keyListener = new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (keyCode == KeyEvent.KEYCODE_ESCAPE
                        && event.getAction() == KeyEvent.ACTION_UP) {
                    back();
                    return true;
                }
                return false;
            }
        };
        mapView.addOnKeyListener(keyListener);
        Log.d(TAG, "escape bound to back");
    }

    private int dp(int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }
}

package com.atakmap.android.atmosphere.ui;

import android.content.Context;
import android.content.Intent;
import android.view.View;

import com.atakmap.android.dropdown.DropDown.OnStateListener;
import com.atakmap.android.dropdown.DropDownReceiver;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;

/**
 * The pane's host: a drop-down that opens at half width and can be dragged or
 * toggled out to full width and back.
 *
 * <p>Not the stable {@code gov.tak.api.ui.Pane}: that carries only a preferred size
 * fixed when it is built, cannot be resized after it is shown, and never hears about
 * the handle being dragged. Full width was asked for, and only the drop-down API
 * has it (the same reason IAP, MAST and Signal DF use it). The pane's content lives
 * in {@link AtmospherePane}; this class only hosts it.
 */
public class AtmosphereDropDown extends DropDownReceiver implements OnStateListener {

    private static final String TAG = "AtmosphereDropDown";

    private final View root;
    private final AtmospherePane pane;

    /** Current size, tracked so the wide/narrow decision knows where it is. */
    private double currentWidth = HALF_WIDTH;
    private double currentHeight = FULL_HEIGHT;

    public AtmosphereDropDown(MapView mapView, View root, AtmospherePane pane) {
        super(mapView);
        this.root = root;
        this.pane = pane;
    }

    public void show() {
        if (isVisible())
            return;
        setRetain(true);
        // ignoreBackButton is FALSE. The manager calls onBackButtonPressed() either way,
        // so Back still narrows a wide pane first; what the flag does is gate the close
        // that follows a false answer -- closeRightDropDown(false, true) returns without
        // closing a dropdown that ignores Back. With it true, from 2026-09-21 to
        // 2026-09-26, Back could never close this pane at all: the operator pressed it
        // six times and reported ATAK locked up. Retention under a chooser or another
        // dropdown comes from setRetain(true) above, not from this flag.
        showDropDown(root, HALF_WIDTH, FULL_HEIGHT, FULL_WIDTH, HALF_HEIGHT, false, this);
        pane.onShown();
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        // Opened directly by the toolbar item, not by broadcast.
    }

    /**
     * The handle was dragged or tapped. ATAK asks, the pane answers. Wide is
     * {@code FULL_WIDTH} less the handle, never {@code FULL_WIDTH} itself: that strip
     * is where the handle lives, and covering it leaves no way back.
     */
    @Override
    protected void onStateRequested(int state) {
        if (state == DROPDOWN_STATE_FULLSCREEN)
            goWide();
        else if (state == DROPDOWN_STATE_NORMAL)
            goNarrow();
    }

    /** Toggle from the pane's own control. */
    public void toggleWide() {
        if (isWide())
            goNarrow();
        else
            goWide();
    }

    /**
     * Open wide, for a page whose picture wants the room: a tapped area's ERC chart.
     * A closed pane is opened at that size; a resize before it is showing is lost, and
     * the chart came up at half width (XCover, 2026-10-05).
     */
    public void showWide() {
        if (isVisible()) {
            if (!isWide())
                goWide();
            return;
        }
        setRetain(true);
        showDropDown(root, FULL_WIDTH - HANDLE_THICKNESS_LANDSCAPE, FULL_HEIGHT, FULL_WIDTH,
                FULL_HEIGHT - HANDLE_THICKNESS_PORTRAIT, false, this);
        pane.onShown();
    }

    private void goWide() {
        if (!isPortrait())
            resize(FULL_WIDTH - HANDLE_THICKNESS_LANDSCAPE, FULL_HEIGHT);
        else
            resize(FULL_WIDTH, FULL_HEIGHT - HANDLE_THICKNESS_PORTRAIT);
    }

    private void goNarrow() {
        if (!isPortrait())
            resize(HALF_WIDTH, FULL_HEIGHT);
        else
            resize(FULL_WIDTH, HALF_HEIGHT);
    }

    public boolean isWide() {
        return !isPortrait()
                ? currentWidth > HALF_WIDTH + 0.01
                : currentHeight > HALF_HEIGHT + 0.01;
    }

    /** Back narrows a wide pane first; a second Back closes it. */
    @Override
    protected boolean onBackButtonPressed() {
        if (isWide()) {
            goNarrow();
            return true;
        }
        return false;
    }

    @Override
    public void onDropDownSizeChanged(double width, double height) {
        currentWidth = width;
        currentHeight = height;
        Log.d(TAG, "size " + width + " x " + height);
        pane.onPaneResized();
    }

    @Override
    public void onDropDownVisible(boolean visible) {
        if (visible)
            pane.onShown();
    }

    @Override
    public void onDropDownClose() {
        // Nothing runs while the pane is closed; the client's own work finishes on its
        // executor and the snapshot store is on disk. An armed pick is the exception:
        // it holds the map's tap listeners and must let go.
        pane.onClosed();
    }

    @Override
    public void onDropDownSelectionRemoved() {
        // no map item is selected by this pane
    }

    @Override
    protected void disposeImpl() {
        pane.dispose();
    }
}

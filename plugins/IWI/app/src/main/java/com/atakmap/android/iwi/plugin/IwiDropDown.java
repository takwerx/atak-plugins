package com.atakmap.android.iwi.plugin;

import android.content.Context;
import android.content.Intent;
import android.view.View;

import com.atakmap.android.dropdown.DropDown.OnStateListener;
import com.atakmap.android.dropdown.DropDownReceiver;
import com.atakmap.android.maps.MapView;

/**
 * The pane's host: a drop-down that opens at half width and can be dragged out to
 * full width, the way Atmosphere's does (operator, 2026-10-14: "allow this to expand
 * out ... like we have for atmosphere ... for like big buttons").
 *
 * <p>Not the stable {@code gov.tak.api.ui.Pane}: that carries only a preferred size
 * fixed when it is built and never hears about the handle being dragged. Wide is
 * {@code FULL_WIDTH} less the handle, never {@code FULL_WIDTH} itself: that strip is
 * where the handle lives, and covering it leaves no way back. Back narrows a wide
 * pane first; a second Back closes it.
 */
public class IwiDropDown extends DropDownReceiver implements OnStateListener {

    private final View root;
    private final IwiPane pane;

    private double currentWidth = HALF_WIDTH;
    private double currentHeight = FULL_HEIGHT;

    public IwiDropDown(MapView mapView, View root, IwiPane pane) {
        super(mapView);
        this.root = root;
        this.pane = pane;
    }

    public void show() {
        if (isVisible())
            return;
        // Retained under a chooser or another drop-down. ignoreBackButton stays false,
        // or Back can never close it (Atmosphere, 2026-09-26).
        setRetain(true);
        showDropDown(root, HALF_WIDTH, FULL_HEIGHT, FULL_WIDTH, HALF_HEIGHT, false, this);
        pane.onShown();
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        // Opened directly by the toolbar item, not by broadcast.
    }

    /** The handle was dragged or tapped. ATAK asks, the pane answers. */
    @Override
    protected void onStateRequested(int state) {
        if (state == DROPDOWN_STATE_FULLSCREEN)
            goWide();
        else if (state == DROPDOWN_STATE_NORMAL)
            goNarrow();
    }

    /** The pane's own button: wide if narrow, narrow if wide. */
    public void toggleWide() {
        if (isWide())
            goNarrow();
        else
            goWide();
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

    /**
     * Past three quarters, not past half: ATAK reports a half pane as a little over
     * half, and the first tap on the wide button read that as already wide and did
     * nothing (s10-dev-1, 2026-10-14).
     */
    public boolean isWide() {
        return !isPortrait()
                ? currentWidth > (HALF_WIDTH + FULL_WIDTH) / 2
                : currentHeight > (HALF_HEIGHT + FULL_HEIGHT) / 2;
    }

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
        com.atakmap.coremap.log.Log.d("IWI-DropDown", "size " + width + " x " + height);
    }

    @Override
    public void onDropDownVisible(boolean visible) {
    }

    @Override
    public void onDropDownClose() {
        // An armed map pick holds the map's tap listeners and must let go.
        pane.onClosed();
    }

    @Override
    public void onDropDownSelectionRemoved() {
    }

    @Override
    protected void disposeImpl() {
        pane.dispose();
    }
}

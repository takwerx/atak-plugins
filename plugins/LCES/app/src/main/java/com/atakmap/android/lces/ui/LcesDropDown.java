package com.atakmap.android.lces.ui;

import android.content.Context;
import android.content.Intent;

import com.atakmap.android.dropdown.DropDown.OnStateListener;
import com.atakmap.android.dropdown.DropDownReceiver;
import com.atakmap.android.maps.MapView;

/**
 * The pane's host: a drop-down that opens at half width and can be dragged or
 * toggled out to full width and back, so an IRPG page can be read with the room
 * it needs (Atmosphere's {@code AtmosphereDropDown}).
 *
 * <p>Not the stable {@code gov.tak.api.ui.Pane}: that carries only a preferred size
 * fixed when it is built and cannot be resized once shown. The content lives in
 * {@link LcesPane}; this class only hosts it.
 */
public class LcesDropDown extends DropDownReceiver implements OnStateListener {

    private final LcesPane pane;

    private double currentWidth = HALF_WIDTH;
    private double currentHeight = FULL_HEIGHT;

    public LcesDropDown(MapView mapView, LcesPane pane) {
        super(mapView);
        this.pane = pane;
        pane.setHost(this);
    }

    public void show() {
        pane.reset();
        if (isVisible())
            return;
        setRetain(true);
        // ignoreBackButton false: with it true Back can never close the pane
        // (Atmosphere, 2026-09-26). onBackButtonPressed() is called either way.
        showDropDown(pane.getView(), HALF_WIDTH, FULL_HEIGHT, FULL_WIDTH, HALF_HEIGHT,
                false, this);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        // Opened directly by the toolbar item, not by broadcast.
    }

    /**
     * The handle was dragged or tapped. Wide is {@code FULL_WIDTH} less the handle,
     * never {@code FULL_WIDTH} itself: that strip is where the handle lives, and
     * covering it leaves no way back.
     */
    @Override
    protected void onStateRequested(int state) {
        if (state == DROPDOWN_STATE_FULLSCREEN)
            goWide();
        else if (state == DROPDOWN_STATE_NORMAL)
            goNarrow();
    }

    void toggleWide() {
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

    boolean isWide() {
        return !isPortrait()
                ? currentWidth > HALF_WIDTH + 0.01
                : currentHeight > HALF_HEIGHT + 0.01;
    }

    /** Back narrows a wide pane, then leaves a page for the main screen, then closes. */
    @Override
    protected boolean onBackButtonPressed() {
        if (isWide()) {
            goNarrow();
            return true;
        }
        return pane.back();
    }

    @Override
    public void onDropDownSizeChanged(double width, double height) {
        currentWidth = width;
        currentHeight = height;
    }

    @Override
    public void onDropDownVisible(boolean visible) {
    }

    @Override
    public void onDropDownClose() {
    }

    @Override
    public void onDropDownSelectionRemoved() {
    }

    @Override
    protected void disposeImpl() {
    }
}

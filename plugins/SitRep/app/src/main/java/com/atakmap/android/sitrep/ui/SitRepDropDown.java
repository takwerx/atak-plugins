package com.atakmap.android.sitrep.ui;

import android.content.Context;
import android.content.Intent;
import android.view.View;

import com.atakmap.android.dropdown.DropDown.OnStateListener;
import com.atakmap.android.dropdown.DropDownReceiver;
import com.atakmap.android.maps.MapView;

/**
 * The pane's host: a drop-down that opens at half width and can be dragged or
 * toggled out to full width and back, so a report can be read wide (operator,
 * 2026-11-13: "look at what we have in atmosphere ... the icon to widen the page").
 *
 * <p>Not the stable {@code gov.tak.api.ui.Pane}: that carries only a preferred size
 * fixed when it is built, cannot be resized after it is shown, and never hears about
 * the handle being dragged. Copied from Atmosphere's AtmosphereDropDown.
 */
public class SitRepDropDown extends DropDownReceiver implements OnStateListener {

    private final View root;

    /** Current size, tracked so the wide/narrow decision knows where it is. */
    private double currentWidth = HALF_WIDTH;
    private double currentHeight = FULL_HEIGHT;

    public SitRepDropDown(MapView mapView, View root) {
        super(mapView);
        this.root = root;
    }

    public void show() {
        if (isVisible())
            return;
        setRetain(true);
        // ignoreBackButton is FALSE, or Back can never close the pane (Atmosphere,
        // 2026-09-26). Back still narrows a wide pane first, through
        // onBackButtonPressed.
        showDropDown(root, HALF_WIDTH, FULL_HEIGHT, FULL_WIDTH, HALF_HEIGHT, false, this);
    }

    public void close() {
        if (isVisible())
            closeDropDown();
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        // Opened directly by the toolbar item and by taps on an area, not by broadcast.
    }

    /** The handle was dragged or tapped. Wide is FULL_WIDTH less the handle. */
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

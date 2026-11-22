package com.atakmap.android.iwi.plugin;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.LinearLayout;

import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.dropdown.DropDown.OnStateListener;
import com.atakmap.android.dropdown.DropDownReceiver;
import com.atakmap.android.maps.MapView;

/**
 * The pane's host: a drop-down that opens at half width and can be dragged or toggled
 * out to full width and back (Atmosphere's {@code AtmosphereDropDown}), holding IWI's
 * pages and its Settings page.
 *
 * <p>Not the stable {@code gov.tak.api.ui.Pane}: that carries only a preferred size
 * fixed when it is built and cannot be resized once shown. Wide is {@code FULL_WIDTH}
 * less the handle, never {@code FULL_WIDTH} itself: that strip is where the handle
 * lives, and covering it leaves no way back.
 *
 * <p>IWI's pages lived inside LCES's pane from 2026-11-03 to 2026-11-07; this class is
 * the part of LCES's shell IWI needs on its own: the Settings page and the width.
 */
public class IwiDropDown extends DropDownReceiver implements OnStateListener {

    private final View root;
    private final View iwiHost;
    private final View settingsPage;
    private final IwiPane iwi;

    private double currentWidth = HALF_WIDTH;
    private double currentHeight = FULL_HEIGHT;

    public IwiDropDown(MapView mapView, Context pluginContext, TraumaManager trauma) {
        super(mapView);
        root = PluginLayoutInflater.inflate(pluginContext, R.layout.main_layout, null);
        iwiHost = root.findViewById(R.id.iwi_host);
        settingsPage = root.findViewById(R.id.settings_page);

        final View pages = PluginLayoutInflater.inflate(pluginContext, R.layout.iwi_layout,
                null);
        ((LinearLayout) iwiHost).addView(pages, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        final View settings = PluginLayoutInflater.inflate(pluginContext,
                R.layout.iwi_settings, null);
        ((LinearLayout) root.findViewById(R.id.iwi_settings_host)).addView(settings);

        iwi = new IwiPane(pluginContext, mapView, pages, settings, trauma,
                new IwiPane.Exits() {
                    @Override
                    public void leaveIwi() {
                        closeDropDown();
                    }

                    @Override
                    public void openSettings() {
                        showSettings(true);
                    }

                    @Override
                    public void toggleWide() {
                        IwiDropDown.this.toggleWide();
                    }

                    @Override
                    public void narrow() {
                        if (isWide())
                            goNarrow();
                    }
                });

        root.findViewById(R.id.btn_settings_back).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        leaveSettings();
                    }
                });
        root.findViewById(R.id.btn_settings_wide).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        toggleWide();
                    }
                });
    }

    /** The toolbar button, or a tap on this device's IWI marker: the IWI page, current. */
    public void show() {
        showSettings(false);
        iwi.open();
        if (isVisible())
            return;
        setRetain(true);
        // ignoreBackButton false: with it true Back can never close the pane
        // (Atmosphere, 2026-09-26). onBackButtonPressed() is called either way.
        showDropDown(root, HALF_WIDTH, FULL_HEIGHT, FULL_WIDTH, HALF_HEIGHT, false, this);
    }

    /** A tap on another device's IWI marker: their whole report, read only. */
    public void showView(String uid) {
        show();
        iwi.openView(uid);
    }

    private void showSettings(boolean on) {
        settingsPage.setVisibility(on ? View.VISIBLE : View.GONE);
        iwiHost.setVisibility(on ? View.GONE : View.VISIBLE);
        if (on)
            iwi.refreshSettings();
    }

    private void leaveSettings() {
        showSettings(false);
        iwi.refreshSettings();
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        // Opened directly by the toolbar item and the marker tap, not by broadcast.
    }

    /** The handle was dragged or tapped. ATAK asks, the pane answers. */
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

    /**
     * Past three quarters, not past half: ATAK reports a half pane as a little over
     * half, and the first tap on the wide button read that as already wide and did
     * nothing (s10-dev-1, 2026-10-14).
     */
    boolean isWide() {
        return !isPortrait()
                ? currentWidth > (HALF_WIDTH + FULL_WIDTH) / 2
                : currentHeight > (HALF_HEIGHT + FULL_HEIGHT) / 2;
    }

    /**
     * Back narrows a wide pane, then leaves Settings or one of IWI's pages for the IWI
     * page, then closes.
     */
    @Override
    protected boolean onBackButtonPressed() {
        if (isWide()) {
            goNarrow();
            return true;
        }
        if (settingsPage.getVisibility() == View.VISIBLE) {
            leaveSettings();
            return true;
        }
        return iwi.back();
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
        // An armed map pick holds the map's tap listeners and must let go.
        iwi.onClosed();
    }

    @Override
    public void onDropDownSelectionRemoved() {
    }

    @Override
    protected void disposeImpl() {
        iwi.dispose();
    }
}

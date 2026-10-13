package com.atakmap.android.comms.plugin;

import android.content.Context;
import android.view.View;

import com.atak.plugins.impl.PluginContextProvider;
import com.atakmap.android.comms.ui.CommsPane;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;

import gov.tak.api.plugin.IPlugin;
import gov.tak.api.plugin.IServiceController;
import gov.tak.api.ui.IHostUIService;
import gov.tak.api.ui.Pane;
import gov.tak.api.ui.PaneBuilder;
import gov.tak.api.ui.ToolbarItem;
import gov.tak.api.ui.ToolbarItemAdapter;
import gov.tak.platform.marshal.MarshalManager;

/**
 * Comms: mountaintop radio sites on the map, nearest first, with the nets on each
 * and ATAK's viewshed drawn from the antenna.
 *
 * <p>Two panes in the same slot, the way Cam Depot does it: the site list, and one
 * site's details. Closing the detail puts the list back, whether it was the Close
 * button or the back key.
 */
public class Comms implements IPlugin {

    private static final String TAG = "Comms";
    private static final String PREFS_KEY = "commsPreference";

    IServiceController serviceController;
    Context pluginContext;
    IHostUIService uiService;
    ToolbarItem toolbarItem;
    Pane pane;
    Pane detailPane;
    private boolean swappingDetail;
    CommsPane content;

    public Comms(IServiceController serviceController) {
        this.serviceController = serviceController;
        final PluginContextProvider ctxProvider = serviceController
                .getService(PluginContextProvider.class);
        if (ctxProvider != null) {
            pluginContext = ctxProvider.getPluginContext();
            pluginContext.setTheme(R.style.ATAKPluginTheme);
        }
        uiService = serviceController.getService(IHostUIService.class);

        toolbarItem = new ToolbarItem.Builder(
                pluginContext.getString(R.string.app_name),
                MarshalManager.marshal(
                        // ic_toolbar, the bare white glyph, for ATAK's dark toolbar.
                        // ic_launcher is the glyph on a dark tile, for Android's
                        // light backgrounds only.
                        pluginContext.getResources().getDrawable(R.drawable.ic_toolbar),
                        android.graphics.drawable.Drawable.class,
                        gov.tak.api.commons.graphics.Bitmap.class))
                .setListener(new ToolbarItemAdapter() {
                    @Override
                    public void onClick(ToolbarItem item) {
                        togglePane();
                    }
                }).setIdentifier(pluginContext.getPackageName())
                .build();
    }

    @Override
    public void onStart() {
        if (uiService == null)
            return;
        uiService.addToolbarItem(toolbarItem);
        registerPreferences();
    }

    /**
     * The plugin's entry in ATAK's Tool Preferences: the only way an operator can
     * reach the user manual compiled into {@code assets/usermanual.pdf}.
     */
    private void registerPreferences() {
        try {
            com.atakmap.app.preferences.ToolsPreferenceFragment.register(
                    new com.atakmap.app.preferences.ToolsPreferenceFragment.ToolPreference(
                            pluginContext.getString(R.string.app_name),
                            pluginContext.getString(R.string.prefs_summary),
                            PREFS_KEY,
                            pluginContext.getResources().getDrawable(R.drawable.ic_toolbar),
                            new CommsPreferenceFragment(pluginContext)));
        } catch (LinkageError | RuntimeException notThisBuild) {
            Log.w(TAG, "could not register preferences: " + notThisBuild);
        }
    }

    private void unregisterPreferences() {
        try {
            com.atakmap.app.preferences.ToolsPreferenceFragment.unregister(PREFS_KEY);
        } catch (LinkageError | RuntimeException notThisBuild) {
            Log.w(TAG, "could not unregister preferences: " + notThisBuild);
        }
    }

    @Override
    public void onStop() {
        unregisterPreferences();
        if (uiService == null)
            return;
        uiService.removeToolbarItem(toolbarItem);
        if (content != null) {
            // Dismisses every viewshed the plugin made and removes its markers.
            content.dispose();
            content = null;
            pane = null;
            detailPane = null;
        }
    }

    private void togglePane() {
        if (pane != null && uiService != null && uiService.isPaneVisible(pane)) {
            uiService.closePane(pane);
            return;
        }
        showPane();
    }

    private void showPane() {
        final MapView mapView = MapView.getMapView();
        if (mapView == null) {
            Log.w(TAG, "no MapView yet; ignoring the toolbar tap");
            return;
        }
        if (pane == null) {
            content = new CommsPane(pluginContext, mapView);
            content.setDetailHost(new CommsPane.DetailHost() {
                @Override
                public void showDetailPane(View v) {
                    if (detailPane != null && uiService.isPaneVisible(detailPane)) {
                        swappingDetail = true;
                        uiService.closePane(detailPane);
                    }
                    final Pane opened = new PaneBuilder(v)
                            .setMetaValue(Pane.RELATIVE_LOCATION, Pane.Location.Default)
                            .setMetaValue(Pane.PREFERRED_WIDTH_RATIO, 0.5D)
                            .setMetaValue(Pane.PREFERRED_HEIGHT_RATIO, 0.5D)
                            .build();
                    detailPane = opened;
                    uiService.showPane(opened, new IHostUIService.IPaneLifecycleListener() {
                        @Override
                        public void onPaneVisible(boolean visible) {
                        }

                        @Override
                        public void onPaneClose() {
                            if (swappingDetail || detailPane != opened)
                                return;
                            if (pane != null && !uiService.isPaneVisible(pane))
                                uiService.showPane(pane, null);
                        }
                    });
                    swappingDetail = false;
                }

                @Override
                public void hideDetailPane() {
                    if (detailPane != null && uiService.isPaneVisible(detailPane))
                        uiService.closePane(detailPane);
                }
            });
            pane = new PaneBuilder(content.getView())
                    .setMetaValue(Pane.RELATIVE_LOCATION, Pane.Location.Default)
                    .setMetaValue(Pane.PREFERRED_WIDTH_RATIO, 0.5D)
                    .setMetaValue(Pane.PREFERRED_HEIGHT_RATIO, 0.5D)
                    .build();
        }
        if (!uiService.isPaneVisible(pane))
            uiService.showPane(pane, null);
    }
}

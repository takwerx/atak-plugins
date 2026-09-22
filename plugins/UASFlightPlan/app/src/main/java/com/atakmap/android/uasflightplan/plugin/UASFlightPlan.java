package com.atakmap.android.uasflightplan.plugin;

import android.content.Context;
import android.view.View;

import com.atak.plugins.impl.PluginContextProvider;
import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.uasflightplan.map.IslandOverlay;
import com.atakmap.android.uasflightplan.ui.UASFlightPlanPane;
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
 * UAS Flight Plan: a launch point with its ground elevation, and the terrain that
 * sticks up through the MSL ceiling painted as islands around it.
 *
 * <h3>The overlay outlives the pane on purpose</h3>
 *
 * {@link IslandOverlay} is created here and started with the plugin, not with the
 * pane and not inside a {@code Tool}. ATAK ends the active tool whenever another one
 * starts, a dropdown opens or Back is pressed, and the pane comes and goes with the
 * toolbar button; a painting that lived in either would vanish when the pilot
 * switched base maps.
 */
public class UASFlightPlan implements IPlugin {

    private static final String TAG = "UASFlightPlan";

    IServiceController serviceController;
    Context pluginContext;
    IHostUIService uiService;
    ToolbarItem toolbarItem;
    Pane templatePane;

    private IslandOverlay overlay;
    private UASFlightPlanPane pane;

    public UASFlightPlan(IServiceController serviceController) {
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
                        pluginContext.getResources().getDrawable(R.drawable.ic_toolbar),
                        android.graphics.drawable.Drawable.class,
                        gov.tak.api.commons.graphics.Bitmap.class))
                .setListener(new ToolbarItemAdapter() {
                    @Override
                    public void onClick(ToolbarItem item) {
                        showPane();
                    }
                }).setIdentifier(pluginContext.getPackageName())
                .build();
    }

    @Override
    public void onStart() {
        if (uiService == null)
            return;

        uiService.addToolbarItem(toolbarItem);

        final MapView mapView = MapView.getMapView();
        if (mapView != null) {
            overlay = new IslandOverlay(mapView);
            overlay.start();
        } else {
            // Without a map there is nothing to draw on. The toolbar button still
            // appears and says so when tapped, rather than failing silently.
            Log.w(TAG, "no MapView at start; the overlay is unavailable");
        }
    }

    @Override
    public void onStop() {
        if (uiService != null)
            uiService.removeToolbarItem(toolbarItem);

        // Close the pane, do not just drop the reference. ATAK keeps showing a pane
        // whose plugin has been unloaded, and every control on it still points at the
        // overlay this method is about to stop.
        if (templatePane != null && uiService != null) {
            try {
                uiService.closePane(templatePane);
            } catch (RuntimeException e) {
                Log.w(TAG, "could not close the pane on stop", e);
            }
        }
        if (pane != null) {
            pane.onClosed();
            pane = null;
        }
        // Nothing may stay behind: a reload that left the layer or the GL SPI
        // registered would paint with classes from the previous build.
        if (overlay != null) {
            overlay.stop();
            overlay = null;
        }
        templatePane = null;
    }

    private void showPane() {
        final MapView mapView = MapView.getMapView();
        if (mapView == null || overlay == null) {
            Log.w(TAG, "no MapView yet; ignoring the toolbar tap");
            return;
        }

        if (templatePane == null) {
            final View root = PluginLayoutInflater.inflate(pluginContext,
                    R.layout.main_layout, null);
            pane = new UASFlightPlanPane(root, pluginContext, mapView, overlay);

            templatePane = new PaneBuilder(root)
                    .setMetaValue(Pane.RELATIVE_LOCATION, Pane.Location.Default)
                    .setMetaValue(Pane.PREFERRED_WIDTH_RATIO, 0.5D)
                    .setMetaValue(Pane.PREFERRED_HEIGHT_RATIO, 0.5D)
                    .build();
        }

        if (!uiService.isPaneVisible(templatePane)) {
            // The lifecycle listener is not optional. LaunchPoint takes ATAK's map
            // event listeners exclusively while it waits for the tap; if the pane is
            // closed with the X mid-pick, nothing else ever pops them and the map
            // goes deaf to ATAK's own handlers until the plugin is reloaded.
            uiService.showPane(templatePane, new IHostUIService.IPaneLifecycleListener() {
                @Override
                public void onPaneVisible(boolean visible) {
                }

                @Override
                public void onPaneClose() {
                    if (pane != null)
                        pane.onPaneClosed();
                }
            });
        }
    }
}

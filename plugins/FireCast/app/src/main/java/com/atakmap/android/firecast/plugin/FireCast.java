package com.atakmap.android.firecast.plugin;

import android.content.Context;

import com.atak.plugins.impl.PluginContextProvider;
import com.atakmap.android.firecast.DetectionFeed;
import com.atakmap.android.firecast.DetectionPane;
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
 * Fire Cast: satellite heat detections drawn at the ground they actually cover.
 *
 * <p>This class is lifecycle and nothing else. The detections live in
 * {@link DetectionFeed}, which runs for as long as the plugin is loaded - not inside a
 * {@code Tool}, and not inside the pane - so closing the pane, opening another tool,
 * switching base maps or backgrounding ATAK leaves the map and the refresh alone.
 */
public class FireCast implements IPlugin {

    private static final String TAG = "FireCast";
    private static final String PREFS_KEY = "firecastPreference";

    IServiceController serviceController;
    Context pluginContext;
    IHostUIService uiService;
    ToolbarItem toolbarItem;
    Pane pane;
    MapView mapView;
    DetectionFeed feed;
    DetectionPane paneView;

    public FireCast(IServiceController serviceController) {
        this.serviceController = serviceController;
        final PluginContextProvider ctxProvider = serviceController.getService(PluginContextProvider.class);
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
        if (uiService != null)
            uiService.addToolbarItem(toolbarItem);
        registerPreferences();
        mapView = MapView.getMapView();
        if (mapView != null && feed == null) {
            feed = new DetectionFeed(mapView, pluginContext);
            feed.setPaneOpener(new Runnable() {
                @Override
                public void run() {
                    showPane();
                }
            });
            feed.start();
        }
    }

    @Override
    public void onStop() {
        // ATAK keeps a plugin's pane on screen across a reload, and a pane whose buttons
        // point at a stopped instance does nothing when tapped.
        if (pane != null && uiService != null) {
            try {
                if (uiService.isPaneVisible(pane))
                    uiService.closePane(pane);
            } catch (Exception ignored) {
            }
        }
        pane = null;
        paneView = null;
        if (feed != null) {
            feed.stop();
            feed = null;
        }
        if (uiService != null)
            uiService.removeToolbarItem(toolbarItem);
        unregisterPreferences();
    }

    private void showPane() {
        if (uiService == null || feed == null)
            return;
        if (pane == null) {
            paneView = new DetectionPane(mapView, pluginContext, feed);
            feed.setListener(paneView);
            pane = new PaneBuilder(paneView.view())
                    .setMetaValue(Pane.RELATIVE_LOCATION, Pane.Location.Default)
                    .setMetaValue(Pane.PREFERRED_WIDTH_RATIO, 0.5D)
                    .setMetaValue(Pane.PREFERRED_HEIGHT_RATIO, 0.5D)
                    .build();
        }
        if (!uiService.isPaneVisible(pane)) {
            uiService.showPane(pane, null);
            paneView.refreshUi();
        }
    }

    /** The Tool Preferences entry the manual is reached through; a build without the class costs the manual, not the plugin. */
    private void registerPreferences() {
        try {
            com.atakmap.app.preferences.ToolsPreferenceFragment.register(
                    new com.atakmap.app.preferences.ToolsPreferenceFragment.ToolPreference(
                            pluginContext.getString(R.string.app_name),
                            pluginContext.getString(R.string.prefs_summary),
                            PREFS_KEY,
                            pluginContext.getResources().getDrawable(R.drawable.ic_toolbar),
                            new FireCastPreferenceFragment(pluginContext)));
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
}

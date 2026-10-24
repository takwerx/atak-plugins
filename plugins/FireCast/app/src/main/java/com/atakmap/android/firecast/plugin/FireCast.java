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
    com.atakmap.android.firecast.model.FireModel model;
    com.atakmap.android.firecast.model.ModelReceiver modelPage;

    public FireCast(IServiceController serviceController) {
        this.serviceController = serviceController;
        final PluginContextProvider ctxProvider = serviceController.getService(PluginContextProvider.class);
        if (ctxProvider != null) {
            pluginContext = ctxProvider.getPluginContext();
            pluginContext.setTheme(R.style.ATAKPluginTheme);
        }
        uiService = serviceController.getService(IHostUIService.class);
        // The fire model's native library is found through the plugin's own package.
        PluginNativeLoader.init(pluginContext);
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
        if (mapView != null && firis == null) {
            firis = new com.atakmap.android.firecast.FirisModels(mapView, pluginContext);
            firis.start();
        }
        if (mapView != null && pyrecast == null) {
            pyrecast = new com.atakmap.android.firecast.PyrecastForecasts(mapView, pluginContext);
            pyrecast.start();
        }
        if (mapView != null && model == null) {
            model = new com.atakmap.android.firecast.model.FireModel(mapView, pluginContext);
            model.start();
            modelPage = new com.atakmap.android.firecast.model.ModelReceiver(mapView, pluginContext, model,
                    new Runnable() {
                        @Override
                        public void run() {
                            if (feed != null)
                                feed.reopenPane();
                        }
                    });
            model.setListener(modelPage);
            // The start is placed from the map; the page comes back to say so.
            model.setOnPlaced(new Runnable() {
                @Override
                public void run() {
                    com.atakmap.android.ipc.AtakBroadcast.getInstance().sendBroadcast(
                            new android.content.Intent(com.atakmap.android.firecast.model.ModelReceiver.ACTION));
                }
            });
            model.setPickHooks(new Runnable() {
                @Override
                public void run() {
                    if (feed != null)
                        feed.setLayerVisible(false);
                }
            }, new Runnable() {
                @Override
                public void run() {
                    if (feed != null)
                        feed.setLayerVisible(true);
                }
            });
            final com.atakmap.android.ipc.AtakBroadcast.DocumentedIntentFilter f =
                    new com.atakmap.android.ipc.AtakBroadcast.DocumentedIntentFilter();
            f.addAction(com.atakmap.android.firecast.model.ModelReceiver.ACTION, "open the fire model page");
            com.atakmap.android.ipc.AtakBroadcast.getInstance().registerReceiver(modelPage, f);
        }
        // `adb shell am broadcast -a com.atakmap.android.firecast.SHOW` opens the pane,
        // so a test session never drives the Tools list. A system receiver, because
        // AtakBroadcast's own is process-local and am broadcast cannot reach it.
        if (showReceiver == null) {
            showReceiver = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context c, android.content.Intent i) {
                    if (mapView != null)
                        mapView.post(new Runnable() {
                            @Override
                            public void run() {
                                showPane();
                            }
                        });
                }
            };
            final com.atakmap.android.ipc.AtakBroadcast.DocumentedIntentFilter sf =
                    new com.atakmap.android.ipc.AtakBroadcast.DocumentedIntentFilter();
            sf.addAction(SHOW, "open the Fire Cast pane");
            com.atakmap.android.ipc.AtakBroadcast.getInstance().registerSystemReceiver(showReceiver, sf);
        }
    }

    public static final String SHOW = "com.atakmap.android.firecast.SHOW";
    private android.content.BroadcastReceiver showReceiver;
    private com.atakmap.android.firecast.FirisModels firis;
    private com.atakmap.android.firecast.PyrecastForecasts pyrecast;

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
        if (showReceiver != null) {
            try {
                com.atakmap.android.ipc.AtakBroadcast.getInstance().unregisterSystemReceiver(showReceiver);
            } catch (Exception ignored) {
            }
            showReceiver = null;
        }
        if (modelPage != null) {
            try {
                com.atakmap.android.ipc.AtakBroadcast.getInstance().unregisterReceiver(modelPage);
            } catch (Exception ignored) {
            }
            modelPage.dispose();
            modelPage = null;
        }
        if (model != null) {
            model.stop();
            model = null;
        }
        if (firis != null) {
            firis.setListener(null);
            firis.stop();
            firis = null;
        }
        if (pyrecast != null) {
            pyrecast.setListener(null);
            pyrecast.stop();
            pyrecast = null;
        }
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
            paneView = new DetectionPane(mapView, pluginContext, feed, firis, pyrecast);
            feed.setListener(paneView);
            if (firis != null) {
                firis.setListener(paneView);
                paneView.onFirisChanged();
            }
            if (pyrecast != null) {
                pyrecast.setListener(paneView);
                paneView.onPyrecastChanged();
            }
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

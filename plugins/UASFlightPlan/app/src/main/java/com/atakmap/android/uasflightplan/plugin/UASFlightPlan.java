package com.atakmap.android.uasflightplan.plugin;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.view.View;

import com.atak.plugins.impl.PluginContextProvider;
import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.ipc.AtakBroadcast.DocumentedIntentFilter;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.uasflightplan.map.IslandOverlay;
import com.atakmap.android.uasflightplan.obstacles.ObstacleManager;
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

    /**
     * Opens the pane from the outside: {@code am broadcast -a <ACTION_SHOW>}.
     * A system receiver, because {@code registerReceiver} is process-local and
     * {@code am broadcast} never reaches it. Driving ATAK's Tools list over adb
     * is unreliable and every stray tap lands in another plugin's pane; this is
     * how a session opens the pane for a test.
     *
     * <p>With {@code lat} and {@code lon} extras (degrees, doubles) it also
     * places the launch point there, which is how another plugin hands a point
     * over and how a test lands on a known spot. Nothing else is read from it.
     */
    public static final String ACTION_SHOW = "com.atakmap.android.uasflightplan.SHOW";

    /** The key of the plugin's row under ATAK's Tool Preferences. */
    private static final String PREFS_KEY = "uasflightplanPreference";

    private final BroadcastReceiver showReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            showPane();
            if (intent == null || pane == null)
                return;
            final double lat = intent.getDoubleExtra("lat", Double.NaN);
            final double lon = intent.getDoubleExtra("lon", Double.NaN);
            if (Double.isNaN(lat) || Double.isNaN(lon)
                    || Math.abs(lat) > 90d || Math.abs(lon) > 180d)
                return;
            pane.placeLaunchPoint(new com.atakmap.coremap.maps.coords.GeoPoint(lat, lon));
        }
    };

    /**
     * A tap on one of our obstacles opens its page in the pane, not ATAK's built-in
     * feature metadata screen. ATAK asks these listeners before it opens a radial,
     * and one that answers true stops it; items that are not ours keep theirs. A
     * moment later, not now: a pick from ATAK's Select Item list closes the list
     * and then posts its own "show details", which closed a page opened at once
     * (Atmosphere, 2026-10-05).
     */
    private final com.atakmap.android.menu.MapMenuEventListener tapOpensPage =
            new com.atakmap.android.menu.MapMenuEventListener() {
                @Override
                public boolean onShowMenu(final com.atakmap.android.maps.MapItem item) {
                    if (item == null || !item.getMetaBoolean("uasflightplan", false))
                        return false;
                    final MapView mv = MapView.getMapView();
                    if (mv == null || obstacles == null)
                        return false;
                    final com.atakmap.android.uasflightplan.obstacles.Obstacle o =
                            obstacles.obstacleFor(item.getMetaLong("featureid", -1L));
                    if (o == null)
                        return false;
                    mv.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                showPane();
                                if (pane != null)
                                    pane.showDetails(o);
                            } catch (RuntimeException e) {
                                Log.w(TAG, "tap to page", e);
                            }
                        }
                    }, 250);
                    return true;
                }

                @Override
                public void onHideMenu(com.atakmap.android.maps.MapItem item) {
                }
            };

    IServiceController serviceController;
    Context pluginContext;
    IHostUIService uiService;
    ToolbarItem toolbarItem;
    Pane templatePane;

    private IslandOverlay overlay;
    private ObstacleManager obstacles;
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
        registerPreferences();
        AtakBroadcast.getInstance().registerSystemReceiver(showReceiver,
                new DocumentedIntentFilter(ACTION_SHOW, "Open the UAS Flight Plan pane"));

        final MapView mapView = MapView.getMapView();
        if (mapView != null) {
            overlay = new IslandOverlay(mapView);
            overlay.start();
            obstacles = new ObstacleManager(mapView, pluginContext);
            obstacles.start();
            final com.atakmap.android.menu.MapMenuReceiver menus =
                    com.atakmap.android.menu.MapMenuReceiver.getInstance();
            if (menus != null)
                menus.addEventListener(tapOpensPage);
            // The plan the pilot left comes back without a tap: the pane is built
            // now (not shown), which restores the launch point and the area and
            // recomputes. A moment later, so ATAK's map and drawings are up first.
            mapView.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (overlay != null && obstacles != null)
                        ensurePane(mapView);
                }
            }, 2000L);
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
        unregisterPreferences();
        try {
            AtakBroadcast.getInstance().unregisterSystemReceiver(showReceiver);
        } catch (RuntimeException e) {
            Log.w(TAG, "show receiver was not registered", e);
        }

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
        final com.atakmap.android.menu.MapMenuReceiver menus =
                com.atakmap.android.menu.MapMenuReceiver.getInstance();
        if (menus != null)
            menus.removeEventListener(tapOpensPage);
        if (obstacles != null) {
            obstacles.stop();
            obstacles = null;
        }
        if (overlay != null) {
            overlay.stop();
            overlay = null;
        }
        templatePane = null;
    }

    /**
     * Put the plugin in ATAK's Tool Preferences, which is the only way a pilot
     * can reach the user manual.
     *
     * <p>The manual is compiled into {@code assets/usermanual.pdf}, and an asset is
     * not reachable by anyone; without this entry it ships inside the APK with no
     * way to open it.
     *
     * <p>Guarded rather than assumed: this reaches into ATAK's own preferences
     * classes, so a build that does not expose them costs the manual entry and
     * nothing else.
     */
    private void registerPreferences() {
        try {
            com.atakmap.app.preferences.ToolsPreferenceFragment.register(
                    new com.atakmap.app.preferences.ToolsPreferenceFragment
                            .ToolPreference(
                                    pluginContext.getString(R.string.app_name),
                                    pluginContext.getString(R.string.prefs_summary),
                                    PREFS_KEY,
                                    // ic_toolbar, not ic_launcher: this row sits on
                                    // ATAK's dark UI and wants the bare glyph.
                                    pluginContext.getResources().getDrawable(
                                            R.drawable.ic_toolbar),
                                    new UASFlightPlanPreferenceFragment(pluginContext)));
        } catch (LinkageError | RuntimeException notThisBuild) {
            Log.w(TAG, "could not register preferences: " + notThisBuild);
        }
    }

    private void unregisterPreferences() {
        try {
            com.atakmap.app.preferences.ToolsPreferenceFragment
                    .unregister(PREFS_KEY);
        } catch (LinkageError | RuntimeException notThisBuild) {
            Log.w(TAG, "could not unregister preferences: " + notThisBuild);
        }
    }

    /** Builds the pane once; it restores the saved plan as it is built. */
    private void ensurePane(MapView mapView) {
        if (templatePane != null)
            return;
        final View root = PluginLayoutInflater.inflate(pluginContext,
                R.layout.main_layout, null);
        pane = new UASFlightPlanPane(root, pluginContext, mapView, overlay, obstacles);
        templatePane = new PaneBuilder(root)
                .setMetaValue(Pane.RELATIVE_LOCATION, Pane.Location.Default)
                .setMetaValue(Pane.PREFERRED_WIDTH_RATIO, 0.5D)
                .setMetaValue(Pane.PREFERRED_HEIGHT_RATIO, 0.5D)
                .build();
    }

    private void showPane() {
        final MapView mapView = MapView.getMapView();
        if (mapView == null || overlay == null || obstacles == null) {
            Log.w(TAG, "no MapView yet; ignoring the toolbar tap");
            return;
        }

        ensurePane(mapView);

        if (!uiService.isPaneVisible(templatePane)) {
            // The lifecycle listener is not optional. LaunchPoint takes ATAK's map
            // event listeners exclusively while it waits for the tap; if the pane is
            // closed with the X mid-pick, nothing else ever pops them and the map
            // goes deaf to ATAK's own handlers until the plugin is reloaded.
            uiService.showPane(templatePane, new IHostUIService.IPaneLifecycleListener() {
                @Override
                public void onPaneVisible(boolean visible) {
                    if (visible && pane != null)
                        pane.onPaneShown();
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

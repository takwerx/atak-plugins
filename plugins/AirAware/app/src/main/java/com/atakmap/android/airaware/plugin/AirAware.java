package com.atakmap.android.airaware.plugin;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.atak.plugins.impl.PluginContextProvider;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.ipc.AtakBroadcast.DocumentedIntentFilter;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.airaware.Tfr;
import com.atakmap.android.airaware.TfrManager;
import com.atakmap.android.airaware.ui.TfrPane;
import com.atakmap.coremap.log.Log;

import gov.tak.api.plugin.IPlugin;
import gov.tak.api.plugin.IServiceController;
import android.view.View;
import com.atak.plugins.impl.PluginLayoutInflater;
import gov.tak.api.ui.IHostUIService;
import gov.tak.api.ui.Pane;
import gov.tak.api.ui.PaneBuilder;
import gov.tak.api.ui.ToolbarItem;
import gov.tak.api.ui.ToolbarItemAdapter;
import gov.tak.platform.marshal.MarshalManager;

/**
 * Lifecycle, the toolbar button, and the pane. Everything that lasts is in
 * {@link TfrManager}; this class is its face and nothing more.
 */
public class AirAware implements IPlugin {

    private static final String TAG = "AirAware";
    /** What the radial's details button broadcasts. */
    public static final String ACTION_DETAILS = "com.atakmap.android.airaware.TFR_DETAILS";
    /** So a pane can be opened over adb without finding a button. */
    public static final String ACTION_SHOW = "com.atakmap.android.airaware.SHOW";

    IServiceController serviceController;
    Context pluginContext;
    IHostUIService uiService;
    /**
     * The flight planner: UAS Flight Plan's own pane, carried over whole when the
     * operator deprecated that plugin into this one. It keeps its own overlay, obstacle
     * manager, launch point and area picker, and its own screen, because that is the
     * screen the operator already knows and nothing about it should change.
     */
    private com.atakmap.android.airaware.plan.PlanPane planPane;
    private Pane planTemplate;
    private com.atakmap.android.airaware.plan.IslandOverlay planOverlay;
    private com.atakmap.android.airaware.plan.ObstacleManager planObstacles;
    ToolbarItem toolbarItem;
    Pane pane;

    private MapView mapView;
    private TfrManager manager;
    private TfrPane paneUi;
    private BroadcastReceiver receiver;
    private BroadcastReceiver showReceiver;
    private com.atakmap.android.menu.MapMenuEventListener menuListener;

    public AirAware(IServiceController serviceController) {
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
        mapView = MapView.getMapView();
        if (mapView != null) {
            manager = new TfrManager(mapView, pluginContext);
            paneUi = new TfrPane(mapView, pluginContext, manager);
            paneUi.setOnPlan(new Runnable() {
                @Override
                public void run() {
                    showPlanPane();
                }
            });
            manager.setListener(paneUi);
            manager.start();
            registerReceiver();
            registerTap();
        }
        uiService.addToolbarItem(toolbarItem);
    }

    @Override
    public void onStop() {
        if (receiver != null) {
            try {
                AtakBroadcast.getInstance().unregisterReceiver(receiver);
            } catch (Exception e) {
                Log.w(TAG, "unregistering failed", e);
            }
            receiver = null;
        }
        if (showReceiver != null) {
            try {
                AtakBroadcast.getInstance().unregisterSystemReceiver(showReceiver);
            } catch (Exception e) {
                Log.w(TAG, "unregistering the show receiver failed", e);
            }
            showReceiver = null;
        }
        if (menuListener != null) {
            final com.atakmap.android.menu.MapMenuReceiver menus =
                    com.atakmap.android.menu.MapMenuReceiver.getInstance();
            if (menus != null)
                menus.removeEventListener(menuListener);
            menuListener = null;
        }
        if (manager != null) {
            // Everything this plugin drew comes off with it, and nothing it downloaded is
            // forgotten: stop takes things off the map, only the operator's own clear
            // forgets.
            manager.dispose();
            manager = null;
        }
        if (planPane != null) {
            planPane.onPaneClosed();
            planPane = null;
        }
        if (planObstacles != null) {
            planObstacles.stop();
            planObstacles = null;
        }
        if (planOverlay != null) {
            planOverlay.stop();
            planOverlay = null;
        }
        planTemplate = null;
        if (uiService != null) {
            // Close and forget the pane, or a reload leaves the old view on screen bound
            // to the manager that was just disposed: it showed "Updated 13 min ago" and
            // "in view (0)" over a map that was drawing a restriction, which reads as the
            // list having lost track rather than as a pane nobody rebuilt.
            if (pane != null) {
                try {
                    uiService.closePane(pane);
                } catch (Exception e) {
                    Log.w(TAG, "closing the pane failed", e);
                }
                pane = null;
            }
            uiService.removeToolbarItem(toolbarItem);
        }
        paneUi = null;
    }

    private void registerReceiver() {
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final String uid = intent.getStringExtra("targetUID");
                if (uid == null || mapView == null || manager == null || paneUi == null)
                    return;
                final MapItem item = mapView.getRootGroup().deepFindUID(uid);
                if (item == null)
                    return;
                final Tfr t = manager.byNotam(item.getMetaString("tfr_notam_id", null));
                if (t != null)
                    paneUi.showDetails(t);
            }
        };
        final DocumentedIntentFilter f = new DocumentedIntentFilter();
        f.addAction(ACTION_DETAILS, "Open a restriction's details from its radial");
        AtakBroadcast.getInstance().registerReceiver(receiver, f);

        // A system receiver, not a process-local one, so a session can open the pane with
        // one adb command instead of driving ATAK's Tools list -- where slow swipes
        // register as taps and a stray one lands in another plugin's settings.
        showReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                showPane();
            }
        };
        final DocumentedIntentFilter sf = new DocumentedIntentFilter();
        sf.addAction(ACTION_SHOW, "Open the TFR pane");
        AtakBroadcast.getInstance().registerSystemReceiver(showReceiver, sf);
    }

    /**
     * A tap on a restriction opens its details in the pane.
     *
     * <p><b>ATAK is asked for the item before it opens anything of its own.</b> Listening
     * for the click instead left ATAK to do its half as well: it selected the item and
     * left its own callout -- "Edwards Afb Class E5 ... 2,534 ft MSL" -- pinned to the
     * top of the map, still there after Back, because nothing we did ever told ATAK the
     * tap was handled. A {@code MapMenuEventListener} that answers true does tell it.
     * Items that are not ours are not claimed and keep their radial exactly as before.
     * This is Atmosphere's path, for the same reason and after the same bug.
     */
    private void registerTap() {
        menuListener = new com.atakmap.android.menu.MapMenuEventListener() {
            @Override
            public boolean onShowMenu(final MapItem item) {
                if (item == null || manager == null || paneUi == null)
                    return false;
                if (item.getMetaString("tfr_notam_id", null) == null
                        && item.getMetaString("metar_icao", null) == null
                        && item.getMetaString("airspace_id", null) == null
                        && item.getMetaString("obstacle_oas", null) == null
                        && item.getMetaString("uasfm_id", null) == null)
                    return false;
                // A moment later, not now: a pick from ATAK's Select Item list closes the
                // list and then posts its own show-details, which closed the page opened
                // here. A plain tap does not notice the quarter second.
                mapView.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        openFor(item);
                    }
                }, 250);
                return true;
            }

            @Override
            public void onHideMenu(MapItem item) {
            }
        };
        final com.atakmap.android.menu.MapMenuReceiver menus =
                com.atakmap.android.menu.MapMenuReceiver.getInstance();
        if (menus != null)
            menus.addEventListener(menuListener);
        else
            Log.w(TAG, "no radial menu receiver; taps keep ATAK's own menu");
    }

    /** Whatever the tapped item is, on AirAware's own page. */
    private void openFor(MapItem item) {
        try {
            final String notam = item.getMetaString("tfr_notam_id", null);
            if (notam != null) {
                final Tfr t = manager.byNotam(notam);
                if (t == null)
                    return;
                showPane();
                paneUi.showDetails(t);
                return;
            }
            final String icao = item.getMetaString("metar_icao", null);
            if (icao != null) {
                final com.atakmap.android.airaware.Metar m = manager.airfield(icao);
                if (m == null)
                    return;
                showPane();
                paneUi.showAirfield(m);
                return;
            }
            final String shelf = item.getMetaString("airspace_id", null);
            if (shelf != null) {
                final com.atakmap.android.airaware.Airspace a = manager.airspaceById(shelf);
                if (a == null)
                    return;
                showPane();
                paneUi.showAirspace(a);
                return;
            }
            final String oas = item.getMetaString("obstacle_oas", null);
            if (oas != null) {
                final com.atakmap.android.airaware.Obstacle o = manager.obstacleByOas(oas);
                if (o == null)
                    return;
                showPane();
                paneUi.showObstacle(o);
                return;
            }
            final String cell = item.getMetaString("uasfm_id", null);
            if (cell == null)
                return;
            final com.atakmap.android.airaware.UasfmCell c = manager.uasfmById(cell);
            if (c == null)
                return;
            showPane();
            paneUi.showUasfm(c);
        } catch (RuntimeException e) {
            Log.w(TAG, "opening the page for a tap failed", e);
        }
    }

    /** Builds the planner's pane once; it restores the saved plan as it is built. */
    private void ensurePlanPane() {
        if (planTemplate != null)
            return;
        planOverlay = new com.atakmap.android.airaware.plan.IslandOverlay(mapView);
        planObstacles = new com.atakmap.android.airaware.plan.ObstacleManager(mapView,
                pluginContext);
        // Both have to be started, which the plugin they came from did in its own
        // onStart. Without it IslandOverlay.started stays false, every computeFor is
        // refused with "UAS Flight Plan was reloaded", and because nothing is ever
        // sampled the ground reads as "unknown until DTED2 covers it" on a phone that
        // has DTED2. Two misleading messages, one missing call.
        planOverlay.start();
        planObstacles.start();
        final View root = PluginLayoutInflater.inflate(pluginContext,
                R.layout.plan_main, null);
        planPane = new com.atakmap.android.airaware.plan.PlanPane(root, pluginContext,
                mapView, planOverlay, planObstacles);
        planPane.setOnBack(new Runnable() {
            @Override
            public void run() {
                if (uiService != null && planTemplate != null)
                    uiService.closePane(planTemplate);
                showPane();
            }
        });
        planTemplate = new PaneBuilder(root)
                .setMetaValue(Pane.RELATIVE_LOCATION, Pane.Location.Default)
                .setMetaValue(Pane.PREFERRED_WIDTH_RATIO, 0.5D)
                .setMetaValue(Pane.PREFERRED_HEIGHT_RATIO, 0.5D)
                .build();
    }

    private void showPlanPane() {
        if (uiService == null || mapView == null)
            return;
        ensurePlanPane();
        if (uiService.isPaneVisible(planTemplate))
            return;
        // The lifecycle listener is not optional. LaunchPoint takes ATAK's map event
        // listeners exclusively while it waits for the tap; if the pane is closed with
        // the X mid-pick, nothing else ever pops them and the map goes deaf to ATAK's
        // own handlers until the plugin is reloaded.
        uiService.showPane(planTemplate, new IHostUIService.IPaneLifecycleListener() {
            @Override
            public void onPaneVisible(boolean visible) {
                if (visible && planPane != null)
                    planPane.onPaneShown();
            }

            @Override
            public void onPaneClose() {
                if (planPane != null)
                    planPane.onPaneClosed();
            }
        });
    }

    private void showPane() {
        if (pane == null) {
            pane = new PaneBuilder(paneUi.build())
                    .setMetaValue(Pane.RELATIVE_LOCATION, Pane.Location.Default)
                    // Half, like every other takwerx plugin. Narrowing it to 0.38 was
                    // tried and put back: TFR being the one odd pane is worse than a pane
                    // being wide, and what was actually too large wants finding first.
                    .setMetaValue(Pane.PREFERRED_WIDTH_RATIO, 0.5D)
                    .setMetaValue(Pane.PREFERRED_HEIGHT_RATIO, 0.5D)
                    .build();
        }
        paneUi.onShown();
        if (!uiService.isPaneVisible(pane))
            uiService.showPane(pane, null);
    }
}

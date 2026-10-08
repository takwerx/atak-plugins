package com.atakmap.android.tfr.plugin;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.atak.plugins.impl.PluginContextProvider;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.ipc.AtakBroadcast.DocumentedIntentFilter;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.tfr.Tfr;
import com.atakmap.android.tfr.TfrManager;
import com.atakmap.android.tfr.ui.TfrPane;
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
 * Lifecycle, the toolbar button, and the pane. Everything that lasts is in
 * {@link TfrManager}; this class is its face and nothing more.
 */
public class TFR implements IPlugin {

    private static final String TAG = "TFR";
    /** What the radial's details button broadcasts. */
    public static final String ACTION_DETAILS = "com.atakmap.android.tfr.TFR_DETAILS";
    /** So a pane can be opened over adb without finding a button. */
    public static final String ACTION_SHOW = "com.atakmap.android.tfr.SHOW";

    IServiceController serviceController;
    Context pluginContext;
    IHostUIService uiService;
    ToolbarItem toolbarItem;
    Pane pane;

    private MapView mapView;
    private TfrManager manager;
    private TfrPane paneUi;
    private BroadcastReceiver receiver;
    private BroadcastReceiver showReceiver;
    private com.atakmap.android.maps.MapEventDispatcher.MapEventDispatchListener tapListener;

    public TFR(IServiceController serviceController) {
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
        if (tapListener != null && mapView != null) {
            mapView.getMapEventDispatcher().removeMapEventListener(
                    com.atakmap.android.maps.MapEvent.ITEM_CLICK, tapListener);
            tapListener = null;
        }
        if (manager != null) {
            // Everything this plugin drew comes off with it, and nothing it downloaded is
            // forgotten: stop takes things off the map, only the operator's own clear
            // forgets.
            manager.dispose();
            manager = null;
        }
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
     * <p>A listener rather than a radial: the features carry a blank menu, so ATAK opens
     * nothing of its own and the click arrives here. Only our own items are claimed --
     * every other plugin's and ATAK's own still behave exactly as before, which is why
     * this adds a listener rather than clearing ITEM_CLICK.
     */
    private void registerTap() {
        tapListener = new com.atakmap.android.maps.MapEventDispatcher.MapEventDispatchListener() {
            @Override
            public void onMapEvent(com.atakmap.android.maps.MapEvent event) {
                if (event == null || manager == null || paneUi == null)
                    return;
                final MapItem item = event.getItem();
                if (item == null)
                    return;
                final String notam = item.getMetaString("tfr_notam_id", null);
                if (notam == null)
                    return;
                final Tfr t = manager.byNotam(notam);
                if (t == null)
                    return;
                showPane();
                paneUi.showDetails(t);
            }
        };
        mapView.getMapEventDispatcher().addMapEventListener(
                com.atakmap.android.maps.MapEvent.ITEM_CLICK, tapListener);
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

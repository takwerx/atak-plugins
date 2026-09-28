package com.atakmap.android.signaldf.plugin;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.atak.plugins.impl.PluginContextProvider;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.ipc.AtakBroadcast.DocumentedIntentFilter;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.signaldf.net.KrakenLink;
import com.atakmap.android.signaldf.model.VehicleHeading;
import com.atakmap.android.signaldf.net.BearingPublisher;
import com.atakmap.android.signaldf.ui.SignalDfDropDown;
import com.atakmap.coremap.log.Log;

import gov.tak.api.plugin.IPlugin;
import gov.tak.api.plugin.IServiceController;
import gov.tak.api.ui.IHostUIService;
import gov.tak.api.ui.ToolbarItem;
import gov.tak.api.ui.ToolbarItemAdapter;
import gov.tak.platform.marshal.MarshalManager;

/**
 * Signal DF -- RF direction finding from a KrakenSDR, on the ATAK map.
 *
 * <p>The plugin owns the radio link. {@link KrakenLink} is created here and
 * lives exactly as long as the plugin does, never inside the pane and never
 * inside a {@code Tool}: ATAK ends the active tool whenever another starts, a
 * dropdown opens or Back is pressed, and a search does not stop being a search
 * because the operator switched base maps. FOBS 0.4 shipped a GPS recording
 * inside its tool and lost a walk to exactly that.
 *
 * <p>0.1 is deliberately small -- connect to the radio and put one correct
 * bearing on the map. No fix, no power lobe, no sharing, no WebSocket. The
 * bearing has to be right before anything built on top of it means anything,
 * and as of 0.1 it has not been checked against a radio at all.
 */
public class SignalDF implements IPlugin {

    private static final String TAG = "SignalDF";

    /**
     * Opens the pane from outside: a test over adb, a hotkey, or another plugin.
     * A system broadcast, because ATAK's own {@code registerReceiver} wraps
     * {@code LocalBroadcastManager} and is process-local, so {@code am broadcast}
     * cannot reach it. It opens a pane and nothing else.
     */
    public static final String ACTION_SHOW = "com.atakmap.android.signaldf.SHOW";

    private final BroadcastReceiver showReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            showPane();
        }
    };

    IServiceController serviceController;
    Context pluginContext;
    IHostUIService uiService;
    ToolbarItem toolbarItem;

    private KrakenLink link;
    private BearingPublisher publisher;
    private VehicleHeading vehicleHeading;
    private SignalDfDropDown dropDown;

    /** The sharing component, for the pane's settings screen. */
    public BearingPublisher publisher() {
        return publisher;
    }

    public SignalDF(IServiceController serviceController) {
        this.serviceController = serviceController;
        final PluginContextProvider ctxProvider = serviceController
                .getService(PluginContextProvider.class);
        if (ctxProvider != null) {
            pluginContext = ctxProvider.getPluginContext();
            pluginContext.setTheme(R.style.ATAKPluginTheme);
        }

        // obtain the UI service
        uiService = serviceController.getService(IHostUIService.class);

        // create the button and set the identifier to be well known
        // if you fail to do this, the toolbar configuration will never
        // be able to find it again after the user moves the icon.
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

        // Built before any pane exists, so nothing about the connection depends
        // on the operator having opened one.
        if (link == null) {
            // The host's context: a plugin context has no system services, and
            // WifiPin needs the connectivity service.
            MapView mv = MapView.getMapView();
            link = new KrakenLink(mv == null ? null : mv.getContext());
        }

        // Sharing lives out here with the link, not in the pane, because
        // transmitting has to outlive the tap that started it: the operator
        // switches base maps or presses Back and the bearings keep going out.
        if (publisher == null) {
            MapView mv = MapView.getMapView();
            if (mv != null) {
                vehicleHeading = new VehicleHeading(mv);
                publisher = new BearingPublisher(mv);
                link.addListener(publisher);
            }
        }

        uiService.addToolbarItem(toolbarItem);
        AtakBroadcast.getInstance().registerSystemReceiver(showReceiver,
                new DocumentedIntentFilter(ACTION_SHOW, "Open the Signal DF pane"));
    }

    @Override
    public void onStop() {
        if (uiService != null)
            uiService.removeToolbarItem(toolbarItem);

        try {
            AtakBroadcast.getInstance().unregisterSystemReceiver(showReceiver);
        } catch (RuntimeException e) {
            Log.w(TAG, "show receiver was not registered", e);
        }

        if (dropDown != null) {
            dropDown.dispose();
            dropDown = null;
        }
        // The link is stopped rather than left running: a stopped plugin that
        // is still polling a radio is a battery drain nobody can see.
        if (publisher != null) {
            if (link != null)
                link.removeListener(publisher);
            publisher.dispose();
            publisher = null;
        }
        if (vehicleHeading != null) {
            vehicleHeading.dispose();
            vehicleHeading = null;
        }
        if (link != null) {
            link.dispose();
            link = null;
        }
    }

    private void showPane() {
        final MapView mapView = MapView.getMapView();
        if (mapView == null) {
            Log.w(TAG, "no map view yet, cannot open the pane");
            return;
        }
        if (link == null)
            link = new KrakenLink(mapView.getContext());
        if (dropDown == null)
            dropDown = new SignalDfDropDown(mapView, pluginContext);
        dropDown.show();
    }
}

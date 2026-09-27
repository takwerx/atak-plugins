package com.atakmap.android.signaldf.plugin;

import android.content.Context;

import com.atak.plugins.impl.PluginContextProvider;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.signaldf.net.KrakenLink;
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

    IServiceController serviceController;
    Context pluginContext;
    IHostUIService uiService;
    ToolbarItem toolbarItem;

    private KrakenLink link;
    private SignalDfDropDown dropDown;

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
        if (link == null)
            link = new KrakenLink();

        uiService.addToolbarItem(toolbarItem);
    }

    @Override
    public void onStop() {
        if (uiService != null)
            uiService.removeToolbarItem(toolbarItem);

        if (dropDown != null) {
            dropDown.dispose();
            dropDown = null;
        }
        // The link is stopped rather than left running: a stopped plugin that
        // is still polling a radio is a battery drain nobody can see.
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
            link = new KrakenLink();
        if (dropDown == null)
            dropDown = new SignalDfDropDown(mapView, pluginContext);
        dropDown.show();
    }
}

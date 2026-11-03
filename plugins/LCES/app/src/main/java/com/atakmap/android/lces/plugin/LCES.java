package com.atakmap.android.lces.plugin;

import android.content.Context;

import com.atak.plugins.impl.PluginContextProvider;
import com.atakmap.android.lces.ui.LcesDropDown;
import com.atakmap.android.lces.ui.LcesPane;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;

import gov.tak.api.plugin.IPlugin;
import gov.tak.api.plugin.IServiceController;
import gov.tak.api.ui.IHostUIService;
import gov.tak.api.ui.ToolbarItem;
import gov.tak.api.ui.ToolbarItemAdapter;
import gov.tak.platform.marshal.MarshalManager;

/**
 * Lookouts, Communications, Escape Routes, Safety Zones: the IRPG one tap away,
 * and wildland fire lessons learned on the map.
 */
public class LCES implements IPlugin {

    private static final String TAG = "LCES";

    IServiceController serviceController;
    Context pluginContext;
    IHostUIService uiService;
    ToolbarItem toolbarItem;
    LcesDropDown dropDown;
    MapView mapView;

    public LCES(IServiceController serviceController) {
        this.serviceController = serviceController;
        final PluginContextProvider ctxProvider = serviceController
                .getService(PluginContextProvider.class);
        if (ctxProvider != null) {
            pluginContext = ctxProvider.getPluginContext();
            pluginContext.setTheme(R.style.ATAKPluginTheme);
        }

        uiService = serviceController.getService(IHostUIService.class);

        // The identifier has to be well known, or the toolbar configuration cannot
        // find the button again after the user moves it.
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
        mapView = MapView.getMapView();
        if (mapView == null)
            Log.w(TAG, "no map view at start");
    }

    @Override
    public void onStop() {
        // A pane whose buttons point at a stopped instance does nothing when tapped.
        if (dropDown != null) {
            dropDown.dispose();
            dropDown = null;
        }
        if (uiService != null)
            uiService.removeToolbarItem(toolbarItem);
    }

    private void showPane() {
        if (uiService == null)
            return;
        if (mapView == null)
            mapView = MapView.getMapView();
        if (mapView == null) {
            Log.w(TAG, "no map view; the pane has nothing to show");
            return;
        }
        if (dropDown == null)
            dropDown = new LcesDropDown(mapView, new LcesPane(mapView, pluginContext));
        dropDown.show();
    }
}

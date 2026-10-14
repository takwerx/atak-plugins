
package com.atakmap.android.iwi.plugin;

import android.content.Context;
import android.view.View;

import com.atakmap.android.cot.detail.CotDetailManager;
import com.atakmap.android.maps.MapView;

import com.atak.plugins.impl.PluginContextProvider;
import com.atak.plugins.impl.PluginLayoutInflater;

import gov.tak.api.plugin.IPlugin;
import gov.tak.api.plugin.IServiceController;
import gov.tak.api.ui.IHostUIService;
import gov.tak.api.ui.ToolbarItem;
import gov.tak.api.ui.ToolbarItemAdapter;
import gov.tak.platform.marshal.MarshalManager;

public class IWI implements IPlugin {

    IServiceController serviceController;
    Context pluginContext;
    IHostUIService uiService;
    ToolbarItem toolbarItem;
    /** The pane, hosted in a drop-down so it can be dragged out wide. */
    IwiDropDown dropDown;
    IwiDetailHandler iwiDetailHandler;
    /** Anything that must outlive a tap lives here, not in a Tool. */
    TraumaManager traumaManager;
    IwiPane iwiPane;

    public IWI(IServiceController serviceController) {
        this.serviceController = serviceController;
        final PluginContextProvider ctxProvider = serviceController
                .getService(PluginContextProvider.class);
        if (ctxProvider != null) {
            pluginContext = ctxProvider.getPluginContext();
            pluginContext.setTheme(R.style.ATAKPluginTheme);
        }

        // obtain the UI service
        uiService = serviceController.getService(IHostUIService.class);

        // initialize the toolbar button for the plugin

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
                })
                .build();
    }

    @Override
    public void onStart() {
        // The detail handler is what makes _iwi_ typed rather than opaque on a
        // phone that has the plugin.
        if (iwiDetailHandler == null)
            iwiDetailHandler = new IwiDetailHandler();
        CotDetailManager.getInstance().registerHandler(iwiDetailHandler);

        // The trauma center pack: read from disk on a worker, refreshed from the
        // depot in the background, kept for the plugin's life.
        final MapView mapView = MapView.getMapView();
        if (mapView != null && traumaManager == null) {
            traumaManager = new TraumaManager(mapView);
            traumaManager.start();
        }

        if (uiService == null)
            return;

        uiService.addToolbarItem(toolbarItem);
    }

    @Override
    public void onStop() {
        if (iwiDetailHandler != null)
            CotDetailManager.getInstance().unregisterHandler(iwiDetailHandler);
        if (traumaManager != null) {
            traumaManager.stop();
            traumaManager = null;
        }
        if (dropDown != null) {
            // disposeImpl disposes the pane, which lets go of any map pick.
            dropDown.closeDropDown();
            dropDown.dispose();
            dropDown = null;
        }
        iwiPane = null;

        // the plugin is stopping, remove the button from the toolbar
        if (uiService == null)
            return;

        uiService.removeToolbarItem(toolbarItem);
    }

    private void showPane() {
        final MapView mapView = MapView.getMapView();
        if (mapView == null || traumaManager == null)
            return;
        if (dropDown == null) {
            final View v = PluginLayoutInflater.inflate(pluginContext,
                    R.layout.main_layout, null);
            iwiPane = new IwiPane(pluginContext, mapView, v, traumaManager);
            dropDown = new IwiDropDown(mapView, v, iwiPane);
        }
        dropDown.show();
    }
}


package com.atakmap.android.ipaws.plugin;

import android.content.Context;

import com.atak.plugins.impl.PluginContextProvider;
import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.ipaws.AlertManager;
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
 * Public emergency alerts on the ATAK map, filtered on this device.
 *
 * <p>The manager lives for the plugin's life; the pane is only its controls. Nothing
 * here is a {@code Tool}, so nothing stops when the user switches base maps.
 */
public class IPAWS implements IPlugin {

    private static final String TAG = "IPAWS";

    AlertManager manager;
    MapView mapView;

    IServiceController serviceController;
    Context pluginContext;
    IHostUIService uiService;
    ToolbarItem toolbarItem;
    Pane templatePane;

    public IPAWS(IServiceController serviceController) {
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
                }).setIdentifier(pluginContext.getPackageName())
                .build();
    }

    @Override
    public void onStart() {
        if (uiService != null)
            uiService.addToolbarItem(toolbarItem);
        mapView = MapView.getMapView();
        if (mapView != null && manager == null) {
            manager = new AlertManager(mapView, pluginContext);
            manager.start();
        } else if (mapView == null) {
            Log.w(TAG, "no map view at start; the overlay cannot be attached");
        }
    }

    @Override
    public void onStop() {
        // ATAK keeps a plugin's pane on screen across a reload, and a pane whose
        // buttons point at a stopped instance does nothing when tapped.
        if (templatePane != null && uiService != null) {
            try {
                if (uiService.isPaneVisible(templatePane))
                    uiService.closePane(templatePane);
            } catch (Exception ignored) {
                // Already gone, which is the state we wanted.
            }
            templatePane = null;
        }
        if (manager != null) {
            manager.stop();
            manager = null;
        }
        if (uiService != null)
            uiService.removeToolbarItem(toolbarItem);
    }

    private void showPane() {
        // instantiate the plugin view if necessary
        if(templatePane == null) {
            // Remember to use the PluginLayoutInflator if you are actually inflating a custom view
            // In this case, using it is not necessary - but I am putting it here to remind
            // developers to look at this Inflator

            templatePane = new PaneBuilder(PluginLayoutInflater.inflate(pluginContext,
                    R.layout.main_layout, null))
                    // relative location is set to default; pane will switch location dependent on
                    // current orientation of device screen
                    .setMetaValue(Pane.RELATIVE_LOCATION, Pane.Location.Default)
                    // pane will take up 50% of screen width in landscape mode
                    .setMetaValue(Pane.PREFERRED_WIDTH_RATIO, 0.5D)
                    // pane will take up 50% of screen height in portrait mode
                    .setMetaValue(Pane.PREFERRED_HEIGHT_RATIO, 0.5D)
                    .build();
        }

        // if the plugin pane is not visible, show it!
        if(!uiService.isPaneVisible(templatePane)) {
            uiService.showPane(templatePane, null);
        }
    }
}

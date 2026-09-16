
package com.atakmap.android.ipaws.plugin;

import android.content.Context;

import com.atak.plugins.impl.PluginContextProvider;
import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.ipaws.AlertManager;
import com.atakmap.android.ipaws.ui.IpawsPane;
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
    IpawsPane paneUi;
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
        if (paneUi != null) {
            paneUi.dispose();
            paneUi = null;
        }
        if (manager != null) {
            manager.stop();
            manager = null;
        }
        if (uiService != null)
            uiService.removeToolbarItem(toolbarItem);
    }

    private void showPane() {
        if (manager == null || mapView == null) {
            Log.w(TAG, "no manager yet; the pane has nothing to show");
            return;
        }
        if (templatePane == null) {
            paneUi = new IpawsPane(mapView, pluginContext, manager, manager.getCounties());
            templatePane = new PaneBuilder(paneUi.getView())
                    .setMetaValue(Pane.RELATIVE_LOCATION, Pane.Location.Default)
                    .setMetaValue(Pane.PREFERRED_WIDTH_RATIO, 0.5D)
                    .setMetaValue(Pane.PREFERRED_HEIGHT_RATIO, 0.5D)
                    .build();
        }
        // Reopening shows the current state, including whatever arrived while it was
        // closed: the poll never stopped, only this view did.
        paneUi.refresh();
        if (!uiService.isPaneVisible(templatePane)) {
            uiService.showPane(templatePane, null);
        }
    }
}

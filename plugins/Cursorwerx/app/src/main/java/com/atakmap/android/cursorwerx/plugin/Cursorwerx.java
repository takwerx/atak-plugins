
package com.atakmap.android.cursorwerx.plugin;

import android.content.Context;

import com.atak.plugins.impl.PluginContextProvider;
import com.atakmap.android.cursorwerx.BackControl;
import com.atakmap.android.cursorwerx.ClickRepair;
import com.atakmap.android.cursorwerx.PanControl;
import com.atakmap.android.cursorwerx.PointerRouter;
import com.atakmap.android.maps.MapView;
import com.atak.plugins.impl.PluginLayoutInflater;

import gov.tak.api.plugin.IPlugin;
import gov.tak.api.plugin.IServiceController;
import gov.tak.api.ui.IHostUIService;
import gov.tak.api.ui.Pane;
import gov.tak.api.ui.PaneBuilder;
import gov.tak.api.ui.ToolbarItem;
import gov.tak.api.ui.ToolbarItemAdapter;
import gov.tak.platform.marshal.MarshalManager;

public class Cursorwerx implements IPlugin {

    IServiceController serviceController;
    Context pluginContext;
    IHostUIService uiService;
    ToolbarItem toolbarItem;
    Pane templatePane;
    BackControl backControl;
    PointerRouter pointerRouter;
    PanControl panControl;
    ClickRepair clickRepair;

    public Cursorwerx(IServiceController serviceController) {
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
        // A Back control that works. ATAK's own faux nav bar cannot press Back on
        // Android 14; see BackControl for why and for the measurement.
        final MapView mapView = MapView.getMapView();
        if (mapView != null) {
            backControl = new BackControl(mapView);
            backControl.attach();

            // Stop the map zooming when the wheel is used over ATAK's own UI.
            pointerRouter = new PointerRouter(mapView);
            pointerRouter.setVerbose(true); // bring-up: log where each tick landed
            pointerRouter.attach();

            // Mouse drag pans the map; a click is handed back to ATAK.
            panControl = new PanControl(mapView);
            panControl.setVerbose(true);
            panControl.attach();

            // A mouse click lands in text fields; see ClickRepair for the Android quirk.
            if (mapView.getContext() instanceof android.app.Activity) {
                clickRepair = new ClickRepair((android.app.Activity) mapView.getContext());
                clickRepair.setVerbose(true);
                clickRepair.attach();
            }
        }

        // the plugin is starting, add the button to the toolbar
        if (uiService == null)
            return;

        uiService.addToolbarItem(toolbarItem);
    }

    @Override
    public void onStop() {
        // Symmetric with onStart: a reload must leave no view and no listener behind.
        if (clickRepair != null) {
            clickRepair.detach();
            clickRepair = null;
        }
        if (panControl != null) {
            panControl.detach();
            panControl = null;
        }
        if (pointerRouter != null) {
            pointerRouter.detach();
            pointerRouter = null;
        }
        if (backControl != null) {
            backControl.detach();
            backControl = null;
        }

        // the plugin is stopping, remove the button from the toolbar
        if (uiService == null)
            return;

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

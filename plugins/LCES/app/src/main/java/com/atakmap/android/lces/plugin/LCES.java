package com.atakmap.android.lces.plugin;

import android.content.Context;

import com.atak.plugins.impl.PluginContextProvider;
import com.atakmap.android.lces.lessons.Incident;
import com.atakmap.android.lces.lessons.LessonsManager;
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
    LcesPane pane;
    LessonsManager lessons;
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
        if (mapView == null) {
            Log.w(TAG, "no map view at start");
            return;
        }
        // The Lessons Learned layer lives as long as the plugin, not the pane: it is on
        // the map and answers taps whether or not the pane has been opened.
        lessons = new LessonsManager(mapView, pluginContext);
        lessons.setListener(new LessonsManager.Listener() {
            @Override
            public void onLessons(LessonsManager.Result r) {
                if (pane != null)
                    pane.onLessons(r);
            }

            @Override
            public void onTapped(Incident i) {
                showPane();
                if (pane != null)
                    pane.openIncident(i);
            }
        });
        lessons.start();
    }

    @Override
    public void onStop() {
        // A pane whose buttons point at a stopped instance does nothing when tapped.
        if (dropDown != null) {
            dropDown.dispose();
            dropDown = null;
        }
        pane = null;
        if (lessons != null) {
            lessons.stop();
            lessons = null;
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
        if (dropDown == null) {
            pane = new LcesPane(mapView, pluginContext, lessons);
            dropDown = new LcesDropDown(mapView, pane);
            if (lessons != null && lessons.last() != null)
                pane.onLessons(lessons.last());
        }
        dropDown.show();
    }
}

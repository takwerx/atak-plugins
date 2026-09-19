package com.atakmap.android.mast.plugin;

import android.content.Context;
import android.widget.Toast;

import com.atak.plugins.impl.PluginContextProvider;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.mast.MastPane;
import com.atakmap.android.mast.Mission;
import com.atakmap.android.mast.feed.FeedPublisher;
import com.atakmap.coremap.log.Log;

import gov.tak.api.plugin.IPlugin;
import gov.tak.api.plugin.IServiceController;
import gov.tak.api.ui.IHostUIService;
import gov.tak.api.ui.ToolbarItem;
import gov.tak.api.ui.ToolbarItemAdapter;
import gov.tak.platform.marshal.MarshalManager;

/**
 * MAST, Mission Adaptable Survey Tool: pick the mission, get only its tools. One
 * toolbar button, one pane. See PLAN-MAST-v0.1 in the notes repo.
 */
public class MAST implements IPlugin {

    private static final String TAG = "MAST";

    IServiceController serviceController;
    Context pluginContext;
    IHostUIService uiService;
    ToolbarItem toolbarItem;
    private MapView mapView;
    private MastPane pane;
    private FeedPublisher feeds;

    public MAST(IServiceController serviceController) {
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
                        // ic_toolbar: the bare white glyph for ATAK's dark toolbar.
                        // ic_launcher is the same glyph on a dark tile, for Android.
                        pluginContext.getResources().getDrawable(R.drawable.ic_toolbar),
                        android.graphics.drawable.Drawable.class,
                        gov.tak.api.commons.graphics.Bitmap.class))
                .setListener(new ToolbarItemAdapter() {
                    @Override
                    public void onClick(ToolbarItem item) {
                        if (pane != null)
                            pane.toggle();
                    }
                }).setIdentifier(pluginContext.getPackageName())
                .build();
    }

    @Override
    public void onStart() {
        if (uiService == null)
            return;
        mapView = MapView.getMapView();
        if (mapView == null) {
            Log.w(TAG, "no MapView; MAST not started");
            return;
        }
        feeds = new FeedPublisher(mapView, pluginContext);
        pane = new MastPane(mapView, pluginContext, feeds);
        try {
            pane.setMission(Mission.load(pluginContext, "missions/active_shooter.json"));
        } catch (Exception e) {
            Log.e(TAG, "mission load failed", e);
            Toast.makeText(mapView.getContext(),
                    pluginContext.getString(R.string.mission_load_failed, e.getMessage()),
                    Toast.LENGTH_LONG).show();
        }
        uiService.addToolbarItem(toolbarItem);
    }

    @Override
    public void onStop() {
        if (uiService == null)
            return;
        uiService.removeToolbarItem(toolbarItem);
        if (pane != null) {
            pane.dispose();
            pane = null;
        }
        if (feeds != null) {
            feeds.dispose();
            feeds = null;
        }
    }
}

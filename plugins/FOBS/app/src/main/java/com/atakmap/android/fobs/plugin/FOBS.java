package com.atakmap.android.fobs.plugin;

import android.content.Context;

import com.atak.plugins.impl.PluginContextProvider;
import com.atakmap.android.cot.detail.CotDetailManager;
import com.atakmap.android.fobs.track.FobsDetailHandler;
import com.atakmap.android.fobs.track.GpsTrackTool;
import com.atakmap.android.fobs.ui.Chooser;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.toolbar.ToolManagerBroadcastReceiver;
import com.atakmap.coremap.log.Log;

import gov.tak.api.plugin.IPlugin;
import gov.tak.api.plugin.IServiceController;
import gov.tak.api.ui.IHostUIService;
import gov.tak.api.ui.ToolbarItem;
import gov.tak.api.ui.ToolbarItemAdapter;
import gov.tak.platform.marshal.MarshalManager;

/**
 * FOBS, Field Observation Survey. One toolbar button, one chooser, and tracks that are
 * ordinary ATAK drawing shapes. See PLAN-FOBS-v0.1 in the notes repo.
 */
public class FOBS implements IPlugin {

    private static final String TAG = "FOBS";

    IServiceController serviceController;
    Context pluginContext;
    IHostUIService uiService;
    ToolbarItem toolbarItem;

    private MapView mapView;
    private Chooser chooser;
    private GpsTrackTool gpsTrackTool;
    private FobsDetailHandler detailHandler;

    public FOBS(IServiceController serviceController) {
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
                        if (chooser != null)
                            chooser.show();
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
            Log.w(TAG, "no MapView; FOBS not started");
            return;
        }

        detailHandler = new FobsDetailHandler();
        CotDetailManager.getInstance().registerHandler(detailHandler);

        gpsTrackTool = new GpsTrackTool(mapView, pluginContext);
        ToolManagerBroadcastReceiver.getInstance().registerTool(GpsTrackTool.ID,
                gpsTrackTool);

        chooser = new Chooser(mapView, pluginContext);
        uiService.addToolbarItem(toolbarItem);
    }

    @Override
    public void onStop() {
        if (uiService == null)
            return;
        uiService.removeToolbarItem(toolbarItem);
        chooser = null;
        if (gpsTrackTool != null) {
            ToolManagerBroadcastReceiver.getInstance().unregisterTool(GpsTrackTool.ID);
            gpsTrackTool.dispose();
            gpsTrackTool = null;
        }
        if (detailHandler != null) {
            CotDetailManager.getInstance().unregisterHandler(detailHandler);
            detailHandler = null;
        }
    }
}

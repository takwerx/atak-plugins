package com.atakmap.android.fobs.plugin;

import android.content.Context;

import com.atak.plugins.impl.PluginContextProvider;
import com.atakmap.android.cot.detail.CotDetailManager;
import com.atakmap.android.fobs.feed.FeedPublisher;
import com.atakmap.android.fobs.track.CutTrackTool;
import com.atakmap.android.fobs.track.DrawTrackTool;
import com.atakmap.android.fobs.track.FobsDetailHandler;
import com.atakmap.android.fobs.track.FobsMenuFactory;
import com.atakmap.android.fobs.track.FreehandTrack;
import com.atakmap.android.fobs.track.GpsTrackTool;
import com.atakmap.android.fobs.track.ImportTrack;
import com.atakmap.android.fobs.track.JoinTracksTool;
import com.atakmap.android.fobs.track.LassoJoin;
import com.atakmap.android.fobs.track.SelectTrack;
import com.atakmap.android.fobs.ui.FobsPane;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.menu.MapMenuReceiver;
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
    private FobsPane pane;
    private GpsTrackTool gpsTrackTool;
    private DrawTrackTool drawTrackTool;
    private CutTrackTool cutTrackTool;
    private JoinTracksTool joinTracksTool;
    private LassoJoin lassoJoin;
    private SelectTrack selectTrack;
    private FreehandTrack freehand;
    private ImportTrack importTrack;
    private FobsMenuFactory menuFactory;
    private FeedPublisher feed;
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
            Log.w(TAG, "no MapView; FOBS not started");
            return;
        }

        detailHandler = new FobsDetailHandler();
        CotDetailManager.getInstance().registerHandler(detailHandler);
        feed = new FeedPublisher(mapView, pluginContext);

        gpsTrackTool = new GpsTrackTool(mapView, pluginContext);
        ToolManagerBroadcastReceiver.getInstance().registerTool(GpsTrackTool.ID,
                gpsTrackTool);
        drawTrackTool = new DrawTrackTool(mapView, pluginContext);
        ToolManagerBroadcastReceiver.getInstance().registerTool(DrawTrackTool.ID,
                drawTrackTool);
        cutTrackTool = new CutTrackTool(mapView, pluginContext);
        cutTrackTool.setUiService(uiService);
        joinTracksTool = new JoinTracksTool(mapView, pluginContext);
        ToolManagerBroadcastReceiver.getInstance().registerTool(JoinTracksTool.ID,
                joinTracksTool);
        ToolManagerBroadcastReceiver.getInstance().registerTool(CutTrackTool.ID,
                cutTrackTool);

        selectTrack = new SelectTrack(mapView, pluginContext);
        pane = new FobsPane(mapView, pluginContext, uiService);
        pane.setSelectTrack(selectTrack);
        freehand = new FreehandTrack(mapView, pluginContext);
        pane.setFreehand(freehand);
        importTrack = new ImportTrack(mapView, pluginContext);
        pane.setImportTrack(importTrack);
        lassoJoin = new LassoJoin(mapView, pluginContext);
        pane.setLassoJoin(lassoJoin);
        selectTrack.setImportTrack(importTrack);
        menuFactory = new FobsMenuFactory(mapView, pluginContext, importTrack);
        MapMenuReceiver.getInstance().registerMapMenuFactory(menuFactory);
        uiService.addToolbarItem(toolbarItem);
    }

    @Override
    public void onStop() {
        if (uiService == null)
            return;
        uiService.removeToolbarItem(toolbarItem);
        if (pane != null) {
            pane.close();
            pane = null;
        }
        if (gpsTrackTool != null) {
            ToolManagerBroadcastReceiver.getInstance().unregisterTool(GpsTrackTool.ID);
            gpsTrackTool.dispose();
            gpsTrackTool = null;
        }
        if (menuFactory != null) {
            MapMenuReceiver.getInstance().unregisterMapMenuFactory(menuFactory);
            menuFactory = null;
        }
        if (importTrack != null) {
            importTrack.dispose();
            importTrack = null;
        }
        if (freehand != null) {
            freehand.dispose();
            freehand = null;
        }
        if (selectTrack != null) {
            selectTrack.dispose();
            selectTrack = null;
        }
        if (lassoJoin != null) {
            lassoJoin.dispose();
            lassoJoin = null;
        }
        if (joinTracksTool != null) {
            ToolManagerBroadcastReceiver.getInstance().unregisterTool(JoinTracksTool.ID);
            joinTracksTool.dispose();
            joinTracksTool = null;
        }
        if (cutTrackTool != null) {
            ToolManagerBroadcastReceiver.getInstance().unregisterTool(CutTrackTool.ID);
            cutTrackTool.dispose();
            cutTrackTool = null;
        }
        if (drawTrackTool != null) {
            ToolManagerBroadcastReceiver.getInstance().unregisterTool(DrawTrackTool.ID);
            drawTrackTool.dispose();
            drawTrackTool = null;
        }
        if (feed != null) {
            feed.dispose();
            feed = null;
        }
        if (detailHandler != null) {
            CotDetailManager.getInstance().unregisterHandler(detailHandler);
            detailHandler = null;
        }
    }
}

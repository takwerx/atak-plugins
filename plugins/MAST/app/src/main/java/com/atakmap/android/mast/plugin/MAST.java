package com.atakmap.android.mast.plugin;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import com.atak.plugins.impl.PluginContextProvider;
import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.android.overlay.MapOverlay;
import com.atakmap.android.toolbar.ToolManagerBroadcastReceiver;
import com.atakmap.android.user.PlacePointTool;
import com.atakmap.coremap.log.Log;

import gov.tak.api.plugin.IPlugin;
import gov.tak.api.plugin.IServiceController;
import gov.tak.api.ui.IHostUIService;
import gov.tak.api.ui.Pane;
import gov.tak.api.ui.PaneBuilder;
import gov.tak.api.ui.ToolbarItem;
import gov.tak.api.ui.ToolbarItemAdapter;
import gov.tak.platform.marshal.MarshalManager;

import java.util.ArrayList;

/**
 * MAST, Mission Adaptable Survey Tool. See PLAN-MAST-v0.1 in the notes repo.
 *
 * <p>M0 state: a throwaway pane with one button per unproven mechanic (open Overlay
 * Manager inside Imagery, start ATAK's shape tool with a callback, drop a typed marker).
 * Replaced by the mission picker at M1.
 */
public class MAST implements IPlugin {

    private static final String TAG = "MAST";

    /** Callback ATAK's ShapeCreationTool fires when the shape is finished. */
    private static final String SHAPE_DONE = "com.atakmap.android.mast.SHAPE_DONE";
    private static final String SHAPE_TOOL = "com.atakmap.android.drawing.SHAPE_TOOL";
    private static final String MANAGE_HIERARCHY = "com.atakmap.android.maps.MANAGE_HIERARCHY";
    /** The Maps dropdown (Imagery / Mobile / Favorites tabs); imagery is not in Overlay Manager on 5.8. */
    private static final String MANAGE_LAYERS = "com.atakmap.android.maps.MANAGE_LAYERS";

    IServiceController serviceController;
    Context pluginContext;
    IHostUIService uiService;
    ToolbarItem toolbarItem;
    Pane pane;
    private MapView mapView;
    private int markerCount = 0;

    private final BroadcastReceiver shapeDone = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String uid = intent.getStringExtra("uid");
            MapItem item = uid == null ? null : mapView.getRootGroup().deepFindUID(uid);
            String msg = "M0 shape callback: uid=" + uid + " item="
                    + (item == null ? "not found"
                            : item.getType() + " '" + item.getTitle() + "' in "
                                    + (item.getGroup() == null ? "no group"
                                            : item.getGroup().getFriendlyName()));
            Log.d(TAG, msg);
            Toast.makeText(mapView.getContext(), msg, Toast.LENGTH_LONG).show();
        }
    };

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
                        showPane();
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
        AtakBroadcast.getInstance().registerReceiver(shapeDone,
                new AtakBroadcast.DocumentedIntentFilter(SHAPE_DONE,
                        "MAST: ATAK's shape tool finished; carries the shape uid"));
        uiService.addToolbarItem(toolbarItem);
    }

    @Override
    public void onStop() {
        if (uiService == null)
            return;
        uiService.removeToolbarItem(toolbarItem);
        if (mapView != null)
            AtakBroadcast.getInstance().unregisterReceiver(shapeDone);
        if (pane != null && uiService.isPaneVisible(pane))
            uiService.closePane(pane);
        pane = null;
    }

    private void showPane() {
        if (pane == null) {
            View v = PluginLayoutInflater.inflate(pluginContext, R.layout.main_layout, null);
            v.findViewById(R.id.m0_maps).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    openImagery();
                }
            });
            v.findViewById(R.id.m0_shape).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    drawShape();
                }
            });
            v.findViewById(R.id.m0_marker).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    dropMarker();
                }
            });
            v.findViewById(R.id.m0_overlays).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    listOverlays();
                }
            });
            pane = new PaneBuilder(v)
                    .setMetaValue(Pane.RELATIVE_LOCATION, Pane.Location.Default)
                    .setMetaValue(Pane.PREFERRED_WIDTH_RATIO, 0.5D)
                    .setMetaValue(Pane.PREFERRED_HEIGHT_RATIO, 0.5D)
                    .build();
        }
        if (!uiService.isPaneVisible(pane))
            uiService.showPane(pane, null);
    }

    private void closePane() {
        if (pane != null && uiService.isPaneVisible(pane))
            uiService.closePane(pane);
    }

    private void toast(String msg) {
        Log.d(TAG, msg);
        Toast.makeText(mapView.getContext(), msg, Toast.LENGTH_LONG).show();
    }

    /** Every registered overlay, so the Imagery entry's UID is read off the device. */
    private void listOverlays() {
        int n = 0;
        for (MapOverlay o : mapView.getMapOverlayManager().getOverlays()) {
            Log.d(TAG, "M0 overlay id='" + o.getIdentifier() + "' name='" + o.getName()
                    + "' class=" + o.getClass().getName());
            n++;
        }
        toast("M0: " + n + " overlays logged (adb logcat -s MAST)");
    }

    /**
     * Measured on the XCover (5.8.0.3), 2026-09-18: Overlay Manager's root list has no
     * Imagery entry and no MapOverlay is named Imagery, so MANAGE_HIERARCHY with
     * list_item_paths=[Imagery] opens Overlay Manager at the root. Imagery is the Maps
     * dropdown: MANAGE_LAYERS, whose Imagery tab sorts on-device datasets by whether
     * they cover the current view.
     */
    private void openImagery() {
        Log.d(TAG, "M0 sending " + MANAGE_LAYERS);
        closePane();
        AtakBroadcast.getInstance().sendBroadcast(new Intent(MANAGE_LAYERS));
    }

    /** ATAK's own polygon tool with a callback; the receiver above reports the uid. */
    private void drawShape() {
        Bundle b = new Bundle();
        b.putParcelable("callback", new Intent(SHAPE_DONE));
        closePane();
        ToolManagerBroadcastReceiver.getInstance().startTool(SHAPE_TOOL, b);
        Log.d(TAG, "M0 startTool(" + SHAPE_TOOL + ") sent; active="
                + ToolManagerBroadcastReceiver.getInstance().getActiveTool());
    }

    /** A typed, persistent marker through ATAK's own PlacePointTool at the map center. */
    private void dropMarker() {
        markerCount++;
        String name = mapView.getDeviceCallsign() + " - Suspect LKP " + markerCount;
        Marker m = new PlacePointTool.MarkerCreator(mapView.getCenterPoint())
                .setType("a-h-G-U-C-I")
                .setCallsign(name)
                .setHow("h-g-i-g-o")
                .setArchive(true)
                .showCotDetails(false)
                .placePoint();
        if (m == null) {
            toast("M0: placePoint returned null");
            return;
        }
        toast("M0 marker " + m.getUID() + " type=" + m.getType() + " '" + m.getTitle()
                + "' in " + (m.getGroup() == null ? "no group" : m.getGroup().getFriendlyName()));
    }
}

package com.atakmap.android.mast.plugin;

import android.content.Context;
import android.widget.Toast;

import com.atak.plugins.impl.PluginContextProvider;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.mast.MastPane;
import com.atakmap.android.mast.Missions;
import com.atakmap.coremap.log.Log;

import gov.tak.api.plugin.IPlugin;
import gov.tak.api.plugin.IServiceController;
import gov.tak.api.ui.IHostUIService;
import gov.tak.api.ui.ToolbarItem;
import gov.tak.api.ui.ToolbarItemAdapter;
import gov.tak.platform.marshal.MarshalManager;

/**
 * MAST, Mission Adaptable Support Tools: pick the mission, open its plugins. One
 * toolbar button, one pane: the front door to the mission plugins. See
 * PLAN-MAST-v0.1 in the notes repo.
 */
public class MAST implements IPlugin {

    private static final String TAG = "MAST";
    /** The Tool Preferences entry, which is where the manual is opened. */
    private static final String PREFS_KEY = "mast_preferences";

    /** Opens the pane; a system receiver, so adb reaches it too (Atmosphere's way). */
    public static final String SHOW_ACTION = "com.atakmap.android.mast.SHOW";

    IServiceController serviceController;
    Context pluginContext;
    IHostUIService uiService;
    ToolbarItem toolbarItem;
    private MapView mapView;
    private MastPane pane;
    private android.content.BroadcastReceiver showReceiver;

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
        pane = new MastPane(mapView, pluginContext);
        try {
            pane.setMissions(Missions.load(pluginContext, "missions.json"));
        } catch (Exception e) {
            Log.e(TAG, "missions load failed", e);
            Toast.makeText(mapView.getContext(),
                    pluginContext.getString(R.string.mission_load_failed, e.getMessage()),
                    Toast.LENGTH_LONG).show();
        }
        uiService.addToolbarItem(toolbarItem);
        registerPreferences();
        showReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context context, android.content.Intent intent) {
                if (pane != null)
                    pane.show();
            }
        };
        try {
            AtakBroadcast.getInstance().registerSystemReceiver(showReceiver,
                    new AtakBroadcast.DocumentedIntentFilter(SHOW_ACTION, "Open the MAST pane"));
        } catch (RuntimeException e) {
            Log.w(TAG, "show receiver", e);
            showReceiver = null;
        }
    }

    @Override
    public void onStop() {
        if (uiService == null)
            return;
        uiService.removeToolbarItem(toolbarItem);
        unregisterPreferences();
        if (showReceiver != null) {
            try {
                AtakBroadcast.getInstance().unregisterSystemReceiver(showReceiver);
            } catch (RuntimeException e) {
                Log.w(TAG, "show receiver was not registered", e);
            }
            showReceiver = null;
        }
        if (pane != null) {
            pane.dispose();
            pane = null;
        }
    }

    private void registerPreferences() {
        try {
            com.atakmap.app.preferences.ToolsPreferenceFragment.register(
                    new com.atakmap.app.preferences.ToolsPreferenceFragment.ToolPreference(
                            pluginContext.getString(R.string.app_name),
                            pluginContext.getString(R.string.prefs_summary),
                            PREFS_KEY,
                            // ic_toolbar: this row sits on ATAK's dark UI.
                            pluginContext.getResources().getDrawable(R.drawable.ic_toolbar),
                            new MastPreferenceFragment(pluginContext)));
        } catch (LinkageError | RuntimeException notThisBuild) {
            Log.w(TAG, "could not register preferences: " + notThisBuild);
        }
    }

    private void unregisterPreferences() {
        try {
            com.atakmap.app.preferences.ToolsPreferenceFragment.unregister(PREFS_KEY);
        } catch (LinkageError | RuntimeException notThisBuild) {
            Log.w(TAG, "could not unregister preferences: " + notThisBuild);
        }
    }
}

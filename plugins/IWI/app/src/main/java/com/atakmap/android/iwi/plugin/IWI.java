package com.atakmap.android.iwi.plugin;

import android.content.Context;
import android.content.Intent;

import com.atak.plugins.impl.PluginContextProvider;
import com.atakmap.android.cot.detail.CotDetailManager;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.menu.MapMenuEventListener;
import com.atakmap.android.menu.MapMenuReceiver;
import com.atakmap.coremap.log.Log;

import gov.tak.api.plugin.IPlugin;
import gov.tak.api.plugin.IServiceController;
import gov.tak.api.ui.IHostUIService;
import gov.tak.api.ui.ToolbarItem;
import gov.tak.api.ui.ToolbarItemAdapter;
import gov.tak.platform.marshal.MarshalManager;

/**
 * Incident Within an Incident: the NWCG Medical Incident Report, its Radio Script, one
 * CASEVAC marker with the nearest trauma center, shared through a Data Sync feed.
 *
 * <p>Its own plugin again since 2026-11-07, after four days inside LCES (PLAN-LCES
 * decision 9, reversed); the code written there is the code here.
 */
public class IWI implements IPlugin {

    private static final String TAG = "IWI";
    /** The Tool Preferences entry, the only way to reach the manual. */
    private static final String PREFS_KEY = "iwiPreference";
    private HospitalLabels hospitalLabels;

    IServiceController serviceController;
    Context pluginContext;
    IHostUIService uiService;
    ToolbarItem toolbarItem;
    /** The pane, hosted in a drop-down so it can be dragged out wide. */
    IwiDropDown dropDown;
    MapView mapView;
    /** The trauma center pack and the report's saved state, for the plugin's life. */
    TraumaManager trauma;
    /** Makes {@code _iwi_} typed rather than opaque on this device. */
    IwiDetailHandler iwiDetailHandler;
    /** Claims a tap on this device's own IWI marker, or one carrying a report. */
    MapMenuEventListener iwiTap;

    public IWI(IServiceController serviceController) {
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
                        showPane(null);
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
        if (iwiDetailHandler == null)
            iwiDetailHandler = new IwiDetailHandler();
        CotDetailManager.getInstance().registerHandler(iwiDetailHandler);
        // Read from disk on a worker, refreshed from the depot in the background.
        trauma = new TraumaManager(mapView);
        trauma.start();
        hospitalLabels = new HospitalLabels(mapView);
        hospitalLabels.start();
        registerIwiTap();
        registerPreferences();
    }

    private void registerPreferences() {
        try {
            com.atakmap.app.preferences.ToolsPreferenceFragment.register(
                    new com.atakmap.app.preferences.ToolsPreferenceFragment.ToolPreference(
                            pluginContext.getString(R.string.app_name),
                            pluginContext.getString(R.string.prefs_summary),
                            PREFS_KEY,
                            pluginContext.getResources().getDrawable(R.drawable.ic_toolbar),
                            new IwiPreferenceFragment(pluginContext)));
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

    /**
     * A tap on this device's IWI marker opens the IWI page, not ATAK's CASEVAC view
     * (operator 2026-10-14). The marker stays a genuine CASEVAC, so a device without
     * IWI opens its own CASEVAC view. Another device's IWI that carries its report
     * opens read only (2026-11-06). Answering true stops the radial.
     */
    private void registerIwiTap() {
        if (iwiTap != null || trauma == null)
            return;
        iwiTap = new MapMenuEventListener() {
            @Override
            public boolean onShowMenu(MapItem item) {
                if (item == null || trauma == null)
                    return false;
                final boolean ours = IwiPane.isOurs(trauma, item.getUID());
                if (!ours && !IwiPane.carriesReport(item))
                    return false;
                final String uid = item.getUID();
                // A moment later: a pick from ATAK's Select Item list posts its own
                // show-details after this, which would close a pane opened inline.
                final MapView mv = MapView.getMapView();
                if (mv != null) {
                    mv.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            clearAtakSelection();
                            showPane(ours ? null : uid);
                        }
                    }, 250);
                }
                return true;
            }

            @Override
            public void onHideMenu(MapItem item) {
            }
        };
        final MapMenuReceiver menus = MapMenuReceiver.getInstance();
        if (menus != null)
            menus.addEventListener(iwiTap);
        else
            Log.w(TAG, "no radial menu receiver; a tap on the IWI marker keeps ATAK's view");
    }

    /** Take ATAK's own focus and callout off the map, as its list items do. */
    private static void clearAtakSelection() {
        try {
            AtakBroadcast.getInstance().sendBroadcast(
                    new Intent("com.atakmap.android.maps.UNFOCUS"));
            AtakBroadcast.getInstance().sendBroadcast(
                    new Intent("com.atakmap.android.maps.HIDE_DETAILS"));
        } catch (RuntimeException e) {
            Log.d(TAG, "could not clear ATAK's selection: " + e);
        }
    }

    @Override
    public void onStop() {
        unregisterPreferences();
        if (iwiTap != null) {
            final MapMenuReceiver menus = MapMenuReceiver.getInstance();
            if (menus != null)
                menus.removeEventListener(iwiTap);
            iwiTap = null;
        }
        // A pane whose buttons point at a stopped instance does nothing when tapped;
        // disposeImpl disposes the pane, which lets go of any map pick.
        if (dropDown != null) {
            dropDown.closeDropDown();
            dropDown.dispose();
            dropDown = null;
        }
        if (hospitalLabels != null) {
            hospitalLabels.stop();
            hospitalLabels = null;
        }
        if (iwiDetailHandler != null)
            CotDetailManager.getInstance().unregisterHandler(iwiDetailHandler);
        if (trauma != null) {
            trauma.stop();
            trauma = null;
        }
        if (uiService != null)
            uiService.removeToolbarItem(toolbarItem);
    }

    /** The IWI page, or with a uid, another device's IWI read only. */
    private void showPane(String viewUid) {
        if (mapView == null)
            mapView = MapView.getMapView();
        if (mapView == null || trauma == null) {
            Log.w(TAG, "no map view; the pane has nothing to show");
            return;
        }
        if (dropDown == null)
            dropDown = new IwiDropDown(mapView, pluginContext, trauma);
        if (viewUid == null)
            dropDown.show();
        else
            dropDown.showView(viewUid);
    }
}

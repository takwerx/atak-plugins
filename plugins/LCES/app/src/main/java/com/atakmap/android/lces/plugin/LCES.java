package com.atakmap.android.lces.plugin;

import android.content.Context;
import android.content.Intent;

import com.atak.plugins.impl.PluginContextProvider;
import com.atakmap.android.cot.detail.CotDetailManager;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.lces.iwi.IwiDetailHandler;
import com.atakmap.android.lces.iwi.IwiPane;
import com.atakmap.android.lces.iwi.TraumaManager;
import com.atakmap.android.lces.lessons.Incident;
import com.atakmap.android.lces.lessons.LessonsManager;
import com.atakmap.android.lces.ui.LcesDropDown;
import com.atakmap.android.lces.ui.LcesPane;
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
 * Lookouts, Communications, Escape Routes, Safety Zones: the IRPG one tap away,
 * wildland fire lessons learned on the map, and IWI, the Medical Incident Report with
 * its CASEVAC marker and the nearest trauma center (copied forward from the IWI
 * plugin, PLAN-LCES decision 9).
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
    /** IWI: the trauma center pack and the report's saved state, for the plugin's life. */
    TraumaManager trauma;
    /** Makes {@code _iwi_} typed rather than opaque on this device. */
    IwiDetailHandler iwiDetailHandler;
    /** Claims a tap on this device's own IWI marker for the IWI page. */
    MapMenuEventListener iwiTap;

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

        if (iwiDetailHandler == null)
            iwiDetailHandler = new IwiDetailHandler();
        CotDetailManager.getInstance().registerHandler(iwiDetailHandler);
        trauma = new TraumaManager(mapView);
        trauma.start();
        registerIwiTap();
    }

    /**
     * A tap on this device's IWI marker opens the IWI page, not ATAK's CASEVAC view
     * (IWI's, operator 2026-10-14). The marker stays a genuine CASEVAC, so a device
     * without LCES opens its own CASEVAC view; only this device's own marker and the
     * not-yet-declared preview are claimed. Answering true stops the radial.
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
                // Another device's IWI with its report on it: read it all, read only.
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
                            showPane();
                            if (pane == null)
                                return;
                            if (ours)
                                pane.openIwi();
                            else
                                pane.openIwiView(uid);
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
        if (iwiTap != null) {
            final MapMenuReceiver menus = MapMenuReceiver.getInstance();
            if (menus != null)
                menus.removeEventListener(iwiTap);
            iwiTap = null;
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
            pane = new LcesPane(mapView, pluginContext, lessons, trauma);
            dropDown = new LcesDropDown(mapView, pane);
            if (lessons != null && lessons.last() != null)
                pane.onLessons(lessons.last());
        }
        dropDown.show();
    }
}

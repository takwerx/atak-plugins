
package com.atakmap.android.iwi.plugin;

import android.content.Context;
import android.view.View;

import android.content.Intent;

import com.atakmap.android.cot.detail.CotDetailManager;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.menu.MapMenuEventListener;
import com.atakmap.android.menu.MapMenuReceiver;
import com.atakmap.coremap.log.Log;

import com.atak.plugins.impl.PluginContextProvider;
import com.atak.plugins.impl.PluginLayoutInflater;

import gov.tak.api.plugin.IPlugin;
import gov.tak.api.plugin.IServiceController;
import gov.tak.api.ui.IHostUIService;
import gov.tak.api.ui.ToolbarItem;
import gov.tak.api.ui.ToolbarItemAdapter;
import gov.tak.platform.marshal.MarshalManager;

public class IWI implements IPlugin {

    IServiceController serviceController;
    Context pluginContext;
    IHostUIService uiService;
    ToolbarItem toolbarItem;
    /** The pane, hosted in a drop-down so it can be dragged out wide. */
    IwiDropDown dropDown;
    IwiDetailHandler iwiDetailHandler;
    /** Anything that must outlive a tap lives here, not in a Tool. */
    TraumaManager traumaManager;
    IwiPane iwiPane;
    /** Claims a tap on this phone's own IWI marker for the IWI pane. */
    MapMenuEventListener menuListener;

    public IWI(IServiceController serviceController) {
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
                })
                .build();
    }

    @Override
    public void onStart() {
        // The detail handler is what makes _iwi_ typed rather than opaque on a
        // phone that has the plugin.
        if (iwiDetailHandler == null)
            iwiDetailHandler = new IwiDetailHandler();
        CotDetailManager.getInstance().registerHandler(iwiDetailHandler);

        // The trauma center pack: read from disk on a worker, refreshed from the
        // depot in the background, kept for the plugin's life.
        final MapView mapView = MapView.getMapView();
        if (mapView != null && traumaManager == null) {
            traumaManager = new TraumaManager(mapView);
            traumaManager.start();
        }

        registerTap();

        if (uiService == null)
            return;

        uiService.addToolbarItem(toolbarItem);
    }

    @Override
    public void onStop() {
        if (menuListener != null) {
            final MapMenuReceiver menus = MapMenuReceiver.getInstance();
            if (menus != null)
                menus.removeEventListener(menuListener);
            menuListener = null;
        }
        if (iwiDetailHandler != null)
            CotDetailManager.getInstance().unregisterHandler(iwiDetailHandler);
        if (traumaManager != null) {
            traumaManager.stop();
            traumaManager = null;
        }
        if (dropDown != null) {
            // disposeImpl disposes the pane, which lets go of any map pick.
            dropDown.closeDropDown();
            dropDown.dispose();
            dropDown = null;
        }
        iwiPane = null;

        // the plugin is stopping, remove the button from the toolbar
        if (uiService == null)
            return;

        uiService.removeToolbarItem(toolbarItem);
    }

    /**
     * A tap on this phone's IWI marker opens IWI, not ATAK's CASEVAC view (operator,
     * 2026-10-14: "when i click on it, it opens casevac"). The marker stays a genuine
     * CASEVAC, so a phone without IWI still opens its own CASEVAC view and reads the
     * report there; only here is the tap IWI's.
     *
     * <p>ATAK asks a {@code MapMenuEventListener} before it opens anything; answering
     * true stops the radial and tells ATAK the tap was handled (AirAware's path). Only
     * this phone's own IWI marker and the not-yet-declared preview are claimed; every
     * other item, a CASEVAC from another phone included, keeps ATAK's own behavior.
     */
    private void registerTap() {
        if (menuListener != null || traumaManager == null)
            return;
        menuListener = new MapMenuEventListener() {
            @Override
            public boolean onShowMenu(MapItem item) {
                if (item == null || traumaManager == null)
                    return false;
                final String uid = item.getUID();
                final IwiState state = new IwiState(traumaManager.prefs());
                final boolean ours = PreviewMarker.UID.equals(uid)
                        || (state.isLive() && state.uid().equals(uid));
                if (!ours)
                    return false;
                // A moment later: a pick from ATAK's Select Item list posts its own
                // show-details after this, which would close a pane opened inline.
                final MapView mv = MapView.getMapView();
                if (mv != null) {
                    mv.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            clearAtakSelection();
                            showPane();
                            if (iwiPane != null)
                                iwiPane.showMain();
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
            menus.addEventListener(menuListener);
        else
            Log.w("IWI", "no radial menu receiver; a tap keeps ATAK's own CASEVAC view");
    }

    /** Take ATAK's own focus and callout off the map, as its list items do. */
    private static void clearAtakSelection() {
        try {
            AtakBroadcast.getInstance().sendBroadcast(
                    new Intent("com.atakmap.android.maps.UNFOCUS"));
            AtakBroadcast.getInstance().sendBroadcast(
                    new Intent("com.atakmap.android.maps.HIDE_DETAILS"));
        } catch (RuntimeException e) {
            Log.d("IWI", "could not clear ATAK's selection: " + e);
        }
    }

    private void showPane() {
        final MapView mapView = MapView.getMapView();
        if (mapView == null || traumaManager == null)
            return;
        if (dropDown == null) {
            final View v = PluginLayoutInflater.inflate(pluginContext,
                    R.layout.main_layout, null);
            iwiPane = new IwiPane(pluginContext, mapView, v, traumaManager);
            dropDown = new IwiDropDown(mapView, v, iwiPane);
            iwiPane.setWideToggle(new Runnable() {
                @Override
                public void run() {
                    if (dropDown != null)
                        dropDown.toggleWide();
                }
            });
        }
        dropDown.show();
    }
}

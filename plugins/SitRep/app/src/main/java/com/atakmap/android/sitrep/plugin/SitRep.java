package com.atakmap.android.sitrep.plugin;

import android.content.Context;

import com.atak.plugins.impl.PluginContextProvider;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.sitrep.AreaOutlines;
import com.atakmap.android.sitrep.Report;
import com.atakmap.android.sitrep.SitRepManager;
import com.atakmap.android.sitrep.ui.SitRepPane;
import com.atakmap.coremap.log.Log;

import gov.tak.api.plugin.IPlugin;
import gov.tak.api.plugin.IServiceController;
import gov.tak.api.ui.IHostUIService;
import gov.tak.api.ui.Pane;
import gov.tak.api.ui.PaneBuilder;
import gov.tak.api.ui.ToolbarItem;
import gov.tak.api.ui.ToolbarItemAdapter;
import gov.tak.platform.marshal.MarshalManager;

/**
 * Sit Rep: draw an area, read an all-hazard situation report for it.
 *
 * <p>The manager lives for the plugin's life and owns the reports; the pane is only
 * how they are read. Nothing is a {@code Tool}, so a report keeps fetching when the
 * operator switches tools, opens a dropdown or closes the pane.
 */
public class SitRep implements IPlugin {

    private static final String TAG = "SitRep";

    IServiceController serviceController;
    Context pluginContext;
    IHostUIService uiService;
    ToolbarItem toolbarItem;
    Pane pane;
    SitRepPane paneUi;
    SitRepManager manager;
    MapView mapView;
    private com.atakmap.android.menu.MapMenuEventListener tapOpensReport;

    public SitRep(IServiceController serviceController) {
        this.serviceController = serviceController;
        final PluginContextProvider ctxProvider = serviceController
                .getService(PluginContextProvider.class);
        if (ctxProvider != null) {
            pluginContext = ctxProvider.getPluginContext();
            pluginContext.setTheme(R.style.ATAKPluginTheme);
        }
        uiService = serviceController.getService(IHostUIService.class);
        // A well-known identifier, or the toolbar configuration cannot find the
        // button again after the operator moves it.
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
        manager = new SitRepManager(mapView);
        manager.setListener(new SitRepManager.Listener() {
            @Override
            public void changed() {
                if (paneUi != null)
                    paneUi.refresh();
            }

            @Override
            public void reportStarted(Report r) {
                showPane(r.id);
            }
        });
        manager.start();
        registerTap();
    }

    @Override
    public void onStop() {
        // ATAK keeps a plugin's pane on screen across a reload, and a pane whose
        // buttons point at a stopped instance does nothing when tapped.
        if (pane != null && uiService != null) {
            try {
                if (uiService.isPaneVisible(pane))
                    uiService.closePane(pane);
            } catch (Exception ignored) {
                // Already gone.
            }
        }
        pane = null;
        paneUi = null;
        unregisterTap();
        if (manager != null) {
            manager.stop();
            manager = null;
        }
        if (uiService != null)
            uiService.removeToolbarItem(toolbarItem);
    }

    /** Opens the pane: on the main screen, or on one report. */
    private void showPane(String reportId) {
        if (manager == null || mapView == null || uiService == null)
            return;
        if (pane == null) {
            paneUi = new SitRepPane(mapView, pluginContext, manager);
            pane = new PaneBuilder(paneUi.getView())
                    .setMetaValue(Pane.RELATIVE_LOCATION, Pane.Location.Default)
                    .setMetaValue(Pane.PREFERRED_WIDTH_RATIO, 0.5D)
                    .setMetaValue(Pane.PREFERRED_HEIGHT_RATIO, 0.5D)
                    .build();
        }
        if (reportId == null)
            paneUi.showMain();
        else
            paneUi.showReport(reportId);
        if (!uiService.isPaneVisible(pane))
            uiService.showPane(pane, null);
    }

    /**
     * A tap on a report's area opens that report in the pane, no radial (PLAN section
     * 11). Only our outlines are claimed; everyone else's items keep their radials.
     */
    private void registerTap() {
        tapOpensReport = new com.atakmap.android.menu.MapMenuEventListener() {
            @Override
            public boolean onShowMenu(MapItem item) {
                final String id = AreaOutlines.reportOf(item);
                if (id == null || manager == null || manager.find(id) == null)
                    return false;
                // A moment later: a pick from ATAK's Select Item list posts its own
                // show-details, which would close a page opened inline.
                mapView.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        showPane(id);
                    }
                }, 250);
                return true;
            }

            @Override
            public void onHideMenu(MapItem item) {
            }
        };
        final com.atakmap.android.menu.MapMenuReceiver menus =
                com.atakmap.android.menu.MapMenuReceiver.getInstance();
        if (menus != null)
            menus.addEventListener(tapOpensReport);
        else
            Log.w(TAG, "no radial menu receiver; taps on areas keep the radial");
    }

    private void unregisterTap() {
        if (tapOpensReport == null)
            return;
        final com.atakmap.android.menu.MapMenuReceiver menus =
                com.atakmap.android.menu.MapMenuReceiver.getInstance();
        if (menus != null)
            menus.removeEventListener(tapOpensReport);
        tapOpensReport = null;
    }
}

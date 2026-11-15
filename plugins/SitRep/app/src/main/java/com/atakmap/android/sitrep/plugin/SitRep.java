package com.atakmap.android.sitrep.plugin;

import android.content.Context;

import com.atak.plugins.impl.PluginContextProvider;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.sitrep.AreaOutlines;
import com.atakmap.android.sitrep.Report;
import com.atakmap.android.sitrep.SitRepManager;
import com.atakmap.android.sitrep.ui.SitRepDropDown;
import com.atakmap.android.sitrep.ui.SitRepPane;
import com.atakmap.coremap.log.Log;

import gov.tak.api.plugin.IPlugin;
import gov.tak.api.plugin.IServiceController;
import gov.tak.api.ui.IHostUIService;
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
    SitRepDropDown pane;
    SitRepPane paneUi;
    SitRepManager manager;
    MapView mapView;
    private com.atakmap.android.menu.MapMenuEventListener tapOpensReport;
    private com.atakmap.android.sitrep.PlaceRadial radial;
    public static final String SHOW = "com.atakmap.android.sitrep.SHOW";
    private android.content.BroadcastReceiver showReceiver;

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
        manager = new SitRepManager(mapView, pluginContext);
        manager.setListener(new SitRepManager.Listener() {
            @Override
            public void changed() {
                if (paneUi != null)
                    paneUi.refresh();
            }

            @Override
            public void reportStarted(final Report r) {
                showPane(r.id);
                // A new area goes in view as it loads, whichever way it was made
                // (operator, 2026-11-13: searched a city, the map stayed put). After
                // the pane has opened, so the fit leaves the pane's side clear.
                if (mapView != null)
                    mapView.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (paneUi != null)
                                paneUi.goToReport(r.id);
                        }
                    }, 400);
            }
        });
        manager.start();
        radial = new com.atakmap.android.sitrep.PlaceRadial(mapView, new com.atakmap.android.sitrep.PlaceRadial.Opener() {
            @Override
            public void openPlace(String reportId, String category, String key) {
                if (manager == null || reportId == null || manager.find(reportId) == null)
                    return;
                showPane(reportId);
                if (paneUi != null)
                    paneUi.showPlace(reportId, category, key);
            }
        });
        radial.register();
        registerTap();
        // Opens the pane from adb and from other plugins: am broadcast cannot reach a
        // process-local receiver, so this one is a system receiver.
        showReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context c, android.content.Intent i) {
                final String id = i.getStringExtra("report");
                if (mapView != null)
                    mapView.post(new Runnable() {
                        @Override
                        public void run() {
                            showPane(id != null && manager != null && manager.find(id) != null ? id : null);
                        }
                    });
            }
        };
        final com.atakmap.android.ipc.AtakBroadcast.DocumentedIntentFilter show =
                new com.atakmap.android.ipc.AtakBroadcast.DocumentedIntentFilter();
        show.addAction(SHOW, "open the Sit Rep pane, optionally on one report (extra: report)");
        com.atakmap.android.ipc.AtakBroadcast.getInstance().registerSystemReceiver(showReceiver, show);
        registerPreferences();
    }

    private static final String PREFS_KEY = "sitrep_preferences";

    /** The Tool Preferences entry: the only way to reach the user manual. */
    private void registerPreferences() {
        try {
            com.atakmap.app.preferences.ToolsPreferenceFragment.register(
                    new com.atakmap.app.preferences.ToolsPreferenceFragment.ToolPreference(
                            pluginContext.getString(R.string.app_name),
                            pluginContext.getString(R.string.prefs_summary),
                            PREFS_KEY,
                            pluginContext.getResources().getDrawable(R.drawable.ic_toolbar),
                            new SitRepPreferenceFragment(pluginContext)));
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

    @Override
    public void onStop() {
        // ATAK keeps a plugin's pane on screen across a reload, and a pane whose
        // buttons point at a stopped instance does nothing when tapped.
        if (pane != null) {
            try {
                pane.close();
                pane.dispose();
            } catch (Exception ignored) {
                // Already gone.
            }
        }
        pane = null;
        paneUi = null;
        unregisterTap();
        if (showReceiver != null) {
            try {
                com.atakmap.android.ipc.AtakBroadcast.getInstance().unregisterSystemReceiver(showReceiver);
            } catch (Exception ignored) {
                // Never registered.
            }
            showReceiver = null;
        }
        if (radial != null) {
            radial.unregister();
            radial = null;
        }
        if (manager != null) {
            manager.stop();
            manager = null;
        }
        unregisterPreferences();
        if (uiService != null)
            uiService.removeToolbarItem(toolbarItem);
    }

    /** Opens the pane: on the main screen, or on one report. */
    private void showPane(String reportId) {
        if (manager == null || mapView == null)
            return;
        if (pane == null) {
            paneUi = new SitRepPane(mapView, pluginContext, manager);
            pane = new SitRepDropDown(mapView, paneUi.getView());
            final SitRepDropDown host = pane;
            paneUi.setWideToggle(new Runnable() {
                @Override
                public void run() {
                    host.toggleWide();
                }
            });
        }
        if (reportId == null)
            paneUi.showMain();
        else
            paneUi.showReport(reportId);
        pane.show();
    }

    /**
     * A tap on a report's area opens that report in the pane, no radial (PLAN section
     * 11). Only our outlines are claimed; everyone else's items keep their radials.
     */
    private void registerTap() {
        tapOpensReport = new com.atakmap.android.menu.MapMenuEventListener() {
            @Override
            public boolean onShowMenu(MapItem item) {
                if (item != null && item.hasMetaValue("sitrep_key")) {
                    // A point place: ATAK opens its radial (PLAN section 11).
                    if (radial != null)
                        radial.opening(item);
                    return false;
                }
                if (item != null && item.getMetaBoolean("sitrep_place", false)) {
                    // A line or an installation's edge: its details, nothing to edit or send.
                    final String title = item.getMetaString("title", "Place");
                    final String details = item.getMetaString("sitrep_details", "");
                    mapView.post(new Runnable() {
                        @Override
                        public void run() {
                            new android.app.AlertDialog.Builder(mapView.getContext())
                                    .setTitle(title).setMessage(details)
                                    .setPositiveButton("OK", null).show();
                        }
                    });
                    return true;
                }
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

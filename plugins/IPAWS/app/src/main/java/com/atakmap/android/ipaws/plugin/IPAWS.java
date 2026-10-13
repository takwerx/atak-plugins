
package com.atakmap.android.ipaws.plugin;

import android.content.Context;
import android.content.Intent;

import com.atak.plugins.impl.PluginContextProvider;
import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.ipaws.AlertManager;
import com.atakmap.android.ipaws.ui.AlertDetails;
import com.atakmap.android.ipaws.ui.AlertDetailsReceiver;
import com.atakmap.android.ipaws.ui.IpawsPane;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;

import gov.tak.api.plugin.IPlugin;
import gov.tak.api.plugin.IServiceController;
import android.view.View;

import gov.tak.api.ui.IHostUIService;
import gov.tak.api.ui.Pane;
import gov.tak.api.ui.PaneBuilder;
import gov.tak.api.ui.ToolbarItem;
import gov.tak.api.ui.ToolbarItemAdapter;
import gov.tak.platform.marshal.MarshalManager;

/**
 * Public emergency alerts on the ATAK map, filtered on this device.
 *
 * <p>The manager lives for the plugin's life; the pane is only its controls. Nothing
 * here is a {@code Tool}, so nothing stops when the user switches base maps.
 */
public class IPAWS implements IPlugin {

    private static final String TAG = "IPAWS";

    AlertManager manager;
    IpawsPane paneUi;
    AlertDetails details;
    Pane detailPane;
    /** True while one detail pane is being replaced by another, so its close is ignored. */
    boolean swappingDetail;
    /** Whether the list was up when the details opened, so closing can restore it. */
    boolean listWasVisible;
    MapView mapView;
    private com.atakmap.android.menu.MapMenuEventListener tapOpensDetails;

    IServiceController serviceController;
    Context pluginContext;
    IHostUIService uiService;
    ToolbarItem toolbarItem;
    Pane templatePane;

    public IPAWS(IServiceController serviceController) {
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
                }).setIdentifier(pluginContext.getPackageName())
                .build();
    }

    private static final String PREFS_KEY = "ipawsPreference";

    /** The Tool Preferences entry the manual is reached through; a build without the class costs the manual, not the plugin. */
    private void registerPreferences() {
        try {
            com.atakmap.app.preferences.ToolsPreferenceFragment.register(
                    new com.atakmap.app.preferences.ToolsPreferenceFragment.ToolPreference(
                            pluginContext.getString(R.string.app_name),
                            pluginContext.getString(R.string.prefs_summary),
                            PREFS_KEY,
                            pluginContext.getResources().getDrawable(R.drawable.ic_toolbar),
                            new IpawsPreferenceFragment(pluginContext)));
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
    public void onStart() {
        if (uiService != null)
            uiService.addToolbarItem(toolbarItem);
        registerPreferences();
        mapView = MapView.getMapView();
        if (mapView != null && manager == null) {
            manager = new AlertManager(mapView, pluginContext);
            manager.start();
            // Registered here, not when the pane is first opened. Tapping an alert on
            // the map is a perfectly ordinary thing to do before ever opening the
            // pane, and while this lived in showPane() the radial's Details button
            // broadcast into a void -- no receiver, no log line, nothing at all.
            details = new AlertDetails(pluginContext, manager, detailHost());
            manager.setDetailsReceiver(
                    new AlertDetailsReceiver(mapView, manager, details));
            registerTap();
        } else if (mapView == null) {
            Log.w(TAG, "no map view at start; the overlay cannot be attached");
        }
    }

    @Override
    public void onStop() {
        // ATAK keeps a plugin's pane on screen across a reload, and a pane whose
        // buttons point at a stopped instance does nothing when tapped.
        if (templatePane != null && uiService != null) {
            try {
                if (uiService.isPaneVisible(templatePane))
                    uiService.closePane(templatePane);
            } catch (Exception ignored) {
                // Already gone, which is the state we wanted.
            }
            templatePane = null;
        }
        if (detailPane != null) {
            try {
                if (uiService != null && uiService.isPaneVisible(detailPane))
                    uiService.closePane(detailPane);
            } catch (Exception ignored) {
                // Already gone, which is the state we wanted.
            }
            detailPane = null;
        }
        unregisterTap();
        if (paneUi != null) {
            paneUi.dispose();
            paneUi = null;
        }
        if (details != null) {
            details.dispose();
            details = null;
        }
        if (manager != null) {
            manager.stop();
            manager = null;
        }
        if (uiService != null)
            uiService.removeToolbarItem(toolbarItem);
        unregisterPreferences();
    }

    /**
     * A tap on an alert's area opens its details in the pane, never ATAK's radial
     * (operator, 2026-10-09: "when you click an item it opens right to the side pane,
     * removes the radial menu and then having to click on details"). ATAK asks each
     * {@code MapMenuEventListener} before it opens a radial, and one that answers true
     * stops it and tells ATAK the tap was handled, so no callout is left on the map.
     * Only items carrying our overlay mark are claimed; everyone else's keep their
     * radial. The details go through the same receiver the radial's button used, so
     * an alert that expired since the map drew it still says so.
     *
     * <p>The alert's own radial stays on the item: if this listener cannot be
     * registered, a tap still reaches the details in two steps instead of none.
     * Atmosphere and AirAware answer taps the same way.
     */
    private void registerTap() {
        tapOpensDetails = new com.atakmap.android.menu.MapMenuEventListener() {
            @Override
            public boolean onShowMenu(final MapItem item) {
                if (item == null || item.getMetaString("ipaws_overlay", null) == null)
                    return false;
                final MapView mv = mapView;
                if (mv == null)
                    return false;
                // A moment later, not now: a pick from ATAK's Select Item list closes
                // the list and then posts its own show-details, which closed the page
                // opened here in Atmosphere and AirAware. A plain tap does not notice
                // the quarter second.
                mv.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            final Intent open = new Intent(AlertDetailsReceiver.ACTION);
                            final String id = item.getMetaString("ipaws_alert_id", null);
                            if (id != null)
                                open.putExtra(AlertDetailsReceiver.EXTRA_ALERT_ID, id);
                            else
                                open.putExtra("targetUID", item.getUID());
                            AtakBroadcast.getInstance().sendBroadcast(open);
                        } catch (RuntimeException e) {
                            Log.w(TAG, "tap to details", e);
                        }
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
            menus.addEventListener(tapOpensDetails);
        else
            Log.w(TAG, "no radial menu receiver; taps keep the alert's radial");
    }

    private void unregisterTap() {
        if (tapOpensDetails == null)
            return;
        final com.atakmap.android.menu.MapMenuReceiver menus =
                com.atakmap.android.menu.MapMenuReceiver.getInstance();
        if (menus != null)
            menus.removeEventListener(tapOpensDetails);
        tapOpensDetails = null;
    }

    /**
     * Somewhere to put an alert's details, and the thing that puts the list back.
     *
     * <p>Both panes live in the same slot, so opening an alert REPLACES the list
     * rather than sitting beside it. Closing the details therefore has to bring the
     * list back, or the plugin simply disappears -- which is exactly what the back key
     * did: it closed the details and left a bare map with no way back except the
     * toolbar. This listens for the pane CLOSING rather than doing it in the Back
     * button's handler, because the back key never goes through that button.
     */
    private AlertDetails.Host detailHost() {
        return new AlertDetails.Host() {
            @Override
            public void showDetailPane(View v) {
                if (uiService == null)
                    return;
                if (detailPane != null && uiService.isPaneVisible(detailPane)) {
                    swappingDetail = true;
                    uiService.closePane(detailPane);
                }
                final Pane opened = new PaneBuilder(v)
                        .setMetaValue(Pane.RELATIVE_LOCATION, Pane.Location.Default)
                        .setMetaValue(Pane.PREFERRED_WIDTH_RATIO, 0.5D)
                        .setMetaValue(Pane.PREFERRED_HEIGHT_RATIO, 0.5D)
                        .build();
                detailPane = opened;
                // Opened from the map with no pane up, closing it should return to the
                // map, not conjure a list the operator never asked for.
                listWasVisible = templatePane != null && uiService.isPaneVisible(templatePane);
                uiService.showPane(opened, new IHostUIService.IPaneLifecycleListener() {
                    @Override
                    public void onPaneVisible(boolean visible) {
                    }

                    @Override
                    public void onPaneClose() {
                        if (swappingDetail || detailPane != opened)
                            return;   // a newer alert took its place
                        if (listWasVisible && templatePane != null
                                && !uiService.isPaneVisible(templatePane))
                            uiService.showPane(templatePane, null);
                    }
                });
                swappingDetail = false;
            }

            @Override
            public void hideDetailPane() {
                if (uiService != null && detailPane != null
                        && uiService.isPaneVisible(detailPane))
                    uiService.closePane(detailPane);
            }
        };
    }

    private void showPane() {
        if (manager == null || mapView == null) {
            Log.w(TAG, "no manager yet; the pane has nothing to show");
            return;
        }
        if (templatePane == null) {
            paneUi = new IpawsPane(mapView, pluginContext, manager, manager.getCounties(), details);
            templatePane = new PaneBuilder(paneUi.getView())
                    .setMetaValue(Pane.RELATIVE_LOCATION, Pane.Location.Default)
                    .setMetaValue(Pane.PREFERRED_WIDTH_RATIO, 0.5D)
                    .setMetaValue(Pane.PREFERRED_HEIGHT_RATIO, 0.5D)
                    .build();
        }
        // Reopening shows the current state, including whatever arrived while it was
        // closed: the poll never stopped, only this view did.
        paneUi.refresh();
        if (!uiService.isPaneVisible(templatePane)) {
            uiService.showPane(templatePane, null);
        }
    }
}

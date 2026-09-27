
package com.atakmap.android.cursorwerx.plugin;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.view.View;
import android.widget.Button;

import com.atak.plugins.impl.PluginContextProvider;
import com.atakmap.android.cursorwerx.BackControl;
import com.atakmap.android.cursorwerx.ClickRepair;
import com.atakmap.android.cursorwerx.PanControl;
import com.atakmap.android.cursorwerx.PointerRouter;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;
import com.atak.plugins.impl.PluginLayoutInflater;

import gov.tak.api.plugin.IPlugin;
import gov.tak.api.plugin.IServiceController;
import gov.tak.api.ui.IHostUIService;
import gov.tak.api.ui.Pane;
import gov.tak.api.ui.PaneBuilder;
import gov.tak.api.ui.ToolbarItem;
import gov.tak.api.ui.ToolbarItemAdapter;
import gov.tak.platform.marshal.MarshalManager;

public class Cursorwerx implements IPlugin {

    private static final String TAG = "Cursorwerx";

    /** Our entry in ATAK's Tool Preferences. */
    private static final String PREFS_KEY = "cursorwerxPreferences";

    IServiceController serviceController;
    Context pluginContext;
    IHostUIService uiService;
    ToolbarItem toolbarItem;
    Pane templatePane;
    BackControl backControl;
    PointerRouter pointerRouter;
    PanControl panControl;
    ClickRepair clickRepair;
    /** The pane's "Wheel zoom: value" row; relabelled every time the pane is shown. */
    Button wheelZoomButton;

    public Cursorwerx(IServiceController serviceController) {
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

    @Override
    public void onStart() {
        // A Back control that works. ATAK's own faux nav bar cannot press Back on
        // Android 14; see BackControl for why and for the measurement.
        final MapView mapView = MapView.getMapView();
        if (mapView != null) {
            backControl = new BackControl(mapView);
            backControl.attach();

            // Stop the map zooming when the wheel is used over ATAK's own UI.
            pointerRouter = new PointerRouter(mapView);
            pointerRouter.setVerbose(BuildConfig.DEBUG); // debug builds log where each tick landed
            pointerRouter.attach();

            // Mouse drag pans the map; a click is handed back to ATAK.
            panControl = new PanControl(mapView);
            panControl.setVerbose(BuildConfig.DEBUG);
            panControl.attach();

            // A mouse click lands in text fields; see ClickRepair for the Android quirk.
            if (mapView.getContext() instanceof android.app.Activity) {
                clickRepair = new ClickRepair((android.app.Activity) mapView.getContext());
                clickRepair.setVerbose(BuildConfig.DEBUG);
                clickRepair.attach();
            }
        }

        registerPreferences();

        // the plugin is starting, add the button to the toolbar
        if (uiService == null)
            return;

        uiService.addToolbarItem(toolbarItem);
    }

    /**
     * Cursorwerx's entry in ATAK's Tool Preferences, holding the wheel zoom setting. A
     * failure is logged and the plugin runs on: the pane's row sets the same value.
     */
    private void registerPreferences() {
        try {
            com.atakmap.app.preferences.ToolsPreferenceFragment.register(
                    new com.atakmap.app.preferences.ToolsPreferenceFragment.ToolPreference(
                            pluginContext.getString(R.string.app_name),
                            pluginContext.getString(R.string.prefs_summary),
                            PREFS_KEY,
                            pluginContext.getResources().getDrawable(R.drawable.ic_toolbar),
                            new CursorwerxPreferenceFragment(pluginContext)));
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
        // Symmetric with onStart: a reload must leave no view and no listener behind.
        if (clickRepair != null) {
            clickRepair.detach();
            clickRepair = null;
        }
        if (panControl != null) {
            panControl.detach();
            panControl = null;
        }
        if (pointerRouter != null) {
            pointerRouter.detach();
            pointerRouter = null;
        }
        if (backControl != null) {
            backControl.detach();
            backControl = null;
        }
        unregisterPreferences();

        // the plugin is stopping, remove the button from the toolbar
        if (uiService == null)
            return;

        uiService.removeToolbarItem(toolbarItem);
    }

    private void showPane() {
        // instantiate the plugin view if necessary
        if(templatePane == null) {
            // Remember to use the PluginLayoutInflator if you are actually inflating a custom view
            // In this case, using it is not necessary - but I am putting it here to remind
            // developers to look at this Inflator

            final View view = PluginLayoutInflater.inflate(pluginContext,
                    R.layout.main_layout, null);
            wheelZoomButton = view.findViewById(R.id.wheel_zoom);
            if (wheelZoomButton != null)
                wheelZoomButton.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        chooseWheelZoom();
                    }
                });
            templatePane = new PaneBuilder(view)
                    // relative location is set to default; pane will switch location dependent on
                    // current orientation of device screen
                    .setMetaValue(Pane.RELATIVE_LOCATION, Pane.Location.Default)
                    // pane will take up 50% of screen width in landscape mode
                    .setMetaValue(Pane.PREFERRED_WIDTH_RATIO, 0.5D)
                    // pane will take up 50% of screen height in portrait mode
                    .setMetaValue(Pane.PREFERRED_HEIGHT_RATIO, 0.5D)
                    .build();
        }

        // Tool Preferences may have changed the value since the pane was last open.
        relabelWheelZoom();

        // if the plugin pane is not visible, show it!
        if(!uiService.isPaneVisible(templatePane)) {
            uiService.showPane(templatePane, null);
        }
    }

    /** ATAK's own preferences, where Tool Preferences keeps the same setting. */
    private static SharedPreferences atakPrefs() {
        final MapView mv = MapView.getMapView();
        return mv == null ? null
                : PreferenceManager.getDefaultSharedPreferences(mv.getContext());
    }

    /** Index of the stored step in the choice list; the default's when unset or unknown. */
    private int wheelZoomIndex() {
        final String[] values = pluginContext.getResources()
                .getStringArray(R.array.wheel_zoom_values);
        final SharedPreferences prefs = atakPrefs();
        final String stored = prefs == null ? PointerRouter.DEFAULT_WHEEL_ZOOM
                : prefs.getString(PointerRouter.PREF_WHEEL_ZOOM, PointerRouter.DEFAULT_WHEEL_ZOOM);
        int fallback = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(stored))
                return i;
            if (values[i].equals(PointerRouter.DEFAULT_WHEEL_ZOOM))
                fallback = i;
        }
        return fallback;
    }

    private void relabelWheelZoom() {
        if (wheelZoomButton == null)
            return;
        final String[] names = pluginContext.getResources()
                .getStringArray(R.array.wheel_zoom_names);
        wheelZoomButton.setText(pluginContext.getString(R.string.wheel_zoom_row,
                names[wheelZoomIndex()]));
    }

    /**
     * The choice as a single-choice dialog on the MapView context, never a Spinner and
     * never the plugin context (CLAUDE.md, plugin UI standard: either ends in a
     * BadTokenException that takes ATAK down).
     */
    private void chooseWheelZoom() {
        final MapView mv = MapView.getMapView();
        if (mv == null)
            return;
        final String[] entries = pluginContext.getResources()
                .getStringArray(R.array.wheel_zoom_entries);
        final String[] values = pluginContext.getResources()
                .getStringArray(R.array.wheel_zoom_values);
        new AlertDialog.Builder(mv.getContext())
                .setTitle(pluginContext.getString(R.string.wheel_zoom_dialog))
                .setSingleChoiceItems(entries, wheelZoomIndex(),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                final SharedPreferences prefs = atakPrefs();
                                if (prefs != null && which >= 0 && which < values.length)
                                    prefs.edit().putString(PointerRouter.PREF_WHEEL_ZOOM,
                                            values[which]).apply();
                                relabelWheelZoom();
                                dialog.dismiss();
                            }
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}

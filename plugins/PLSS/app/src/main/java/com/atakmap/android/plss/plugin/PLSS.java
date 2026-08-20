
package com.atakmap.android.plss.plugin;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.Button;

import com.atak.plugins.impl.PluginContextProvider;
import com.atak.plugins.impl.PluginLayoutInflater;

import com.atakmap.android.gui.ColorPalette;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.overlay.Overlay;
import com.atakmap.android.overlay.OverlayManager;
import com.atakmap.android.plss.PlssOverlay;
import com.atakmap.android.plss.graphics.GLPlssOverlay;
import com.atakmap.coremap.log.Log;
import com.atakmap.map.layer.opengl.GLLayerFactory;

import gov.tak.api.plugin.IPlugin;
import gov.tak.api.plugin.IServiceController;
import gov.tak.api.ui.IHostUIService;
import gov.tak.api.ui.Pane;
import gov.tak.api.ui.PaneBuilder;
import gov.tak.api.ui.ToolbarItem;
import gov.tak.api.ui.ToolbarItemAdapter;
import gov.tak.platform.marshal.MarshalManager;

public class PLSS implements IPlugin,
        OverlayManager.OnServiceListener,
        Overlay.OnVisibleChangedListener {

    private static final String TAG = "PLSS";

    /** The action gridlines uses to reach the shared Overlay Manager service. */
    private static final String OVERLAY_SHARED = "com.atakmap.android.overlay.SHARED";

    /** Row label in Overlay Manager, next to Grid Lines. */
    private static final String OVERLAY_NAME = "PLSS";

    /**
     * California first: it is the only state with an authoritative reference to
     * check against (the existing KMZ), so it is the pipeline checkpoint. See the
     * plan, decision 3.
     */
    private static final String PACK_STATE = "CA";

    IServiceController serviceController;
    Context pluginContext;
    IHostUIService uiService;
    ToolbarItem toolbarItem;
    Pane templatePane;

    PlssOverlay plssOverlay;

    /** the Overlay Manager row -- this is what carries the eyeball */
    OverlayManager overlayManager;
    Overlay overlayEntry;

    Button toggleButton;
    Button townshipColorButton;
    Button sectionColorButton;

    public PLSS(IServiceController serviceController) {
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
                        pluginContext.getResources().getDrawable(R.drawable.ic_launcher),
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
        // the SPI is what lets ATAK's renderer build a GLPlssOverlay when the
        // PlssOverlay layer is added to the map
        GLLayerFactory.register(GLPlssOverlay.SPI2);

        plssOverlay = new PlssOverlay(OVERLAY_NAME);
        plssOverlay.setVisible(false);

        // v0.1 reads a sideloaded per-state pack; managed download comes later
        // (PLAN-PLSS-v0.1.md section 5.4)
        if (!plssOverlay.openPack(PACK_STATE))
            Log.w(TAG, "no PLSS pack for " + PACK_STATE
                    + "; the overlay will draw nothing");

        final MapView mapView = MapView.getMapView();
        if (mapView != null) {
            // Added once and left in place; visibility is what toggles. This is
            // how GridLinesMapComponent does it, and it is what lets the Overlay
            // Manager eyeball drive the layer directly.
            mapView.addLayer(MapView.RenderStack.MAP_SURFACE_OVERLAYS,
                    plssOverlay);

            final Intent intent = new Intent();
            intent.setAction(OVERLAY_SHARED);
            OverlayManager.aquireService(mapView.getContext(), intent, this);
        } else {
            Log.w(TAG, "no MapView at startup; PLSS overlay not attached");
        }

        // the plugin is starting, add the button to the toolbar
        if (uiService == null)
            return;

        uiService.addToolbarItem(toolbarItem);
    }

    @Override
    public void onStop() {
        // symmetric teardown -- the plugin's classes live in ATAK's process, so
        // anything left registered here leaks into the host
        if (overlayEntry != null) {
            overlayEntry.removeOnVisibleChangedListener(this);
            overlayEntry = null;
        }
        if (overlayManager != null) {
            overlayManager.releaseService();
            overlayManager = null;
        }

        final MapView mapView = MapView.getMapView();
        if (mapView != null && plssOverlay != null)
            mapView.removeLayer(MapView.RenderStack.MAP_SURFACE_OVERLAYS,
                    plssOverlay);

        GLLayerFactory.unregister(GLPlssOverlay.SPI2);
        plssOverlay.closePack();
        plssOverlay = null;

        // the plugin is stopping, remove the button from the toolbar
        if (uiService == null)
            return;

        uiService.removeToolbarItem(toolbarItem);
    }

    // ---------------------------------------------------------------- overlay

    @Override
    public void onOverlayManagerBind(OverlayManager manager) {
        overlayManager = manager;

        overlayEntry = manager.registerOverlay(OVERLAY_NAME);
        overlayEntry.setFriendlyName(OVERLAY_NAME);
        // the icon lives in the plugin APK, so the authority is the plugin's
        // package -- ATAK's own package cannot resolve it
        overlayEntry.setIconUri("android.resource://"
                + pluginContext.getPackageName() + "/" + R.drawable.ic_launcher);
        overlayEntry.setVisible(isOverlayVisible());
        overlayEntry.addOnVisibleChangedListener(this);

        Log.d(TAG, "registered PLSS in Overlay Manager");
    }

    @Override
    public void onOverlayManagerUnbind(OverlayManager manager) {
        if (overlayEntry != null) {
            overlayEntry.removeOnVisibleChangedListener(this);
            overlayEntry = null;
        }
        overlayManager = null;
    }

    /** The Overlay Manager eyeball was tapped. */
    @Override
    public void onOverlayVisibleChanged(Overlay overlay) {
        setOverlayVisible(overlay.getVisible());
    }

    private boolean isOverlayVisible() {
        return plssOverlay != null && plssOverlay.isVisible();
    }

    /**
     * Single place that changes visibility, so the Overlay Manager row, the
     * layer and the pane cannot drift apart. Guarded against re-entry -- setting
     * the Overlay fires the listener that lands back here.
     */
    private void setOverlayVisible(boolean visible) {
        if (plssOverlay != null && plssOverlay.isVisible() != visible)
            plssOverlay.setVisible(visible);

        if (overlayEntry != null && overlayEntry.getVisible() != visible)
            overlayEntry.setVisible(visible);

        syncPane();
        Log.d(TAG, "PLSS overlay visible=" + visible);
    }

    // ------------------------------------------------------------------- pane

    private void showPane() {
        // instantiate the plugin view if necessary
        if(templatePane == null) {
            // Remember to use the PluginLayoutInflator if you are actually inflating a custom view
            // In this case, using it is not necessary - but I am putting it here to remind
            // developers to look at this Inflator
            final View root = PluginLayoutInflater.inflate(pluginContext,
                    R.layout.main_layout, null);
            bindPane(root);

            templatePane = new PaneBuilder(root)
                    // relative location is set to default; pane will switch location dependent on
                    // current orientation of device screen
                    .setMetaValue(Pane.RELATIVE_LOCATION, Pane.Location.Default)
                    // pane will take up 50% of screen width in landscape mode
                    .setMetaValue(Pane.PREFERRED_WIDTH_RATIO, 0.5D)
                    // pane will take up 50% of screen height in portrait mode
                    .setMetaValue(Pane.PREFERRED_HEIGHT_RATIO, 0.5D)
                    .build();
        }

        syncPane();

        // if the plugin pane is not visible, show it!
        if(!uiService.isPaneVisible(templatePane)) {
            uiService.showPane(templatePane, null);
        }
    }

    private void bindPane(View root) {
        toggleButton = root.findViewById(R.id.plss_toggle);
        townshipColorButton = root.findViewById(R.id.plss_township_color);
        sectionColorButton = root.findViewById(R.id.plss_section_color);

        toggleButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setOverlayVisible(!isOverlayVisible());
            }
        });

        townshipColorButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showColorPicker(true);
            }
        });

        sectionColorButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showColorPicker(false);
            }
        });
    }

    /** Keeps the pane's controls showing the overlay's actual state. */
    private void syncPane() {
        if (toggleButton != null)
            toggleButton.setText(pluginContext.getString(
                    isOverlayVisible() ? R.string.plss_hide : R.string.plss_show));

        if (plssOverlay == null)
            return;

        if (townshipColorButton != null)
            townshipColorButton.setBackgroundColor(
                    plssOverlay.getTownshipColor());

        if (sectionColorButton != null)
            sectionColorButton.setBackgroundColor(
                    plssOverlay.getSectionColor());
    }

    /**
     * ATAK's own colour palette. It inflates ATAK's resources, so it must be
     * built with ATAK's context -- the plugin context cannot see them.
     */
    private void showColorPicker(final boolean township) {
        final MapView mapView = MapView.getMapView();
        if (mapView == null || plssOverlay == null) {
            Log.w(TAG, "no MapView available; cannot show the colour picker");
            return;
        }

        final ColorPalette palette = new ColorPalette(mapView.getContext());
        palette.setColor(township ? plssOverlay.getTownshipColor()
                : plssOverlay.getSectionColor());

        final AlertDialog dialog = new AlertDialog.Builder(mapView.getContext())
                .setTitle(pluginContext.getString(township
                        ? R.string.plss_township_color_title
                        : R.string.plss_section_color_title))
                .setView(palette)
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        palette.setOnColorSelectedListener(
                new ColorPalette.OnColorSelectedListener() {
                    @Override
                    public void onColorSelected(int color, String label) {
                        if (township)
                            plssOverlay.setTownshipColor(color);
                        else
                            plssOverlay.setSectionColor(color);

                        syncPane();
                        dialog.dismiss();
                    }
                });

        dialog.show();
    }
}

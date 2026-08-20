
package com.atakmap.android.plss.plugin;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.atak.plugins.impl.PluginContextProvider;
import com.atak.plugins.impl.PluginLayoutInflater;

import com.atakmap.android.gui.ColorPalette;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.overlay.Overlay;
import com.atakmap.android.overlay.OverlayManager;
import com.atakmap.android.plss.PlssOverlay;
import com.atakmap.android.plss.PlssPackManager;
import com.atakmap.android.plss.graphics.GLPlssOverlay;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.map.layer.opengl.GLLayerFactory;

import java.io.File;
import java.util.List;

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



    IServiceController serviceController;
    Context pluginContext;
    IHostUIService uiService;
    ToolbarItem toolbarItem;
    Pane templatePane;

    PlssOverlay plssOverlay;

    /** the Overlay Manager row -- this is what carries the eyeball */
    OverlayManager overlayManager;
    Overlay overlayEntry;

    PlssPackManager packManager;

    Button toggleButton;
    Button townshipColorButton;
    Button sectionColorButton;
    Button labelColorButton;

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

        // The plugin ships bare; the operator downloads the states they work in
        // (PLAN-PLSS-v0.1.md section 5.4). Zero packs is a normal first run.
        packManager = new PlssPackManager(PlssOverlay.packDir());
        if (plssOverlay.openPacks() == 0)
            Log.d(TAG, "no PLSS packs installed yet");

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
        plssOverlay.closePacks();
        plssOverlay = null;

        if (packManager != null) {
            packManager.shutdown();
            packManager = null;
        }

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
        final Button findButton = root.findViewById(R.id.plss_find);
        findButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showTownshipLookup();
            }
        });

        final Button manageButton = root.findViewById(R.id.plss_manage);
        manageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDataManager();
            }
        });

        toggleButton = root.findViewById(R.id.plss_toggle);
        townshipColorButton = root.findViewById(R.id.plss_township_color);
        sectionColorButton = root.findViewById(R.id.plss_section_color);
        labelColorButton = root.findViewById(R.id.plss_label_color);

        toggleButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setOverlayVisible(!isOverlayVisible());
            }
        });

        townshipColorButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showColorPicker(TARGET_TOWNSHIP);
            }
        });

        sectionColorButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showColorPicker(TARGET_SECTION);
            }
        });

        labelColorButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showColorPicker(TARGET_LABEL);
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

        if (labelColorButton != null)
            labelColorButton.setBackgroundColor(plssOverlay.getLabelColor());
    }

    /**
     * Jumps the map to a township by meridian, township and range.
     *
     * The meridian is part of the key, not decoration: township and range
     * numbers restart at each principal meridian, so "T1N-R1W" exists under most
     * of them and they are hundreds of miles apart.
     */
    private void showTownshipLookup() {
        final MapView mapView = MapView.getMapView();
        if (mapView == null || plssOverlay == null)
            return;

        if (!plssOverlay.hasData()) {
            Toast.makeText(mapView.getContext(),
                    pluginContext.getString(R.string.plss_no_data),
                    Toast.LENGTH_LONG).show();
            return;
        }

        final View root = PluginLayoutInflater.inflate(pluginContext,
                R.layout.find_layout, null);

        final Button meridian = root.findViewById(R.id.find_meridian);
        final Button twpDir = root.findViewById(R.id.find_twp_dir);
        final Button rngDir = root.findViewById(R.id.find_rng_dir);
        final EditText twp = root.findViewById(R.id.find_twp);
        final EditText rng = root.findViewById(R.id.find_rng);
        final TextView status = root.findViewById(R.id.find_status);

        // Buttons that open a list dialog, rather than Spinners. A Spinner opens
        // its popup with the context that inflated it, and the plugin context is
        // not an Activity -- it has no window token, so the popup throws
        // BadTokenException. Every window here is built with ATAK's context.
        final Context ui = mapView.getContext();
        final List<String> meridians = plssOverlay.meridians();

        setChooser(ui, meridian, meridians,
                meridians.isEmpty() ? "" : meridians.get(0));
        setChooser(ui, twpDir, java.util.Arrays.asList("N", "S"), "N");
        setChooser(ui, rngDir, java.util.Arrays.asList("E", "W"), "E");

        final AlertDialog dialog = new AlertDialog.Builder(mapView.getContext())
                .setTitle(pluginContext.getString(R.string.plss_find_title))
                .setView(root)
                .setNegativeButton(android.R.string.cancel, null)
                // resolved here, not passed as an id: this dialog is built with
                // ATAK's context, which cannot see the plugin's resources
                .setPositiveButton(pluginContext.getString(R.string.plss_go),
                        (DialogInterface.OnClickListener) null)
                .create();

        dialog.show();

        // set the listener after show() so a failed lookup leaves the dialog up
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        final String t = twp.getText().toString().trim();
                        final String r = rng.getText().toString().trim();

                        if (t.isEmpty() || r.isEmpty()) {
                            status.setText("Enter a township and range");
                            return;
                        }

                        // matches the label built by tools/pack_plss.py
                        final String label = "T" + t
                                + twpDir.getText() + "-R" + r
                                + rngDir.getText();

                        final String pm = meridian.getText().toString();
                        final double[] box = plssOverlay.findTownship(pm,
                                label);

                        if (box == null) {
                            status.setText("No " + label + " in " + pm);
                            return;
                        }

                        goTo(mapView, box);
                        dialog.dismiss();
                    }
                });
    }

    /**
     * Turns a button into a picker: its text is the current value, tapping it
     * offers the list.
     */
    private void setChooser(final Context ui, final Button button,
            final List<String> items, String initial) {

        button.setText(initial);

        if (items.isEmpty()) {
            button.setEnabled(false);
            return;
        }

        final String[] arr = items.toArray(new String[0]);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(ui)
                        .setItems(arr, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                button.setText(arr[which]);
                            }
                        })
                        .show();
            }
        });
    }

    /** Centres the map on a bbox and zooms so it fits with a little margin. */
    private void goTo(MapView mapView, double[] box) {
        final double west = box[0], south = box[1], east = box[2],
                north = box[3];

        final double centreLat = (south + north) / 2.0;
        final double centreLon = (west + east) / 2.0;

        // metres per degree, good enough for framing
        final double mPerDegLat = 110540.0;
        final double mPerDegLon = 111320.0
                * Math.cos(Math.toRadians(centreLat));

        final double widthM = (east - west) * mPerDegLon;
        final double heightM = (north - south) * mPerDegLat;

        final int screenW = Math.max(mapView.getWidth(), 1);
        final int screenH = Math.max(mapView.getHeight(), 1);

        // 1.15 leaves the township clear of the screen edges
        final double resolution = Math.max(widthM / screenW,
                heightM / screenH) * 1.15;

        mapView.getMapController().panZoomTo(
                new GeoPoint(centreLat, centreLon),
                mapView.mapResolutionAsMapScale(resolution), true);
    }

    /**
     * Lists what is installed and what can be downloaded, and lets the operator
     * add or remove states. The plugin ships with no data at all, so this is the
     * first screen that matters on a fresh install.
     */
    private void showDataManager() {
        final MapView mapView = MapView.getMapView();
        if (mapView == null || packManager == null)
            return;

        final View root = PluginLayoutInflater.inflate(pluginContext,
                R.layout.data_layout, null);
        final TextView status = root.findViewById(R.id.pack_status);
        final ProgressBar progress = root.findViewById(R.id.pack_progress);
        final LinearLayout list = root.findViewById(R.id.pack_list);

        final AlertDialog dialog = new AlertDialog.Builder(mapView.getContext())
                .setTitle(pluginContext.getString(R.string.plss_data_title))
                .setView(root)
                .setPositiveButton(pluginContext.getString(R.string.plss_close),
                        (DialogInterface.OnClickListener) null)
                .create();

        status.setText(pluginContext.getString(R.string.plss_loading));
        showInstalled(list, status, progress, null);

        packManager.fetchManifest(new PlssPackManager.ManifestCallback() {
            @Override
            public void onManifest(List<PlssPackManager.Pack> packs,
                    String sourceDate) {
                status.setText("BLM source " + sourceDate);
                showInstalled(list, status, progress, packs);
            }

            @Override
            public void onError(String message) {
                status.setText("Could not reach the data server: " + message);
                showInstalled(list, status, progress, null);
            }
        });

        dialog.show();
    }

    /** Rebuilds both lists in place, so it can be called after every change. */
    private void showInstalled(final LinearLayout list, final TextView status,
            final ProgressBar progress,
            final List<PlssPackManager.Pack> available) {

        list.removeAllViews();

        final List<String> installed = PlssOverlay.installedStates();

        addHeader(list, pluginContext.getString(R.string.plss_installed));
        if (installed.isEmpty()) {
            addHeader(list, pluginContext.getString(R.string.plss_no_data));
        } else {
            for (final String state : installed) {
                final File f = new File(PlssOverlay.packDir(),
                        "plss_" + state + ".sqlite");
                addRow(list, state + String.format(" — %.1f MB",
                        f.length() / 1048576.0),
                        pluginContext.getString(R.string.plss_delete),
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                deletePack(state, list, status, progress,
                                        available);
                            }
                        });
            }
        }

        if (available == null)
            return;

        addHeader(list, pluginContext.getString(R.string.plss_available));
        for (final PlssPackManager.Pack pack : available) {
            if (installed.contains(pack.state))
                continue;

            addRow(list, pack.describe(),
                    pluginContext.getString(R.string.plss_download),
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            startDownload(pack, list, status, progress,
                                    available);
                        }
                    });
        }
    }

    private void addHeader(LinearLayout list, String text) {
        final TextView tv = new TextView(pluginContext);
        tv.setText(text);
        tv.setPadding(0, 14, 0, 4);
        list.addView(tv, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void addRow(LinearLayout list, String label, String action,
            View.OnClickListener onAction) {

        final View row = PluginLayoutInflater.inflate(pluginContext,
                R.layout.pack_row, null);
        ((TextView) row.findViewById(R.id.pack_label)).setText(label);

        final Button b = row.findViewById(R.id.pack_action);
        b.setText(action);
        b.setOnClickListener(onAction);

        list.addView(row);
    }

    private void startDownload(final PlssPackManager.Pack pack,
            final LinearLayout list, final TextView status,
            final ProgressBar progress,
            final List<PlssPackManager.Pack> available) {

        progress.setVisibility(View.VISIBLE);
        progress.setProgress(0);
        status.setText("Downloading " + pack.name + "…");

        packManager.download(pack, new PlssPackManager.DownloadCallback() {
            @Override
            public void onProgress(int percent, long soFar, long total) {
                if (percent >= 0)
                    progress.setProgress(percent);

                status.setText(String.format("Downloading %s — %.1f / %.1f MB",
                        pack.name, soFar / 1048576.0, total / 1048576.0));
            }

            @Override
            public void onComplete(PlssPackManager.Pack p, File installed) {
                progress.setVisibility(View.GONE);
                status.setText(p.name + " installed");

                // reopen so the new pack is queryable without a restart
                reloadPacks();
                showInstalled(list, status, progress, available);
            }

            @Override
            public void onError(String message) {
                progress.setVisibility(View.GONE);
                status.setText("Download failed: " + message);
            }
        });
    }

    private void deletePack(String state, LinearLayout list, TextView status,
            ProgressBar progress, List<PlssPackManager.Pack> available) {

        final File f = new File(PlssOverlay.packDir(),
                "plss_" + state + ".sqlite");

        // close first: the file is open by SQLite until the packs are reopened
        if (plssOverlay != null)
            plssOverlay.closePacks();

        final boolean gone = !f.exists() || f.delete();
        reloadPacks();

        status.setText(gone ? state + " deleted"
                : "Could not delete " + state);
        showInstalled(list, status, progress, available);
    }

    private void reloadPacks() {
        if (plssOverlay == null)
            return;

        plssOverlay.openPacks();

        // the renderer caches by bbox; drop it so the next frame re-queries
        plssOverlay.setVisible(plssOverlay.isVisible());
    }

    /**
     * ATAK's own colour palette. It inflates ATAK's resources, so it must be
     * built with ATAK's context -- the plugin context cannot see them.
     */
    private static final int TARGET_TOWNSHIP = 0;
    private static final int TARGET_SECTION = 1;
    private static final int TARGET_LABEL = 2;

    private void showColorPicker(final int target) {
        final MapView mapView = MapView.getMapView();
        if (mapView == null || plssOverlay == null) {
            Log.w(TAG, "no MapView available; cannot show the colour picker");
            return;
        }

        final int current;
        final int titleRes;
        switch (target) {
            case TARGET_TOWNSHIP:
                current = plssOverlay.getTownshipColor();
                titleRes = R.string.plss_township_color_title;
                break;
            case TARGET_LABEL:
                current = plssOverlay.getLabelColor();
                titleRes = R.string.plss_label_color_title;
                break;
            default:
                current = plssOverlay.getSectionColor();
                titleRes = R.string.plss_section_color_title;
                break;
        }

        final ColorPalette palette = new ColorPalette(mapView.getContext());
        palette.setColor(current);

        final AlertDialog dialog = new AlertDialog.Builder(mapView.getContext())
                .setTitle(pluginContext.getString(titleRes))
                .setView(palette)
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        palette.setOnColorSelectedListener(
                new ColorPalette.OnColorSelectedListener() {
                    @Override
                    public void onColorSelected(int color, String label) {
                        switch (target) {
                            case TARGET_TOWNSHIP:
                                plssOverlay.setTownshipColor(color);
                                break;
                            case TARGET_LABEL:
                                plssOverlay.setLabelColor(color);
                                break;
                            default:
                                plssOverlay.setSectionColor(color);
                                break;
                        }

                        syncPane();
                        dialog.dismiss();
                    }
                });

        dialog.show();
    }
}

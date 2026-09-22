package com.atakmap.android.atmosphere.plugin;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.view.View;

import com.atak.plugins.impl.PluginContextProvider;
import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.atmosphere.compat.MapCompat;
import com.atakmap.android.atmosphere.data.SnapshotStore;
import com.atakmap.android.atmosphere.data.WeatherClient;
import com.atakmap.android.atmosphere.net.EgressPolicy;
import com.atakmap.android.atmosphere.source.SourceRegistry;
import com.atakmap.android.atmosphere.ui.AtmospherePane;
import com.atakmap.coremap.log.Log;

import java.io.File;

import gov.tak.api.plugin.IPlugin;
import gov.tak.api.plugin.IServiceController;
import gov.tak.api.ui.IHostUIService;
import gov.tak.api.ui.Pane;
import gov.tak.api.ui.PaneBuilder;
import gov.tak.api.ui.ToolbarItem;
import gov.tak.api.ui.ToolbarItemAdapter;
import gov.tak.platform.marshal.MarshalManager;

/**
 * Plugin lifecycle only: the toolbar item and the pane.
 *
 * <p>The weather work lives in {@link AtmospherePane} and below it. Building the registry
 * and the cache is deferred until the pane is first opened, because both need ATAK's own
 * context and it is not guaranteed to exist while the plugin is being constructed.
 */
public class Atmosphere implements IPlugin {

    private static final String TAG = "Atmosphere";

    IServiceController serviceController;
    Context pluginContext;
    IHostUIService uiService;
    ToolbarItem toolbarItem;
    Pane pane;

    private AtmospherePane atmospherePane;

    public Atmosphere(IServiceController serviceController) {
        this.serviceController = serviceController;
        final PluginContextProvider ctxProvider = serviceController
                .getService(PluginContextProvider.class);
        if (ctxProvider != null) {
            pluginContext = ctxProvider.getPluginContext();
            pluginContext.setTheme(R.style.ATAKPluginTheme);
        }

        uiService = serviceController.getService(IHostUIService.class);

        // Set the identifier so the toolbar can find the button again after the operator
        // moves it.
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
        if (uiService == null)
            return;
        uiService.addToolbarItem(toolbarItem);
    }

    @Override
    public void onStop() {
        if (uiService == null)
            return;
        uiService.removeToolbarItem(toolbarItem);
    }

    private void showPane() {
        if (pane == null) {
            final View view = PluginLayoutInflater.inflate(pluginContext,
                    R.layout.main_layout, null);

            final EgressPolicy egress = new EgressPolicy(pluginVersion());
            final SourceRegistry registry = new SourceRegistry(pluginContext,
                    externalSourceDir());
            final WeatherClient client = new WeatherClient(egress,
                    new SnapshotStore(cacheDir()));

            atmospherePane = new AtmospherePane(view, pluginContext, registry, egress, client);

            pane = new PaneBuilder(view)
                    .setMetaValue(Pane.RELATIVE_LOCATION, Pane.Location.Default)
                    .setMetaValue(Pane.PREFERRED_WIDTH_RATIO, 0.5D)
                    .setMetaValue(Pane.PREFERRED_HEIGHT_RATIO, 0.5D)
                    .build();
        }

        if (!uiService.isPaneVisible(pane)) {
            uiService.showPane(pane, null);
            atmospherePane.onShown();
        }
    }

    /** Where the operator drops their own source definitions. */
    private static File externalSourceDir() {
        return new File(Environment.getExternalStorageDirectory(),
                SourceRegistry.EXTERNAL_DIR);
    }

    /**
     * Cached responses go in ATAK's private storage, not on the SD card: the cache
     * records which places have been looked at.
     */
    private File cacheDir() {
        final Context atak = MapCompat.atakContext();
        final File base = atak != null ? atak.getFilesDir()
                : pluginContext.getFilesDir();
        return new File(base, "weather-cache");
    }

    /** Short version for the User-Agent, e.g. "0.1" out of "0.1 (abc1234) - [5.7.0]". */
    private String pluginVersion() {
        try {
            final PackageManager pm = pluginContext.getPackageManager();
            final String name = pm.getPackageInfo(pluginContext.getPackageName(), 0)
                    .versionName;
            if (name == null || name.trim().isEmpty())
                return "dev";
            return name.trim().split(" ")[0];
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "cannot read plugin version", e);
            return "dev";
        }
    }
}

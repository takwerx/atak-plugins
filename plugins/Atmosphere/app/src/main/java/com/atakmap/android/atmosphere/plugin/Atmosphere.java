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
import com.atakmap.android.atmosphere.ui.AtmosphereDropDown;
import com.atakmap.android.atmosphere.ui.AtmospherePane;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;

import java.io.File;

import gov.tak.api.plugin.IPlugin;
import gov.tak.api.plugin.IServiceController;
import gov.tak.api.ui.IHostUIService;
import gov.tak.api.ui.ToolbarItem;
import gov.tak.api.ui.ToolbarItemAdapter;
import gov.tak.platform.marshal.MarshalManager;

/**
 * Plugin lifecycle only: the toolbar item and the drop-down that hosts the pane.
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
    private AtmosphereDropDown dropDown;
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
        if (uiService == null)
            return;
        uiService.addToolbarItem(toolbarItem);
    }

    @Override
    public void onStop() {
        if (uiService != null)
            uiService.removeToolbarItem(toolbarItem);
        if (dropDown != null) {
            dropDown.dispose();
            dropDown = null;
            atmospherePane = null;
        }
    }

    private void showPane() {
        if (dropDown == null) {
            final MapView mapView = MapView.getMapView();
            if (mapView == null) {
                Log.w(TAG, "no MapView yet; cannot open the pane");
                return;
            }
            final View view = PluginLayoutInflater.inflate(pluginContext,
                    R.layout.main_layout, null);
            final EgressPolicy egress = new EgressPolicy(pluginVersion());
            final SourceRegistry registry = new SourceRegistry(pluginContext,
                    externalSourceDir());
            final WeatherClient client = new WeatherClient(egress,
                    new SnapshotStore(cacheDir()));
            atmospherePane = new AtmospherePane(view, pluginContext, registry, egress, client);
            dropDown = new AtmosphereDropDown(mapView, view, atmospherePane);
            atmospherePane.setHost(dropDown);
        }
        dropDown.show();
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

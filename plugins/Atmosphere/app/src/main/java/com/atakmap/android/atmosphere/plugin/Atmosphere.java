package com.atakmap.android.atmosphere.plugin;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.view.View;

import com.atak.plugins.impl.PluginContextProvider;
import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.atmosphere.compat.MapCompat;
import com.atakmap.android.atmosphere.data.SnapshotStore;
import com.atakmap.android.atmosphere.data.WeatherClient;
import com.atakmap.android.atmosphere.net.EgressPolicy;
import com.atakmap.android.atmosphere.overlay.AirQualityOverlay;
import com.atakmap.android.atmosphere.overlay.SpotOverlay;
import com.atakmap.android.atmosphere.overlay.BuoyOverlay;
import com.atakmap.android.atmosphere.overlay.GaugeOverlay;
import com.atakmap.android.atmosphere.overlay.StationOverlay;
import com.atakmap.android.atmosphere.overlay.WarningsOverlay;
import com.atakmap.android.atmosphere.overlay.RadarOverlay;
import com.atakmap.android.atmosphere.overlay.SmokeOverlay;
import com.atakmap.android.atmosphere.overlay.TropicalOverlay;
import com.atakmap.android.atmosphere.ui.StormDetailsReceiver;
import com.atakmap.android.atmosphere.overlay.WindOverlay;
import com.atakmap.android.atmosphere.source.SourceRegistry;
import com.atakmap.android.atmosphere.ui.AtmosphereDropDown;
import com.atakmap.android.atmosphere.ui.AtmospherePane;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.ipc.AtakBroadcast.DocumentedIntentFilter;
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

    /**
     * Opens the pane from outside: another plugin (MAST's tool tiles), a hotkey, or
     * a test over adb. A system broadcast, because ATAK's own registerReceiver is
     * process-local and unreachable from anything but ATAK itself. It opens a
     * pane and nothing else.
     */
    public static final String ACTION_SHOW = "com.atakmap.android.atmosphere.SHOW";

    private final BroadcastReceiver showReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            showPane();
        }
    };

    IServiceController serviceController;
    Context pluginContext;
    IHostUIService uiService;
    ToolbarItem toolbarItem;
    private AtmosphereDropDown dropDown;
    private AtmospherePane atmospherePane;
    /** One egress policy for the pane and the overlays: the single choke point. */
    private EgressPolicy egress;
    /** Outlives the pane: the radar stays up while the pane is closed. */
    private RadarOverlay radar;
    private TropicalOverlay tropical;
    private StormDetailsReceiver stormDetails;
    private WindOverlay wind;
    private SmokeOverlay smoke;
    private AirQualityOverlay air;
    private WarningsOverlay warnings;
    private SpotOverlay spotLayer;
    private StationOverlay stations;
    private GaugeOverlay gauges;
    private BuoyOverlay buoys;

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
        startOverlays();
        AtakBroadcast.getInstance().registerSystemReceiver(showReceiver,
                new DocumentedIntentFilter(ACTION_SHOW, "Open the Atmosphere pane"));
    }

    /** The overlays need the map; it exists by onStart, and showPane retries if not. */
    private void startOverlays() {
        if (radar != null)
            return;
        final MapView mapView = MapView.getMapView();
        if (mapView == null)
            return;
        if (egress == null)
            egress = new EgressPolicy(pluginVersion());
        radar = new RadarOverlay(mapView, egress);
        radar.start();
        wind = new WindOverlay(mapView, egress);
        wind.start();
        smoke = new SmokeOverlay(mapView, egress);
        smoke.start();
        air = new AirQualityOverlay(mapView, pluginContext, egress);
        air.start();
        warnings = new WarningsOverlay(mapView, pluginContext, egress);
        warnings.start();
        spotLayer = new SpotOverlay(mapView, pluginContext, egress);
        spotLayer.start();
        stations = new StationOverlay(mapView, pluginContext, egress);
        stations.start();
        gauges = new GaugeOverlay(mapView, pluginContext, egress);
        gauges.start();
        buoys = new BuoyOverlay(mapView, pluginContext, egress);
        buoys.start();
        tropical = new TropicalOverlay(mapView, pluginContext, egress);
        tropical.start();
        if (stormDetails == null) {
            stormDetails = new StormDetailsReceiver(mapView, pluginContext);
            final DocumentedIntentFilter f = new DocumentedIntentFilter();
            f.addAction(StormDetailsReceiver.ACTION,
                    "show what the advisory says about a tapped storm feature");
            AtakBroadcast.getInstance().registerReceiver(stormDetails, f);
        }
        // A spot tapped on the map opens its forecast, not its own fields. Registered
        // whether or not the pane exists yet: the overlays start with the plugin and
        // the pane is not built until it is first shown, so anything set inside that
        // check is set only on the paths where the pane already happens to be there.
        com.atakmap.android.atmosphere.ui.StormDetailsReceiver.setSpotOpener(
                new com.atakmap.android.atmosphere.ui.StormDetailsReceiver.SpotOpener() {
                    @Override
                    public void openSpot(String spotId) {
                        if (atmospherePane != null)
                            atmospherePane.openSpot(spotId);
                    }

                    @Override
                    public void openGauge(String lid) {
                        if (atmospherePane == null)
                            showPane();
                        if (atmospherePane != null)
                            atmospherePane.openGauge(lid);
                    }

                    @Override
                    public void openBuoy(String id) {
                        if (atmospherePane == null)
                            showPane();
                        if (atmospherePane != null)
                            atmospherePane.openBuoy(id);
                    }
                });
        if (atmospherePane != null) {
            atmospherePane.setRadar(radar);
            atmospherePane.setWind(wind);
            atmospherePane.setSmoke(smoke);
            atmospherePane.setAirQuality(air);
            atmospherePane.setWarnings(warnings);
            atmospherePane.setSpotLayer(spotLayer);
            atmospherePane.setStations(stations);
            atmospherePane.setGauges(gauges);
            atmospherePane.setBuoys(buoys);
            atmospherePane.setTropical(tropical);
        }
    }

    @Override
    public void onStop() {
        if (uiService != null)
            uiService.removeToolbarItem(toolbarItem);
        try {
            AtakBroadcast.getInstance().unregisterSystemReceiver(showReceiver);
        } catch (RuntimeException e) {
            Log.w(TAG, "show receiver was not registered", e);
        }
        if (stormDetails != null) {
            try {
                AtakBroadcast.getInstance().unregisterReceiver(stormDetails);
            } catch (RuntimeException e) {
                Log.w(TAG, "storm details receiver was not registered", e);
            }
            stormDetails.dispose();
            stormDetails = null;
        }
        if (buoys != null) {
            buoys.stop();
            buoys = null;
        }
        if (gauges != null) {
            gauges.stop();
            gauges = null;
        }
        if (tropical != null) {
            tropical.stop();
            tropical = null;
        }
        if (spotLayer != null) {
            spotLayer.stop();
        if (stations != null)
            stations.stop();
            spotLayer = null;
        }
        if (warnings != null) {
            warnings.stop();
            warnings = null;
        }
        if (air != null) {
            air.stop();
            air = null;
        }
        if (smoke != null) {
            smoke.stop();
            smoke = null;
        }
        if (wind != null) {
            wind.stop();
            wind = null;
        }
        if (radar != null) {
            radar.stop();
            radar = null;
        }
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
            if (egress == null)
                egress = new EgressPolicy(pluginVersion());
            final SourceRegistry registry = new SourceRegistry(pluginContext,
                    externalSourceDir());
            final WeatherClient client = new WeatherClient(egress,
                    new SnapshotStore(cacheDir()));
            atmospherePane = new AtmospherePane(view, pluginContext, registry, egress, client);
            dropDown = new AtmosphereDropDown(mapView, view, atmospherePane);
            atmospherePane.setHost(dropDown);
            startOverlays();
            if (radar != null)
                atmospherePane.setRadar(radar);
            if (wind != null)
                atmospherePane.setWind(wind);
            if (smoke != null)
                atmospherePane.setSmoke(smoke);
            if (air != null)
                atmospherePane.setAirQuality(air);
            if (warnings != null)
                atmospherePane.setWarnings(warnings);
            if (spotLayer != null)
                atmospherePane.setSpotLayer(spotLayer);
            if (stations != null)
                atmospherePane.setStations(stations);
            if (gauges != null)
                atmospherePane.setGauges(gauges);
            if (buoys != null)
                atmospherePane.setBuoys(buoys);
            if (tropical != null)
                atmospherePane.setTropical(tropical);
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

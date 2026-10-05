package com.atakmap.android.atmosphere.plugin;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.view.View;

import com.atak.plugins.impl.PluginContextProvider;
import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.atmosphere.compat.GeneratedFiles;
import com.atakmap.android.atmosphere.compat.MapCompat;
import com.atakmap.android.atmosphere.data.SnapshotStore;
import com.atakmap.android.atmosphere.data.WeatherClient;
import com.atakmap.android.atmosphere.net.EgressPolicy;
import com.atakmap.android.atmosphere.overlay.AirQualityOverlay;
import com.atakmap.android.atmosphere.overlay.SpotOverlay;
import com.atakmap.android.atmosphere.overlay.BuoyOverlay;
import com.atakmap.android.atmosphere.overlay.FireZoneOverlay;
import com.atakmap.android.atmosphere.overlay.GaugeOverlay;
import com.atakmap.android.atmosphere.overlay.StationOverlay;
import com.atakmap.android.atmosphere.overlay.AvalancheOverlay;
import com.atakmap.android.atmosphere.overlay.HighFlowOverlay;
import com.atakmap.android.atmosphere.overlay.FloodedGroundOverlay;
import com.atakmap.android.atmosphere.overlay.SatelliteOverlay;
import com.atakmap.android.atmosphere.overlay.WaveOverlay;
import com.atakmap.android.atmosphere.overlay.RainOverlay;
import com.atakmap.android.atmosphere.overlay.BeachOverlay;
import com.atakmap.android.atmosphere.overlay.SnotelOverlay;
import com.atakmap.android.atmosphere.overlay.SnowOverlay;
import com.atakmap.android.atmosphere.overlay.SstOverlay;
import com.atakmap.android.atmosphere.overlay.FireWxOutlookOverlay;
import com.atakmap.android.atmosphere.overlay.SawtiOverlay;
import com.atakmap.android.atmosphere.overlay.LightningOverlay;
import com.atakmap.android.atmosphere.overlay.PspsOverlay;
import com.atakmap.android.atmosphere.overlay.FloodOutlookOverlay;
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
    private AvalancheOverlay avalanche;
    private FireZoneOverlay fireZones;
    /**
     * A tap on anything Atmosphere draws opens what it is, never ATAK's radial
     * (takwerx/atmosphere#1; operator, 2026-09-29: "make all those just go to the
     * page i dont need a radial menu really on any of that stuff ... if i wanted to
     * i could just use bloodhound"). An item with a page of its own opens the page;
     * the rest open the details the radial's first button used to. ATAK asks these
     * listeners before it opens a radial, and one that answers true stops it; items
     * that are not ours keep theirs.
     */
    private final com.atakmap.android.menu.MapMenuEventListener tapOpensPage =
            new com.atakmap.android.menu.MapMenuEventListener() {
                @Override
                public boolean onShowMenu(com.atakmap.android.maps.MapItem item) {
                    if (item == null || !item.getMetaBoolean("atmosphere", false))
                        return false;
                    try {
                        if (com.atakmap.android.atmosphere.ui.StormDetailsReceiver
                                .openPage(item))
                            return true;
                        final android.content.Intent details = new android.content.Intent(
                                com.atakmap.android.atmosphere.ui.StormDetailsReceiver.ACTION);
                        details.putExtra("targetUID", item.getUID());
                        AtakBroadcast.getInstance().sendBroadcast(details);
                        return true;
                    } catch (RuntimeException e) {
                        Log.w(TAG, "tap to page", e);
                        return false;
                    }
                }

                @Override
                public void onHideMenu(com.atakmap.android.maps.MapItem item) {
                }
            };
    private FireWxOutlookOverlay firewx;
    private SawtiOverlay sawti;
    private LightningOverlay lightning;
    private PspsOverlay psps;
    private FloodOutlookOverlay flood;
    private BeachOverlay beach;
    private HighFlowOverlay highflow;
    private FloodedGroundOverlay floodground;
    private SnowOverlay snow;
    private SatelliteOverlay satellite;
    private SstOverlay sst;
    private SnotelOverlay snotel;
    private StormDetailsReceiver stormDetails;
    private WindOverlay wind;
    private SmokeOverlay smoke;
    private WaveOverlay waves;
    private RainOverlay rain;
    private AirQualityOverlay air;
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

    /** The Tool Preferences row, and the manual behind it. */
    private static final String PREFS_KEY = "atmospherePreference";

    private void registerPreferences() {
        try {
            com.atakmap.app.preferences.ToolsPreferenceFragment.register(
                    new com.atakmap.app.preferences.ToolsPreferenceFragment.ToolPreference(
                            pluginContext.getString(R.string.app_name),
                            pluginContext.getString(R.string.prefs_summary),
                            PREFS_KEY,
                            pluginContext.getResources().getDrawable(R.drawable.ic_toolbar),
                            new AtmospherePreferenceFragment(pluginContext)));
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
        if (uiService == null)
            return;
        uiService.addToolbarItem(toolbarItem);
        registerPreferences();
        startOverlays();
        AtakBroadcast.getInstance().registerSystemReceiver(showReceiver,
                new DocumentedIntentFilter(ACTION_SHOW, "Open the Atmosphere pane"));
    }

    /**
     * The warnings layer was removed on 2026-09-26: alerts are IPAWS's, the
     * companion plugin, which draws the same feed and sends notifications. A phone
     * that ran it holds 600 zone shapes (28 MB) and a store it no longer reads.
     * The symbol folders that 0.9 and earlier composed on the card go the same way.
     * Swept once, on a daemon thread, never on the load thread.
     */
    private static void sweepRemovedLayerFiles() {
        final Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final java.io.File root = com.atakmap.coremap.filesystem.FileSystemUtils
                            .getItem("tools/atmosphere");
                    if (root == null)
                        return;
                    deleteTree(new java.io.File(root, "zones"));
                    for (String f : new String[] { "warnings.sqlite", "warnings.sqlite-journal",
                            "warnings.sqlite-wal", "warnings.sqlite-shm" })
                        //noinspection ResultOfMethodCallIgnored
                        new java.io.File(root, f).delete();
                    // The icons moved to ATAK's private storage in 0.10 (takwerx/atmosphere#2).
                    // By name only: the stores and the manual beside them stay.
                    for (String f : GeneratedFiles.LEGACY)
                        deleteTree(new java.io.File(root, f));
                    final String[] left = root.list();
                    if (left != null)
                        for (String f : left)
                            if (f.startsWith(GeneratedFiles.LEGACY_SET_ASIDE))
                                deleteTree(new java.io.File(root, f));
                } catch (Exception e) {
                    Log.w(TAG, "could not sweep the removed warnings layer's files", e);
                }
            }
        }, "atmosphere-sweep");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static void deleteTree(java.io.File f) {
        if (f == null || !f.exists())
            return;
        final java.io.File[] kids = f.listFiles();
        if (kids != null)
            for (java.io.File k : kids)
                deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
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
        sweepRemovedLayerFiles();
        radar = new RadarOverlay(mapView, egress);
        radar.start();
        wind = new WindOverlay(mapView, egress);
        wind.start();
        smoke = new SmokeOverlay(mapView, egress);
        smoke.start();
        waves = new WaveOverlay(mapView, egress);
        waves.start();
        rain = new RainOverlay(mapView, egress);
        rain.start();
        air = new AirQualityOverlay(mapView, pluginContext, egress);
        air.start();
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
        avalanche = new AvalancheOverlay(mapView, pluginContext, egress);
        avalanche.start();
        fireZones = new FireZoneOverlay(mapView, pluginContext, egress);
        fireZones.start();
        firewx = new FireWxOutlookOverlay(mapView, pluginContext, egress);
        firewx.start();
        sawti = new SawtiOverlay(mapView, pluginContext, egress);
        sawti.start();
        lightning = new LightningOverlay(mapView, egress);
        lightning.start();
        psps = new PspsOverlay(mapView, pluginContext, egress);
        psps.start();
        flood = new FloodOutlookOverlay(mapView, pluginContext, egress);
        flood.start();
        beach = new BeachOverlay(mapView, pluginContext, egress);
        beach.start();
        floodground = new FloodedGroundOverlay(mapView, egress);
        floodground.start();
        highflow = new HighFlowOverlay(mapView, pluginContext, egress);
        highflow.start();
        snow = new SnowOverlay(mapView, egress);
        snow.start();
        satellite = new SatelliteOverlay(mapView, egress);
        satellite.start();
        sst = new SstOverlay(mapView, egress);
        sst.start();
        snotel = new SnotelOverlay(mapView, pluginContext, egress);
        snotel.start();
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
        final com.atakmap.android.menu.MapMenuReceiver menus =
                com.atakmap.android.menu.MapMenuReceiver.getInstance();
        if (menus != null)
            menus.addEventListener(tapOpensPage);
        else
            Log.w(TAG, "no radial menu receiver; taps keep the radial");
        com.atakmap.android.atmosphere.ui.StormDetailsReceiver.setSpotOpener(
                new com.atakmap.android.atmosphere.ui.StormDetailsReceiver.SpotOpener() {
                    @Override
                    public void openSpot(String spotId) {
                        if (atmospherePane == null)
                            showPane();
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
                    public void openZone(String id, String name, String cwa) {
                        if (atmospherePane == null)
                            showPane();
                        if (atmospherePane != null)
                            atmospherePane.openZone(id, name, cwa);
                    }

                    @Override
                    public void openSawti(String ref) {
                        if (atmospherePane == null)
                            showPane();
                        if (atmospherePane != null)
                            atmospherePane.openSawti(ref);
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
            if (waves != null)
                atmospherePane.setWaves(waves);
            if (rain != null)
                atmospherePane.setRain(rain);
            atmospherePane.setAirQuality(air);
            if (avalanche != null)
                atmospherePane.setAvalanche(avalanche);
            if (fireZones != null)
                atmospherePane.setFireZones(fireZones);
            if (firewx != null)
                atmospherePane.setFireWx(firewx);
            if (sawti != null)
                atmospherePane.setSawti(sawti);
            if (lightning != null)
                atmospherePane.setLightning(lightning);
            if (psps != null)
                atmospherePane.setPsps(psps);
            if (flood != null)
                atmospherePane.setFlood(flood);
            if (beach != null)
                atmospherePane.setBeach(beach);
            if (floodground != null)
                atmospherePane.setFloodedGround(floodground);
            if (highflow != null)
                atmospherePane.setHighFlow(highflow);
            if (snow != null)
                atmospherePane.setSnow(snow);
            if (satellite != null)
                atmospherePane.setSatellite(satellite);
            if (sst != null)
                atmospherePane.setSst(sst);
            if (snotel != null)
                atmospherePane.setSnotel(snotel);
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
        unregisterPreferences();
        try {
            AtakBroadcast.getInstance().unregisterSystemReceiver(showReceiver);
        } catch (RuntimeException e) {
            Log.w(TAG, "show receiver was not registered", e);
        }
        final com.atakmap.android.menu.MapMenuReceiver menus =
                com.atakmap.android.menu.MapMenuReceiver.getInstance();
        if (menus != null)
            menus.removeEventListener(tapOpensPage);
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
        if (snotel != null) {
            snotel.stop();
            snotel = null;
        }
        if (sst != null) {
            sst.stop();
            sst = null;
        }
        if (highflow != null) {
            highflow.stop();
            highflow = null;
        }
        if (floodground != null) {
            floodground.stop();
            floodground = null;
        }
        if (satellite != null) {
            satellite.stop();
            satellite = null;
        }
        if (snow != null) {
            snow.stop();
            snow = null;
        }
        if (beach != null) {
            beach.stop();
            beach = null;
        }
        if (flood != null) {
            flood.stop();
            flood = null;
        }
        if (psps != null) {
            psps.stop();
            psps = null;
        }
        if (lightning != null) {
            lightning.stop();
            lightning = null;
        }
        if (sawti != null) {
            sawti.stop();
            sawti = null;
        }
        if (firewx != null) {
            firewx.stop();
            firewx = null;
        }
        if (fireZones != null) {
            fireZones.stop();
            fireZones = null;
        }
        if (avalanche != null) {
            avalanche.stop();
            avalanche = null;
        }
        if (air != null) {
            air.stop();
            air = null;
        }
        if (waves != null) {
            waves.stop();
            waves = null;
        }
        if (rain != null) {
            rain.stop();
            rain = null;
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
        // Static pools are per generation; a generation that is unloaded shuts its
        // own down, or its threads keep the whole generation in memory.
        com.atakmap.android.atmosphere.overlay.OverlayPools.shutdown();
        com.atakmap.android.atmosphere.net.Http.shutdown();
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
            if (waves != null)
                atmospherePane.setWaves(waves);
            if (rain != null)
                atmospherePane.setRain(rain);
            if (air != null)
                atmospherePane.setAirQuality(air);
            if (avalanche != null)
                atmospherePane.setAvalanche(avalanche);
            if (fireZones != null)
                atmospherePane.setFireZones(fireZones);
            if (firewx != null)
                atmospherePane.setFireWx(firewx);
            if (sawti != null)
                atmospherePane.setSawti(sawti);
            if (lightning != null)
                atmospherePane.setLightning(lightning);
            if (psps != null)
                atmospherePane.setPsps(psps);
            if (flood != null)
                atmospherePane.setFlood(flood);
            if (beach != null)
                atmospherePane.setBeach(beach);
            if (floodground != null)
                atmospherePane.setFloodedGround(floodground);
            if (highflow != null)
                atmospherePane.setHighFlow(highflow);
            if (snow != null)
                atmospherePane.setSnow(snow);
            if (satellite != null)
                atmospherePane.setSatellite(satellite);
            if (sst != null)
                atmospherePane.setSst(sst);
            if (snotel != null)
                atmospherePane.setSnotel(snotel);
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

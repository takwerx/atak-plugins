package com.atakmap.android.atmosphere.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PointF;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.HorizontalScrollView;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.atakmap.android.atmosphere.astro.Astro;
import com.atakmap.android.atmosphere.compat.MapCompat;
import com.atakmap.android.atmosphere.data.Favorites;
import com.atakmap.android.atmosphere.data.Nhc;
import com.atakmap.android.atmosphere.data.ParamSelection;
import com.atakmap.android.atmosphere.data.WeatherClient;
import com.atakmap.android.atmosphere.model.Reading;
import com.atakmap.android.atmosphere.model.SeriesEntry;
import com.atakmap.android.atmosphere.model.Snapshot;
import com.atakmap.android.atmosphere.net.EgressPolicy;
import com.atakmap.android.atmosphere.data.AirNow;
import com.atakmap.android.atmosphere.overlay.AirQualityOverlay;
import com.atakmap.android.atmosphere.overlay.SpotOverlay;
import com.atakmap.android.atmosphere.compat.ScaleBar;
import com.atakmap.android.atmosphere.data.RedFlag;
import com.atakmap.android.atmosphere.overlay.GaugeOverlay;
import com.atakmap.android.atmosphere.overlay.StationOverlay;
import com.atakmap.android.atmosphere.overlay.WarningsOverlay;
import com.atakmap.android.atmosphere.data.NwsAlerts;
import com.atakmap.android.atmosphere.overlay.RadarOverlay;
import com.atakmap.android.atmosphere.overlay.SmokeOverlay;
import com.atakmap.android.atmosphere.overlay.TropicalOverlay;
import com.atakmap.android.atmosphere.overlay.WindScaleView;
import com.atakmap.android.atmosphere.overlay.WindOverlay;
import com.atakmap.android.atmosphere.smoke.NomadsSmoke;
import com.atakmap.android.atmosphere.wind.NomadsWind;
import com.atakmap.android.atmosphere.plugin.R;
import com.atakmap.android.atmosphere.source.SourceRegistry;
import com.atakmap.android.atmosphere.source.WxParam;
import com.atakmap.android.atmosphere.source.WxSourceDef;
import com.atakmap.android.atmosphere.units.Quantity;
import com.atakmap.android.atmosphere.units.UnitSystem;
import com.atakmap.android.atmosphere.units.Units;
import com.atakmap.android.maps.MapEvent;
import com.atakmap.android.maps.MapEventDispatcher;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.coremap.maps.coords.GeoPointMetaData;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * The plugin pane: pick a source, pick a place, read the weather.
 *
 * <p>Everything shown here comes from a source definition — the labels, the units, which
 * variables exist. Nothing in this class knows the name of a weather provider.
 *
 * <p>Anonymous listeners rather than lambdas: this code ships in release builds, where
 * the SDK documents lambdas breaking under proguard.
 */
public final class AtmospherePane {

    private static final String PREF_SOURCE = "weather.source.selected";
    private static final String PREF_UNITS = "weather.units";
    /** Which point the readout is for: a {@link PointMode} name. */
    private static final String PREF_MODE = "weather.position.mode";
    /** The picked point as "lat,lon", kept so a picked point survives a reopen. */
    private static final String PREF_PICKED = "weather.position.picked";
    /** The name of the favorite being read, when one is; absent for map center or self. */
    private static final String PREF_FAVORITE = "weather.position.favorite";
    private static final String PREF_TREND = "weather.trend.kind";
    private static final String PREF_HOURS_TABLE = "weather.hours.table";
    private static final String PREF_RADAR_OPEN = "weather.radar.open";
    private static final String PREF_TROPICAL_OPEN = "weather.tropical.open";
    private static final String PREF_WIND_OPEN = "weather.wind.open";
    private static final String PREF_SMOKE_OPEN = "weather.smoke.open";
    private static final String PREF_AIR_OPEN = "weather.air.open";
    private static final String PREF_WARN_OPEN = "weather.warn.open";
    private static final String PREF_STATIONS_OPEN = "weather.layers.stations.open";
    private static final String PREF_GAUGES_OPEN = "weather.layers.gauges.open";
    private static final String PREF_STATIONS_GUIDE_OPEN = "weather.layers.stations.guide";
    private static final String PREF_SPOT_OPEN = "weather.spotlayer.open";

    private final View root;
    private final Context pluginContext;
    private final SourceRegistry registry;
    private final EgressPolicy egress;
    private final WeatherClient client;

    private final ImageButton modeSelf;
    private final ImageButton modeCenter;
    private final ImageButton modePick;
    private final ImageButton favoritesButton;
    private final Button unitsButton;
    private final ImageButton wideButton;
    private final ImageButton pageButton;
    private final ViewPager pager;
    private final LinearLayout pageDots;
    private final View[] pages;
    /** Page 3, its own class; the pane only hosts it. */
    private final SpotPage spotPage;
    private final ImageButton refreshButton;
    private final ImageButton settingsButton;
    private final TextView positionText;
    private final TextView statusText;
    private final TextView currentHeading;
    private final LinearLayout currentContainer;
    private final TextView seriesHeading;
    private final HorizontalScrollView trendScroll;
    private final LinearLayout trendHost;
    private final LinearLayout trendChips;
    private final TrendStripView trend;
    private Kind trendKind = Kind.TEMP;
    private final Button hoursTableButton;
    private final View hoursTable;
    private final LinearLayout hoursLegend;
    private final LinearLayout hoursContainer;
    private boolean hoursTableOpen;
    private final TextView daysHeading;
    private final View daysStrip;
    private final LinearLayout daysLegend;
    private final LinearLayout daysContainer;
    private final TextView attributionText;
    private final TextView layersAttribution;
    private final Button radarToggle;
    private final View scrubber;
    private final TextView scrubberLabel;
    private final LinearLayout tropicalSettings;
    private final ImageButton tropicalExpand;
    private final Button tropicalToggle;
    private final TextView tropicalStatus;
    private final LinearLayout tropicalRows;
    private final LinearLayout tropicalScale;
    private TropicalOverlay tropical;
    private boolean tropicalOpen = true;
    /** Which storms are showing their own list of maps. By storm id, not by slot. */
    private final Set<String> stormOpen = new HashSet<>();
    private final LinearLayout radarSettings;
    private final LinearLayout windSettings;
    private final ImageButton radarExpand;
    private final ImageButton windExpand;
    /** Whether each layer is showing its own settings. Folded away, a layer is a row. */
    private boolean radarOpen;
    private boolean windOpen = true;
    private final LinearLayout scrubberDays;
    private final LinearLayout scrubberHours;
    /** The times the picker offers, and which of them is on the map. */
    private final List<Long> whenTimes = new ArrayList<>();
    private int whenIndex = -1;
    /** The index that means "right now": hour 0 for wind, the last frame for radar. */
    private int whenLive = -1;
    /**
     * True while the strip is following now rather than a time somebody picked. Live,
     * Today and Tomorrow are one choice of three, so exactly one of them is ever lit
     * (operator, 2026-09-22: "how can i have live and today both checked with 6pm,
     * this makes no sense, its live or today or tomorrow"). The hours belong to a day,
     * so they are only up when a day is the choice.
     */
    private boolean liveMode = true;
    /** Start of the day whose hours are laid out, so a repaint need not rebuild. */
    private long whenDay = Long.MIN_VALUE;
    private RadarOverlay radar;
    private List<String> radarFrames = new ArrayList<>();
    private final Button windToggle;
    private final View windScaleHost;
    private final WindScaleView windScale;
    private final LinearLayout windUnitRow;
    private final View windLevelBlock;
    private final TextView windLevelLabel;
    private final TextView windReading;
    private final LinearLayout windLevelRows;
    private WindOverlay wind;
    private int windHours;
    private final Button smokeToggle;
    private final ImageButton smokeExpand;
    private final LinearLayout smokeSettings;
    private final WindScaleView smokeScale;
    private final TextView smokeReading;
    private final LinearLayout smokeHeightRow;
    private boolean smokeOpen = true;
    private SmokeOverlay smoke;
    private int smokeHours;
    private final Button airToggle;
    private final ImageButton airExpand;
    private final LinearLayout airSettings;
    private final TextView airStatus;
    private final LinearLayout airScale;
    private final TextView airReading;
    private boolean airOpen = true;
    private AirQualityOverlay air;
    private final Button warnToggle;
    private final ImageButton warnExpand;
    private final Button spotToggle;
    private final ImageButton spotExpand;
    private final Button spotOpenOnly;
    private final TextView spotLayerStatus;
    private final LinearLayout spotLegend;
    private final View spotSettings;
    private SpotOverlay spotLayer;
    private StationOverlay stationLayer;
    private StationPage stationPage;
    private TextView stationsStatus, stationsBasis;
    private Button stationsToggle;
    private ImageButton stationsExpand;
    private View stationsSettings;
    private LinearLayout stationsOriginRow, stationsDistanceRow, stationsLegend;
    private LinearLayout stationsGateRow, stationsLabelGateRow, stationsShowRow;
    private TextView stationsGateText, stationsLabelGateText;
    private Button stationsLabels, stationsGuideToggle;
    private ImageButton stationsGuideExpand;
    private LinearLayout stationsGuide;
    private boolean stationsGuideOpen;
    private boolean stationsOpen;
    private GaugeOverlay gaugeLayer;
    private GaugePage gaugePage;
    private LinearLayout gaugesShowRow, gaugesGateRow, gaugesLabelGateRow;
    private TextView gaugesGateText, gaugesLabelGateText;
    private Button gaugesLabels;
    private TextView gaugesStatus;
    private Button gaugesToggle;
    private ImageButton gaugesExpand;
    private View gaugesSettings;
    private LinearLayout gaugesOriginRow, gaugesDistanceRow, gaugesLegend;
    private boolean gaugesOpen;
    private boolean spotOpen = true;
    private final LinearLayout warnSettings;
    private final TextView warnHere;
    private final TextView warnStatus;
    private final LinearLayout warnGroups;
    private boolean warnOpen = true;
    private WarningsOverlay warnings;

    private final List<WxSourceDef> sources;

    private WxSourceDef selected;
    private UnitSystem units;
    /** Where the readout is for. */
    public enum PointMode {
        SELF, CENTER, PICKED, FAVORITE;

        static PointMode fromName(String n) {
            if (n != null)
                for (PointMode m : values())
                    if (m.name().equals(n)) return m;
            return CENTER;
        }
    }

    private PointMode mode = PointMode.CENTER;
    /** The point picked on the map, when the mode is PICKED. */
    private GeoPoint pickedPoint;
    /** The saved place being read, when the mode is FAVORITE. */
    private Favorites.Place favorite;
    private final Favorites favorites;
    /** True between the Pick tap and the map tap; the map listeners are pushed. */
    private boolean pickArmed;
    private MapEventDispatcher.MapEventDispatchListener pickListener;
    /** The drop-down hosting this pane, for the wide toggle; set after construction. */
    private AtmosphereDropDown host;
    private Snapshot snapshot;

    public AtmospherePane(View root, Context pluginContext, SourceRegistry registry,
            EgressPolicy egress, WeatherClient client) {

        this.root = root;
        this.pluginContext = pluginContext;
        this.registry = registry;
        this.egress = egress;
        this.client = client;
        this.sources = registry.sources();

        // The pages are their own layouts, inflated here and handed to the pager;
        // every id below is looked up across the root and the pages.
        final LayoutInflater inflater = LayoutInflater.from(pluginContext);
        spotPage = new SpotPage(pluginContext, MapView.getMapView(), egress,
                new SpotPage.Host() {
                    @Override
                    public GeoPoint point() {
                        return AtmospherePane.this.point();
                    }

                    @Override
                    public String pointLabel() {
                        return modeLabel();
                    }

                    @Override
                    public UnitSystem units() {
                        return units;
                    }
                });
        stationPage = new StationPage(pluginContext, mapView(), new StationPage.Host() {
            @Override
            public UnitSystem units() {
                return units;
            }
        });
        gaugePage = new GaugePage(pluginContext, mapView(), new GaugePage.Host() {
            @Override
            public UnitSystem units() {
                return units;
            }

            @Override
            public EgressPolicy egress() {
                return egress;
            }
        });
        pages = new View[] {
                inflater.inflate(R.layout.page_forecast, null),
                inflater.inflate(R.layout.page_layers, null),
                spotPage.view(),
                stationPage.view(),
                gaugePage.view()
        };
        pager = root.findViewById(R.id.pager);
        pageDots = root.findViewById(R.id.page_dots);
        pageButton = root.findViewById(R.id.page_button);
        wirePager();

        modeSelf = find(R.id.mode_self);
        modeCenter = find(R.id.mode_center);
        modePick = find(R.id.mode_pick);
        favoritesButton = find(R.id.favorites_button);
        unitsButton = find(R.id.units_button);
        wideButton = find(R.id.wide_button);
        refreshButton = find(R.id.refresh_button);
        settingsButton = find(R.id.settings_button);
        positionText = find(R.id.position_text);
        statusText = find(R.id.status_text);
        currentHeading = find(R.id.current_heading);
        currentContainer = find(R.id.current_container);
        seriesHeading = find(R.id.series_heading);
        trendScroll = find(R.id.trend_scroll);
        trendHost = find(R.id.trend_host);
        trendChips = find(R.id.trend_chips);
        trend = new TrendStripView(pluginContext);
        trendHost.addView(trend);
        hoursTableButton = find(R.id.hours_table_button);
        hoursTable = find(R.id.hours_table);
        hoursLegend = find(R.id.hours_legend);
        hoursContainer = find(R.id.hours_container);
        daysHeading = find(R.id.days_heading);
        daysStrip = find(R.id.days_strip);
        daysLegend = find(R.id.days_legend);
        daysContainer = find(R.id.days_container);
        attributionText = find(R.id.attribution_text);
        layersAttribution = find(R.id.layers_attribution);
        radarToggle = find(R.id.radar_toggle);
        windToggle = find(R.id.wind_toggle);
        windScaleHost = find(R.id.wind_scale_host);
        windScale = new WindScaleView(pluginContext);
        ((LinearLayout) windScaleHost).addView(windScale);
        windUnitRow = new LinearLayout(pluginContext);
        windUnitRow.setOrientation(LinearLayout.HORIZONTAL);
        ((LinearLayout) windScaleHost).addView(windUnitRow);
        buildWindUnitRow();
        windLevelBlock = find(R.id.wind_level_block);
        windLevelLabel = find(R.id.wind_level_label);
        windReading = find(R.id.wind_reading);
        windLevelRows = find(R.id.wind_level_rows);
        buildWindLevelRows();
        scrubber = find(R.id.scrubber);
        scrubberLabel = find(R.id.scrubber_label);
        tropicalSettings = find(R.id.tropical_settings);
        tropicalExpand = find(R.id.tropical_expand);
        tropicalToggle = find(R.id.tropical_toggle);
        tropicalStatus = find(R.id.tropical_status);
        tropicalRows = find(R.id.tropical_rows);
        tropicalScale = find(R.id.tropical_scale);
        buildStormScale();
        radarSettings = find(R.id.radar_settings);
        windSettings = find(R.id.wind_settings);
        radarExpand = find(R.id.radar_expand);
        windExpand = find(R.id.wind_expand);
        smokeToggle = find(R.id.smoke_toggle);
        smokeExpand = find(R.id.smoke_expand);
        smokeSettings = find(R.id.smoke_settings);
        smokeScale = new WindScaleView(pluginContext);
        ((LinearLayout) find(R.id.smoke_scale_host)).addView(smokeScale);
        smokeReading = find(R.id.smoke_reading);
        smokeHeightRow = find(R.id.smoke_height_row);
        buildSmokeHeightRow();
        airToggle = find(R.id.air_toggle);
        airExpand = find(R.id.air_expand);
        airSettings = find(R.id.air_settings);
        airStatus = find(R.id.air_status);
        airScale = find(R.id.air_scale);
        airReading = find(R.id.air_reading);
        buildAirScale();
        warnToggle = find(R.id.warn_toggle);
        warnExpand = find(R.id.warn_expand);
        spotToggle = find(R.id.spot_toggle);
        spotExpand = find(R.id.spot_expand);
        spotOpenOnly = find(R.id.spot_open_only);
        spotLayerStatus = find(R.id.spot_layer_status);
        stationsStatus = find(R.id.stations_status);
        stationsBasis = find(R.id.stations_basis);
        stationsToggle = find(R.id.stations_toggle);
        stationsExpand = find(R.id.stations_expand);
        stationsSettings = find(R.id.stations_settings);
        stationsOriginRow = find(R.id.stations_origin_row);
        stationsDistanceRow = find(R.id.stations_distance_row);
        stationsLegend = find(R.id.stations_legend);
        stationsGateRow = find(R.id.stations_gate_row);
        stationsShowRow = find(R.id.stations_show_row);
        stationsLabelGateRow = find(R.id.stations_label_gate_row);
        stationsGateText = find(R.id.stations_gate_text);
        stationsLabelGateText = find(R.id.stations_label_gate_text);
        stationsLabels = find(R.id.stations_labels);
        stationsGuide = find(R.id.stations_guide);
        stationsGuideToggle = find(R.id.stations_guide_toggle);
        stationsGuideExpand = find(R.id.stations_guide_expand);
        gaugesToggle = find(R.id.gauges_toggle);
        gaugesExpand = find(R.id.gauges_expand);
        gaugesSettings = find(R.id.gauges_settings);
        gaugesStatus = find(R.id.gauges_status);
        gaugesOriginRow = find(R.id.gauges_origin_row);
        gaugesDistanceRow = find(R.id.gauges_distance_row);
        gaugesLegend = find(R.id.gauges_legend);
        gaugesShowRow = find(R.id.gauges_show_row);
        gaugesGateRow = find(R.id.gauges_gate_row);
        gaugesLabelGateRow = find(R.id.gauges_label_gate_row);
        gaugesGateText = find(R.id.gauges_gate_text);
        gaugesLabelGateText = find(R.id.gauges_label_gate_text);
        gaugesLabels = find(R.id.gauges_labels);
        gaugesLabels.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (gaugeLayer == null)
                    return;
                gaugeLayer.setLabels(!gaugeLayer.hasLabels());
                updateLayerControls();
            }
        });
        ((Button) find(R.id.gauges_open_list)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openPage(gaugePage == null ? null : gaugePage.view());
            }
        });
        spotLegend = find(R.id.spot_legend);
        spotSettings = find(R.id.spot_settings);
        warnSettings = find(R.id.warn_settings);
        warnHere = find(R.id.warn_here);
        warnStatus = find(R.id.warn_status);
        warnGroups = find(R.id.warn_groups);
        buildWarnGroups();
        scrubberDays = find(R.id.scrubber_days);
        scrubberHours = find(R.id.scrubber_hours);
        wireLayers();

        final SharedPreferences prefs = MapCompat.prefs();
        units = UnitSystem.fromName(prefs == null ? null
                : prefs.getString(PREF_UNITS, null), UnitSystem.METRIC);
        favorites = new Favorites(MapCompat.atakContext());
        favorite = favorites.byName(prefs == null ? null : prefs.getString(PREF_FAVORITE, null));
        pickedPoint = parsePoint(prefs == null ? null : prefs.getString(PREF_PICKED, null));
        mode = PointMode.fromName(prefs == null ? null : prefs.getString(PREF_MODE, null));
        // A mode whose point is gone (favorite removed, nothing picked) falls back.
        if ((mode == PointMode.FAVORITE && favorite == null)
                || (mode == PointMode.PICKED && pickedPoint == null))
            mode = PointMode.CENTER;
        trendKind = Kind.fromName(prefs == null ? null : prefs.getString(PREF_TREND, null));
        hoursTableOpen = prefs != null && prefs.getBoolean(PREF_HOURS_TABLE, false);
        // Open the first time a layer is used, so its controls are found; after that
        // the operator's own choice stands.
        radarOpen = prefs == null || prefs.getBoolean(PREF_RADAR_OPEN, true);
        tropicalOpen = prefs == null || prefs.getBoolean(PREF_TROPICAL_OPEN, true);
        tropicalExpand.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                tropicalOpen = !tropicalOpen;
                rememberFold(PREF_TROPICAL_OPEN, tropicalOpen);
                updateLayerControls();
            }
        });
        windOpen = prefs == null || prefs.getBoolean(PREF_WIND_OPEN, true);
        radarExpand.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                radarOpen = !radarOpen;
                rememberFold(PREF_RADAR_OPEN, radarOpen);
                updateLayerControls();
            }
        });
        windExpand.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                windOpen = !windOpen;
                rememberFold(PREF_WIND_OPEN, windOpen);
                updateLayerControls();
            }
        });
        smokeOpen = prefs == null || prefs.getBoolean(PREF_SMOKE_OPEN, true);
        airOpen = prefs == null || prefs.getBoolean(PREF_AIR_OPEN, true);
        warnOpen = prefs == null || prefs.getBoolean(PREF_WARN_OPEN, true);
        spotOpen = prefs == null || prefs.getBoolean(PREF_SPOT_OPEN, true);
        stationsOpen = prefs == null || prefs.getBoolean(PREF_STATIONS_OPEN, true);
        gaugesOpen = prefs == null || prefs.getBoolean(PREF_GAUGES_OPEN, true);
        gaugesExpand.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                gaugesOpen = !gaugesOpen;
                rememberFold(PREF_GAUGES_OPEN, gaugesOpen);
                updateLayerControls();
            }
        });
        gaugesToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (gaugeLayer == null)
                    return;
                if (gaugeLayer.isOn()) {
                    gaugeLayer.setOn(false);
                    updateLayerControls();
                } else if (egress.isLayerEnabled(GaugeOverlay.LAYER_ID)) {
                    gaugeLayer.setOn(true);
                    updateLayerControls();
                } else {
                    askToAllowGauges();
                }
            }
        });
        stationsExpand.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stationsOpen = !stationsOpen;
                rememberFold(PREF_STATIONS_OPEN, stationsOpen);
                updateLayerControls();
            }
        });
        final View.OnClickListener guide = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stationsGuideOpen = !stationsGuideOpen;
                rememberFold(PREF_STATIONS_GUIDE_OPEN, stationsGuideOpen);
                updateLayerControls();
            }
        };
        ((Button) find(R.id.layers_all_off)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                allLayers(false);
            }
        });
        ((Button) find(R.id.layers_all_on)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                allLayers(true);
            }
        });
        stationsGuideToggle.setOnClickListener(guide);
        stationsGuideExpand.setOnClickListener(guide);
        stationsGuideOpen = prefs != null && prefs.getBoolean(PREF_STATIONS_GUIDE_OPEN, false);
        ((Button) find(R.id.stations_open_list)).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openPage(stationPage == null ? null : stationPage.view());
                    }
                });
        ((Button) find(R.id.spot_open_list)).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openPage(spotPage == null ? null : spotPage.view());
                    }
                });
        stationsLabels.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (stationLayer == null)
                    return;
                stationLayer.setLabels(!stationLayer.hasLabels());
                updateLayerControls();
            }
        });
        stationsToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (stationLayer == null)
                    return;
                if (stationLayer.isOn()) {
                    stationLayer.setOn(false);
                    updateLayerControls();
                } else if (egress.isLayerEnabled(StationOverlay.LAYER_ID)) {
                    stationLayer.setOn(true);
                    updateLayerControls();
                } else {
                    askToAllowStations();
                }
            }
        });
        spotExpand.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                spotOpen = !spotOpen;
                rememberFold(PREF_SPOT_OPEN, spotOpen);
                updateLayerControls();
            }
        });
        spotOpenOnly.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (spotLayer == null)
                    return;
                spotLayer.setRecentOnly(!spotLayer.isRecentOnly());
                // One setting, both surfaces: the list reads the same preference, so
                // it has to be told to redraw when this changes.
                spotPage.onFilterChanged();
                updateLayerControls();
            }
        });
        warnExpand.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                warnOpen = !warnOpen;
                rememberFold(PREF_WARN_OPEN, warnOpen);
                updateLayerControls();
            }
        });
        airExpand.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                airOpen = !airOpen;
                rememberFold(PREF_AIR_OPEN, airOpen);
                updateLayerControls();
            }
        });
        smokeExpand.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                smokeOpen = !smokeOpen;
                rememberFold(PREF_SMOKE_OPEN, smokeOpen);
                updateLayerControls();
            }
        });
        updateHoursTableButton();
        hoursTableButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hoursTableOpen = !hoursTableOpen;
                final SharedPreferences p = MapCompat.prefs();
                if (p != null)
                    p.edit().putBoolean(PREF_HOURS_TABLE, hoursTableOpen).apply();
                updateHoursTableButton();
                render();
            }
        });

        loadSelectedSource(prefs);
        wireButtons();
        updateUnitsButton();
        updateModeIcons();
        showSourceProblemsIfAny();
    }

    /** Called every time the pane is shown, so a stale pane never lingers. */
    public void onShown() {
        refresh(false);
        // The frame list ages while the pane is closed; a stale one is re-read.
        if (radar != null && radar.isOn())
            radar.refreshFrames(false);
        // An advisory is six-hourly, so this is cheap and only runs when somebody
        // has actually opened the pane to look.
        if (tropical != null && tropical.isOn())
            tropical.refresh(false);
        if (air != null && air.isOn())
            air.refresh(false);
        if (warnings != null && warnings.isOn())
            warnings.refresh(false);
        if (pages[pager.getCurrentItem()] == spotPage.view())
            spotPage.onShown();
        updateLayerControls();
    }

    /** A view by id, in the root or on any page. */
    private <T extends View> T find(int id) {
        T v = root.findViewById(id);
        if (v != null)
            return v;
        for (View page : pages) {
            v = page.findViewById(id);
            if (v != null)
                return v;
        }
        throw new IllegalStateException("no view with id " + id);
    }

    /**
     * Pages in ATAK's own ViewPager (the SDK helloworld TabViewDropDown pattern):
     * the forecast first, the layers a swipe or the arrow away. Plain views, not
     * fragments: a fragment needs the host activity's FragmentManager and the
     * plugin class loader, which is a release-proguard risk for nothing.
     */
    private void wirePager() {
        pager.setAdapter(new PagerAdapter() {
            @Override
            public int getCount() {
                return pages.length;
            }

            @Override
            public boolean isViewFromObject(View view, Object object) {
                return view == object;
            }

            @Override
            public Object instantiateItem(ViewGroup container, int position) {
                container.addView(pages[position]);
                return pages[position];
            }

            @Override
            public void destroyItem(ViewGroup container, int position, Object object) {
                container.removeView((View) object);
            }
        });
        pager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                updatePageDots(position);
                if (pages[position] == spotPage.view())
                    spotPage.onShown();
                // A list page re-reads its layer when it comes into view: the tile
                // counts are otherwise as old as the last redraw, and the operator
                // saw "Red Flag (1)" over a list of two (2026-09-26).
                if (stationPage != null && pages[position] == stationPage.view())
                    stationPage.refresh();
                if (gaugePage != null && pages[position] == gaugePage.view())
                    gaugePage.refresh();
            }
        });
        for (int i = 0; i < pages.length; i++) {
            final View dot = new View(pluginContext);
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(9), dp(9));
            lp.setMargins(dp(4), 0, dp(4), 0);
            dot.setLayoutParams(lp);
            final android.graphics.drawable.GradientDrawable bg =
                    new android.graphics.drawable.GradientDrawable();
            bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            bg.setColor(Color.WHITE);
            dot.setBackground(bg);
            final int page = i;
            dot.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    pager.setCurrentItem(page, true);
                }
            });
            pageDots.addView(dot);
        }
        updatePageDots(0);
        pageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pager.setCurrentItem((pager.getCurrentItem() + 1) % pages.length, true);
            }
        });
    }

    private void updatePageDots(int current) {
        for (int i = 0; i < pageDots.getChildCount(); i++)
            pageDots.getChildAt(i).setAlpha(i == current ? 1f : 0.35f);
        // The arrow points at the page it will go to: right until the last page,
        // then mirrored, since from there it goes back to the first.
        pageButton.setScaleX(current == pages.length - 1 ? -1f : 1f);
    }

    /** The drop-down hosting this pane; the wide toggle needs it. */
    public void setHost(AtmosphereDropDown host) {
        this.host = host;
    }

    /** The radar overlay, owned by the plugin; the pane drives and reads it. */
    public void setRadar(RadarOverlay overlay) {
        radar = overlay;
        if (radar == null)
            return;
        radar.setListener(new RadarOverlay.Listener() {
            @Override
            public void onFrames(List<String> times, int shown) {
                radarFrames = times;
                if (radar.isOn())
                    setWhenTimes(radarTimes(), shown, Math.max(0, times.size() - 1));
                updateLayerControls();
            }

            @Override
            public void onFrameShown(int index, String time) {
                if (!radar.isOn())
                    return;
                showWhen(index);
                scrubberLabel.setText(frameLabel(index, time));
            }

            @Override
            public void onStatus(String status) {
                if (!radar.isOn())
                    return;
                if (!status.isEmpty())
                    scrubberLabel.setText(status);
                else if (radar != null) {
                    final int i = radar.frameIndex();
                    scrubberLabel.setText(frameLabel(i,
                            i < 0 || i >= radarFrames.size() ? null : radarFrames.get(i)));
                }
            }
        });
        updateLayerControls();
    }

    /** The wind overlay, owned by the plugin; shares the scrubber with the radar. */
    public void setWind(WindOverlay overlay) {
        wind = overlay;
        if (wind == null)
            return;
        wind.setListener(new WindOverlay.Listener() {
            @Override
            public void onFrames(List<String> labels, int shown) {
                windHours = Math.max(0, labels.size() - 1);
                if (wind.isOn())
                    setWhenTimes(windTimes(), shown, 0);
                updateLayerControls();
            }

            @Override
            public void onFrameShown(int index, long validTime) {
                if (!wind.isOn())
                    return;
                showWhen(index);
                scrubberLabel.setText(windLabel(index, validTime));
                // The model can change with the height, so the level line follows.
                updateWindLevel();
            }

            @Override
            public void onStatus(String status) {
                if (!wind.isOn())
                    return;
                if (!status.isEmpty())
                    scrubberLabel.setText(status);
                else
                    scrubberLabel.setText(
                            windLabel(wind.hourIndex(), wind.validTime(wind.hourIndex())));
            }
        });
        updateLayerControls();
    }

    /**
     * The smoke overlay, owned by the plugin. A forecast from the same runs as the
     * wind, so it shares the strip and the wind's way of naming an hour.
     */
    public void setSmoke(SmokeOverlay overlay) {
        smoke = overlay;
        if (smoke == null)
            return;
        smoke.setListener(new SmokeOverlay.Listener() {
            @Override
            public void onFrames(List<String> labels, int shown) {
                smokeHours = Math.max(0, labels.size() - 1);
                if (smoke.isOn())
                    setWhenTimes(smokeTimes(), shown, 0);
                updateLayerControls();
            }

            @Override
            public void onFrameShown(int index, long validTime) {
                if (!smoke.isOn())
                    return;
                showWhen(index);
                scrubberLabel.setText(windLabel(index, validTime));
                // A new hour is a new picture, so the number at the point moves too.
                updateSmokeReading();
            }

            @Override
            public void onStatus(String status) {
                if (!smoke.isOn())
                    return;
                if (!status.isEmpty())
                    scrubberLabel.setText(status);
                else
                    scrubberLabel.setText(
                            windLabel(smoke.hourIndex(), smoke.validTime(smoke.hourIndex())));
                updateSmokeReading();
            }
        });
        updateLayerControls();
    }

    /** Warnings, owned by the plugin. Not time-enabled; its lines follow the pane's point. */
    public void setWarnings(WarningsOverlay overlay) {
        warnings = overlay;
        if (warnings == null)
            return;
        warnings.setListener(new WarningsOverlay.Listener() {
            @Override
            public void onStatus(String status) {
                warnStatus.setText(status);
                warnStatus.setVisibility(status.isEmpty() ? View.GONE : View.VISIBLE);
            }

            @Override
            public void onAlerts() {
                updateWarnHere();
            }
        });
        updateLayerControls();
    }

    /**
     * Air quality, owned by the plugin. The latest hour only, so no strip: its line
     * says which hour is on the map, and its reading follows the pane's point.
     */
    public void setAirQuality(AirQualityOverlay overlay) {
        air = overlay;
        if (air == null)
            return;
        air.setListener(new AirQualityOverlay.Listener() {
            @Override
            public void onStatus(String status) {
                airStatus.setText(status);
                airStatus.setVisibility(status.isEmpty() ? View.GONE : View.VISIBLE);
            }

            @Override
            public void onContours() {
                updateAirReading();
            }
        });
        updateLayerControls();
    }

    /**
     * When the wind on screen is for, led by how far that is from now, which is what
     * somebody scrubbing the bar is actually asking. "+0 h" is a modeler's way of
     * counting and the model's name is not something a crew can act on.
     */
    private String windLabel(int hour, long validTime) {
        if (validTime <= 0)
            return "No forecast yet";
        // Counted in steps off the bar rather than off the clock. Step 0 is the hour
        // that contains now, so at 5:46 it is the 5 pm forecast: measuring that
        // against the clock reads "44 min ago", which is stale weather to anyone
        // looking at it, and it is the wind blowing right now.
        final String when = hour <= 0 ? "Now"
                : "In " + hour + " h";
        return when + ", " + clock(validTime);
    }

    /** "now", "in 6 h", "2 h ago" \u2014 a forecast hour in the operator's terms. */
    private static String relativeTime(long when) {
        final long minutes = Math.round((when - System.currentTimeMillis()) / 60000.0);
        if (Math.abs(minutes) <= 30)
            return "now";
        final long size = Math.abs(minutes);
        final String gap = size < 60 ? size + " min" : Math.round(size / 60.0) + " h";
        return minutes > 0 ? "in " + gap : gap + " ago";
    }

    /** "Tue 6 pm": the day and hour, with a lower-case meridiem. */
    private static String clock(long when) {
        return new SimpleDateFormat("EEE h a", Locale.US).format(new Date(when))
                .replace("AM", "am").replace("PM", "pm");
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ---- layers ------------------------------------------------------------------

    private void wireLayers() {
        tropicalToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (tropical == null)
                    return;
                if (tropical.isOn()) {
                    tropical.setOn(false);
                    updateLayerControls();
                } else if (egress.isLayerEnabled(TropicalOverlay.LAYER_ID)) {
                    turnTropicalOn();
                } else {
                    askToAllowTropical();
                }
            }
        });
        windToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (wind == null)
                    return;
                if (wind.isOn()) {
                    wind.setOn(false);
                    updateLayerControls();
                } else if (egress.isLayerEnabled(WindOverlay.LAYER_ID)) {
                    turnWindOn();
                } else {
                    askToAllowWind();
                }
            }
        });
        spotToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (spotLayer == null)
                    return;
                if (spotLayer.isOn()) {
                    spotLayer.setOn(false);
                    updateLayerControls();
                } else if (egress.isLayerEnabled(SpotOverlay.LAYER_ID)) {
                    turnSpotLayerOn();
                } else {
                    askToAllowSpotLayer();
                }
            }
        });
        warnToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (warnings == null)
                    return;
                if (warnings.isOn()) {
                    warnings.setOn(false);
                    updateLayerControls();
                } else if (egress.isLayerEnabled(WarningsOverlay.LAYER_ID)) {
                    warnings.setOn(true);
                    updateLayerControls();
                } else {
                    askToAllowWarnings();
                }
            }
        });
        airToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (air == null)
                    return;
                if (air.isOn()) {
                    air.setOn(false);
                    updateLayerControls();
                } else if (egress.isLayerEnabled(AirQualityOverlay.LAYER_ID)) {
                    air.setOn(true);
                    updateLayerControls();
                } else {
                    askToAllowAir();
                }
            }
        });
        smokeToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (smoke == null)
                    return;
                if (smoke.isOn()) {
                    smoke.setOn(false);
                    updateLayerControls();
                } else if (egress.isLayerEnabled(SmokeOverlay.LAYER_ID)) {
                    turnSmokeOn();
                } else {
                    askToAllowSmoke();
                }
            }
        });
        radarToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (radar == null)
                    return;
                if (radar.isOn()) {
                    radar.setOn(false);
                    updateLayerControls();
                } else if (egress.isLayerEnabled(RadarOverlay.LAYER_ID)) {
                    turnRadarOn();
                } else {
                    askToAllowRadar();
                }
            }
        });
    }

    // ---- when: live, then a day, then an hour ----------------------------------------

    /** The wind's forecast hours as wall-clock times, index for index. */
    private List<Long> windTimes() {
        final List<Long> out = new ArrayList<>();
        if (wind != null)
            for (int i = 0; i <= windHours; i++)
                out.add(wind.validTime(i));
        return out;
    }

    /** The smoke's forecast hours as wall-clock times, index for index. */
    private List<Long> smokeTimes() {
        final List<Long> out = new ArrayList<>();
        if (smoke != null)
            for (int i = 0; i <= smokeHours; i++)
                out.add(smoke.validTime(i));
        return out;
    }

    /** The radar's frame stamps as wall-clock times, index for index. */
    private List<Long> radarTimes() {
        final List<Long> out = new ArrayList<>();
        for (String t : radarFrames)
            out.add(com.atakmap.android.atmosphere.data.IsoTime.parse(t));
        return out;
    }

    /**
     * Hand the picker a fresh set of times.
     *
     * @param live the index that means now: hour 0 for a forecast that runs forward,
     *             the last frame for a radar loop that runs up to the present.
     */
    private void setWhenTimes(List<Long> times, int shown, int live) {
        whenTimes.clear();
        whenTimes.addAll(times);
        whenLive = live;
        whenIndex = shown >= 0 && shown < whenTimes.size() ? shown : live;
        // A rebuilt list is following now again whenever it lands on now: the run
        // rolled forward, or the layer was just switched on.
        liveMode = whenIndex == whenLive;
        whenDay = Long.MIN_VALUE;
        buildWhenPicker();
    }

    /** The map moved to another time; follow it without rebuilding unless the day changed. */
    private void showWhen(int index) {
        if (index < 0 || index >= whenTimes.size())
            return;
        whenIndex = index;
        if (liveMode == (scrubberHours.getVisibility() == View.VISIBLE)
                || startOfDay(whenTimes.get(index)) != whenDay)
            buildWhenPicker();
        else
            paintWhenPicker();
    }

    /**
     * Live, the days, and the hours of the day being shown, as buttons.
     *
     * <p>This was a SeekBar and a SeekBar was wrong here twice over: on a pager page
     * it will not drag sideways, and once it is made to, a finger meaning to scroll
     * the page drags the value instead. Buttons also say what the choices are without
     * being touched, which is the whole of "pick a day and an hour".
     */
    private void buildWhenPicker() {
        scrubberDays.removeAllViews();
        scrubberHours.removeAllViews();
        if (whenTimes.isEmpty())
            return;
        if (whenIndex < 0 || whenIndex >= whenTimes.size())
            whenIndex = Math.max(0, Math.min(whenLive, whenTimes.size() - 1));
        whenDay = startOfDay(whenTimes.get(whenIndex));

        // Live, then one button per day the times cover. A single day is not worth
        // a button of its own: the hours below it are already that day.
        scrubberDays.addView(whenButton("Live", null, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                liveMode = true;
                pickWhen(whenLive);
            }
        }, liveMode));
        final List<Long> days = new ArrayList<>();
        for (Long t : whenTimes) {
            final long d = startOfDay(t);
            if (!days.contains(d))
                days.add(d);
        }
        // Every day the forecast reaches gets a button, even when that is one. It used
        // to take two before any appeared, on the grounds that a lone "Today" says
        // nothing -- but Live hides the hours, so a forecast that fits inside one day
        // left Live as the only control on the page with no way to reach an hour at
        // all (operator, 2026-09-23: "all i see is live"). Last night's window
        // straddled midnight and showed two, which is why this held until morning.
        for (final Long day : days)
            scrubberDays.addView(whenButton(dayLabel(day), day, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    // A day is picked by going to its first hour, so green always
                    // means "this is what is on the map" and never "this is open".
                    liveMode = false;
                    for (int i = 0; i < whenTimes.size(); i++)
                        if (startOfDay(whenTimes.get(i)) == day) {
                            pickWhen(i);
                            return;
                        }
                }
            }, !liveMode && day == whenDay));

        // Live is not a day, so it has no hours to choose from.
        scrubberHours.setVisibility(liveMode ? View.GONE : View.VISIBLE);
        if (liveMode)
            return;
        LinearLayout row = null;
        int inRow = 0;
        for (int i = 0; i < whenTimes.size(); i++) {
            if (startOfDay(whenTimes.get(i)) != whenDay)
                continue;
            if (inRow % 4 == 0) {
                row = new LinearLayout(pluginContext);
                row.setOrientation(LinearLayout.HORIZONTAL);
                scrubberHours.addView(row, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
            }
            final int index = i;
            final Button b = whenButton(hourLabel(whenTimes.get(i)), index,
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            liveMode = false;
                            pickWhen(index);
                        }
                    }, i == whenIndex);
            row.addView(b);
            inRow++;
        }
        // Pad the last row so four across stay four across.
        if (row != null)
            for (int i = inRow % 4; i > 0 && i < 4; i++) {
                final View filler = new View(pluginContext);
                final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, 1, 1f);
                lp.rightMargin = dp(4);
                row.addView(filler, lp);
            }
    }

    /** Repaint which button is green without rebuilding the rows. */
    private void paintWhenPicker() {
        paintGreen(scrubberDays);
        for (int i = 0; i < scrubberHours.getChildCount(); i++) {
            final View row = scrubberHours.getChildAt(i);
            if (row instanceof LinearLayout)
                paintGreen((LinearLayout) row);
        }
    }

    private void paintGreen(LinearLayout host) {
        final long day = whenIndex >= 0 && whenIndex < whenTimes.size()
                ? startOfDay(whenTimes.get(whenIndex)) : Long.MIN_VALUE;
        for (int i = 0; i < host.getChildCount(); i++) {
            final View v = host.getChildAt(i);
            if (!(v instanceof Button))
                continue;
            final Object tag = v.getTag(R.id.scrubber);
            final boolean on;
            if (tag instanceof Integer)
                on = !liveMode && (Integer) tag == whenIndex;
            else if (tag instanceof Long)
                on = !liveMode && ((Long) tag).longValue() == day;
            else
                on = liveMode;
            ((Button) v).setTextColor(on
                    ? pluginContext.getResources().getColor(R.color.state_on) : Color.WHITE);
        }
    }

    /** One cell of the picker: a quarter-width TakwerxButton, green when it is on. */
    private Button whenButton(String text, Object tag, View.OnClickListener onClick,
            boolean on) {
        final Button b = (Button) LayoutInflater.from(pluginContext)
                .inflate(R.layout.trend_chip, scrubberHours, false);
        b.setText(text);
        // Keyed so a repaint can tell an hour (Integer) from a day (Long) from Live.
        b.setTag(R.id.scrubber, tag);
        b.setTextSize(13);
        b.setOnClickListener(onClick);
        b.setTextColor(on
                ? pluginContext.getResources().getColor(R.color.state_on) : Color.WHITE);
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = dp(4);
        lp.topMargin = dp(4);
        b.setLayoutParams(lp);
        return b;
    }

    /** Send a pick to whichever layer owns the strip. */
    private void pickWhen(int index) {
        if (index < 0 || index >= whenTimes.size())
            return;
        // The line answers the tap, not the fetch. A grid takes a moment to arrive and
        // the label is only written when it does, so the heading sat on the old time
        // while the buttons had already moved, which reads as a control that did not
        // take (XCover, 2026-09-22).
        if (wind != null && wind.isOn()) {
            wind.setHourIndex(index);
            scrubberLabel.setText(windLabel(index, whenTimes.get(index)));
        } else if (smoke != null && smoke.isOn()) {
            smoke.setHourIndex(index);
            scrubberLabel.setText(windLabel(index, whenTimes.get(index)));
        } else if (radar != null && radar.isOn()) {
            radar.setFrameIndex(index);
            scrubberLabel.setText(frameLabel(index,
                    index < radarFrames.size() ? radarFrames.get(index) : null));
        }
        showWhen(index);
    }

    /** "Today", "Tomorrow", else the weekday. */
    private static String dayLabel(long day) {
        final long from = Math.round(
                (day - startOfDay(System.currentTimeMillis())) / 86_400_000.0);
        if (from == 0)
            return "Today";
        if (from == 1)
            return "Tomorrow";
        return new SimpleDateFormat("EEE", Locale.US).format(new Date(day));
    }

    /** "6 pm" on the hour, "5:12 pm" off it, because radar frames are not hourly. */
    private static String hourLabel(long when) {
        final Calendar c = Calendar.getInstance();
        c.setTimeInMillis(when);
        final String pattern = c.get(Calendar.MINUTE) == 0 ? "h a" : "h:mm a";
        return new SimpleDateFormat(pattern, Locale.US).format(new Date(when))
                .replace("AM", "am").replace("PM", "pm");
    }

    private static long startOfDay(long when) {
        final Calendar c = Calendar.getInstance();
        c.setTimeInMillis(when);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /**
     * Hurricanes: NHC's live storms, their forecast cone and their track. Unlike radar
     * and wind this is not a time-enabled layer -- an advisory is a single picture
     * issued every six hours, so it does not share the strip.
     */
    public void setTropical(TropicalOverlay overlay) {
        tropical = overlay;
        if (tropical == null)
            return;
        tropical.setListener(new TropicalOverlay.Listener() {
            @Override
            public void onStorms(List<Nhc.Storm> storms) {
                buildStormRows(storms);
                updateLayerControls();
            }

            @Override
            public void onStatus(String status) {
                if (!status.isEmpty()) {
                    tropicalStatus.setText(status);
                    tropicalStatus.setVisibility(View.VISIBLE);
                }
            }
        });
        updateLayerControls();
    }

    /**
     * One row per storm: what it is, how hard, and Go to. The shape is Comms'
     * site_row and Cam Depot's camera_row, so a crew moving between takwerx plugins
     * finds the same control (operator, 2026-09-23).
     */
    private void buildStormRows(List<Nhc.Storm> storms) {
        tropicalRows.removeAllViews();
        if (storms.isEmpty()) {
            tropicalStatus.setText(R.string.tropical_none);
            tropicalStatus.setVisibility(View.VISIBLE);
            return;
        }
        // The list is the answer, so the status line gets out of its way.
        tropicalStatus.setVisibility(View.GONE);
        for (final Nhc.Storm storm : storms) {
            final View row = LayoutInflater.from(pluginContext)
                    .inflate(R.layout.storm_row, tropicalRows, false);
            ((TextView) row.findViewById(R.id.name)).setText(storm.display());
            ((TextView) row.findViewById(R.id.detail)).setText(strength(storm));
            row.findViewById(R.id.goto_btn).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (tropical != null)
                        tropical.goTo(storm);
                }
            });
            tropicalRows.addView(row);

            // Each storm's own maps, folded under it. Same chevron as a layer's
            // settings, because it means the same thing (operator, 2026-09-23:
            // "sub menus for the different maps it has for each one ... to turn on
            // and off like we have in the feature layer plugin").
            final LinearLayout products = new LinearLayout(pluginContext);
            products.setOrientation(LinearLayout.VERTICAL);
            products.setVisibility(stormOpen.contains(storm.id)
                    ? View.VISIBLE : View.GONE);
            for (final Nhc.Product product : Nhc.Product.values())
                products.addView(productButton(storm, product));
            tropicalRows.addView(products);

            final ImageButton chevron = (ImageButton) row.findViewById(R.id.storm_expand);
            chevron.setRotation(stormOpen.contains(storm.id) ? 180f : 0f);
            chevron.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (stormOpen.contains(storm.id))
                        stormOpen.remove(storm.id);
                    else
                        stormOpen.add(storm.id);
                    final boolean open = stormOpen.contains(storm.id);
                    products.setVisibility(open ? View.VISIBLE : View.GONE);
                    chevron.setRotation(open ? 180f : 0f);
                }
            });
        }
    }

    /**
     * What the track colors mean (operator, 2026-09-23: "on the hurricane when you
     * expand the section a color code for the categories"). Swatches come from the
     * same table the map draws with, so the legend cannot drift from the map.
     */
    /**
     * EPA's six categories in EPA's colors, from the table the map is drawn with. Two
     * lines allowed, because "Sensitive groups" and "Very unhealthy" are the words and
     * six cells across half a pane is not wide.
     */
    private void buildAirScale() {
        airScale.removeAllViews();
        for (AirNow.Category c : AirNow.Category.values()) {
            final TextView cell = new TextView(pluginContext);
            cell.setText(c.shortLabel);
            cell.setTextSize(10);
            cell.setMaxLines(2);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(dp(1), dp(3), dp(1), dp(3));
            cell.setBackgroundColor(c.color);
            // Green, yellow and orange carry dark text; red and darker carry light.
            cell.setTextColor(c.ordinal() <= AirNow.Category.SENSITIVE.ordinal()
                    ? 0xFF101010 : 0xFFFFFFFF);
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
            cell.setLayoutParams(lp);
            airScale.addView(cell);
        }
    }

    /**
     * Fire weather, land, marine: one switch each, green when shown. Categories first,
     * the IPAWS lesson -- "if i dont care about marine i dont want marine".
     */
    private void buildWarnGroups() {
        final NwsAlerts.Group[] order = { NwsAlerts.Group.FIRE, NwsAlerts.Group.LAND,
                NwsAlerts.Group.MARINE };
        final int[] labels = { R.string.warn_fire, R.string.warn_land, R.string.warn_marine };
        for (int i = 0; i < order.length; i++) {
            final NwsAlerts.Group g = order[i];
            final Button b = (Button) LayoutInflater.from(pluginContext)
                    .inflate(R.layout.trend_chip, warnGroups, false);
            b.setText(labels[i]);
            b.setTextSize(13);
            b.setTag(g);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (warnings == null)
                        return;
                    warnings.setShowing(g, !warnings.isShowing(g));
                    updateWarnGroups();
                }
            });
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = dp(4);
            lp.topMargin = dp(4);
            b.setLayoutParams(lp);
            warnGroups.addView(b);
        }
    }

    private void updateWarnGroups() {
        for (int i = 0; i < warnGroups.getChildCount(); i++) {
            final View v = warnGroups.getChildAt(i);
            if (v instanceof Button && v.getTag() instanceof NwsAlerts.Group)
                ((Button) v).setTextColor(warnings != null
                        && warnings.isShowing((NwsAlerts.Group) v.getTag())
                        ? pluginContext.getResources().getColor(R.color.state_on)
                        : Color.WHITE);
        }
    }

    /**
     * What is in effect at the pane's point, most urgent first: "Here: Red Flag Warning
     * until Thu 8 pm". One line per warning, three at most; the map has the rest.
     */
    private void updateWarnHere() {
        if (warnings == null || !warnings.isOn()) {
            warnHere.setText(R.string.empty);
            return;
        }
        final GeoPoint p = point();
        if (p == null) {
            warnHere.setText(R.string.empty);
            return;
        }
        final List<NwsAlerts.Alert> here = warnings.inEffectAt(p.getLatitude(), p.getLongitude());
        if (here.isEmpty()) {
            warnHere.setText(R.string.warn_here_none);
            return;
        }
        final StringBuilder b = new StringBuilder("Here: ");
        for (int i = 0; i < here.size() && i < 3; i++) {
            if (i > 0)
                b.append('\n').append("      ");
            final NwsAlerts.Alert a = here.get(i);
            b.append(a.event);
            if (a.until() > 0)
                b.append(" until ").append(WarningsOverlay.clock(a.until()));
        }
        if (here.size() > 3)
            b.append("\n      and ").append(here.size() - 3).append(" more");
        warnHere.setText(b.toString());
    }

    /** The category at the pane's point, from the contours already on the map. */
    private void updateAirReading() {
        if (air == null || !air.isOn()) {
            airReading.setText(R.string.empty);
            return;
        }
        final GeoPoint p = point();
        if (p == null) {
            airReading.setText(R.string.empty);
            return;
        }
        final AirNow.Category c = air.at(p.getLatitude(), p.getLongitude());
        airReading.setText(c == null
                ? pluginContext.getString(R.string.air_here_none)
                : pluginContext.getString(R.string.air_here, c.label, c.range));
    }

    private void buildStormScale() {
        tropicalScale.removeAllViews();
        for (int i = 0; i < TropicalOverlay.SAFFIR_SIMPSON_LABELS.length; i++) {
            final TextView cell = new TextView(pluginContext);
            cell.setText(TropicalOverlay.SAFFIR_SIMPSON_LABELS[i]);
            cell.setTextSize(11);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(0, dp(3), 0, dp(3));
            cell.setBackgroundColor(TropicalOverlay.rungColor(i));
            // The pale rungs need dark text and the saturated ones light; the scale
            // runs light-to-dark, so the split is where it stops being readable.
            cell.setTextColor(i <= 3 ? 0xFF101010 : 0xFFFFFFFF);
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            cell.setLayoutParams(lp);
            tropicalScale.addView(cell);
        }
    }

    /** One of a storm's maps: ON green, OFF plain, the way every toggle here reads. */
    private Button productButton(final Nhc.Storm storm, final Nhc.Product product) {
        final Button b = (Button) LayoutInflater.from(pluginContext)
                .inflate(R.layout.storm_product, tropicalRows, false);
        paintProduct(b, storm, product);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (tropical == null)
                    return;
                final boolean next = !tropical.isEnabled(storm, product);
                tropical.setEnabled(storm, product, next);
                paintProduct((Button) v, storm, product);
            }
        });
        return b;
    }

    private void paintProduct(Button b, Nhc.Storm storm, Nhc.Product product) {
        final boolean on = tropical != null && tropical.isEnabled(storm, product);
        b.setText(product.label + (on ? "  ON" : "  OFF"));
        b.setTextColor(pluginContext.getResources().getColor(
                on ? R.color.state_on : R.color.state_off));
    }

    /** "150 mph, cat 4, 922 mb" -- the numbers a crew would hear on the news. */
    private String strength(Nhc.Storm storm) {
        final StringBuilder b = new StringBuilder();
        if (storm.intensityKt > 0) {
            // Knots are how the advisory is written and how nobody outside aviation
            // reads it. This line is not a unit the pane's own switch reaches.
            b.append(Math.round(storm.intensityKt * 1.15078)).append(" mph");
            if (storm.category() > 0)
                b.append(", cat ").append(storm.category());
        }
        if (storm.pressureMb > 0) {
            if (b.length() > 0)
                b.append(", ");
            b.append(storm.pressureMb).append(" mb");
        }
        return b.toString();
    }

    private void turnTropicalOn() {
        if (tropical != null)
            tropical.setOn(true);
        updateLayerControls();
    }

    private void askToAllowTropical() {
        final Context ctx = MapCompat.atakContext();
        if (ctx == null)
            return;
        new AlertDialog.Builder(ctx)
                .setTitle(pluginContext.getString(R.string.tropical_allow_title))
                .setMessage(pluginContext.getString(R.string.tropical_allow_text))
                .setPositiveButton(pluginContext.getString(R.string.allow),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                egress.setLayerEnabled(TropicalOverlay.LAYER_ID, true);
                                turnTropicalOn();
                            }
                        })
                .setNegativeButton(pluginContext.getString(R.string.close), null)
                .show();
    }

    /**
     * What the layer draws, hooked to the pane. Not time-enabled: a spot request is
     * a standing thing, not a frame, so it never touches the time strip.
     */
    /**
     * The station layer. Its controls on this page are not built yet; it is held so
     * the plugin can hand it over once, and turned on from the layer's own toggle.
     */
    public void setStations(StationOverlay overlay) {
        stationLayer = overlay;
        if (stationLayer == null)
            return;
        stationLayer.setListener(new StationOverlay.Listener() {
            @Override
            public void onStationsStatus(String s) {
                if (!s.isEmpty() && stationsStatus != null)
                    stationsStatus.setText(s);
            }

            @Override
            public void onStationsDrawn(int drawn, int total, int critical) {
                if (stationsStatus != null)
                    stationsStatus.setText(total == 0 ? ""
                            : drawn + " stations, " + critical + " at criteria");
                if (stationPage != null)
                    stationPage.refresh();
                updateLayerControls();
            }

            @Override
            public void onOriginMoved() {
                // The map moved: the list is ordered by distance from it, so it is
                // now in the wrong order even though nothing was refetched.
                if (stationPage != null)
                    stationPage.refresh();
                updateLayerControls();      // the gate readouts quote the scale bar
            }
        });
        if (stationPage != null)
            stationPage.setLayer(stationLayer);
    }

    public void setGauges(GaugeOverlay overlay) {
        gaugeLayer = overlay;
        if (gaugeLayer == null)
            return;
        gaugeLayer.setListener(new GaugeOverlay.Listener() {
            @Override
            public void onGaugesStatus(String s) {
                if (!s.isEmpty() && gaugesStatus != null)
                    gaugesStatus.setText(s);
            }

            @Override
            public void onGaugesDrawn(int drawn, int total, int flooding) {
                if (gaugesStatus != null)
                    gaugesStatus.setText(total == 0 ? "" : drawn + " gauges, "
                            + (flooding == 0 ? "none at action stage or above"
                                    : flooding + " at action stage or above"));
                if (gaugePage != null)
                    gaugePage.refresh();
                updateLayerControls();
            }

            @Override
            public void onOriginMoved() {
                if (gaugePage != null)
                    gaugePage.refresh();
            }
        });
        if (gaugePage != null)
            gaugePage.setLayer(gaugeLayer);
    }

    public void setSpotLayer(SpotOverlay overlay) {
        spotLayer = overlay;
        if (spotLayer == null)
            return;
        spotLayer.setListener(new SpotOverlay.Listener() {
            @Override
            public void onStatus(String s) {
                if (!s.isEmpty())
                    spotLayerStatus.setText(s);
            }

            @Override
            public void onDrawn(int drawn, int total) {
                spotLayerStatus.setText(total == 0 ? ""
                        : drawn + " on the map, of " + total + " in the country");
                updateLayerControls();
            }
        });
        updateLayerControls();
    }

    private void turnSpotLayerOn() {
        if (spotLayer != null)
            spotLayer.setOn(true);
        updateLayerControls();
    }

    private void askToAllowGauges() {
        final Context ctx = MapCompat.atakContext();
        if (ctx == null)
            return;
        new AlertDialog.Builder(ctx)
                .setTitle(pluginContext.getString(R.string.gauges_allow_title))
                .setMessage(pluginContext.getString(R.string.gauges_allow_text))
                .setPositiveButton(pluginContext.getString(R.string.allow),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                egress.setLayerEnabled(GaugeOverlay.LAYER_ID, true);
                                if (gaugeLayer != null)
                                    gaugeLayer.setOn(true);
                                updateLayerControls();
                            }
                        })
                .setNegativeButton(pluginContext.getString(R.string.close), null)
                .show();
    }

    private void buildGaugesOriginRow() {
        gaugesOriginRow.removeAllViews();
        final boolean fromMe = gaugeLayer.isFromMe();
        gaugesOriginRow.addView(choiceTile(
                pluginContext.getString(R.string.stations_from_me), fromMe,
                new Runnable() {
                    @Override
                    public void run() {
                        gaugeLayer.setFromMe(true);
                        updateLayerControls();
                    }
                }));
        gaugesOriginRow.addView(choiceTile(
                pluginContext.getString(R.string.stations_from_map), !fromMe,
                new Runnable() {
                    @Override
                    public void run() {
                        gaugeLayer.setFromMe(false);
                        updateLayerControls();
                    }
                }));
    }

    private void buildGaugesDistanceRow() {
        gaugesDistanceRow.removeAllViews();
        final int current = gaugeLayer.miles();
        for (final int m : GaugeOverlay.RADII)
            gaugesDistanceRow.addView(choiceTile(m + " mi", m == current,
                    new Runnable() {
                        @Override
                        public void run() {
                            gaugeLayer.setMiles(m);
                            updateLayerControls();
                        }
                    }));
    }

    /** Which gauges reach the map: the same three the list filters by. */
    private void buildGaugesShowRow() {
        gaugesShowRow.removeAllViews();
        gaugeShowTile("All", GaugeOverlay.SHOW_ALL, 0);
        gaugeShowTile("High water only", GaugeOverlay.SHOW_HIGH,
                GaugeOverlay.legendColor(com.atakmap.android.atmosphere.data.Nwps.ACTION));
        gaugeShowTile("\u2605 Favorites only", GaugeOverlay.SHOW_FAVORITES, StationPage.STAR_ON);
    }

    private void gaugeShowTile(String label, final int value, int color) {
        final boolean chosen = gaugeLayer.show() == value;
        final Button b = (Button) LayoutInflater.from(pluginContext)
                .inflate(R.layout.trend_chip, gaugesShowRow, false);
        b.setText(label);
        b.setTextSize(12);
        b.setTextColor(chosen
                ? (color != 0 ? color : pluginContext.getResources().getColor(R.color.state_on))
                : Color.WHITE);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                gaugeLayer.setShow(value);
                if (gaugePage != null)
                    gaugePage.refresh();
                updateLayerControls();
            }
        });
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = dp(4);
        b.setLayoutParams(lp);
        gaugesShowRow.addView(b);
    }

    /** water.noaa.gov's legend, in its own colors and its own order. */
    private void buildGaugesLegend() {
        if (gaugesLegend.getChildCount() > 0)
            return;
        for (String category : GaugeOverlay.LEGEND)
            gaugesLegend.addView(legendLine(
                    com.atakmap.android.atmosphere.data.Nwps.label(category),
                    GaugeOverlay.legendColor(category)));
    }

    private void askToAllowStations() {
        final Context ctx = MapCompat.atakContext();
        if (ctx == null)
            return;
        new AlertDialog.Builder(ctx)
                .setTitle(pluginContext.getString(R.string.stations_allow_title))
                .setMessage(pluginContext.getString(R.string.stations_allow_text))
                .setPositiveButton(pluginContext.getString(R.string.allow),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                egress.setLayerEnabled(StationOverlay.LAYER_ID, true);
                                if (stationLayer != null)
                                    stationLayer.setOn(true);
                                updateLayerControls();
                            }
                        })
                .setNegativeButton(pluginContext.getString(R.string.close), null)
                .show();
    }

    /**
     * Measured from the operator or from the middle of the map -- the second is how a
     * crew looks at a fire they are not standing on.
     */
    private void buildStationsOriginRow() {
        stationsOriginRow.removeAllViews();
        final boolean fromMe = stationLayer.isFromMe();
        stationsOriginRow.addView(choiceTile(
                pluginContext.getString(R.string.stations_from_me), fromMe,
                new Runnable() {
                    @Override
                    public void run() {
                        stationLayer.setFromMe(true);
                        updateLayerControls();
                    }
                }));
        stationsOriginRow.addView(choiceTile(
                pluginContext.getString(R.string.stations_from_map), !fromMe,
                new Runnable() {
                    @Override
                    public void run() {
                        stationLayer.setFromMe(false);
                        updateLayerControls();
                    }
                }));
    }

    private void buildStationsDistanceRow() {
        stationsDistanceRow.removeAllViews();
        final int current = stationLayer.miles();
        for (final int m : StationOverlay.RADII)
            stationsDistanceRow.addView(choiceTile(m + " mi", m == current,
                    new Runnable() {
                        @Override
                        public void run() {
                            stationLayer.setMiles(m);
                            updateLayerControls();
                        }
                    }));
    }

    /**
     * Every layer at once.
     *
     * <p>Off is unconditional. <b>On only turns on the layers whose server the
     * operator has already allowed</b>: each of these asks once, by name, before it
     * talks to anything, and a button that quietly said yes on behalf of all of them
     * would be a way around that rather than a convenience. The ones still waiting
     * on an answer stay off and are counted, so nothing happens silently.
     */
    private void allLayers(boolean on) {
        int blocked = 0;
        if (radar != null && on == allowed(RadarOverlay.LAYER_ID))
            radar.setOn(on);
        else if (radar != null && on)
            blocked++;
        if (wind != null && on == allowed(WindOverlay.LAYER_ID))
            wind.setOn(on);
        else if (wind != null && on)
            blocked++;
        if (smoke != null && on == allowed(SmokeOverlay.LAYER_ID))
            smoke.setOn(on);
        else if (smoke != null && on)
            blocked++;
        if (air != null && on == allowed(AirQualityOverlay.LAYER_ID))
            air.setOn(on);
        else if (air != null && on)
            blocked++;
        if (warnings != null && on == allowed(WarningsOverlay.LAYER_ID))
            warnings.setOn(on);
        else if (warnings != null && on)
            blocked++;
        if (spotLayer != null && on == allowed(SpotOverlay.LAYER_ID))
            spotLayer.setOn(on);
        else if (spotLayer != null && on)
            blocked++;
        if (tropical != null && on == allowed(TropicalOverlay.LAYER_ID))
            tropical.setOn(on);
        else if (tropical != null && on)
            blocked++;
        if (stationLayer != null && on == allowed(StationOverlay.LAYER_ID))
            stationLayer.setOn(on);
        else if (stationLayer != null && on)
            blocked++;
        if (gaugeLayer != null && on == allowed(GaugeOverlay.LAYER_ID))
            gaugeLayer.setOn(on);
        else if (gaugeLayer != null && on)
            blocked++;
        final Context ctx = MapCompat.atakContext();
        if (blocked > 0 && ctx != null)
            Toast.makeText(ctx, blocked + (blocked == 1 ? " layer is" : " layers are")
                    + " still waiting to be allowed \u2014 turn those on one at a time",
                    Toast.LENGTH_LONG).show();
        updateLayerControls();
    }

    /** True when the operator has already allowed that layer's server, or off is asked. */
    private boolean allowed(String layerId) {
        return egress.isLayerEnabled(layerId);
    }

    /**
     * When the stations draw, and when their readings do -- each as how wide the map
     * is on screen, which is the number already on the scale bar.
     */
    /**
     * The zoom gate and the label zoom, in Feature Layer's shape.
     *
     * <p>Two controls each: <b>Use this zoom</b>, which takes whatever is on screen
     * right now, and a button carrying the current setting that opens the scale-bar
     * ladder. Set it by example when you are already looking at the right view, pick
     * a reading when you are not. Both quote ATAK's own scale bar, which is the
     * reference already on screen.
     */
    /**
     * Which stations reach the map at all. The same three the list filters by, so a
     * map showing only Red Flag and a list showing everything cannot happen.
     */
    private void buildStationsShowRow() {
        stationsShowRow.removeAllViews();
        showTile("All", StationOverlay.SHOW_ALL, 0);
        showTile("Flirting and Red Flag", StationOverlay.SHOW_WATCH,
                StationOverlay.NEAR);
        showTile("Red Flag only", StationOverlay.SHOW_RED, StationOverlay.CRITICAL);
        showTile("\u2605 Favorites only", StationOverlay.SHOW_FAVORITES, StationPage.STAR_ON);
    }

    private void showTile(String label, final int value, int color) {
        final boolean chosen = stationLayer.show() == value;
        final Button b = (Button) LayoutInflater.from(pluginContext)
                .inflate(R.layout.trend_chip, stationsShowRow, false);
        b.setText(label);
        b.setTextSize(12);
        b.setTextColor(chosen
                ? (color != 0 ? color
                        : pluginContext.getResources().getColor(R.color.state_on))
                : Color.WHITE);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stationLayer.setShow(value);
                updateLayerControls();
            }
        });
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = dp(4);
        b.setLayoutParams(lp);
        stationsShowRow.addView(b);
    }

    private void buildStationsGateRows() {
        gateRow(stationsGateRow, stationsGateText, "Stations",
                stationLayer.stationGate(), new Gate() {
                    @Override
                    public void set(double gsd) {
                        stationLayer.setStationGate(gsd);
                    }
                });
        gateRow(stationsLabelGateRow, stationsLabelGateText, "Readings",
                stationLayer.labelGate(), new Gate() {
                    @Override
                    public void set(double gsd) {
                        stationLayer.setLabelGate(gsd);
                    }
                });
    }

    private interface Gate {
        void set(double metersPerPixel);
    }

    /** Which layer's scale-bar arithmetic a gate row quotes; they are all the same. */
    private void buildGaugesGateRows() {
        gateRow(gaugesGateRow, gaugesGateText, "Gauges", gaugeLayer.gate(), new Gate() {
            @Override
            public void set(double gsd) {
                gaugeLayer.setGate(gsd);
            }
        });
        gateRow(gaugesLabelGateRow, gaugesLabelGateText, "Readings",
                gaugeLayer.labelGate(), new Gate() {
                    @Override
                    public void set(double gsd) {
                        gaugeLayer.setLabelGate(gsd);
                    }
                });
    }

    /** Feature Layer's ladder, as scale-bar readings in the operator's big unit. */
    private static final double[] GATE_BIG = { 0.25, 1, 5, 15, 50 };

    private void gateRow(LinearLayout row, final TextView readout, final String what,
            final double current, final Gate gate) {
        row.removeAllViews();
        row.addView(choiceTile("Use this zoom", false, new Runnable() {
            @Override
            public void run() {
                gate.set(stationLayer.resolution());
                updateLayerControls();
            }
        }));
        row.addView(choiceTile(gateSetting(current), true, new Runnable() {
            @Override
            public void run() {
                pickGate(what, gate);
            }
        }));
        readout.setText(gateText(current, what));
    }

    /** The ladder, as scale-bar readings, plus Always. */
    private void pickGate(final String what, final Gate gate) {
        final Context ctx = MapCompat.atakContext();
        if (ctx == null)
            return;
        final String[] labels = new String[GATE_BIG.length + 1];
        for (int i = 0; i < GATE_BIG.length; i++)
            labels[i] = gateName(i);
        labels[GATE_BIG.length] = "Always";
        new AlertDialog.Builder(ctx)
                .setTitle(what + " drawn when the scale bar reads")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        gate.set(which == GATE_BIG.length ? StationOverlay.ALWAYS_GATE
                                : stationLayer.gsdForBig(GATE_BIG[which]));
                        updateLayerControls();
                    }
                })
                .setNegativeButton(pluginContext.getString(R.string.close), null)
                .show();
    }

    private static String gateName(int i) {
        final double n = GATE_BIG[i];
        final String num = n == Math.floor(n) ? String.format(Locale.US, "%.0f", n)
                : String.format(Locale.US, "%.2f", n);
        return num + " " + ScaleBar.bigLabel() + " or closer";
    }

    /** What the button itself carries: the current setting, short. */
    private String gateSetting(double gate) {
        if (StationOverlay.isAlways(gate))
            return "Always";
        return ScaleBar.describe(gate * stationLayer.barPixels()) + " or closer";
    }

    private String gateText(double gate, String what) {
        final String bar = ScaleBar.text(mapView());
        if (StationOverlay.isAlways(gate))
            return what + " always drawn  \u00b7  scale bar now " + bar;
        return what + " drawn at " + ScaleBar.describe(gate * stationLayer.barPixels())
                + " or closer  \u00b7  scale bar now " + bar
                + (stationLayer.drawingNow(gate) ? "" : "  \u2014 hidden");
    }

    private static MapView mapView() {
        return MapView.getMapView();
    }

    /** One tile of a row of choices: the chosen one green, the Traffic convention. */
    private View choiceTile(String label, boolean chosen, final Runnable onPick) {
        final Button b = (Button) LayoutInflater.from(pluginContext)
                .inflate(R.layout.trend_chip, stationsOriginRow, false);
        b.setText(label);
        b.setTextSize(13);
        // Green text on a plain face for the chosen one, never a colored button.
        b.setTextColor(chosen
                ? pluginContext.getResources().getColor(R.color.state_on) : Color.WHITE);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onPick.run();
            }
        });
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = dp(4);
        b.setLayoutParams(lp);
        return b;
    }

    /**
     * How to read a station: the barb's own feathers at real speeds, then what the
     * three colors mean.
     *
     * <p>The examples are composed by the same code that draws the map, so the guide
     * cannot drift from what is on it. A legend redrawn by hand is a legend that is
     * eventually wrong.
     */
    private void buildStationsGuide() {
        if (stationsGuide.getChildCount() > 0 || stationLayer == null)
            return;
        stationsGuide.addView(guideNote(
                "The staff points at where the wind is coming from. Short feather 5, "
                        + "long feather 10, triangle 50 \u2014 added up."));
        stationsGuide.addView(guideRow(example(StationOverlay.NORMAL),
                "Calm", "no staff at all"));
        // The whole ladder, not a handful of examples: this is the chart a crew would
        // otherwise go and look up, and it is generated from the same arithmetic the
        // map draws with, so it cannot disagree with what is on screen.
        for (int kt = 5; kt <= 100; kt += 5)
            stationsGuide.addView(guideRow(barbExample(kt),
                    stationLayer.exampleSpeed(kt), feathers(kt)));
        stationsGuide.addView(guideNote(
                "Feathers are counted in knots, the way every station plot does it. "
                        + "The numbers beside a station are in your own unit."));

        stationsGuide.addView(guideHeading("What the color means"));
        stationsGuide.addView(guideRow(example(StationOverlay.NORMAL),
                "Below criteria", "neither humidity nor wind is there"));
        stationsGuide.addView(guideRow(example(StationOverlay.NEAR),
                "Flirting", "one of the two criteria is met"));
        stationsGuide.addView(guideRow(example(StationOverlay.CRITICAL),
                "Red Flag", "both are met at once"));

        // The source lives here rather than on the list page: there it was three
        // lines of credit above the fold, and in landscape that is most of the rows
        // the operator can see (2026-09-25).
        stationsGuide.addView(guideHeading("Where this comes from"));
        stationsGuide.addView(guideNote(pluginContext.getString(R.string.credit_stations)));
    }

    private Example example(final int stateColor) {
        return new Example() {
            @Override
            public String compose() {
                return stationLayer == null ? null
                        : stationLayer.exampleSymbol(stateColor);
            }
        };
    }

    private Example barbExample(final int knots) {
        return new Example() {
            @Override
            public String compose() {
                return stationLayer == null ? null : stationLayer.exampleBarb(knots);
            }
        };
    }

    /** "1 triangle, 1 long, 1 short" -- what is actually drawn at that speed. */
    private static String feathers(int knots) {
        final com.atakmap.android.atmosphere.data.WindBarb.Feathers f =
                com.atakmap.android.atmosphere.data.WindBarb.of(knots);
        final StringBuilder b = new StringBuilder();
        append(b, f.flags, "triangle", "triangles");
        append(b, f.fulls, "long", "long");
        append(b, f.halves, "short", "short");
        return b.toString();
    }

    private static void append(StringBuilder b, int n, String one, String many) {
        if (n <= 0)
            return;
        if (b.length() > 0)
            b.append(", ");
        b.append(n).append(' ').append(n == 1 ? one : many);
    }

    /**
     * Compose the legend's artwork off the main thread and fill it in as it lands.
     *
     * <p>Twenty-one barbs is twenty-one PNG encodes, twenty-one file writes and
     * twenty-one full-bitmap scans to trim them. Done where the row is built -- which
     * is the thread that draws the map -- that is seconds of nothing happening, and
     * the same mistake as the spot layer's inserts and the storm layer's icons
     * earlier today. The rows go up immediately with their text; the pictures arrive.
     */
    private final java.util.concurrent.ExecutorService guideWorker =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    private void art(final ImageView into, final Example example) {
        guideWorker.execute(new Runnable() {
            @Override
            public void run() {
                final String uri = example.compose();
                if (uri == null)
                    return;
                final android.graphics.Bitmap raw = android.graphics.BitmapFactory
                        .decodeFile(uri.replace("file://", ""));
                if (raw == null)
                    return;
                final android.graphics.Bitmap shown = trimmed(raw);
                // The file has no density of its own; without this Android rescales
                // it by the screen's, and the legend stops matching the map.
                shown.setDensity(android.graphics.Bitmap.DENSITY_NONE);
                // The view itself is the handler: no MapView reference is held here,
                // and a row that has gone away simply never gets its picture.
                into.post(new Runnable() {
                    @Override
                    public void run() {
                        into.setImageBitmap(shown);
                    }
                });
            }
        });
    }

    /** Something the legend needs drawn, composed only when the worker gets to it. */
    private interface Example {
        String compose();
    }

    /** One guide row: the real symbol on the left, what it means on the right. */
    private View guideRow(final Example example, String title, String detail) {
        final LinearLayout row = new LinearLayout(pluginContext);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(2), 0, dp(2));
        final ImageView art = new ImageView(pluginContext);
        art.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        // Drawn at the size the map draws it, pixel for pixel. Squeezed into a fixed
        // box it was a smudge, which is no use in a guide whose whole job is to let
        // you count feathers (operator, 2026-09-25).
        art.setScaleType(ImageView.ScaleType.CENTER);
        art.setMinimumWidth(dp(56));
        art.setMinimumHeight(dp(26));
        art(art, example);
        row.addView(art);
        final TextView t = new TextView(pluginContext);
        t.setTextColor(Color.WHITE);
        t.setTextSize(13);
        t.setPadding(dp(8), 0, 0, 0);
        t.setText(detail == null || detail.isEmpty() ? title : title + " \u2014 " + detail);
        row.addView(t);
        return row;
    }

    /** A small caps heading inside the guide, the pane's own convention. */
    private View guideHeading(String text) {
        final TextView t = new TextView(pluginContext);
        t.setTextColor(Color.WHITE);
        t.setTextSize(10);
        t.setAllCaps(true);
        t.setAlpha(0.6f);
        t.setPadding(0, dp(10), 0, dp(2));
        t.setText(text);
        return t;
    }

    /**
     * The same bitmap with its empty margin cut off.
     *
     * <p>A station icon is composed on a canvas big enough for a barb pointing any
     * direction and symmetric about the disc, so that the disc lands on the station.
     * On the map that is invisible. In a list it is most of the row: the examples all
     * point west, so the entire east half is blank and the rows sat a long way apart
     * (operator, 2026-09-25: "why so much buffer between the lines"). The pixels are
     * not resized, only the empty ones dropped, so the legend still matches the map.
     */
    private static android.graphics.Bitmap trimmed(android.graphics.Bitmap src) {
        final int w = src.getWidth(), h = src.getHeight();
        final int[] row = new int[w];
        int top = -1, bottom = -1, left = w, right = -1;
        for (int y = 0; y < h; y++) {
            src.getPixels(row, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x++) {
                if ((row[x] >>> 24) == 0)
                    continue;
                if (top < 0)
                    top = y;
                bottom = y;
                if (x < left)
                    left = x;
                if (x > right)
                    right = x;
            }
        }
        if (top < 0 || right < left)
            return src;                 // nothing drawn; hand it back untouched
        final int pad = 2;
        left = Math.max(0, left - pad);
        top = Math.max(0, top - pad);
        right = Math.min(w - 1, right + pad);
        bottom = Math.min(h - 1, bottom + pad);
        return android.graphics.Bitmap.createBitmap(src, left, top,
                right - left + 1, bottom - top + 1);
    }

    private View guideNote(String text) {
        final TextView t = new TextView(pluginContext);
        t.setTextColor(Color.WHITE);
        t.setTextSize(12);
        t.setAlpha(0.75f);
        t.setPadding(0, dp(4), 0, dp(2));
        t.setText(text);
        return t;
    }

    /** What the symbol's color means, from the map's own palette. */
    private void buildStationsLegend() {
        if (stationsLegend.getChildCount() > 0)
            return;
        // The Fire Weather Snooper's own legend, word for word.
        stationsLegend.addView(legendLine("Hitting Red Flag criteria",
                StationOverlay.CRITICAL));
        stationsLegend.addView(legendLine("Flirting with Red Flag criteria",
                StationOverlay.NEAR));
        stationsLegend.addView(legendLine("Below criteria", StationOverlay.NORMAL));
    }

    private void askToAllowSpotLayer() {
        final Context ctx = MapCompat.atakContext();
        if (ctx == null)
            return;
        new AlertDialog.Builder(ctx)
                .setTitle(pluginContext.getString(R.string.spot_layer_allow_title))
                .setMessage(pluginContext.getString(R.string.spot_layer_allow_text))
                .setPositiveButton(pluginContext.getString(R.string.allow),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                egress.setLayerEnabled(SpotOverlay.LAYER_ID, true);
                                turnSpotLayerOn();
                            }
                        })
                .setNegativeButton(pluginContext.getString(R.string.close), null)
                .show();
    }

    /**
     * NWS's own legend, drawn from the same art the map is using so the two can
     * never disagree: a letter for what the request is, a color for how far along.
     */
    private void buildSpotLegend() {
        if (spotLegend.getChildCount() > 0)
            return;
        final String[][] kinds = {
                { "W", "Wildfire" }, { "P", "Prescribed fire" }, { "M", "Marine" },
                { "H", "HAZMAT" }, { "S", "Search and rescue" }, { "O", "Other" } };
        final StringBuilder letters = new StringBuilder();
        for (String[] k : kinds) {
            if (letters.length() > 0)
                letters.append("   ");
            letters.append(k[0]).append(' ').append(k[1]);
        }
        spotLegend.addView(legendLine(letters.toString(), 0));
        spotLegend.addView(legendLine("Forecast issued", SpotOverlay.DONE));
        spotLegend.addView(legendLine("Update requested", SpotOverlay.WAITING));
        spotLegend.addView(legendLine("Waiting for the forecast", SpotOverlay.PENDING));
    }

    /** One legend row: a swatch in the status color, or none for the letter key. */
    private View legendLine(String text, int color) {
        final LinearLayout row = new LinearLayout(pluginContext);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(3), 0, dp(3));
        if (color != 0) {
            final View swatch = new View(pluginContext);
            swatch.setBackgroundColor(color);
            final LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(14), dp(14));
            sp.rightMargin = dp(8);
            sp.topMargin = dp(2);
            row.addView(swatch, sp);
        }
        final TextView t = new TextView(pluginContext);
        t.setText(text);
        t.setTextSize(12);
        t.setTextColor(Color.WHITE);
        row.addView(t);
        return row;
    }

    /**
     * A spot request tapped on the map: bring its page forward and open the forecast.
     * The details receiver routes here rather than showing the request's own fields,
     * which the row already carries (operator, 2026-09-25: "when i click on it from
     * the map it should bring up the forecast").
     */
    /**
     * Jump to the page that lists what a layer is drawing.
     *
     * <p>A layer's settings and its list are two ends of the same thing, and the only
     * way between them was to know which page it was on and swipe there (operator,
     * 2026-09-25: "can we have a hot link from the layer to the list").
     */
    private void openPage(View page) {
        if (page == null)
            return;
        for (int i = 0; i < pages.length; i++)
            if (pages[i] == page) {
                pager.setCurrentItem(i, true);
                return;
            }
    }

    public void openSpot(final String spotId) {
        if (spotId == null || spotId.isEmpty())
            return;
        for (int i = 0; i < pages.length; i++)
            if (pages[i] == spotPage.view()) {
                pager.setCurrentItem(i, false);
                break;
            }
        if (host != null)
            host.show();
        spotPage.showById(spotId);
    }

    /** The egress gate: the host, by name, once. */
    private void askToAllowRadar() {
        final Context ctx = MapCompat.atakContext();
        if (ctx == null)
            return;
        new AlertDialog.Builder(ctx)
                .setTitle(pluginContext.getString(R.string.radar_allow_title))
                .setMessage(pluginContext.getString(R.string.radar_allow_text))
                .setPositiveButton(pluginContext.getString(R.string.allow),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                egress.setLayerEnabled(RadarOverlay.LAYER_ID, true);
                                turnRadarOn();
                            }
                        })
                .setNegativeButton(pluginContext.getString(R.string.close), null)
                .show();
    }

    /** One time-enabled layer at a time (WxReport's rule): the scrubber is one strip. */
    private void turnRadarOn() {
        if (wind != null && wind.isOn())
            wind.setOn(false);
        if (smoke != null && smoke.isOn())
            smoke.setOn(false);
        if (radar != null) {
            radar.setOn(true);
            setWhenTimes(radarTimes(), radar.frameIndex(),
                    Math.max(0, radarFrames.size() - 1));
        }
        updateLayerControls();
    }

    private void turnWindOn() {
        if (radar != null && radar.isOn())
            radar.setOn(false);
        if (smoke != null && smoke.isOn())
            smoke.setOn(false);
        if (wind != null) {
            wind.setOn(true);
            setWhenTimes(windTimes(), wind.hourIndex(), 0);
        }
        updateLayerControls();
    }

    /** One time-enabled layer at a time, because they share the one strip. */
    private void turnSmokeOn() {
        if (radar != null && radar.isOn())
            radar.setOn(false);
        if (wind != null && wind.isOn())
            wind.setOn(false);
        if (smoke != null) {
            smoke.setOn(true);
            setWhenTimes(smokeTimes(), smoke.hourIndex(), 0);
        }
        updateLayerControls();
    }

    private void askToAllowWarnings() {
        final Context ctx = MapCompat.atakContext();
        if (ctx == null)
            return;
        new AlertDialog.Builder(ctx)
                .setTitle(pluginContext.getString(R.string.warn_allow_title))
                .setMessage(pluginContext.getString(R.string.warn_allow_text))
                .setPositiveButton(pluginContext.getString(R.string.allow),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                egress.setLayerEnabled(WarningsOverlay.LAYER_ID, true);
                                if (warnings != null)
                                    warnings.setOn(true);
                                updateLayerControls();
                            }
                        })
                .setNegativeButton(pluginContext.getString(R.string.close), null)
                .show();
    }

    private void askToAllowAir() {
        final Context ctx = MapCompat.atakContext();
        if (ctx == null)
            return;
        new AlertDialog.Builder(ctx)
                .setTitle(pluginContext.getString(R.string.air_allow_title))
                .setMessage(pluginContext.getString(R.string.air_allow_text))
                .setPositiveButton(pluginContext.getString(R.string.allow),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                egress.setLayerEnabled(AirQualityOverlay.LAYER_ID, true);
                                if (air != null)
                                    air.setOn(true);
                                updateLayerControls();
                            }
                        })
                .setNegativeButton(pluginContext.getString(R.string.close), null)
                .show();
    }

    private void askToAllowSmoke() {
        final Context ctx = MapCompat.atakContext();
        if (ctx == null)
            return;
        new AlertDialog.Builder(ctx)
                .setTitle(pluginContext.getString(R.string.smoke_allow_title))
                .setMessage(pluginContext.getString(R.string.smoke_allow_text))
                .setPositiveButton(pluginContext.getString(R.string.allow),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                egress.setLayerEnabled(SmokeOverlay.LAYER_ID, true);
                                turnSmokeOn();
                            }
                        })
                .setNegativeButton(pluginContext.getString(R.string.close), null)
                .show();
    }

    private void askToAllowWind() {
        final Context ctx = MapCompat.atakContext();
        if (ctx == null)
            return;
        new AlertDialog.Builder(ctx)
                .setTitle(pluginContext.getString(R.string.wind_allow_title))
                .setMessage(pluginContext.getString(R.string.wind_allow_text))
                .setPositiveButton(pluginContext.getString(R.string.allow),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                egress.setLayerEnabled(WindOverlay.LAYER_ID, true);
                                turnWindOn();
                            }
                        })
                .setNegativeButton(pluginContext.getString(R.string.close), null)
                .show();
    }

    private void updateLayerControls() {
        final boolean radarOn = radar != null && radar.isOn();
        final boolean windOn = wind != null && wind.isOn();
        radarToggle.setText(radarOn ? R.string.radar_on : R.string.radar_off);
        radarToggle.setTextColor(pluginContext.getResources().getColor(
                radarOn ? R.color.state_on : R.color.state_off));
        windToggle.setText(windOn ? R.string.wind_on : R.string.wind_off);
        windToggle.setTextColor(pluginContext.getResources().getColor(
                windOn ? R.color.state_on : R.color.state_off));
        final boolean smokeOn = smoke != null && smoke.isOn();
        smokeToggle.setText(smokeOn ? R.string.smoke_on : R.string.smoke_off);
        smokeToggle.setTextColor(pluginContext.getResources().getColor(
                smokeOn ? R.color.state_on : R.color.state_off));
        smokeExpand.setVisibility(smokeOn ? View.VISIBLE : View.GONE);
        smokeExpand.setRotation(smokeOpen ? 180f : 0f);
        smokeSettings.setVisibility(smokeOn && smokeOpen ? View.VISIBLE : View.GONE);
        if (smokeOn) {
            updateSmokeScale();
            updateSmokeHeightRow();
            updateSmokeReading();
        }
        final boolean stationsOn = stationLayer != null && stationLayer.isOn();
        stationsToggle.setText(stationsOn ? R.string.stations_on : R.string.stations_off);
        stationsToggle.setTextColor(pluginContext.getResources().getColor(
                stationsOn ? R.color.state_on : R.color.state_off));
        stationsExpand.setVisibility(stationsOn ? View.VISIBLE : View.GONE);
        stationsExpand.setRotation(stationsOpen ? 180f : 0f);
        stationsSettings.setVisibility(stationsOn && stationsOpen ? View.VISIBLE : View.GONE);
        if (stationsOn) {
            final boolean withLabels = stationLayer.hasLabels();
            stationsLabels.setText(withLabels ? "Readings and names  ON" : "Readings and names  OFF");
            stationsLabels.setTextColor(pluginContext.getResources().getColor(
                    withLabels ? R.color.state_on : R.color.state_off));
            buildStationsOriginRow();
            buildStationsDistanceRow();
            buildStationsShowRow();
            buildStationsGateRows();
            buildStationsLegend();
            stationsGuideExpand.setRotation(stationsGuideOpen ? 180f : 0f);
            stationsGuide.setVisibility(stationsGuideOpen ? View.VISIBLE : View.GONE);
            if (stationsGuideOpen)
                buildStationsGuide();
            stationsBasis.setText(RedFlag.basis());
        }

        final boolean gaugesOn = gaugeLayer != null && gaugeLayer.isOn();
        gaugesToggle.setText(gaugesOn ? R.string.gauges_on : R.string.gauges_off);
        gaugesToggle.setTextColor(pluginContext.getResources().getColor(
                gaugesOn ? R.color.state_on : R.color.state_off));
        gaugesExpand.setVisibility(gaugesOn ? View.VISIBLE : View.GONE);
        gaugesExpand.setRotation(gaugesOpen ? 180f : 0f);
        gaugesSettings.setVisibility(gaugesOn && gaugesOpen ? View.VISIBLE : View.GONE);
        if (gaugesOn) {
            buildGaugesOriginRow();
            buildGaugesDistanceRow();
            buildGaugesShowRow();
            buildGaugesGateRows();
            final boolean gl = gaugeLayer.hasLabels();
            gaugesLabels.setText(gl ? "Readings and names  ON" : "Readings and names  OFF");
            gaugesLabels.setTextColor(pluginContext.getResources().getColor(
                    gl ? R.color.state_on : R.color.state_off));
            buildGaugesLegend();
        }
        final boolean spotOn = spotLayer != null && spotLayer.isOn();
        spotToggle.setText(spotOn ? R.string.spot_layer_on : R.string.spot_layer_off);
        spotToggle.setTextColor(pluginContext.getResources().getColor(
                spotOn ? R.color.state_on : R.color.state_off));
        spotExpand.setVisibility(spotOn ? View.VISIBLE : View.GONE);
        spotExpand.setRotation(spotOpen ? 180f : 0f);
        spotSettings.setVisibility(spotOn && spotOpen ? View.VISIBLE : View.GONE);
        if (spotOn) {
            final boolean recentOnly = spotLayer.isRecentOnly();
            // Age, not status. Nearly every request is filled within the hour, so
            // hiding filled ones hid today's forecast for an active fire -- which is
            // the one a crew is looking for (operator, 2026-09-25).
            spotOpenOnly.setText(recentOnly
                    ? "Last 3 days only  ON" : "Last 3 days only  OFF");
            spotOpenOnly.setTextColor(pluginContext.getResources().getColor(
                    recentOnly ? R.color.state_on : R.color.state_off));
            buildSpotLegend();
        }
        final boolean airOn = air != null && air.isOn();
        airToggle.setText(airOn ? R.string.air_on : R.string.air_off);
        airToggle.setTextColor(pluginContext.getResources().getColor(
                airOn ? R.color.state_on : R.color.state_off));
        airExpand.setVisibility(airOn ? View.VISIBLE : View.GONE);
        airExpand.setRotation(airOpen ? 180f : 0f);
        airSettings.setVisibility(airOn && airOpen ? View.VISIBLE : View.GONE);
        if (airOn)
            updateAirReading();
        final boolean warnOn = warnings != null && warnings.isOn();
        warnToggle.setText(warnOn ? R.string.warn_on : R.string.warn_off);
        warnToggle.setTextColor(pluginContext.getResources().getColor(
                warnOn ? R.color.state_on : R.color.state_off));
        warnExpand.setVisibility(warnOn ? View.VISIBLE : View.GONE);
        warnExpand.setRotation(warnOpen ? 180f : 0f);
        warnSettings.setVisibility(warnOn && warnOpen ? View.VISIBLE : View.GONE);
        if (warnOn) {
            updateWarnGroups();
            updateWarnHere();
        }
        // A layer with nothing on the map has no settings worth a chevron.
        final boolean tropicalOn = tropical != null && tropical.isOn();
        tropicalToggle.setText(tropicalOn ? R.string.tropical_on : R.string.tropical_off);
        tropicalToggle.setTextColor(pluginContext.getResources().getColor(
                tropicalOn ? R.color.state_on : R.color.state_off));
        tropicalExpand.setVisibility(tropicalOn ? View.VISIBLE : View.GONE);
        tropicalExpand.setRotation(tropicalOpen ? 180f : 0f);
        tropicalSettings.setVisibility(tropicalOn && tropicalOpen ? View.VISIBLE : View.GONE);
        radarExpand.setVisibility(radarOn ? View.VISIBLE : View.GONE);
        windExpand.setVisibility(windOn ? View.VISIBLE : View.GONE);
        radarExpand.setRotation(radarOpen ? 180f : 0f);
        windExpand.setRotation(windOpen ? 180f : 0f);
        radarSettings.setVisibility(radarOn && radarOpen ? View.VISIBLE : View.GONE);
        windSettings.setVisibility(windOn && windOpen ? View.VISIBLE : View.GONE);
        // The legend explains what is on the map, so it appears with the layer.
        windScaleHost.setVisibility(windOn ? View.VISIBLE : View.GONE);
        windLevelBlock.setVisibility(windOn ? View.VISIBLE : View.GONE);
        if (windOn) {
            updateWindReading();
            updateWindScale();
            // The row is built in the constructor, before the stored unit has been
            // read, so the first paint of it happens here.
            updateWindUnitRow();
            updateWindLevel();
        }
        if (windOn)
            hostScrubberIn(windSettings);
        else if (smokeOn)
            hostScrubberIn(smokeSettings);
        else if (radarOn)
            hostScrubberIn(radarSettings);
        scrubber.setVisibility(radarOn || windOn || smokeOn ? View.VISIBLE : View.GONE);
        creditTheLayers(radarOn, windOn);
    }

    /**
     * Put the time strip under the layer that owns it. One strip, not one per layer:
     * only one time-enabled layer can be on at a time (WxReport's rule), so moving
     * the one view beats keeping two in step. It goes first in the block, above that
     * layer's own controls.
     */
    private void hostScrubberIn(LinearLayout container) {
        final ViewParent parent = scrubber.getParent();
        if (parent == container)
            return;
        if (parent instanceof ViewGroup)
            ((ViewGroup) parent).removeView(scrubber);
        container.addView(scrubber, 0);
    }

    private void rememberFold(String key, boolean open) {
        final SharedPreferences p = MapCompat.prefs();
        if (p != null)
            p.edit().putBoolean(key, open).apply();
    }

    /**
     * Credit what this page actually draws. It used to show the forecast source's
     * line, because the one attribution view in the pane sat on this page while the
     * forecast wrote into it -- so a page of NOAA model wind and an NWS radar service
     * was crediting the forecast API, which serves neither (operator, 2026-09-22:
     * "yeah i guess make it accurate"). The host comes from the constant the fetch
     * uses, so the credit cannot drift from where the data came from.
     */
    private void creditTheLayers(boolean radarOn, boolean windOn) {
        final StringBuilder out = new StringBuilder();
        if (windOn)
            out.append(pluginContext.getString(R.string.credit_wind, NomadsWind.HOST));
        if (smoke != null && smoke.isOn()) {
            if (out.length() > 0)
                out.append('\n');
            out.append(pluginContext.getString(R.string.credit_smoke, SmokeOverlay.HOST));
        }
        if (radarOn) {
            if (out.length() > 0)
                out.append('\n');
            out.append(pluginContext.getString(R.string.credit_radar, RadarOverlay.HOST));
        }
        if (air != null && air.isOn()) {
            if (out.length() > 0)
                out.append('\n');
            out.append(pluginContext.getString(R.string.credit_air, AirQualityOverlay.HOST));
        }
        if (warnings != null && warnings.isOn()) {
            if (out.length() > 0)
                out.append('\n');
            out.append(pluginContext.getString(R.string.credit_warnings, WarningsOverlay.HOST));
        }
        if (tropical != null && tropical.isOn()) {
            if (out.length() > 0)
                out.append('\n');
            out.append(pluginContext.getString(R.string.credit_tropical, TropicalOverlay.HOST));
        }
        layersAttribution.setText(out.toString());
    }

    /**
     * Wind speed by the unit a crew names it in, picked where they are looking at it
     * (operator, 2026-09-22: "on wind can we have kt and mph as option on the scale
     * on the layers"). Each button sets the whole unit system rather than a private
     * wind unit, so there is one answer to "what units am I in" and the icon row's
     * button never disagrees: knots is the aviation system, miles per hour imperial,
     * kilometers per hour metric. The third is there because the system has three; a
     * row where the live setting matched no button would be worse than a spare.
     */
    private void buildWindUnitRow() {
        final UnitSystem[] order = { UnitSystem.IMPERIAL, UnitSystem.AVIATION, UnitSystem.METRIC };
        for (final UnitSystem system : order) {
            final Button b = (Button) LayoutInflater.from(pluginContext)
                    .inflate(R.layout.trend_chip, windUnitRow, false);
            b.setText(Units.displayUnit(Quantity.SPEED, system));
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    applyUnits(system);
                }
            });
            b.setTag(system);
            windUnitRow.addView(b);
        }
        updateWindUnitRow();
    }

    private void updateWindUnitRow() {
        for (int i = 0; i < windUnitRow.getChildCount(); i++) {
            final View child = windUnitRow.getChildAt(i);
            if (!(child instanceof Button))
                continue;
            ((Button) child).setTextColor(child.getTag() == units
                    ? pluginContext.getResources().getColor(R.color.state_on) : Color.WHITE);
        }
    }

    /** Every route to a unit change goes through here, so nothing gets left behind. */
    private void applyUnits(UnitSystem system) {
        units = system;
        final SharedPreferences p = MapCompat.prefs();
        if (p != null)
            p.edit().putString(PREF_UNITS, units.name()).apply();
        updateUnitsButton();
        updateWindScale();
        updateWindUnitRow();
        relabelWindLevelRows();
        updateWindLevel();
        // A unit change is a display change: re-render, never re-fetch.
        render();
    }

    /**
     * What the wind is doing at the pane's own point, in the operator's own units.
     *
     * <p>Read straight out of the grid the particles are flying on, so it costs a
     * bilinear interpolation and never a request. It is the point mode's point, which
     * means "Pick a point" is already the tap-the-map-and-read-it control; nothing new
     * to learn and nothing new to get in the way of panning.
     */
    private void updateWindReading() {
        final GeoPoint p = point();
        final float[] uv = wind == null || p == null ? null
                : wind.readingAt(p.getLatitude(), p.getLongitude());
        if (uv == null) {
            // Silence would read as calm. Say which it is.
            windReading.setText(wind != null && wind.isOn() && p != null
                    ? R.string.wind_here_unknown : R.string.empty);
            return;
        }
        final double speed = Math.hypot(uv[0], uv[1]);
        // Meteorological convention: the wind is named for where it comes FROM, which
        // is the opposite of the vector it blows along.
        double from = Math.toDegrees(Math.atan2(-uv[0], -uv[1]));
        if (from < 0)
            from += 360;
        windReading.setText(pluginContext.getString(R.string.wind_here,
                Units.format(Quantity.SPEED, speed, units),
                Math.round(from), Units.degreesToCompass(from)));
    }

    /**
     * Paint the height that is being drawn, and say how fine the wind is there.
     * Ten meters up to the jet, a ladder rather than a scale because that is what
     * the models carry: two heights above the ground and the standard pressure
     * surfaces above them.
     */
    private void updateWindLevel() {
        if (wind == null)
            return;
        final int picked = wind.levelIndex();
        for (int i = 0; i < windLevelRows.getChildCount(); i++) {
            final View row = windLevelRows.getChildAt(i);
            if (!(row instanceof LinearLayout))
                continue;
            final LinearLayout cells = (LinearLayout) row;
            for (int j = 0; j < cells.getChildCount(); j++) {
                final View cell = cells.getChildAt(j);
                if (!(cell instanceof Button) || !(cell.getTag() instanceof Integer))
                    continue;
                ((Button) cell).setTextColor((Integer) cell.getTag() == picked
                        ? pluginContext.getResources().getColor(R.color.state_on)
                        : Color.WHITE);
            }
        }
    }

    /**
     * One button per height the models carry (operator, 2026-09-22: "lets just do
     * some presets on the wind height"). It began as a slider and the slider was the
     * wrong control here: it lives on a page of a ViewPager inside a scroller, so a
     * finger meaning to scroll the page moved the wind to the jet stream instead, and
     * eight discrete levels were never a scale to begin with. Buttons say what the
     * choices are without being touched, which a slider cannot.
     *
     * <p>Four to a row, weighted so the two rows line up, and the labels are the
     * heights themselves rather than the pressure surfaces they come from.
     */
    private void buildWindLevelRows() {
        final NomadsWind.Level[] all = NomadsWind.Level.values();
        final int perRow = 4;
        LinearLayout row = null;
        for (int i = 0; i < all.length; i++) {
            if (i % perRow == 0) {
                row = new LinearLayout(pluginContext);
                row.setOrientation(LinearLayout.HORIZONTAL);
                windLevelRows.addView(row, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
            }
            final int index = i;
            final Button b = (Button) LayoutInflater.from(pluginContext)
                    .inflate(R.layout.trend_chip, row, false);
            b.setText(all[i].shortLabel(units == UnitSystem.METRIC));
            // The button is a height on a small target; spoken, it is the whole name,
            // with the pressure surface for anyone in aviation units.
            b.setContentDescription(all[i].label(units == UnitSystem.METRIC,
                    units == UnitSystem.AVIATION));
            b.setTextSize(13);
            b.setTag(index);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (wind == null)
                        return;
                    wind.setLevelIndex(index);
                    updateWindLevel();
                }
            });
            // Even columns: each cell takes a quarter of the row whatever its text.
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = dp(4);
            lp.topMargin = dp(4);
            b.setLayoutParams(lp);
            row.addView(b);
        }
    }

    /** The preset labels carry a unit, so a unit change relabels them. */
    private void relabelWindLevelRows() {
        final NomadsWind.Level[] all = NomadsWind.Level.values();
        for (int i = 0; i < windLevelRows.getChildCount(); i++) {
            final View r = windLevelRows.getChildAt(i);
            if (!(r instanceof LinearLayout))
                continue;
            final LinearLayout cells = (LinearLayout) r;
            for (int j = 0; j < cells.getChildCount(); j++) {
                final View cell = cells.getChildAt(j);
                if (!(cell instanceof Button) || !(cell.getTag() instanceof Integer))
                    continue;
                final int index = (Integer) cell.getTag();
                if (index >= 0 && index < all.length) {
                    ((Button) cell).setText(all[index].shortLabel(units == UnitSystem.METRIC));
                    cell.setContentDescription(all[index].label(units == UnitSystem.METRIC,
                            units == UnitSystem.AVIATION));
                }
            }
        }
    }

    /**
     * The two heights the model carries smoke at, as two presets side by side: the
     * ground, which is what a crew breathes, and the whole sky, which is what they see
     * and what the sun and the aircraft come through.
     */
    private void buildSmokeHeightRow() {
        for (final NomadsSmoke.Height h : NomadsSmoke.Height.values()) {
            final Button b = (Button) LayoutInflater.from(pluginContext)
                    .inflate(R.layout.trend_chip, smokeHeightRow, false);
            b.setText(h == NomadsSmoke.Height.GROUND ? R.string.smoke_ground : R.string.smoke_sky);
            b.setTextSize(13);
            b.setTag(h);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (smoke == null)
                        return;
                    smoke.setHeight(h);
                    updateSmokeHeightRow();
                    updateSmokeScale();
                    updateSmokeReading();
                }
            });
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = dp(4);
            lp.topMargin = dp(4);
            b.setLayoutParams(lp);
            smokeHeightRow.addView(b);
        }
    }

    private void updateSmokeHeightRow() {
        final NomadsSmoke.Height picked = smoke == null ? null : smoke.height();
        for (int i = 0; i < smokeHeightRow.getChildCount(); i++) {
            final View cell = smokeHeightRow.getChildAt(i);
            if (cell instanceof Button)
                ((Button) cell).setTextColor(cell.getTag() == picked
                        ? pluginContext.getResources().getColor(R.color.state_on)
                        : Color.WHITE);
        }
    }

    /**
     * The legend for the height on the map: the air quality bands with their numbers
     * near the ground, light to dense for the sky, which has no scale a crew reads
     * in numbers.
     */
    private void updateSmokeScale() {
        final NomadsSmoke.Height h = smoke == null ? NomadsSmoke.Height.GROUND : smoke.height();
        smokeScale.setBands(h.legendColors(), h.legendBreaks(), h.unit);
        if (h == NomadsSmoke.Height.GROUND)
            smokeScale.setEnds(null, null);
        else
            smokeScale.setEnds(pluginContext.getString(R.string.smoke_light),
                    pluginContext.getString(R.string.smoke_dense));
    }

    /**
     * The smoke at the pane's own point, read out of the picture already on the map.
     * Near the ground it is a number and what the number means for breathing; for
     * the sky it is words only.
     */
    private void updateSmokeReading() {
        if (smoke == null || !smoke.isOn()) {
            smokeReading.setText(R.string.empty);
            return;
        }
        final GeoPoint p = point();
        final NomadsSmoke.Height h = smoke.height();
        final float v = p == null ? Float.NaN
                : smoke.readingAt(p.getLatitude(), p.getLongitude());
        String line;
        if (p == null)
            line = "";
        else if (Float.isNaN(v))
            // Silence would read as clean air. Say which it is.
            line = pluginContext.getString(R.string.wind_here_unknown);
        else if (h == NomadsSmoke.Height.GROUND && h.band(v) >= 0)
            line = pluginContext.getString(R.string.smoke_here_amount,
                    v >= 10 ? String.valueOf(Math.round(v))
                            : String.format(Locale.US, "%.1f", v),
                    h.unit, h.words(v));
        else
            line = pluginContext.getString(R.string.smoke_here, h.words(v));
        if (smoke.isCropped())
            line = line.isEmpty() ? pluginContext.getString(R.string.smoke_cropped)
                    : line + "\n" + pluginContext.getString(R.string.smoke_cropped);
        smokeReading.setText(line);
    }

    /**
     * A distance a crew can picture, for the position-rounding choices. Decimal places
     * are a way of storing a number, not a thing anybody can stand in.
     */
    private String roughly(int meters) {
        if (units == UnitSystem.METRIC)
            return meters >= 1000 ? Math.round(meters / 1000.0) + " km" : meters + " m";
        final double feet = meters / 0.3048;
        if (feet >= 5280)
            return Math.round(feet / 5280) + " mi";
        if (feet >= 900)
            return (Math.round(feet / 528) / 10.0) + " mi";
        return Math.round(feet / 3) + " yd";
    }

    /** The legend's numbers follow the operator's unit, like every other speed. */
    private void updateWindScale() {
        final float[] breaks = new float[WindScaleView.bandCount() - 1];
        for (int i = 0; i < breaks.length; i++)
            breaks[i] = (float) Units.toDisplay(Quantity.SPEED, WindScaleView.bandEdgeMs(i), units);
        windScale.setScale(breaks, Units.displayUnit(Quantity.SPEED, units));
    }

    /** "Latest, 4:40 pm" or "18 min ago, 4:22 pm", in the phone's zone. */
    private String frameLabel(int index, String time) {
        if (time == null || index < 0)
            return "No frame yet";
        final long t = com.atakmap.android.atmosphere.data.IsoTime.parse(time);
        if (t <= 0)
            return time;
        final String when = new SimpleDateFormat("h:mm a", Locale.US).format(new Date(t))
                .replace("AM", "am").replace("PM", "pm");
        final boolean latest = index == radarFrames.size() - 1;
        return (latest ? "Latest" : capitalize(relativeTime(t))) + ", " + when;
    }

    /** The pane left the screen: an armed pick must not keep the map's tap listeners. */
    public void onClosed() {
        disarmPick();
        updateModeIcons();
    }

    /** The plugin is going away: stop the spot page's worker and its fetches. */
    public void dispose() {
        onClosed();
        spotPage.dispose();
    }

    private void loadSelectedSource(SharedPreferences prefs) {
        final String storedId = prefs == null ? null : prefs.getString(PREF_SOURCE, null);
        int index = 0;
        for (int i = 0; i < sources.size(); i++) {
            if (sources.get(i).id.equals(storedId)) {
                index = i;
                break;
            }
        }
        if (!sources.isEmpty())
            selected = sources.get(index);
    }

    /**
     * The source picker: a single-choice dialog on ATAK's own context. Never a
     * Spinner: its dropdown is a Dialog built from the context that inflated the
     * view, and on the plugin context that is a BadTokenException that kills ATAK
     * (plugin UI standard, CLAUDE.md).
     */
    private void showSourceDialog() {
        if (sources.isEmpty())
            return;
        final Context ctx = MapCompat.atakContext();
        if (ctx == null)
            return;
        final String[] names = sourceNames();
        final int checked = selected == null ? -1 : sources.indexOf(selected);
        new AlertDialog.Builder(ctx)
                .setTitle(pluginContext.getString(R.string.source_title))
                .setSingleChoiceItems(names, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        d.dismiss();
                        if (which < 0 || which >= sources.size())
                            return;
                        selected = sources.get(which);
                        final SharedPreferences p = MapCompat.prefs();
                        if (p != null)
                            p.edit().putString(PREF_SOURCE, selected.id).apply();
                        snapshot = null;
                        refresh(false);
                    }
                })
                .setNegativeButton(pluginContext.getString(R.string.close), null)
                .show();
    }

    /** The gear: everything that is not the readout itself. */
    private void showSettingsDialog() {
        final Context ctx = MapCompat.atakContext();
        if (ctx == null)
            return;
        final String[] items = {
                pluginContext.getString(R.string.source_title) + ": "
                        + (selected == null ? pluginContext.getString(R.string.no_sources)
                                : sourceName(selected)),
                pluginContext.getString(R.string.sources_title),
                pluginContext.getString(R.string.variables_title),
                pluginContext.getString(R.string.privacy_title),
        };
        new AlertDialog.Builder(ctx)
                .setTitle(pluginContext.getString(R.string.settings_title))
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        switch (which) {
                            case 0: showSourceDialog(); break;
                            case 1: showSourcesDialog(); break;
                            case 2: showVariablesDialog(); break;
                            default: showPrivacyDialog(); break;
                        }
                    }
                })
                .setNegativeButton(pluginContext.getString(R.string.close), null)
                .show();
    }

    private String[] sourceNames() {
        final String[] names = new String[sources.size()];
        for (int i = 0; i < sources.size(); i++)
            names[i] = sourceName(sources.get(i));
        return names;
    }

    /** External (operator-dropped) definitions are marked, as the Sources dialog marks them. */
    private static String sourceName(WxSourceDef def) {
        return def.origin == WxSourceDef.Origin.EXTERNAL
                ? def.displayName + " *" : def.displayName;
    }

    private void wireButtons() {
        refreshButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refresh(true);
            }
        });

        modeSelf.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setMode(PointMode.SELF);
            }
        });

        modeCenter.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setMode(PointMode.CENTER);
            }
        });

        modePick.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                armPick();
            }
        });

        favoritesButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showFavoritesDialog();
            }
        });

        unitsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final UnitSystem[] all = UnitSystem.values();
                applyUnits(all[(units.ordinal() + 1) % all.length]);
            }
        });

        wideButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (host != null)
                    host.toggleWide();
            }
        });

        settingsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSettingsDialog();
            }
        });
    }

    // ---- the point ---------------------------------------------------------------

    private void setMode(PointMode m) {
        disarmPick();
        mode = m;
        if (m != PointMode.FAVORITE)
            favorite = null;
        final SharedPreferences p = MapCompat.prefs();
        if (p != null) {
            final SharedPreferences.Editor e = p.edit().putString(PREF_MODE, m.name());
            if (favorite == null)
                e.remove(PREF_FAVORITE);
            else
                e.putString(PREF_FAVORITE, favorite.name);
            if (pickedPoint == null)
                e.remove(PREF_PICKED);
            else
                e.putString(PREF_PICKED, pickedPoint.getLatitude() + "," + pickedPoint.getLongitude());
            e.apply();
        }
        updateModeIcons();
        snapshot = null;
        refresh(false);
    }

    private void setFavorite(Favorites.Place place) {
        favorite = place;
        setMode(place == null ? PointMode.CENTER : PointMode.FAVORITE);
    }

    /** The icon for the point in use is green; an armed pick is green until the tap. */
    private void updateModeIcons() {
        final int on = pluginContext.getResources().getColor(R.color.state_on);
        modeSelf.setColorFilter(mode == PointMode.SELF ? on : Color.WHITE);
        modeCenter.setColorFilter(mode == PointMode.CENTER ? on : Color.WHITE);
        modePick.setColorFilter(pickArmed || mode == PointMode.PICKED ? on : Color.WHITE);
        favoritesButton.setColorFilter(mode == PointMode.FAVORITE ? on : Color.WHITE);
    }

    /**
     * The row is too narrow for "Imperial", so the button wears the unit that tells
     * the three systems apart: Celsius for metric, Fahrenheit for imperial, knots
     * for aviation, which is imperial in every way but wind, distance and pressure.
     * It read "\u00b0C" in aviation until 2026-09-22, which is the one thing it must
     * never do: say Celsius while the readout is in Fahrenheit.
     */
    private void updateUnitsButton() {
        final String face;
        switch (units) {
            case METRIC:
                face = "\u00b0C";
                break;
            case AVIATION:
                face = "kt";
                break;
            default:
                face = "\u00b0F";
                break;
        }
        unitsButton.setText(face);
        unitsButton.setContentDescription(units.label());
    }

    private GeoPoint point() {
        switch (mode) {
            case SELF: return MapCompat.selfPoint();
            case PICKED: return pickedPoint;
            case FAVORITE: return favorite == null ? null
                    : new GeoPoint(favorite.latitude, favorite.longitude);
            default: return MapCompat.mapCenter();
        }
    }

    /** What the position line calls the point. */
    private String modeLabel() {
        switch (mode) {
            case SELF: return pluginContext.getString(R.string.self_position);
            case PICKED: return pluginContext.getString(R.string.picked_point);
            case FAVORITE: return "\u2605 " + (favorite == null ? "" : favorite.name);
            default: return pluginContext.getString(R.string.map_center);
        }
    }

    private static GeoPoint parsePoint(String s) {
        if (s == null)
            return null;
        final String[] parts = s.split(",");
        if (parts.length != 2)
            return null;
        try {
            final GeoPoint p = new GeoPoint(Double.parseDouble(parts[0].trim()),
                    Double.parseDouble(parts[1].trim()));
            return p.isValid() ? p : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Pick a point: the next map tap is the point (WxReport's Map Lock). The map's
     * click listeners are pushed and ours put in front, and popped again on the tap,
     * on a second Pick tap, or when the pane closes, so an armed pick never outlives
     * the pane and never eats a tap meant for ATAK.
     */
    private void armPick() {
        if (pickArmed) {
            disarmPick();
            updateModeIcons();
            statusText.setText("");
            return;
        }
        final MapView mv = MapView.getMapView();
        if (mv == null)
            return;
        pickArmed = true;
        updateModeIcons();
        statusText.setTextColor(pluginContext.getResources().getColor(R.color.state_on));
        statusText.setText(pluginContext.getString(R.string.pick_prompt));
        final MapEventDispatcher d = mv.getMapEventDispatcher();
        d.pushListeners();
        d.clearListeners(MapEvent.MAP_CLICK);
        d.clearListeners(MapEvent.ITEM_CLICK);
        pickListener = new MapEventDispatcher.MapEventDispatchListener() {
            @Override
            public void onMapEvent(MapEvent event) {
                final PointF pf = event.getPointF();
                GeoPoint p = null;
                if (pf != null) {
                    final GeoPointMetaData gp = mv.inverseWithElevation(pf.x, pf.y);
                    p = gp == null ? null : gp.get();
                }
                disarmPick();
                if (p == null || !p.isValid()) {
                    updateModeIcons();
                    return;
                }
                pickedPoint = p;
                setMode(PointMode.PICKED);
            }
        };
        d.addMapEventListener(MapEvent.MAP_CLICK, pickListener);
        d.addMapEventListener(MapEvent.ITEM_CLICK, pickListener);
    }

    private void disarmPick() {
        if (!pickArmed)
            return;
        pickArmed = false;
        pickListener = null;
        final MapView mv = MapView.getMapView();
        if (mv != null)
            mv.getMapEventDispatcher().popListeners();
    }

    private void refresh(boolean force) {
        if (selected == null) {
            statusText.setText(R.string.no_sources);
            return;
        }

        final GeoPoint p = point();
        if (p == null) {
            positionText.setText(R.string.no_position);
            statusText.setText(mode == PointMode.SELF
                    ? "No self position yet (no GPS fix). Tap Refresh once ATAK has one."
                    : mode == PointMode.PICKED ? "No point picked yet" : "No map center yet");
            return;
        }

        // The point being read, and nothing about how it is rounded on the way
        // out; that belongs in Position sent, not on a line read every time.
        positionText.setText(modeLabel() + " \u2014 " + egress.latitude(p) + ", "
                + egress.longitude(p));
        statusText.setTextColor(Color.parseColor("#dfb228"));
        statusText.setText("Getting the forecast\u2026");

        client.fetch(selected, p, force, new WeatherClient.Listener() {
            @Override
            public void onSnapshot(Snapshot result, boolean fromCache) {
                snapshot = result;
                final long age = result.ageMillis(System.currentTimeMillis());
                statusText.setTextColor(Color.parseColor("#dfb228"));
                // The place and how old it is. Which service answered, and whether
                // the bytes came off disk, are not things a crew can act on.
                statusText.setText((result.place == null ? "" : result.place + " \u2014 ")
                        + Snapshot.describeAge(age));
                render();
            }

            @Override
            public void onError(String message, Snapshot stale) {
                statusText.setTextColor(Color.parseColor("#ff8a65"));
                if (stale != null) {
                    snapshot = stale;
                    final long age = stale.ageMillis(System.currentTimeMillis());
                    statusText.setText(message + " — showing the last one, "
                            + Snapshot.describeAge(age));
                    render();
                } else {
                    statusText.setText(message);
                    currentContainer.removeAllViews();
                    trendChips.removeAllViews();
                }
            }
        });
    }

    private void updateHoursTableButton() {
        hoursTableButton.setText(hoursTableOpen ? R.string.hide_hours_table
                : R.string.show_hours_table);
    }

    private void render() {
        currentContainer.removeAllViews();
        trendChips.removeAllViews();
        hoursLegend.removeAllViews();
        hoursContainer.removeAllViews();
        daysLegend.removeAllViews();
        daysContainer.removeAllViews();

        if (snapshot == null)
            return;

        final Set<String> wanted = ParamSelection.selectedKeys(selected);

        List<Reading> now = new ArrayList<>();
        for (Reading r : snapshot.current) {
            if (wanted.contains(r.key))
                now.add(r);
        }

        // A source with no "current" block (NWS is one) still has a first forecast step,
        // which is the closest thing to now it can offer. Say so rather than showing
        // an empty heading.
        boolean fromSeries = false;
        if (now.isEmpty() && !snapshot.series.isEmpty()) {
            for (Reading r : snapshot.series.get(0).readings) {
                if (wanted.contains(r.key))
                    now.add(r);
            }
            fromSeries = true;
        }

        // A service with no "current" block gives its first forecast hour instead. That
        // is a fact about the feed, not about the weather, so the heading stays "Now".
        currentHeading.setText(pluginContext.getString(R.string.heading_now));

        renderNow(now);
        renderSunMoon();

        renderTrend(wanted);
        renderHours(wanted);
        renderDays(wanted);
        attributionText.setText(snapshot.attribution == null ? "" : snapshot.attribution);
    }

    /** True when no hour of the whole forecast has a value for the element. */
    private boolean neverIssued(String key) {
        if (snapshot == null)
            return false;
        for (SeriesEntry e : snapshot.series) {
            final Reading r = e.reading(key);
            if (r != null && !Double.isNaN(r.value))
                return false;
        }
        return true;
    }

    /** A tile whose value is a sentence, not a number: smaller, dimmer, allowed to wrap. */
    private View noteTile(String label, String note) {
        final LinearLayout t = (LinearLayout) tile(label, note);
        final TextView v = (TextView) t.getChildAt(0);
        v.setTextSize(13);
        v.setAlpha(0.7f);
        v.setTypeface(Typeface.DEFAULT);
        // A tile is a third of the pane; the sentence needs two lines there, and a
        // number's single line cut it to "Not issued by this of" (XCover, 2026-09-26).
        v.setSingleLine(false);
        v.setMaxLines(2);
        return t;
    }

    private static boolean isTwentyFoot(String key) {
        return key != null && key.toLowerCase(Locale.US).contains("twentyfoot");
    }

    /** The first hour's 10 m wind direction, or null when there is none. */
    private Reading tenMeterDirection() {
        if (snapshot == null || snapshot.series.isEmpty())
            return null;
        for (Reading r : snapshot.series.get(0).readings)
            if (kind(r) == Kind.DIR && !isTwentyFoot(r.key) && !Double.isNaN(r.value))
                return r;
        return null;
    }

    /** The first hour's 10 m wind, or null when the source has none or it is blank. */
    private Reading tenMeterWind() {
        if (snapshot == null || snapshot.series.isEmpty())
            return null;
        for (Reading r : snapshot.series.get(0).readings)
            if (kind(r) == Kind.WIND && !Double.isNaN(r.value))
                return r;
        return null;
    }

    private static final int TILE_COLUMNS = 3;
    /** Roboto Medium: the readout face. Not monospace, which spaced the digits like a terminal. */
    private static final Typeface VALUE_FACE = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    /**
     * The "now" block as tiles: the number big with its label directly under it, three
     * across. A label-left, value-right row put the two at opposite edges, and at 95 %
     * width they were half a screen apart.
     */
    private void renderNow(List<Reading> now) {
        LinearLayout row = null;
        int inRow = TILE_COLUMNS;
        for (Reading r : now) {
            if (inRow == TILE_COLUMNS) {
                row = new LinearLayout(pluginContext);
                row.setOrientation(LinearLayout.HORIZONTAL);
                currentContainer.addView(row);
                inRow = 0;
            }
            if (kind(r) == Kind.SKY) {
                row.addView(skyTile(r));
            } else if (kind(r) == Kind.WIND20 && Double.isNaN(r.value) && tenMeterWind() != null) {
                // The grid has hours with no 20-foot wind (76 of 87 at SGX, measured
                // 2026-09-26). The 10 m wind for the hour, labeled as such, beats a
                // dash -- and beats a number that does not say which wind it is.
                row.addView(tile("Wind (10 m)", tenMeterWind().format(units)));
            } else if (kind(r) == Kind.DIR && isTwentyFoot(r.key) && Double.isNaN(r.value)
                    && tenMeterDirection() != null) {
                // The 20-foot wind's direction goes with it: an office that does not
                // issue the one does not issue the other (STO, 2026-09-26), and the
                // 10 m direction is what the 10 m wind above was read against.
                row.addView(tile("Wind from (10 m)", tenMeterDirection().format(units)));
            } else if (Double.isNaN(r.value) && neverIssued(r.key)) {
                // The grid defines every element for every office and each office
                // fills the ones it issues: WBGT is there at SGX, LOX, REV and PDT and
                // empty at MTR and BOI (measured 2026-09-26). A dash would read as
                // "no value this hour"; the truth is that this office never has one.
                row.addView(noteTile(tileLabel(r), "Not issued by this office"));
            } else {
                row.addView(tile(tileLabel(r), r.format(units)));
            }
            inRow++;
        }
        // Pad a short last row so its tiles keep the width of the others.
        while (row != null && inRow < TILE_COLUMNS) {
            final View filler = new View(pluginContext);
            filler.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
            row.addView(filler);
            inRow++;
        }
    }

    /** Wind direction is where the wind comes from; the label says so. */
    private static String tileLabel(Reading r) {
        switch (kind(r)) {
            case TEMP: return "Temperature";
            case DEW: return "Dew point";
            case FEELS: return "Feels like";
            case RH: return "Humidity";
            case WIND: return "Wind (10 m)";
            case WIND20: return "20-ft wind";
            case GUST: return "Gusts";
            case TRANSPORT: return "Transport wind";
            case DIR: return "Wind from";
            case TDIR: return "Transport from";
            case MIXING: return "Mixing height";
            case WBGT: return "Wet bulb globe";
            case POP: return "Precip chance";
            case PRECIP: return "Precip";
            case SKY: return "Sky cover";
            default: return r.label;
        }
    }

    /** Sky cover as the station-plot okta circle, the percentage in the label under it. */
    private View skyTile(Reading r) {
        final LinearLayout t = new LinearLayout(pluginContext);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        t.setPadding(dp(4), dp(6), dp(4), dp(6));
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        final SkyCoverView glyph = new SkyCoverView(pluginContext);
        glyph.setLayoutParams(new LinearLayout.LayoutParams(dp(34), dp(34)));
        glyph.setPercent(r.valid() ? r.value : Double.NaN);
        final TextView l = new TextView(pluginContext);
        l.setText(r.valid() ? "Sky cover " + r.format(units) : "Sky cover");
        l.setTextSize(11);
        l.setAlpha(0.6f);
        l.setGravity(Gravity.CENTER);
        l.setSingleLine(true);
        t.addView(glyph);
        t.addView(l);
        return t;
    }

    private View tile(String label, String value) {
        final LinearLayout t = new LinearLayout(pluginContext);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        t.setPadding(dp(4), dp(6), dp(4), dp(6));
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        final TextView v = new TextView(pluginContext);
        v.setText(value);
        v.setTextSize(26);
        v.setTypeface(VALUE_FACE);
        v.setTextColor(Color.WHITE);
        v.setGravity(Gravity.CENTER);
        v.setSingleLine(true);
        final TextView l = new TextView(pluginContext);
        l.setText(label);
        l.setTextSize(11);
        l.setAlpha(0.6f);
        l.setGravity(Gravity.CENTER);
        l.setSingleLine(true);
        t.addView(v);
        t.addView(l);
        return t;
    }

    /** Hours are shown as columns; more than this is the days strip's job. */
    private static final int HOURS_SHOWN = 48;
    private static final int COLUMN_DP = 92;
    private static final int HEADER_DP = 36;
    private static final int ROW_DP = 24;

    /**
     * One value across the coming hours, the way the operator reads it in a consumer
     * weather app: a column per hour, the number on a line whose height follows it, a
     * sun or moon with cloud for the sky, sunrise and sunset as their own columns. The
     * chip row under it picks the value; direction rides on the wind as an arrow and
     * sky cover is in every column, so neither is a chip.
     */
    private void renderTrend(Set<String> wanted) {
        final List<Reading> order = keyOrder(wanted);
        final List<Reading> chips = new ArrayList<>();
        for (Reading r : order) {
            final Kind k = kind(r);
            if (k != Kind.DIR && k != Kind.TDIR && k != Kind.SKY && k != Kind.OTHER)
                chips.add(r);
        }
        final boolean any = !chips.isEmpty() && !snapshot.series.isEmpty();
        seriesHeading.setVisibility(any ? View.VISIBLE : View.GONE);
        trendScroll.setVisibility(any ? View.VISIBLE : View.GONE);
        trendChips.setVisibility(any ? View.VISIBLE : View.GONE);
        if (!any)
            return;
        // The chosen kind must be one this source has, or fall back to the first.
        Reading chosen = null;
        for (Reading r : chips)
            if (kind(r) == trendKind) chosen = r;
        if (chosen == null) {
            chosen = chips.get(0);
            trendKind = kind(chosen);
        }
        for (final Reading r : chips) {
            final Button chip = (Button) LayoutInflater.from(pluginContext)
                    .inflate(R.layout.trend_chip, trendChips, false);
            chip.setText(chipLabel(r));
            chip.setTextColor(kind(r) == trendKind
                    ? pluginContext.getResources().getColor(R.color.state_on) : Color.WHITE);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    trendKind = kind(r);
                    final SharedPreferences p = MapCompat.prefs();
                    if (p != null)
                        p.edit().putString(PREF_TREND, trendKind.name()).apply();
                    render();
                }
            });
            trendChips.addView(chip);
        }
        trend.setColumns(trendColumns(chosen, order));
        trendScroll.scrollTo(0, 0);
    }

    /**
     * The hours as a table: every shown value per hour, the legend pinned on the left,
     * behind a toggle. The trend strip is the default read and the table is there when
     * a number for a given hour is wanted; the operator asked to keep both. Closed by
     * default, the choice kept in prefs, and only built while open, since 48 columns
     * of TextViews are not free.
     */
    private void renderHours(Set<String> wanted) {
        final List<Reading> order = keyOrder(wanted);
        final boolean any = !order.isEmpty() && !snapshot.series.isEmpty();
        hoursTableButton.setVisibility(any ? View.VISIBLE : View.GONE);
        hoursTable.setVisibility(any && hoursTableOpen ? View.VISIBLE : View.GONE);
        if (!any || !hoursTableOpen)
            return;
        final List<String> labels = new ArrayList<>();
        for (Reading r : order)
            labels.add(shortLabel(r));
        hoursLegend.addView(column("", labels, true));
        final SimpleDateFormat day = new SimpleDateFormat("EEE", Locale.US);
        final SimpleDateFormat hour = new SimpleDateFormat("ha", Locale.US);
        int shown = 0;
        for (SeriesEntry entry : snapshot.series) {
            if (shown++ >= HOURS_SHOWN)
                break;
            final List<String> values = new ArrayList<>();
            for (Reading k : order) {
                final Reading r = entry.reading(k.key);
                values.add(r == null ? "\u2014" : r.format(units));
            }
            final String header;
            if (entry.timeMillis > 0) {
                final Date d = new Date(entry.timeMillis);
                header = day.format(d) + "\n" + hour.format(d).toLowerCase(Locale.US);
            } else {
                header = String.valueOf(entry.timeRaw);
            }
            hoursContainer.addView(column(header, values, false));
        }
    }

    /** The chip text carries the unit, so the columns can print bare numbers. */
    private String chipLabel(Reading r) {
        switch (kind(r)) {
            case TEMP: return "Temperature";
            case DEW: return "Dew point";
            case FEELS: return "Feels like";
            case RH: return "Humidity";
            case WIND: return "Wind (10 m) " + Units.displayUnit(Quantity.SPEED, units);
            case WIND20: return "20-ft wind " + Units.displayUnit(Quantity.SPEED, units);
            case GUST: return "Gusts " + Units.displayUnit(Quantity.SPEED, units);
            case TRANSPORT: return "Transport wind " + Units.displayUnit(Quantity.SPEED, units);
            case MIXING: return "Mixing height " + Units.displayUnit(Quantity.HEIGHT, units);
            case WBGT: return "Wet bulb globe";
            case POP: return "Precip chance";
            case PRECIP: return "Precip " + Units.displayUnit(Quantity.PRECIPITATION, units);
            default: return r.label;
        }
    }

    /** A column's number: compact, the unit implied by the chip. */
    private String columnText(Reading r) {
        if (r == null || !r.valid())
            return "\u2014";
        switch (r.quantity) {
            case TEMPERATURE:
                return Math.round(Units.toDisplay(r.quantity, r.value, units)) + "\u00b0";
            case PERCENT:
                return Math.round(r.value) + "%";
            case SPEED:
                return String.valueOf(Math.round(Units.toDisplay(r.quantity, r.value, units)));
            default:
                return Units.format(r.quantity, r.value, units)
                        .replace(" " + Units.displayUnit(r.quantity, units), "");
        }
    }

    private List<TrendStripView.Column> trendColumns(Reading chosen, List<Reading> order) {
        final List<TrendStripView.Column> cols = new ArrayList<>();
        String dirKey = null, skyKey = null;
        for (Reading r : order) {
            if (kind(r) == Kind.DIR) dirKey = r.key;
            if (kind(r) == Kind.SKY) skyKey = r.key;
        }
        final long now = System.currentTimeMillis();
        final SimpleDateFormat hourFmt = new SimpleDateFormat("ha", Locale.US);
        final SimpleDateFormat dayFmt = new SimpleDateFormat("EEE", Locale.US);
        final SimpleDateFormat dayKey = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        final SimpleDateFormat clock = new SimpleDateFormat("h:mm", Locale.US);
        final Map<String, long[]> sunByDay = new LinkedHashMap<>();
        String lastDay = null;
        int shown = 0;
        long prevTime = 0;
        boolean nowMarked = false;
        for (SeriesEntry e : snapshot.series) {
            if (shown++ >= HOURS_SHOWN)
                break;
            if (e.timeMillis <= 0)
                continue;
            final Date d = new Date(e.timeMillis);
            final String dk = dayKey.format(d);
            long[] sun = sunByDay.get(dk);
            if (sun == null && !sunByDay.containsKey(dk)) {
                sun = Astro.sunRiseSet(e.timeMillis, TimeZone.getDefault(),
                        snapshot.latitude, snapshot.longitude);
                sunByDay.put(dk, sun);
            }
            // a sunrise or sunset between the previous hour and this one gets its own column
            if (sun != null && prevTime > 0) {
                for (int i = 0; i < 2; i++) {
                    if (sun[i] > prevTime && sun[i] <= e.timeMillis) {
                        final TrendStripView.Column sc = new TrendStripView.Column();
                        sc.sunEvent = true;
                        sc.sunrise = i == 0;
                        sc.header = clock.format(new Date(sun[i]));
                        sc.sunLabel = i == 0 ? "Sunrise" : "Sunset";
                        cols.add(sc);
                    }
                }
            }
            final TrendStripView.Column c = new TrendStripView.Column();
            final boolean isNow = !nowMarked && e.timeMillis <= now + 3_600_000L
                    && e.timeMillis > now - 3_600_000L;
            if (isNow) {
                c.header = "Now";
                c.now = true;
                nowMarked = true;
            } else if (lastDay != null && !dk.equals(lastDay)) {
                c.header = dayFmt.format(d) + "\n" + hourFmt.format(d).toLowerCase(Locale.US);
            } else {
                c.header = hourFmt.format(d).toLowerCase(Locale.US);
            }
            lastDay = dk;
            final Reading v = e.reading(chosen.key);
            c.value = v == null || !v.valid() ? Double.NaN
                    : Units.toDisplay(v.quantity, v.value, units);
            c.text = columnText(v);
            if ((kind(chosen) == Kind.WIND || kind(chosen) == Kind.GUST) && dirKey != null) {
                final Reading dir = e.reading(dirKey);
                if (dir != null && dir.valid())
                    c.arrowDeg = (dir.value + 180) % 360;   // from -> toward
            }
            if (skyKey != null) {
                final Reading sky = e.reading(skyKey);
                c.oktas = sky == null || !sky.valid() ? -1 : SkyCoverView.oktas(sky.value);
            }
            c.night = sun == null ? false : (e.timeMillis < sun[0] || e.timeMillis >= sun[1]);
            cols.add(c);
            prevTime = e.timeMillis;
        }
        return cols;
    }

    /** Sunrise, sunset and the moon for the point, computed on the device. */
    private void renderSunMoon() {
        if (snapshot == null)
            return;
        final long now = System.currentTimeMillis();
        final long[] sun = Astro.sunRiseSet(now, TimeZone.getDefault(), snapshot.latitude,
                snapshot.longitude);
        final SimpleDateFormat clock = new SimpleDateFormat("h:mm a", Locale.US);
        final LinearLayout row = new LinearLayout(pluginContext);
        row.setOrientation(LinearLayout.HORIZONTAL);
        if (sun != null) {
            row.addView(tile("Sunrise", clock.format(new Date(sun[0])).toLowerCase(Locale.US)));
            row.addView(tile("Sunset", clock.format(new Date(sun[1])).toLowerCase(Locale.US)));
        } else {
            row.addView(tile("Sun", "no rise or set today"));
            final View filler = new View(pluginContext);
            filler.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
            row.addView(filler);
        }
        final int lit = (int) Math.round(Astro.moonIllumination(now) * 100);
        row.addView(tile(Astro.moonPhaseName(now), lit + "% lit"));
        currentContainer.addView(row);
    }

    /** The wanted readings in the order the source lists them, taken from the first hour that has them. */
    private List<Reading> keyOrder(Set<String> wanted) {
        final List<Reading> order = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        for (SeriesEntry entry : snapshot.series) {
            for (Reading r : entry.readings) {
                if (wanted.contains(r.key) && seen.add(r.key))
                    order.add(r);
            }
            if (!order.isEmpty())
                break;
        }
        return order;
    }

    /**
     * The hours folded into days: the numbers a shift is planned on. High and low
     * temperature, the lowest RH, the strongest wind and gust, the highest chance of
     * precipitation and the total. Only shown when the forecast covers more than one day.
     */
    private void renderDays(Set<String> wanted) {
        final Map<String, DayAgg> days = new LinkedHashMap<>();
        final SimpleDateFormat dayKey = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        final SimpleDateFormat dayHead = new SimpleDateFormat("EEE\nM/d", Locale.US);
        for (SeriesEntry e : snapshot.series) {
            if (e.timeMillis <= 0)
                continue;
            final Date d = new Date(e.timeMillis);
            final String k = dayKey.format(d);
            DayAgg agg = days.get(k);
            if (agg == null) {
                agg = new DayAgg(dayHead.format(d));
                days.put(k, agg);
            }
            for (Reading r : e.readings) {
                if (r.valid() && wanted.contains(r.key))
                    agg.take(r);
            }
        }
        final boolean any = days.size() >= 2;
        daysHeading.setVisibility(any ? View.VISIBLE : View.GONE);
        daysStrip.setVisibility(any ? View.VISIBLE : View.GONE);
        if (!any)
            return;

        // Rows exist only when some day has the value, so a source without gusts has no gust row.
        final String[] rowLabels = {"Hi", "Lo", "Min RH", "Max wind", "Max gust", "Precip %", "Precip"};
        final boolean[] rowUsed = new boolean[rowLabels.length];
        for (DayAgg a : days.values()) {
            for (int i = 0; i < rowLabels.length; i++)
                rowUsed[i] |= !Double.isNaN(a.row(i));
        }
        final List<String> labels = new ArrayList<>();
        for (int i = 0; i < rowLabels.length; i++)
            if (rowUsed[i]) labels.add(rowLabels[i]);
        daysLegend.addView(column("", labels, true));
        for (DayAgg a : days.values()) {
            final List<String> values = new ArrayList<>();
            for (int i = 0; i < rowLabels.length; i++) {
                if (!rowUsed[i])
                    continue;
                final double v = a.row(i);
                values.add(Double.isNaN(v) ? "\u2014" : Units.format(a.quantity(i), v, units));
            }
            daysContainer.addView(column(a.header, values, false));
        }
    }

    /** What a reading is, from its quantity and its name, so days can be aggregated. */
    private enum Kind {
        TEMP, DEW, FEELS, WBGT, RH, WIND, WIND20, GUST, TRANSPORT, DIR, TDIR, MIXING, POP,
        PRECIP, SKY, OTHER;

        static Kind fromName(String n) {
            if (n == null) return TEMP;
            for (Kind k : values())
                if (k.name().equals(n)) return k;
            return TEMP;
        }
    }

    private static Kind kind(Reading r) {
        final String k = (r.key + " " + r.label).toLowerCase(Locale.US);
        switch (r.quantity) {
            case TEMPERATURE:
                if (k.contains("dew")) return Kind.DEW;
                if (k.contains("wet bulb") || k.contains("wetbulb")) return Kind.WBGT;
                if (k.contains("feel") || k.contains("apparent")) return Kind.FEELS;
                return Kind.TEMP;
            case HEIGHT:
                return k.contains("mixing") ? Kind.MIXING : Kind.OTHER;
            case PERCENT:
                if (k.contains("humid")) return Kind.RH;
                if (k.contains("precip")) return Kind.POP;
                if (k.contains("cloud") || k.contains("sky")) return Kind.SKY;
                return Kind.OTHER;
            case SPEED:
                if (k.contains("gust"))
                    return Kind.GUST;
                if (k.contains("transport"))
                    return Kind.TRANSPORT;
                // The fire weather wind: every prescription and spot forecast is
                // written in 20-foot winds, and this is the one the readout quotes.
                if (k.contains("twentyfoot") || k.contains("20-ft"))
                    return Kind.WIND20;
                return Kind.WIND;
            case ANGLE:
                return k.contains("transport") ? Kind.TDIR : Kind.DIR;
            case PRECIPITATION:
                return Kind.PRECIP;
            default:
                return Kind.OTHER;
        }
    }

    /** Legend text that fits a column. */
    private static String shortLabel(Reading r) {
        switch (kind(r)) {
            case TEMP: return "Temp";
            case DEW: return "Dew pt";
            case FEELS: return "Feels";
            case RH: return "RH";
            case WIND: return "10 m";
            case WIND20: return "20-ft";
            case GUST: return "Gust";
            case TRANSPORT: return "Transp";
            case DIR: return "Dir";
            case TDIR: return "T dir";
            case MIXING: return "Mix ht";
            case WBGT: return "WBGT";
            case POP: return "Precip %";
            case PRECIP: return "Precip";
            case SKY: return "Sky";
            default: return r.label.length() > 9 ? r.label.substring(0, 9) : r.label;
        }
    }

    /** One day's extremes, NaN until a reading arrives. */
    private static final class DayAgg {
        final String header;
        double tHi = Double.NaN, tLo = Double.NaN, rhLo = Double.NaN, windHi = Double.NaN,
                gustHi = Double.NaN, popHi = Double.NaN, precipSum = Double.NaN;

        DayAgg(String header) {
            this.header = header;
        }

        void take(Reading r) {
            switch (kind(r)) {
                case TEMP:
                    tHi = Double.isNaN(tHi) ? r.value : Math.max(tHi, r.value);
                    tLo = Double.isNaN(tLo) ? r.value : Math.min(tLo, r.value);
                    break;
                case RH: rhLo = Double.isNaN(rhLo) ? r.value : Math.min(rhLo, r.value); break;
                case WIND: case WIND20:
                    windHi = Double.isNaN(windHi) ? r.value : Math.max(windHi, r.value); break;
                case GUST: gustHi = Double.isNaN(gustHi) ? r.value : Math.max(gustHi, r.value); break;
                case POP: popHi = Double.isNaN(popHi) ? r.value : Math.max(popHi, r.value); break;
                case PRECIP: precipSum = Double.isNaN(precipSum) ? r.value : precipSum + r.value; break;
                default: break;
            }
        }

        double row(int i) {
            switch (i) {
                case 0: return tHi;
                case 1: return tLo;
                case 2: return rhLo;
                case 3: return windHi;
                case 4: return gustHi;
                case 5: return popHi;
                default: return precipSum;
            }
        }

        Quantity quantity(int i) {
            switch (i) {
                case 0: case 1: return Quantity.TEMPERATURE;
                case 2: case 5: return Quantity.PERCENT;
                case 3: case 4: return Quantity.SPEED;
                default: return Quantity.PRECIPITATION;
            }
        }
    }

    /**
     * A column of the strip: a two-line header and one line per row, every line a fixed
     * height so the legend column and the value columns line up.
     */
    private View column(String header, List<String> values, boolean legend) {
        final LinearLayout col = new LinearLayout(pluginContext);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(
                legend ? LinearLayout.LayoutParams.WRAP_CONTENT : dp(COLUMN_DP),
                LinearLayout.LayoutParams.WRAP_CONTENT));
        col.setPadding(dp(4), 0, dp(4), 0);
        final TextView h = new TextView(pluginContext);
        h.setText(header);
        h.setTextSize(12);
        h.setMaxLines(2);
        h.setHeight(dp(HEADER_DP));
        h.setGravity((legend ? Gravity.START : Gravity.CENTER_HORIZONTAL) | Gravity.BOTTOM);
        h.setAlpha(0.8f);
        col.addView(h);
        for (String v : values) {
            final TextView t = new TextView(pluginContext);
            t.setText(v);
            t.setTextSize(15);
            t.setSingleLine(true);
            t.setHeight(dp(ROW_DP));
            t.setGravity((legend ? Gravity.START : Gravity.CENTER_HORIZONTAL) | Gravity.CENTER_VERTICAL);
            if (legend)
                t.setAlpha(0.7f);
            else
                t.setTypeface(VALUE_FACE);
            col.addView(t);
        }
        return col;
    }

    private int dp(int v) {
        return Math.round(v * pluginContext.getResources().getDisplayMetrics().density);
    }

    // ---- dialogs -----------------------------------------------------------------

    private void showSourcesDialog() {
        final Context ctx = MapCompat.atakContext();
        if (ctx == null || sources.isEmpty())
            return;

        final String[] labels = new String[sources.size()];
        final boolean[] enabled = new boolean[sources.size()];
        for (int i = 0; i < sources.size(); i++) {
            final WxSourceDef def = sources.get(i);
            final StringBuilder sb = new StringBuilder(def.displayName);
            sb.append("\n").append(hosts(def));
            if (def.origin == WxSourceDef.Origin.EXTERNAL)
                sb.append("\nfrom ").append(def.originFile);
            if (def.requiresApiKey)
                sb.append("\nnot available in this build");
            labels[i] = sb.toString();
            enabled[i] = egress.isEnabled(def);
        }

        new AlertDialog.Builder(ctx)
                .setTitle(pluginContext.getString(R.string.sources_title))
                .setMultiChoiceItems(labels, enabled,
                        new DialogInterface.OnMultiChoiceClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which,
                                    boolean isChecked) {
                                egress.setEnabled(sources.get(which), isChecked);
                            }
                        })
                .setPositiveButton(pluginContext.getString(R.string.close), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        refresh(false);
                    }
                })
                .show();
    }

    private void showVariablesDialog() {
        final Context ctx = MapCompat.atakContext();
        if (ctx == null || selected == null)
            return;

        final List<WxParam> params = selected.params;
        final String[] labels = new String[params.size()];
        final boolean[] checked = new boolean[params.size()];
        final Set<String> keys = new HashSet<>(ParamSelection.selectedKeys(selected));
        for (int i = 0; i < params.size(); i++) {
            labels[i] = params.get(i).label;
            checked[i] = keys.contains(params.get(i).key);
        }

        new AlertDialog.Builder(ctx)
                .setTitle(pluginContext.getString(R.string.variables_title))
                .setMultiChoiceItems(labels, checked,
                        new DialogInterface.OnMultiChoiceClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which,
                                    boolean isChecked) {
                                if (isChecked)
                                    keys.add(params.get(which).key);
                                else
                                    keys.remove(params.get(which).key);
                            }
                        })
                .setPositiveButton(pluginContext.getString(R.string.close), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        ParamSelection.setSelected(selected, keys);
                        // The variable list is part of the request, so this needs a
                        // fetch, not just a re-render.
                        refresh(true);
                    }
                })
                .show();
    }

    // ---- favorites -----------------------------------------------------------------

    private void showFavoritesDialog() {
        final Context ctx = MapCompat.atakContext();
        if (ctx == null)
            return;
        final List<Favorites.Place> all = new ArrayList<>(favorites.all());
        final AlertDialog.Builder b = new AlertDialog.Builder(ctx)
                .setTitle(pluginContext.getString(R.string.favorites_title));
        if (all.isEmpty()) {
            b.setMessage(pluginContext.getString(R.string.favorites_none));
        } else {
            final String[] names = new String[all.size()];
            int checked = -1;
            for (int i = 0; i < all.size(); i++) {
                names[i] = all.get(i).name;
                if (favorite != null && favorite.name.equals(names[i]))
                    checked = i;
            }
            b.setSingleChoiceItems(names, checked, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    dialog.dismiss();
                    setFavorite(all.get(which));
                }
            });
            b.setNeutralButton(pluginContext.getString(R.string.favorites_remove), new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    showRemoveFavoritesDialog();
                }
            });
        }
        b.setPositiveButton(pluginContext.getString(R.string.favorites_add), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                showAddFavoriteDialog();
            }
        });
        b.setNegativeButton(pluginContext.getString(R.string.close), null);
        b.show();
    }

    /**
     * Save the point being read now under a name, suggested from the provider's
     * nearest city when the last snapshot is for this point, else the coordinates.
     * Saving does not switch to it: an operator starring their own position wants
     * to keep following it, not freeze it.
     */
    private void showAddFavoriteDialog() {
        final Context ctx = MapCompat.atakContext();
        if (ctx == null)
            return;
        final GeoPoint p = point();
        if (p == null) {
            Toast.makeText(ctx, pluginContext.getString(R.string.no_position), Toast.LENGTH_SHORT).show();
            return;
        }
        String suggested = egress.latitude(p) + ", " + egress.longitude(p);
        if (snapshot != null && snapshot.place != null
                && Math.abs(snapshot.latitude - p.getLatitude()) < 0.01
                && Math.abs(snapshot.longitude - p.getLongitude()) < 0.01)
            suggested = snapshot.place;

        final EditText input = new EditText(ctx);
        input.setSingleLine(true);
        input.setText(suggested);
        input.setSelectAllOnFocus(true);
        final double lat = p.getLatitude(), lon = p.getLongitude();
        new AlertDialog.Builder(ctx)
                .setTitle(pluginContext.getString(R.string.favorite_name_title))
                .setView(input)
                .setPositiveButton(pluginContext.getString(R.string.save), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        final Favorites.Place saved = favorites.add(
                                input.getText().toString(), lat, lon);
                        if (saved != null)
                            Toast.makeText(ctx, "Saved \u2605 " + saved.name,
                                    Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(pluginContext.getString(R.string.close), null)
                .show();
    }

    private void showRemoveFavoritesDialog() {
        final Context ctx = MapCompat.atakContext();
        final List<Favorites.Place> all = new ArrayList<>(favorites.all());
        if (ctx == null || all.isEmpty())
            return;
        final String[] names = new String[all.size()];
        for (int i = 0; i < all.size(); i++)
            names[i] = all.get(i).name;
        final boolean[] checked = new boolean[all.size()];
        new AlertDialog.Builder(ctx)
                .setTitle(pluginContext.getString(R.string.favorites_remove_title))
                .setMultiChoiceItems(names, checked,
                        new DialogInterface.OnMultiChoiceClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which,
                                    boolean isChecked) {
                                checked[which] = isChecked;
                            }
                        })
                .setPositiveButton(pluginContext.getString(R.string.remove), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        boolean activeGone = false;
                        for (int i = 0; i < names.length; i++) {
                            if (!checked[i])
                                continue;
                            favorites.remove(names[i]);
                            if (favorite != null && favorite.name.equals(names[i]))
                                activeGone = true;
                        }
                        // The place being read is gone: back to the map center.
                        if (activeGone)
                            setMode(PointMode.CENTER);
                    }
                })
                .setNegativeButton(pluginContext.getString(R.string.close), null)
                .show();
    }

    private void showPrivacyDialog() {
        final Context ctx = MapCompat.atakContext();
        if (ctx == null)
            return;

        final int[] choices = {0, 1, 2, 3, 4};
        final String[] labels = new String[choices.length];
        for (int i = 0; i < choices.length; i++) {
            labels[i] = "Rounded to about " + roughly(
                    EgressPolicy.approximateMeters(choices[i]));
        }

        int current = 0;
        for (int i = 0; i < choices.length; i++) {
            if (choices[i] == egress.positionDecimals()) {
                current = i;
                break;
            }
        }

        new AlertDialog.Builder(ctx)
                .setTitle(pluginContext.getString(R.string.privacy_title))
                .setMessage("Asking for a forecast means saying roughly where you "
                        + "are. This sets how exact that is. Nothing else about you "
                        + "is sent.")
                .setSingleChoiceItems(labels, current,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                egress.setPositionDecimals(choices[which]);
                            }
                        })
                .setPositiveButton(pluginContext.getString(R.string.close), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        refresh(false);
                    }
                })
                .show();
    }

    /**
     * A source file that did not load is shown once, at start. Upstream plugins that
     * only log this leave the operator staring at a source that never appears.
     */
    private void showSourceProblemsIfAny() {
        final List<String> problems = registry.problems();
        if (problems.isEmpty())
            return;

        final Context ctx = MapCompat.atakContext();
        if (ctx == null)
            return;

        final StringBuilder sb = new StringBuilder();
        for (String p : problems)
            sb.append("• ").append(p).append("\n");

        new AlertDialog.Builder(ctx)
                .setTitle(pluginContext.getString(R.string.source_problems))
                .setMessage(sb.toString().trim())
                .setPositiveButton(pluginContext.getString(R.string.close), null)
                .show();
    }

    private static String hosts(WxSourceDef def) {
        final List<String> h = def.hosts();
        if (h.isEmpty())
            return "no host";
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < h.size(); i++) {
            if (i > 0)
                sb.append(", ");
            sb.append(h.get(i));
        }
        return sb.toString();
    }
}

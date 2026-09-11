package com.atakmap.android.comms.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.comms.data.CatalogStore;
import com.atakmap.android.comms.data.ScaleBar;
import com.atakmap.android.comms.data.Tones;
import com.atakmap.android.comms.data.Units;
import com.atakmap.android.comms.map.LineOfSight;
import com.atakmap.android.comms.map.SiteLayer;
import com.atakmap.android.comms.model.Catalog;
import com.atakmap.android.comms.model.Catalog.Channel;
import com.atakmap.android.comms.model.Catalog.Net;
import com.atakmap.android.comms.model.Catalog.Site;
import com.atakmap.android.comms.plugin.R;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.conversions.Span;
import com.atakmap.coremap.conversions.SpanUtilities;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoCalculations;
import com.atakmap.coremap.maps.coords.GeoPoint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The Comms pane: search, filters, and the sites nearest first.
 *
 * <p>Cam Depot's shape. The filter controls ride as the list's header view so the
 * pane has exactly one scroller (a ListView cannot live in a ScrollView), every
 * control is a {@code TakwerxButton}, every dialog is built on the MapView context,
 * and distances follow ATAK's own range unit.
 *
 * <p>When the search names a net, each row's subtitle is that net's line at that
 * site, so "closest Command 5" is answered by the list itself. A net with no fixed
 * site (CDF C5 and C11 are incident portables) is shown as its own card rather than
 * as an empty list.
 */
public final class CommsPane implements CatalogStore.Listener, SiteLayer.Listener {

    private static final String TAG = "CommsPane";

    /** The map draws this many of the nearest sites; the status line says when it stopped. */
    private static final int MAX_MAP = 300;
    private static final int MAX_NET_CARDS = 12;

    // Standing preferences: where you work and what you care to see. The search box
    // and the radius are per-task and deliberately not remembered.
    private static final String PREF_STATE = "comms_state";
    private static final String PREF_FROM_MAP = "comms_from_map";
    private static final String PREF_AGENCIES_OFF = "comms_agencies_off";
    private static final String PREF_ZOOM = "comms_zoom_threshold";
    private static final String PREF_VIEWSHED_RANGE = "comms_viewshed_range_big";
    private static final String PREF_MAP_ON = "comms_map_on";
    /** The operator's own antenna height above ground, meters. A standing preference: what you carry. */
    private static final String PREF_ME_HEIGHT = "comms_operator_height_m";
    private static final double DEFAULT_ME_HEIGHT_M = 1.5;
    /** Handheld, vehicle, mast, tower, aircraft; feet or meters depending on ATAK's unit. */
    private static final double[] HEIGHT_PRESETS_FT = { 5, 8, 20, 50, 100 };
    private static final double[] HEIGHT_PRESETS_M = { 1.5, 2.5, 6, 15, 30 };
    private static final String[] HEIGHT_NAMES = { "handheld", "vehicle", "mast", "tower", "aircraft" };

    /** Radius choices in the operator's own big unit; 0 is off. */
    private static final int[] RADIUS_PRESETS = { 0, 10, 25, 50, 100, 200 };
    /** Viewshed range choices in the big unit, under ATAK's 100 km cap. */
    private static final int[] VIEWSHED_PRESETS = { 5, 10, 20, 30, 50 };
    /** Zoom presets as what the scale bar would read, in the big unit. */
    private static final double[] ZOOM_PRESET_BIG = { 1, 5, 15, 50, 150 };
    private static final String[] ZOOM_PRESET_NAMES = { "neighborhood", "town", "county", "region", "state" };
    private static final String ALWAYS = "Always draw them";

    private static final int STATUS_BUSY = 0xFF4CAF50;
    private static final int STATUS_WARN = 0xFFFFB74D;

    public interface DetailHost {
        void showDetailPane(View v);

        void hideDetailPane();
    }

    private final Context pluginContext;
    private final MapView mapView;
    private final CatalogStore store;
    private final SiteLayer layer;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final View root;
    private final TextView status;
    private final int statusColor;
    private final Button toggle;
    private final ListView list;
    private final View controls;
    private final EditText search;
    private final Button searchClear;
    private final Button stateButton, fromButton, radiusButton;
    private final LinearLayout agencyBox;
    private final TextView zoomLabel;
    private final Button viewshedsOff, viewshedRange, meViewshed, meHeight, sync;
    private final TextView viewshedNote;
    private final RowAdapter adapter;
    private DetailHost detailHost;

    private String state;            // null means every state in the catalog
    private boolean fromMapCenter;
    private double radiusBig;        // 0 is off
    private final Set<String> agenciesOff = new HashSet<>();
    private boolean mapOn = true;
    private final Map<String, CheckBox> agencyBoxes = new LinkedHashMap<>();
    private Site openSite;           // the site whose detail pane is up, if any
    private boolean syncPressed;     // the operator asked for a refresh; say how it went

    /**
     * The viewshed from the operator, and the sites inside it. Per task, not
     * remembered: a viewshed from where you stood yesterday is not one you asked for.
     */
    private boolean meViewshedOn;
    private GeoPoint meFrom;          // where the operator's viewshed was drawn from
    private final Map<String, Boolean> los = new java.util.HashMap<>();
    private boolean losPending;
    private int losUnknown, losChecked;
    private int losGeneration;
    private final java.util.concurrent.ExecutorService losWorker =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    /**
     * Runs on the GL render thread. Touching a View here is a native SIGSEGV with no
     * Java stack trace; post to the main looper and coalesce.
     */
    private final com.atakmap.map.AtakMapView.OnMapMovedListener mapWatch =
            new com.atakmap.map.AtakMapView.OnMapMovedListener() {
                @Override
                public void onMapMoved(com.atakmap.map.AtakMapView v, boolean animate) {
                    handler.removeCallbacks(mapTick);
                    handler.postDelayed(mapTick, 300);
                }
            };

    private final Runnable mapTick = new Runnable() {
        @Override
        public void run() {
            updateZoomLabel();
            // "Map center" means where the map is now, so the list follows the map.
            if (fromMapCenter)
                apply();
        }
    };

    /** Following the operator: re-sort when they have moved a useful distance. */
    private GeoPoint lastFrom;
    private final Runnable selfTick = new Runnable() {
        @Override
        public void run() {
            if (!fromMapCenter) {
                final GeoPoint me = selfPoint();
                if (me != null && (lastFrom == null || distance(me, lastFrom) > 250)) {
                    apply();
                    if (meViewshedOn)
                        refreshMeViewshed();
                }
            }
            handler.postDelayed(this, 20_000);
        }
    };

    public CommsPane(Context pluginContext, MapView mapView) {
        this.pluginContext = pluginContext;
        this.mapView = mapView;
        this.layer = new SiteLayer(mapView, pluginContext);
        this.store = new CatalogStore(pluginContext, mapView, this);
        layer.setListener(this);

        root = PluginLayoutInflater.inflate(pluginContext, R.layout.main_layout, null);
        status = root.findViewById(R.id.status);
        statusColor = status.getCurrentTextColor();
        toggle = root.findViewById(R.id.toggle);
        list = root.findViewById(R.id.sites);

        controls = PluginLayoutInflater.inflate(pluginContext, R.layout.controls_header, null);
        list.addHeaderView(controls, null, false);
        search = controls.findViewById(R.id.search);
        searchClear = controls.findViewById(R.id.search_clear);
        stateButton = controls.findViewById(R.id.state);
        fromButton = controls.findViewById(R.id.from);
        radiusButton = controls.findViewById(R.id.radius);
        agencyBox = controls.findViewById(R.id.agencies);
        zoomLabel = controls.findViewById(R.id.zoom_label);
        viewshedsOff = controls.findViewById(R.id.viewsheds_off);
        viewshedRange = controls.findViewById(R.id.viewshed_range);
        meViewshed = controls.findViewById(R.id.me_viewshed);
        meHeight = controls.findViewById(R.id.me_height);
        viewshedNote = controls.findViewById(R.id.viewshed_note);
        sync = controls.findViewById(R.id.sync);

        // A ListView blocks focus to its children by default, which means an EditText
        // in a header view never receives a keystroke.
        list.setItemsCanFocus(true);
        list.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);

        adapter = new RowAdapter();
        list.setAdapter(adapter);

        restoreUi();
        wire();
        layer.setMapOn(mapOn);
        layer.setMaxResolution(prefs().getFloat(PREF_ZOOM, -1) > 0
                ? prefs().getFloat(PREF_ZOOM, -1) : defaultZoomThreshold());
        layer.setViewshedRangeMeters(Units.bigToMeters(prefs().getFloat(PREF_VIEWSHED_RANGE, 20)));
        updateButtons();
        updateZoomLabel();
        mapView.addOnMapMovedListener(mapWatch);
        handler.postDelayed(selfTick, 20_000);
        busy("Loading catalog…");
        store.load();
    }

    public View getView() {
        return root;
    }

    public void setDetailHost(DetailHost h) {
        detailHost = h;
    }

    public void dispose() {
        handler.removeCallbacks(mapTick);
        handler.removeCallbacks(selfTick);
        mapView.removeOnMapMovedListener(mapWatch);
        losWorker.shutdownNow();
        layer.dispose();
        store.dispose();
    }

    // ---- wiring ---------------------------------------------------------------

    private void wire() {
        toggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mapOn = !mapOn;
                prefs().edit().putBoolean(PREF_MAP_ON, mapOn).apply();
                layer.setMapOn(mapOn);
                updateButtons();
                updateStatus();
            }
        });

        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                searchClear.setEnabled(e.length() > 0);
                handler.removeCallbacks(searchTick);
                handler.postDelayed(searchTick, 200);
            }
        });
        searchClear.setEnabled(false);
        searchClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                search.setText("");
                hideKeyboard();
                apply();
            }
        });

        stateButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final Catalog c = store.catalog();
                if (c == null)
                    return;
                final List<String> items = new ArrayList<>();
                items.add("All states");
                items.addAll(c.states());
                choose("State", items, state == null ? "All states" : state, new Chosen() {
                    @Override
                    public void onChosen(String value) {
                        state = "All states".equals(value) ? null : value;
                        prefs().edit().putString(PREF_STATE, state == null ? "" : state).apply();
                        updateButtons();
                        apply();
                    }
                });
            }
        });

        fromButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                fromMapCenter = !fromMapCenter;
                prefs().edit().putBoolean(PREF_FROM_MAP, fromMapCenter).apply();
                updateButtons();
                apply();
            }
        });

        radiusButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final List<String> names = new ArrayList<>();
                for (int r : RADIUS_PRESETS)
                    names.add(radiusLabel(r));
                choose("Show sites within", names, radiusLabel((int) radiusBig), new Chosen() {
                    @Override
                    public void onChosen(String value) {
                        for (int r : RADIUS_PRESETS)
                            if (radiusLabel(r).equals(value))
                                radiusBig = r;
                        updateButtons();
                        apply();
                    }
                });
            }
        });

        controls.findViewById(R.id.zoom_set).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Whatever the operator is looking at right now becomes the threshold.
                setThreshold(mapView.getMapResolution());
            }
        });
        controls.findViewById(R.id.zoom_preset).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final List<String> names = new ArrayList<>();
                names.add(ALWAYS);
                for (int i = 0; i < ZOOM_PRESET_BIG.length; i++)
                    names.add(zoomPresetLabel(i));
                choose("Draw sites when the scale bar reads", names, null, new Chosen() {
                    @Override
                    public void onChosen(String value) {
                        if (ALWAYS.equals(value)) {
                            setThreshold(Double.MAX_VALUE);
                            return;
                        }
                        for (int i = 0; i < ZOOM_PRESET_BIG.length; i++)
                            if (zoomPresetLabel(i).equals(value))
                                setThreshold(Units.bigToMeters(ZOOM_PRESET_BIG[i]) / scaleBarPixels());
                    }
                });
            }
        });

        viewshedsOff.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final int n = layer.hideAllViewsheds();
                updateButtons();
                toast(n == 1 ? "Viewshed off" : String.format(Locale.US, "%d viewsheds off", n));
            }
        });

        viewshedRange.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final List<String> names = new ArrayList<>();
                for (int r : VIEWSHED_PRESETS)
                    names.add(r + " " + Units.bigLabel());
                final int cur = (int) Math.round(prefs().getFloat(PREF_VIEWSHED_RANGE, 20));
                choose("Viewshed range from the antenna", names, cur + " " + Units.bigLabel(), new Chosen() {
                    @Override
                    public void onChosen(String value) {
                        final int r = Integer.parseInt(value.split(" ")[0]);
                        prefs().edit().putFloat(PREF_VIEWSHED_RANGE, r).apply();
                        layer.setViewshedRangeMeters(Units.bigToMeters(r));
                        updateButtons();
                        if (meViewshedOn)
                            refreshMeViewshed();
                        else
                            toast("Applies to the next viewshed you turn on");
                    }
                });
            }
        });

        meViewshed.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (meViewshedOn) {
                    meViewshedOn = false;
                    meFrom = null;
                    losGeneration++;
                    losPending = false;
                    los.clear();
                    layer.hideMeViewshed();
                    layer.setLineOfSight(null);
                    updateButtons();
                    apply();
                    return;
                }
                if (originPoint() == null) {
                    toast("No position to draw from");
                    return;
                }
                meViewshedOn = true;
                refreshMeViewshed();
                updateButtons();
            }
        });

        meHeight.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final List<String> names = new ArrayList<>();
                String current = null;
                for (int i = 0; i < HEIGHT_NAMES.length; i++) {
                    names.add(heightPresetLabel(i));
                    if (Math.abs(heightPresetMeters(i) - operatorHeightM()) < 0.05)
                        current = heightPresetLabel(i);
                }
                choose("Your antenna height above ground", names, current, new Chosen() {
                    @Override
                    public void onChosen(String value) {
                        for (int i = 0; i < HEIGHT_NAMES.length; i++)
                            if (heightPresetLabel(i).equals(value))
                                prefs().edit().putFloat(PREF_ME_HEIGHT, (float) heightPresetMeters(i)).apply();
                        updateButtons();
                        if (meViewshedOn)
                            refreshMeViewshed();
                    }
                });
            }
        });

        sync.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                busy("Refreshing the catalog…");
                syncPressed = true;
                store.sync();
            }
        });
    }

    private final Runnable searchTick = new Runnable() {
        @Override
        public void run() {
            apply();
        }
    };

    private interface Chosen {
        void onChosen(String value);
    }

    private void choose(String title, final List<String> items, String current, final Chosen cb) {
        if (items.isEmpty()) {
            toast("Nothing to choose from yet");
            return;
        }
        final String[] arr = items.toArray(new String[0]);
        int checked = -1;
        for (int i = 0; i < arr.length; i++)
            if (arr[i].equals(current)) {
                checked = i;
                break;
            }
        new AlertDialog.Builder(mapView.getContext())
                .setTitle(title)
                .setSingleChoiceItems(arr, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        d.dismiss();
                        cb.onChosen(arr[w]);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void toast(String s) {
        Toast.makeText(mapView.getContext(), s, Toast.LENGTH_SHORT).show();
    }

    private void hideKeyboard() {
        try {
            final InputMethodManager imm = (InputMethodManager) mapView.getContext()
                    .getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null)
                imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
        } catch (RuntimeException ignored) {
        }
    }

    // ---- preferences ----------------------------------------------------------

    private SharedPreferences prefs() {
        return PreferenceManager.getDefaultSharedPreferences(mapView.getContext());
    }

    private void restoreUi() {
        final String st = prefs().getString(PREF_STATE, "CA");
        state = st == null || st.isEmpty() ? null : st;
        fromMapCenter = prefs().getBoolean(PREF_FROM_MAP, false);
        mapOn = prefs().getBoolean(PREF_MAP_ON, true);
        agenciesOff.clear();
        for (String a : prefs().getString(PREF_AGENCIES_OFF, "").split("\\|"))
            if (!a.isEmpty())
                agenciesOff.add(a);
    }

    private void rememberAgencies() {
        final StringBuilder b = new StringBuilder();
        for (String a : agenciesOff) {
            if (b.length() > 0)
                b.append('|');
            b.append(a);
        }
        prefs().edit().putString(PREF_AGENCIES_OFF, b.toString()).apply();
    }

    private static String radiusLabel(int r) {
        return r == 0 ? "Whole state" : String.format(Locale.US, "%d %s", r, Units.bigLabel());
    }

    private static String zoomPresetLabel(int i) {
        final double n = ZOOM_PRESET_BIG[i];
        return String.format(Locale.US, "%.0f %s  —  %s", n, Units.bigLabel(), ZOOM_PRESET_NAMES[i]);
    }

    private void updateButtons() {
        toggle.setText(mapOn ? "ON" : "OFF");
        toggle.setTextColor(mapOn ? 0xFF3DDC61 : 0xFFFF5B52);
        stateButton.setText(state == null ? "All states" : "State: " + state);
        fromButton.setText(fromMapCenter ? "From: Map center" : "From: Me");
        radiusButton.setText(radiusBig > 0 ? "Within " + radiusLabel((int) radiusBig) : "Within: whole state");
        final int n = layer.viewshedCount();
        viewshedsOff.setEnabled(n > 0);
        viewshedsOff.setText(n == 0 ? "No viewsheds shown"
                : String.format(Locale.US, "Turn off %d viewshed%s", n, n == 1 ? "" : "s"));
        viewshedRange.setText("Range: " + Math.round(prefs().getFloat(PREF_VIEWSHED_RANGE, 20)) + " " + Units.bigLabel());
        meViewshed.setText((fromMapCenter ? "From map center " : "From me ") + (meViewshedOn ? "ON" : "OFF"));
        meHeight.setText("Height: " + heightLabel(operatorHeightM()));
        meViewshed.setTextColor(meViewshedOn ? 0xFF3DDC61 : 0xFFFF5B52);
        viewshedNote.setText(meViewshedOn
                ? String.format(Locale.US, "Your viewshed is drawn from %s above the ground where you are. Line of sight, not radio coverage.",
                        heightLabel(operatorHeightM()))
                : pluginContext.getString(R.string.viewshed_note));
    }

    // ---- the operator's own viewshed ------------------------------------------------

    /** Where "from me" is right now: the device, or the map center by choice or with no fix. */
    private GeoPoint originPoint() {
        final GeoPoint me = fromMapCenter ? null : selfPoint();
        return me != null ? me : mapCenter();
    }

    /**
     * How high the operator's antenna is above the ground. The plugin's own setting,
     * not ATAK's viewshed-tool height: that one was found at 1,000 ft on the XCover
     * from an earlier experiment, and a handheld drawn from 1,000 ft up sees
     * everything. Handheld by default.
     */
    private double operatorHeightM() {
        try {
            final float v = prefs().getFloat(PREF_ME_HEIGHT, (float) DEFAULT_ME_HEIGHT_M);
            return v > 0 && v < 1000 ? v : DEFAULT_ME_HEIGHT_M;
        } catch (RuntimeException ignored) {
            return DEFAULT_ME_HEIGHT_M;
        }
    }

    private static String heightPresetLabel(int i) {
        final boolean metric = Units.type() == Span.METRIC;
        final double v = metric ? HEIGHT_PRESETS_M[i] : HEIGHT_PRESETS_FT[i];
        final String num = v == Math.floor(v) ? String.format(Locale.US, "%.0f", v) : String.format(Locale.US, "%.1f", v);
        return num + (metric ? " m" : " ft") + "  —  " + HEIGHT_NAMES[i];
    }

    private static double heightPresetMeters(int i) {
        return Units.type() == Span.METRIC ? HEIGHT_PRESETS_M[i] : HEIGHT_PRESETS_FT[i] * 0.3048;
    }

    private static String heightLabel(double meters) {
        if (Units.type() == Span.METRIC)
            return String.format(Locale.US, "%.1f m", meters);
        return String.format(Locale.US, "%.0f ft", SpanUtilities.convert(meters, Span.METER, Span.FOOT));
    }

    /** Draw (or move) the operator's viewshed and check line of sight to every site in range. */
    private void refreshMeViewshed() {
        final GeoPoint from = originPoint();
        if (from == null) {
            toast("No position to draw from");
            return;
        }
        meFrom = from;
        if (!layer.showMeViewshed(from, operatorHeightM())) {
            toast("ATAK could not draw the viewshed");
            return;
        }
        runLineOfSight(from);
    }

    /**
     * Off the main thread: every site in the state within the viewshed range gets a
     * line-of-sight test over the device's elevation data. A few hundred sites is a
     * few seconds on a slow phone; the status line says it is working.
     */
    private void runLineOfSight(final GeoPoint from) {
        final Catalog c = store.catalog();
        if (c == null)
            return;
        final int generation = ++losGeneration;
        final double range = layer.getViewshedRangeMeters();
        final double h0 = operatorHeightM();
        final List<Site> candidates = new ArrayList<>();
        for (Site s : c.sites) {
            if (state != null && !s.st.equals(state))
                continue;
            if (distance(from, new GeoPoint(s.lat, s.lon)) <= range)
                candidates.add(s);
        }
        losPending = true;
        apply();
        losWorker.execute(new Runnable() {
            @Override
            public void run() {
                final Map<String, Boolean> out = new java.util.HashMap<>();
                final long started = System.currentTimeMillis();
                int unknown = 0;
                for (Site s : candidates) {
                    if (generation != losGeneration)
                        return;         // superseded by a newer request
                    final GeoPoint to = new GeoPoint(s.lat, s.lon);
                    final Boolean r = LineOfSight.clear(from, h0, to, SiteLayer.antennaHeight(s),
                            distance(from, to));
                    if (r == null)
                        unknown++;
                    else
                        out.put(s.id, r);
                }
                final int finalUnknown = unknown;
                int seenCount = 0;
                for (Boolean b : out.values())
                    if (b)
                        seenCount++;
                Log.d(TAG, String.format(Locale.US, "line of sight: %d of %d sites within %.0f m, %d unknown, %d ms",
                        seenCount, candidates.size(), range, unknown, System.currentTimeMillis() - started));
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (generation != losGeneration || !meViewshedOn)
                            return;
                        los.clear();
                        los.putAll(out);
                        losUnknown = finalUnknown;
                        losChecked = candidates.size();
                        losPending = false;
                        layer.setLineOfSight(los);
                        apply();
                    }
                });
            }
        });
    }

    /** "yes", "no", "not checked" or "unknown" for one site, when the operator's viewshed is on. */
    private String lineOfSightWord(Site s) {
        if (!meViewshedOn)
            return null;
        if (losPending)
            return "checking";
        final Boolean r = los.get(s.id);
        if (r != null)
            return r ? "yes" : "no";
        if (meFrom != null && distance(meFrom, new GeoPoint(s.lat, s.lon)) > layer.getViewshedRangeMeters())
            return "beyond the range";
        return "unknown, no elevation data";
    }

    // ---- zoom gate --------------------------------------------------------------

    private double scaleBarPixels() {
        final double res = mapView.getMapResolution();
        return res > 0 ? ScaleBar.meters(mapView) / res : 200;
    }

    /** First run: the "region" preset. */
    private double defaultZoomThreshold() {
        return Units.bigToMeters(ZOOM_PRESET_BIG[3]) / scaleBarPixels();
    }

    private void setThreshold(double metersPerPixel) {
        layer.setMaxResolution(metersPerPixel);
        prefs().edit().putFloat(PREF_ZOOM, metersPerPixel == Double.MAX_VALUE ? Float.MAX_VALUE
                : (float) metersPerPixel).apply();
        updateZoomLabel();
        updateStatus();
    }

    private void updateZoomLabel() {
        final double limit = layer.getMaxResolution();
        final String now = ScaleBar.text(mapView);
        if (limit >= Float.MAX_VALUE / 2) {
            zoomLabel.setText("Sites are always drawn · scale bar now " + now);
            return;
        }
        zoomLabel.setText("Sites draw when the scale bar reads "
                + Units.formatBig(limit * scaleBarPixels()) + " or less · now " + now
                + (layer.isWithinZoom() ? "" : " (zoom in to see them)"));
    }

    // ---- where from -------------------------------------------------------------

    /** Where the device is, or null without a fix. */
    private GeoPoint selfPoint() {
        try {
            final com.atakmap.android.maps.Marker self = mapView.getSelfMarker();
            final GeoPoint p = self == null ? null : self.getPoint();
            if (p == null || !p.isValid() || (p.getLatitude() == 0 && p.getLongitude() == 0))
                return null;
            return p;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private GeoPoint mapCenter() {
        try {
            return mapView.getPoint().get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static double distance(GeoPoint a, GeoPoint b) {
        try {
            return GeoCalculations.distanceTo(a, b);
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    private static double bearing(GeoPoint a, GeoPoint b) {
        try {
            double d = GeoCalculations.bearingTo(a, b);
            while (d < 0)
                d += 360;
            return d % 360;
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    // ---- filtering ----------------------------------------------------------------

    /** A row of the list: a site with its distance, or a net card. */
    static final class Row {
        final Site site;
        final Net net;
        final Channel matched;
        final double meters, bearing;

        Row(Site site, Channel matched, double meters, double bearing) {
            this.site = site;
            this.net = null;
            this.matched = matched;
            this.meters = meters;
            this.bearing = bearing;
        }

        Row(Net net) {
            this.site = null;
            this.net = net;
            this.matched = null;
            this.meters = Double.NaN;
            this.bearing = Double.NaN;
        }
    }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.US).replace('-', ' ').replaceAll("\\s+", " ").trim();
    }

    private static boolean netMatches(Net n, String q) {
        if (norm(n.id).contains(q) || norm(n.name).contains(q) || norm(n.remarks).contains(q))
            return true;
        if (q.matches("\\d{3}(\\.\\d*)?") && (n.rx.startsWith(q) || n.tx.startsWith(q)))
            return true;
        return Tones.matches(n.rxTone, q) || Tones.matches(n.txTone, q);
    }

    /** The first channel on a site the query names, or null. */
    private static Channel matchChannel(Site s, String q) {
        for (Channel c : s.channels) {
            if (netMatches(c.net, q) || norm(c.callsign).contains(q)
                    || Tones.matches(c.txTone, q) || Tones.matches(c.rxTone, q))
                return c;
            if (q.matches("\\d{3}(\\.\\d*)?") && (c.rx().startsWith(q) || c.tx().startsWith(q)))
                return c;
        }
        return null;
    }

    private static boolean siteMatches(Site s, String q) {
        if (norm(s.name).contains(q) || (!s.county.isEmpty() && norm(s.county).contains(q)))
            return true;
        for (String m : s.managers)
            if (norm(m).contains(q))
                return true;
        return false;
    }

    private boolean agencyOn(Site s) {
        if (agenciesOff.isEmpty())
            return true;
        for (String a : s.agencies)
            if (!agenciesOff.contains(a))
                return true;
        return s.agencies.isEmpty();
    }

    private void apply() {
        final Catalog c = store.catalog();
        if (c == null)
            return;
        final String q = norm(search.getText().toString());
        final GeoPoint me = fromMapCenter ? null : selfPoint();
        final boolean noFix = !fromMapCenter && me == null;
        final GeoPoint from = me != null ? me : mapCenter();
        lastFrom = from;
        final double radiusM = radiusBig > 0 ? Units.bigToMeters(radiusBig) : 0;

        final List<Row> rows = new ArrayList<>();
        int inState = 0;
        for (Site s : c.sites) {
            if (state != null && !s.st.equals(state))
                continue;
            inState++;
            if (!agencyOn(s))
                continue;
            final double d = from == null ? Double.NaN : distance(from, new GeoPoint(s.lat, s.lon));
            if (radiusM > 0 && !Double.isNaN(d) && d > radiusM)
                continue;
            Channel matched = null;
            if (!q.isEmpty()) {
                matched = matchChannel(s, q);
                if (matched == null && !siteMatches(s, q))
                    continue;
            }
            rows.add(new Row(s, matched, d, from == null ? Double.NaN : bearing(from, new GeoPoint(s.lat, s.lon))));
        }
        Collections.sort(rows, new Comparator<Row>() {
            @Override
            public int compare(Row a, Row b) {
                if (Double.isNaN(a.meters) != Double.isNaN(b.meters))
                    return Double.isNaN(a.meters) ? 1 : -1;
                final int d = Double.compare(a.meters, b.meters);
                return d != 0 ? d : a.site.name.compareToIgnoreCase(b.site.name);
            }
        });

        // Nets the query names that have no fixed site here: the answer is the net
        // itself, with its pair and tone, rather than an empty list.
        final List<Row> cards = new ArrayList<>();
        Net named = null;
        if (!q.isEmpty()) {
            for (Net n : c.nets) {
                if (!netMatches(n, q))
                    continue;
                if (named == null)
                    named = n;
                boolean sited = false;
                for (Site s : n.sites)
                    if (state == null || s.st.equals(state)) {
                        sited = true;
                        break;
                    }
                if (!sited && cards.size() < MAX_NET_CARDS)
                    cards.add(new Row(n));
            }
        }

        final List<Site> forMap = new ArrayList<>();
        for (Row r : rows) {
            if (forMap.size() >= MAX_MAP)
                break;
            forMap.add(r.site);
        }
        layer.show(forMap);

        final List<Object> listRows = new ArrayList<>(cards.size() + rows.size() + 4);
        if (!cards.isEmpty()) {
            listRows.add(String.format(Locale.US, "Nets with no fixed site here (%,d)", cards.size()));
            listRows.addAll(cards);
        }
        int seen = 0;
        if (meViewshedOn && !losPending) {
            // Three sections: what you can see, what you cannot, what is out of range.
            final List<Row> yes = new ArrayList<>(), no = new ArrayList<>(), far = new ArrayList<>();
            for (Row r : rows) {
                final Boolean v = los.get(r.site.id);
                if (v != null && v)
                    yes.add(r);
                else if (v != null)
                    no.add(r);
                else
                    far.add(r);
            }
            seen = yes.size();
            listRows.add(String.format(Locale.US, "Line of sight from %s (%,d)", fromMapCenter ? "the map center" : "you", yes.size()));
            listRows.addAll(yes);
            if (!no.isEmpty()) {
                listRows.add(String.format(Locale.US, "No line of sight (%,d)", no.size()));
                listRows.addAll(no);
            }
            if (!far.isEmpty()) {
                listRows.add(String.format(Locale.US, "Beyond %s, not checked (%,d)",
                        Units.formatBig(layer.getViewshedRangeMeters()), far.size()));
                listRows.addAll(far);
            }
        } else {
            if (!cards.isEmpty())
                listRows.add(String.format(Locale.US, "Sites (%,d)", rows.size()));
            listRows.addAll(rows);
        }
        adapter.set(listRows);
        updateAgencyCounts(c, inState);

        // Say what is and is not being shown.
        final StringBuilder b = new StringBuilder();
        b.append(String.format(Locale.US, "%,d site%s", rows.size(), rows.size() == 1 ? "" : "s"));
        if (radiusM > 0 && from != null)
            b.append(" within ").append(radiusLabel((int) radiusBig));
        if (!q.isEmpty())
            b.append(" match “").append(search.getText().toString().trim()).append("”");
        if (!cards.isEmpty())
            b.append(String.format(Locale.US, " · %d net%s with no fixed site here, listed first",
                    cards.size(), cards.size() == 1 ? "" : "s"));
        if (meViewshedOn) {
            if (losPending)
                b.append(" · checking line of sight…");
            else {
                b.append(String.format(Locale.US, " · %d of %d within %s have line of sight from %s",
                        seen, losChecked, Units.formatBig(layer.getViewshedRangeMeters()),
                        fromMapCenter ? "the map center" : "you"));
                if (losUnknown > 0)
                    b.append(String.format(Locale.US, ", %d unknown (no elevation data)", losUnknown));
            }
        }
        if (!mapOn)
            b.append(" · map OFF");
        else if (rows.size() > MAX_MAP)
            b.append(String.format(Locale.US, " · map shows nearest %,d, zoom in or narrow the search", MAX_MAP));
        else if (!layer.isWithinZoom())
            b.append(" · zoom in to see them on the map");
        if (noFix && from != null)
            b.append(" · no GPS fix, measured from the map center");
        else if (from == null)
            b.append(" · nowhere to measure from, sorted by name");
        status.setTextColor(noFix ? STATUS_WARN : statusColor);
        status.setText(b.toString());
    }

    private void updateStatus() {
        apply();
    }

    /** One checkbox per agency the catalog knows, carrying how many sites it would show. */
    private void updateAgencyCounts(Catalog c, int inState) {
        final Map<String, Integer> counts = new LinkedHashMap<>();
        for (Site s : c.sites) {
            if (state != null && !s.st.equals(state))
                continue;
            for (String a : s.agencies)
                counts.put(a, counts.containsKey(a) ? counts.get(a) + 1 : 1);
        }
        final List<String> names = new ArrayList<>(counts.keySet());
        Collections.sort(names, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                final int d = counts.get(b) - counts.get(a);
                return d != 0 ? d : a.compareToIgnoreCase(b);
            }
        });
        boolean rebuild = names.size() != agencyBoxes.size();
        for (String n : names)
            if (!agencyBoxes.containsKey(n))
                rebuild = true;
        if (rebuild) {
            agencyBox.removeAllViews();
            agencyBoxes.clear();
            LinearLayout rowView = null;
            for (final String a : names) {
                if (rowView == null || rowView.getChildCount() == 2) {
                    rowView = new LinearLayout(mapView.getContext());
                    rowView.setOrientation(LinearLayout.HORIZONTAL);
                    agencyBox.addView(rowView, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                }
                final CheckBox cb = new CheckBox(mapView.getContext());
                cb.setChecked(!agenciesOff.contains(a));
                cb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton b, boolean checked) {
                        if (checked)
                            agenciesOff.remove(a);
                        else
                            agenciesOff.add(a);
                        rememberAgencies();
                        apply();
                    }
                });
                rowView.addView(cb, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                agencyBoxes.put(a, cb);
            }
        }
        for (Map.Entry<String, CheckBox> e : agencyBoxes.entrySet()) {
            final Integer n = counts.get(e.getKey());
            e.getValue().setText(String.format(Locale.US, "%s (%,d)", e.getKey(), n == null ? 0 : n));
        }
    }

    private void busy(String message) {
        status.setTextColor(STATUS_BUSY);
        status.setText(message);
    }

    // ---- store callbacks ---------------------------------------------------------

    @Override
    public void onCatalog(Catalog catalog, String s) {
        if (catalog == null) {
            status.setTextColor(STATUS_WARN);
            status.setText(s);
            return;
        }
        // A remembered state the catalog no longer has falls back to all.
        if (state != null && !catalog.states().contains(state))
            state = null;
        updateButtons();
        apply();
        if (syncPressed) {
            syncPressed = false;
            toast(s);
        }
    }

    @Override
    public void onFetchFailed(String s) {
        apply();
        // Only when the operator asked: the daily refresh failing quietly is the
        // status line's job, not a toast over the map every time the pane opens.
        if (syncPressed) {
            syncPressed = false;
            toast(s);
        }
    }

    // ---- layer callbacks ---------------------------------------------------------

    @Override
    public void onDetails(Site site) {
        showDetail(site);
    }

    @Override
    public void onViewshedChanged() {
        updateButtons();
    }

    // ---- formatting ----------------------------------------------------------------

    private static String distanceLine(Row r) {
        if (Double.isNaN(r.meters))
            return "";
        final StringBuilder b = new StringBuilder(Units.format(r.meters));
        if (!Double.isNaN(r.bearing))
            b.append(String.format(Locale.US, " · %03.0f°", r.bearing));
        return b.toString();
    }

    private static String elevation(Site s) {
        double m = s.elevM;
        if (Double.isNaN(m)) {
            try {
                final double hae = com.atakmap.android.util.ATAKUtilities.getElevation(s.lat, s.lon, null);
                if (GeoPoint.isAltitudeValid(hae))
                    m = com.atakmap.coremap.maps.conversion.EGM96.getMSL(s.lat, s.lon, hae);
            } catch (LinkageError | RuntimeException ignored) {
            }
        }
        if (Double.isNaN(m))
            return "";
        if (Units.type() == Span.METRIC)
            return String.format(Locale.US, "%,.0f m", m);
        return String.format(Locale.US, "%,.0f ft", SpanUtilities.convert(m, Span.METER, Span.FOOT));
    }

    /**
     * "CDF Command 5 · Tone 8 (103.5) · KMF695". No frequencies on a site: the radio
     * already knows the net's pair, and what the operator needs from the map is which
     * tone opens this site (operator, 2026-09-10).
     */
    static String channelLine(Channel c) {
        final StringBuilder b = new StringBuilder();
        // The designator alone: it is what the channel is called and what the radio
        // display says. The plans' descriptive column ("OES Fire V4 (Previously OES
        // 2B)") is kept for the search to match on and is not put on screen.
        b.append(c.net.id);
        b.append(" · ").append(toneLine(c.effectiveRxTone(), c.net.rxToneText, c.effectiveTxTone(), c.net.txToneText));
        if (!c.callsign.isEmpty())
            b.append(" · ").append(c.callsign);
        return b.toString();
    }

    static String netLine(Net n) {
        return toneLine(n.rxTone, n.rxToneText, n.txTone, n.txToneText);
    }

    /**
     * The tone that opens this repeater, formatted where the tone table lives so a
     * test can reach it. The access tone is the one a mobile transmits, which is the
     * site's own; the receive tone belongs to the net and is the same everywhere on
     * it, so it is not shown.
     */
    static String toneLine(double rx, String rxText, double tx, String txText) {
        return Tones.line(tx, txText, rx, rxText);
    }

    private static String netsSummary(Site s) {
        if (s.channels.isEmpty())
            return "no nets recorded";
        final StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.channels.size() && i < 6; i++) {
            if (i > 0)
                b.append(" · ");
            b.append(s.channels.get(i).netId);
        }
        if (s.channels.size() > 6)
            b.append(String.format(Locale.US, " +%d", s.channels.size() - 6));
        return b.toString();
    }

    // ---- site detail --------------------------------------------------------------

    private void showDetail(final Site s) {
        if (s == null || detailHost == null)
            return;
        openSite = s;
        final View v = PluginLayoutInflater.inflate(pluginContext, R.layout.site_detail, null);
        final TextView info = v.findViewById(R.id.info);
        final LinearLayout nets = v.findViewById(R.id.nets);
        final Button viewshed = v.findViewById(R.id.viewshed);

        final StringBuilder b = new StringBuilder();
        b.append(s.name).append('\n');
        if (!s.county.isEmpty())
            b.append(s.county).append(" County, ");
        b.append(s.st);
        final String elev = elevation(s);
        if (!elev.isEmpty())
            b.append(" · ").append(elev);
        final GeoPoint from = fromMapCenter ? mapCenter() : (selfPoint() != null ? selfPoint() : mapCenter());
        if (from != null) {
            final GeoPoint p = new GeoPoint(s.lat, s.lon);
            b.append('\n').append(Units.format(distance(from, p)))
                    .append(String.format(Locale.US, " · %03.0f°", bearing(from, p)))
                    .append(fromMapCenter || selfPoint() == null ? " from the map center" : " from you");
        }
        if (!s.managers.isEmpty()) {
            b.append('\n');
            for (int i = 0; i < s.managers.size(); i++)
                b.append(i > 0 ? " · " : "").append(s.managers.get(i));
        }
        final String losWord = lineOfSightWord(s);
        if (losWord != null)
            b.append("\nLine of sight from ").append(fromMapCenter ? "the map center: " : "you: ").append(losWord);
        b.append(String.format(Locale.US, "\n%.5f, %.5f", s.lat, s.lon));
        info.setText(b.toString());

        nets.removeAllViews();
        if (s.channels.isEmpty()) {
            final TextView none = new TextView(mapView.getContext());
            none.setText("No nets recorded for this site yet.");
            none.setTextSize(12);
            none.setPadding(0, 8, 0, 8);
            nets.addView(none);
        }
        for (Channel c : s.channels) {
            final View row = PluginLayoutInflater.inflate(pluginContext, R.layout.net_row, null);
            ((TextView) row.findViewById(R.id.title)).setText(
                    c.net.id);
            ((TextView) row.findViewById(R.id.line)).setText(
                    toneLine(c.effectiveRxTone(), c.net.rxToneText, c.effectiveTxTone(), c.net.txToneText)
                    + (c.callsign.isEmpty() ? "" : " · " + c.callsign));
            final StringBuilder n = new StringBuilder();
            if (c.net.portable)
                n.append("portable repeater, deployed per incident");
            if (!c.notes.isEmpty())
                n.append(n.length() > 0 ? " · " : "").append(c.notes);
            // Where a row came from is NOT shown. Some of this catalog is built from
            // material that is not ours to point at, and naming it on screen puts it
            // in every screenshot. The catalog carries the source per channel so a
            // disagreement between two of them can be traced off the device; the
            // operator on the map wants the mountain and the tone.
            final TextView note = row.findViewById(R.id.note);
            note.setText(n.toString());
            note.setVisibility(n.length() == 0 ? View.GONE : View.VISIBLE);
            nets.addView(row);
        }

        final TextView vsNote = v.findViewById(R.id.viewshed_note);
        vsNote.setText(String.format(Locale.US,
                "Viewshed is line of sight from the antenna (%.0f m above ground, %s range), not radio coverage. Needs elevation data on the device.",
                SiteLayer.antennaHeight(s), Units.formatBig(layer.getViewshedRangeMeters())));

        styleViewshedButton(viewshed, s);
        viewshed.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View bv) {
                layer.toggleViewshed(s);
                styleViewshedButton(viewshed, s);
                updateButtons();
                if (layer.isViewshedOn(s))
                    layer.goTo(s);
            }
        });
        v.findViewById(R.id.goto_site).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View bv) {
                layer.goTo(s);
            }
        });
        v.findViewById(R.id.close_pane).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View bv) {
                openSite = null;
                detailHost.hideDetailPane();
            }
        });
        detailHost.showDetailPane(v);
    }

    private void styleViewshedButton(Button b, Site s) {
        final boolean on = layer.isViewshedOn(s);
        b.setText(on ? "Viewshed ON" : "Viewshed OFF");
        b.setTextColor(on ? 0xFF3DDC61 : 0xFFFF5B52);
    }

    // ---- list ---------------------------------------------------------------------

    private final class RowAdapter extends BaseAdapter {

        private static final int TYPE_HEADING = 0;
        private static final int TYPE_SITE = 1;
        private static final int TYPE_NET = 2;

        /** Each entry is a heading String, a site Row or a net Row. */
        private List<Object> rows = new ArrayList<>();

        void set(List<Object> next) {
            rows = next;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int i) {
            return rows.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public int getViewTypeCount() {
            return 3;
        }

        @Override
        public int getItemViewType(int i) {
            final Object o = rows.get(i);
            if (o instanceof String)
                return TYPE_HEADING;
            return ((Row) o).site != null ? TYPE_SITE : TYPE_NET;
        }

        @Override
        public boolean areAllItemsEnabled() {
            return false;
        }

        @Override
        public boolean isEnabled(int i) {
            return !(rows.get(i) instanceof String);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final int type = getItemViewType(position);
            if (type == TYPE_HEADING) {
                View h = convertView;
                if (h == null)
                    h = PluginLayoutInflater.inflate(pluginContext, R.layout.list_section, null);
                ((TextView) h.findViewById(R.id.section)).setText((String) rows.get(position));
                return h;
            }
            final Row r = (Row) rows.get(position);
            if (type == TYPE_NET) {
                View v = convertView;
                if (v == null)
                    v = PluginLayoutInflater.inflate(pluginContext, R.layout.net_row, null);
                final Net n = r.net;
                ((TextView) v.findViewById(R.id.title)).setText(n.id);
                ((TextView) v.findViewById(R.id.line)).setText(netLine(n));
                final TextView note = v.findViewById(R.id.note);
                note.setText(n.portable ? "portable repeater, deployed per incident"
                        : (n.simplex() ? "simplex, no repeater site" : "no site recorded for this net yet"));
                note.setVisibility(View.VISIBLE);
                return v;
            }
            View v = convertView;
            if (v == null)
                v = PluginLayoutInflater.inflate(pluginContext, R.layout.site_row, null);
            final Site s = r.site;
            ((TextView) v.findViewById(R.id.name)).setText(s.name);
            final StringBuilder d = new StringBuilder();
            final String dist = distanceLine(r);
            if (!dist.isEmpty())
                d.append(dist).append("  ");
            if (!s.county.isEmpty())
                d.append(s.county);
            d.append('\n');
            d.append(r.matched != null ? channelLine(r.matched) : netsSummary(s));
            ((TextView) v.findViewById(R.id.detail)).setText(d.toString());
            v.findViewById(R.id.goto_btn).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View b) {
                    layer.goTo(s);
                }
            });
            // The list runs with setItemsCanFocus(true) for the search box, so a row
            // with a Button in it never delivers OnItemClickListener; the row itself
            // is the click target.
            v.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View b) {
                    showDetail(s);
                }
            });
            return v;
        }
    }
}

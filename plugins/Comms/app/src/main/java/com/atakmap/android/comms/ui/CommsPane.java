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
import com.atakmap.android.comms.data.Nets;
import com.atakmap.android.comms.data.Tones;
import com.atakmap.android.comms.data.Units;
import com.atakmap.android.comms.map.Reach;
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
 * site, so "closest Command 1" is answered by the list itself.
 *
 * <p>Everything in here has a location. The catalog carries no net that is on no
 * site, so there is never a row that cannot be gone to -- no cards for incident
 * portables, no channels that exist only in a plan.
 */
public final class CommsPane implements CatalogStore.Listener, SiteLayer.Listener {

    private static final String TAG = "CommsPane";

    /**
     * The map draws this many of the nearest sites; the status line says when it
     * stopped.
     *
     * <p>It was 300, set when the catalog was expected to run to thousands. A
     * California catalog is 387, so the cap was quietly discarding the farthest 87 --
     * and "farthest" from anywhere in the middle of the state means the far north and
     * the far south. Red Mtn in Del Norte was in the discarded tail while its row sat
     * in the list, which reads as a missing site rather than a trimmed one. The cap
     * is a guard against a catalog that grows several states wide, not something a
     * one-state catalog should ever meet.
     */
    private static final int MAX_MAP = 1000;

    // Standing preferences: where you work and what you care to see. The search box
    // and the radius are per-task and deliberately not remembered.
    private static final String PREF_STATE = "comms_state";
    private static final String PREF_FROM_MAP = "comms_from_map";
    private static final String PREF_AGENCIES_OFF = "comms_agencies_off";
    private static final String PREF_ZOOM = "comms_zoom_threshold";
    private static final String PREF_CHECK_RANGE = "comms_viewshed_range_big";
    private static final String PREF_ONLY_LIKELY = "comms_only_likely";
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
    /** How far out to check, in the big unit. */
    private static final int[] RANGE_PRESETS = { 5, 10, 20, 30, 50 };
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
    private final Button checkRange, meReach, meHeight, onlyLikelyButton, sync;
    private final TextView reachNote;
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
     * Which sites a handheld here is likely to open. Per task, not remembered: an
     * answer for where you stood yesterday is not one you asked for.
     */
    private boolean meReachOn;
    /**
     * Show only the sites worth trying. Off by default, and it does nothing until the
     * check has run: the operator asked for it looking at a screen of repeaters where
     * a handful were green (2026-09-11).
     */
    private boolean onlyLikely;
    private GeoPoint meFrom;          // where the answers were worked out from
    private final Map<String, Reach.State> reach = new java.util.HashMap<>();
    private boolean reachPending;
    private int reachUnknown, reachChecked;
    /**
     * How the check is getting on, for the status line. A minute of "working it
     * out..." with nothing moving is indistinguishable from a hang, and the operator
     * asked to be able to tell the difference (2026-09-11). Written by the worker,
     * read by the ticker, so both are volatile.
     */
    private volatile int reachDone, reachTotal;
    private volatile long reachStarted;
    /** Repaints the counter while the check runs, and stops itself when it ends. */
    private final Runnable reachTick = new Runnable() {
        @Override
        public void run() {
            if (!reachPending)
                return;
            status.setTextColor(STATUS_BUSY);
            status.setText(reachProgress());
            handler.postDelayed(this, 500);
        }
    };
    private int reachGeneration;
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

    /**
     * Following the operator: re-sort when the self marker moves.
     *
     * <p>This was a 20 second poll with a 250 m threshold, which is invisible to
     * someone who has just dragged their own marker to see the list change -- the
     * operator did exactly that with GPS off and read it as broken. ATAK tells us
     * when the marker moves, so there is nothing to poll for; 50 m is enough to
     * absorb a fix jittering in place without churning the list.
     *
     * <p>The callback does not arrive on the main thread. Touching a View from it
     * is a native SIGSEGV with no Java stack trace, the same trap as
     * {@code onMapMoved}, so it posts and coalesces.
     */
    private GeoPoint lastFrom;
    private static final double FOLLOW_M = 50;
    private final Runnable selfTick = new Runnable() {
        @Override
        public void run() {
            if (fromMapCenter)
                return;
            final GeoPoint me = selfPoint();
            if (me == null || (lastFrom != null && distance(me, lastFrom) <= FOLLOW_M))
                return;
            apply();
            if (meReachOn)
                refreshReach();
        }
    };

    private final com.atakmap.android.maps.PointMapItem.OnPointChangedListener selfWatch =
            new com.atakmap.android.maps.PointMapItem.OnPointChangedListener() {
                @Override
                public void onPointChanged(com.atakmap.android.maps.PointMapItem item) {
                    handler.removeCallbacks(selfTick);
                    handler.postDelayed(selfTick, 300);
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
        checkRange = controls.findViewById(R.id.viewshed_range);
        meReach = controls.findViewById(R.id.me_viewshed);
        onlyLikelyButton = controls.findViewById(R.id.only_likely);
        meHeight = controls.findViewById(R.id.me_height);
        reachNote = controls.findViewById(R.id.viewshed_note);
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
        layer.setCheckRangeMeters(Units.bigToMeters(prefs().getFloat(PREF_CHECK_RANGE, 20)));
        updateButtons();
        updateZoomLabel();
        mapView.addOnMapMovedListener(mapWatch);
        watchSelf(true);
        busy("Loading catalog…");
        store.load();
    }

    public View getView() {
        return root;
    }

    public void setDetailHost(DetailHost h) {
        detailHost = h;
    }

    /**
     * Set the moment the pane is torn down. ATAK can leave the view on screen after
     * that -- reloading a plugin is the everyday way it happens -- and a tap on a
     * dead pane then reaches a shut down worker. On 2026-09-11 that took ATAK down
     * with a RejectedExecutionException from the From me button, because the plugin
     * had just been reinstalled underneath an open pane. Nothing that outlives
     * dispose may do work; it may only do nothing quietly.
     */
    private volatile boolean disposed;

    public void dispose() {
        disposed = true;
        handler.removeCallbacks(mapTick);
        handler.removeCallbacks(selfTick);
        handler.removeCallbacks(reachTick);
        watchSelf(false);
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

        checkRange.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final List<String> names = new ArrayList<>();
                for (int r : RANGE_PRESETS)
                    names.add(r + " " + Units.bigLabel());
                final int cur = (int) Math.round(prefs().getFloat(PREF_CHECK_RANGE, 20));
                choose("Check sites out to", names, cur + " " + Units.bigLabel(), new Chosen() {
                    @Override
                    public void onChosen(String value) {
                        final int r = Integer.parseInt(value.split(" ")[0]);
                        prefs().edit().putFloat(PREF_CHECK_RANGE, r).apply();
                        layer.setCheckRangeMeters(Units.bigToMeters(r));
                        updateButtons();
                        if (meReachOn)
                            refreshReach();
                        else
                            toast("Applies the next time you turn it on");
                    }
                });
            }
        });

        meReach.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (meReachOn) {
                    meReachOn = false;
                    meFrom = null;
                    reachGeneration++;
                    reachPending = false;
                    reach.clear();
                    layer.setReach(null);
                    updateButtons();
                    apply();
                    return;
                }
                if (originPoint() == null) {
                    toast("No position to check from");
                    return;
                }
                meReachOn = true;
                refreshReach();
                updateButtons();
            }
        });

        onlyLikelyButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onlyLikely = !onlyLikely;
                prefs().edit().putBoolean(PREF_ONLY_LIKELY, onlyLikely).apply();
                if (onlyLikely && !meReachOn)
                    toast("Turn the check on and this hides everything it does not like");
                updateButtons();
                apply();
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
                        if (meReachOn)
                            refreshReach();
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
        onlyLikely = prefs().getBoolean(PREF_ONLY_LIKELY, false);
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
        checkRange.setText("Range: " + Math.round(prefs().getFloat(PREF_CHECK_RANGE, 20)) + " " + Units.bigLabel());
        meReach.setText((fromMapCenter ? "From map center " : "From me ") + (meReachOn ? "ON" : "OFF"));
        meHeight.setText("Height: " + heightLabel(operatorHeightM()));
        meReach.setTextColor(meReachOn ? 0xFF3DDC61 : 0xFFFF5B52);
        onlyLikelyButton.setText("Only likely " + (onlyLikely ? "ON" : "OFF"));
        onlyLikelyButton.setTextColor(onlyLikely ? 0xFF3DDC61 : 0xFFFF5B52);
        // Never disabled. Greying it out while the check is off trapped the operator
        // with it stuck ON and no way back (2026-09-11: "if i turn from me off and
        // only likely is still on i want to be able to turn that off. right now i
        // cant"). A control that can be turned on has to be turnable off from the
        // same place, whatever else is switched.
        reachNote.setText(meReachOn
                ? String.format(Locale.US, "Worked out for a handheld %s above the ground where you are, over the terrain in between. A guide, not a promise \u2014 try the radio.",
                        heightLabel(operatorHeightM()))
                : pluginContext.getString(R.string.viewshed_note));
    }

    // ---- what a handheld here can reach ---------------------------------------------

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

    /** Work out which sites a handheld here is likely to open. */
    private void refreshReach() {
        if (disposed)
            return;
        final GeoPoint from = originPoint();
        if (from == null) {
            toast("No position to check from");
            return;
        }
        meFrom = from;
        runReachCheck(from);
    }

    /**
     * Off the main thread: every site in the state within the range gets its terrain
     * profile walked and its path loss worked out. A few hundred sites is a few
     * seconds on a slow phone; the status line says it is working.
     */
    private void runReachCheck(final GeoPoint from) {
        final Catalog c = store.catalog();
        if (c == null || disposed)
            return;
        final int generation = ++reachGeneration;
        final double range = layer.getCheckRangeMeters();
        final double h0 = operatorHeightM();
        final List<Site> candidates = new ArrayList<>();
        for (Site s : c.sites) {
            if (state != null && !s.st.equals(state))
                continue;
            if (distance(from, new GeoPoint(s.lat, s.lon)) <= range)
                candidates.add(s);
        }
        reachPending = true;
        reachDone = 0;
        reachTotal = candidates.size();
        reachStarted = System.currentTimeMillis();
        apply();
        handler.removeCallbacks(reachTick);
        handler.post(reachTick);
        final Runnable work = new Runnable() {
            @Override
            public void run() {
                final Map<String, Reach.State> out = new java.util.HashMap<>();
                final long started = System.currentTimeMillis();
                int unknown = 0;
                for (Site s : candidates) {
                    if (generation != reachGeneration)
                        return;         // superseded by a newer request
                    reachDone++;
                    final GeoPoint to = new GeoPoint(s.lat, s.lon);
                    final Reach.State r = Reach.to(from, h0, to, SiteLayer.antennaHeight(s),
                            distance(from, to));
                    if (r == Reach.State.UNKNOWN)
                        unknown++;
                    else
                        out.put(s.id, r);
                }
                final int finalUnknown = unknown;
                int likely = 0;
                for (Reach.State b : out.values())
                    if (b == Reach.State.LIKELY)
                        likely++;
                Log.d(TAG, String.format(Locale.US, "reach: %d of %d sites likely within %.0f m, %d unknown, %d ms",
                        likely, candidates.size(), range, unknown, System.currentTimeMillis() - started));
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (generation != reachGeneration || !meReachOn)
                            return;
                        reach.clear();
                        reach.putAll(out);
                        reachUnknown = finalUnknown;
                        reachChecked = candidates.size();
                        reachPending = false;
                        handler.removeCallbacks(reachTick);
                        layer.setReach(reach);
                        apply();
                    }
                });
            }
        };
        try {
            losWorker.execute(work);
        } catch (java.util.concurrent.RejectedExecutionException shuttingDown) {
            // The pool is gone, so the check cannot run and must not look as if it is.
            Log.w(TAG, "reach check not started: the worker is shut down", shuttingDown);
            reachPending = false;
            handler.removeCallbacks(reachTick);
            apply();
        }
    }

    /** "working out what you can reach... 23 of 46, 12s" while the check runs. */
    private String reachProgress() {
        final long secs = (System.currentTimeMillis() - reachStarted) / 1000;
        final int total = reachTotal;
        if (total <= 0)
            return String.format(Locale.US, "Working out what you can reach\u2026 %ds", secs);
        return String.format(Locale.US, "Working out what you can reach\u2026 %d of %d, %ds",
                Math.min(reachDone, total), total, secs);
    }

    /** How one site reads on the details pane while the check is on, or null when it is off. */
    private String reachWord(Site s) {
        if (!meReachOn)
            return null;
        if (reachPending)
            return "checking";
        final Reach.State r = reach.get(s.id);
        if (r == Reach.State.LIKELY)
            return "likely";
        if (r == Reach.State.MARGINAL)
            return "marginal";
        if (r == Reach.State.UNLIKELY)
            return "unlikely";
        if (meFrom != null && distance(meFrom, new GeoPoint(s.lat, s.lon)) > layer.getCheckRangeMeters())
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
    /**
     * Attach to or detach from the self marker's point.
     *
     * <p>ATAK replaces the self marker when the device identity changes, so the
     * listener is attached to whatever marker is there now and re-attached on the
     * next start rather than held for the plugin's life.
     */
    private com.atakmap.android.maps.PointMapItem watched;

    private void watchSelf(boolean on) {
        try {
            if (watched != null) {
                watched.removeOnPointChangedListener(selfWatch);
                watched = null;
            }
            if (!on)
                return;
            final com.atakmap.android.maps.Marker self = mapView.getSelfMarker();
            if (self != null) {
                self.addOnPointChangedListener(selfWatch);
                watched = self;
            }
        } catch (LinkageError | RuntimeException e) {
            Log.w(TAG, "could not follow the self marker; the list will not re-sort as you move", e);
        }
    }

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

    /**
     * A designator with the shorthand taken out, for matching what people type.
     *
     * <p>"CDF C5" is written a dozen ways out loud and every one of them should find
     * it: CDF 5, cdf command 5, command 5, c5, CDF-C5. So the spoken words drop
     * ("command", "net", "channel", "repeater"), the C that prefixes a channel
     * number drops, and every space and dash goes. All three become "cdf5".
     *
     * <p>The operator, 2026-09-11: "we are not focusing on super strict if it
     * sounds like cdf command 5 list it".
     */
    static String spoken(String s) {
        String k = norm(s);
        k = k.replaceAll("\\b(command|comand|cmd|channel|chan|net|repeater|tac|tactical)\\b", " ");
        k = k.replaceAll("[^a-z0-9]", "");
        // After the spaces and dashes go, not before: the plan writes "VNC C-5" and
        // the record that has the sites writes "VNC C5 R", and only a C sitting
        // directly on a digit can be dropped without eating a letter of a name.
        return k.replaceAll("c(\\d)", "$1");
    }

    private static boolean netMatches(Net n, String q) {
        if (norm(n.id).contains(q) || norm(n.name).contains(q) || norm(n.remarks).contains(q))
            return true;
        // Loosely, the way it is said rather than the way it is printed. Only when
        // the query has something in it: an empty spoken form matches everything.
        final String sq = spoken(q);
        if (sq.length() >= 2 && (spoken(n.id).contains(sq) || spoken(n.name).contains(sq)))
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
        // Every control ends up here, so one check covers a tap that arrives after
        // the pane has been torn down but before ATAK has taken the view away.
        if (disposed)
            return;
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

        // Every net in the catalog is on a site, so a query that matches a net
        // always has sites to show. There is nothing to list that cannot be gone to.
        // The net the query names, preferring a portable: those are the only nets in
        // the catalog with no site, and a search that finds one has nothing to list,
        // so the status line is the whole answer.
        Net named = null;
        if (!q.isEmpty())
            for (Net n : c.nets)
                if (netMatches(n, q)) {
                    if (named == null)
                        named = n;
                    if (n.portable) {
                        named = n;
                        break;
                    }
                }

        // Only likely: once the check has run, put the rest away entirely, map and
        // list both. A marker for a repeater that cannot be worked from here is in
        // the way in either place. Nothing is hidden while the check is still running
        // or turned off, so the control can never empty the pane on its own.
        final int beforeOnlyLikely = rows.size();
        if (onlyLikely && meReachOn && !reachPending) {
            final List<Row> keep = new ArrayList<>();
            for (Row r : rows)
                if (reach.get(r.site.id) == Reach.State.LIKELY)
                    keep.add(r);
            rows.clear();
            rows.addAll(keep);
        }
        final int hiddenByOnlyLikely = beforeOnlyLikely - rows.size();

        final List<Site> forMap = new ArrayList<>();
        for (Row r : rows) {
            if (forMap.size() >= MAX_MAP)
                break;
            forMap.add(r.site);
        }
        layer.show(forMap);

        final List<Object> listRows = new ArrayList<>(rows.size() + 4);
        int seen = 0;
        if (meReachOn && !reachPending) {
            // Four sections: likely, marginal, unlikely, and what was out of range.
            // Each keeps the nearest-first order it arrived in.
            final List<Row> likely = new ArrayList<>(), marginal = new ArrayList<>(),
                    unlikely = new ArrayList<>(), far = new ArrayList<>();
            for (Row r : rows) {
                final Reach.State v = reach.get(r.site.id);
                if (v == Reach.State.LIKELY)
                    likely.add(r);
                else if (v == Reach.State.MARGINAL)
                    marginal.add(r);
                else if (v == Reach.State.UNLIKELY)
                    unlikely.add(r);
                else
                    far.add(r);
            }
            seen = likely.size();
            listRows.add(String.format(Locale.US, "Likely from %s (%,d)",
                    fromMapCenter ? "the map center" : "here", likely.size()));
            listRows.addAll(likely);
            if (!marginal.isEmpty()) {
                listRows.add(String.format(Locale.US, "Marginal (%,d)", marginal.size()));
                listRows.addAll(marginal);
            }
            if (!unlikely.isEmpty()) {
                listRows.add(String.format(Locale.US, "Unlikely (%,d)", unlikely.size()));
                listRows.addAll(unlikely);
            }
            if (!far.isEmpty()) {
                listRows.add(String.format(Locale.US, "Beyond %s, not checked (%,d)",
                        Units.formatBig(layer.getCheckRangeMeters()), far.size()));
                listRows.addAll(far);
            }
        } else {
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
        // An incident portable is carried to the fire, so there is no mountain to
        // list. Saying nothing reads as the plugin being broken; say what it is.
        if (named != null && named.portable && rows.isEmpty())
            b.append(" · ").append(netLabel(named.id))
                    .append(" is an incident portable, set up at the incident, so it has no site");
        if (hiddenByOnlyLikely > 0)
            b.append(String.format(Locale.US, " · %,d more hidden by Only likely", hiddenByOnlyLikely));
        if (meReachOn) {
            if (reachPending)
                b.append(" · ").append(reachProgress());
            else {
                b.append(String.format(Locale.US, " · %d of %d within %s look likely from %s",
                        seen, reachChecked, Units.formatBig(layer.getCheckRangeMeters()),
                        fromMapCenter ? "the map center" : "here"));
                if (reachUnknown > 0)
                    b.append(String.format(Locale.US, ", %d unknown (no elevation data)", reachUnknown));
            }
        }
        // When nothing at all is on the map, say so first and in the warning colour.
        // Trailing it in grey after the counts is how the operator spent a while
        // looking at an empty map on 2026-09-11: "i have use this zoom and they arent
        // rendering" -- the pane had been saying "· map OFF" at the end of the line.
        // Running out of room to draw is different from being turned off, so each
        // says which it is and where the control for it lives.
        String blocking = null;
        if (!mapOn)
            blocking = "Map is OFF, nothing is drawn. The toggle is at the top of this pane";
        else if (!layer.isWithinZoom())
            blocking = "Zoomed out too far to draw them. Use this zoom sets the limit";
        else if (rows.size() > MAX_MAP)
            b.append(String.format(Locale.US, " · map shows nearest %,d, zoom in or narrow the search", MAX_MAP));
        if (noFix && from != null)
            b.append(" · no GPS fix, measured from the map center");
        else if (from == null)
            b.append(" · nowhere to measure from, sorted by name");
        status.setTextColor(blocking != null || noFix ? STATUS_WARN : statusColor);
        status.setText(blocking == null ? b.toString() : blocking + " · " + b);
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

    // ---- formatting ----------------------------------------------------------------

    private static String distanceLine(Row r) {
        if (Double.isNaN(r.meters))
            return "";
        final StringBuilder b = new StringBuilder(Units.format(r.meters));
        if (!Double.isNaN(r.bearing))
            b.append(String.format(Locale.US, " · %03.0f°", r.bearing));
        return b.toString();
    }

    /**
     * A channel as it should read: the designator, with the abbreviations a call plan
     * uses for a net spelled out.
     *
     * <p>"OES V4" is what that channel is called and stays as it is. "ANF FN" is not
     * a name, it is "ANF Forest Net" with the words taken out, so they go back in.
     * Only these tokens are touched; nothing else about a designator is guessed at.
     *
     * <p>A trailing "R" for repeater is dropped rather than spelled out. Every site
     * in this plugin is a repeater, so the letter says nothing the pane is not
     * already saying, and "SHU R" reads better as "SHU". Only the whole word: the R
     * in a channel name like "XSD C11R" is part of the channel, not a suffix.
     */
    static String netLabel(String designator) {
        final String[] parts = Nets.noRepeaterSuffix(designator).split(" ");
        final StringBuilder b = new StringBuilder();
        for (String p : parts) {
            if ("R".equals(p))
                continue;
            if (b.length() > 0)
                b.append(' ');
            if ("FN".equals(p))
                b.append("Forest Net");
            else if ("AN".equals(p))
                b.append("Admin Net");
            else if ("SVC".equals(p))
                b.append("Service Net");
            else if ("FCSN".equals(p))
                b.append("Fire Camp Service Net");
            else if ("OPS".equals(p))
                b.append("Operations Net");
            else if ("EN".equals(p))
                b.append("Emergency Net");
            else if ("L".equals(p))
                b.append("Local");
            else
                b.append(p);
        }
        // A designator that was nothing but the suffix keeps its own name.
        return b.length() > 0 ? b.toString() : designator;
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
        b.append(netLabel(c.net.id));
        // Designator and the tone that opens it. The FCC callsign is the licence
        // identifier for the station, not anything an operator sets on a radio, so it
        // stays in the catalog and off the screen.
        b.append(" · ").append(toneLine(c.effectiveRxTone(), c.net.rxToneText,
                c.effectiveTxTone(), c.net.txToneText));
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
            b.append(Nets.noRepeaterSuffix(s.channels.get(i).netId));
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

        final StringBuilder b = new StringBuilder();
        b.append(s.label()).append('\n');
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
        // Who runs equipment on the mountain is not why an operator opened this.
        final String word = reachWord(s);
        if (word != null)
            b.append("\nHandheld from ").append(fromMapCenter ? "the map center: " : "here: ").append(word);
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
                    netLabel(c.net.id));
            ((TextView) row.findViewById(R.id.line)).setText(
                    toneLine(c.effectiveRxTone(), c.net.rxToneText,
                            c.effectiveTxTone(), c.net.txToneText));
            // Designator, tone, callsign, and nothing else. The plans' prose about
            // what a site covers is not something this plugin can stand behind, and
            // where a row came from is not ours to put on screen. Both are kept in
            // the catalog and neither is shown.
            row.findViewById(R.id.note).setVisibility(View.GONE);
            nets.addView(row);
        }

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

    // ---- list ---------------------------------------------------------------------

    private final class RowAdapter extends BaseAdapter {

        private static final int TYPE_HEADING = 0;
        private static final int TYPE_SITE = 1;

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
            return TYPE_SITE;
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
            View v = convertView;
            if (v == null)
                v = PluginLayoutInflater.inflate(pluginContext, R.layout.site_row, null);
            final Site s = r.site;
            ((TextView) v.findViewById(R.id.name)).setText(s.label());
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

package com.atakmap.android.evacmap.plugin;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.atak.plugins.impl.PluginContextProvider;
import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.evacmap.Catalog;
import com.atakmap.android.evacmap.ZoneLayer;
import com.atakmap.android.evacmap.ZoneManager;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import gov.tak.api.plugin.IPlugin;
import gov.tak.api.plugin.IServiceController;
import gov.tak.api.ui.IHostUIService;
import gov.tak.api.ui.Pane;
import gov.tak.api.ui.PaneBuilder;
import gov.tak.api.ui.ToolbarItem;
import gov.tak.api.ui.ToolbarItemAdapter;
import gov.tak.platform.marshal.MarshalManager;

/**
 * Evac Map: evacuation zones from state and county services, browsed the way Cam Depot
 * browses cameras. Pick a state; its statewide sources are always listed; pick one or
 * more counties and their sources stay listed underneath. The manager lives for the
 * plugin's life; the pane is only its controls.
 */
public class EvacMap implements IPlugin {

    private static final String TAG = "EvacMap";

    IServiceController serviceController;
    Context pluginContext;
    IHostUIService uiService;
    ToolbarItem toolbarItem;
    Pane pane;
    View paneView;
    ZoneManager manager;
    MapView mapView;
    /** The state the pane is showing, a two-letter code, or null before one is picked. */
    private String state;
    private android.widget.SeekBar radius;
    private TextView radiusLabel, zoomLabel;
    private Button radiusFromButton, radiusPresetButton;
    private android.widget.EditText search;
    private Button searchClear, statusFilterButton;
    /** The status the list is narrowed to, a legend key, or null for all. */
    private String statusFilter;
    /** Rows the list draws before it says "narrow the search". */
    private static final int LIST_CAP = 100;

    public EvacMap(IServiceController serviceController) {
        this.serviceController = serviceController;
        final PluginContextProvider ctxProvider = serviceController
                .getService(PluginContextProvider.class);
        if (ctxProvider != null) {
            pluginContext = ctxProvider.getPluginContext();
            pluginContext.setTheme(R.style.ATAKPluginTheme);
        }
        uiService = serviceController.getService(IHostUIService.class);
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
        if (uiService != null)
            uiService.addToolbarItem(toolbarItem);
        mapView = MapView.getMapView();
        if (mapView != null && manager == null) {
            manager = new ZoneManager(mapView, pluginContext);
            manager.start();
        }
    }

    @Override
    public void onStop() {
        // Close our pane: ATAK keeps a plugin's pane on screen across a reload, and a
        // pane whose buttons point at a stopped instance does nothing when tapped.
        if (pane != null && uiService != null) {
            try {
                if (uiService.isPaneVisible(pane))
                    uiService.closePane(pane);
            } catch (Exception ignored) {
            }
            pane = null;
            paneView = null;
        }
        if (manager != null) {
            manager.stop();
            manager = null;
        }
        if (uiService != null)
            uiService.removeToolbarItem(toolbarItem);
    }

    private android.content.SharedPreferences uiPrefs() {
        return mapView.getContext().getSharedPreferences("evacmap.ui", Context.MODE_PRIVATE);
    }

    /** MapView context, never plugin context: a toast on the plugin context kills ATAK. */
    private void toast(String s) {
        Toast.makeText(mapView.getContext(), s, Toast.LENGTH_SHORT).show();
    }

    // ---- pane ---------------------------------------------------------------------

    private void showPane() {
        if (pane == null) {
            paneView = PluginLayoutInflater.inflate(pluginContext, R.layout.main_layout, null);
            paneView.findViewById(R.id.btn_state).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    pickState();
                }
            });
            paneView.findViewById(R.id.btn_counties).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    pickCounties();
                }
            });
            paneView.findViewById(R.id.btn_refresh_all).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (manager != null)
                        manager.refreshAll();
                }
            });
            wireVisibility();
            wireZoneList();
            if (manager != null)
                manager.setOnDetailsBack(new Runnable() {
                    @Override
                    public void run() {
                        showPane();
                    }
                });
            if (manager != null)
                manager.setListener(new ZoneManager.Listener() {
                    @Override
                    public void onChanged() {
                        render();
                    }

                    @Override
                    public void onCatalog() {
                        render();
                    }

                    @Override
                    public void onMapMoved() {
                        updateZoomLabel();
                    }
                });
            pane = new PaneBuilder(paneView)
                    .setMetaValue(Pane.RELATIVE_LOCATION, Pane.Location.Default)
                    .setMetaValue(Pane.PREFERRED_WIDTH_RATIO, 0.5D)
                    .setMetaValue(Pane.PREFERRED_HEIGHT_RATIO, 0.5D)
                    .build();
        }
        if (state == null)
            state = defaultState();
        render();
        if (!uiService.isPaneVisible(pane))
            uiService.showPane(pane, null);
    }

    /** The state picked last time; else the one under the map center; else the first listed. */
    private String defaultState() {
        final Catalog c = manager == null ? null : manager.catalog();
        if (c == null)
            return null;
        final String saved = uiPrefs().getString("state", null);
        if (saved != null && c.states().contains(saved))
            return saved;
        try {
            final GeoPoint center = mapView.getCenterPoint().get();
            final String at = c.stateAt(center.getLatitude(), center.getLongitude());
            if (at != null)
                return at;
        } catch (Exception e) {
            Log.w(TAG, "map center unavailable", e);
        }
        final List<String> states = c.states();
        return states.isEmpty() ? null : states.get(0);
    }

    /** MapView context, never plugin context: a dialog on the plugin context kills ATAK. */
    private void pickState() {
        final Catalog c = manager == null ? null : manager.catalog();
        if (c == null)
            return;
        final List<String> states = c.states();
        final String[] labels = new String[states.size()];
        int checked = -1;
        for (int i = 0; i < states.size(); i++) {
            final String st = states.get(i);
            final List<Catalog.Source> srcs = c.forState(st);
            int on = 0;
            for (Catalog.Source s : srcs)
                if (manager.isOn(s))
                    on++;
            labels[i] = st + "  (" + srcs.size() + (srcs.size() == 1 ? " source" : " sources")
                    + (on > 0 ? ", " + on + " on" : "") + ")";
            if (st.equals(state))
                checked = i;
        }
        new AlertDialog.Builder(mapView.getContext())
                .setTitle("State or province")
                .setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        d.dismiss();
                        state = states.get(which);
                        uiPrefs().edit().putString("state", state).apply();
                        render();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---- visibility: Cam Depot's radius and zoom controls ---------------------------

    /**
     * Quote the scale bar, because it is already on screen. Presets are what the bar
     * would read, in the operator's own big unit, so every entry is a clean number in
     * whatever system ATAK is set to.
     */
    static final String ALWAYS = "Always draw them";
    private static final double[] PRESET_BAR_BIG = { 0.25, 1, 5, 15, 50 };
    private static final String[] PRESET_NAMES = { "city block", "neighborhood", "town", "county", "region" };
    /** Radius choices in the big unit; 0 is off. Stops where the slider stops. */
    private static final int[] RADIUS_PRESETS = { 0, 2, 5, 10, 25, 50 };

    private static String presetLabel(int i) {
        final double n = PRESET_BAR_BIG[i];
        final String num = n == Math.floor(n) ? String.format(java.util.Locale.US, "%.0f", n)
                : String.format(java.util.Locale.US, "%.2f", n);
        return num + " " + com.atakmap.android.evacmap.Units.bigLabel() + "  —  " + PRESET_NAMES[i];
    }

    private static String radiusPresetLabel(int r) {
        return r == 0 ? "Off — the whole state"
                : String.format(java.util.Locale.US, "%d %s", r, com.atakmap.android.evacmap.Units.bigLabel());
    }

    private void wireVisibility() {
        final com.atakmap.android.evacmap.Visibility v = manager.visibility;
        radius = paneView.findViewById(R.id.radius);
        radiusLabel = paneView.findViewById(R.id.radius_label);
        zoomLabel = paneView.findViewById(R.id.zoom_label);
        radiusFromButton = paneView.findViewById(R.id.pick_point);
        radiusPresetButton = paneView.findViewById(R.id.radius_preset);
        radius.setMax(com.atakmap.android.evacmap.Visibility.RADIUS_MAX);
        radius.setProgress(v.radiusBig);
        radius.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar bar, int progress, boolean user) {
                if (!user)
                    return;
                v.radiusBig = progress;
                updateRadiusLabels();
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar bar) {
                // The filter runs when the thumb is let go, not on every step of the drag.
                manager.setRadiusBig(bar.getProgress());
            }
        });
        radiusFromButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                // Rotates between the operator and the map.
                manager.setFromMap(!v.fromMap);
                updateRadiusLabels();
            }
        });
        radiusPresetButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                final String[] names = new String[RADIUS_PRESETS.length];
                int current = -1;
                for (int i = 0; i < RADIUS_PRESETS.length; i++) {
                    names[i] = radiusPresetLabel(RADIUS_PRESETS[i]);
                    if (v.radiusBig == RADIUS_PRESETS[i])
                        current = i;
                }
                choose("Show zones within", names, current, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        radius.setProgress(RADIUS_PRESETS[which]);
                        manager.setRadiusBig(RADIUS_PRESETS[which]);
                        updateRadiusLabels();
                    }
                });
            }
        });
        paneView.findViewById(R.id.radius_extent).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                // "What I am looking at", as a radius: center to corner, so the whole
                // visible rectangle is inside the circle.
                final com.atakmap.coremap.maps.coords.GeoBounds b = mapView.getBounds();
                final GeoPoint c = mapView.getPoint().get();
                if (b == null || c == null) {
                    toast("The map has no extent yet");
                    return;
                }
                final double meters = com.atakmap.coremap.maps.coords.GeoCalculations.distanceTo(c,
                        new GeoPoint(b.getNorth(), b.getEast()));
                final double big = meters / com.atakmap.android.evacmap.Units.bigToMeters(1);
                final int max = com.atakmap.android.evacmap.Visibility.RADIUS_MAX;
                if (big > max)
                    toast(String.format(java.util.Locale.US, "That view is wider than %d %s — radius set to the maximum",
                            max, com.atakmap.android.evacmap.Units.bigLabel()));
                final int r = (int) Math.max(1, Math.min(max, Math.round(big)));
                v.fromMap = true;
                radius.setProgress(r);
                manager.setRadiusBig(r);
                manager.setFromMap(true);
                updateRadiusLabels();
            }
        });
        paneView.findViewById(R.id.zoom_set).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                // Whatever the operator is looking at right now becomes the threshold.
                manager.setZoomThreshold(mapView.getMapResolution());
                updateZoomLabel();
            }
        });
        paneView.findViewById(R.id.zoom_preset).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                final String[] names = new String[PRESET_BAR_BIG.length + 1];
                for (int i = 0; i < PRESET_BAR_BIG.length; i++)
                    names[i] = presetLabel(i);
                names[PRESET_BAR_BIG.length] = ALWAYS;
                choose("Draw zones when the scale bar reads", names, -1, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (which >= PRESET_BAR_BIG.length)
                            manager.setZoomThreshold(Double.MAX_VALUE);
                        else
                            manager.setZoomThreshold(com.atakmap.android.evacmap.Units.bigToMeters(PRESET_BAR_BIG[which])
                                    / scaleBarPixels());
                        updateZoomLabel();
                    }
                });
            }
        });
        updateRadiusLabels();
        updateZoomLabel();
    }

    /** A single-choice list on the MapView context, the way Cam Depot's pickers open. */
    private void choose(String title, String[] names, int checked, final DialogInterface.OnClickListener onPick) {
        new AlertDialog.Builder(mapView.getContext())
                .setTitle(title)
                .setSingleChoiceItems(names, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        d.dismiss();
                        onPick.onClick(d, which);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void updateRadiusLabels() {
        if (radiusLabel == null || manager == null)
            return;
        final com.atakmap.android.evacmap.Visibility v = manager.visibility;
        radiusLabel.setText(v.radiusBig <= 0 ? "Radius: off — the whole state"
                : String.format(java.util.Locale.US, "Within %d %s of %s", v.radiusBig,
                        com.atakmap.android.evacmap.Units.bigLabel(), v.fromLabel()));
        radiusFromButton.setText("Measuring from: " + v.fromLabel());
        String preset = "Presets";
        for (int r : RADIUS_PRESETS)
            if (v.radiusBig == r)
                preset = r == 0 ? "Preset: Off" : String.format(java.util.Locale.US, "Preset: %d %s", r,
                        com.atakmap.android.evacmap.Units.bigLabel());
        radiusPresetButton.setText(preset);
    }

    private void updateZoomLabel() {
        if (zoomLabel == null || manager == null)
            return;
        final String bar = com.atakmap.android.evacmap.ScaleBar.text(mapView);
        final double limit = manager.visibility.maxResolution;
        if (limit == Double.MAX_VALUE) {
            zoomLabel.setText("Always drawn  ·  scale bar: " + bar);
            return;
        }
        final String at = com.atakmap.android.evacmap.ScaleBar.describe(limit * scaleBarPixels());
        zoomLabel.setText(String.format(java.util.Locale.US, "Drawn at %s or closer  ·  scale bar now: %s%s",
                at, bar, manager.visibility.withinZoom(mapView) ? "" : "  — hidden"));
    }

    /** Pixels the scale bar spans, derived so the quoted threshold matches its text. */
    private double scaleBarPixels() {
        final double res = mapView.getMapResolution();
        if (res <= 0)
            return 200;
        final double m = com.atakmap.android.evacmap.ScaleBar.meters(mapView);
        return m > 0 ? m / res : 200;
    }

    // ---- the zone list: search, a status filter, Go to ------------------------------

    private void wireZoneList() {
        search = paneView.findViewById(R.id.search);
        searchClear = paneView.findViewById(R.id.search_clear);
        statusFilterButton = paneView.findViewById(R.id.status_filter);
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence c, int a, int b, int d) {
            }

            @Override
            public void onTextChanged(CharSequence c, int a, int b, int d) {
            }

            @Override
            public void afterTextChanged(android.text.Editable e) {
                renderZones();
            }
        });
        // Clear sits beside the box and is disabled while it is empty: a live Clear
        // button is the panel saying a search is narrowing the list.
        searchClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                search.setText("");
            }
        });
        statusFilterButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickStatus();
            }
        });
    }

    /** Every zone on the map in this state: the ON layers' lists, joined. */
    private List<ZoneLayer.ZoneInfo> zonesInState() {
        final List<ZoneLayer.ZoneInfo> out = new ArrayList<>();
        for (ZoneLayer l : manager.snapshot())
            if (l.isVisible() && (state == null || l.source.st.equalsIgnoreCase(state)))
                out.addAll(l.zones);
        return out;
    }

    /** The filter states what it will cost before it is used: each status with its count. */
    private void pickStatus() {
        final Map<String, Integer> counts = new LinkedHashMap<>();
        final Map<String, Integer> colors = new LinkedHashMap<>();
        for (ZoneLayer.ZoneInfo z : zonesInState()) {
            final Integer n = counts.get(z.status);
            counts.put(z.status, n == null ? 1 : n + 1);
            if (z.color != 0)
                colors.put(z.status, z.color);
        }
        final List<String> keys = new ArrayList<>(counts.keySet());
        final String[] names = new String[keys.size() + 1];
        names[0] = "All  (" + zonesInState().size() + ")";
        int checked = statusFilter == null ? 0 : -1;
        for (int i = 0; i < keys.size(); i++) {
            names[i + 1] = keys.get(i) + "  (" + counts.get(keys.get(i)) + ")";
            if (keys.get(i).equals(statusFilter))
                checked = i + 1;
        }
        choose("Zones with status", names, checked, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                statusFilter = which == 0 ? null : keys.get(which - 1);
                renderZones();
            }
        });
    }

    /**
     * The list: what is on the map in this state, narrowed by the search box and the
     * status filter, nearest first from the point the radius measures from, with the
     * distance on each row and Go to. Capped, and it says so.
     */
    private void renderZones() {
        if (paneView == null || manager == null)
            return;
        final LinearLayout container = paneView.findViewById(R.id.zones_container);
        final TextView summary = paneView.findViewById(R.id.zones_summary);
        container.removeAllViews();
        final String q = search.getText().toString().trim().toLowerCase(java.util.Locale.US);
        searchClear.setEnabled(!q.isEmpty());
        final List<ZoneLayer.ZoneInfo> all = zonesInState();
        statusFilterButton.setText(statusFilter == null ? "Status: all" : "Status: " + statusFilter);
        if (statusFilter != null) {
            boolean seen = false;
            for (ZoneLayer.ZoneInfo z : all)
                if (statusFilter.equals(z.status)) {
                    seen = true;
                    break;
                }
            if (!seen)
                statusFilterButton.setText("Status: " + statusFilter + " (none now)");
        }
        final List<ZoneLayer.ZoneInfo> hits = new ArrayList<>();
        for (ZoneLayer.ZoneInfo z : all) {
            if (statusFilter != null && !statusFilter.equals(z.status))
                continue;
            if (!z.matches(q))
                continue;
            hits.add(z);
        }
        final GeoPoint from = manager.visibility.from(mapView);
        final Map<ZoneLayer.ZoneInfo, Double> dist = new java.util.HashMap<>();
        for (ZoneLayer.ZoneInfo z : hits)
            dist.put(z, from == null || Double.isNaN(z.lat) ? Double.MAX_VALUE
                    : com.atakmap.coremap.maps.coords.GeoCalculations.distanceTo(from, new GeoPoint(z.lat, z.lon)));
        Collections.sort(hits, new java.util.Comparator<ZoneLayer.ZoneInfo>() {
            @Override
            public int compare(ZoneLayer.ZoneInfo a, ZoneLayer.ZoneInfo b) {
                return Double.compare(dist.get(a), dist.get(b));
            }
        });
        final StringBuilder sb = new StringBuilder();
        if (all.isEmpty())
            sb.append("No zones on the map for ").append(state == null ? "this state" : state)
                    .append(". Turn a source on.");
        else if (hits.isEmpty())
            sb.append("No zone matches").append(q.isEmpty() ? "" : " \u201c" + q + "\u201d")
                    .append(statusFilter == null ? "" : " with status " + statusFilter).append('.');
        else if (hits.size() > LIST_CAP)
            sb.append("Nearest ").append(LIST_CAP).append(" of ").append(hits.size())
                    .append(" zones, from ").append(manager.visibility.fromLabel()).append(" \u00b7 narrow the search");
        else
            sb.append(hits.size()).append(hits.size() == 1 ? " zone" : " zones").append(", nearest first from ")
                    .append(manager.visibility.fromLabel());
        summary.setText(sb.toString());
        int n = 0;
        for (final ZoneLayer.ZoneInfo z : hits) {
            if (n++ >= LIST_CAP)
                break;
            final View row = PluginLayoutInflater.inflate(pluginContext, R.layout.zone_row, null);
            final double d = dist.get(z);
            ((TextView) row.findViewById(R.id.row_title)).setText(z.name
                    + (d == Double.MAX_VALUE ? "" : "  \u00b7  " + com.atakmap.android.evacmap.Units.format(d)));
            final TextView sub = row.findViewById(R.id.row_status);
            final SpannableStringBuilder line = new SpannableStringBuilder();
            if (!z.status.isEmpty()) {
                line.append(z.status);
                if (z.color != 0)
                    line.setSpan(new ForegroundColorSpan(z.color), 0, line.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (!z.county.isEmpty())
                line.append(line.length() > 0 ? " \u00b7 " : "").append(z.county);
            line.append(line.length() > 0 ? " \u00b7 " : "").append(z.sourceTitle);
            sub.setText(line);
            row.findViewById(R.id.row_goto).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    manager.frame(z.bounds);
                }
            });
            // The row itself opens the details; the button beside it goes there.
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    manager.showDetails(z.sourceId, z.featureId);
                }
            });
            container.addView(row);
        }
    }

    // ---- counties -----------------------------------------------------------------

    /**
     * Counties picked for a state, as {@link Catalog#countyKey} keys, remembered per state
     * so a picked list stays up. The first time a state is opened, the county under the
     * map center is picked for it.
     */
    private Set<String> selectedCounties(String st) {
        final Set<String> out = new LinkedHashSet<>();
        if (st == null)
            return out;
        final String saved = uiPrefs().getString("counties." + st, null);
        if (saved == null) {
            try {
                final Catalog c = manager.catalog();
                final GeoPoint center = mapView.getCenterPoint().get();
                final Catalog.County at = c == null ? null
                        : c.countyAt(st, center.getLatitude(), center.getLongitude());
                if (at != null)
                    out.add(Catalog.countyKey(at.name));
            } catch (Exception e) {
                Log.w(TAG, "map center unavailable", e);
            }
            saveCounties(st, out);
            return out;
        }
        try {
            final JSONArray arr = new JSONArray(saved);
            for (int i = 0; i < arr.length(); i++)
                out.add(Catalog.countyKey(arr.getString(i)));
        } catch (Exception e) {
            Log.w(TAG, "county selection unreadable", e);
        }
        return out;
    }

    private void saveCounties(String st, Collection<String> keys) {
        uiPrefs().edit().putString("counties." + st, new JSONArray(keys).toString()).apply();
    }

    /** What the picker knows about one county: its name, its own sources, its zones in the statewide feeds. */
    private static final class CountyEntry {
        final String key;
        String name;
        int sources, zones;
        double[] bounds;

        CountyEntry(String key, String name) {
            this.key = key;
            this.name = name;
        }
    }

    /**
     * Every county of the state, by name: the Census list, plus any county a source
     * names, plus any county a statewide feed that is on has zones in.
     */
    private Map<String, CountyEntry> countyEntries(Catalog c, List<Catalog.Source> srcs) {
        final Map<String, CountyEntry> out = new java.util.TreeMap<>();
        for (Catalog.County county : c.countiesOf(state)) {
            final CountyEntry e = new CountyEntry(Catalog.countyKey(county.name), county.name);
            e.bounds = county.bounds;
            out.put(e.key, e);
        }
        for (Catalog.Source s : srcs) {
            if (s.statewide())
                continue;
            final String k = Catalog.countyKey(s.county);
            CountyEntry e = out.get(k);
            if (e == null) {
                e = new CountyEntry(k, s.county);
                out.put(k, e);
            }
            e.sources++;
        }
        for (ZoneLayer l : manager.snapshot()) {
            if (!l.isVisible() || !l.source.statewide() || !l.source.st.equalsIgnoreCase(state))
                continue;
            for (Map.Entry<String, ZoneLayer.CountyTally> t : l.countyTallies.entrySet()) {
                CountyEntry e = out.get(t.getKey());
                if (e == null) {
                    e = new CountyEntry(t.getKey(), t.getValue().name);
                    out.put(t.getKey(), e);
                }
                e.zones += t.getValue().total();
            }
        }
        return out;
    }

    /** One or many, ticked in a multi-choice dialog on the MapView context, the way Cam Depot picks counties. */
    private void pickCounties() {
        final Catalog c = manager == null ? null : manager.catalog();
        if (c == null || state == null)
            return;
        final Map<String, CountyEntry> entries = countyEntries(c, c.forState(state));
        if (entries.isEmpty()) {
            toast("No county list for " + state + " yet");
            return;
        }
        final List<CountyEntry> list = new ArrayList<>(entries.values());
        Collections.sort(list, new java.util.Comparator<CountyEntry>() {
            @Override
            public int compare(CountyEntry a, CountyEntry b) {
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        final String[] labels = new String[list.size()];
        final boolean[] ticked = new boolean[list.size()];
        final Set<String> selected = selectedCounties(state);
        for (int i = 0; i < list.size(); i++) {
            final CountyEntry e = list.get(i);
            final StringBuilder sb = new StringBuilder(e.name);
            final List<String> parts = new ArrayList<>();
            if (e.zones > 0)
                parts.add(e.zones + (e.zones == 1 ? " zone" : " zones"));
            if (e.sources > 0)
                parts.add(e.sources + (e.sources == 1 ? " source" : " sources"));
            if (!parts.isEmpty())
                sb.append("  (").append(TextUtils.join(", ", parts)).append(')');
            labels[i] = sb.toString();
            ticked[i] = selected.contains(e.key);
        }
        new AlertDialog.Builder(mapView.getContext())
                .setTitle("Counties in " + state)
                .setMultiChoiceItems(labels, ticked, new DialogInterface.OnMultiChoiceClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which, boolean isChecked) {
                        ticked[which] = isChecked;
                    }
                })
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        final List<String> chosen = new ArrayList<>();
                        for (int i = 0; i < list.size(); i++)
                            if (ticked[i])
                                chosen.add(list.get(i).key);
                        saveCounties(state, chosen);
                        render();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---- rendering ----------------------------------------------------------------

    private void render() {
        if (paneView == null || manager == null)
            return;
        final Catalog c = manager.catalog();
        final Button stateButton = paneView.findViewById(R.id.btn_state);
        final TextView status = paneView.findViewById(R.id.status);
        final TextView legend = paneView.findViewById(R.id.legend);
        final LinearLayout statewide = paneView.findViewById(R.id.statewide_container);
        final TextView statewideEmpty = paneView.findViewById(R.id.statewide_empty);
        final Button countiesButton = paneView.findViewById(R.id.btn_counties);
        final LinearLayout county = paneView.findViewById(R.id.county_container);
        final TextView countyHint = paneView.findViewById(R.id.county_hint);
        statewide.removeAllViews();
        county.removeAllViews();

        if (c == null) {
            stateButton.setText("No catalog");
            status.setText(manager.catalogStatus);
            legend.setText("");
            statewideEmpty.setVisibility(View.VISIBLE);
            statewideEmpty.setText("The catalog could not be read.");
            countiesButton.setEnabled(false);
            countyHint.setVisibility(View.GONE);
            return;
        }
        if (state == null || !c.states().contains(state))
            state = defaultState();
        stateButton.setText(state == null ? "Pick a state" : "State: " + state);

        // Totals across everything that is on, in the plugin's status vocabulary.
        final Map<String, Integer> totals = new LinkedHashMap<>();
        final Map<String, Integer> colors = new LinkedHashMap<>();
        int on = 0, drawn = 0, loading = 0;
        for (ZoneLayer l : manager.snapshot()) {
            if (!l.isVisible())
                continue;
            on++;
            drawn += l.count;
            if (l.refreshing || l.busy)
                loading++;
            for (Map.Entry<String, Integer> e : l.statusCounts.entrySet()) {
                final Integer prev = totals.get(e.getKey());
                totals.put(e.getKey(), prev == null ? e.getValue() : prev + e.getValue());
                final Integer col = l.statusColors.get(e.getKey());
                if (col != null)
                    colors.put(e.getKey(), col);
            }
        }
        final StringBuilder sb = new StringBuilder();
        sb.append(on == 0 ? "Nothing on" : on + " on, " + drawn + " zones drawn");
        if (loading > 0)
            sb.append(", ").append(loading).append(" loading");
        sb.append(" · ").append(manager.catalogStatus);
        if (on > 0 && !manager.visibility.withinZoom(mapView))
            sb.append("\nMap: none drawn — zoom in past your threshold");
        status.setText(sb.toString());
        legend.setText(legendText(totals, colors));

        final List<Catalog.Source> srcs = state == null ? new ArrayList<Catalog.Source>() : c.forState(state);

        // Statewide: always listed.
        int nStatewide = 0;
        for (Catalog.Source s : srcs) {
            if (!s.statewide())
                continue;
            statewide.addView(sourceRow(s));
            nStatewide++;
        }
        statewideEmpty.setVisibility(nStatewide == 0 ? View.VISIBLE : View.GONE);
        statewideEmpty.setText("No statewide source for " + state + " yet.");

        // Counties: the picked ones stay listed, plus any county with a source that is
        // on, so nothing drawn on the map can be missing from the pane. Under each, the
        // county's share of every statewide feed, then the county's own sources.
        final Map<String, CountyEntry> entries = countyEntries(c, srcs);
        final Set<String> selected = selectedCounties(state);
        for (Catalog.Source s : srcs)
            if (!s.statewide() && manager.isOn(s))
                selected.add(Catalog.countyKey(s.county));
        if (entries.isEmpty()) {
            countiesButton.setText("No county list for " + state + " yet");
            countiesButton.setEnabled(false);
            countyHint.setVisibility(View.GONE);
            renderZones();
            return;
        }
        countiesButton.setEnabled(true);
        final List<CountyEntry> shown = new ArrayList<>();
        for (CountyEntry e : entries.values())
            if (selected.contains(e.key))
                shown.add(e);
        Collections.sort(shown, new java.util.Comparator<CountyEntry>() {
            @Override
            public int compare(CountyEntry a, CountyEntry b) {
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        if (shown.isEmpty()) {
            countiesButton.setText("Pick counties  (" + entries.size() + " in " + state + ")");
            countyHint.setVisibility(View.VISIBLE);
            renderZones();
            return;
        }
        final List<String> names = new ArrayList<>();
        for (CountyEntry e : shown)
            names.add(e.name);
        countiesButton.setText((shown.size() == 1 ? "County: " : shown.size() + " counties: ")
                + TextUtils.join(", ", names));
        countyHint.setVisibility(View.GONE);
        for (CountyEntry e : shown) {
            for (Catalog.Source s : srcs)
                if (s.statewide() && !s.countyField.isEmpty())
                    county.addView(countyShareRow(e, s));
            for (Catalog.Source s : srcs)
                if (!s.statewide() && Catalog.countyKey(s.county).equals(e.key))
                    county.addView(sourceRow(s));
        }
        renderZones();
    }

    /**
     * One county's share of one statewide feed: "Monterey in Active Evacuation Zones",
     * the status counts there, and Go to. The feed is turned on and off in its own row
     * above; this row only says what it holds for the county.
     */
    private View countyShareRow(final CountyEntry e, final Catalog.Source s) {
        final View row = PluginLayoutInflater.inflate(pluginContext, R.layout.county_row, null);
        final ZoneLayer l = manager.find(s.id);
        ((TextView) row.findViewById(R.id.row_title)).setText(e.name + " in " + s.title);
        final TextView status = row.findViewById(R.id.row_status);
        final ZoneLayer.CountyTally t = l == null ? null : l.countyTallies.get(e.key);
        final double[] target;
        if (l == null || !l.isVisible()) {
            status.setText(s.publisher + " · statewide source is off");
            target = e.bounds;
        } else if (l.refreshing || l.busy) {
            status.setText(s.publisher + " · loading");
            target = e.bounds;
        } else if (t == null || t.total() == 0) {
            status.setText(s.publisher + " · no zones in " + e.name + " now");
            target = e.bounds;
        } else {
            final SpannableStringBuilder sb = new SpannableStringBuilder(s.publisher + " · ");
            sb.append(legendText(t.counts, l.statusColors));
            status.setText(sb);
            target = t.bounds != null ? t.bounds : e.bounds;
        }
        final Button go = row.findViewById(R.id.row_goto);
        go.setEnabled(target != null);
        go.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                frame(target);
            }
        });
        return row;
    }

    private void frame(double[] b) {
        manager.frame(b);
    }

    /** "Order 37 · Warning 48 · Advisory 16", each label in its own color. */
    private static CharSequence legendText(Map<String, Integer> totals, Map<String, Integer> colors) {
        final SpannableStringBuilder out = new SpannableStringBuilder();
        for (Map.Entry<String, Integer> e : totals.entrySet()) {
            if (out.length() > 0)
                out.append("  ·  ");
            final int start = out.length();
            out.append(e.getKey()).append(' ').append(String.valueOf(e.getValue()));
            final Integer col = colors.get(e.getKey());
            if (col != null)
                out.setSpan(new ForegroundColorSpan(col), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return out;
    }

    private View sourceRow(final Catalog.Source s) {
        final View row = PluginLayoutInflater.inflate(pluginContext, R.layout.source_row, null);
        final ZoneLayer l = manager.find(s.id);
        ((TextView) row.findViewById(R.id.row_title)).setText(s.displayTitle());
        ((TextView) row.findViewById(R.id.row_status)).setText(statusLine(s, l));
        final Button toggle = row.findViewById(R.id.row_toggle);
        final boolean isOn = l != null && l.isVisible();
        if (l != null && (l.refreshing || l.busy)) {
            toggle.setText("Loading…");
            toggle.setTextColor(Color.parseColor("#FFC107"));
            toggle.setEnabled(false);
        } else {
            toggle.setText(isOn ? "ON" : "OFF");
            toggle.setTextColor(isOn ? Color.parseColor("#3ddc61") : Color.parseColor("#ff5b52"));
            toggle.setEnabled(true);
        }
        toggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                manager.setOn(s, !manager.isOn(s));
            }
        });
        final Button go = row.findViewById(R.id.row_goto);
        go.setEnabled(l != null ? l.bounds != null : s.bounds != null);
        go.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final ZoneLayer now = manager.find(s.id);
                manager.frame(now != null && now.bounds != null ? now.bounds : s.bounds);
            }
        });
        final Button refresh = row.findViewById(R.id.row_refresh);
        refresh.setEnabled(isOn && !(l.refreshing || l.busy));
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final ZoneLayer now = manager.find(s.id);
                if (now != null)
                    manager.refresh(now);
            }
        });
        return row;
    }

    /**
     * What the row promises before it is on (publisher, kind, count at catalog time) and
     * what it holds once it is (count, age, STALE with the reason).
     */
    private static String statusLine(Catalog.Source s, ZoneLayer l) {
        final StringBuilder sb = new StringBuilder();
        if (!s.publisher.isEmpty())
            sb.append(s.publisher).append(" · ");
        sb.append("live".equals(s.kind) ? "live, every " + s.refreshMin + " min"
                : "static".equals(s.kind) ? "static zones" : "support layer");
        if (l == null || !l.isVisible()) {
            sb.append(" · ").append(s.features).append(s.features == 1 ? " feature" : " features");
            if (!s.note.isEmpty())
                sb.append("\n").append(s.note);
            return sb.toString();
        }
        sb.append("\n");
        if (l.refreshing || l.busy) {
            sb.append("Loading…");
            if (l.progress > 0)
                sb.append(' ').append(l.progress).append(s.features > 0 ? " of ~" + s.features : " so far");
            else if (s.features > 0)
                sb.append(" ~").append(s.features).append(" features");
            return sb.toString();
        }
        if (l.outsideRadius > 0)
            sb.append(l.count).append(" of ").append(l.count + l.outsideRadius).append(" zones within the radius");
        else
            sb.append(l.count).append(l.count == 1 ? " feature" : " features");
        if (l.lastRefresh > 0)
            sb.append(" · refreshed ").append(age(l.lastRefresh)).append(" ago");
        else
            sb.append(" · never refreshed");
        if (l.stale)
            sb.append(" · STALE: ").append(l.status);
        else if (l.status.startsWith("capped"))
            sb.append(" · ").append(l.status);
        return sb.toString();
    }

    private static String age(long since) {
        final long s = Math.max(0, (System.currentTimeMillis() - since) / 1000);
        if (s < 60)
            return s + " s";
        if (s < 3600)
            return (s / 60) + " min";
        if (s < 86400)
            return (s / 3600) + " h";
        return (s / 86400) + " d";
    }
}

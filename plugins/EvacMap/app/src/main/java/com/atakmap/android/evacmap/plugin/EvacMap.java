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

    // ---- counties -----------------------------------------------------------------

    /** Counties picked for a state, remembered per state so a picked list stays up. */
    private Set<String> selectedCounties(String st) {
        final Set<String> out = new LinkedHashSet<>();
        if (st == null)
            return out;
        try {
            final JSONArray arr = new JSONArray(uiPrefs().getString("counties." + st, "[]"));
            for (int i = 0; i < arr.length(); i++)
                out.add(arr.getString(i));
        } catch (Exception e) {
            Log.w(TAG, "county selection unreadable", e);
        }
        return out;
    }

    private void saveCounties(String st, Collection<String> counties) {
        uiPrefs().edit().putString("counties." + st, new JSONArray(counties).toString()).apply();
    }

    /** Counties of this state that have sources, in catalog order, with their source counts. */
    private static Map<String, Integer> countiesOf(List<Catalog.Source> srcs) {
        final Map<String, Integer> out = new LinkedHashMap<>();
        for (Catalog.Source s : srcs) {
            if (s.statewide())
                continue;
            final Integer n = out.get(s.county);
            out.put(s.county, n == null ? 1 : n + 1);
        }
        return out;
    }

    /** One or many, ticked in a multi-choice dialog on the MapView context, the way Cam Depot picks counties. */
    private void pickCounties() {
        final Catalog c = manager == null ? null : manager.catalog();
        if (c == null || state == null)
            return;
        final Map<String, Integer> counties = countiesOf(c.forState(state));
        if (counties.isEmpty()) {
            toast("No county sources for " + state + " yet");
            return;
        }
        final String[] names = counties.keySet().toArray(new String[0]);
        final String[] labels = new String[names.length];
        final boolean[] ticked = new boolean[names.length];
        final Set<String> selected = selectedCounties(state);
        for (int i = 0; i < names.length; i++) {
            final int n = counties.get(names[i]);
            labels[i] = names[i] + "  (" + n + (n == 1 ? " source)" : " sources)");
            ticked[i] = selected.contains(names[i]);
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
                        for (int i = 0; i < names.length; i++)
                            if (ticked[i])
                                chosen.add(names[i]);
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
            sb.append(", ").append(loading).append(loading == 1 ? " loading" : " loading");
        sb.append(" · ").append(manager.catalogStatus);
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
        // on, so nothing drawn on the map can be missing from the pane.
        final Map<String, Integer> counties = countiesOf(srcs);
        final Set<String> selected = selectedCounties(state);
        for (Catalog.Source s : srcs)
            if (!s.statewide() && manager.isOn(s))
                selected.add(s.county);
        if (counties.isEmpty()) {
            countiesButton.setText("No county sources for " + state + " yet");
            countiesButton.setEnabled(false);
            countyHint.setVisibility(View.GONE);
            return;
        }
        countiesButton.setEnabled(true);
        final List<String> shown = new ArrayList<>();
        for (String name : counties.keySet())
            if (selected.contains(name))
                shown.add(name);
        if (shown.isEmpty()) {
            countiesButton.setText("Pick counties  (" + counties.size() + " available)");
            countyHint.setVisibility(View.VISIBLE);
            return;
        }
        countiesButton.setText((shown.size() == 1 ? "County: " : shown.size() + " counties: ")
                + TextUtils.join(", ", shown));
        countyHint.setVisibility(View.GONE);
        for (Catalog.Source s : srcs)
            if (!s.statewide() && selected.contains(s.county))
                county.addView(sourceRow(s));
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
                if (now != null) {
                    now.panTo();
                } else if (s.bounds != null) {
                    final double[] b = s.bounds;
                    final double padLat = Math.max(0.002, (b[2] - b[0]) * 0.15);
                    final double padLon = Math.max(0.002, (b[3] - b[1]) * 0.15);
                    com.atakmap.android.util.ATAKUtilities.scaleToFit(mapView, new GeoPoint[] {
                            new GeoPoint(b[0] - padLat, b[1] - padLon),
                            new GeoPoint(b[2] + padLat, b[3] + padLon) }, 0d,
                            mapView.getWidth(), mapView.getHeight());
                }
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

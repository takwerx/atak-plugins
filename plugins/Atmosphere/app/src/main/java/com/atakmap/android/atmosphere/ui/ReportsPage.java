package com.atakmap.android.atmosphere.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.atakmap.android.atmosphere.compat.MapCompat;
import com.atakmap.android.atmosphere.data.Spot;
import com.atakmap.android.atmosphere.data.StormReports;
import com.atakmap.android.atmosphere.net.EgressPolicy;
import com.atakmap.android.atmosphere.data.DebrisFlow;
import com.atakmap.android.atmosphere.data.TornadoPaths;
import com.atakmap.android.atmosphere.overlay.OutlookOverlay;
import com.atakmap.android.atmosphere.overlay.StormReportsOverlay;
import com.atakmap.android.atmosphere.overlay.TornadoPathsOverlay;
import com.atakmap.android.atmosphere.plugin.R;
import com.atakmap.android.atmosphere.units.UnitSystem;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoBounds;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.map.AtakMapView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Storm Reports page: the reports the map layer holds, as a list narrowed by
 * where (all, near me, on the map, a state), ordered newest or closest, one family
 * at a time if wanted. IPAWS has the warnings; these are the observations
 * (operator, 2026-10-11: "make storm reports have its own page like ipaws ...
 * filter by event type and map center or my location").
 *
 * <p>The Spot Weather Forecast page's shape, carried forward. The list is the
 * layer's own data: the window (24, 48, 72 h) and the family are shared with the
 * map, so what the page lists is what the map draws. Nothing is fetched here; the
 * layer fetches, and the page is told when it has.
 */
public final class ReportsPage {

    private static final String TAG = "AtmosphereReportsPage";
    private static final String PREF_FILTER = "weather.reports.filter";
    private static final String PREF_STATE = "weather.reports.state";
    private static final String PREF_RADIUS = "weather.reports.radius";
    private static final String PREF_CLOSEST = "weather.reports.sort.closest";
    private static final int MAX_ROWS = 60;
    private static final int[] RADII = { 25, 50, 100, 250 };

    private enum Filter { ALL, NEAR, MAP, STATE }

    /** What the page needs from the pane around it. */
    public interface Host {
        UnitSystem units();

        /** The point the pane's top row is set to: my position, the map center, or a pick. */
        GeoPoint point();

        /** What the pane calls that point: "My position", "Map center", "Picked point". */
        String pointLabel();

        /** The layer has never been allowed: ask, by server name. */
        void askToAllowReports();

        /** Switch the map layer on or off; the list follows it. */
        void setReportsOn(boolean on);

        /** The Tornado Paths layer, whose surveyed paths the list carries too. */
        void setTornadoOn(boolean on);
    }

    /** The surveyed paths, as a type beside the report families in the picker. */
    private static final int PATHS = StormReports.FAMILIES.length;

    /**
     * One row: a report, or a surveyed tornado path. Both have a place, a time and
     * a point to fly to; the details text is the layer's own.
     */
    private static final class Item {
        final String label, where, office, details;
        final long time;
        final double lat, lon;
        final int color;
        final String state;
        /** 0..7 a report family, {@link #PATHS} a path. */
        final int type;

        Item(String label, String where, String office, String details, long time, double lat,
                double lon, int color, String state, int type) {
            this.label = label;
            this.where = where;
            this.office = office;
            this.details = details;
            this.time = time;
            this.lat = lat;
            this.lon = lon;
            this.color = color;
            this.state = state;
            this.type = type;
        }
    }

    private final Context pluginContext;
    private final MapView mapView;
    private final EgressPolicy egress;
    private final Host host;
    private final ScrollView root;
    private final LinearLayout gate, browse, detail, list, windowRow;
    private final TextView gateText, status, detailTitle, detailFacts;
    private final Button gateButton, all, near, onMap, state, sortNewest, sortClosest, typeButton;
    private StormReportsOverlay overlay;
    private TornadoPathsOverlay paths;
    private Filter filter = Filter.ALL;
    private String stateCode = "";
    private int radiusIndex = 1;
    private boolean closest;
    private Item showing;
    private static final long SETTLE_MS = 700;
    /**
     * The map moved: "On map" re-reads the map, and "Near" follows a map-center
     * point, once the map settles (operator, 2026-10-11: "i zoomed out list did not
     * change"). The list is a few rows; redrawing it is cheap.
     */
    private final Runnable settled = new Runnable() {
        @Override
        public void run() {
            if (showing != null || root.getVisibility() != View.VISIBLE)
                return;
            if (filter == Filter.MAP || filter == Filter.NEAR || closest)
                render();
        }
    };
    private final AtakMapView.OnMapMovedListener moved = new AtakMapView.OnMapMovedListener() {
        @Override
        public void onMapMoved(AtakMapView v, boolean animate) {
            mapView.removeCallbacks(settled);
            mapView.postDelayed(settled, SETTLE_MS);
        }
    };

    public ReportsPage(Context pluginContext, MapView mapView, EgressPolicy egress, Host host) {
        this.pluginContext = pluginContext;
        this.mapView = mapView;
        this.egress = egress;
        this.host = host;
        final SharedPreferences p = MapCompat.prefs();
        if (p != null) {
            try {
                filter = Filter.valueOf(p.getString(PREF_FILTER, Filter.ALL.name()));
            } catch (IllegalArgumentException e) {
                filter = Filter.ALL;
            }
            stateCode = p.getString(PREF_STATE, "");
            radiusIndex = Math.max(0, Math.min(RADII.length - 1, p.getInt(PREF_RADIUS, 1)));
            closest = p.getBoolean(PREF_CLOSEST, false);
            
        }

        root = new ScrollView(pluginContext);
        root.setFocusableInTouchMode(true);
        root.setDescendantFocusability(android.view.ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        final LinearLayout body = new LinearLayout(pluginContext);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, 0, 0, dp(8));
        root.addView(body);
        body.addView(heading(pluginContext.getString(R.string.heading_reports)));

        // Before the layer is allowed, or while it is off: one line and one button.
        gate = new LinearLayout(pluginContext);
        gate.setOrientation(LinearLayout.VERTICAL);
        gateText = text(14, false);
        gateText.setPadding(0, dp(6), 0, 0);
        gate.addView(gateText);
        gateButton = chip("");
        gateButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (wantsReports() && !egress.isLayerEnabled(StormReportsOverlay.LAYER_ID))
                    host.askToAllowReports();
                else if (wantsReports() && !(overlay != null && overlay.isOn()))
                    host.setReportsOn(true);
                else if (pathsShown())
                    host.setTornadoOn(true);
                else
                    selectAll();
                showGateOrList();
                render();
            }
        });
        gate.addView(gateButton, matchWidth(6));
        body.addView(gate);

        browse = new LinearLayout(pluginContext);
        browse.setOrientation(LinearLayout.VERTICAL);
        body.addView(browse);
        final LinearLayout where = row();
        all = chip(pluginContext.getString(R.string.reports_anywhere));
        near = chip(pluginContext.getString(R.string.reports_near));
        onMap = chip(pluginContext.getString(R.string.reports_map));
        state = chip(pluginContext.getString(R.string.spot_state));
        // Four chips with counts share one row; on a phone-width pane "Nationwide (143)"
        // was cut to "Nationw..." (operator, 2026-10-12). Two lines at 13sp instead.
        for (Button b : new Button[] { all, near, onMap, state }) {
            b.setSingleLine(false);
            b.setMaxLines(2);
            b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        }
        for (Button b : new Button[] { all, near, onMap, state })
            where.addView(b, weight(1f, b != state));
        browse.addView(where);
        final LinearLayout order = row();
        sortNewest = chip(pluginContext.getString(R.string.spot_sort_newest));
        sortClosest = chip(pluginContext.getString(R.string.spot_sort_closest));
        typeButton = chip(pluginContext.getString(R.string.reports_type_all));
        order.addView(sortNewest, weight(1f, true));
        order.addView(sortClosest, weight(1f, true));
        order.addView(typeButton, weight(1.4f, false));
        browse.addView(order);
        windowRow = row();
        browse.addView(windowRow);
        status = text(13, false);
        status.setPadding(0, dp(6), 0, 0);
        browse.addView(status);
        list = new LinearLayout(pluginContext);
        list.setOrientation(LinearLayout.VERTICAL);
        browse.addView(list);

        // One report: Back and Go to, the pill's words, then everything the office logged.
        detail = new LinearLayout(pluginContext);
        detail.setOrientation(LinearLayout.VERTICAL);
        detail.setVisibility(View.GONE);
        final LinearLayout bar = row();
        final Button back = chip(pluginContext.getString(R.string.back));
        final Button goTo = chip(pluginContext.getString(R.string.go_to));
        bar.addView(back, weight(1f, true));
        bar.addView(goTo, weight(1f, false));
        detail.addView(bar);
        detailTitle = text(17, true);
        detailTitle.setPadding(0, dp(8), 0, 0);
        detail.addView(detailTitle);
        detailFacts = text(14, false);
        detailFacts.setPadding(0, dp(4), 0, 0);
        detailFacts.setLineSpacing(dp(2), 1f);
        detail.addView(detailFacts);
        body.addView(detail);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showing = null;
                showGateOrList();
                render();
            }
        });
        goTo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (showing != null)
                    goTo(showing);
            }
        });

        wire();
        showGateOrList();
        mapView.addOnMapMovedListener(moved);
    }

    public View view() {
        return root;
    }

    /** The map layer, once the plugin has made it; the list is its data. */
    public void setOverlay(StormReportsOverlay o) {
        overlay = o;
        // An empty set is a dead end, never a state to start in.
        if (overlay.families() == 0)
            selectAll();
        showGateOrList();
        render();
    }

    private void selectAll() {
        if (overlay != null)
            overlay.setFamilies(StormReportsOverlay.ALL_FAMILIES);
    }

    /** The Tornado Paths layer; its surveyed paths join the list. */
    public void setPathsOverlay(TornadoPathsOverlay o) {
        paths = o;
        showGateOrList();
        render();
    }

    /** The layer fetched, or its window or family changed: redraw the list. */
    public void onDataChanged() {
        if (showing == null)
            render();
    }

    /** The page came into view. */
    public void onShown() {
        showGateOrList();
        if (overlay != null && overlay.isOn())
            overlay.refresh(false);
        if (paths != null && paths.isOn())
            paths.refresh(false);
        if (showing == null)
            render();
    }

    public void dispose() {
        mapView.removeOnMapMovedListener(moved);
        mapView.removeCallbacks(settled);
    }

    /** The pane's top-row point changed: a Near list follows it. */
    public void pointChanged() {
        if (showing == null && (filter == Filter.NEAR || closest))
            render();
    }

    /** Whether any report family is wanted; with none, the list is the paths alone. */
    private boolean wantsReports() {
        return overlay == null || overlay.families() != 0;
    }

    /** The paths ride the Tornado tick: one type, points and paths (operator, 2026-10-11). */
    private boolean pathsShown() {
        return overlay == null || overlay.showsFamily(0);
    }

    private void showGateOrList() {
        final boolean allowed = egress.isLayerEnabled(StormReportsOverlay.LAYER_ID);
        final boolean on = overlay != null && overlay.isOn();
        final boolean pathsOn = paths != null && paths.isOn();
        // Each kind wanted needs its own layer on; the reports layer first, since it
        // is the page's own.
        final boolean ready = (!wantsReports() || (allowed && on)) && (!pathsShown() || pathsOn)
                && (wantsReports() || pathsShown());
        if (wantsReports() && !allowed) {
            gateText.setText(pluginContext.getString(R.string.reports_gate_text));
            gateButton.setText(pluginContext.getString(R.string.reports_load));
        } else if (wantsReports() && !on) {
            gateText.setText(pluginContext.getString(R.string.reports_off_text));
            gateButton.setText(pluginContext.getString(R.string.reports_switch_on));
        } else if (pathsShown() && !pathsOn) {
            gateText.setText(pluginContext.getString(R.string.reports_paths_off_text));
            gateButton.setText(pluginContext.getString(R.string.reports_switch_paths_on));
        } else if (!ready) {
            gateText.setText(pluginContext.getString(R.string.reports_nothing_picked));
            gateButton.setText("Select all");
        }
        gate.setVisibility(ready ? View.GONE : View.VISIBLE);
        browse.setVisibility(ready && showing == null ? View.VISIBLE : View.GONE);
        detail.setVisibility(ready && showing != null ? View.VISIBLE : View.GONE);
    }

    private void wire() {
        all.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setFilter(Filter.ALL);
            }
        });
        near.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickRadius();
            }
        });
        onMap.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setFilter(Filter.MAP);
            }
        });
        state.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickState();
            }
        });
        sortNewest.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setClosest(false);
            }
        });
        sortClosest.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setClosest(true);
            }
        });
        typeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickType();
            }
        });
    }

    private void setFilter(Filter f) {
        filter = f;
        final SharedPreferences p = MapCompat.prefs();
        if (p != null)
            p.edit().putString(PREF_FILTER, f.name()).putString(PREF_STATE, stateCode)
                    .putInt(PREF_RADIUS, radiusIndex).apply();
        render();
    }

    private void setClosest(boolean value) {
        closest = value;
        final SharedPreferences p = MapCompat.prefs();
        if (p != null)
            p.edit().putBoolean(PREF_CLOSEST, value).apply();
        render();
    }

    // ---- the list -------------------------------------------------------------------------

    /** Everything the list can show: the reports in their window and family, and the paths in theirs. */
    private List<Item> pool() {
        final List<Item> out = new ArrayList<>();
        if (overlay != null)
            for (StormReports.Report r : overlay.reports())
                out.add(new Item(r.label(), r.place + (r.state.isEmpty() ? "" : ", " + r.state),
                        r.office, StormReportsOverlay.details(r), r.time, r.lat, r.lon,
                        StormReports.COLORS[r.family], r.state, r.family));
        if (pathsShown() && paths != null && paths.isOn())
            for (OutlookOverlay.Area a : paths.shownAreas()) {
                if (!(a.extra instanceof TornadoPaths.Track))
                    continue;
                final TornadoPaths.Track t = (TornadoPaths.Track) a.extra;
                final double[] at = DebrisFlow.firstPoint(t.geometry);
                if (at == null)
                    continue;
                final String length = Double.isNaN(t.lengthMi) || t.lengthMi <= 0 ? ""
                        : distanceLabel(t.lengthMi * 1609.344) + " path";
                out.add(new Item(a.title, join(" \u00b7 ", "Tornado path", length),
                        t.office.isEmpty() ? "" : t.office + " office", a.details,
                        t.stormDate > 0 ? t.stormDate : t.start, at[0], at[1], a.color, "", PATHS));
            }
        Collections.sort(out, new Comparator<Item>() {
            @Override
            public int compare(Item x, Item y) {
                return Long.compare(y.time, x.time);
            }
        });
        return out;
    }

    private void render() {
        if (overlay == null)
            return;
        buildWindowRow();
        paintFilters();
        list.removeAllViews();
        final List<Item> pool = pool();
        if (pool.isEmpty()) {
            if (!wantsReports())
                status.setText("No tornado paths in the last " + pathsWindow()
                        + ". A path appears a day or more after the storm.");
            else
                status.setText(overlay.reportsAnyFamily().isEmpty()
                        ? "Getting the storm reports\u2026"
                        : "No " + familyWord() + " reports in the last " + overlay.dayLabel(overlay.day()) + ".");
            return;
        }
        final GeoPoint self = MapCompat.selfPoint();
        List<Item> shown;
        String what;
        switch (filter) {
            case NEAR: {
                final GeoPoint from = host.point();
                if (from == null) {
                    status.setText("No position yet for " + host.pointLabel().toLowerCase(Locale.US));
                    return;
                }
                shown = near(pool, from, radiusMeters());
                what = "within " + radiusLabel(radiusIndex) + " of " + host.pointLabel().toLowerCase(Locale.US);
                break;
            }
            case MAP: {
                final double[] box = viewBox();
                if (box == null) {
                    shown = new ArrayList<>(pool);
                    what = "in the country (zoom in to use the map)";
                } else {
                    shown = inBox(pool, box);
                    what = "on the map";
                }
                break;
            }
            case STATE:
                shown = inState(pool, stateCode);
                what = "in " + Spot.stateName(stateCode)
                        + (pathsShown() ? " (tornado paths carry no state and are left out)" : "");
                break;
            default:
                shown = new ArrayList<>(pool);
                what = "in the country";
        }
        final GeoPoint from = closest ? closestFrom() : null;
        if (from != null)
            sortByDistance(shown, from);
        final String order = from != null ? "closest" : "newest";
        final boolean withPaths = pathsShown() && paths != null && paths.isOn();
        final String window = !wantsReports() ? "last " + pathsWindow()
                : "last " + overlay.dayLabel(overlay.day())
                        + (withPaths ? ", paths from the last " + pathsWindow() : "");
        final String noun = !wantsReports() ? "tornado paths"
                : familyWord() + " reports" + (withPaths ? " and paths" : "");
        if (shown.isEmpty())
            status.setText("No " + noun + " " + what + " in the " + window + ".");
        else if (shown.size() > MAX_ROWS)
            status.setText("The " + order + " " + MAX_ROWS + " of " + shown.size() + " " + noun
                    + " " + what + ", " + window + ". Narrow it down to see the rest.");
        else
            status.setText(shown.size() + " " + noun + " " + what + ", " + window + ", " + order
                    + " first.");
        for (int i = 0; i < shown.size() && i < MAX_ROWS; i++)
            list.addView(row(shown.get(i), self));
    }

    /** The paths layer's window, "30 days", or "" before the layer is handed over. */
    private String pathsWindow() {
        return paths == null ? "" : paths.dayLabel(paths.day());
    }

    /** "storm", one family's name, or "selected" for a mix. */
    private String familyWord() {
        if (overlay == null || overlay.allFamilies())
            return "storm";
        int n = 0, which = -1;
        for (int i = 0; i < StormReports.FAMILIES.length; i++)
            if (overlay.showsFamily(i)) {
                n++;
                which = i;
            }
        return n == 1 ? StormReports.FAMILIES[which].toLowerCase(Locale.US) : "selected";
    }

    /** The window chips, the layer's own: 24 h, 48 h, 72 h. */
    private void buildWindowRow() {
        windowRow.removeAllViews();
        final OutlookOverlay layer = overlay;
        if (layer == null)
            return;
        for (int d = 1; d <= layer.days(); d++) {
            final int value = d;
            final Button b = chip(layer.dayLabel(d));
            green(b, layer.day() == d);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    layer.setDay(value);
                    render();
                }
            });
            windowRow.addView(b, weight(1f, d < layer.days()));
        }
    }

    /** Each filter says what it would show before it is tapped. */
    private void paintFilters() {
        final GeoPoint self = MapCompat.selfPoint();
        final List<Item> can = pool();
        all.setText(pluginContext.getString(R.string.reports_anywhere) + count(can, can.size()));
        final GeoPoint from = host.point();
        near.setText(from == null || can.isEmpty() ? pluginContext.getString(R.string.reports_near)
                : radiusLabel(radiusIndex) + count(can, near(can, from, radiusMeters()).size()));
        final double[] box = viewBox();
        onMap.setText(pluginContext.getString(R.string.reports_map)
                + (box == null ? "" : count(can, inBox(can, box).size())));
        state.setText(stateCode.isEmpty() ? pluginContext.getString(R.string.spot_state)
                : Spot.stateName(stateCode) + count(can, inState(can, stateCode).size()));
        green(sortNewest, !closest);
        green(sortClosest, closest);
        typeButton.setText(typeWords());
        green(typeButton, !overlay.allFamilies());
        green(all, filter == Filter.ALL);
        green(near, filter == Filter.NEAR);
        green(onMap, filter == Filter.MAP);
        green(state, filter == Filter.STATE);
    }

    private static String count(List<?> pool, int n) {
        return pool.isEmpty() ? "" : " (" + n + ")";
    }

    private View row(final Item r, GeoPoint self) {
        final LinearLayout row = new LinearLayout(pluginContext);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackgroundResource(R.drawable.btn_gray);
        row.setPadding(dp(10), dp(6), dp(10), dp(8));
        row.setClickable(true);
        final LinearLayout.LayoutParams lp = matchWidth(4);
        row.setLayoutParams(lp);
        final View dot = new View(pluginContext);
        dot.setBackgroundColor(r.color);
        final LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(dp(14), dp(14));
        dp.rightMargin = dp(10);
        dp.topMargin = dp(5);
        row.addView(dot, dp);
        final LinearLayout col = new LinearLayout(pluginContext);
        col.setOrientation(LinearLayout.VERTICAL);
        row.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        final TextView title = text(15, true);
        title.setText(r.label);
        col.addView(title);
        final TextView where = text(13, false);
        where.setTextColor(0xFFD0D0D0);
        where.setText(join(" \u00b7 ", r.where, r.office));
        col.addView(where);
        final TextView when = text(12, false);
        when.setTextColor(0xFFA0A0A0);
        when.setText(join(" · ", clock(r.time),
                self == null ? "" : distanceLabel(Spot.distanceMeters(self.getLatitude(),
                        self.getLongitude(), r.lat, r.lon))));
        col.addView(when);
        // One tap does both: the map goes there and the details open (operator,
        // 2026-10-11: "you just tap right on the list and it goes there and then
        // it opens up the side pane").
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                goTo(r);
                showDetail(r);
            }
        });
        final TextView go = text(13, true);
        go.setText(pluginContext.getString(R.string.go_to));
        go.setTextColor(pluginContext.getResources().getColor(R.color.state_on));
        go.setPadding(dp(8), dp(4), 0, 0);
        row.addView(go);
        return row;
    }

    private void showDetail(Item r) {
        showing = r;
        showGateOrList();
        detailTitle.setText(r.label);
        detailFacts.setText(r.details);
        root.scrollTo(0, 0);
    }

    private void goTo(Item r) {
        try {
            mapView.getMapController().panZoomTo(new GeoPoint(r.lat, r.lon),
                    mapView.mapResolutionAsMapScale(20d), true);
        } catch (LinkageError | RuntimeException e) {
            Log.w(TAG, "panZoomTo failed; plain pan", e);
            mapView.getMapController().panTo(new GeoPoint(r.lat, r.lon), true);
        }
    }

    // ---- pickers: a tile grid of TakwerxButtons --------------------------------------------

    private void pickRadius() {
        final String[] labels = new String[RADII.length];
        for (int i = 0; i < RADII.length; i++)
            labels[i] = radiusLabel(i);
        tiles("How far from " + host.pointLabel().toLowerCase(Locale.US), labels, radiusIndex, 3,
                new Picked() {
                    @Override
                    public void picked(int which) {
                        radiusIndex = which;
                        setFilter(Filter.NEAR);
                    }
                });
    }

    private void pickState() {
        if (overlay == null)
            return;
        final Map<String, Integer> byState = new LinkedHashMap<>();
        for (Item r : pool())
            if (!r.state.isEmpty())
                byState.put(r.state, byState.containsKey(r.state) ? byState.get(r.state) + 1 : 1);
        final List<String> codes = new ArrayList<>(byState.keySet());
        Collections.sort(codes);
        if (codes.isEmpty())
            return;
        final String[] labels = new String[codes.size()];
        for (int i = 0; i < codes.size(); i++)
            labels[i] = Spot.stateName(codes.get(i)) + " (" + byState.get(codes.get(i)) + ")";
        tiles(pluginContext.getString(R.string.spot_pick_state), labels, codes.indexOf(stateCode), 3,
                new Picked() {
                    @Override
                    public void picked(int which) {
                        stateCode = codes.get(which);
                        setFilter(Filter.STATE);
                    }
                });
    }

    /** What the type button reads: All Types, one name, or how many are ticked. */
    private String typeWords() {
        if (overlay == null)
            return pluginContext.getString(R.string.reports_type_all);
        if (overlay.allFamilies())
            return pluginContext.getString(R.string.reports_type_all);
        final List<String> names = new ArrayList<>();
        for (int i = 0; i < StormReports.FAMILIES.length; i++)
            if (overlay.showsFamily(i))
                names.add(StormReports.FAMILIES[i]);
        if (names.isEmpty())
            return "No types";
        return names.size() == 1 ? names.get(0) : names.size() + " types";
    }

    /**
     * Every family with how many of each in the window, and the surveyed paths, as
     * switches: tick any mix, for the map and the list alike (operator, 2026-10-11:
     * "i need to be able to select what i want").
     */
    private void pickType() {
        if (overlay == null)
            return;
        final Context ctx = MapCompat.atakContext();
        if (ctx == null)
            return;
        final List<StormReports.Report> pool = overlay.reportsAnyFamily();
        final int[] counts = new int[StormReports.FAMILIES.length];
        for (StormReports.Report r : pool)
            counts[r.family]++;
        int nPaths = 0;
        if (paths != null && paths.isOn())
            for (OutlookOverlay.Area a : paths.shownAreas())
                if (a.extra instanceof TornadoPaths.Track)
                    nPaths++;
        final LinearLayout grid = new LinearLayout(pluginContext);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(dp(8), dp(4), dp(4), dp(4));
        final ScrollView scroll = new ScrollView(pluginContext);
        scroll.addView(grid);
        final Button[] tiles = new Button[StormReports.FAMILIES.length];
        final String[] names = new String[StormReports.FAMILIES.length];
        for (int i = 0; i < StormReports.FAMILIES.length; i++)
            names[i] = StormReports.FAMILIES[i] + " (" + counts[i] + ")";
        // Tornado is points and paths in one: the count says both.
        names[0] = "Tornado (" + counts[0] + " reports, "
                + (paths == null || !paths.isOn() ? "paths off" : nPaths + " paths") + ")";
        final Button allTile = chip("Select all");
        allTile.setTextSize(14);
        final Button noneTile = chip("Clear all");
        noneTile.setTextSize(14);
        // A switch reads as one: a check mark and green when on, plain white when off.
        final Runnable paint = new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < StormReports.FAMILIES.length; i++) {
                    final boolean on = overlay.showsFamily(i);
                    tiles[i].setText((on ? "\u2713 " : "") + names[i]);
                    green(tiles[i], on);
                }
            }
        };
        // Select all and Clear all first, where they are seen without scrolling.
        LinearLayout row = new LinearLayout(pluginContext);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(allTile, weight(1f, true));
        row.addView(noneTile, weight(1f, true));
        grid.addView(row);
        for (int i = 0; i < StormReports.FAMILIES.length; i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(pluginContext);
                row.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(row);
            }
            final int which = i;
            final Button b = chip(names[i]);
            b.setTextSize(14);
            b.setSingleLine(false);
            b.setMaxLines(2);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    overlay.setFamilies(overlay.families() ^ (1 << which));
                    paint.run();
                    showGateOrList();
                    render();
                }
            });
            tiles[i] = b;
            row.addView(b, weight(1f, true));
        }
        allTile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectAll();
                paint.run();
                showGateOrList();
                render();
            }
        });
        noneTile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                overlay.setFamilies(0);
                paint.run();
                showGateOrList();
                render();
            }
        });
        paint.run();
        final AlertDialog dialog = new AlertDialog.Builder(ctx)
                .setTitle(pluginContext.getString(R.string.reports_pick_type))
                .setView(scroll)
                .setNegativeButton(pluginContext.getString(R.string.close), null)
                .create();
        // Closed with nothing ticked is not "show nothing": it is back to everything.
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                if (overlay.families() == 0) {
                    selectAll();
                    showGateOrList();
                    render();
                }
            }
        });
        dialog.show();
    }

    private interface Picked {
        void picked(int which);
    }

    /** A compact dialog of TakwerxButton tiles, the current choice green, on ATAK's context. */
    private void tiles(String title, String[] labels, int current, int columns, final Picked picked) {
        final Context ctx = MapCompat.atakContext();
        if (ctx == null)
            return;
        final LinearLayout grid = new LinearLayout(pluginContext);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(dp(8), dp(4), dp(4), dp(4));
        final ScrollView scroll = new ScrollView(pluginContext);
        scroll.addView(grid);
        final AlertDialog dialog = new AlertDialog.Builder(ctx)
                .setTitle(title)
                .setView(scroll)
                .setNegativeButton(pluginContext.getString(R.string.close), null)
                .create();
        LinearLayout row = null;
        for (int i = 0; i < labels.length; i++) {
            if (i % columns == 0) {
                row = new LinearLayout(pluginContext);
                row.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(row);
            }
            final Button b = chip(labels[i]);
            b.setTextSize(14);
            // A long family name wraps rather than losing its count.
            b.setSingleLine(false);
            b.setMaxLines(2);
            green(b, i == current);
            final int which = i;
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dialog.dismiss();
                    picked.picked(which);
                }
            });
            row.addView(b, weight(1f, true));
        }
        dialog.show();
    }

    // ---- filters over the reports ---------------------------------------------------------

    private static List<Item> near(List<Item> all, GeoPoint from, double meters) {
        final List<Item> out = new ArrayList<>();
        for (Item r : all)
            if (Spot.distanceMeters(from.getLatitude(), from.getLongitude(), r.lat, r.lon) <= meters)
                out.add(r);
        return out;
    }

    private static List<Item> inBox(List<Item> all, double[] box) {
        final List<Item> out = new ArrayList<>();
        for (Item r : all)
            if (r.lat <= box[0] && r.lat >= box[2] && r.lon >= box[1] && r.lon <= box[3])
                out.add(r);
        return out;
    }

    private static List<Item> inState(List<Item> all, String code) {
        final List<Item> out = new ArrayList<>();
        for (Item r : all)
            if (r.state.equalsIgnoreCase(code))
                out.add(r);
        return out;
    }

    private static void sortByDistance(List<Item> list, final GeoPoint from) {
        Collections.sort(list, new Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                return Double.compare(
                        Spot.distanceMeters(from.getLatitude(), from.getLongitude(), a.lat, a.lon),
                        Spot.distanceMeters(from.getLatitude(), from.getLongitude(), b.lat, b.lon));
            }
        });
    }

    /** {north, west, south, east}, or null when the map shows the world. */
    private double[] viewBox() {
        final GeoBounds b = mapView.getBounds();
        if (b == null || Double.isNaN(b.getNorth()) || Double.isNaN(b.getSouth())
                || Double.isNaN(b.getEast()) || Double.isNaN(b.getWest())
                || b.getEast() <= b.getWest() || b.getEast() - b.getWest() >= 355)
            return null;
        return new double[] { b.getNorth(), b.getWest(), b.getSouth(), b.getEast() };
    }

    /** Closest to the pane's point, whatever the top row is set to. */
    private GeoPoint closestFrom() {
        final GeoPoint p = host.point();
        if (p != null)
            return p;
        final double[] box = viewBox();
        return box == null ? null : new GeoPoint((box[0] + box[2]) / 2, (box[1] + box[3]) / 2);
    }

    // ---- units and words ------------------------------------------------------------------

    private double unitMeters() {
        switch (host.units()) {
            case METRIC:
                return 1000d;
            case AVIATION:
                return 1852d;
            default:
                return 1609.344;
        }
    }

    private String unitName() {
        switch (host.units()) {
            case METRIC:
                return "km";
            case AVIATION:
                return "nm";
            default:
                return "mi";
        }
    }

    private double radiusMeters() {
        return RADII[radiusIndex] * unitMeters();
    }

    private String radiusLabel(int i) {
        return RADII[i] + " " + unitName();
    }

    private String distanceLabel(double meters) {
        final double v = meters / unitMeters();
        return (v < 10 ? String.format(Locale.US, "%.1f", v) : String.valueOf(Math.round(v)))
                + " " + unitName();
    }

    /** "today 8:13 pm", "yesterday 8:13 pm", or the day and date. */
    private static String clock(long when) {
        if (when <= 0)
            return "at an unknown time";
        final SimpleDateFormat key = new SimpleDateFormat("yyyyMMdd", Locale.US);
        final String time = new SimpleDateFormat("h:mm a", Locale.US)
                .format(new Date(when)).replace("AM", "am").replace("PM", "pm");
        final String on = key.format(new Date(when));
        if (on.equals(key.format(new Date())))
            return "today " + time;
        if (on.equals(key.format(new Date(System.currentTimeMillis() - 86_400_000L))))
            return "yesterday " + time;
        return new SimpleDateFormat("EEE MMM d", Locale.US).format(new Date(when)) + ", " + time;
    }

    private static String join(String sep, String... parts) {
        final StringBuilder b = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isEmpty())
                continue;
            if (b.length() > 0)
                b.append(sep);
            b.append(p);
        }
        return b.toString();
    }

    // ---- widgets ---------------------------------------------------------------------------

    private TextView heading(String words) {
        final TextView t = new TextView(pluginContext);
        t.setText(words);
        t.setTextSize(12f);
        t.setAllCaps(true);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(0xFF00E5FF);
        t.setPadding(0, dp(8), 0, 0);
        return t;
    }

    private TextView text(int sp, boolean bold) {
        final TextView t = new TextView(pluginContext);
        t.setTextSize(sp);
        t.setTextColor(Color.WHITE);
        if (bold)
            t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button chip(String words) {
        final Button b = (Button) LayoutInflater.from(pluginContext)
                .inflate(R.layout.trend_chip, null, false);
        b.setText(words);
        return b;
    }

    private LinearLayout row() {
        final LinearLayout r = new LinearLayout(pluginContext);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setLayoutParams(matchWidth(4));
        return r;
    }

    private LinearLayout.LayoutParams weight(float w, boolean gapRight) {
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, w);
        if (gapRight)
            lp.rightMargin = dp(4);
        return lp;
    }

    private LinearLayout.LayoutParams matchWidth(int topDp) {
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp);
        return lp;
    }

    private void green(Button b, boolean on) {
        b.setTextColor(on ? pluginContext.getResources().getColor(R.color.state_on) : Color.WHITE);
    }

    private int dp(int v) {
        return Math.round(v * pluginContext.getResources().getDisplayMetrics().density);
    }
}

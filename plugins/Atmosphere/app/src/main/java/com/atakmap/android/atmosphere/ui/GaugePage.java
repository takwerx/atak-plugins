package com.atakmap.android.atmosphere.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.atmosphere.data.Nwps;
import com.atakmap.android.atmosphere.net.EgressPolicy;
import com.atakmap.android.atmosphere.net.Http;
import com.atakmap.android.atmosphere.overlay.GaugeOverlay;
import com.atakmap.android.atmosphere.plugin.R;
import com.atakmap.android.atmosphere.units.Quantity;
import com.atakmap.android.atmosphere.units.UnitSystem;
import com.atakmap.android.atmosphere.units.Units;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.maps.coords.GeoCalculations;
import com.atakmap.coremap.maps.coords.GeoPoint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The river gauges as a list, nearest first: the station page's shape, reading
 * the gauge layer. The filters are the map's own, each with its count.
 */
public final class GaugePage {

    public interface Host {
        UnitSystem units();

        EgressPolicy egress();
    }

    /** What the last opened record fetched, so Back and reopen do not refetch. */
    private String chartLid;
    private Nwps.Hydrograph chart;
    private Nwps.Stages chartStages = Nwps.Stages.NONE;
    private int chartDays = 7;
    private HydrographView chartView;
    private TextView chartStatus;

    private final Context pluginContext;
    private final MapView mapView;
    private final Host host;
    private final View root;
    private final LinearLayout filterRow, scopeRow;
    private final TextView status, heading;
    private final ListView list;
    private final View detail;
    private final LinearLayout detailBody;
    private final Adapter adapter = new Adapter();
    private Nwps.Gauge showing;

    private GaugeOverlay layer;
    private int filter = GaugeOverlay.SHOW_ALL;
    private List<Nwps.Gauge> shown = new ArrayList<>();

    public GaugePage(Context pluginContext, MapView mapView, Host host) {
        this.pluginContext = pluginContext;
        this.mapView = mapView;
        this.host = host;
        root = PluginLayoutInflater.inflate(pluginContext, R.layout.page_stations, null);
        filterRow = root.findViewById(R.id.stations_filter_row);
        scopeRow = root.findViewById(R.id.stations_scope_row);
        status = root.findViewById(R.id.stations_page_status);
        list = root.findViewById(R.id.stations_list);
        list.setAdapter(adapter);
        detail = root.findViewById(R.id.stations_detail);
        detailBody = root.findViewById(R.id.stations_detail_body);
        heading = root.findViewById(R.id.stations_page_heading);
        heading.setText(R.string.gauges_page_heading);
    }

    public View view() {
        return root;
    }

    public void setLayer(GaugeOverlay overlay) {
        layer = overlay;
        refresh();
    }

    public void refresh() {
        if (layer == null)
            return;
        final List<Nwps.Gauge> all = layer.gauges();
        final GeoPoint from = layer.originPoint();
        int high = 0, starred = 0;
        final List<Nwps.Gauge> keep = new ArrayList<>();
        for (Nwps.Gauge g : all) {
            if (layer.passes(GaugeOverlay.SHOW_HIGH, g))
                high++;
            if (layer.isFavorite(g))
                starred++;
            if (layer.passes(filter, g))
                keep.add(g);
        }
        if (from != null)
            Collections.sort(keep, new Comparator<Nwps.Gauge>() {
                @Override
                public int compare(Nwps.Gauge a, Nwps.Gauge b) {
                    return Double.compare(metersFrom(from, a), metersFrom(from, b));
                }
            });
        shown = keep;
        buildFilterRow(all.size(), high, starred);
        buildScopeRow();
        status.setText(summary(all.size(), from));
        adapter.notifyDataSetChanged();
        if (showing != null)
            showDetail(showing);
    }

    private String summary(int total, GeoPoint from) {
        if (layer == null || !layer.isOn())
            return "Turn the river gauges layer on to see them here.";
        if (from == null)
            return layer.isFromMe() ? "No position yet — switch to the map center."
                    : "The map has no center yet.";
        if (total == 0)
            return "No gauges within " + layer.miles() + " mi.";
        return shown.size() + " of " + total + " gauges, nearest first, within "
                + layer.miles() + " mi of " + (layer.isFromMe() ? "you" : "the map");
    }

    private void buildFilterRow(int total, int high, int starred) {
        filterRow.removeAllViews();
        filterRow.addView(tile("All (" + total + ")", filter == GaugeOverlay.SHOW_ALL, 0,
                GaugeOverlay.SHOW_ALL));
        filterRow.addView(tile("High water (" + high + ")", filter == GaugeOverlay.SHOW_HIGH,
                GaugeOverlay.legendColor(Nwps.ACTION), GaugeOverlay.SHOW_HIGH));
        final View fav = tile("★ (" + starred + ")", filter == GaugeOverlay.SHOW_FAVORITES,
                StationPage.STAR_ON, GaugeOverlay.SHOW_FAVORITES);
        ((LinearLayout.LayoutParams) fav.getLayoutParams()).weight = 0.6f;
        filterRow.addView(fav);
    }

    private void buildScopeRow() {
        scopeRow.removeAllViews();
        final boolean me = layer.isFromMe();
        scopeRow.addView(scopeTile(pluginContext.getString(R.string.stations_from_me), me, true));
        scopeRow.addView(scopeTile(pluginContext.getString(R.string.stations_from_map), !me, false));
    }

    private void styleStar(TextView star, Nwps.Gauge g) {
        final boolean on = layer != null && layer.isFavorite(g);
        star.setText(on ? "★" : "☆");
        star.setTextColor(on ? StationPage.STAR_ON : StationPage.STAR_OFF);
    }

    private void toggleFavorite(Nwps.Gauge g) {
        if (layer == null)
            return;
        layer.toggleFavorite(g);
        refresh();
    }

    private View tile(String label, boolean chosen, int color, final int value) {
        final Button b = chip(label, chosen, color);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                filter = value;
                refresh();
            }
        });
        return b;
    }

    private View scopeTile(String label, boolean chosen, final boolean fromMe) {
        final Button b = chip(label, chosen, 0);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (layer != null)
                    layer.setFromMe(fromMe);
                refresh();
            }
        });
        return b;
    }

    private Button chip(String label, boolean chosen, int color) {
        final Button b = (Button) LayoutInflater.from(pluginContext)
                .inflate(R.layout.trend_chip, filterRow, false);
        b.setText(label);
        b.setTextSize(13);
        b.setTextColor(chosen
                ? (color != 0 ? color : pluginContext.getResources().getColor(R.color.state_on))
                : 0xFFFFFFFF);
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = dp(4);
        b.setLayoutParams(lp);
        return b;
    }

    private static double metersFrom(GeoPoint from, Nwps.Gauge g) {
        if (from == null || Double.isNaN(g.latitude))
            return Double.MAX_VALUE;
        return GeoCalculations.distanceTo(from, new GeoPoint(g.latitude, g.longitude));
    }

    private int dp(int v) {
        return Math.round(v * pluginContext.getResources().getDisplayMetrics().density);
    }

    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int i) {
            return shown.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            final View row = convert != null ? convert
                    : PluginLayoutInflater.inflate(pluginContext, R.layout.station_row, null);
            final Nwps.Gauge g = shown.get(i);
            row.findViewById(R.id.state).setBackgroundColor(
                    GaugeOverlay.legendColor(g.category()));
            ((TextView) row.findViewById(R.id.name)).setText(g.name);
            ((TextView) row.findViewById(R.id.detail)).setText(detail(g));
            final TextView star = row.findViewById(R.id.star);
            styleStar(star, g);
            star.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggleFavorite(g);
                }
            });
            row.findViewById(R.id.goto_btn).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    goTo(g);
                }
            });
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showDetail(g);
                }
            });
            return row;
        }
    }

    /** "No flooding  ·  2.53 ft  ·  57 cfs  ·  4.1 mi  ·  75 min ago" */
    private String detail(Nwps.Gauge g) {
        final UnitSystem system = host.units();
        final StringBuilder b = new StringBuilder(Nwps.label(g.category()));
        append(b, GaugeOverlay.stage(g.observed.stage, system));
        append(b, GaugeOverlay.flow(g.observed.flow, system));
        final GeoPoint from = layer == null ? null : layer.originPoint();
        if (from != null)
            append(b, Units.format(Quantity.LENGTH, metersFrom(from, g), system));
        append(b, ago(g.observed.at));
        return b.toString();
    }

    private static void append(StringBuilder b, String s) {
        if (s == null || s.isEmpty())
            return;
        if (b.length() > 0)
            b.append("  ·  ");
        b.append(s);
    }

    private static String ago(long at) {
        if (at <= 0)
            return "";
        final double hours = (System.currentTimeMillis() - at) / 3_600_000.0;
        if (hours < 1.5)
            return Math.max(1, Math.round(hours * 60)) + " min ago";
        if (hours < 48)
            return Math.round(hours) + " h ago";
        return Math.round(hours / 24) + " d ago";
    }

    private void showDetail(final Nwps.Gauge g) {
        showing = g;
        detailBody.removeAllViews();
        final LinearLayout buttons = new LinearLayout(pluginContext);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(action("Back", new Runnable() {
            @Override
            public void run() {
                showList();
            }
        }));
        buttons.addView(action("Go to", new Runnable() {
            @Override
            public void run() {
                goTo(g);
            }
        }));
        final boolean starred = layer != null && layer.isFavorite(g);
        final Button star = (Button) action(starred ? "★ Favorite" : "☆ Favorite",
                new Runnable() {
                    @Override
                    public void run() {
                        toggleFavorite(g);
                        showDetail(g);
                    }
                });
        star.setTextColor(starred ? StationPage.STAR_ON : 0xFFFFFFFF);
        buttons.addView(star);
        detailBody.addView(buttons);

        final TextView title = new TextView(pluginContext);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(17);
        title.setPadding(0, dp(8), 0, dp(2));
        title.setText(g.name);
        detailBody.addView(title);
        // The hydrograph, under the name and above the fields: it is what the
        // record is opened for. Two requests, both cached against the id, so Back
        // and reopen cost nothing; a fresh gauge fetches.
        final LinearLayout dayRow = new LinearLayout(pluginContext);
        dayRow.setOrientation(LinearLayout.HORIZONTAL);
        dayRow.setPadding(0, dp(6), 0, 0);
        for (final int d : new int[] { 1, 3, 7, 14, 30 }) {
            final Button b = chip(d + "d", d == chartDays, 0);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    chartDays = d;
                    showDetail(g);
                }
            });
            dayRow.addView(b);
        }
        detailBody.addView(dayRow);
        chartView = new HydrographView(pluginContext);
        detailBody.addView(chartView);
        chartStatus = new TextView(pluginContext);
        chartStatus.setTextSize(11);
        chartStatus.setAlpha(0.7f);
        chartStatus.setTextColor(0xFFFFFFFF);
        detailBody.addView(chartStatus);
        if (g.lid.equals(chartLid) && chart != null) {
            chartView.set(chart, chartStages, host.units(), chartDays);
            chartStatus.setText(chartCaption());
        } else {
            chartStatus.setText("Getting the hydrograph…");
            fetchChart(g);
        }
        for (String[] r : GaugeOverlay.describe(g, host.units(), System.currentTimeMillis()))
            detailBody.addView(field(r[0], r[1]));

        detail.setVisibility(View.VISIBLE);
        list.setVisibility(View.GONE);
        heading.setVisibility(View.GONE);
        filterRow.setVisibility(View.GONE);
        scopeRow.setVisibility(View.GONE);
        status.setVisibility(View.GONE);
    }

    private String chartCaption() {
        if (chart == null)
            return "";
        final StringBuilder b = new StringBuilder();
        b.append("Solid: observed. Dashed: forecast");
        if (chart.forecast.isEmpty())
            b.append(" (none issued)");
        else if (chart.forecastIssued > 0)
            b.append(", issued ").append(new java.text.SimpleDateFormat("EEE h:mm a",
                    java.util.Locale.US).format(new java.util.Date(chart.forecastIssued)));
        b.append(". ");
        b.append(chartStages.any() ? "Bands: action, minor, moderate, major flood stages."
                : "This gauge has no flood stages defined.");
        return b.toString();
    }

    private void fetchChart(final Nwps.Gauge g) {
        final EgressPolicy egress = host.egress();
        final java.util.Map<String, String> headers = new java.util.HashMap<>();
        headers.put("Accept", "application/json");
        Http.get(Nwps.stageflowUrl(g.lid), egress.userAgent(), headers, new Http.Callback() {
            @Override
            public void onSuccess(String body) {
                final Nwps.Hydrograph h = Nwps.parseStageflow(body);
                Http.get(Nwps.gaugeUrl(g.lid), egress.userAgent(), headers,
                        new Http.Callback() {
                            @Override
                            public void onSuccess(String rec) {
                                deliver(g, h, Nwps.parseStages(rec));
                            }

                            @Override
                            public void onFailure(String error) {
                                deliver(g, h, Nwps.Stages.NONE);
                            }
                        });
            }

            @Override
            public void onFailure(String error) {
                if (showing != null && showing.lid.equals(g.lid) && chartStatus != null)
                    chartStatus.setText("Could not get the hydrograph: " + error);
            }
        });
    }

    private void deliver(Nwps.Gauge g, Nwps.Hydrograph h, Nwps.Stages s) {
        chartLid = g.lid;
        chart = h;
        chartStages = s;
        if (showing == null || !showing.lid.equals(g.lid) || chartView == null)
            return;
        chartView.set(h, s, host.units(), chartDays);
        chartStatus.setText(chartCaption());
    }

    private void showList() {
        showing = null;
        detail.setVisibility(View.GONE);
        list.setVisibility(View.VISIBLE);
        heading.setVisibility(View.VISIBLE);
        filterRow.setVisibility(View.VISIBLE);
        scopeRow.setVisibility(View.VISIBLE);
        status.setVisibility(View.VISIBLE);
    }

    private View action(String label, final Runnable onPress) {
        final Button b = (Button) LayoutInflater.from(pluginContext)
                .inflate(R.layout.trend_chip, filterRow, false);
        b.setText(label);
        b.setTextSize(14);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onPress.run();
            }
        });
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = dp(4);
        b.setLayoutParams(lp);
        return b;
    }

    private View field(String label, String value) {
        final LinearLayout row = new LinearLayout(pluginContext);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(5), 0, dp(1));
        final TextView l = new TextView(pluginContext);
        l.setTextSize(10);
        l.setAllCaps(true);
        l.setAlpha(0.6f);
        l.setTextColor(0xFFFFFFFF);
        l.setText(label);
        final TextView v = new TextView(pluginContext);
        v.setTextSize(15);
        v.setTextColor(0xFFFFFFFF);
        v.setText(value);
        row.addView(l);
        row.addView(v);
        return row;
    }

    private void goTo(Nwps.Gauge g) {
        if (Double.isNaN(g.latitude))
            return;
        try {
            mapView.getMapController().panTo(new GeoPoint(g.latitude, g.longitude), true);
        } catch (RuntimeException e) {
            // A pan that will not happen is not worth a crash.
        }
    }
}

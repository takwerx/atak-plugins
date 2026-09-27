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
import com.atakmap.android.atmosphere.data.Coops;
import com.atakmap.android.atmosphere.data.Cwf;
import com.atakmap.android.atmosphere.data.Ndbc;
import com.atakmap.android.atmosphere.net.EgressPolicy;
import com.atakmap.android.atmosphere.net.Http;
import com.atakmap.android.atmosphere.overlay.BuoyOverlay;
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
 * The buoys as a list, nearest first: the station page's shape, reading
 * the buoy layer. The filters are the map's own, each with its count.
 */
public final class BuoyPage {

    public interface Host {
        UnitSystem units();

        EgressPolicy egress();
    }

    /** CO-OPS station lists, fetched once a session; null until they land. */
    private static List<Coops.Station> tideStations, currentStations;
    /** When the lists were last asked for; a request older than this is retried. */
    private static long listsRequestedAt;
    private static final long LIST_RETRY_MS = 90_000L;
    private LinearLayout tideBlock;
    private String tideFor;
    /**
     * CO-OPS and api.weather.gov answers by URL. Every buoy redraw rebuilds the open
     * record, which was asking for the same tide twice a minute on 2026-09-26.
     * Predictions do not change; the observed level is six-minute data and a
     * coastal waters forecast is issued twice a day, so ten minutes holds for all
     * of it, and a failure is held the same length so a station with no water
     * level is not asked again at every redraw either.
     */
    private static final java.util.Map<String, Object[]> answers = new java.util.HashMap<>();
    private static final long ANSWER_MS = 10 * 60_000L;
    private LinearLayout marineBlock;


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
    private Ndbc.Buoy showing;

    private BuoyOverlay layer;
    private int filter = BuoyOverlay.SHOW_ALL;
    private List<Ndbc.Buoy> shown = new ArrayList<>();

    public BuoyPage(Context pluginContext, MapView mapView, Host host) {
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
        heading.setText(R.string.buoys_page_heading);
    }

    public View view() {
        return root;
    }

    public void setLayer(BuoyOverlay overlay) {
        layer = overlay;
        refresh();
    }

    public void refresh() {
        if (layer == null)
            return;
        final List<Ndbc.Buoy> all = layer.buoys();
        final GeoPoint from = layer.originPoint();
        int wind = 0, waves = 0, starred = 0;
        final List<Ndbc.Buoy> keep = new ArrayList<>();
        for (Ndbc.Buoy g : all) {
            if (g.hasWind())
                wind++;
            if (g.hasWaves())
                waves++;
            if (layer.isFavorite(g))
                starred++;
            if (layer.passes(filter, g))
                keep.add(g);
        }
        if (from != null)
            Collections.sort(keep, new Comparator<Ndbc.Buoy>() {
                @Override
                public int compare(Ndbc.Buoy a, Ndbc.Buoy b) {
                    return Double.compare(metersFrom(from, a), metersFrom(from, b));
                }
            });
        shown = keep;
        buildFilterRow(all.size(), wind, waves, starred);
        buildScopeRow();
        status.setText(summary(all.size(), from));
        adapter.notifyDataSetChanged();
        if (showing != null)
            showDetail(showing);
    }

    private String summary(int total, GeoPoint from) {
        if (layer == null || !layer.isOn())
            return "Turn the buoys layer on to see them here.";
        if (from == null)
            return layer.isFromMe() ? "No position yet — switch to the map center."
                    : "The map has no center yet.";
        if (total == 0)
            return "No buoys within " + layer.miles() + " mi.";
        return shown.size() + " of " + total + " buoys, nearest first, within "
                + layer.miles() + " mi of " + (layer.isFromMe() ? "you" : "the map");
    }

    private void buildFilterRow(int total, int wind, int waves, int starred) {
        filterRow.removeAllViews();
        filterRow.addView(tile("All (" + total + ")", filter == BuoyOverlay.SHOW_ALL, 0,
                BuoyOverlay.SHOW_ALL));
        filterRow.addView(tile("Wind (" + wind + ")", filter == BuoyOverlay.SHOW_WIND,
                0, BuoyOverlay.SHOW_WIND));
        filterRow.addView(tile("Seas (" + waves + ")", filter == BuoyOverlay.SHOW_WAVES,
                0, BuoyOverlay.SHOW_WAVES));
        final View fav = tile("\u2605 (" + starred + ")", filter == BuoyOverlay.SHOW_FAVORITES,
                StationPage.STAR_ON, BuoyOverlay.SHOW_FAVORITES);
        ((LinearLayout.LayoutParams) fav.getLayoutParams()).weight = 0.6f;
        filterRow.addView(fav);
    }

    private void buildScopeRow() {
        scopeRow.removeAllViews();
        final boolean me = layer.isFromMe();
        scopeRow.addView(scopeTile(pluginContext.getString(R.string.stations_from_me), me, true));
        scopeRow.addView(scopeTile(pluginContext.getString(R.string.stations_from_map), !me, false));
    }

    private void styleStar(TextView star, Ndbc.Buoy g) {
        final boolean on = layer != null && layer.isFavorite(g);
        star.setText(on ? "★" : "☆");
        star.setTextColor(on ? StationPage.STAR_ON : StationPage.STAR_OFF);
    }

    private void toggleFavorite(Ndbc.Buoy g) {
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

    private static double metersFrom(GeoPoint from, Ndbc.Buoy g) {
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
            final Ndbc.Buoy g = shown.get(i);
            row.findViewById(R.id.state).setBackgroundColor(BuoyOverlay.colorFor(g));
            ((TextView) row.findViewById(R.id.name)).setText(g.label());
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

    /**
     * "12G15 kt NW  ·  3.9 ft @ 13 s  ·  4.1 mi  ·  38 min ago". The reading is the
     * map pill's own text: the row had its own copy, and Morro Bay read "No current
     * reading" in the list under a pill that said "Water 61 °F" (2026-09-26).
     */
    private String detail(Ndbc.Buoy g) {
        final UnitSystem system = host.units();
        final StringBuilder b = new StringBuilder();
        append(b, BuoyOverlay.pillReading(g, system));
        final GeoPoint from = layer == null ? null : layer.originPoint();
        if (from != null)
            append(b, Units.format(Quantity.LENGTH, metersFrom(from, g), system));
        append(b, ago(g.observedAt));
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

    /** Open one buoy's record by id, from a tap on the map. */
    public void showById(String lid) {
        if (layer == null || lid == null)
            return;
        for (Ndbc.Buoy g : layer.buoys())
            if (lid.equals(g.id)) {
                showDetail(g);
                return;
            }
    }

    private void showDetail(final Ndbc.Buoy g) {
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
        title.setText(g.label());
        detailBody.addView(title);
        // Tides and currents from the nearest CO-OPS stations, under the name and
        // above the buoy's own fields: what the water is about to do is read
        // before what the air is doing. Filled in when the answers land.
        tideBlock = new LinearLayout(pluginContext);
        tideBlock.setOrientation(LinearLayout.VERTICAL);
        detailBody.addView(tideBlock);
        tideFor = g.id;
        fillTides(g);
        for (String[] r : BuoyOverlay.describe(g, host.units(), System.currentTimeMillis()))
            detailBody.addView(field(r[0], r[1]));
        // What the water will do next, under what it is doing now.
        marineBlock = new LinearLayout(pluginContext);
        marineBlock.setOrientation(LinearLayout.VERTICAL);
        detailBody.addView(marineBlock);
        fillMarine(g);

        detail.setVisibility(View.VISIBLE);
        list.setVisibility(View.GONE);
        heading.setVisibility(View.GONE);
        filterRow.setVisibility(View.GONE);
        scopeRow.setVisibility(View.GONE);
        status.setVisibility(View.GONE);
    }

    private static final double TIDE_REACH_MI = 40, CURRENT_REACH_MI = 15;

    private void fillTides(final Ndbc.Buoy g) {
        if (tideStations == null || currentStations == null) {
            tideBlock.addView(field("Tides and currents", "Finding the nearest stations\u2026"));
            fetchStationLists(g);
            return;
        }
        final Coops.Station tide = Coops.nearest(tideStations, g.latitude, g.longitude, TIDE_REACH_MI);
        final Coops.Station cur = Coops.nearest(currentStations, g.latitude, g.longitude, CURRENT_REACH_MI);
        if (tide == null && cur == null) {
            tideBlock.addView(field("Tides and currents", "No CO-OPS station within "
                    + (int) TIDE_REACH_MI + " mi"));
            return;
        }
        final EgressPolicy egress = host.egress();
        final java.util.Map<String, String> h = new java.util.HashMap<>();
        if (tide != null) {
            final TextView row = (TextView) ((LinearLayout) field("Tide at " + tide.name + " ("
                    + miles(Coops.milesBetween(g.latitude, g.longitude, tide.latitude, tide.longitude))
                    + ")", "Getting the tide\u2026")).getChildAt(1);
            tideBlock.addView((View) row.getParent());
            cached(Coops.hiloUrl(tide.id, Coops.today()), egress.userAgent(), h, new Http.Callback() {
                @Override
                public void onSuccess(String body) {
                    if (!g.id.equals(tideFor))
                        return;
                    final List<Coops.Tide> t = Coops.parseHilo(body);
                    final StringBuilder b = new StringBuilder();
                    for (Coops.Tide x : t) {
                        if (b.length() > 0)
                            b.append('\n');
                        b.append(x.high ? "High " : "Low  ").append(Coops.clock(x.at)).append(' ')
                                .append(Coops.day(x.at)).append(String.format(java.util.Locale.US,
                                        "  \u00b7  %.1f ft", x.feet));
                    }
                    row.setText(b.length() == 0 ? "No predictions" : b.toString());
                    // Asked of every station: the type does not say who measures.
                    cached(Coops.waterLevelUrl(tide.id), egress.userAgent(), h,
                                new Http.Callback() {
                                    @Override
                                    public void onSuccess(String wl) {
                                        final Coops.Tide now = Coops.parseWaterLevel(wl);
                                        if (now != null && g.id.equals(tideFor))
                                            row.setText(String.format(java.util.Locale.US,
                                                    "Now %.1f ft above MLLW at %s\n", now.feet,
                                                    Coops.clock(now.at)) + row.getText());
                                    }

                                    @Override
                                    public void onFailure(String error) {
                                    }
                                });
                }

                @Override
                public void onFailure(String error) {
                    if (g.id.equals(tideFor))
                        row.setText("Could not get the tide: " + error);
                }
            });
        }
        if (cur == null) {
            tideBlock.addView(field("Current", "No current station within " + (int) CURRENT_REACH_MI
                    + " mi"));
        }
        if (cur != null) {
            final TextView row = (TextView) ((LinearLayout) field("Current at " + cur.name
                    + (Double.isNaN(cur.depthFt) ? "" : String.format(java.util.Locale.US, ", %.0f ft deep", cur.depthFt))
                    + " (" + miles(Coops.milesBetween(g.latitude, g.longitude, cur.latitude, cur.longitude))
                    + ")", "Getting the current\u2026")).getChildAt(1);
            tideBlock.addView((View) row.getParent());
            cached(Coops.currentsUrl(cur.id, cur.bin), egress.userAgent(), h, new Http.Callback() {
                @Override
                public void onSuccess(String body) {
                    if (!g.id.equals(tideFor))
                        return;
                    final List<Coops.Current> c = Coops.parseCurrents(body);
                    final StringBuilder b = new StringBuilder();
                    for (Coops.Current x : c) {
                        if (b.length() > 0)
                            b.append('\n');
                        final String what = x.type.equals("slack") ? "Slack"
                                : x.type.equals("ebb") ? "Ebb  " : "Flood";
                        b.append(what).append(' ').append(Coops.clock(x.at));
                        if (!x.type.equals("slack") && !Double.isNaN(x.knots))
                            b.append(String.format(java.util.Locale.US, "  \u00b7  %.1f kt toward %d\u00b0",
                                    Math.abs(x.knots), Math.round(x.type.equals("ebb") ? x.ebbDir : x.floodDir)));
                    }
                    row.setText(b.length() == 0 ? "No predictions" : b.toString());
                }

                @Override
                public void onFailure(String error) {
                    if (g.id.equals(tideFor))
                        row.setText("Could not get the current: " + error);
                }
            });
        }
    }

    /** "0.4 mi" for a pier across the harbor, "18 mi" up the coast; never "0 mi". */
    private static String miles(double d) {
        return d < 10 ? String.format(java.util.Locale.US, "%.1f mi", d)
                : Math.round(d) + " mi";
    }

    /**
     * The coastal waters forecast for the buoy's marine zone. {@code /points} names
     * the zone and the office, the office's newest CWF is fetched and the zone's
     * section cut out of it -- three requests, all through {@link #answers}, so
     * the record rebuilt on the next redraw costs nothing. An offshore zone is
     * forecast by an ocean center in a product this does not read yet, and the
     * row says so instead of showing the wrong office's text.
     */
    private void fillMarine(final Ndbc.Buoy g) {
        final EgressPolicy egress = host.egress();
        final java.util.Map<String, String> h = new java.util.HashMap<>();
        final TextView row = (TextView) ((LinearLayout) field("Coastal waters forecast",
                "Getting the forecast\u2026")).getChildAt(1);
        marineBlock.addView((View) row.getParent());
        cached(Cwf.pointUrl(g.latitude, g.longitude), egress.userAgent(), h, new Http.Callback() {
            @Override
            public void onSuccess(String body) {
                if (!g.id.equals(tideFor))
                    return;
                final Cwf.Zone z = Cwf.parsePoint(body);
                if (z == null) {
                    row.setText("Not in a coastal waters forecast zone");
                    return;
                }
                if (!"CWF".equals(z.productType())) {
                    row.setText("Offshore zone " + z.id + ": the offshore waters forecast is not read yet");
                    return;
                }
                cached(Cwf.latestUrl("CWF", z.cwa), egress.userAgent(), h, new Http.Callback() {
                    @Override
                    public void onSuccess(String list) {
                        if (!g.id.equals(tideFor))
                            return;
                        final String id = Cwf.parseLatestId(list);
                        if (id == null) {
                            row.setText("No coastal waters forecast from " + z.cwa);
                            return;
                        }
                        cached(Cwf.productUrl(id), egress.userAgent(), h, new Http.Callback() {
                            @Override
                            public void onSuccess(String product) {
                                if (!g.id.equals(tideFor))
                                    return;
                                final String s = Cwf.section(Cwf.parseText(product), z.id);
                                row.setText(s == null ? "Zone " + z.id + " is not in " + z.cwa
                                        + "'s forecast" : marineText(s));
                            }

                            @Override
                            public void onFailure(String error) {
                                forecastFailed(row, g, error);
                            }
                        });
                    }

                    @Override
                    public void onFailure(String error) {
                        forecastFailed(row, g, error);
                    }
                });
            }

            @Override
            public void onFailure(String error) {
                forecastFailed(row, g, error);
            }
        });
    }

    private void forecastFailed(TextView row, Ndbc.Buoy g, String error) {
        if (g.id.equals(tideFor))
            row.setText("Could not get the forecast: " + error);
    }

    /** The zone, when it was issued, then each period on its own line. */
    static String marineText(String section) {
        final StringBuilder b = new StringBuilder(Cwf.name(section));
        final String issued = Cwf.issued(section);
        if (!issued.isEmpty())
            b.append('\n').append("Issued ").append(issued);
        for (Cwf.Period p : Cwf.periods(section))
            b.append('\n').append(p.name).append(": ").append(p.text);
        return b.toString();
    }

    /** {@link Http#get} through {@link #answers}. */
    private static void cached(final String url, String userAgent, java.util.Map<String, String> h,
            final Http.Callback cb) {
        synchronized (answers) {
            final Object[] hit = answers.get(url);
            if (hit != null && System.currentTimeMillis() - (Long) hit[1] < ANSWER_MS) {
                if (hit[0] != null)
                    cb.onSuccess((String) hit[0]);
                else
                    cb.onFailure((String) hit[2]);
                return;
            }
        }
        Http.get(url, userAgent, h, new Http.Callback() {
            @Override
            public void onSuccess(String body) {
                synchronized (answers) {
                    answers.put(url, new Object[] { body, System.currentTimeMillis(), null });
                }
                cb.onSuccess(body);
            }

            @Override
            public void onFailure(String error) {
                synchronized (answers) {
                    answers.put(url, new Object[] { null, System.currentTimeMillis(), error });
                }
                cb.onFailure(error);
            }
        });
    }

    /** Two megabytes each, once; the record is filled when they land. */
    private void fetchStationLists(final Ndbc.Buoy g) {
        // Not a latch. The first attempt on 2026-09-26 went out on a dead Wi-Fi
        // link and never came back; a boolean here kept every reopen on "Finding
        // the nearest stations..." until the plugin was reloaded.
        final long now = System.currentTimeMillis();
        if (now - listsRequestedAt < LIST_RETRY_MS)
            return;
        listsRequestedAt = now;
        final EgressPolicy egress = host.egress();
        final java.util.Map<String, String> h = new java.util.HashMap<>();
        Http.getLarge(Coops.TIDE_STATIONS_URL, egress.userAgent(), h, new Http.Callback() {
            @Override
            public void onSuccess(String body) {
                tideStations = Coops.parseStations(body);
                Http.getLarge(Coops.CURRENT_STATIONS_URL, egress.userAgent(), h, new Http.Callback() {
                    @Override
                    public void onSuccess(String body2) {
                        currentStations = Coops.parseStations(body2);
                        if (showing != null && showing.id.equals(tideFor))
                            showDetail(showing);
                    }

                    @Override
                    public void onFailure(String error) {
                        currentStations = new ArrayList<>();
                        listsRequestedAt = 0L;
                        if (showing != null && showing.id.equals(tideFor))
                            showDetail(showing);
                    }
                });
            }

            @Override
            public void onFailure(String error) {
                listsRequestedAt = 0L;
                if (tideBlock != null && showing != null && showing.id.equals(g.id)) {
                    tideBlock.removeAllViews();
                    tideBlock.addView(field("Tides and currents", "Could not reach CO-OPS: " + error));
                }
            }
        });
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

    private void goTo(Ndbc.Buoy g) {
        if (Double.isNaN(g.latitude))
            return;
        try {
            mapView.getMapController().panTo(new GeoPoint(g.latitude, g.longitude), true);
        } catch (RuntimeException e) {
            // A pan that will not happen is not worth a crash.
        }
    }
}

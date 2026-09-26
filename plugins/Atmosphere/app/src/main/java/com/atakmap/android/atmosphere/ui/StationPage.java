
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
import com.atakmap.android.atmosphere.data.RedFlag;
import com.atakmap.android.atmosphere.data.Raws;
import com.atakmap.android.atmosphere.overlay.StationOverlay;
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
 * The stations as a list, nearest first.
 *
 * <p>Everything here is read from the layer rather than fetched again: the same
 * stations, the same state rule, the same origin. A list and a map built from two
 * answers disagree, and the operator is the one who finds it -- which is exactly what
 * happened with the spot forecasts, three times in one day.
 *
 * <p>The filters are the three states the symbols are colored by, each carrying its
 * count, so a filter says what it will cost before it is used.
 *
 * <p>It reorders when the map moves. Scoped to the map center that is the whole point:
 * pan toward a fire and the nearest stations to it come to the top.
 */
public final class StationPage {

    /** What the page needs from whoever owns it. */
    public interface Host {
        UnitSystem units();
    }

    private static final int ALL = -1;

    private final Context pluginContext;
    private final MapView mapView;
    private final Host host;
    private final View root;
    private final LinearLayout filterRow, scopeRow;
    private final TextView status;
    private final ListView list;
    private final Adapter adapter = new Adapter();

    private StationOverlay layer;
    private int filter = ALL;
    private List<Raws.Station> shown = new ArrayList<>();

    public StationPage(Context pluginContext, MapView mapView, Host host) {
        this.pluginContext = pluginContext;
        this.mapView = mapView;
        this.host = host;
        root = PluginLayoutInflater.inflate(pluginContext, R.layout.page_stations, null);
        filterRow = root.findViewById(R.id.stations_filter_row);
        scopeRow = root.findViewById(R.id.stations_scope_row);
        status = root.findViewById(R.id.stations_page_status);
        list = root.findViewById(R.id.stations_list);
        list.setAdapter(adapter);
    }

    public View view() {
        return root;
    }

    public void setLayer(StationOverlay overlay) {
        layer = overlay;
        refresh();
    }

    /** The stations changed, or the map moved: rebuild what is shown and reorder it. */
    public void refresh() {
        if (layer == null)
            return;
        final List<Raws.Station> all = layer.stations();
        final long now = System.currentTimeMillis();
        final GeoPoint from = layer.originPoint();

        final int[] counts = new int[3];
        final List<Raws.Station> keep = new ArrayList<>();
        for (Raws.Station s : all) {
            // The same two exclusions the map makes: a station with nothing to say is
            // not a reading, and one that stopped reporting days ago is not weather.
            if (s.silent() || s.stale(now))
                continue;
            final int state = StationOverlay.stateOf(s);
            counts[state]++;
            if (filter == ALL || filter == state)
                keep.add(s);
        }
        if (from != null)
            Collections.sort(keep, new Comparator<Raws.Station>() {
                @Override
                public int compare(Raws.Station a, Raws.Station b) {
                    return Double.compare(metersFrom(from, a), metersFrom(from, b));
                }
            });
        shown = keep;

        buildFilterRow(counts);
        buildScopeRow();
        status.setText(summary(counts, from));
        adapter.notifyDataSetChanged();
    }

    private String summary(int[] counts, GeoPoint from) {
        if (layer == null || !layer.isOn())
            return "Turn the weather stations layer on to see them here.";
        if (from == null)
            return layer.isFromMe() ? "No position yet — switch to the map center."
                    : "The map has no center yet.";
        final int total = counts[0] + counts[1] + counts[2];
        if (total == 0)
            return "No stations reporting within " + layer.miles() + " mi.";
        return shown.size() + " of " + total + " stations, nearest first, within "
                + layer.miles() + " mi of " + (layer.isFromMe() ? "you" : "the map");
    }

    /** All, then the two that matter, each with what it costs. */
    private void buildFilterRow(int[] counts) {
        filterRow.removeAllViews();
        final int total = counts[0] + counts[1] + counts[2];
        filterRow.addView(tile("All (" + total + ")", filter == ALL, 0, ALL));
        filterRow.addView(tile("Red Flag (" + counts[RedFlag.CRITICAL] + ")",
                filter == RedFlag.CRITICAL, StationOverlay.CRITICAL, RedFlag.CRITICAL));
        filterRow.addView(tile("Flirting (" + counts[RedFlag.NEAR] + ")",
                filter == RedFlag.NEAR, StationOverlay.NEAR, RedFlag.NEAR));
    }

    /** Where "nearest" is measured from. The layer owns it; this only switches it. */
    private void buildScopeRow() {
        scopeRow.removeAllViews();
        final boolean me = layer.isFromMe();
        scopeRow.addView(scopeTile("My position", me, true));
        scopeRow.addView(scopeTile("Map center", !me, false));
    }

    private View tile(String label, boolean chosen, final int color, final int value) {
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

    /**
     * One control: green when it is the choice in force, the Traffic convention, and
     * in the state's own color when it names a state -- so the filter and the symbols
     * it selects are the same three colors.
     */
    private Button chip(String label, boolean chosen, int color) {
        final Button b = (Button) LayoutInflater.from(pluginContext)
                .inflate(R.layout.trend_chip, filterRow, false);
        b.setText(label);
        b.setTextSize(13);
        b.setTextColor(chosen
                ? (color != 0 ? color : pluginContext.getResources()
                        .getColor(R.color.state_on))
                : 0xFFFFFFFF);
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = dp(4);
        b.setLayoutParams(lp);
        return b;
    }

    private static double metersFrom(GeoPoint from, Raws.Station s) {
        if (from == null || Double.isNaN(s.latitude))
            return Double.MAX_VALUE;
        return GeoCalculations.distanceTo(from, new GeoPoint(s.latitude, s.longitude));
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
            final Raws.Station s = shown.get(i);
            final int state = StationOverlay.stateOf(s);
            row.findViewById(R.id.state).setBackgroundColor(colorOf(state));
            ((TextView) row.findViewById(R.id.name)).setText(s.name);
            ((TextView) row.findViewById(R.id.detail)).setText(detail(s));
            final Button go = row.findViewById(R.id.goto_btn);
            go.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    goTo(s);
                }
            });
            return row;
        }
    }

    private static int colorOf(int state) {
        if (state == RedFlag.CRITICAL)
            return StationOverlay.CRITICAL;
        return state == RedFlag.NEAR ? StationOverlay.NEAR : StationOverlay.NORMAL;
    }

    /** "17G30 mph from 250 (WSW) · 42% · 12 mi · 35 minutes ago" */
    private String detail(Raws.Station s) {
        final UnitSystem system = host.units();
        final StringBuilder b = new StringBuilder();
        if (!Double.isNaN(s.windMph)) {
            b.append(Units.format(Quantity.SPEED, s.windMph * 0.44704, system));
            if (!Double.isNaN(s.gustMph) && Math.round(s.gustMph) > Math.round(s.windMph))
                b.append(" G ").append(Math.round(Units.toDisplay(Quantity.SPEED,
                        s.gustMph * 0.44704, system)));
            if (!Double.isNaN(s.windFromDeg))
                b.append(' ').append(Units.degreesToCompass(s.windFromDeg));
        }
        if (!Double.isNaN(s.relativeHumidity))
            append(b, Math.round(s.relativeHumidity) + "%");
        final GeoPoint from = layer == null ? null : layer.originPoint();
        if (from != null)
            append(b, Units.format(Quantity.LENGTH, metersFrom(from, s), system));
        append(b, ago(s));
        return b.toString();
    }

    private static void append(StringBuilder b, String s) {
        if (s == null || s.isEmpty())
            return;
        if (b.length() > 0)
            b.append("  ·  ");
        b.append(s);
    }

    private static String ago(Raws.Station s) {
        final double hours = s.ageHours(System.currentTimeMillis());
        if (hours == Double.MAX_VALUE)
            return "never reported";
        if (hours < 1.5)
            return Math.max(1, Math.round(hours * 60)) + " min ago";
        return Math.round(hours) + " h ago";
    }

    /** Put the station in the middle of the map, without changing the zoom. */
    private void goTo(Raws.Station s) {
        if (Double.isNaN(s.latitude))
            return;
        try {
            mapView.getMapController().panTo(
                    new GeoPoint(s.latitude, s.longitude), true);
        } catch (RuntimeException e) {
            // A pan that will not happen is not worth a crash.
        }
    }
}

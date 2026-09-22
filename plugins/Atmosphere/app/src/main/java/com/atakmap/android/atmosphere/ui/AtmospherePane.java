package com.atakmap.android.atmosphere.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.atakmap.android.atmosphere.compat.MapCompat;
import com.atakmap.android.atmosphere.data.ParamSelection;
import com.atakmap.android.atmosphere.data.WeatherClient;
import com.atakmap.android.atmosphere.model.Reading;
import com.atakmap.android.atmosphere.model.SeriesEntry;
import com.atakmap.android.atmosphere.model.Snapshot;
import com.atakmap.android.atmosphere.net.EgressPolicy;
import com.atakmap.android.atmosphere.plugin.R;
import com.atakmap.android.atmosphere.source.SourceRegistry;
import com.atakmap.android.atmosphere.source.WxParam;
import com.atakmap.android.atmosphere.source.WxSourceDef;
import com.atakmap.android.atmosphere.units.Quantity;
import com.atakmap.android.atmosphere.units.UnitSystem;
import com.atakmap.android.atmosphere.units.Units;
import com.atakmap.coremap.maps.coords.GeoPoint;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
    private static final String PREF_USE_SELF = "weather.position.useSelf";

    private final View root;
    private final Context pluginContext;
    private final SourceRegistry registry;
    private final EgressPolicy egress;
    private final WeatherClient client;

    private final Button sourceButton;
    private final Button refreshButton;
    private final Button centerButton;
    private final Button selfButton;
    private final Button unitsButton;
    private final TextView positionText;
    private final TextView statusText;
    private final TextView currentHeading;
    private final LinearLayout currentContainer;
    private final TextView seriesHeading;
    private final LinearLayout seriesLegend;
    private final LinearLayout seriesContainer;
    private final TextView daysHeading;
    private final View daysStrip;
    private final LinearLayout daysLegend;
    private final LinearLayout daysContainer;
    private final TextView attributionText;

    private final List<WxSourceDef> sources;

    private WxSourceDef selected;
    private UnitSystem units;
    private boolean useSelf;
    private Snapshot snapshot;

    public AtmospherePane(View root, Context pluginContext, SourceRegistry registry,
            EgressPolicy egress, WeatherClient client) {

        this.root = root;
        this.pluginContext = pluginContext;
        this.registry = registry;
        this.egress = egress;
        this.client = client;
        this.sources = registry.sources();

        sourceButton = root.findViewById(R.id.source_button);
        refreshButton = root.findViewById(R.id.refresh_button);
        centerButton = root.findViewById(R.id.center_button);
        selfButton = root.findViewById(R.id.self_button);
        unitsButton = root.findViewById(R.id.units_button);
        positionText = root.findViewById(R.id.position_text);
        statusText = root.findViewById(R.id.status_text);
        currentHeading = root.findViewById(R.id.current_heading);
        currentContainer = root.findViewById(R.id.current_container);
        seriesHeading = root.findViewById(R.id.series_heading);
        seriesLegend = root.findViewById(R.id.series_legend);
        seriesContainer = root.findViewById(R.id.series_container);
        daysHeading = root.findViewById(R.id.days_heading);
        daysStrip = root.findViewById(R.id.days_strip);
        daysLegend = root.findViewById(R.id.days_legend);
        daysContainer = root.findViewById(R.id.days_container);
        attributionText = root.findViewById(R.id.attribution_text);

        final SharedPreferences prefs = MapCompat.prefs();
        units = UnitSystem.fromName(prefs == null ? null
                : prefs.getString(PREF_UNITS, null), UnitSystem.METRIC);
        useSelf = prefs != null && prefs.getBoolean(PREF_USE_SELF, false);

        wireSourceButton(prefs);
        wireButtons();
        updateUnitsButton();
        updatePositionMode();
        showSourceProblemsIfAny();
    }

    /** Called every time the pane is shown, so a stale pane never lingers. */
    public void onShown() {
        refresh(false);
    }

    /**
     * The source picker is a button that opens a single-choice dialog on ATAK's own
     * context. Never a Spinner: its dropdown is a Dialog built from the context that
     * inflated the view, and on the plugin context that is a BadTokenException that
     * kills ATAK (plugin UI standard, CLAUDE.md).
     */
    private void wireSourceButton(SharedPreferences prefs) {
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
        updateSourceButton();

        sourceButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (sources.isEmpty())
                    return;
                final Context ctx = MapCompat.atakContext();
                if (ctx == null)
                    return;
                final String[] names = sourceNames();
                final int checked = selected == null ? -1 : sources.indexOf(selected);
                new AlertDialog.Builder(ctx)
                        .setTitle(pluginContext.getString(R.string.source_title))
                        .setSingleChoiceItems(names, checked,
                                new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d, int which) {
                                        d.dismiss();
                                        if (which < 0 || which >= sources.size())
                                            return;
                                        selected = sources.get(which);
                                        final SharedPreferences p = MapCompat.prefs();
                                        if (p != null)
                                            p.edit().putString(PREF_SOURCE, selected.id).apply();
                                        updateSourceButton();
                                        snapshot = null;
                                        refresh(false);
                                    }
                                })
                        .setNegativeButton(pluginContext.getString(R.string.close), null)
                        .show();
            }
        });
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

    private void updateSourceButton() {
        if (selected == null) {
            sourceButton.setText(R.string.no_sources);
            sourceButton.setEnabled(false);
        } else {
            sourceButton.setText(sourceName(selected));
            sourceButton.setEnabled(true);
        }
    }

    private void wireButtons() {
        refreshButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refresh(true);
            }
        });

        centerButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setUseSelf(false);
            }
        });

        selfButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setUseSelf(true);
            }
        });

        unitsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final UnitSystem[] all = UnitSystem.values();
                units = all[(units.ordinal() + 1) % all.length];
                final SharedPreferences p = MapCompat.prefs();
                if (p != null)
                    p.edit().putString(PREF_UNITS, units.name()).apply();
                updateUnitsButton();
                // A unit change is a display change: re-render, never re-fetch.
                render();
            }
        });

        root.findViewById(R.id.variables_button).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showVariablesDialog();
                    }
                });

        root.findViewById(R.id.sources_button).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showSourcesDialog();
                    }
                });

        root.findViewById(R.id.privacy_button).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showPrivacyDialog();
                    }
                });
    }

    private void setUseSelf(boolean value) {
        useSelf = value;
        final SharedPreferences p = MapCompat.prefs();
        if (p != null)
            p.edit().putBoolean(PREF_USE_SELF, value).apply();
        updatePositionMode();
        snapshot = null;
        refresh(false);
    }

    private void updatePositionMode() {
        centerButton.setEnabled(useSelf);
        selfButton.setEnabled(!useSelf);
    }

    private void updateUnitsButton() {
        unitsButton.setText(units.label());
    }

    private GeoPoint point() {
        return useSelf ? MapCompat.selfPoint() : MapCompat.mapCenter();
    }

    private void refresh(boolean force) {
        if (selected == null) {
            statusText.setText(R.string.no_sources);
            return;
        }

        final GeoPoint p = point();
        if (p == null) {
            positionText.setText(R.string.no_position);
            statusText.setText(useSelf
                    ? "No self position yet — no GPS fix"
                    : "No map centre yet");
            return;
        }

        positionText.setText("Sending " + egress.latitude(p) + ", " + egress.longitude(p)
                + "  (rounded to ~" + EgressPolicy.approximateMetres(
                        egress.positionDecimals()) + " m)");
        statusText.setText("Fetching from " + selected.displayName + "…");

        client.fetch(selected, p, force, new WeatherClient.Listener() {
            @Override
            public void onSnapshot(Snapshot result, boolean fromCache) {
                snapshot = result;
                final long age = result.ageMillis(System.currentTimeMillis());
                statusText.setTextColor(Color.parseColor("#dfb228"));
                statusText.setText(result.sourceName + " — "
                        + Snapshot.describeAge(age) + (fromCache ? " (cached)" : ""));
                render();
            }

            @Override
            public void onError(String message, Snapshot stale) {
                statusText.setTextColor(Color.parseColor("#ff8a65"));
                if (stale != null) {
                    snapshot = stale;
                    final long age = stale.ageMillis(System.currentTimeMillis());
                    statusText.setText(message + " — showing cached, "
                            + Snapshot.describeAge(age));
                    render();
                } else {
                    statusText.setText(message);
                    currentContainer.removeAllViews();
                    seriesContainer.removeAllViews();
                }
            }
        });
    }

    private void render() {
        currentContainer.removeAllViews();
        seriesLegend.removeAllViews();
        seriesContainer.removeAllViews();
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

        currentHeading.setText(fromSeries ? "Now (first forecast step)"
                : pluginContext.getString(R.string.heading_now));

        for (Reading r : now)
            currentContainer.addView(readingRow(r.label, r.format(units)));

        renderHours(wanted);
        renderDays(wanted);
        attributionText.setText(snapshot.attribution == null ? "" : snapshot.attribution);
    }

    /** Hours are shown as columns; more than this is the days strip's job. */
    private static final int HOURS_SHOWN = 48;
    private static final int COLUMN_DP = 92;
    private static final int HEADER_DP = 36;
    private static final int ROW_DP = 24;

    /**
     * One column per forecast hour, scrolled sideways, with the legend pinned on the
     * left (Jean's daily strip and WxReport's hourly row, as the operator asked).
     * Numbers, not a chart: an engine boss reads a number on a vehicle mount.
     */
    private void renderHours(Set<String> wanted) {
        final List<Reading> order = keyOrder(wanted);
        final boolean any = !order.isEmpty();
        seriesHeading.setVisibility(any ? View.VISIBLE : View.GONE);
        seriesLegend.setVisibility(any ? View.VISIBLE : View.GONE);
        if (!any)
            return;
        final List<String> labels = new ArrayList<>();
        for (Reading r : order)
            labels.add(shortLabel(r));
        seriesLegend.addView(column("", labels, true));

        final SimpleDateFormat day = new SimpleDateFormat("EEE", Locale.US);
        final SimpleDateFormat hour = new SimpleDateFormat("HH:mm", Locale.US);
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
                header = day.format(d) + "\n" + hour.format(d);
            } else {
                header = String.valueOf(entry.timeRaw);
            }
            seriesContainer.addView(column(header, values, false));
        }
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
    private enum Kind { TEMP, DEW, FEELS, RH, WIND, GUST, DIR, POP, PRECIP, OTHER }

    private static Kind kind(Reading r) {
        final String k = (r.key + " " + r.label).toLowerCase(Locale.US);
        switch (r.quantity) {
            case TEMPERATURE:
                if (k.contains("dew")) return Kind.DEW;
                if (k.contains("feel") || k.contains("apparent")) return Kind.FEELS;
                return Kind.TEMP;
            case PERCENT:
                if (k.contains("humid")) return Kind.RH;
                if (k.contains("precip")) return Kind.POP;
                return Kind.OTHER;
            case SPEED:
                return k.contains("gust") ? Kind.GUST : Kind.WIND;
            case ANGLE:
                return Kind.DIR;
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
            case WIND: return "Wind";
            case GUST: return "Gust";
            case DIR: return "Dir";
            case POP: return "Precip %";
            case PRECIP: return "Precip";
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
                case WIND: windHi = Double.isNaN(windHi) ? r.value : Math.max(windHi, r.value); break;
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
            t.setTextSize(14);
            t.setSingleLine(true);
            t.setHeight(dp(ROW_DP));
            t.setGravity((legend ? Gravity.START : Gravity.CENTER_HORIZONTAL) | Gravity.CENTER_VERTICAL);
            if (legend)
                t.setAlpha(0.7f);
            else
                t.setTypeface(Typeface.MONOSPACE);
            col.addView(t);
        }
        return col;
    }

    private int dp(int v) {
        return Math.round(v * pluginContext.getResources().getDisplayMetrics().density);
    }

    private View readingRow(String label, String value) {
        final LinearLayout row = new LinearLayout(pluginContext);
        row.setOrientation(LinearLayout.HORIZONTAL);

        final TextView left = new TextView(pluginContext);
        left.setText(label);
        left.setTextSize(14);
        left.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        final TextView right = new TextView(pluginContext);
        right.setText(value);
        right.setTextSize(14);
        right.setTypeface(Typeface.MONOSPACE);
        right.setGravity(Gravity.END);
        right.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f));

        row.addView(left);
        row.addView(right);
        return row;
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
                sb.append("\nneeds an API key — not supported in this build");
            labels[i] = sb.toString();
            enabled[i] = egress.isEnabled(def);
        }

        new AlertDialog.Builder(ctx)
                .setTitle(R.string.sources_title)
                .setMultiChoiceItems(labels, enabled,
                        new DialogInterface.OnMultiChoiceClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which,
                                    boolean isChecked) {
                                egress.setEnabled(sources.get(which), isChecked);
                            }
                        })
                .setPositiveButton(R.string.close, new DialogInterface.OnClickListener() {
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
                .setTitle(R.string.variables_title)
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
                .setPositiveButton(R.string.close, new DialogInterface.OnClickListener() {
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

    private void showPrivacyDialog() {
        final Context ctx = MapCompat.atakContext();
        if (ctx == null)
            return;

        final int[] choices = {0, 1, 2, 3, 4};
        final String[] labels = new String[choices.length];
        for (int i = 0; i < choices.length; i++) {
            labels[i] = choices[i] + " decimals — about "
                    + EgressPolicy.approximateMetres(choices[i]) + " m";
        }

        int current = 0;
        for (int i = 0; i < choices.length; i++) {
            if (choices[i] == egress.positionDecimals()) {
                current = i;
                break;
            }
        }

        new AlertDialog.Builder(ctx)
                .setTitle(R.string.privacy_title)
                .setMessage("A forecast query has to say roughly where you are. This is "
                        + "how precisely your position is sent to the provider — nothing "
                        + "else about you goes with it.")
                .setSingleChoiceItems(labels, current,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                egress.setPositionDecimals(choices[which]);
                            }
                        })
                .setPositiveButton(R.string.close, new DialogInterface.OnClickListener() {
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
                .setTitle(R.string.source_problems)
                .setMessage(sb.toString().trim())
                .setPositiveButton(R.string.close, null)
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

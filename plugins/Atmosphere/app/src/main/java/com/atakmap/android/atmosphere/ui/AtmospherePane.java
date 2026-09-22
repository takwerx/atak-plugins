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
import com.atakmap.android.atmosphere.units.UnitSystem;
import com.atakmap.coremap.maps.coords.GeoPoint;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
    private final LinearLayout seriesContainer;
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
        seriesContainer = root.findViewById(R.id.series_container);
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
        seriesContainer.removeAllViews();

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

        seriesHeading.setVisibility(snapshot.series.isEmpty() ? View.GONE : View.VISIBLE);
        final SimpleDateFormat fmt = new SimpleDateFormat("EEE HH:mm", Locale.US);
        for (SeriesEntry entry : snapshot.series) {
            final StringBuilder values = new StringBuilder();
            for (Reading r : entry.readings) {
                if (!wanted.contains(r.key) || !r.valid())
                    continue;
                if (values.length() > 0)
                    values.append("   ");
                values.append(r.format(units));
            }
            if (values.length() == 0)
                continue;
            final String when = entry.timeMillis > 0
                    ? fmt.format(new Date(entry.timeMillis))
                    : String.valueOf(entry.timeRaw);
            seriesContainer.addView(readingRow(when, values.toString()));
        }

        attributionText.setText(snapshot.attribution == null ? "" : snapshot.attribution);
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

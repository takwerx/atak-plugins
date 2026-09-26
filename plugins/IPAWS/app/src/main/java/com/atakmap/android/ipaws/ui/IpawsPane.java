package com.atakmap.android.ipaws.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.preference.PreferenceManager;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.ipaws.AlertManager;
import com.atakmap.android.ipaws.AlertStyles;
import com.atakmap.android.ipaws.data.Alert;
import com.atakmap.android.ipaws.data.Areas;
import com.atakmap.android.ipaws.data.Counties;
import com.atakmap.android.ipaws.data.Events;
import com.atakmap.android.ipaws.data.Filter;
import com.atakmap.android.ipaws.data.Regions;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.ipaws.plugin.R;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The pane: where the operator says which states, and which counties inside them.
 *
 * <p>Cam Depot's controls, because a user moving between takwerx plugins should not
 * be learning a new dialect each time -- a {@code TakwerxButton} showing the current
 * value, opening an {@code AlertDialog} on the <b>MapView</b> context. Never a
 * Spinner: its dropdown is a Dialog built from the plugin context, which has no
 * window token, and it takes ATAK down outright.
 *
 * <p>Where it differs from Cam Depot is that states are multi-select here. Cam Depot
 * has one state at a time; this filter is "a state or a list of states, or counties
 * within them", so counties are reached through whichever selected state you want to
 * narrow -- a flat list of 3269 counties would be no use to anybody.
 */
public class IpawsPane {

    private final MapView mapView;
    private final Context pluginContext;
    private final AlertManager manager;
    private final Counties counties;
    private final View root;

    private final ListView list;
    private final AlertRows rows;
    private final AlertDetails details;
    private final TextView status;
    private final Button regionsButton;
    private final Button statesButton;
    private final Button countiesButton;
    private final Button categoriesButton;
    private final Button eventsButton;
    private final Button severityButton;
    private final Button intervalButton;
    private final Button notifyButton;
    private final Button notifySeverityButton;
    private final Button notifyWhereButton;
    private final Button notifyUpdatesButton;
    private final Button allButton;
    /** The settings page, shown in place of the alert list. */
    private final View settingsPage;
    private final Button gateButton;
    private final TextView scopeLabel;
    private final android.widget.SeekBar scopeSeek;
    private final Button scopeFromButton;
    /** True while a finger is on the slider, so a poll's refresh does not yank it back. */
    private boolean scopeDragging;

    /** Radius presets in the large unit, after Everything and What is in view. */
    private static final int[] SCOPE_PRESETS = { 2, 5, 10, 25, 50 };
    /** The radius a "Measuring from" tap gives when there was none, as Feature Layer's does. */
    private static final int DEFAULT_SCOPE_BIG = 25;

    /** Feature Layer's presets, in the operator's large unit, as the scale bar reads them. */
    private static final double[] GATE_BIG = { 0.25, 1, 5, 15, 50 };
    private final LinearLayout mapKey;
    private final Fold gateFold;
    private final Fold scopeFold;
    private final Fold keyFold;
    private final Fold whereFold;
    private final Fold typesFold;
    private final Fold notifyFold;
    /** What the key last drew, so a poll that changed nothing does not rebuild it. */
    private List<String> keyShown = new ArrayList<>();
    private final SharedPreferences prefs;

    /**
     * Atmosphere's drop-down: a row that names the section and what it is set to, and
     * an arrow that opens its controls. Open or closed is remembered per section, and
     * everything starts closed, so the pane opens as a screen of settings at a glance.
     * A section whose row is a switch (Notify) passes no title button: its arrow alone
     * opens it, and shows only while the switch is on, as Atmosphere's layers do.
     */
    private final class Fold {
        final View row;
        final Button head;
        final android.widget.ImageButton chevron;
        final View body;
        final String pref;
        boolean open;

        Fold(View header, int rowId, int headId, int chevronId, int bodyId, String pref,
                boolean headOpens) {
            row = header.findViewById(rowId);
            head = header.findViewById(headId);
            chevron = header.findViewById(chevronId);
            body = header.findViewById(bodyId);
            this.pref = pref;
            open = prefs.getBoolean(pref, false);
            final View.OnClickListener flip = new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    open = !open;
                    prefs.edit().putBoolean(Fold.this.pref, open).apply();
                    show(true);
                }
            };
            chevron.setOnClickListener(flip);
            if (headOpens)
                head.setOnClickListener(flip);
            show(true);
        }

        /** @param available false hides the arrow and the controls, as a switched-off layer does */
        void show(boolean available) {
            chevron.setVisibility(available ? View.VISIBLE : View.GONE);
            chevron.setRotation(open ? 180f : 0f);
            body.setVisibility(available && open ? View.VISIBLE : View.GONE);
        }
    }

    public IpawsPane(MapView mapView, Context pluginContext, AlertManager manager,
            Counties counties, AlertDetails details) {
        this.mapView = mapView;
        this.pluginContext = pluginContext;
        this.manager = manager;
        this.counties = counties;
        this.details = details;
        this.root = PluginLayoutInflater.inflate(pluginContext, R.layout.main_layout, null);

        status = root.findViewById(R.id.status);
        list = root.findViewById(R.id.alerts);
        // The controls are the list's header, so the whole pane is one scroller. A
        // ListView inside a ScrollView would render one row tall.
        final View header = PluginLayoutInflater.inflate(pluginContext,
                R.layout.controls_header, null);
        list.addHeaderView(header, null, false);
        // Everything set once and left lives on the settings page, which takes the
        // list's place while it is open.
        final View settings = PluginLayoutInflater.inflate(pluginContext,
                R.layout.settings_controls, null);
        ((android.view.ViewGroup) root.findViewById(R.id.settings_container)).addView(settings);
        settingsPage = root.findViewById(R.id.settings_page);
        header.findViewById(R.id.btn_settings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSettings(true);
            }
        });
        root.findViewById(R.id.btn_settings_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSettings(false);
            }
        });
        rows = new AlertRows(pluginContext);
        list.setAdapter(rows);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View v, int position, long id) {
                // Header rows count in this position, so take them back off again.
                final Alert a = rows.onTap(position - list.getHeaderViewsCount());
                // A group with more than one alert opens instead of navigating, and
                // says so by returning nothing.
                if (a != null)
                    IpawsPane.this.details.show(a);
            }
        });

        prefs = PreferenceManager.getDefaultSharedPreferences(mapView.getContext());
        allButton = header.findViewById(R.id.btn_all);
        allButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                manager.setMapOn(!manager.getFilter().mapOn);
                refresh();
            }
        });
        scopeLabel = settings.findViewById(R.id.scope_label);
        scopeSeek = settings.findViewById(R.id.scope_seek);
        scopeFromButton = settings.findViewById(R.id.btn_scope_from);
        bindScope(settings);
        gateButton = settings.findViewById(R.id.btn_gate);
        gateButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseGate();
            }
        });
        settings.findViewById(R.id.btn_use_zoom).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // What the scale bar reads right now becomes the gate, so "Use this
                // zoom" means exactly this zoom.
                manager.setGate(ScaleBar.meters(IpawsPane.this.mapView));
                refresh();
            }
        });
        mapKey = settings.findViewById(R.id.map_key);
        gateFold = new Fold(settings, R.id.fold_gate_row, R.id.fold_gate_head,
                R.id.fold_gate_chev, R.id.fold_gate_body, "ipaws.fold.gate", true);
        scopeFold = new Fold(settings, R.id.fold_scope_row, R.id.fold_scope_head,
                R.id.fold_scope_chev, R.id.fold_scope_body, "ipaws.fold.scope", true);
        keyFold = new Fold(settings, R.id.fold_key_row, R.id.btn_map_key,
                R.id.fold_key_chev, R.id.map_key, "ipaws.map_key_open", true);
        whereFold = new Fold(settings, R.id.fold_where_row, R.id.fold_where_head,
                R.id.fold_where_chev, R.id.fold_where_body, "ipaws.fold.where", true);
        typesFold = new Fold(settings, R.id.fold_types_row, R.id.fold_types_head,
                R.id.fold_types_chev, R.id.fold_types_body, "ipaws.fold.types", true);
        notifyFold = new Fold(settings, R.id.fold_notify_row, R.id.fold_notify_head,
                R.id.fold_notify_chev, R.id.fold_notify_body, "ipaws.fold.notify", true);

        regionsButton = settings.findViewById(R.id.btn_regions);
        statesButton = settings.findViewById(R.id.btn_states);
        countiesButton = settings.findViewById(R.id.btn_counties);
        categoriesButton = settings.findViewById(R.id.btn_categories);
        eventsButton = settings.findViewById(R.id.btn_events);
        severityButton = settings.findViewById(R.id.btn_severity);
        intervalButton = settings.findViewById(R.id.btn_interval);
        notifyButton = header.findViewById(R.id.btn_notify);
        notifySeverityButton = settings.findViewById(R.id.btn_notify_severity);
        notifyWhereButton = settings.findViewById(R.id.btn_notify_where);
        notifyUpdatesButton = settings.findViewById(R.id.btn_notify_updates);
        notifySeverityButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseNotifySeverities();
            }
        });
        notifyWhereButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseNotifyWhere();
            }
        });
        notifyUpdatesButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseNotifyUpdates();
            }
        });
        // The real path, on demand: waiting for new weather is not a way to find out
        // what a notification looks and sounds like.
        settings.findViewById(R.id.btn_notify_test).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                manager.testNotification();
            }
        });

        regionsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseRegions();
            }
        });
        statesButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseStates();
            }
        });
        countiesButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseCountyState();
            }
        });
        categoriesButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseCategories();
            }
        });
        eventsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseEventCategory();
            }
        });
        severityButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseSeverities();
            }
        });
        intervalButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseInterval();
            }
        });
        notifyButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleNotify();
            }
        });
        settings.findViewById(R.id.btn_refresh).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                manager.poll();
            }
        });

        manager.setListener(new AlertManager.Listener() {
            @Override
            public void onChanged() {
                refresh();
            }
        });
        refresh();
    }

    public View getView() {
        return root;
    }

    public void dispose() {
        manager.setListener(null);
    }

    /** The settings page in place of the list, or the list back. */
    private void showSettings(boolean open) {
        settingsPage.setVisibility(open ? View.VISIBLE : View.GONE);
        list.setVisibility(open ? View.GONE : View.VISIBLE);
    }

    private void refreshStatus() {
        status.setText(manager.statusLine());
    }

    /** Re-reads everything the pane shows. Main thread. */
    public void refresh() {
        final Filter f = manager.getFilter();
        refreshStatus();
        regionsButton.setText(regionsLabel(f));
        statesButton.setText(statesLabel(f));
        countiesButton.setText(countiesLabel(f));
        categoriesButton.setText(categoriesLabel(f));
        eventsButton.setText(eventsLabel(f));
        severityButton.setText(severityLabel(f));
        intervalButton.setText(intervalLabel(f));
        setNotifyLabels(f);
        setAllLabel(f);
        gateButton.setText(gateLabel(f.gateBarM));
        showScope(f);
        // Each drop-down's row says what it is set to, so a closed pane still reads
        // as the whole picture.
        gateFold.head.setText("Zoom gate: " + gateLabel(f.gateBarM));
        scopeFold.head.setText("Distance: " + scopeText(f));
        whereFold.head.setText("Where: " + statesLabel(f)
                + (f.counties.isEmpty() ? "" : ", " + countiesLabel(f)));
        typesFold.head.setText("Types: " + categoriesLabel(f));
        // Severities only: with the where added the row ran out of room on the S22
        // ("In my stat..."), and the where is the first thing the row opens to.
        notifyFold.head.setText("Notifications: " + severityList(f.notifySeverities));
        rows.set(manager.snapshot());
        refreshKey();
    }

    // ---- map ------------------------------------------------------------------------

    /**
     * The switch shows what IS, the way Evac Zone's, Dozer Country's and Atmosphere's
     * do: green "Alerts ON" while they are drawn, red "Alerts OFF" while they are not,
     * in Evac Zone's colors. It was Feature Layer's "All ON" / "All OFF", which names
     * what a tap will do; over Hawaii with the map off, the operator read the green
     * "All ON" as the state and could not see why nothing drew.
     */
    private void setAllLabel(Filter f) {
        allButton.setText(pluginContext.getString(f.mapOn ? R.string.map_on : R.string.map_off));
        allButton.setTextColor(f.mapOn ? 0xFF3DDC61 : 0xFFFF5B52);
    }

    // ---- distance scope -------------------------------------------------------------

    private static String fromName(String from) {
        return "center".equals(from) ? "Map Center" : "My Location";
    }

    /** Whole large units in a radius, e.g. 25 for 25 mi. */
    private static int bigOf(double meters) {
        return (int) Math.round(meters / Units.bigToMeters(1));
    }

    /** Feature Layer's words: Everything, What is in view, or Within 25 mi of My Location. */
    private String scopeText(Filter f) {
        if ("view".equals(f.scope))
            return "What is in view";
        if (!"radius".equals(f.scope))
            return "Everything";
        final String s = "Within " + bigOf(f.scopeRadiusM) + " " + Units.bigLabel() + " of "
                + fromName(f.scopeFrom);
        // Said out loud: a circle quietly drawn around the map center when the operator
        // asked for their own position is the wrong picture looking right.
        return manager.scopeHasNoFix() ? s + " - no GPS fix, measuring from the map center" : s;
    }

    private void showScope(Filter f) {
        scopeLabel.setText(scopeText(f));
        scopeFromButton.setText("Measuring from: " + fromName(f.scopeFrom));
        if (!scopeDragging)
            scopeSeek.setProgress(!"radius".equals(f.scope) ? 0
                    : Math.max(1, Math.min(scopeSeek.getMax(), bigOf(f.scopeRadiusM))));
    }

    /**
     * Feature Layer's controls: the slider is the radius, the From button names the
     * point and rotates it, Use this extent takes the radius from the map, and Presets
     * is the list. One difference, the operator's: the slider's far left is Everything,
     * no limit at all -- "all the way left is all on" -- where Feature Layer's is What
     * is in view. What is in view stays, as a preset.
     */
    private void bindScope(View settings) {
        scopeSeek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar sb, int p, boolean fromUser) {
                if (fromUser)
                    scopeLabel.setText(p == 0 ? "Everything"
                            : "Within " + p + " " + Units.bigLabel() + " of "
                                    + fromName(manager.getFilter().scopeFrom));
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar sb) {
                scopeDragging = true;
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar sb) {
                scopeDragging = false;
                applyScope(sb.getProgress(), manager.getFilter().scopeFrom);
            }
        });
        scopeFromButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Rotates between the two points. From Everything or What is in view it
                // also needs a radius, or the button would change nothing anyone could
                // see.
                final Filter f = manager.getFilter();
                final int p = "radius".equals(f.scope) ? bigOf(f.scopeRadiusM) : DEFAULT_SCOPE_BIG;
                applyScope(p, "center".equals(f.scopeFrom) ? "me" : "center");
            }
        });
        settings.findViewById(R.id.btn_scope_extent).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // "What I am looking at", as a radius: center to corner, so the whole
                // visible rectangle is inside the circle.
                final com.atakmap.coremap.maps.coords.GeoBounds b = mapView.getBounds();
                final com.atakmap.coremap.maps.coords.GeoPoint c = mapView.getPoint().get();
                if (b == null || c == null || Double.isNaN(b.getNorth()) || Double.isNaN(b.getEast())) {
                    Toast.makeText(mapView.getContext(), "The map has no extent yet",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                final double m = c.distanceTo(
                        new com.atakmap.coremap.maps.coords.GeoPoint(b.getNorth(), b.getEast()));
                final double bigD = m / Units.bigToMeters(1);
                if (bigD > scopeSeek.getMax())
                    Toast.makeText(mapView.getContext(), String.format(Locale.US,
                            "That view is wider than %d %s, radius set to the maximum",
                            scopeSeek.getMax(), Units.bigLabel()), Toast.LENGTH_SHORT).show();
                applyScope((int) Math.max(1, Math.min(scopeSeek.getMax(), Math.round(bigD))),
                        "center");
            }
        });
        settings.findViewById(R.id.btn_scope_presets).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final Filter f = manager.getFilter();
                final String[] items = new String[SCOPE_PRESETS.length + 2];
                items[0] = "Everything";
                items[1] = "What is in view";
                int checked = "view".equals(f.scope) ? 1 : "radius".equals(f.scope) ? -1 : 0;
                final int now = "radius".equals(f.scope) ? bigOf(f.scopeRadiusM) : -1;
                for (int i = 0; i < SCOPE_PRESETS.length; i++) {
                    items[i + 2] = SCOPE_PRESETS[i] + " " + Units.bigLabel();
                    if (SCOPE_PRESETS[i] == now)
                        checked = i + 2;
                }
                fromTop(new AlertDialog.Builder(mapView.getContext())
                        .setTitle("Show alerts within")
                        .setSingleChoiceItems(items, checked, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                d.dismiss();
                                final String from = manager.getFilter().scopeFrom;
                                if (w == 1) {
                                    manager.setScope("view", from, 0);
                                    refresh();
                                } else {
                                    applyScope(w == 0 ? 0 : SCOPE_PRESETS[w - 2], from);
                                }
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show());
            }
        });
    }

    /** The slider's value: 0 is Everything, anything else a radius. */
    private void applyScope(int big, String from) {
        if (big <= 0)
            manager.setScope("all", from, 0);
        else
            manager.setScope("radius", from, Units.bigToMeters(big));
        refresh();
    }

    /** What the gate button reads: the scale-bar reading and "or closer", or Always. */
    private static String gateLabel(double barMeters) {
        return barMeters == Double.MAX_VALUE ? "Always"
                : ScaleBar.describe(barMeters) + " or closer";
    }

    private static String gateName(double big) {
        final String num = big == Math.floor(big)
                ? String.format(Locale.US, "%.0f", big)
                : String.format(Locale.US, "%.2f", big);
        return num + " " + Units.bigLabel() + " or closer";
    }

    /** Feature Layer's picker, word for word. */
    private void chooseGate() {
        final String[] labels = new String[GATE_BIG.length + 1];
        for (int i = 0; i < GATE_BIG.length; i++)
            labels[i] = gateName(GATE_BIG[i]);
        labels[GATE_BIG.length] = "Always";
        new AlertDialog.Builder(mapView.getContext())
                .setTitle("Draw when the scale bar reads")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        manager.setGate(which == GATE_BIG.length ? Double.MAX_VALUE
                                : Units.bigToMeters(GATE_BIG[which]));
                        refresh();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Opens a pick-one list at its top, with the current choice still ticked.
     * Android scrolls the list to the ticked row, which on a phone in landscape put
     * Everything, first in the distance presets, above the top of the dialog -- the
     * operator read that as there being no Everything at all.
     */
    private static void fromTop(AlertDialog d) {
        final android.widget.ListView lv = d.getListView();
        if (lv == null)
            return;
        lv.post(new Runnable() {
            @Override
            public void run() {
                lv.setSelection(0);
            }
        });
    }

    // ---- map key --------------------------------------------------------------------

    /**
     * One line per event type drawn on the map, in NWS's priority order, colored by
     * the same call the map uses. Only what is on the map: the full table is 111
     * rows, which is a reference, not a key.
     */
    private void refreshKey() {
        final List<String> events = manager.mapKey();
        if (!events.equals(keyShown)) {
            keyShown = events;
            mapKey.removeAllViews();
            for (String e : events)
                mapKey.addView(legendLine(e, AlertStyles.color(e)));
        }
        showKey();
    }

    /**
     * Open or closed as the operator left it, and no row at all when nothing is on the
     * map -- a key to an empty map is a control that does nothing.
     */
    private void showKey() {
        final boolean any = !keyShown.isEmpty();
        keyFold.row.setVisibility(any ? View.VISIBLE : View.GONE);
        keyFold.head.setText("Map key");
        keyFold.show(any);
    }

    /** One key line: a swatch in the map's color, then the event name. */
    private View legendLine(String text, int color) {
        final LinearLayout row = new LinearLayout(pluginContext);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(3), 0, dp(3));
        final View swatch = new View(pluginContext);
        swatch.setBackgroundColor(color);
        final LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(14), dp(14));
        sp.rightMargin = dp(8);
        row.addView(swatch, sp);
        final TextView t = new TextView(pluginContext);
        t.setText(text);
        t.setTextSize(13);
        t.setTextColor(Color.WHITE);
        row.addView(t);
        return row;
    }

    private int dp(int v) {
        return Math.round(v * pluginContext.getResources().getDisplayMetrics().density);
    }

    // ---- updates --------------------------------------------------------------------

    private static final int[] INTERVALS = { 1, 2, 5, 10, 15, 30 };

    private String intervalLabel(Filter f) {
        return f.pollMinutes == 1 ? "Every minute"
                : String.format(Locale.US, "Every %d min", f.pollMinutes);
    }

    private void chooseInterval() {
        final Filter f = manager.getFilter();
        final String[] names = new String[INTERVALS.length];
        int checked = -1;
        for (int i = 0; i < INTERVALS.length; i++) {
            names[i] = INTERVALS[i] == 1 ? "Every minute" : "Every " + INTERVALS[i] + " minutes";
            if (INTERVALS[i] == f.pollMinutes)
                checked = i;
        }
        fromTop(new AlertDialog.Builder(mapView.getContext())
                .setTitle("Check for alerts")
                .setSingleChoiceItems(names, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        d.dismiss();
                        f.pollMinutes = INTERVALS[w];
                        manager.saveFilter();
                        refresh();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show());
    }


    // ---- notifications --------------------------------------------------------------

    /** Radius choices for "Within N of me", in the large unit. */
    private static final int[] NOTIFY_RADII = { 2, 5, 10, 25, 50 };

    private void setNotifyLabels(Filter f) {
        notifyButton.setText(pluginContext.getString(
                f.notify ? R.string.notify_on : R.string.notify_off));
        notifyButton.setTextColor(f.notify ? 0xFF40D040 : 0xFFE05050);
        notifySeverityButton.setText("Severity: " + severityList(f.notifySeverities));
        notifyWhereButton.setText("Where: " + notifyWhereText(f));
        notifyUpdatesButton.setText(f.notifyUpdates ? "Updates: New and updated"
                : "Updates: New alerts only");
    }

    private static String severityList(java.util.Set<String> s) {
        if (s.isEmpty())
            return "none";
        final List<String> out = new ArrayList<>();
        for (String sev : Alert.SEVERITIES)
            if (s.contains(sev))
                out.add(sev);
        // Unknown is left out of "all": nearly nothing is Unknown, and every real
        // severity ticked is what a person means by all of them.
        final boolean all = s.contains("Extreme") && s.contains("Severe")
                && s.contains("Moderate") && s.contains("Minor");
        return all ? "all" : android.text.TextUtils.join(", ", out);
    }

    private static String notifyWhereText(Filter f) {
        if ("near".equals(f.notifyWhere))
            return "Within " + bigOf(f.notifyRadiusM) + " " + Units.bigLabel() + " of me";
        if ("map".equals(f.notifyWhere))
            return "Same as the map";
        return "In my states";
    }

    /** ON green, OFF red, a plain tap -- the same toggle every takwerx plugin has. */
    private void toggleNotify() {
        final Filter f = manager.getFilter();
        f.notify = !f.notify;
        // On with nothing to be told about would be a switch that does nothing.
        if (f.notify && f.notifySeverities.isEmpty()) {
            f.notifySeverities.add("Extreme");
            f.notifySeverities.add("Severe");
        }
        manager.saveFilter();
        refresh();
    }

    /**
     * Which severities chime. Defaulting to Extreme and Severe is the whole reason this
     * is usable: a notification for a Winter Weather Advisory in the next county gets
     * the plugin switched off within a day.
     */
    private void chooseNotifySeverities() {
        final Filter f = manager.getFilter();
        final String[] names = Alert.SEVERITIES;
        final boolean[] ticked = new boolean[names.length];
        for (int i = 0; i < names.length; i++)
            ticked[i] = f.notifySeverities.contains(names[i]);
        new AlertDialog.Builder(mapView.getContext())
                .setTitle("Notify me about")
                .setMultiChoiceItems(names, ticked,
                        new DialogInterface.OnMultiChoiceClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which, boolean isChecked) {
                                ticked[which] = isChecked;
                            }
                        })
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        f.notifySeverities.clear();
                        for (int i = 0; i < ticked.length; i++)
                            if (ticked[i])
                                f.notifySeverities.add(names[i]);
                        // Nothing ticked and notifications on would be a control that
                        // does nothing, so that is off.
                        if (f.notifySeverities.isEmpty())
                            f.notify = false;
                        manager.saveFilter();
                        refresh();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Where a new alert has to be to chime. Not the map's distance unless asked. */
    private void chooseNotifyWhere() {
        final Filter f = manager.getFilter();
        final String[] items = new String[NOTIFY_RADII.length + 2];
        items[0] = "In my states";
        int checked = "states".equals(f.notifyWhere) ? 0 : -1;
        for (int i = 0; i < NOTIFY_RADII.length; i++) {
            items[i + 1] = "Within " + NOTIFY_RADII[i] + " " + Units.bigLabel() + " of me";
            if ("near".equals(f.notifyWhere) && NOTIFY_RADII[i] == bigOf(f.notifyRadiusM))
                checked = i + 1;
        }
        items[items.length - 1] = "Same as the map";
        if ("map".equals(f.notifyWhere))
            checked = items.length - 1;
        fromTop(new AlertDialog.Builder(mapView.getContext())
                .setTitle("Notify me about alerts")
                .setSingleChoiceItems(items, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        d.dismiss();
                        if (w == 0) {
                            f.notifyWhere = "states";
                        } else if (w == items.length - 1) {
                            f.notifyWhere = "map";
                        } else {
                            f.notifyWhere = "near";
                            f.notifyRadiusM = Units.bigToMeters(NOTIFY_RADII[w - 1]);
                        }
                        manager.saveFilter();
                        refresh();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show());
    }

    /**
     * Whether an alert already out that is revised -- extended, reworded, a new expiry
     * -- chimes again. NWS gives every revision an id of its own, so without this one
     * warning notified again at every update.
     */
    private void chooseNotifyUpdates() {
        final Filter f = manager.getFilter();
        final String[] items = { "New alerts only", "New and updated" };
        fromTop(new AlertDialog.Builder(mapView.getContext())
                .setTitle("Notify me about")
                .setSingleChoiceItems(items, f.notifyUpdates ? 1 : 0,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                d.dismiss();
                                f.notifyUpdates = w == 1;
                                manager.saveFilter();
                                refresh();
                            }
                        })
                .setNegativeButton("Cancel", null)
                .show());
    }

    // ---- regions --------------------------------------------------------------------

    /**
     * A region is ticked only when every one of its areas is selected, so the label
     * reports what is actually true rather than what was last tapped.
     */
    private String regionsLabel(Filter f) {
        final List<String> on = new ArrayList<>();
        for (String r : Regions.names())
            if (Regions.isFullySelected(r, f.areas))
                on.add(r);
        if (on.isEmpty())
            return "Regions";
        if (on.size() == 1)
            return on.get(0);
        return String.format(Locale.US, "%d regions", on.size());
    }

    /**
     * Regions add and remove states; they are not stored. Ticking one adds its areas
     * to whatever is already selected and unticking removes them, so a region and the
     * state list can never disagree -- there is nothing kept to disagree with.
     */
    private void chooseRegions() {
        final Filter f = manager.getFilter();
        final List<String> regions = Regions.names();
        final String[] names = regions.toArray(new String[0]);
        final boolean[] ticked = new boolean[names.length];
        final boolean[] before = new boolean[names.length];
        for (int i = 0; i < names.length; i++) {
            ticked[i] = Regions.isFullySelected(names[i], f.areas);
            before[i] = ticked[i];
        }
        new AlertDialog.Builder(mapView.getContext())
                .setTitle("Regions")
                .setMultiChoiceItems(names, ticked,
                        new DialogInterface.OnMultiChoiceClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which, boolean isChecked) {
                                ticked[which] = isChecked;
                            }
                        })
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        final Set<String> areas = new LinkedHashSet<>(f.areas);
                        for (int i = 0; i < names.length; i++) {
                            if (ticked[i] == before[i])
                                continue;   // untouched: leave its states alone
                            if (ticked[i])
                                areas.addAll(Regions.areasIn(names[i]));
                            else
                                areas.removeAll(Regions.areasIn(names[i]));
                        }
                        applyStates(areas);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---- states ---------------------------------------------------------------------

    private String statesLabel(Filter f) {
        final int n = f.areas.size();
        if (n == 0)
            return pluginContext.getString(R.string.choose_states);
        if (n == 1)
            return Areas.name(f.areas.iterator().next());
        return String.format(Locale.US, "%d states", n);
    }

    /**
     * Any number of states. Nothing is applied until OK, so a half-made selection
     * never reaches the map, and Clear is its own button because emptying a set by
     * unticking a dozen boxes is not a thing to ask of anyone.
     */
    private void chooseStates() {
        final List<String> codes = Areas.codes();
        final String[] names = new String[codes.size()];
        final boolean[] ticked = new boolean[codes.size()];
        final Filter f = manager.getFilter();
        for (int i = 0; i < codes.size(); i++) {
            names[i] = Areas.name(codes.get(i));
            ticked[i] = f.areas.contains(codes.get(i));
        }
        new AlertDialog.Builder(mapView.getContext())
                .setTitle("States")
                .setMultiChoiceItems(names, ticked,
                        new DialogInterface.OnMultiChoiceClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which, boolean isChecked) {
                                ticked[which] = isChecked;
                            }
                        })
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        final Set<String> chosen = new LinkedHashSet<>();
                        for (int i = 0; i < ticked.length; i++)
                            if (ticked[i])
                                chosen.add(codes.get(i));
                        applyStates(chosen);
                    }
                })
                .setNeutralButton("Clear", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        applyStates(new LinkedHashSet<String>());
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void applyStates(Set<String> chosen) {
        final Filter f = manager.getFilter();
        f.areas.clear();
        f.areas.addAll(chosen);
        // A county selection for a state that is no longer selected is dead weight,
        // and worse, it would silently narrow that state if it were ever reselected.
        f.dropOrphanedCounties();
        manager.saveFilter();
        refresh();
        // The states are a server-side filter, so this one needs the feed asked again.
        manager.poll();
    }

    // ---- counties -------------------------------------------------------------------

    private String countiesLabel(Filter f) {
        if (f.counties.isEmpty())
            return pluginContext.getString(R.string.all_counties);
        final int states = f.narrowedStates().size();
        if (f.counties.size() == 1)
            return "1 county";
        if (states <= 1)
            return String.format(Locale.US, "%d counties", f.counties.size());
        return String.format(Locale.US, "%d counties in %d states", f.counties.size(), states);
    }

    /**
     * Counties belong to a state, so the first question is which of the selected
     * states to narrow. With one state selected there is nothing to ask and it goes
     * straight there.
     */
    private void chooseCountyState() {
        final Filter f = manager.getFilter();
        if (f.areas.isEmpty()) {
            toast("Choose a state first");
            return;
        }
        final List<String> states = new ArrayList<>();
        for (String a : f.areas)
            if (Areas.isKnown(a))
                states.add(a);   // marine areas have no counties to narrow to
        if (states.isEmpty()) {
            toast("Choose a state first");
            return;
        }
        if (states.size() == 1) {
            chooseCounties(states.get(0));
            return;
        }
        final String[] names = new String[states.size()];
        for (int i = 0; i < states.size(); i++) {
            final Set<String> chosen = f.countiesIn(states.get(i));
            names[i] = chosen.isEmpty() ? Areas.name(states.get(i))
                    : Areas.name(states.get(i)) + "  -  " + chosen.size() + " chosen";
        }
        new AlertDialog.Builder(mapView.getContext())
                .setTitle("Counties in which state?")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        d.dismiss();
                        chooseCounties(states.get(w));
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** The county list for one state, fetched if this is the first time. */
    private void chooseCounties(final String stateCode) {
        if (!counties.isCached(stateCode))
            toast("Getting the counties of " + Areas.name(stateCode));
        counties.get(stateCode, new Counties.Ready() {
            @Override
            public void onCounties(List<Counties.County> list) {
                showCounties(stateCode, list);
            }

            @Override
            public void onFailure(String error) {
                toast("Could not get the counties of " + Areas.name(stateCode)
                        + " - " + error);
            }
        });
    }

    private void showCounties(final String stateCode, final List<Counties.County> list) {
        final Filter f = manager.getFilter();
        final Set<String> already = f.countiesIn(stateCode);
        final String[] names = new String[list.size()];
        final boolean[] ticked = new boolean[list.size()];
        for (int i = 0; i < list.size(); i++) {
            names[i] = list.get(i).name;
            ticked[i] = already.contains(list.get(i).same);
        }
        new AlertDialog.Builder(mapView.getContext())
                .setTitle(Areas.name(stateCode))
                .setMultiChoiceItems(names, ticked,
                        new DialogInterface.OnMultiChoiceClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which, boolean isChecked) {
                                ticked[which] = isChecked;
                            }
                        })
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        final Set<String> chosen = new LinkedHashSet<>();
                        for (int i = 0; i < ticked.length; i++)
                            if (ticked[i])
                                chosen.add(list.get(i).same);
                        applyCounties(stateCode, chosen);
                    }
                })
                // Clear means "the whole state again", which is what no counties means.
                .setNeutralButton("Whole state", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        applyCounties(stateCode, new LinkedHashSet<String>());
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void applyCounties(String stateCode, Set<String> chosen) {
        final Filter f = manager.getFilter();
        f.setCountiesIn(stateCode, chosen);
        manager.saveFilter();
        refresh();
        // Counties are filtered here, not asked of the server, so the map can be
        // redrawn from the set already in hand -- no request, no waiting.
        manager.reapplyFilter();
    }

    // ---- types ----------------------------------------------------------------------

    private String categoriesLabel(Filter f) {
        final int all = Events.categories().size();
        final int on = all - f.offCategories.size();
        if (f.offCategories.isEmpty())
            return pluginContext.getString(R.string.all_categories);
        if (on == 1) {
            for (String c : Events.categories())
                if (!f.offCategories.contains(c))
                    return c;
        }
        return String.format(Locale.US, "%d of %d categories", on, all);
    }

    /**
     * The whole categories, on or off. Switching Marine off takes its twenty event
     * types with it, which is the control the operator asked for first.
     */
    private void chooseCategories() {
        final List<String> cats = Events.categories();
        final String[] names = cats.toArray(new String[0]);
        final boolean[] ticked = new boolean[names.length];
        final Filter f = manager.getFilter();
        for (int i = 0; i < names.length; i++)
            ticked[i] = !f.offCategories.contains(names[i]);
        new AlertDialog.Builder(mapView.getContext())
                .setTitle("Categories")
                .setMultiChoiceItems(names, ticked,
                        new DialogInterface.OnMultiChoiceClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which, boolean isChecked) {
                                ticked[which] = isChecked;
                            }
                        })
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        f.offCategories.clear();
                        for (int i = 0; i < ticked.length; i++)
                            if (!ticked[i])
                                f.offCategories.add(names[i]);
                        // An event choice inside a category now off is dead weight,
                        // and would silently narrow it if it were ever switched back on.
                        f.dropOrphanedEvents();
                        manager.saveFilter();
                        refresh();
                        manager.reapplyFilter();
                    }
                })
                // All of them on is the default, so "Clear" here means exactly that.
                .setNeutralButton("All on", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        f.offCategories.clear();
                        manager.saveFilter();
                        refresh();
                        manager.reapplyFilter();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private String eventsLabel(Filter f) {
        if (f.events.isEmpty())
            return pluginContext.getString(R.string.all_types);
        final int cats = f.narrowedCategories().size();
        if (f.events.size() == 1)
            return f.events.iterator().next();
        if (cats <= 1)
            return String.format(Locale.US, "%d types", f.events.size());
        return String.format(Locale.US, "%d types in %d categories", f.events.size(), cats);
    }

    /** Which category to get specific in; skipped when only one is left on. */
    private void chooseEventCategory() {
        final Filter f = manager.getFilter();
        final List<String> on = new ArrayList<>();
        for (String c : Events.categories())
            if (!f.offCategories.contains(c))
                on.add(c);
        if (on.isEmpty()) {
            toast("Every category is switched off");
            return;
        }
        if (on.size() == 1) {
            chooseEvents(on.get(0));
            return;
        }
        final String[] names = new String[on.size()];
        for (int i = 0; i < on.size(); i++) {
            final int n = f.eventsIn(on.get(i)).size();
            names[i] = n == 0 ? on.get(i) : on.get(i) + "  -  " + n + " chosen";
        }
        new AlertDialog.Builder(mapView.getContext())
                .setTitle("Types in which category?")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        d.dismiss();
                        chooseEvents(on.get(w));
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void chooseEvents(final String category) {
        final Filter f = manager.getFilter();
        final List<String> all = Events.eventsIn(category);
        final Set<String> already = f.eventsIn(category);
        final String[] names = all.toArray(new String[0]);
        final boolean[] ticked = new boolean[names.length];
        for (int i = 0; i < names.length; i++)
            ticked[i] = already.contains(names[i]);
        new AlertDialog.Builder(mapView.getContext())
                .setTitle(category)
                .setMultiChoiceItems(names, ticked,
                        new DialogInterface.OnMultiChoiceClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which, boolean isChecked) {
                                ticked[which] = isChecked;
                            }
                        })
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        final Set<String> chosen = new LinkedHashSet<>();
                        for (int i = 0; i < ticked.length; i++)
                            if (ticked[i])
                                chosen.add(names[i]);
                        // Everything ticked is the same as the whole category, and
                        // storing it as the whole category means a type NWS adds later
                        // is included rather than silently missing.
                        f.setEventsIn(category,
                                chosen.size() == names.length
                                        ? new LinkedHashSet<String>() : chosen);
                        manager.saveFilter();
                        refresh();
                        manager.reapplyFilter();
                    }
                })
                .setNeutralButton("Whole category", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        f.setEventsIn(category, new LinkedHashSet<String>());
                        manager.saveFilter();
                        refresh();
                        manager.reapplyFilter();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---- severity -------------------------------------------------------------------

    private String severityLabel(Filter f) {
        if (f.severities.isEmpty() || f.severities.size() == Alert.SEVERITIES.length)
            return pluginContext.getString(R.string.all_severities);
        if (f.severities.size() == 1)
            return f.severities.iterator().next();
        return String.format(Locale.US, "%d severities", f.severities.size());
    }

    private void chooseSeverities() {
        final Filter f = manager.getFilter();
        final String[] names = Alert.SEVERITIES;
        final boolean[] ticked = new boolean[names.length];
        for (int i = 0; i < names.length; i++)
            ticked[i] = f.severities.isEmpty() || f.severities.contains(names[i]);
        new AlertDialog.Builder(mapView.getContext())
                .setTitle("Severity")
                .setMultiChoiceItems(names, ticked,
                        new DialogInterface.OnMultiChoiceClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which, boolean isChecked) {
                                ticked[which] = isChecked;
                            }
                        })
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        final Set<String> chosen = new LinkedHashSet<>();
                        for (int i = 0; i < ticked.length; i++)
                            if (ticked[i])
                                chosen.add(names[i]);
                        f.severities.clear();
                        // Everything ticked is the same as no filter, and storing it
                        // as no filter keeps the query short and the label honest.
                        if (chosen.size() != names.length)
                            f.severities.addAll(chosen);
                        manager.saveFilter();
                        refresh();
                        manager.poll();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---- helpers --------------------------------------------------------------------

    /** MapView context, always: a toast on the plugin context has no window token. */
    private void toast(String s) {
        Toast.makeText(mapView.getContext(), s, Toast.LENGTH_SHORT).show();
    }
}

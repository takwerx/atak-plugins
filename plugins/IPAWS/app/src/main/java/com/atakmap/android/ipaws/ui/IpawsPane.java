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
import com.atakmap.android.ipaws.data.MainThread;
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
    private final Button allButton;
    private final Button gateButton;
    private final TextView scopeLabel;
    private final android.widget.SeekBar scopeSeek;
    private final Button scopeFromButton;
    /** True while a finger is on the slider, so a poll's refresh does not yank it back. */
    private boolean scopeDragging;

    /** Feature Layer's presets, in the large unit; 0 here is Everywhere. */
    private static final int[] SCOPE_PRESETS = { 0, 2, 5, 10, 25, 50 };
    /** The radius a "Measuring from" tap gives when there was none, as Feature Layer's does. */
    private static final int DEFAULT_SCOPE_BIG = 25;
    /** Whether the status line last said "zoom in", so a map move only redraws a change. */
    private boolean saidZoomIn;

    /**
     * Keeps the status line's "zoom in" true while the map moves: it is read from the
     * zoom, and the pane otherwise only refreshes on a poll. Cam Depot's pattern --
     * onMapMoved runs on the GL thread every frame of a pinch, so it only posts, and
     * the posts are coalesced.
     */
    private final com.atakmap.map.AtakMapView.OnMapMovedListener moved =
            new com.atakmap.map.AtakMapView.OnMapMovedListener() {
                @Override
                public void onMapMoved(com.atakmap.map.AtakMapView view, boolean animate) {
                    MainThread.remove(gateTick);
                    MainThread.postDelayed(gateTick, 150);
                }
            };

    private final Runnable gateTick = new Runnable() {
        @Override
        public void run() {
            if (manager.zoomedOutPastGate() != saidZoomIn)
                refreshStatus();
        }
    };

    /** Feature Layer's presets, in the operator's large unit, as the scale bar reads them. */
    private static final double[] GATE_BIG = { 0.25, 1, 5, 15, 50 };
    private final Button keyButton;
    private final LinearLayout mapKey;
    /** What the key last drew, so a poll that changed nothing does not rebuild it. */
    private List<String> keyShown = new ArrayList<>();
    private final SharedPreferences prefs;
    private boolean keyOpen;

    private static final String PREF_KEY_OPEN = "ipaws.map_key_open";

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
        keyOpen = prefs.getBoolean(PREF_KEY_OPEN, false);
        allButton = header.findViewById(R.id.btn_all);
        allButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                manager.setMapOn(!manager.getFilter().mapOn);
                refresh();
            }
        });
        scopeLabel = header.findViewById(R.id.scope_label);
        scopeSeek = header.findViewById(R.id.scope_seek);
        scopeFromButton = header.findViewById(R.id.btn_scope_from);
        bindScope(header);
        gateButton = header.findViewById(R.id.btn_gate);
        gateButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseGate();
            }
        });
        header.findViewById(R.id.btn_use_zoom).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                manager.setGate(IpawsPane.this.mapView.getMapResolution());
                refresh();
            }
        });
        keyButton = header.findViewById(R.id.btn_map_key);
        mapKey = header.findViewById(R.id.map_key);
        keyButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                keyOpen = !keyOpen;
                prefs.edit().putBoolean(PREF_KEY_OPEN, keyOpen).apply();
                showKey();
            }
        });

        regionsButton = header.findViewById(R.id.btn_regions);
        statesButton = header.findViewById(R.id.btn_states);
        countiesButton = header.findViewById(R.id.btn_counties);
        categoriesButton = header.findViewById(R.id.btn_categories);
        eventsButton = header.findViewById(R.id.btn_events);
        severityButton = header.findViewById(R.id.btn_severity);
        intervalButton = header.findViewById(R.id.btn_interval);
        notifyButton = header.findViewById(R.id.btn_notify);

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
                chooseNotify();
            }
        });
        header.findViewById(R.id.btn_refresh).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                manager.poll();
            }
        });

        mapView.addOnMapMovedListener(moved);
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
        mapView.removeOnMapMovedListener(moved);
        MainThread.remove(gateTick);
    }

    private void refreshStatus() {
        saidZoomIn = manager.zoomedOutPastGate();
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
        setNotifyLabel(f);
        setAllLabel(f);
        gateButton.setText(gateLabel(f.gateGsd));
        showScope(f);
        rows.set(manager.snapshot());
        refreshKey();
    }

    // ---- map ------------------------------------------------------------------------

    /**
     * Feature Layer's switch, word for word: the button says what it will do, red to
     * take everything off the map and green to put it back.
     */
    private void setAllLabel(Filter f) {
        allButton.setText(pluginContext.getString(f.mapOn ? R.string.all_off : R.string.all_on));
        allButton.setTextColor(f.mapOn ? 0xFFF44336 : 0xFF4CAF50);
    }

    // ---- distance scope -------------------------------------------------------------

    private static String fromName(String from) {
        return "center".equals(from) ? "Map Center" : "My Location";
    }

    /** Whole large units in a radius, e.g. 25 for 25 mi. */
    private static int bigOf(double meters) {
        return (int) Math.round(meters / Units.bigToMeters(1));
    }

    private String scopeText(Filter f) {
        if (f.scopeRadiusM <= 0)
            return "Everywhere";
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
            scopeSeek.setProgress(f.scopeRadiusM <= 0 ? scopeSeek.getMax()
                    : Math.max(0, Math.min(scopeSeek.getMax(), bigOf(f.scopeRadiusM) - 1)));
    }

    /** Feature Layer's controls, one for one. The slider runs 1 to 50 in the large unit. */
    private void bindScope(View header) {
        scopeSeek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar sb, int p, boolean fromUser) {
                if (fromUser)
                    scopeLabel.setText("Within " + (p + 1) + " " + Units.bigLabel() + " of "
                            + fromName(manager.getFilter().scopeFrom));
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar sb) {
                scopeDragging = true;
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar sb) {
                scopeDragging = false;
                manager.setScope(manager.getFilter().scopeFrom,
                        Units.bigToMeters(sb.getProgress() + 1));
                refresh();
            }
        });
        scopeFromButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Rotates between the two points. From Everywhere it also needs a
                // radius, or the button would change nothing anyone could see.
                final Filter f = manager.getFilter();
                final double r = f.scopeRadiusM > 0 ? f.scopeRadiusM
                        : Units.bigToMeters(DEFAULT_SCOPE_BIG);
                manager.setScope("center".equals(f.scopeFrom) ? "me" : "center", r);
                refresh();
            }
        });
        header.findViewById(R.id.btn_scope_extent).setOnClickListener(new View.OnClickListener() {
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
                final int max = scopeSeek.getMax() + 1;
                int big = (int) Math.max(1, Math.round(m / Units.bigToMeters(1)));
                if (big > max) {
                    Toast.makeText(mapView.getContext(), String.format(Locale.US,
                            "That view is wider than %d %s, radius set to the maximum",
                            max, Units.bigLabel()), Toast.LENGTH_SHORT).show();
                    big = max;
                }
                manager.setScope("center", Units.bigToMeters(big));
                refresh();
            }
        });
        header.findViewById(R.id.btn_scope_presets).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final Filter f = manager.getFilter();
                final int now = f.scopeRadiusM <= 0 ? 0 : bigOf(f.scopeRadiusM);
                final String[] items = new String[SCOPE_PRESETS.length];
                int checked = -1;
                for (int i = 0; i < SCOPE_PRESETS.length; i++) {
                    items[i] = SCOPE_PRESETS[i] == 0 ? "Everywhere"
                            : "Within " + SCOPE_PRESETS[i] + " " + Units.bigLabel();
                    if (SCOPE_PRESETS[i] == now)
                        checked = i;
                }
                new AlertDialog.Builder(mapView.getContext())
                        .setTitle("Show alerts within")
                        .setSingleChoiceItems(items, checked, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                d.dismiss();
                                manager.setScope(manager.getFilter().scopeFrom,
                                        SCOPE_PRESETS[w] == 0 ? 0 : Units.bigToMeters(SCOPE_PRESETS[w]));
                                refresh();
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        });
    }

    /** What the gate button reads: the scale-bar reading and "or closer", or Always. */
    private static String gateLabel(double gsd) {
        return gsd == Double.MAX_VALUE ? "Always" : Units.barReading(gsd) + " or closer";
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
                        final double gsd = which == GATE_BIG.length ? Double.MAX_VALUE
                                : Units.bigToMeters(GATE_BIG[which]) / Units.BAR_PIXELS;
                        manager.setGate(gsd);
                        refresh();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
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
     * Open or closed as the operator left it, and no button at all when nothing is
     * on the map -- a key to an empty map is a control that does nothing.
     */
    private void showKey() {
        final boolean any = !keyShown.isEmpty();
        keyButton.setVisibility(any ? View.VISIBLE : View.GONE);
        keyButton.setText(pluginContext.getString(
                keyOpen ? R.string.map_key_hide : R.string.map_key_show));
        mapKey.setVisibility(any && keyOpen ? View.VISIBLE : View.GONE);
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
        new AlertDialog.Builder(mapView.getContext())
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
                .show();
    }

    /** ON green, OFF red, on a plain button -- the same toggle every takwerx plugin has. */
    private void setNotifyLabel(Filter f) {
        notifyButton.setText(pluginContext.getString(
                f.notify ? R.string.notify_on : R.string.notify_off));
        notifyButton.setTextColor(f.notify ? 0xFF40D040 : 0xFFE05050);
    }

    /**
     * Off, or on at the severities the operator picks. Defaulting to Extreme and
     * Severe is the whole reason this is usable: a notification for a Winter Weather
     * Advisory in the next county gets the plugin switched off within a day.
     */
    private void chooseNotify() {
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
                .setPositiveButton("Notify me", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        f.notifySeverities.clear();
                        for (int i = 0; i < ticked.length; i++)
                            if (ticked[i])
                                f.notifySeverities.add(names[i]);
                        // Nothing ticked and notifications on would be a control that
                        // does nothing, so that is off.
                        f.notify = !f.notifySeverities.isEmpty();
                        manager.saveFilter();
                        refresh();
                    }
                })
                .setNeutralButton("Turn off", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        f.notify = false;
                        manager.saveFilter();
                        refresh();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
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

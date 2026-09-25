package com.atakmap.android.ipaws.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.ipaws.AlertManager;
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

    /** Re-reads everything the pane shows. Main thread. */
    public void refresh() {
        final Filter f = manager.getFilter();
        status.setText(manager.statusLine());
        regionsButton.setText(regionsLabel(f));
        statesButton.setText(statesLabel(f));
        countiesButton.setText(countiesLabel(f));
        categoriesButton.setText(categoriesLabel(f));
        eventsButton.setText(eventsLabel(f));
        severityButton.setText(severityLabel(f));
        intervalButton.setText(intervalLabel(f));
        setNotifyLabel(f);
        rows.set(manager.snapshot());
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

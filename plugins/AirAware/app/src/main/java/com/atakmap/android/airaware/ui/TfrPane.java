package com.atakmap.android.airaware.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.airaware.Airspace;
import com.atakmap.android.airaware.AirspaceFeatures;
import com.atakmap.android.airaware.UasfmFeatures;
import com.atakmap.android.airaware.Nsufr;
import com.atakmap.android.airaware.NsufrFeatures;
import com.atakmap.android.airaware.Notam;
import com.atakmap.android.airaware.NotamFeatures;
import com.atakmap.android.airaware.Metar;
import com.atakmap.android.airaware.Obstacle;
import com.atakmap.android.airaware.UasfmCell;
import com.atakmap.android.airaware.MetarFeatures;
import com.atakmap.android.airaware.Tfr;
import com.atakmap.android.airaware.TfrArea;
import com.atakmap.android.airaware.TfrFeatures;
import com.atakmap.android.airaware.TfrGeofence;
import com.atakmap.android.airaware.Places;
import com.atakmap.android.airaware.TfrManager;
import com.atakmap.android.airaware.TfrTypes;
import com.atakmap.android.airaware.TfrVertical;
import com.atakmap.android.airaware.TfrWatch;
import com.atakmap.android.airaware.plugin.R;
import com.atakmap.coremap.conversions.Span;
import com.atakmap.coremap.conversions.SpanUtilities;
import com.atakmap.coremap.maps.coords.GeoCalculations;
import com.atakmap.coremap.maps.coords.GeoPoint;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;

/**
 * The pane: a status line, three buttons, the list, and a settings page in place of the
 * list. The takwerx shape, so an operator moving between plugins already knows where
 * everything is.
 */
public class TfrPane implements TfrManager.Listener {

    /** Past this the list is trimmed and the status line says so. */
    private static final int LIST_CAP = 100;
    /** Scale-bar presets for the zoom gate, in the operator's large unit. */
    private static final double[] GATE_PRESETS = { 0.25, 1, 5, 15, 50 };
    private static final int[] REFRESH_PRESETS = { 15, 30, 60, 180 };

    private final MapView mapView;
    private final Context pluginContext;
    private final TfrManager manager;
    private final SharedPreferences prefs;

    private View root;
    private ListView list;
    private View header, settingsPage, detailsPage;
    private TextView detailsTitle, detailsBody;
    private Button detailsBack, detailsGeofence, detailsFaa;
    /** What the details page is showing, so its buttons know their subject. */
    private Tfr showing;
    private Button settingsButton, refreshButton, backButton, planButton;
    /** What the Flight Plan button does; the plugin supplies it. */
    private Runnable onPlan;
    private TextView statusText, headingText;
    private LinearLayout settingsContainer, updatesContainer, keyBody;
    private TextView downloadedNote;
    private Fold fromFold, keyFold, updatesFold, fencesFold;
    private LinearLayout fencesContainer, fromContainer, layerList;

    private final List<Tfr> rows = new ArrayList<>();
    private RowAdapter adapter;
    private int inViewTotal;

    private final SimpleDateFormat local = new SimpleDateFormat("EEE d MMM HH:mm", Locale.US);

    public TfrPane(MapView mapView, Context pluginContext, TfrManager manager) {
        this.mapView = mapView;
        this.pluginContext = pluginContext;
        this.manager = manager;
        this.prefs = mapView.getContext().getSharedPreferences("airaware", Context.MODE_PRIVATE);
    }

    public View build() {
        root = PluginLayoutInflater.inflate(pluginContext, R.layout.main_layout, null);
        statusText = root.findViewById(R.id.status);
        list = root.findViewById(R.id.tfr_list);
        settingsPage = root.findViewById(R.id.settings_page);
        settingsContainer = root.findViewById(R.id.settings_container);
        detailsPage = root.findViewById(R.id.details_page);
        detailsTitle = root.findViewById(R.id.details_title);
        detailsBody = root.findViewById(R.id.details_body);
        detailsBack = root.findViewById(R.id.btn_details_back);
        detailsGeofence = root.findViewById(R.id.btn_details_geofence);
        detailsFaa = root.findViewById(R.id.btn_details_faa);

        header = PluginLayoutInflater.inflate(pluginContext, R.layout.controls_header, null);
        list.addHeaderView(header, null, false);
        adapter = new RowAdapter();
        list.setAdapter(adapter);

        settingsButton = header.findViewById(R.id.btn_settings);
        refreshButton = header.findViewById(R.id.btn_refresh);
        planButton = header.findViewById(R.id.btn_plan);
        headingText = header.findViewById(R.id.list_heading);
        layerList = header.findViewById(R.id.layer_list);
        header.findViewById(R.id.layers_all_on).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                manager.setAllLayersOn(true);
                render();
            }
        });
        header.findViewById(R.id.layers_all_off).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                manager.setAllLayersOn(false);
                render();
            }
        });

        final View settings = PluginLayoutInflater.inflate(pluginContext,
                R.layout.settings_controls, null);
        settingsContainer.addView(settings);
        backButton = root.findViewById(R.id.btn_settings_back);
        updatesContainer = settings.findViewById(R.id.updates_container);
        keyBody = settings.findViewById(R.id.fold_key_body);
        downloadedNote = settings.findViewById(R.id.downloaded_note);


        fencesContainer = settings.findViewById(R.id.fences_container);
        fromContainer = settings.findViewById(R.id.from_container);

        fencesFold = new Fold(settings, R.id.fold_fences_head, R.id.fold_fences_chev,
                R.id.fold_fences_body, "fold.fences");
        fromFold = new Fold(settings, R.id.fold_from_head, R.id.fold_from_chev,
                R.id.fold_from_body, "fold.from");
        keyFold = new Fold(settings, R.id.fold_key_head, R.id.fold_key_chev,
                R.id.fold_key_body, "fold.key");
        updatesFold = new Fold(settings, R.id.fold_updates_head, R.id.fold_updates_chev,
                R.id.fold_updates_body, "fold.updates");

        wire();
        render();
        return root;
    }

    /** The pane opens on the main screen every time; a reused one would reopen on Settings. */
    public void onShown() {
        if (!isShowingDetails())
            showList();
    }

    private void wire() {
        settingsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSettings(true);
            }
        });
        // The planner is UAS Flight Plan's own pane, carried over whole, and it opens
        // as its own pane rather than a page in here: that is the screen the operator
        // already knows and nothing about it should change.
        planButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (onPlan != null)
                    onPlan.run();
            }
        });
        backButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSettings(false);
            }
        });
        detailsBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Back takes ATAK's pinned callout with it, whichever way the item was picked.
                com.atakmap.android.airaware.plugin.AirAware.clearAtakSelection();
                showList();
            }
        });
        detailsGeofence.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (showing != null)
                    geofence(showing);
            }
        });
        detailsFaa.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (showing != null)
                    open(showing.faaPageUrl());
            }
        });
        refreshButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                manager.syncNow();
                render();
            }
        });
        // The row's own listener, not the list's: a row holding a focusable Button stops
        // firing OnItemClickListener altogether, so the tap would simply die.
        list.setOnItemClickListener(null);
    }

    private void showSettings(boolean on) {
        settingsPage.setVisibility(on ? View.VISIBLE : View.GONE);
        detailsPage.setVisibility(View.GONE);
        list.setVisibility(on ? View.GONE : View.VISIBLE);
        if (on)
            render();
    }

    /** Back to the list from wherever. */
    public void showList() {
        showing = null;
        settingsPage.setVisibility(View.GONE);
        detailsPage.setVisibility(View.GONE);
        list.setVisibility(View.VISIBLE);
        render();
    }

    public void setOnPlan(Runnable r) {
        this.onPlan = r;
    }

    public boolean isShowingDetails() {
        return detailsPage != null && detailsPage.getVisibility() == View.VISIBLE;
    }

    @Override
    public void onChanged() {
        render();
    }

    // ---- drawing the pane ----

    public void render() {
        if (root == null)
            return;
        // Refresh is the restrictions' download, so it follows that layer and not the
        // plugin: there is no plugin-wide on any more.
        refreshButton.setEnabled(manager.isLayerOn(TfrManager.LAYER_RESTRICTIONS)
                && !manager.isSyncing());
        statusText.setText(manager.status());

        buildLayerRows();
        rebuildList();
        renderSettings();
    }

    private void rebuildList() {
        rows.clear();
        // The list is the restrictions' list, so it follows that layer. With METARs on
        // and TFR off it was still counting restrictions nobody could see.
        final boolean on = manager.isLayerOn(TfrManager.LAYER_RESTRICTIONS);
        final List<Tfr> inView = on ? manager.inView() : new ArrayList<Tfr>();
        inViewTotal = inView.size();
        final GeoPoint from = manager.self();
        Collections.sort(inView, new Comparator<Tfr>() {
            @Override
            public int compare(Tfr a, Tfr b) {
                return Double.compare(distance(from, a), distance(from, b));
            }
        });
        for (int i = 0; i < inView.size() && i < LIST_CAP; i++)
            rows.add(inView.get(i));
        // The heading names the scope it is actually showing. It read "in view" whatever
        // Area was set to, so an operator who had chosen Everything zoomed onto a single
        // restriction and the count did not move -- correctly, and for a reason the pane
        // was hiding from them.
        headingText.setText(on ? manager.listHeading() + " (" + inViewTotal + ")"
                : manager.listHeading());
        adapter.notifyDataSetChanged();
    }

    /** Settings now holds only what is not a layer's own: see the layer rows for those. */
    private void renderSettings() {
        fromFold.label("Measure from", manager.measureFromLabel());
        buildFrom();

        keyFold.label("Map key", null);
        buildKey();

        // buildFences labels its own fold, so leaving it out of the render left the
        // Geofences row on the Settings page blank: a button with a chevron and no
        // words on it. In layout order, between Map key and Updates.
        buildFences();

        updatesFold.label("Updates", "every " + manager.refreshMinutesForDisplay() + " min");
        buildUpdates();
        downloadedNote.setText(manager.describeCacheForDisplay());
    }

    /**
     * The operator's own geofences, and a way to clear the ones guarding nothing.
     *
     * <p>Never removed on the plugin's own initiative: it is their shape, so the plugin
     * reports what has become of the restriction and offers the removal.
     */
    private void buildFences() {
        final List<TfrWatch.Watched> fences = manager.allFences();
        final List<TfrWatch.Watched> stale = manager.staleFences();
        fencesFold.label("Geofences", fences.isEmpty() ? "none made"
                : stale.isEmpty() ? fences.size() + ", all current"
                        : stale.size() + " of " + fences.size() + " out of date");
        fencesContainer.removeAllViews();
        if (fences.isEmpty()) {
            final TextView t = new TextView(pluginContext);
            t.setText("You have not made any. Geofence on a restriction's page makes one.");
            t.setTextSize(13f);
            t.setTextColor(pluginContext.getResources().getColor(R.color.dim_text));
            fencesContainer.addView(t);
            return;
        }
        // Every one, not only the ones that have gone out of date. Listing just the
        // stale ones meant a fence that was perfectly fine never appeared, so after
        // making one the page still read as though nothing was there.
        for (final TfrWatch.Watched w : fences) {
            final boolean ok = w.state == TfrWatch.State.CURRENT;
            final TextView t = new TextView(pluginContext);
            t.setText(w.title + " - " + TfrWatch.words(w.state));
            t.setTextSize(13f);
            t.setPadding(0, 6, 0, 2);
            t.setTextColor(pluginContext.getResources().getColor(
                    ok ? R.color.dim_text : R.color.white));
            fencesContainer.addView(t);

            // Only the stale ones offer removal. A live one is removed from the map the
            // way the operator made it, and a button here would invite clearing a fence
            // that is still guarding something.
            if (ok)
                continue;

            final Button b = new Button(pluginContext, null, 0, R.style.TakwerxButton);
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = 4;
            b.setLayoutParams(lp);
            b.setText("Remove this geofence");
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    manager.removeFence(w.uid);
                    render();
                }
            });
            fencesContainer.addView(b);
        }
        if (stale.size() > 1) {
            final Button all = new Button(pluginContext, null, 0, R.style.TakwerxButton);
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = 6;
            all.setLayoutParams(lp);
            all.setText("Remove all " + stale.size());
            all.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    for (TfrWatch.Watched w2 : manager.staleFences())
                        manager.removeFence(w2.uid);
                    render();
                }
            });
            fencesContainer.addView(all);
        }
    }

    /** Types as a multi-choice with counts, applied on OK. */
    private void pickTypes() {
        final List<String[]> counts = manager.typeCounts();
        if (counts.isEmpty()) {
            new AlertDialog.Builder(mapView.getContext())
                    .setMessage("Nothing is downloaded yet, so there is nothing to choose.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        final String[] labels = new String[counts.size()];
        final boolean[] checked = new boolean[counts.size()];
        for (int i = 0; i < counts.size(); i++) {
            labels[i] = TfrTypes.label(counts.get(i)[0]) + "  (" + counts.get(i)[1] + ")";
            checked[i] = manager.isTypeOn(counts.get(i)[0]);
        }
        final AlertDialog d = new AlertDialog.Builder(mapView.getContext())
                .setTitle("Which kinds of restriction")
                .setMultiChoiceItems(labels, checked,
                        new DialogInterface.OnMultiChoiceClickListener() {
                            @Override
                            public void onClick(DialogInterface dlg, int which, boolean on) {
                                manager.setTypeOn(counts.get(which)[0], on);
                            }
                        })
                .setPositiveButton("Done", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dlg, int w) {
                        render();
                    }
                })
                .show();
        fromTop(d);
    }

    /** Which vocabulary to name places in, then the places themselves. */
    private void pickWhereMode() {
        final String[] modes = { "Everywhere", "By state", "By FAA region", "By center" };
        final AlertDialog d = new AlertDialog.Builder(mapView.getContext())
                .setTitle("Where")
                .setItems(modes, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dlg, int which) {
                        switch (which) {
                            case 1:
                                pickWhere(TfrManager.WHERE_STATE, "Which states");
                                break;
                            case 2:
                                pickWhere(TfrManager.WHERE_REGION, "Which FAA regions");
                                break;
                            case 3:
                                pickWhere(TfrManager.WHERE_CENTER, "Which centers");
                                break;
                            default:
                                manager.setWhere(TfrManager.WHERE_EVERYWHERE, null);
                                render();
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
        fromTop(d);
    }

    /** How much of the picture the list covers. */
    private void pickArea() {
        final String[] labels = {
                "Everything", "What is in view",
                "Within " + ScaleBar.gate(manager.areaRadiusMeters())
                        + (manager.hasFix() ? " of me" : " of the map center")
        };
        final AlertDialog d = new AlertDialog.Builder(mapView.getContext())
                .setTitle("Area the list covers")
                .setSingleChoiceItems(labels, manager.areaMode(),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dlg, int which) {
                                dlg.dismiss();
                                if (which == TfrManager.AREA_RADIUS)
                                    pickRadius();
                                else
                                    manager.setArea(which, 0);
                                render();
                            }
                        })
                .setNegativeButton("Cancel", null)
                .show();
        fromTop(d);
    }

    private void pickAirfieldGate() {
        pickBarGate("Show airfields when the scale bar reads",
                manager.airfieldBarMeters(), new OnGate() {
                    @Override
                    public void set(long meters) {
                        manager.setAirfieldBarMeters(meters);
                    }
                });
    }

    private interface OnGate {
        void set(long meters);
    }

    /** One scale-bar picker, since three gates now want the same list. */
    private void pickBarGate(String title, long current, final OnGate onGate) {
        final String unit = Units.bigLabel();
        final double[] presets = { 1, 5, 10, 25, 50 };
        final String[] labels = new String[presets.length + 1];
        final double[] meters = new double[presets.length + 1];
        for (int i = 0; i < presets.length; i++) {
            labels[i] = trim(presets[i]) + " " + unit + " or closer";
            meters[i] = Units.bigToMeters(presets[i]);
        }
        labels[presets.length] = "Always";
        meters[presets.length] = -1;
        int checked = presets.length;
        for (int i = 0; i < meters.length - 1; i++)
            if (Math.abs(meters[i] - current) < 1)
                checked = i;
        final AlertDialog d = new AlertDialog.Builder(mapView.getContext())
                .setTitle(title)
                .setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dlg, int which) {
                        onGate.set((long) Math.round(meters[which]));
                        dlg.dismiss();
                        render();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
        fromTop(d);
    }

    private void pickLabelGate() {
        final String unit = Units.bigLabel();
        final double[] presets = { 1, 5, 10, 25, 50 };
        final String[] labels = new String[presets.length + 1];
        final double[] meters = new double[presets.length + 1];
        for (int i = 0; i < presets.length; i++) {
            labels[i] = trim(presets[i]) + " " + unit + " or closer";
            meters[i] = Units.bigToMeters(presets[i]);
        }
        labels[presets.length] = "Always";
        meters[presets.length] = -1;
        int checked = presets.length;
        for (int i = 0; i < meters.length - 1; i++)
            if (Math.abs(meters[i] - manager.labelBarMeters()) < 1)
                checked = i;
        final AlertDialog d = new AlertDialog.Builder(mapView.getContext())
                .setTitle("Show names when the scale bar reads")
                .setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dlg, int which) {
                        manager.setLabelBarMeters((long) Math.round(meters[which]));
                        dlg.dismiss();
                        render();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
        fromTop(d);
    }





    /** What the distances and the ordering are measured from. */
    private void buildFrom() {
        fromContainer.removeAllViews();
        addChoice(fromContainer, "My location", manager.measureFrom() == TfrManager.FROM_ME,
                new Runnable() {
                    @Override
                    public void run() {
                        manager.setMeasureFrom(TfrManager.FROM_ME);
                    }
                });
        addChoice(fromContainer, "Map center",
                manager.measureFrom() == TfrManager.FROM_MAP_CENTER, new Runnable() {
                    @Override
                    public void run() {
                        manager.setMeasureFrom(TfrManager.FROM_MAP_CENTER);
                    }
                });
    }


    private void addChoice(LinearLayout into, String text, boolean chosen, final Runnable act) {
        final Button b = new Button(pluginContext, null, 0, R.style.TakwerxButton);
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 4;
        b.setLayoutParams(lp);
        b.setText(text);
        b.setTextColor(pluginContext.getResources().getColor(
                chosen ? R.color.state_on : R.color.white));
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                act.run();
                render();
            }
        });
        into.addView(b);
    }

    /** Multi-choice, applied on OK, with a count on every place and a neutral Clear. */
    private void pickWhere(final int mode, String title) {
        final Map<String, Integer> counts = manager.whereCounts(mode);
        final List<String> keys = new ArrayList<>();
        if (mode == TfrManager.WHERE_REGION) {
            // Every region, so one with nothing in it today can still be chosen.
            keys.addAll(Places.regions());
            for (String k : counts.keySet())
                if (!keys.contains(k))
                    keys.add(k);
        } else {
            keys.addAll(counts.keySet());
            Collections.sort(keys);
        }
        if (keys.isEmpty()) {
            new AlertDialog.Builder(mapView.getContext())
                    .setMessage("Nothing is downloaded yet, so there is nothing to choose from.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        final String[] labels = new String[keys.size()];
        final boolean[] checked = new boolean[keys.size()];
        final Set<String> picked = new LinkedHashSet<>(
                manager.whereMode() == mode ? manager.whereValues()
                        : Collections.<String> emptySet());
        for (int i = 0; i < keys.size(); i++) {
            final String k = keys.get(i);
            final Integer n = counts.get(k);
            labels[i] = name(mode, k) + (n == null ? "" : "  (" + n + ")");
            checked[i] = picked.contains(k);
        }
        final AlertDialog d = new AlertDialog.Builder(mapView.getContext())
                .setTitle(title)
                .setMultiChoiceItems(labels, checked,
                        new DialogInterface.OnMultiChoiceClickListener() {
                            @Override
                            public void onClick(DialogInterface dlg, int which, boolean on) {
                                if (on)
                                    picked.add(keys.get(which));
                                else
                                    picked.remove(keys.get(which));
                            }
                        })
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dlg, int which) {
                        manager.setWhere(picked.isEmpty() ? TfrManager.WHERE_EVERYWHERE : mode,
                                picked);
                        render();
                    }
                })
                .setNeutralButton("Clear", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dlg, int which) {
                        manager.setWhere(TfrManager.WHERE_EVERYWHERE, null);
                        render();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
        fromTop(d);
    }

    private static String name(int mode, String key) {
        if (mode == TfrManager.WHERE_STATE)
            return Places.stateName(key);
        if (mode == TfrManager.WHERE_CENTER)
            return Places.centerName(key);
        return key;
    }

    private void pickRadius() {
        final String unit = Units.bigLabel();
        final double[] presets = { 10, 25, 50, 100, 250 };
        final String[] labels = new String[presets.length];
        for (int i = 0; i < presets.length; i++)
            labels[i] = trim(presets[i]) + " " + unit;
        int checked = -1;
        for (int i = 0; i < presets.length; i++)
            if (Math.abs(Units.bigToMeters(presets[i]) - manager.areaRadiusMeters()) < 1)
                checked = i;
        final AlertDialog d = new AlertDialog.Builder(mapView.getContext())
                .setTitle("Show restrictions within")
                .setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dlg, int which) {
                        manager.setArea(TfrManager.AREA_RADIUS,
                                (long) Math.round(Units.bigToMeters(presets[which])));
                        dlg.dismiss();
                        render();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
        fromTop(d);
    }

    /**
     * The layer rows on the front page: each a switch that says its state, with that
     * layer's own controls under its arrow.
     *
     * <p>Built in code rather than laid out, because a layer's controls are its own and
     * the set of layers will grow. Open and closed is remembered per layer.
     */
    private void buildLayerRows() {
        if (layerList == null)
            return;
        layerList.removeAllViews();
        // The manager's list, not one written out here: see TfrManager.layers().
        for (String[] layer : manager.layers())
            addLayerRow(layer[1], layer[0], layerStatus(layer[0]));
    }

    private String layerStatus(String key) {
        if (TfrManager.LAYER_NOTAMS.equals(key))
            return notamStatus();
        if (TfrManager.LAYER_UASFM.equals(key))
            return uasfmStatus();
        if (TfrManager.LAYER_NSUFR.equals(key))
            return nsufrStatus();
        if (TfrManager.LAYER_OBSTACLES.equals(key))
            return obstaclesStatus();
        if (TfrManager.LAYER_RESTRICTIONS.equals(key))
            return restrictionsStatus();
        if (TfrManager.LAYER_AIRSPACE.equals(key))
            return airspaceStatus(false);
        if (TfrManager.LAYER_SUA.equals(key))
            return airspaceStatus(true);
        return airfieldsStatus();
    }

    /**
     * What the airspace rows say when they are open.
     *
     * <p>Airspace that has not reached the phone gets its own sentence. A layer that is
     * quietly thin reads as open sky, which is the one thing it must never read as.
     */
    private String airspaceStatus(boolean specialUse) {
        final String layer = specialUse ? TfrManager.LAYER_SUA : TfrManager.LAYER_AIRSPACE;
        if (!manager.isLayerOn(layer))
            return "Off. Nothing drawn.";
        if (manager.isAirspaceBusy())
            return "Not downloaded for here yet. Showing what is already on this phone.";
        int n = 0;
        for (String[] row : manager.airspaceCounts(specialUse))
            if (manager.isClassOn(row[0]))
                n += Integer.parseInt(row[1]);
        if (n == 0)
            return "Nothing here yet";
        final String what = specialUse ? " special use areas" : " shelves";
        return n + what + (manager.isAirspaceCapped() ? " (more than fit, zoom in)" : "");
    }

    private String restrictionsStatus() {
        final int n = manager.shown().size();
        if (!manager.isLayerOn(TfrManager.LAYER_RESTRICTIONS))
            return "Off. Nothing drawn.";
        return n + (n == 1 ? " restriction" : " restrictions") + " on the map";
    }

    private String airfieldsStatus() {
        final int n = manager.airfields().size();
        if (!manager.isLayerOn(TfrManager.LAYER_AIRFIELDS))
            return "Off. Nothing drawn.";
        return n == 0 ? "No stations yet" : n + " stations in view";
    }

    private void addLayerRow(final String name, final String key, String status) {
        final Resources res = pluginContext.getResources();
        final View row = PluginLayoutInflater.inflate(pluginContext, R.layout.layer_row, null);
        final Button head = row.findViewById(R.id.layer_toggle);
        final ImageButton chev = row.findViewById(R.id.layer_expand);
        final TextView statusView = row.findViewById(R.id.layer_status);
        final LinearLayout body = row.findViewById(R.id.layer_body);

        setState(res, head, name, manager.isLayerOn(key));
        head.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                manager.setLayerOn(key, !manager.isLayerOn(key));
                render();
            }
        });

        final String pref = "layerfold." + key;
        final boolean open = prefs.getBoolean(pref, false);
        statusView.setText(status);
        statusView.setVisibility(open ? View.VISIBLE : View.GONE);
        body.setVisibility(open ? View.VISIBLE : View.GONE);
        chev.setRotation(open ? 180f : 0f);
        // The arrow works while the layer is off, so it can be set up before it is on.
        chev.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.edit().putBoolean(pref, !open).apply();
                render();
            }
        });

        if (open) {
            if (TfrManager.LAYER_RESTRICTIONS.equals(key))
                fillRestrictionControls(body);
            else if (TfrManager.LAYER_AIRSPACE.equals(key))
                fillAirspaceControls(body, false);
            else if (TfrManager.LAYER_SUA.equals(key))
                fillAirspaceControls(body, true);
            else if (TfrManager.LAYER_OBSTACLES.equals(key))
                fillObstacleControls(body);
            else if (TfrManager.LAYER_NOTAMS.equals(key))
                fillNotamControls(body);
            else if (TfrManager.LAYER_UASFM.equals(key))
                fillUasfmControls(body);
            else if (TfrManager.LAYER_NSUFR.equals(key))
                fillNsufrControls(body);
            else
                fillAirfieldControls(body);
        }
        layerList.addView(row);
    }

    /** What belongs to the restrictions and nothing else. */
    private void fillRestrictionControls(LinearLayout body) {
        addValueButton(body, "Types", typesSummary(), new Runnable() {
            @Override
            public void run() {
                pickTypes();
            }
        });
        addValueButton(body, "Where", manager.whereLabel(), new Runnable() {
            @Override
            public void run() {
                pickWhereMode();
            }
        });
        addValueButton(body, "Area", manager.areaLabel(), new Runnable() {
            @Override
            public void run() {
                pickArea();
            }
        });
        addValueButton(body, "Zoom gate", ScaleBar.gate(manager.gateBarMeters()), new Runnable() {
            @Override
            public void run() {
                pickGate();
            }
        });
        addValueButton(body, "Labels", ScaleBar.gate(manager.labelBarMeters()), new Runnable() {
            @Override
            public void run() {
                pickLabelGate();
            }
        });
    }

    /**
     * What belongs to the airspace shelves.
     *
     * <p>The two rows share a zoom gate on purpose: they come from one fetch, so gating
     * them apart would buy nothing and give the operator two numbers to keep in step.
     */
    private void fillAirspaceControls(LinearLayout body, final boolean specialUse) {
        addValueButton(body, specialUse ? "Kinds" : "Classes",
                airspaceKindsSummary(specialUse), new Runnable() {
                    @Override
                    public void run() {
                        pickAirspaceKinds(specialUse);
                    }
                });
        addValueButton(body, "Show at", ScaleBar.gate(manager.airspaceBarMeters()),
                new Runnable() {
                    @Override
                    public void run() {
                        pickAirspaceGate();
                    }
                });
        addValueButton(body, "3D", manager.airspace3dOn() ? "On" : "Off", new Runnable() {
            @Override
            public void run() {
                manager.setAirspace3dOn(!manager.airspace3dOn());
                render();
            }
        });
        // Off by default and on the layer that owns them, because forty of them over a
        // metro area is the map gone.
        addValueButton(body, "Names on the map",
                manager.airspaceLabelsOn() ? "On" : "Off", new Runnable() {
                    @Override
                    public void run() {
                        manager.setAirspaceLabelsOn(!manager.airspaceLabelsOn());
                        render();
                    }
                });
    }

    private String airspaceKindsSummary(boolean specialUse) {
        final List<String[]> counts = manager.airspaceCounts(specialUse);
        if (counts.isEmpty())
            return "nothing here yet";
        int on = 0;
        for (String[] row : counts)
            if (manager.isClassOn(row[0]))
                on++;
        if (on == counts.size())
            return "all " + counts.size();
        if (on == 0)
            return "none";
        return on + " of " + counts.size();
    }

    private void pickAirspaceKinds(final boolean specialUse) {
        final List<String[]> counts = manager.airspaceCounts(specialUse);
        if (counts.isEmpty()) {
            new AlertDialog.Builder(mapView.getContext())
                    .setMessage("Nothing is downloaded here yet, so there is nothing to choose.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        final String[] labels = new String[counts.size()];
        final boolean[] checked = new boolean[counts.size()];
        for (int i = 0; i < counts.size(); i++) {
            labels[i] = Airspace.setName(counts.get(i)[0]) + "  (" + counts.get(i)[1] + ")";
            checked[i] = manager.isClassOn(counts.get(i)[0]);
        }
        final AlertDialog d = new AlertDialog.Builder(mapView.getContext())
                .setTitle(specialUse ? "Which special use areas" : "Which classes")
                .setMultiChoiceItems(labels, checked,
                        new DialogInterface.OnMultiChoiceClickListener() {
                            @Override
                            public void onClick(DialogInterface dlg, int which, boolean on) {
                                manager.setClassOn(counts.get(which)[0], on);
                            }
                        })
                .setPositiveButton("Done", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dlg, int w) {
                        render();
                    }
                })
                .show();
        fromTop(d);
    }

    private void pickAirspaceGate() {
        pickBarGate("Show airspace when the scale bar reads",
                manager.airspaceBarMeters(), new OnGate() {
                    @Override
                    public void set(long meters) {
                        manager.setAirspaceBarMeters(meters);
                    }
                });
    }

    /**
     * What the NOTAM row says when it is open: how many are drawn, and how old the
     * relay's picture is. The age is the one number that tells a crew whether to trust
     * it; a layer that is quietly an hour stale reads as current.
     */
    private String notamStatus() {
        if (!manager.isLayerOn(TfrManager.LAYER_NOTAMS))
            return "Off. Nothing drawn.";
        final int n = manager.notams().size();
        final StringBuilder b = new StringBuilder();
        if (n == 0) {
            int held = 0;
            for (String[] row : manager.notamKindCounts())
                held += Integer.parseInt(row[1]);
            if (manager.isNotamsMissing())
                b.append("Not downloaded for here yet");
            else if (held > 0)
                b.append(held).append(held == 1 ? " here, in a kind" : " here, all in kinds")
                        .append(" switched off");
            else
                b.append("None in view. Zoom out to look wider");
        } else
            b.append(n).append(n == 1 ? " NOTAM" : " NOTAMs")
                    .append(manager.isNotamsCapped()
                            ? " (nearest shown, zoom in for the rest)" : "");
        final long built = manager.notamBuiltMs();
        if (built > 0) {
            final long ageMin = Math.max(0L, (System.currentTimeMillis() - built) / 60000L);
            b.append(". FAA picture ");
            if (ageMin < 1)
                b.append("under a minute old");
            else if (ageMin < 90)
                b.append(ageMin).append(" min old");
            else
                b.append(ageMin / 60).append(" h old");
            if (ageMin >= 20)
                b.append(" - no newer one has reached this phone");
        }
        return b.toString();
    }

    /** What belongs to the NOTAMs and nothing else. */
    private void fillNotamControls(LinearLayout body) {
        addValueButton(body, "Kinds", notamKindsSummary(), new Runnable() {
            @Override
            public void run() {
                pickNotamKinds();
            }
        });
        addValueButton(body, "Show at", ScaleBar.gate(manager.notamBarMeters()),
                new Runnable() {
                    @Override
                    public void run() {
                        pickBarGate("Show NOTAMs when the scale bar reads",
                                manager.notamBarMeters(), new OnGate() {
                                    @Override
                                    public void set(long meters) {
                                        manager.setNotamBarMeters(meters);
                                    }
                                });
                    }
                });
        // The key, right here under the arrow: the operator looks for what a color
        // means where the layer is, not on the Settings page (2026-10-09). One line
        // per kind that is switched on, in the color the map draws it with.
        final TextView heading = new TextView(pluginContext);
        heading.setText("Key");
        heading.setTextSize(10f);
        heading.setAllCaps(true);
        heading.setAlpha(0.6f);
        heading.setPadding(0, 10, 0, 2);
        heading.setTextColor(pluginContext.getResources().getColor(R.color.dim_text));
        body.addView(heading);
        for (String k : Notam.KINDS)
            if (manager.isNotamKindOn(k))
                addKeyLine(body, Notam.kindName(k), NotamFeatures.color(k));
        addKeyLine(body, "Half strength: not yet in effect", 0);
    }

    private String notamKindsSummary() {
        int on = 0;
        for (String k : Notam.KINDS)
            if (manager.isNotamKindOn(k))
                on++;
        if (on == Notam.KINDS.length)
            return "all " + Notam.KINDS.length;
        return on == 0 ? "none" : on + " of " + Notam.KINDS.length;
    }

    private void pickNotamKinds() {
        final List<String[]> counts = manager.notamKindCounts();
        final String[] labels = new String[counts.size()];
        final boolean[] checked = new boolean[counts.size()];
        for (int i = 0; i < counts.size(); i++) {
            labels[i] = Notam.kindName(counts.get(i)[0]) + "  (" + counts.get(i)[1] + ")";
            checked[i] = manager.isNotamKindOn(counts.get(i)[0]);
        }
        final AlertDialog d = new AlertDialog.Builder(mapView.getContext())
                .setTitle("Which kinds of NOTAM")
                .setMultiChoiceItems(labels, checked,
                        new DialogInterface.OnMultiChoiceClickListener() {
                            @Override
                            public void onClick(DialogInterface dlg, int which, boolean on) {
                                manager.setNotamKindOn(counts.get(which)[0], on);
                            }
                        })
                .setPositiveButton("Done", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dlg, int w) {
                        render();
                    }
                })
                .show();
        fromTop(d);
    }

    /**
     * One NOTAM, on the same details page. The text as the FAA issued it comes first
     * and whole: the abbreviations are the language pilots read, and a paraphrase would
     * be a second thing to get wrong.
     */
    public void showNotam(final Notam n) {
        if (root == null || n == null)
            return;
        showing = null;
        detailsTitle.setText(n.title());
        final long now = System.currentTimeMillis();
        final StringBuilder b = new StringBuilder();
        b.append(n.text.trim()).append("\n\n");
        if (n.isFuture(now))
            b.append("Not yet in effect. Starts ").append(local.format(new Date(n.startMs)));
        else if (n.startMs > 0)
            b.append("In effect since ").append(local.format(new Date(n.startMs)));
        else
            b.append("In effect");
        b.append('\n');
        if (n.endMs == Long.MAX_VALUE)
            b.append("Until: permanent, or until cancelled");
        else
            b.append("Until: ").append(local.format(new Date(n.endMs)))
                    .append(n.estimatedEnd ? " (estimated)" : "");
        if (!n.lower.isEmpty() || !n.upper.isEmpty())
            b.append("\nAltitude: ").append(n.lower.isEmpty() ? "surface" : n.lower)
                    .append(" to ").append(n.upper.isEmpty() ? "unspecified" : n.upper);
        b.append("\n\nKind: ").append(Notam.kindName(n.kind));
        if (!n.location.isEmpty())
            b.append("\nLocation: ").append(n.location);
        if (!n.classification.isEmpty())
            b.append("\nSeries: ").append(classificationName(n.classification));
        final long updated = Notam.parseTime(n.updated);
        if (updated > 0)
            b.append("\nLast changed: ").append(local.format(new Date(updated)));
        if (!n.hasArea())
            b.append("\n\nNo mapped area: the FAA gives this one a point only.");
        b.append("\n\nFAA NOTAM Management Service, through takwerx. Always check the")
                .append(" official NOTAM search before a flight.");
        detailsBody.setText(b.toString());
        detailsGeofence.setVisibility(View.GONE);
        detailsFaa.setVisibility(View.GONE);
        settingsPage.setVisibility(View.GONE);
        list.setVisibility(View.GONE);
        detailsPage.setVisibility(View.VISIBLE);
    }

    private static String classificationName(String c) {
        switch (c) {
            case "DOM":
                return "Domestic";
            case "FDC":
                return "FDC (flight data center)";
            case "MIL":
                return "Military";
            case "INTL":
                return "International format";
            default:
                return c;
        }
    }

    private String uasfmStatus() {
        if (!manager.isLayerOn(TfrManager.LAYER_UASFM))
            return "Off. Nothing drawn.";
        final UasfmCell here = manager.uasfmHere();
        if (here != null)
            return here.label() + "  (" + here.where() + ")";
        final int n = manager.uasfmDrawn().size();
        if (n == 0)
            return "No UAS grid here - outside controlled airspace";
        // The cap is worth spelling out: a square that is not drawn is not a square
        // with no rule, and a half-drawn grid invites exactly that reading.
        return manager.isUasfmCapped()
                ? "Nearest " + n + " squares shown - zoom in for the rest"
                : n + " squares";
    }

    private String nsufrStatus() {
        if (!manager.isLayerOn(TfrManager.LAYER_NSUFR))
            return "Off. Nothing drawn.";
        if (manager.isNsufrMissing())
            return "Not downloaded yet. Needs a network once.";
        final Nsufr here = manager.nsufrHere();
        if (here != null)
            return "You are inside " + here.title() + " (" + here.statusName().toLowerCase(Locale.US) + ")";
        final int n = manager.nsufrDrawn().size();
        if (n == 0)
            return "None in view";
        return manager.isNsufrCapped()
                ? "Nearest " + n + " shown - zoom in for the rest"
                : n + (n == 1 ? " area" : " areas") + " in view";
    }

    /** What belongs to the National Security restrictions and nothing else. */
    private void fillNsufrControls(LinearLayout body) {
        addValueButton(body, "Show at", ScaleBar.gate(manager.nsufrBarMeters()),
                new Runnable() {
                    @Override
                    public void run() {
                        pickBarGate("Show UAS no-fly areas when the scale bar reads",
                                manager.nsufrBarMeters(), new OnGate() {
                                    @Override
                                    public void set(long meters) {
                                        manager.setNsufrBarMeters(meters);
                                    }
                                });
                    }
                });
        addValueButton(body, "3D", manager.nsufr3dOn() ? "On" : "Off", new Runnable() {
            @Override
            public void run() {
                manager.setNsufr3dOn(!manager.nsufr3dOn());
                render();
            }
        });
    }

    /** What belongs to the UAS ceilings and nothing else. */
    private void fillUasfmControls(LinearLayout body) {
        addValueButton(body, "Show at", ScaleBar.gate(manager.uasfmBarMeters()),
                new Runnable() {
                    @Override
                    public void run() {
                        pickBarGate("Show UAS ceilings when the scale bar reads",
                                manager.uasfmBarMeters(), new OnGate() {
                                    @Override
                                    public void set(long meters) {
                                        manager.setUasfmBarMeters(meters);
                                    }
                                });
                    }
                });
    }

    private String obstaclesStatus() {
        if (!manager.isLayerOn(TfrManager.LAYER_OBSTACLES))
            return "Off. Nothing drawn.";
        final int n = manager.obstacles().size();
        if (n == 0)
            return manager.isObstaclesMissing() ? "Not downloaded for here yet"
                    : "None here above " + (int) manager.obstacleFloorFt() + " ft";
        return n + " obstacles"
                + (manager.isObstaclesCapped() ? " (tallest shown, zoom in for the rest)" : "");
    }

    /** What belongs to the obstacles and nothing else. */
    private void fillObstacleControls(LinearLayout body) {
        addValueButton(body, "Kinds", obstacleKindsSummary(), new Runnable() {
            @Override
            public void run() {
                pickObstacleGroups();
            }
        });
        addValueButton(body, "Taller than",
                (int) manager.obstacleFloorFt() + " ft", new Runnable() {
                    @Override
                    public void run() {
                        pickObstacleFloor();
                    }
                });
        addValueButton(body, "Show at", ScaleBar.gate(manager.obstacleBarMeters()),
                new Runnable() {
                    @Override
                    public void run() {
                        pickBarGate("Show obstacles when the scale bar reads",
                                manager.obstacleBarMeters(), new OnGate() {
                                    @Override
                                    public void set(long meters) {
                                        manager.setObstacleBarMeters(meters);
                                    }
                                });
                    }
                });
    }

    private String obstacleKindsSummary() {
        int on = 0;
        final String[] groups = Obstacle.GROUPS;
        for (String g : groups)
            if (manager.isObstacleGroupOn(g))
                on++;
        if (on == groups.length)
            return "all " + groups.length;
        return on == 0 ? "none" : on + " of " + groups.length;
    }

    private void pickObstacleGroups() {
        final List<String[]> counts = manager.obstacleGroupCounts();
        final String[] labels = new String[counts.size()];
        final boolean[] checked = new boolean[counts.size()];
        for (int i = 0; i < counts.size(); i++) {
            labels[i] = Obstacle.groupName(counts.get(i)[0]) + "  (" + counts.get(i)[1] + ")";
            checked[i] = manager.isObstacleGroupOn(counts.get(i)[0]);
        }
        final AlertDialog d = new AlertDialog.Builder(mapView.getContext())
                .setTitle("Which kinds of obstacle")
                .setMultiChoiceItems(labels, checked,
                        new DialogInterface.OnMultiChoiceClickListener() {
                            @Override
                            public void onClick(DialogInterface dlg, int which, boolean on) {
                                manager.setObstacleGroupOn(counts.get(which)[0], on);
                            }
                        })
                .setPositiveButton("Done", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dlg, int w) {
                        render();
                    }
                })
                .show();
        fromTop(d);
    }

    private void pickObstacleFloor() {
        final double[] presets = Obstacle.MIN_AGL_PRESETS_FT;
        final String[] labels = new String[presets.length];
        int selected = 0;
        for (int i = 0; i < presets.length; i++) {
            labels[i] = presets[i] <= 0 ? "Everything the FAA lists"
                    : "Taller than " + (int) presets[i] + " ft";
            if (Math.abs(presets[i] - manager.obstacleFloorFt()) < 0.5)
                selected = i;
        }
        final AlertDialog d = new AlertDialog.Builder(mapView.getContext())
                .setTitle("Height above the ground")
                .setSingleChoiceItems(labels, selected,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dlg, int which) {
                                manager.setObstacleFloorFt(presets[which]);
                                dlg.dismiss();
                                render();
                            }
                        })
                .show();
        fromTop(d);
    }

    /** What belongs to the airfield chips and nothing else. */
    private void fillAirfieldControls(LinearLayout body) {
        addValueButton(body, "Show at", ScaleBar.gate(manager.airfieldBarMeters()),
                new Runnable() {
                    @Override
                    public void run() {
                        pickAirfieldGate();
                    }
                });
    }

    private void addValueButton(LinearLayout into, String name, String value,
            final Runnable act) {
        final Button b = new Button(pluginContext, null, 0, R.style.TakwerxButton);
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 4;
        b.setLayoutParams(lp);
        b.setGravity(android.view.Gravity.LEFT | android.view.Gravity.CENTER_VERTICAL);
        b.setText(value == null || value.isEmpty() ? name : name + ": " + value);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                act.run();
            }
        });
        into.addView(b);
    }

    private String typesSummary() {
        final List<String[]> counts = manager.typeCounts();
        if (counts.isEmpty())
            return "nothing downloaded yet";
        int on = 0;
        for (String[] c : counts)
            if (manager.isTypeOn(c[0]))
                on++;
        return on + " of " + counts.size();
    }


    /**
     * Built from the same colors the map draws with, so the two cannot drift, and it
     * follows the layers: a layer that is off contributes nothing.
     *
     * <p>It used to list the two restriction colors and stop, which was right when
     * restrictions were the only thing drawn and wrong once six layers were. A key that
     * names a third of the map is worse than none, because it reads as complete.
     */
    private void buildKey() {
        keyBody.removeAllViews();
        boolean any = false;

        if (manager.isLayerOn(TfrManager.LAYER_RESTRICTIONS)) {
            addKeyHeading("TFR");
            addKeyLine("In effect now", manager.activeColor());
            addKeyLine("Scheduled, not yet in effect", manager.upcomingColor());
            final int noArea = countWithoutArea();
            if (noArea > 0)
                addKeyLine(noArea + (noArea == 1
                        ? " restriction has no mapped area and is listed only"
                        : " restrictions have no mapped area and are listed only"),
                        0x00000000);
            any = true;
        }

        any |= addAirspaceKey(false, "Airspace");
        any |= addAirspaceKey(true, "Special Use");

        if (manager.isLayerOn(TfrManager.LAYER_UASFM)) {
            addKeyHeading("UAS ceilings");
            // The bands the FAA publishes, coarsest first, each in the color the grid
            // draws that ceiling with.
            final int[] bands = { 400, 300, 200, 100, 0 };
            for (int ft : bands)
                addKeyLine(ft == 0
                        ? "0 ft: no flight without further coordination"
                        : ft + " ft above the ground",
                        UasfmFeatures.color(ft));
            any = true;
        }

        if (manager.isLayerOn(TfrManager.LAYER_NSUFR)) {
            addKeyHeading("UAS No-Fly");
            addKeyLine("No UAS flight, surface to 400 ft: national security restriction",
                    NsufrFeatures.color(Nsufr.FULL_TIME));
            addKeyLine("Part-time: no UAS flight when the facility activates it",
                    NsufrFeatures.color(Nsufr.PART_TIME));
            addKeyLine("Pending: announced, not yet in force",
                    NsufrFeatures.color(Nsufr.PENDING));
            any = true;
        }

        if (manager.isLayerOn(TfrManager.LAYER_OBSTACLES)) {
            addKeyHeading("Obstacles");
            addKeyLine("Charted obstacle, with its height above the ground",
                    manager.obstacleColor());
            any = true;
        }

        if (manager.isLayerOn(TfrManager.LAYER_NOTAMS)) {
            addKeyHeading("NOTAMs");
            for (String k : Notam.KINDS)
                if (manager.isNotamKindOn(k))
                    addKeyLine(Notam.kindName(k), NotamFeatures.color(k));
            addKeyLine("Not yet in effect: same color, half strength", 0x00000000);
            any = true;
        }

        if (manager.isLayerOn(TfrManager.LAYER_AIRFIELDS)) {
            addKeyHeading("METARs");
            addKeyLine("VFR", MetarFeatures.VFR);
            addKeyLine("MVFR", MetarFeatures.MVFR);
            addKeyLine("IFR", MetarFeatures.IFR);
            addKeyLine("LIFR", MetarFeatures.LIFR);
            any = true;
        }

        if (!any)
            addKeyLine("No layer is switched on, so nothing is drawn.", 0x00000000);
    }

    /**
     * One airspace block, listing only the classes or kinds that are switched on.
     *
     * @return whether anything was added
     */
    private boolean addAirspaceKey(boolean specialUse, String heading) {
        final String layer = specialUse ? TfrManager.LAYER_SUA : TfrManager.LAYER_AIRSPACE;
        if (!manager.isLayerOn(layer))
            return false;
        final List<String[]> counts = manager.airspaceCounts(specialUse);
        boolean added = false;
        for (String[] row : counts) {
            if (!manager.isClassOn(row[0]))
                continue;
            if (!added) {
                addKeyHeading(heading);
                added = true;
            }
            addKeyLine(Airspace.setName(row[0]), AirspaceFeatures.color(row[0]));
        }
        // Class E is two colors on the map and one row in the counts, because how low it
        // comes down is decided per shelf rather than by its set.
        if (added && !specialUse && manager.isClassOn("as:E"))
            addKeyLine("Class E from 1,200 ft above the ground",
                    AirspaceFeatures.classE1200Color());
        if (!added) {
            addKeyHeading(heading);
            addKeyLine("Nothing here yet.", 0x00000000);
        }
        return true;
    }

    /** A small-caps heading over a group, the same shape the rest of the pane uses. */
    private void addKeyHeading(String text) {
        final TextView t = new TextView(pluginContext);
        t.setText(text);
        t.setTextSize(10f);
        t.setAllCaps(true);
        t.setAlpha(0.6f);
        t.setPadding(0, keyBody.getChildCount() == 0 ? 2 : 12, 0, 2);
        t.setTextColor(pluginContext.getResources().getColor(R.color.dim_text));
        keyBody.addView(t);
    }

    private void addKeyLine(String text, int color) {
        addKeyLine(keyBody, text, color);
    }

    /** One key line, in the color the map draws that thing with, wherever it is wanted. */
    private void addKeyLine(LinearLayout into, String text, int color) {
        final TextView t = new TextView(pluginContext);
        t.setText(text);
        t.setTextSize(13f);
        t.setPadding(0, 4, 0, 4);
        t.setTextColor(color == 0 ? pluginContext.getResources().getColor(R.color.dim_text) : color);
        into.addView(t);
    }

    private int countWithoutArea() {
        int n = 0;
        for (Tfr t : manager.shown())
            if (t.hasNoMappedArea())
                n++;
        return n;
    }

    private void buildUpdates() {
        updatesContainer.removeAllViews();
        final int current = manager.refreshMinutesForDisplay();
        for (final int m : REFRESH_PRESETS) {
            final Button b = new Button(pluginContext, null, 0, R.style.TakwerxButton);
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.leftMargin = updatesContainer.getChildCount() == 0 ? 0 : 6;
            b.setLayoutParams(lp);
            b.setText(m < 60 ? m + " min" : (m / 60) + " h");
            b.setTextColor(pluginContext.getResources().getColor(
                    m == current ? R.color.state_on : R.color.white));
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    manager.setRefreshMinutes(m);
                    render();
                }
            });
            updatesContainer.addView(b);
        }
    }

    private void pickGate() {
        final String unit = Units.bigLabel();
        final String[] labels = new String[GATE_PRESETS.length + 1];
        final double[] meters = new double[GATE_PRESETS.length + 1];
        for (int i = 0; i < GATE_PRESETS.length; i++) {
            // A preset list pins the large unit, or 0.25 mi appears as 1320 ft.
            labels[i] = trim(GATE_PRESETS[i]) + " " + unit + " or closer";
            meters[i] = Units.bigToMeters(GATE_PRESETS[i]);
        }
        labels[GATE_PRESETS.length] = "Always";
        meters[GATE_PRESETS.length] = -1;
        int checked = GATE_PRESETS.length;
        for (int i = 0; i < meters.length - 1; i++)
            if (Math.abs(meters[i] - manager.gateBarMeters()) < 1)
                checked = i;
        final AlertDialog d = new AlertDialog.Builder(mapView.getContext())
                .setTitle("Draw when the scale bar reads")
                .setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        manager.setGateBarMeters((long) Math.round(meters[which]));
                        dialog.dismiss();
                        render();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
        fromTop(d);
    }

    private static void fromTop(AlertDialog d) {
        final ListView lv = d.getListView();
        if (lv == null)
            return;
        lv.post(new Runnable() {
            @Override
            public void run() {
                lv.setSelection(0);
            }
        });
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? Integer.toString((int) v) : Double.toString(v);
    }

    /**
     * A gate's distance the way the picker wrote it.
     *
     * <p>Not {@code ScaleBar.describe}, which goes through ATAK's generic span formatter
     * and renders a ten mile gate as "10.00 mi". The row head should read back exactly
     * what was chosen from the list.
     */
    private static String gateLabel(long meters) {
        return ScaleBar.gate(meters);
    }

    // ---- details ----

    public void showDetails(final Tfr t) {
        if (root == null)
            return;
        showing = t;
        detailsTitle.setText(t.place());
        detailsBody.setText(detailsText(t));
        detailsGeofence.setVisibility(View.VISIBLE);
        detailsFaa.setVisibility(View.VISIBLE);
        detailsGeofence.setEnabled(!t.drawable().isEmpty());
        settingsPage.setVisibility(View.GONE);
        list.setVisibility(View.GONE);
        detailsPage.setVisibility(View.VISIBLE);
    }

    /** A station's observation, on the same details page a restriction uses. */
    public void showAirfield(final Metar m) {
        if (root == null || m == null)
            return;
        showing = null;
        detailsTitle.setText(m.icao);
        final StringBuilder b = new StringBuilder();
        b.append(m.name).append("\n\n");
        b.append(m.categoryLabel());
        if (!m.visibility.isEmpty())
            b.append("   visibility ").append(m.visibility).append(" sm");
        b.append('\n').append("Ceiling: ").append(m.ceilingLabel());
        if (!m.layers.isEmpty())
            b.append("\nCloud: ").append(android.text.TextUtils.join(", ", m.layers));
        b.append("\nWind: ").append(m.windLabel());
        final String temp = m.tempLabel();
        if (!temp.isEmpty())
            b.append("\nTemperature: ").append(temp);
        final String alt = m.altimeterLabel();
        if (!alt.isEmpty())
            b.append("\nAltimeter: ").append(alt);
        if (m.obsMs > 0)
            b.append("\n\nObserved ").append(local.format(new Date(m.obsMs)));
        if (!m.raw.isEmpty())
            b.append("\n\n").append(m.raw);
        detailsBody.setText(b.toString());
        // Gone, not greyed. A disabled button still reads as something this screen does
        // and you are being denied; a station simply has no geofence and no FAA page.
        detailsGeofence.setVisibility(View.GONE);
        detailsFaa.setVisibility(View.GONE);
        settingsPage.setVisibility(View.GONE);
        list.setVisibility(View.GONE);
        detailsPage.setVisibility(View.VISIBLE);
    }

    /**
     * A shelf of airspace, on the same details page.
     *
     * <p>Neither of the restriction's two buttons belongs here. A geofence off a Class B
     * shelf would alarm on every flight in a metro area, and there is no FAA notice page
     * for charted airspace -- it is on the sectional, not in a NOTAM.
     */
    public void showAirspace(final Airspace a) {
        if (root == null || a == null)
            return;
        showing = null;
        detailsTitle.setText(a.title());
        final StringBuilder b = new StringBuilder();
        b.append(airspaceKind(a));
        if (!a.ident.isEmpty())
            b.append("   ").append(a.ident);
        b.append("\n\n").append(a.heights()).append('\n');
        b.append("\nActive: ").append(AirspaceFeatures.hours(a.workHours)).append('\n');
        if (!a.localType.isEmpty())
            b.append("\n").append(a.localType).append('\n');

        // Where the operator stands in it, which is the question the layer exists for.
        final GeoPoint me = mapView.getSelfMarker() == null ? null
                : mapView.getSelfMarker().getPoint();
        final double myFt = TfrVertical.myFeetMsl(me);
        if (!Double.isNaN(myFt) && a.ceiling.present && !a.ceiling.surface) {
            final double head = a.ceiling.feet - myFt;
            if (head > 0)
                b.append("\nIts top is ").append(TfrVertical.feet(head))
                        .append(" above you.\n");
        }
        if (!Double.isNaN(myFt) && a.floor.present && !a.floor.surface) {
            final double below = a.floor.feet - myFt;
            if (below > 0)
                b.append("\nIts floor is ").append(TfrVertical.feet(below))
                        .append(" above you, so you are underneath it.\n");
        }
        b.append("\nCharted airspace from the FAA. No warranty of accuracy or")
                .append(" timeliness - always check current charts and NOTAMs.");
        detailsBody.setText(b.toString());
        detailsGeofence.setVisibility(View.GONE);
        detailsFaa.setVisibility(View.GONE);
        settingsPage.setVisibility(View.GONE);
        list.setVisibility(View.GONE);
        detailsPage.setVisibility(View.VISIBLE);
    }

    private static String airspaceKind(Airspace a) {
        if (a.isClass()) {
            final String c = a.classCode == null ? "" : a.classCode.trim();
            return c.isEmpty() ? "Airspace" : "Class " + c.toUpperCase(Locale.US);
        }
        return Airspace.setName(a.setKey());
    }

    /** One obstacle, on the same details page. Never ATAK's own metadata. */
    public void showObstacle(final Obstacle o) {
        if (root == null || o == null)
            return;
        showing = null;
        detailsTitle.setText(o.kind());
        final StringBuilder b = new StringBuilder();
        b.append(TfrVertical.comma((int) Math.round(o.aglFt)))
                .append(" ft above the ground\n");
        b.append(TfrVertical.comma((int) Math.round(o.amslFt))).append(" ft MSL at the top");
        if (o.quantity > 1)
            b.append("\n\n").append(o.quantity).append(" of them at this point");
        if (!o.city.isEmpty() || !o.state.isEmpty())
            b.append("\n\n").append(o.city).append(o.city.isEmpty() ? "" : ", ")
                    .append(o.state);
        b.append("\n\nLighting: ").append(o.lightingDetail());
        b.append("\nSurvey: ").append("O".equals(o.verified) ? "verified" : "unverified");
        b.append("\nFAA number: ").append(o.oas);

        final GeoPoint me = mapView.getSelfMarker() == null ? null
                : mapView.getSelfMarker().getPoint();
        final double myFt = TfrVertical.myFeetMsl(me);
        if (!Double.isNaN(myFt)) {
            final double over = o.amslFt - myFt;
            if (over > 0)
                b.append("\n\nIts top is ").append(TfrVertical.feet(over))
                        .append(" above you.");
            else
                b.append("\n\nYou are ").append(TfrVertical.feet(-over))
                        .append(" above its top.");
        }
        b.append("\n\nFAA Digital Obstacle File. Charting obstacles only - not every")
                .append(" tower, and no distribution lines.");
        detailsBody.setText(b.toString());
        detailsGeofence.setVisibility(View.GONE);
        detailsFaa.setVisibility(View.GONE);
        settingsPage.setVisibility(View.GONE);
        list.setVisibility(View.GONE);
        detailsPage.setVisibility(View.VISIBLE);
    }

    /**
     * One UAS grid square, on the same details page. Never ATAK's own radial.
     *
     * <p>The ceiling first and in the FAA's own words, because it is the whole reason
     * the square exists, and 0 gets a sentence rather than a number: a pilot reading
     * "0 ft" can take it for missing data, and it is the opposite -- a rule that stops
     * them.
     */
    public void showUasfm(final UasfmCell c) {
        if (root == null || c == null)
            return;
        showing = null;
        detailsTitle.setText(c.ceilingFt == 0 ? "No flight without coordination"
                : TfrVertical.comma(c.ceilingFt) + " ft AGL");
        final StringBuilder b = new StringBuilder();
        b.append(c.ceilingFt == 0
                ? "The FAA grants nothing automatically in this square. Flying here"
                        + " needs further coordination, and LAANC will not clear it.\n"
                : "You may fly up to " + TfrVertical.comma(c.ceilingFt)
                        + " ft above the ground here without an authorization.\n");
        if (!c.where().isEmpty())
            b.append("\nAirspace of: ").append(c.where()).append('\n');
        b.append("\nLAANC: ").append(c.laanc
                ? "covered - file in an app and it comes back in seconds"
                : "not covered - this one goes by request, not through an app");
        b.append("\n\nThe square is 30 arc-seconds, about half a nautical mile. The")
                .append(" grid covers controlled airspace only: where there is no")
                .append(" square you are in Class G and need no authorization.");
        b.append("\n\nFAA UAS Facility Map. It says what is permitted, not what is")
                .append(" there - check the restrictions, airspace and obstacle layers")
                .append(" too, and always current charts and NOTAMs.");
        detailsBody.setText(b.toString());
        detailsGeofence.setVisibility(View.GONE);
        detailsFaa.setVisibility(View.GONE);
        settingsPage.setVisibility(View.GONE);
        list.setVisibility(View.GONE);
        detailsPage.setVisibility(View.VISIBLE);
    }

    /**
     * One National Security UAS restriction, on the same details page. Never ATAK's
     * own radial.
     *
     * <p>The prohibition first and in plain words, then what the FAA publishes with
     * it: the facility, the base and who runs it, the heights, and the point of
     * contact as published, which is the number to call about it.
     */
    public void showNsufr(final Nsufr n) {
        if (root == null || n == null)
            return;
        showing = null;
        detailsTitle.setText(n.title());
        final StringBuilder b = new StringBuilder();
        if (Nsufr.PENDING.equals(n.status))
            b.append("Pending national security UAS restriction: announced by the FAA")
                    .append(" and not yet in force. When it is, no UAS may fly here.\n");
        else if (Nsufr.PART_TIME.equals(n.status))
            b.append("Part-time national security UAS restriction: when the facility")
                    .append(" activates it, no UAS may fly here, ").append(n.heights())
                    .append(".\n");
        else
            b.append("National security UAS restriction: no UAS may fly here, ")
                    .append(n.heights()).append(". There is no authorization to")
                    .append(" apply for; it is a prohibition under 14 CFR 99.7.\n");
        if (!n.alertType.isEmpty())
            b.append("\nActivation: ").append(n.alertType).append('\n');
        if (!n.adviseInstructions.isEmpty())
            b.append(n.adviseInstructions).append('\n');
        if (!n.adviseAuthority.isEmpty())
            b.append(n.adviseAuthority).append('\n');
        if (!n.adviseNote.isEmpty())
            b.append(n.adviseNote).append('\n');
        // The base, when the title is not already it (64 rows name the facility by the
        // base's name, and many name only the base).
        if (!n.base.isEmpty() && !n.base.equals(n.title()))
            b.append("\nBase: ").append(n.base).append('\n');
        if (!n.branchName().isEmpty())
            b.append("Proponent: ").append(n.branchName());
        if (!n.proponent.isEmpty() && !n.proponent.equalsIgnoreCase(n.branch))
            b.append(" (").append(n.proponent).append(')');
        if (!n.branchName().isEmpty())
            b.append('\n');
        if (!n.reason.isEmpty())
            b.append("Reason: ").append(n.reason).append('\n');
        if (!n.county.isEmpty() || !n.state.isEmpty())
            b.append("Where: ").append(n.county)
                    .append(n.county.isEmpty() || n.state.isEmpty() ? "" : " County, ")
                    .append(n.state).append('\n');
        if (!n.airspace.isEmpty())
            b.append("Airspace: ").append(n.airspace).append('\n');
        b.append("\nPoint of contact, as the FAA publishes it:\n")
                .append(n.poc.isEmpty() ? "none published" : n.poc).append('\n');
        if (!n.faaId.isEmpty())
            b.append("\nFAA ID ").append(n.faaId).append('\n');
        b.append("\nFAA National Security UAS Flight Restrictions, ").append(n.statusName()
                .toLowerCase(Locale.US)).append(". No warranty of accuracy or timeliness -")
                .append(" always check current charts and NOTAMs.");
        detailsBody.setText(b.toString());
        detailsGeofence.setVisibility(View.GONE);
        detailsFaa.setVisibility(View.GONE);
        settingsPage.setVisibility(View.GONE);
        list.setVisibility(View.GONE);
        detailsPage.setVisibility(View.VISIBLE);
    }

    private String detailsText(final Tfr t) {
        final StringBuilder b = new StringBuilder();
        if (!t.stateName.isEmpty())
            b.append(t.stateName).append('\n');
        b.append(TfrTypes.label(t.type)).append("   NOTAM ").append(t.notamId).append('\n');
        b.append('\n').append(window(t)).append('\n');
        if (t.hasNoMappedArea()) {
            // The restriction exists, it is listed, and there is nothing to draw. Saying
            // so is the point: one missing from the map reads as clear airspace.
            b.append("\nNo mapped area is published for this one. Read the notice below.\n");
        } else {
            for (TfrArea a : t.drawable()) {
                b.append('\n');
                if (!a.name.isEmpty())
                    b.append(a.name).append(": ");
                b.append(a.floor.label()).append(" to ").append(a.ceiling.label());
                if (a.radiusNm > 0)
                    b.append(String.format(Locale.US, "   (%.1f NM ring)", a.radiusNm));
            }
            b.append('\n');
        }
        final String vertical = verticalFromHere(t);
        if (vertical != null)
            b.append('\n').append(vertical).append('\n');

        if (!t.notamText.isEmpty())
            b.append('\n').append(t.notamText);

        return b.toString();
    }

    /**
     * Make a geofence from a restriction.
     *
     * <p>Which area first, when there is more than one -- an inner and an outer ring are
     * different airspace and fencing the wrong one is not obvious afterwards -- then
     * whether to carry the published floor and ceiling, then ATAK's own geofence screen
     * for everything else.
     */
    private void geofence(final Tfr t) {
        final List<TfrArea> areas = t.drawable();
        if (areas.isEmpty()) {
            new AlertDialog.Builder(mapView.getContext())
                    .setTitle(t.place())
                    .setMessage("This one has no mapped area, so there is no shape to fence."
                            + " Read the notice for what it covers.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        if (areas.size() == 1) {
            geofenceArea(t, areas.get(0));
            return;
        }
        final String[] labels = new String[areas.size()];
        for (int i = 0; i < areas.size(); i++) {
            final TfrArea a = areas.get(i);
            labels[i] = (a.name.isEmpty() ? "Area " + (i + 1) : a.name)
                    + "  " + a.floor.label() + " to " + a.ceiling.label();
        }
        final AlertDialog d = new AlertDialog.Builder(mapView.getContext())
                .setTitle("Which area")
                .setSingleChoiceItems(labels, -1, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dlg, int which) {
                        dlg.dismiss();
                        geofenceArea(t, areas.get(which));
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
        fromTop(d);
    }

    private void geofenceArea(final Tfr t, final TfrArea a) {
        final String limits = a.floor.label() + " to " + a.ceiling.label();
        new AlertDialog.Builder(mapView.getContext())
                .setTitle("Make a geofence")
                .setMessage("This makes a shape you own, from " + t.place()
                        + ", and opens ATAK's geofence settings for it.\n\n"
                        + "It is a copy taken now. If this restriction changes or is"
                        + " lifted, the geofence stays as it is and you remove it"
                        + " yourself.\n\n"
                        + "Use the published limits (" + limits + ") so it only alerts"
                        + " on something actually inside the airspace, or ground only to"
                        + " alert on anything crossing the area at any height.")
                .setPositiveButton("Use the limits", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        make(t, a, true);
                    }
                })
                .setNeutralButton("Ground only", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        make(t, a, false);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void make(Tfr t, TfrArea a, boolean withAltitude) {
        final com.atakmap.android.drawing.mapItems.DrawingShape shape =
                TfrGeofence.create(mapView, t, a, withAltitude);
        if (shape == null) {
            new AlertDialog.Builder(mapView.getContext())
                    .setMessage("That area could not be turned into a shape.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        TfrGeofence.edit(shape);
    }

    /**
     * The restriction said from where the operator is standing, which is the one form of
     * it they do not have to do arithmetic on.
     */
    private String verticalFromHere(Tfr t) {
        final double mine = myFeetMsl();
        final List<TfrArea> areas = t.drawable();
        if (areas.isEmpty())
            return null;
        final StringBuilder b = new StringBuilder();
        if (!Double.isNaN(mine))
            b.append("You are at ").append(TfrVertical.feet(mine)).append(" MSL.");
        final TfrArea here = TfrVertical.areaContaining(t, manager.self());
        final TfrArea subject = here != null ? here : tallest(areas);
        if (here != null) {
            if (b.length() > 0)
                b.append(' ');
            b.append("You are inside this area.");
        }
        final String head = TfrVertical.headroom(subject, mine);
        if (head != null) {
            if (b.length() > 0)
                b.append(' ');
            b.append("Its ceiling is ").append(head).append('.');
        }
        final String floor = TfrVertical.floorFromHere(subject, mine);
        if (floor != null) {
            if (b.length() > 0)
                b.append(' ');
            b.append(Character.toUpperCase(floor.charAt(0))).append(floor.substring(1))
                    .append('.');
        }
        return b.length() == 0 ? null : b.toString();
    }

    /**
     * Put a restriction in the middle of the map, at a zoom where it can be read.
     *
     * <p>This used to pan and never zoom, on the reasoning that the operator had chosen
     * that zoom. In the field that was wrong: a Go to from 50 miles out centers a
     * restriction you still cannot see and whose name is under the label gate, so the
     * tap appears to do nothing. It now lands at the Labels gate, which is the zoom the
     * operator already set as "close enough to show names", and backs off from there
     * only when the shape is too big to fit.
     */
    private void goTo(Tfr t) {
        final double[] c = TfrFeatures.center(t);
        if (c == null) {
            new AlertDialog.Builder(mapView.getContext())
                    .setTitle(t.place())
                    .setMessage("This one has no mapped area, so there is nowhere to go."
                            + " Open Details to read the notice.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        final GeoPoint at = new GeoPoint(c[0], c[1]);
        final double res = goToResolution(t);
        if (res <= 0) {
            // The scale could not be worked out; a pan is still better than nothing.
            mapView.getMapController().panTo(at, true);
            return;
        }
        double scale = mapView.mapResolutionAsMapScale(res);
        // Clamped without assuming which of the two is the larger number.
        final double lo = Math.min(mapView.getMinMapScale(), mapView.getMaxMapScale());
        final double hi = Math.max(mapView.getMinMapScale(), mapView.getMaxMapScale());
        scale = Math.max(lo, Math.min(hi, scale));
        mapView.getMapController().panZoomTo(at, scale, true);
    }

    /**
     * The map resolution a Go to should land on, in meters per pixel.
     *
     * <p>The target is the Labels gate, so the restriction arrives with its name on it.
     * That gate is stored as what ATAK's scale bar reads, not as a resolution, so the
     * conversion is taken from the live pair rather than from an assumed bar width:
     * the bar currently spans {@code barMeters} at {@code resolution}, so the bar is
     * {@code barMeters / resolution} pixels wide whatever ATAK decided to draw.
     *
     * @return meters per pixel, or 0 when the map cannot be measured right now
     */
    private double goToResolution(Tfr t) {
        final double nowRes = mapView.getMapResolution();
        final double nowBar = ScaleBar.meters(mapView);
        if (!(nowRes > 0) || !(nowBar > 0))
            return 0;
        final double barPixels = nowBar / nowRes;
        if (!(barPixels > 0))
            return 0;

        final long gate = manager.labelBarMeters();
        double res = (gate > 0 ? gate : 16093L) / barPixels;

        // A restriction bigger than that has to be shown whole instead; a Go to that
        // framed the middle of a 200 mile ring would be worse than the one before it.
        final double span = TfrFeatures.span(t);
        if (span > 0) {
            // The pane covers half the screen, so the shorter side is the honest budget.
            final int px = Math.min(mapView.getWidth(), mapView.getHeight());
            if (px > 0) {
                final double fit = span * 1.6d / px;   // leave the shape some margin
                if (fit > res)
                    res = fit;
            }
        }
        return res;
    }

    private void open(String url) {
        try {
            final Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mapView.getContext().startActivity(i);
        } catch (Exception e) {
            new AlertDialog.Builder(mapView.getContext())
                    .setMessage("No app on this device will open that link.")
                    .setPositiveButton("OK", null)
                    .show();
        }
    }

    /** Local and plain, never Zulu: the operator reads a clock, not a flight plan. */
    private String window(Tfr t) {
        final long now = System.currentTimeMillis();
        final StringBuilder b = new StringBuilder();
        b.append(t.isActive(now) ? "In effect now"
                : t.isUpcoming(now) ? "Scheduled" : "Not in effect");
        if (t.effectiveMs > 0)
            b.append("\nFrom ").append(local.format(new Date(t.effectiveMs)));
        b.append(t.expireMs > 0 ? "\nUntil " + local.format(new Date(t.expireMs))
                : "\nNo published end");
        if (t.windowApproximate)
            b.append("\n(the notice did not give a time zone this plugin knows, so these times are approximate)");
        return b.toString();
    }

    // ---- helpers ----

    static void setState(Resources res, Button b, String thing, boolean on) {
        b.setText(thing + (on ? " ON" : " OFF"));
        b.setTextColor(res.getColor(on ? R.color.state_on : R.color.state_off));
    }

    private static double distance(GeoPoint from, Tfr t) {
        double best = Double.MAX_VALUE;
        for (TfrArea a : t.areas)
            for (double[] p : a.ring)
                best = Math.min(best, GeoCalculations.distanceTo(from,
                        new GeoPoint(p[0], p[1])));
        return best;
    }

    /** One Settings row: a head naming the setting and its value, an arrow, a body. */
    private final class Fold {
        final Button head;
        final ImageButton chevron;
        final View body;
        final String pref;
        boolean open;

        Fold(View page, int headId, int chevronId, int bodyId, String pref) {
            head = page.findViewById(headId);
            chevron = page.findViewById(chevronId);
            body = page.findViewById(bodyId);
            this.pref = pref;
            open = prefs.getBoolean(pref, false);
            final View.OnClickListener flip = new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    open = !open;
                    prefs.edit().putBoolean(Fold.this.pref, open).apply();
                    show();
                }
            };
            head.setOnClickListener(flip);
            chevron.setOnClickListener(flip);
            show();
        }

        void label(String name, String value) {
            head.setText(value == null ? name : name + ": " + value);
        }

        void show() {
            chevron.setRotation(open ? 180f : 0f);
            body.setVisibility(open ? View.VISIBLE : View.GONE);
        }
    }

    private class RowAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int i) {
            return rows.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null)
                v = PluginLayoutInflater.inflate(pluginContext, R.layout.row_tfr, null);
            final Tfr t = rows.get(position);
            final long now = System.currentTimeMillis();
            final Resources res = pluginContext.getResources();

            // Map Depot's way: the row goes there and says so in green, with no Go
            // button taking up the width. Details keeps a button of its own, because a
            // row has only one tap to give.
            v.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View x) {
                    goTo(t);
                }
            });
            final Button details = v.findViewById(R.id.row_details);
            details.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View x) {
                    showDetails(t);
                }
            });

            ((TextView) v.findViewById(R.id.row_place)).setText(t.place());
            ((TextView) v.findViewById(R.id.row_limits)).setText(limits(t, myFeetMsl()));

            final TextView state = v.findViewById(R.id.row_state);
            final String type = TfrTypes.label(t.type);
            final String head;
            final int headColor;
            if (t.hasNoMappedArea()) {
                head = type + "   no mapped area";
                headColor = res.getColor(R.color.dim_text);
            } else if (t.isActive(now)) {
                head = type + "   ACTIVE";
                headColor = manager.activeColor();
            } else if (t.expireMs > 0 && now > t.expireMs) {
                // Past its published end. Never shown as merely "scheduled", which reads
                // as something that is still coming.
                head = type + "   EXPIRED";
                headColor = res.getColor(R.color.dim_text);
            } else {
                head = type + "   SCHEDULED";
                headColor = manager.upcomingColor();
            }
            // The hint on a line of its own and only the hint colored, the way Map Depot
            // does it: appended to the type it wraps wherever the width runs out rather
            // than where it reads well, and a whole green row is a christmas tree.
            if (t.hasNoMappedArea()) {
                state.setText(head);
                state.setTextColor(headColor);
            } else {
                final String hint = "\ntap to go there";
                final android.text.SpannableString line =
                        new android.text.SpannableString(head + hint);
                line.setSpan(new android.text.style.ForegroundColorSpan(headColor),
                        0, head.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                line.setSpan(new android.text.style.ForegroundColorSpan(
                        res.getColor(R.color.action_green)),
                        line.length() - hint.length() + 1, line.length(),
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                state.setText(line);
            }

            // The distance on the right, where Feature Layer puts it: it is what the list
            // is ordered by, so it should be readable down the edge in one pass.
            final double d = distance(manager.self(), t);
            ((TextView) v.findViewById(R.id.row_window)).setText(d < Double.MAX_VALUE
                    ? SpanUtilities.formatType(Units.type(), d, Span.METER) : "");
            return v;
        }
    }

    /**
     * The ceiling is the question a TFR is usually being asked, so the row leads with it,
     * and then says it again from where the operator is standing.
     */
    private static String limits(Tfr t, double myFeetMsl) {
        final List<TfrArea> areas = t.drawable();
        if (areas.isEmpty())
            return "no mapped area published";
        final TfrArea top = tallest(areas);
        final StringBuilder b = new StringBuilder();
        if (areas.size() == 1)
            b.append(top.floor.label()).append(" to ").append(top.ceiling.label());
        else
            b.append(areas.size()).append(" areas, up to ").append(top.ceiling.label());
        final String head = TfrVertical.headroom(top, myFeetMsl);
        if (head != null)
            b.append("   ").append(head);
        return b.toString();
    }

    private static TfrArea tallest(List<TfrArea> areas) {
        int highest = -1;
        TfrArea top = areas.get(0);
        for (TfrArea a : areas)
            if (a.ceiling.present && a.ceiling.feet > highest) {
                highest = a.ceiling.feet;
                top = a;
            }
        return top;
    }

    /** Where the operator is, vertically. Read once per render rather than per row. */
    private double myFeetMsl() {
        return TfrVertical.myFeetMsl(manager.self());
    }
}

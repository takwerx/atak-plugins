package com.atakmap.android.tfr.ui;

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
import com.atakmap.android.tfr.Tfr;
import com.atakmap.android.tfr.TfrArea;
import com.atakmap.android.tfr.TfrFeatures;
import com.atakmap.android.tfr.TfrGeofence;
import com.atakmap.android.tfr.Places;
import com.atakmap.android.tfr.TfrManager;
import com.atakmap.android.tfr.TfrTypes;
import com.atakmap.android.tfr.TfrVertical;
import com.atakmap.android.tfr.TfrWatch;
import com.atakmap.android.tfr.plugin.R;
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
    private Button switchButton, settingsButton, refreshButton, backButton;
    private TextView statusText, headingText;
    private LinearLayout settingsContainer, typesContainer, updatesContainer, keyBody;
    private Button useZoomButton, gateButton;
    private TextView gateNow, downloadedNote;
    private Fold gateFold, labelsFold, areaFold, whereFold, typesFold, keyFold, updatesFold,
            fencesFold;
    private Button labelsGateButton;
    private TextView labelsNote;
    private LinearLayout fencesContainer;
    private LinearLayout areaContainer, whereContainer;
    private TextView areaNote;

    private final List<Tfr> rows = new ArrayList<>();
    private RowAdapter adapter;
    private int inViewTotal;

    private final SimpleDateFormat local = new SimpleDateFormat("EEE d MMM HH:mm", Locale.US);

    public TfrPane(MapView mapView, Context pluginContext, TfrManager manager) {
        this.mapView = mapView;
        this.pluginContext = pluginContext;
        this.manager = manager;
        this.prefs = mapView.getContext().getSharedPreferences("tfr", Context.MODE_PRIVATE);
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

        switchButton = header.findViewById(R.id.btn_switch);
        settingsButton = header.findViewById(R.id.btn_settings);
        refreshButton = header.findViewById(R.id.btn_refresh);
        headingText = header.findViewById(R.id.list_heading);

        final View settings = PluginLayoutInflater.inflate(pluginContext,
                R.layout.settings_controls, null);
        settingsContainer.addView(settings);
        backButton = root.findViewById(R.id.btn_settings_back);
        typesContainer = settings.findViewById(R.id.types_container);
        updatesContainer = settings.findViewById(R.id.updates_container);
        keyBody = settings.findViewById(R.id.fold_key_body);
        useZoomButton = settings.findViewById(R.id.btn_use_zoom);
        gateButton = settings.findViewById(R.id.btn_gate);
        gateNow = settings.findViewById(R.id.gate_now);
        downloadedNote = settings.findViewById(R.id.downloaded_note);

        areaContainer = settings.findViewById(R.id.area_container);
        whereContainer = settings.findViewById(R.id.where_container);
        areaNote = settings.findViewById(R.id.area_note);

        labelsGateButton = settings.findViewById(R.id.btn_labels_gate);
        labelsNote = settings.findViewById(R.id.labels_note);
        fencesContainer = settings.findViewById(R.id.fences_container);

        gateFold = new Fold(settings, R.id.fold_gate_head, R.id.fold_gate_chev,
                R.id.fold_gate_body, "fold.gate");
        labelsFold = new Fold(settings, R.id.fold_labels_head, R.id.fold_labels_chev,
                R.id.fold_labels_body, "fold.labels");
        fencesFold = new Fold(settings, R.id.fold_fences_head, R.id.fold_fences_chev,
                R.id.fold_fences_body, "fold.fences");
        areaFold = new Fold(settings, R.id.fold_area_head, R.id.fold_area_chev,
                R.id.fold_area_body, "fold.area");
        whereFold = new Fold(settings, R.id.fold_where_head, R.id.fold_where_chev,
                R.id.fold_where_body, "fold.where");
        typesFold = new Fold(settings, R.id.fold_types_head, R.id.fold_types_chev,
                R.id.fold_types_body, "fold.types");
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
        switchButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                manager.setOn(!manager.isOn());
                render();
            }
        });
        settingsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSettings(true);
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
        useZoomButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                manager.setGateBarMeters((long) Math.round(manager.barMeters()));
                render();
            }
        });
        gateButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickGate();
            }
        });
        labelsGateButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickLabelGate();
            }
        });
        root.findViewById(R.id.btn_types_all_on).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                for (String[] c : manager.typeCounts())
                    manager.setTypeOn(c[0], true);
                render();
            }
        });
        root.findViewById(R.id.btn_types_all_off).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                for (String[] c : manager.typeCounts())
                    manager.setTypeOn(c[0], false);
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
        final Resources res = pluginContext.getResources();
        final boolean on = manager.isOn();

        if (manager.isSyncing()) {
            switchButton.setText("Loading...");
            switchButton.setTextColor(0xFFFFC107);
        } else {
            setState(res, switchButton, "TFR", on);
        }
        refreshButton.setEnabled(on && !manager.isSyncing());
        statusText.setText(manager.status());

        rebuildList();
        renderSettings();
    }

    private void rebuildList() {
        rows.clear();
        final List<Tfr> inView = manager.isOn() ? manager.inView() : new ArrayList<Tfr>();
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
        headingText.setText(manager.isOn() ? manager.listHeading() + " (" + inViewTotal + ")"
                : manager.listHeading());
        adapter.notifyDataSetChanged();
    }

    private void renderSettings() {
        gateFold.label("Zoom gate", manager.gateBarMeters() > 0
                ? gateLabel(manager.gateBarMeters()) + " or closer" : "Always");
        gateButton.setText(manager.gateBarMeters() > 0
                ? gateLabel(manager.gateBarMeters()) + " or closer" : "Always");
        gateNow.setText("Scale bar now " + ScaleBar.describe(manager.barMeters())
                + (manager.isGateHiding() ? " - hidden" : ""));

        labelsFold.label("Labels", manager.labelBarMeters() > 0
                ? gateLabel(manager.labelBarMeters()) + " or closer" : "Always");
        labelsGateButton.setText(manager.labelBarMeters() > 0
                ? gateLabel(manager.labelBarMeters()) + " or closer" : "Always");
        labelsNote.setText(manager.areLabelsHidden()
                ? "Hidden now. Scale bar reads " + ScaleBar.describe(manager.barMeters()) + "."
                : "Showing now.");

        buildFences();

        areaFold.label("Area", manager.areaLabel());
        buildArea();
        whereFold.label("Where", manager.whereLabel());
        buildWhere();

        final List<String[]> counts = manager.typeCounts();
        int onCount = 0;
        for (String[] c : counts)
            if (manager.isTypeOn(c[0]))
                onCount++;
        typesFold.label("Types", counts.isEmpty() ? "nothing downloaded yet"
                : onCount + " of " + counts.size());
        buildTypeTiles(counts);

        keyFold.label("Map key", null);
        buildKey();

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
        final List<TfrWatch.Watched> stale = manager.staleFences();
        fencesFold.label("Geofences", stale.isEmpty() ? "all current"
                : stale.size() + " out of date");
        fencesContainer.removeAllViews();
        if (stale.isEmpty()) {
            final TextView t = new TextView(pluginContext);
            t.setText("Every geofence you made still matches a live restriction.");
            t.setTextSize(13f);
            t.setTextColor(pluginContext.getResources().getColor(R.color.dim_text));
            fencesContainer.addView(t);
            return;
        }
        for (final TfrWatch.Watched w : stale) {
            final TextView t = new TextView(pluginContext);
            t.setText(w.title + " - " + TfrWatch.words(w.state));
            t.setTextSize(13f);
            t.setPadding(0, 6, 0, 2);
            t.setTextColor(pluginContext.getResources().getColor(R.color.white));
            fencesContainer.addView(t);

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

    /** How much of the picture the list covers. The map is governed by Where and Types. */
    private void buildArea() {
        areaContainer.removeAllViews();
        addChoice(areaContainer, "Everything", manager.areaMode() == TfrManager.AREA_EVERYWHERE,
                new Runnable() {
                    @Override
                    public void run() {
                        manager.setArea(TfrManager.AREA_EVERYWHERE, 0);
                    }
                });
        addChoice(areaContainer, "What is in view", manager.areaMode() == TfrManager.AREA_IN_VIEW,
                new Runnable() {
                    @Override
                    public void run() {
                        manager.setArea(TfrManager.AREA_IN_VIEW, 0);
                    }
                });
        addChoice(areaContainer, "Measuring from: " + manager.measureFromLabel(), false,
                new Runnable() {
                    @Override
                    public void run() {
                        manager.setMeasureFrom(manager.measureFrom() == TfrManager.FROM_ME
                                ? TfrManager.FROM_MAP_CENTER : TfrManager.FROM_ME);
                    }
                });
        addChoice(areaContainer, "Within " + ScaleBar.gate(manager.areaRadiusMeters())
                + (manager.hasFix() ? " of me" : " of the map center"),
                manager.areaMode() == TfrManager.AREA_RADIUS, new Runnable() {
                    @Override
                    public void run() {
                        pickRadius();
                    }
                });
        areaNote.setText(manager.hasFix() ? ""
                : "No GPS fix, measuring from the map center.");
        areaNote.setVisibility(manager.hasFix() ? View.GONE : View.VISIBLE);
    }

    /**
     * Where, said three ways. A state is the vocabulary the server-side feed already
     * uses, a region is how the FAA organizes itself, and a center is what the feed puts
     * on every row.
     */
    private void buildWhere() {
        whereContainer.removeAllViews();
        addChoice(whereContainer, "Everywhere", manager.whereMode() == TfrManager.WHERE_EVERYWHERE,
                new Runnable() {
                    @Override
                    public void run() {
                        manager.setWhere(TfrManager.WHERE_EVERYWHERE, null);
                    }
                });
        addChoice(whereContainer, "By state", manager.whereMode() == TfrManager.WHERE_STATE,
                new Runnable() {
                    @Override
                    public void run() {
                        pickWhere(TfrManager.WHERE_STATE, "Which states");
                    }
                });
        addChoice(whereContainer, "By FAA region", manager.whereMode() == TfrManager.WHERE_REGION,
                new Runnable() {
                    @Override
                    public void run() {
                        pickWhere(TfrManager.WHERE_REGION, "Which FAA regions");
                    }
                });
        addChoice(whereContainer, "By center", manager.whereMode() == TfrManager.WHERE_CENTER,
                new Runnable() {
                    @Override
                    public void run() {
                        pickWhere(TfrManager.WHERE_CENTER, "Which centers");
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

    /** A filter states what it will cost before it is used, so every row carries its count. */
    private void buildTypeTiles(List<String[]> counts) {
        typesContainer.removeAllViews();
        final Resources res = pluginContext.getResources();
        for (final String[] c : counts) {
            final Button b = new Button(pluginContext, null, 0, R.style.TakwerxButton);
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = 4;
            b.setLayoutParams(lp);
            setState(res, b, TfrTypes.label(c[0]) + " (" + c[1] + ")", manager.isTypeOn(c[0]));
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    manager.setTypeOn(c[0], !manager.isTypeOn(c[0]));
                    render();
                }
            });
            typesContainer.addView(b);
        }
    }

    /** Built from the same colors the map draws with, so the two cannot drift. */
    private void buildKey() {
        keyBody.removeAllViews();
        addKeyLine("In effect now", manager.activeColor());
        addKeyLine("Scheduled, not yet in effect", manager.upcomingColor());
        final int noArea = countWithoutArea();
        if (noArea > 0)
            addKeyLine(noArea + (noArea == 1 ? " restriction has" : " restrictions have")
                    + " no mapped area and is listed only", 0x00000000);
    }

    private void addKeyLine(String text, int color) {
        final TextView t = new TextView(pluginContext);
        t.setText(text);
        t.setTextSize(13f);
        t.setPadding(0, 4, 0, 4);
        t.setTextColor(color == 0 ? pluginContext.getResources().getColor(R.color.dim_text) : color);
        keyBody.addView(t);
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
        detailsGeofence.setEnabled(!t.drawable().isEmpty());
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
     * Put a restriction in the middle of the map.
     *
     * <p>Pans and never zooms: the operator chose that zoom, and a Go to that also
     * rescaled the map would throw away the view they were working in.
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
        mapView.getMapController().panTo(new GeoPoint(c[0], c[1]), true);
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

            // Feature Layer's way round, which is what the operator pointed at: the row
            // opens details, the Go button moves the map.
            v.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View x) {
                    showDetails(t);
                }
            });
            final Button goTo = v.findViewById(R.id.row_goto);
            goTo.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View x) {
                    goTo(t);
                }
            });

            ((TextView) v.findViewById(R.id.row_place)).setText(t.place());
            ((TextView) v.findViewById(R.id.row_limits)).setText(limits(t, myFeetMsl()));

            final TextView state = v.findViewById(R.id.row_state);
            final String type = TfrTypes.label(t.type);
            if (t.hasNoMappedArea()) {
                state.setText(type + "   no mapped area");
                state.setTextColor(res.getColor(R.color.dim_text));
            } else if (t.isActive(now)) {
                state.setText(type + "   ACTIVE");
                state.setTextColor(manager.activeColor());
            } else if (t.expireMs > 0 && now > t.expireMs) {
                // Past its published end. Never shown as merely "scheduled", which reads
                // as something that is still coming.
                state.setText(type + "   EXPIRED");
                state.setTextColor(res.getColor(R.color.dim_text));
            } else {
                state.setText(type + "   SCHEDULED");
                state.setTextColor(manager.upcomingColor());
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

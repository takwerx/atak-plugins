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
import com.atakmap.android.tfr.TfrManager;
import com.atakmap.android.tfr.TfrTypes;
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
import java.util.List;
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
    private View header, settingsPage;
    private Button switchButton, settingsButton, refreshButton, backButton;
    private TextView statusText, headingText;
    private LinearLayout settingsContainer, typesContainer, updatesContainer, keyBody;
    private Button useZoomButton, gateButton;
    private TextView gateNow, downloadedNote;
    private Fold gateFold, typesFold, keyFold, updatesFold;

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

        gateFold = new Fold(settings, R.id.fold_gate_head, R.id.fold_gate_chev,
                R.id.fold_gate_body, "fold.gate");
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
        showSettings(false);
        render();
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
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View v, int position, long id) {
                final int index = position - list.getHeaderViewsCount();
                if (index >= 0 && index < rows.size())
                    showDetails(rows.get(index));
            }
        });
    }

    private void showSettings(boolean on) {
        settingsPage.setVisibility(on ? View.VISIBLE : View.GONE);
        list.setVisibility(on ? View.GONE : View.VISIBLE);
        if (on)
            render();
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
        final GeoPoint from = mapView.getPoint().get();
        Collections.sort(inView, new Comparator<Tfr>() {
            @Override
            public int compare(Tfr a, Tfr b) {
                return Double.compare(distance(from, a), distance(from, b));
            }
        });
        for (int i = 0; i < inView.size() && i < LIST_CAP; i++)
            rows.add(inView.get(i));
        headingText.setText(manager.isOn()
                ? "Restrictions in view (" + inViewTotal + ")"
                : "Restrictions in view");
        adapter.notifyDataSetChanged();
    }

    private void renderSettings() {
        gateFold.label("Zoom gate", manager.gateBarMeters() > 0
                ? ScaleBar.describe(manager.gateBarMeters()) + " or closer" : "Always");
        gateButton.setText(manager.gateBarMeters() > 0
                ? ScaleBar.describe(manager.gateBarMeters()) + " or closer" : "Always");
        gateNow.setText("Scale bar now " + ScaleBar.describe(manager.barMeters())
                + (manager.isGateHiding() ? " - hidden" : ""));

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

    // ---- details ----

    public void showDetails(final Tfr t) {
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
        if (!t.notamText.isEmpty())
            b.append('\n').append(t.notamText);

        new AlertDialog.Builder(mapView.getContext())
                .setTitle(t.place())
                .setMessage(b.toString())
                .setPositiveButton("Close", null)
                .setNeutralButton("FAA page", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        open(t.faaPageUrl());
                    }
                })
                .show();
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

            ((TextView) v.findViewById(R.id.row_place)).setText(t.place());
            final TextView state = v.findViewById(R.id.row_state);
            if (t.hasNoMappedArea()) {
                state.setText("no area");
                state.setTextColor(res.getColor(R.color.dim_text));
            } else if (t.isActive(now)) {
                state.setText("active");
                state.setTextColor(manager.activeColor());
            } else {
                state.setText("scheduled");
                state.setTextColor(manager.upcomingColor());
            }

            ((TextView) v.findViewById(R.id.row_limits)).setText(limits(t));
            final double d = distance(mapView.getPoint().get(), t);
            ((TextView) v.findViewById(R.id.row_window)).setText(TfrTypes.label(t.type)
                    + (d < Double.MAX_VALUE
                            ? "   " + SpanUtilities.formatType(Units.type(), d, Span.METER)
                            : ""));
            return v;
        }
    }

    /** The ceiling is the question a TFR is usually being asked, so the row leads with it. */
    private static String limits(Tfr t) {
        final List<TfrArea> areas = t.drawable();
        if (areas.isEmpty())
            return "no mapped area published";
        if (areas.size() == 1)
            return areas.get(0).floor.label() + " to " + areas.get(0).ceiling.label();
        int highest = 0;
        TfrArea top = areas.get(0);
        for (TfrArea a : areas)
            if (a.ceiling.present && a.ceiling.feet > highest) {
                highest = a.ceiling.feet;
                top = a;
            }
        return areas.size() + " areas, up to " + top.ceiling.label();
    }
}

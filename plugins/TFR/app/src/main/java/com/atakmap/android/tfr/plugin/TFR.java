package com.atakmap.android.tfr.plugin;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import com.atak.plugins.impl.PluginContextProvider;
import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.tfr.Tfr;
import com.atakmap.android.tfr.TfrArea;
import com.atakmap.android.tfr.TfrManager;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import gov.tak.api.plugin.IPlugin;
import gov.tak.api.plugin.IServiceController;
import gov.tak.api.ui.IHostUIService;
import gov.tak.api.ui.Pane;
import gov.tak.api.ui.PaneBuilder;
import gov.tak.api.ui.ToolbarItem;
import gov.tak.api.ui.ToolbarItemAdapter;
import gov.tak.platform.marshal.MarshalManager;

public class TFR implements IPlugin, TfrManager.Listener {

    IServiceController serviceController;
    Context pluginContext;
    IHostUIService uiService;
    ToolbarItem toolbarItem;
    Pane pane;

    private MapView mapView;
    private TfrManager manager;

    private View paneView;
    private ListView list;
    private View header;
    private Button switchButton, typesButton, refreshButton;
    private TextView statusText, headingText, noteText;
    private final List<Tfr> rows = new ArrayList<>();
    private RowAdapter adapter;

    /** Aviation reads Zulu, so the window is shown in it and says so. */
    private final SimpleDateFormat zulu = new SimpleDateFormat("dd MMM HH:mm", Locale.US);

    public TFR(IServiceController serviceController) {
        this.serviceController = serviceController;
        final PluginContextProvider ctxProvider = serviceController
                .getService(PluginContextProvider.class);
        if (ctxProvider != null) {
            pluginContext = ctxProvider.getPluginContext();
            pluginContext.setTheme(R.style.ATAKPluginTheme);
        }
        uiService = serviceController.getService(IHostUIService.class);
        zulu.setTimeZone(TimeZone.getTimeZone("UTC"));

        toolbarItem = new ToolbarItem.Builder(
                pluginContext.getString(R.string.app_name),
                MarshalManager.marshal(
                        pluginContext.getResources().getDrawable(R.drawable.ic_toolbar),
                        android.graphics.drawable.Drawable.class,
                        gov.tak.api.commons.graphics.Bitmap.class))
                .setListener(new ToolbarItemAdapter() {
                    @Override
                    public void onClick(ToolbarItem item) {
                        showPane();
                    }
                }).setIdentifier(pluginContext.getPackageName())
                .build();
    }

    @Override
    public void onStart() {
        if (uiService == null)
            return;
        mapView = MapView.getMapView();
        if (mapView != null) {
            manager = new TfrManager(mapView, pluginContext);
            manager.setListener(this);
            manager.start();
        }
        uiService.addToolbarItem(toolbarItem);
    }

    @Override
    public void onStop() {
        if (manager != null) {
            // Everything this plugin drew comes off with it. A TFR left on the map by a plugin
            // that is no longer running is airspace nobody is keeping current.
            manager.dispose();
            manager = null;
        }
        if (uiService == null)
            return;
        uiService.removeToolbarItem(toolbarItem);
    }

    // ---- the pane ----

    private void showPane() {
        if (pane == null) {
            paneView = PluginLayoutInflater.inflate(pluginContext, R.layout.main_layout, null);
            list = paneView.findViewById(R.id.tfr_list);
            header = PluginLayoutInflater.inflate(pluginContext, R.layout.pane_header, null);
            // The controls ride in the list's header rather than above it, so the pane is one
            // scroller. Click positions are then offset by the header count.
            list.addHeaderView(header, null, false);
            adapter = new RowAdapter();
            list.setAdapter(adapter);
            wire();

            pane = new PaneBuilder(paneView)
                    .setMetaValue(Pane.RELATIVE_LOCATION, Pane.Location.Default)
                    .setMetaValue(Pane.PREFERRED_WIDTH_RATIO, 0.5D)
                    .setMetaValue(Pane.PREFERRED_HEIGHT_RATIO, 0.5D)
                    .build();
        }
        render();
        if (!uiService.isPaneVisible(pane))
            uiService.showPane(pane, null);
    }

    private void wire() {
        switchButton = header.findViewById(R.id.tfr_switch);
        typesButton = header.findViewById(R.id.tfr_types);
        refreshButton = header.findViewById(R.id.tfr_refresh);
        statusText = header.findViewById(R.id.tfr_status);
        headingText = header.findViewById(R.id.tfr_heading);
        noteText = header.findViewById(R.id.tfr_note);

        switchButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (manager != null)
                    manager.setOn(!manager.isOn());
                render();
            }
        });
        refreshButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (manager != null)
                    manager.syncNow();
            }
        });
        typesButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showTypes();
            }
        });
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View v, int position, long id) {
                int index = position - list.getHeaderViewsCount();
                if (index >= 0 && index < rows.size())
                    showDetails(rows.get(index));
            }
        });
    }

    @Override
    public void onChanged() {
        render();
    }

    private void render() {
        if (manager == null || paneView == null)
            return;
        boolean on = manager.isOn();
        switchButton.setText(on ? R.string.state_on_label : R.string.state_off_label);
        switchButton.setTextColor(pluginContext.getResources()
                .getColor(on ? R.color.state_on : R.color.state_off));
        statusText.setText(manager.status());
        typesButton.setEnabled(on);
        refreshButton.setEnabled(on && !manager.isSyncing());

        rows.clear();
        if (on)
            rows.addAll(manager.inView());
        headingText.setText(on ? "in view (" + rows.size() + ")" : "in view");

        // Say what is drawn but not listed. A list that shows a fraction of the map without
        // saying so reads as the whole picture.
        int shown = on ? manager.shown().size() : 0;
        if (on && shown > rows.size()) {
            noteText.setText((shown - rows.size()) + " more on the map outside this view");
            noteText.setVisibility(View.VISIBLE);
        } else if (on && shown == 0 && !manager.isSyncing()) {
            noteText.setText("nothing to show yet");
            noteText.setVisibility(View.VISIBLE);
        } else {
            noteText.setVisibility(View.GONE);
        }
        adapter.notifyDataSetChanged();
    }

    /** A filter states what it will cost before it is used, so every row carries its count. */
    private void showTypes() {
        if (manager == null || mapView == null)
            return;
        final List<String[]> counts = manager.typeCounts();
        if (counts.isEmpty())
            return;
        final String[] labels = new String[counts.size()];
        final boolean[] checked = new boolean[counts.size()];
        for (int i = 0; i < counts.size(); i++) {
            String type = counts.get(i)[0];
            labels[i] = pretty(type) + " (" + counts.get(i)[1] + ")";
            checked[i] = manager.isTypeOn(type);
        }
        // MapView context, never the plugin context: a dialog built on the plugin context is a
        // BadTokenException and ATAK dies.
        new AlertDialog.Builder(mapView.getContext())
                .setTitle("Show which types")
                .setMultiChoiceItems(labels, checked,
                        new DialogInterface.OnMultiChoiceClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which, boolean isChecked) {
                                manager.setTypeOn(counts.get(which)[0], isChecked);
                            }
                        })
                .setPositiveButton("Done", null)
                .show();
    }

    private void showDetails(final Tfr t) {
        if (mapView == null)
            return;
        StringBuilder b = new StringBuilder();
        b.append(t.place());
        if (!t.stateName.isEmpty())
            b.append(", ").append(t.stateName);
        b.append("\n").append(pretty(t.type)).append("   NOTAM ").append(t.notamId);
        b.append("\n\n").append(window(t)).append('\n');
        if (t.hasNoMappedArea()) {
            // The TFR exists, it is listed, and there is nothing to draw. Saying so is the
            // whole point: a restriction missing from the map reads as clear airspace.
            b.append("\nNo mapped area is published for this one. Read the NOTAM below.\n");
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
            b.append("\n").append(t.notamText);

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
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mapView.getContext().startActivity(i);
        } catch (Exception e) {
            new AlertDialog.Builder(mapView.getContext())
                    .setMessage("No app on this device will open that link.")
                    .setPositiveButton("OK", null)
                    .show();
        }
    }

    /** "AIR SHOWS/SPORTS" is how the FAA writes it; it is not how anybody reads it. */
    private static String pretty(String type) {
        if (type == null || type.isEmpty())
            return "Other";
        String s = type.toLowerCase(Locale.US).replace('/', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private String window(Tfr t) {
        long now = System.currentTimeMillis();
        StringBuilder b = new StringBuilder();
        if (t.isActive(now))
            b.append("In effect now");
        else if (t.isUpcoming(now))
            b.append("Scheduled");
        else
            b.append("Not in effect");
        if (t.effectiveMs > 0)
            b.append("   from ").append(zulu.format(new Date(t.effectiveMs))).append('Z');
        b.append(t.expireMs > 0
                ? "   until " + zulu.format(new Date(t.expireMs)) + "Z"
                : "   no published end");
        if (t.windowApproximate)
            b.append("\n(the NOTAM's time zone was not one this plugin knows, so the window is approximate)");
        return b.toString();
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
            Tfr t = rows.get(position);
            long now = System.currentTimeMillis();

            ((TextView) v.findViewById(R.id.row_place)).setText(t.place());

            TextView state = v.findViewById(R.id.row_state);
            if (t.hasNoMappedArea()) {
                state.setText("no area");
                state.setTextColor(pluginContext.getResources().getColor(R.color.dim_text));
            } else if (t.isActive(now)) {
                state.setText("active");
                state.setTextColor(pluginContext.getResources().getColor(R.color.tfr_active));
            } else {
                state.setText("scheduled");
                state.setTextColor(pluginContext.getResources().getColor(R.color.tfr_upcoming));
            }

            ((TextView) v.findViewById(R.id.row_limits)).setText(limits(t));
            ((TextView) v.findViewById(R.id.row_window)).setText(pretty(t.type)
                    + (t.expireMs > 0 ? "   until " + zulu.format(new Date(t.expireMs)) + "Z" : ""));
            return v;
        }
    }

    /** The ceiling is the question a TFR is usually being asked, so the row leads with it. */
    private static String limits(Tfr t) {
        List<TfrArea> areas = t.drawable();
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

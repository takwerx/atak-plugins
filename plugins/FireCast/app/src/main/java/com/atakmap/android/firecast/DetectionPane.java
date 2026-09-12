package com.atakmap.android.firecast;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.firecast.plugin.R;
import com.atakmap.android.maps.MapView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The side pane: what is on the map, what is being filtered out, and the detections in
 * front of the operator right now.
 *
 * <p>Two rules from other takwerx plugins are doing real work here. Every filter states
 * what it costs on its own face - "VIIRS (1,013)", never "VIIRS" - so nobody has to
 * toggle a control and watch a total move to find out what it does. And the list is
 * what is in the map view, not everything fetched: a list of a thousand detections two
 * counties away is not a list.
 */
public class DetectionPane implements DetectionFeed.Listener {

    /** Most rows in the list. Past this the list stops being readable long before it stops being slow. */
    private static final int MAX_ROWS = 300;

    private final MapView mapView;
    private final Context pluginContext;
    private final DetectionFeed feed;
    private final View root;
    private final View header;
    private final ListView list;
    private final Adapter adapter = new Adapter();
    private final List<Button> sourceButtons = new ArrayList<>();

    private List<HeatDetection> rows = new ArrayList<>();

    public DetectionPane(MapView mapView, Context pluginContext, DetectionFeed feed) {
        this.mapView = mapView;
        this.pluginContext = pluginContext;
        this.feed = feed;
        this.root = PluginLayoutInflater.inflate(pluginContext, R.layout.main_layout, null);
        this.list = (ListView) root;
        this.header = PluginLayoutInflater.inflate(pluginContext, R.layout.pane_header, null);
        list.addHeaderView(header, null, false);
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View v, int position, long id) {
                // The header is row 0, so every list position is shifted by it.
                final int i = position - list.getHeaderViewsCount();
                if (i >= 0 && i < rows.size())
                    open(rows.get(i));
            }
        });
        buildSourceButtons();
        wireFilters();
        refreshUi();
    }

    public View view() {
        return root;
    }

    @Override
    public void onChanged() {
        refreshUi();
    }

    private void open(HeatDetection d) {
        feed.goTo(d);
        final android.content.Intent i = new android.content.Intent(DetectionFeed.ACTION_DETAILS);
        i.putExtra("detectionUid", d.uid());
        com.atakmap.android.ipc.AtakBroadcast.getInstance().sendBroadcast(i);
    }

    // ---- controls ----------------------------------------------------------------

    private void buildSourceButtons() {
        final LinearLayout holder = header.findViewById(R.id.source_buttons);
        holder.removeAllViews();
        sourceButtons.clear();
        for (final DetectionSource s : feed.sources()) {
            final Button b = (Button) PluginLayoutInflater.inflate(pluginContext, R.layout.source_button, null);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    final boolean on = !feed.filters.isOn(s.id());
                    feed.filters.setOn(s.id(), on);
                    refreshUi();
                    // Turning a feed on needs its data; turning one off only needs a redraw.
                    if (on)
                        feed.refresh();
                    else
                        feed.redraw();
                }
            });
            b.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    new AlertDialog.Builder(mapView.getContext())
                            .setTitle(s.title())
                            .setMessage(s.summary())
                            .setPositiveButton("Close", null)
                            .show();
                    return true;
                }
            });
            holder.addView(b);
            sourceButtons.add(b);
        }
    }

    private void wireFilters() {
        header.findViewById(R.id.btn_window).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String[] labels = new String[Filters.WINDOW_CHOICES.length];
                int current = 0;
                for (int i = 0; i < labels.length; i++) {
                    final int h = Filters.WINDOW_CHOICES[i];
                    labels[i] = h >= 48 ? "Last " + (h / 24) + " days" : "Last " + h + " hours";
                    if (h == feed.filters.windowHours)
                        current = i;
                }
                choose("How far back", labels, current, new Chosen() {
                    @Override
                    public void at(int i) {
                        feed.filters.windowHours = Filters.WINDOW_CHOICES[i];
                        feed.filters.save();
                        refreshUi();
                        // A longer window is data we do not have yet; a shorter one is not.
                        feed.refresh();
                    }
                });
            }
        });
        header.findViewById(R.id.btn_confidence).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                choose("Confidence", new String[] {
                        "Any confidence", "Nominal and high", "High only" },
                        feed.filters.minConfidence, new Chosen() {
                            @Override
                            public void at(int i) {
                                feed.filters.minConfidence = i;
                                feed.filters.save();
                                refreshUi();
                                feed.redraw();
                            }
                        });
            }
        });
        header.findViewById(R.id.btn_industrial).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final int current = feed.filters.showMaskAreas ? 2 : feed.filters.industrial;
                choose("Known industrial heat sources", new String[] {
                        "Hide them", "Flag them, keep them on the map", "Flag them and outline the masked ground" },
                        current, new Chosen() {
                            @Override
                            public void at(int i) {
                                feed.filters.industrial = i == 0 ? Filters.INDUSTRIAL_HIDE : Filters.INDUSTRIAL_FLAG;
                                feed.filters.showMaskAreas = i == 2;
                                feed.filters.save();
                                refreshUi();
                                feed.redraw();
                            }
                        });
            }
        });
        header.findViewById(R.id.btn_refresh_every).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String[] labels = new String[Filters.REFRESH_CHOICES.length];
                int current = 0;
                for (int i = 0; i < labels.length; i++) {
                    final int m = Filters.REFRESH_CHOICES[i];
                    labels[i] = m == 0 ? "Only when I ask" : "Every " + m + " minutes";
                    if (m == feed.filters.refreshMinutes)
                        current = i;
                }
                choose("Refresh", labels, current, new Chosen() {
                    @Override
                    public void at(int i) {
                        feed.filters.refreshMinutes = Filters.REFRESH_CHOICES[i];
                        feed.filters.save();
                        refreshUi();
                    }
                });
            }
        });
        header.findViewById(R.id.btn_refresh).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                feed.refresh();
            }
        });
    }

    private interface Chosen {
        void at(int index);
    }

    /**
     * A single-choice dialog on the MapView's context. Never a Spinner and never the
     * plugin context: a Spinner's dropdown is a Dialog built from the context that
     * inflated the view, and on a plugin context that is a BadTokenException that takes
     * ATAK down with it.
     */
    private void choose(String title, String[] labels, int current, final Chosen chosen) {
        new AlertDialog.Builder(mapView.getContext())
                .setTitle(title)
                .setSingleChoiceItems(labels, current, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        chosen.at(which);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---- state on screen ----------------------------------------------------------

    public void refreshUi() {
        final long now = System.currentTimeMillis();
        for (int i = 0; i < sourceButtons.size() && i < feed.sources().size(); i++) {
            final DetectionSource s = feed.sources().get(i);
            final Button b = sourceButtons.get(i);
            final boolean on = feed.filters.isOn(s.id());
            final int available = feed.availableFrom(s.id());
            final int got = feed.fetchedFrom(s.id());
            final StringBuilder label = new StringBuilder(s.title());
            if (on && available >= 0) {
                label.append("  ").append(count(available));
                if (got >= 0 && got < available)
                    label.append(", showing ").append(count(got));
            }
            label.append(on ? "   ON" : "   OFF");
            b.setText(label.toString());
            // Green is showing, red is not - the same reading as every other takwerx
            // plugin's toggles, on the text rather than a colored button face.
            b.setTextColor(on ? 0xFF4CD964 : 0xFFFF5A5A);
        }

        ((Button) header.findViewById(R.id.btn_window)).setText("Last " + feed.filters.windowLabel());
        ((Button) header.findViewById(R.id.btn_confidence)).setText(feed.filters.confidenceLabel());
        ((Button) header.findViewById(R.id.btn_industrial)).setText(
                feed.filters.showMaskAreas ? "Industrial outlined" : feed.filters.industrialLabel());
        ((Button) header.findViewById(R.id.btn_refresh_every)).setText(feed.filters.refreshLabel());

        final TextView status = header.findViewById(R.id.status);
        final TextView detail = header.findViewById(R.id.status_detail);
        if (feed.refreshing) {
            status.setText("Refreshing…");
        } else if (feed.lastRefresh <= 0) {
            status.setText("Nothing fetched yet");
        } else {
            status.setText(count(feed.shownNow()) + " detections on the map");
        }
        status.setTextColor(Color.WHITE);

        final StringBuilder d = new StringBuilder();
        if (feed.lastRefresh > 0)
            d.append("Fetched ").append(elapsed(now - feed.lastRefresh)).append(" ago");
        if (feed.box != null)
            d.append(d.length() == 0 ? "For " : ", for ").append("the area on screen");
        if (feed.clamped)
            d.append(". The map is zoomed out past the area a fetch covers, so this is the middle of the view");
        if (feed.capped)
            d.append(". More were available than are drawn - zoom in to see all of them");
        if (feed.status != null && feed.status.startsWith("no update"))
            d.append(". Last try failed, so this is the previous set: ").append(feed.status.substring(9).trim());
        else if (feed.status != null && feed.status.startsWith("partial"))
            d.append(". ").append(feed.status);
        detail.setText(d.toString());

        ((TextView) header.findViewById(R.id.legend)).setText(
                "Color is age: red within the hour, through orange and amber to gray past a day. "
                        + "Each box is the ground that detection covers - the fire is somewhere inside it. "
                        + "Dashed violet is a known industrial heat source.");

        rows = feed.inView(MAX_ROWS);
        final int inView = feed.inViewCount();
        final TextView heading = header.findViewById(R.id.list_heading);
        if (feed.fetchedTotal() == 0)
            heading.setText("In view");
        else if (inView == 0)
            heading.setText("In view — none here; pan or widen the time window");
        else if (inView > rows.size())
            heading.setText("In view — newest " + count(rows.size()) + " of " + count(inView) + ", zoom in to narrow");
        else
            heading.setText("In view — " + count(inView));
        adapter.notifyDataSetChanged();
    }

    private static String count(int n) {
        return String.format(Locale.US, "%,d", n);
    }

    private static String elapsed(long ms) {
        final long mins = ms / 60000L;
        if (mins < 1)
            return "just now";
        if (mins < 60)
            return mins + " min";
        return (mins / 60) + " h " + String.format(Locale.US, "%02d", mins % 60);
    }

    private class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null)
                v = PluginLayoutInflater.inflate(pluginContext, R.layout.detection_row, null);
            final HeatDetection d = rows.get(position);
            final long now = System.currentTimeMillis();
            final long age = d.ageMillis(now);
            v.findViewById(R.id.row_age_chip)
                    .setBackgroundColor(0xFF000000 | DetectionStyles.ageColor(age));
            final StringBuilder title = new StringBuilder();
            if (d.incidentName != null)
                title.append(d.incidentName).append(" · ");
            title.append(d.sourceTitle);
            if (d.suspect())
                title.append("  (industrial)");
            ((TextView) v.findViewById(R.id.row_title)).setText(title.toString());
            final StringBuilder sub = new StringBuilder(d.footprint.sizeText());
            if (d.frpMw != null)
                sub.append(" · ").append(d.frpText());
            sub.append(" · ").append(d.sensor);
            ((TextView) v.findViewById(R.id.row_detail)).setText(sub.toString());
            ((TextView) v.findViewById(R.id.row_age)).setText(d.ageText(now));
            return v;
        }
    }
}

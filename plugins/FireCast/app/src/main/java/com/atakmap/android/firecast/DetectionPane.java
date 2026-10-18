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
public class DetectionPane implements DetectionFeed.Listener, FirisModels.Listener {

    /** Most rows in the list. Past this the list stops being readable long before it stops being slow. */
    private static final int MAX_ROWS = 300;

    private final MapView mapView;
    private final Context pluginContext;
    private final DetectionFeed feed;
    private final FirisModels firis;
    private final View root;
    private final View header;
    private final ListView list;
    private final Adapter adapter = new Adapter();
    private final List<Button> sourceButtons = new ArrayList<>();

    private List<HeatDetection> rows = new ArrayList<>();
    private final android.content.SharedPreferences prefs;
    private Fold feeds, filters, key;

    /**
     * One drop-down row: a head reading "Name: value", an arrow, and a body hidden until
     * opened. Open or closed is remembered per row, closed to begin with, so the pane
     * opens as a summary with the list right under it (plugin-ui skill).
     */
    private final class Fold {
        final Button head;
        final android.widget.ImageButton chevron;
        final View body;
        final String pref;
        boolean open;

        Fold(int headId, int chevronId, int bodyId, String pref) {
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

    public DetectionPane(MapView mapView, Context pluginContext, DetectionFeed feed, FirisModels firis) {
        this.mapView = mapView;
        this.pluginContext = pluginContext;
        this.feed = feed;
        this.firis = firis;
        this.root = PluginLayoutInflater.inflate(pluginContext, R.layout.main_layout, null);
        this.list = (ListView) root;
        this.header = PluginLayoutInflater.inflate(pluginContext, R.layout.pane_header, null);
        this.prefs = mapView.getContext().getSharedPreferences("firecast", Context.MODE_PRIVATE);
        feeds = new Fold(R.id.fold_feeds_head, R.id.fold_feeds_chev, R.id.source_buttons, "fold.feeds");
        filters = new Fold(R.id.fold_filters_head, R.id.fold_filters_chev, R.id.fold_filters_body, "fold.filters");
        key = new Fold(R.id.fold_key_head, R.id.fold_key_chev, R.id.legend, "fold.key");
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
        buildKey();
        wireFilters();
        wireFiris();
        refreshUi();
    }

    // ---- FIRIS model runs ---------------------------------------------------------

    private void wireFiris() {
        final Button b = header.findViewById(R.id.btn_firis);
        if (firis == null) {
            b.setVisibility(View.GONE);
            return;
        }
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                firis.setOn(!firis.isOn());
                onFirisChanged();
            }
        });
        b.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                showFirisRuns();
                return true;
            }
        });
    }

    /** The runs held, newest first; a tap flies there and turns the layer on. */
    private void showFirisRuns() {
        final List<FirisModels.Run> runs = firis.runs;
        if (runs.isEmpty()) {
            new AlertDialog.Builder(mapView.getContext())
                    .setTitle("FIRIS Model Runs")
                    .setMessage("Nothing saved on this device yet. Turn the layer on to fetch the runs.")
                    .setPositiveButton("Close", null)
                    .show();
            return;
        }
        final String[] items = new String[runs.size()];
        for (int i = 0; i < items.length; i++) {
            final FirisModels.Run r = runs.get(i);
            items[i] = r.shortName() + "  ·  run " + FirisModels.when(r.startMs)
                    + (r.inputs.isEmpty() ? "" : "\n" + r.inputs);
        }
        new AlertDialog.Builder(mapView.getContext())
                .setTitle("FIRIS Model Runs")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (!firis.isOn())
                            firis.setOn(true);
                        firis.setFocus(runs.get(which));
                        firis.goTo(runs.get(which));
                        onFirisChanged();
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    @Override
    public void onFirisChanged() {
        if (firis == null)
            return;
        final Button b = header.findViewById(R.id.btn_firis);
        final boolean on = firis.isOn();
        final int n = firis.runs.size();
        b.setText("FIRIS Model Runs" + (n > 0 ? "  " + n : "") + (on ? "   ON" : "   OFF"));
        b.setTextColor(on ? 0xFF4CD964 : 0xFFFF5A5A);
        final TextView s = header.findViewById(R.id.firis_status);
        final StringBuilder t = new StringBuilder();
        if (firis.busy)
            t.append("Fetching runs…");
        else if (firis.fetchedAt > 0) {
            final String age = elapsed(System.currentTimeMillis() - firis.fetchedAt);
            if (firis.problem != null)
                t.append("No network, showing what this device saved ").append(age).append(" ago.");
            else
                t.append("Fetched ").append(age).append("just now".equals(age) ? "." : " ago.");
        } else if (firis.problem != null)
            t.append("Could not fetch the runs: ").append(firis.problem).append(".");
        if (on && t.length() > 0)
            t.append(" Long press for the list.");
        s.setText(t.toString());
        s.setVisibility(on && t.length() > 0 ? View.VISIBLE : View.GONE);
        buildFirisSlider(on ? firis.focus() : null);
    }

    private String firisSliderFor;

    /**
     * The same "where it is by" as our own model's page: a slider over the run's rings,
     * the edge at that time in white on the map, and the clock and acres above it.
     * Rebuilt only when the run changes, so a refresh does not yank the bar from a finger.
     */
    private void buildFirisSlider(final FirisModels.Run r) {
        final LinearLayout box = header.findViewById(R.id.firis_slider);
        final String key = r == null ? null : r.key();
        if (key == null ? firisSliderFor == null && box.getChildCount() == 0 : key.equals(firisSliderFor))
            return;
        firisSliderFor = key;
        box.removeAllViews();
        if (r == null) {
            box.setVisibility(View.GONE);
            return;
        }
        final double[] hs = r.hours();
        if (hs.length == 0) {
            box.setVisibility(View.GONE);
            return;
        }
        final Context c = mapView.getContext();
        final float dp = c.getResources().getDisplayMetrics().density;
        final LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        final TextView title = new TextView(c);
        title.setText(r.shortName() + ", run " + FirisModels.when(r.startMs));
        title.setTextColor(0xFF00E5FF);
        title.setTextSize(12f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setAllCaps(true);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(title);
        final Button close = (Button) PluginLayoutInflater.inflate(pluginContext, R.layout.source_button, null);
        close.setText("Close");
        close.setTextColor(Color.WHITE);
        close.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                firis.setFocus(null);
                onFirisChanged();
            }
        });
        head.addView(close);
        box.addView(head);
        final TextView label = new TextView(c);
        label.setTextColor(Color.WHITE);
        label.setTextSize(15f);
        int at = 0;
        for (int i = 0; i < hs.length; i++)
            if (hs[i] == firis.focusHours())
                at = i + 1;
        label.setText(firisAtText(r, hs, at));
        box.addView(label);
        final android.widget.SeekBar bar = new android.widget.SeekBar(c);
        bar.setMax(hs.length);
        bar.setProgress(at);
        bar.setPadding((int) (18 * dp), (int) (16 * dp), (int) (18 * dp), (int) (16 * dp));
        bar.setMinimumHeight((int) (52 * dp));
        final android.content.res.ColorStateList white = android.content.res.ColorStateList.valueOf(Color.WHITE);
        bar.setThumbTintList(white);
        bar.setProgressTintList(white);
        bar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar sb, int p, boolean fromUser) {
                if (!fromUser)
                    return;
                label.setText(firisAtText(r, hs, p));
                firis.showAt(r, p == 0 ? 0 : hs[p - 1]);
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar sb) {
                // The list is the pane's scroller; it must not take the drag.
                sb.getParent().requestDisallowInterceptTouchEvent(true);
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar sb) {
            }
        });
        box.addView(bar);
        final LinearLayout ends = new LinearLayout(c);
        final TextView from = new TextView(c), to = new TextView(c);
        from.setText("Run, " + FirisModels.clock(r.startMs));
        to.setText(FirisModels.clock(r.startMs + (long) (hs[hs.length - 1] * 3_600_000L)));
        for (TextView v : new TextView[] { from, to }) {
            v.setTextSize(13f);
            v.setTextColor(0xFFB0B0B0);
            v.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        to.setGravity(android.view.Gravity.END);
        ends.addView(from);
        ends.addView(to);
        box.addView(ends);
        if (!r.inputs.isEmpty()) {
            final TextView in = new TextView(c);
            in.setText("Run with: " + r.inputs);
            in.setTextColor(0xFFB0B0B0);
            in.setTextSize(13f);
            box.addView(in);
        }
        box.setVisibility(View.VISIBLE);
    }

    /** "By 22:02, in 2 h: 340 acres." */
    private static String firisAtText(FirisModels.Run r, double[] hs, int p) {
        if (p <= 0)
            return "Slide to see where the run has the fire at any time up to "
                    + FirisModels.clock(r.startMs + (long) (hs[hs.length - 1] * 3_600_000L))
                    + ". Its edge is drawn on the map in white.";
        final double h = hs[p - 1];
        final StringBuilder s = new StringBuilder("By ")
                .append(FirisModels.clock(r.startMs + (long) (h * 3_600_000L)))
                .append(", in ").append(FirisModels.hoursText(h));
        final double ac = r.acres(h);
        if (ac >= 0)
            s.append(": ").append(String.format(Locale.US, ac < 10 ? "%.1f" : "%,.0f", ac)).append(" acres");
        return s.append(".").toString();
    }

    /**
     * The map key: a swatch per color, drawn the way the map draws it (a filled box
     * with its outline) from the same color function, so the key and the map cannot
     * disagree. Static for the plugin's life; age bands do not change.
     */
    private void buildKey() {
        final LinearLayout key = header.findViewById(R.id.legend);
        key.removeAllViews();
        final long h = 3600000L;
        final long[] ages = { 0, 1 * h, 3 * h, 6 * h, 12 * h, 24 * h };
        for (long age : ages)
            key.addView(keyRow(DetectionStyles.ageColor(age), false, DetectionStyles.ageBand(age)));
        key.addView(keyRow(DetectionStyles.burnScarColor(), false, "Burned ground, mapped from a pass (RADR)"));
        key.addView(keyRow(DetectionStyles.suspectColor(), true, "Known industrial heat source"));
        final TextView note = new TextView(mapView.getContext());
        note.setText("Each box is the ground a detection covers. The fire was somewhere inside it when the satellite looked.");
        note.setTextSize(12f);
        note.setTextColor(0xFFBBBBBB);
        note.setPadding(0, dp(6), 0, 0);
        key.addView(note);
    }

    private View keyRow(int rgb, boolean dashed, String text) {
        final LinearLayout row = new LinearLayout(mapView.getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(3), 0, dp(3));
        final View swatch = new View(mapView.getContext());
        final android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor((0x60 << 24) | rgb);
        if (dashed)
            d.setStroke(dp(2), 0xFF000000 | rgb, dp(5), dp(3));
        else
            d.setStroke(dp(2), 0xFF000000 | rgb);
        swatch.setBackground(d);
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(28), dp(20));
        lp.rightMargin = dp(10);
        swatch.setLayoutParams(lp);
        row.addView(swatch);
        final TextView t = new TextView(mapView.getContext());
        t.setText(text);
        t.setTextSize(14f);
        t.setTextColor(Color.WHITE);
        row.addView(t);
        return row;
    }

    private int dp(int v) {
        return Math.round(v * mapView.getResources().getDisplayMetrics().density);
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
        header.findViewById(R.id.btn_area).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final Filters f = feed.filters;
                final String[] labels = new String[1 + Filters.AREA_CHOICES.length];
                labels[0] = "What is in view";
                int current = 0;
                for (int i = 0; i < Filters.AREA_CHOICES.length; i++) {
                    final double m = Units.bigToMeters(Filters.AREA_CHOICES[i]);
                    labels[1 + i] = "Within " + Filters.AREA_CHOICES[i] + " " + Units.bigLabel() + " of My Location";
                    if (f.areaIsRadius() && Math.abs(m - f.areaM) < 1)
                        current = 1 + i;
                }
                choose("Area", labels, current, new Chosen() {
                    @Override
                    public void at(int which) {
                        if (which == 0) {
                            f.area = Filters.AREA_VIEW;
                        } else {
                            f.area = Filters.AREA_RADIUS;
                            f.areaM = Units.bigToMeters(Filters.AREA_CHOICES[which - 1]);
                        }
                        f.save();
                        refreshUi();
                        feed.followScope();
                    }
                });
            }
        });
        header.findViewById(R.id.btn_activity).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                com.atakmap.android.ipc.AtakBroadcast.getInstance()
                        .sendBroadcast(new android.content.Intent(ActivityReceiver.ACTION));
            }
        });
        header.findViewById(R.id.btn_model).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                com.atakmap.android.ipc.AtakBroadcast.getInstance()
                        .sendBroadcast(new android.content.Intent(com.atakmap.android.firecast.model.ModelReceiver.ACTION));
            }
        });
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

        final StringBuilder on = new StringBuilder();
        for (DetectionSource s : feed.sources())
            if (feed.filters.isOn(s.id()))
                on.append(on.length() == 0 ? "" : ", ").append(s.title());
        feeds.label("Feeds", on.length() == 0 ? "none on" : on.toString());
        filters.label("Filters", (feed.filters.areaIsRadius() ? "within " + Units.formatBig(feed.filters.areaM) : "in view")
                + ", last " + feed.filters.windowLabel()
                + (feed.filters.minConfidence == 0 ? "" : ", " + feed.filters.confidenceLabel().toLowerCase(Locale.US))
                + (feed.filters.industrial == Filters.INDUSTRIAL_HIDE ? ", industrial hidden" : ""));
        key.label("Map key", null);
        ((Button) header.findViewById(R.id.btn_area)).setText(feed.filters.areaLabel());
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
            // The map draws the fetched box, which is wider than the screen, and the list
            // below counts the screen. One number for each, said as such, so the top
            // line and the list never disagree.
            final int shown = feed.shownNow(), near = feed.inViewCount();
            final String where = feed.filters.areaIsRadius()
                    ? "within " + Units.formatBig(feed.filters.areaM) : "in view";
            if (near >= shown)
                status.setText(count(near) + (near == 1 ? " detection " : " detections ") + where);
            else
                status.setText((near == 0 ? "None" : count(near)) + " " + where + ", "
                        + count(shown - near) + " more just outside");
        }
        status.setTextColor(Color.WHITE);

        final StringBuilder d = new StringBuilder();
        if (feed.lastRefresh > 0) {
            final String age = elapsed(now - feed.lastRefresh);
            d.append("Fetched ").append(age).append("just now".equals(age) ? "" : " ago");
        }
        if (feed.box != null)
            d.append(d.length() == 0 ? "For " : ", for ").append(feed.areaText());
        if (feed.clamped)
            d.append(". The map is zoomed out past the area a fetch covers, so this is the middle of the view");
        if (feed.capped)
            d.append(". More were available than are drawn - zoom in to see all of them");
        if (feed.status != null && feed.status.startsWith("no update"))
            d.append(". Could not reach the servers; showing what this phone saved");
        else if (feed.status != null && feed.status.startsWith("partial"))
            d.append(". ").append(feed.status);
        detail.setText(d.toString());

        rows = feed.inView(MAX_ROWS);
        final int inView = feed.inViewCount();
        final TextView heading = header.findViewById(R.id.list_heading);
        final boolean radius = feed.filters.areaIsRadius();
        final String name = radius ? "Within " + Units.formatBig(feed.filters.areaM) : "In view";
        // While a fetch is running an empty list is not "nothing here", it is "not yet":
        // after a fly-to the pane comes back on a new area before the answer does, and
        // "none here" for five seconds read as broken (operator, 2026-10-11).
        if (feed.refreshing && inView == 0)
            heading.setText(name + " — looking…");
        else if (feed.fetchedTotal() == 0)
            heading.setText(name);
        else if (inView == 0)
            heading.setText(name + " — none here; " + (radius ? "widen the area" : "pan") + " or widen the time window");
        else if (inView > rows.size())
            heading.setText(name + " — " + (radius ? "nearest " : "newest ") + count(rows.size()) + " of " + count(inView)
                    + (radius ? ", narrow the area" : ", zoom in to narrow"));
        else
            heading.setText(name + " — " + count(inView));
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
            final StringBuilder sub = new StringBuilder();
            if (feed.filters.areaIsRadius()) {
                final double[] from = feed.areaPoint();
                if (from != null)
                    sub.append(Units.rangeBearing(from, d.lat, d.lon))
                            .append(feed.noFix ? " of map center · " : " of you · ");
            }
            sub.append(d.footprint.sizeText());
            if (d.frpMw != null)
                sub.append(" · ").append(d.frpText());
            sub.append(" · ").append(d.sensor);
            ((TextView) v.findViewById(R.id.row_detail)).setText(sub.toString());
            ((TextView) v.findViewById(R.id.row_age)).setText(d.ageText(now));
            return v;
        }
    }
}

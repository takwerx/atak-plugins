package com.atakmap.android.sitrep.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.sitrep.Report;
import com.atakmap.android.sitrep.SitRepManager;
import com.atakmap.android.sitrep.Units;
import com.atakmap.android.sitrep.plugin.R;
import com.atakmap.android.sitrep.section.Section;
import com.atakmap.android.sitrep.section.Sections;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The pane: the main screen (Draw area | Settings | Areas ON, then REPORTS), the
 * Settings page and one report, by visibility swap in one pane (plugin-ui skill). It
 * shows what the manager holds and filters nothing itself.
 */
public final class SitRepPane {

    private static final String TAG = "SitRep";
    private static final int CYAN = 0xFF00E5FF;
    private static final int WORKING = 0xFFFFC107;
    private static final int[] NEARBY_MI = { 1, 5, 10, 25 };

    private final MapView mapView;
    private final Context plugin;
    private final SitRepManager manager;
    private final SharedPreferences prefs;
    private final View root;
    private final TextView status;
    private final ListView list;
    private final View settingsPage, reportPage;
    private final Button drawButton, areasButton;
    private final LinearLayout settingsContainer, reportSections;
    private final TextView reportTitle, reportAbout;
    private final ScrollView reportScroll;
    private final Adapter adapter = new Adapter();
    private List<Report> shown = new ArrayList<>();
    /** The report on the report page, or null when another page is up. */
    private String openId;
    private Fold nearbyFold;

    public SitRepPane(MapView mapView, Context plugin, SitRepManager manager) {
        this.mapView = mapView;
        this.plugin = plugin;
        this.manager = manager;
        this.prefs = mapView.getContext().getSharedPreferences("sitrep", Context.MODE_PRIVATE);
        root = PluginLayoutInflater.inflate(plugin, R.layout.main_layout, null);
        status = root.findViewById(R.id.status);
        list = root.findViewById(R.id.reports);
        settingsPage = root.findViewById(R.id.settings_page);
        reportPage = root.findViewById(R.id.report_page);
        settingsContainer = root.findViewById(R.id.settings_container);
        reportSections = root.findViewById(R.id.report_sections);
        reportTitle = root.findViewById(R.id.report_title);
        reportAbout = root.findViewById(R.id.report_about);
        reportScroll = root.findViewById(R.id.report_scroll);

        final View header = PluginLayoutInflater.inflate(plugin, R.layout.controls_header, null);
        drawButton = header.findViewById(R.id.btn_draw);
        areasButton = header.findViewById(R.id.btn_areas);
        list.addHeaderView(header, null, false);
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                final int i = position - list.getHeaderViewsCount();
                if (i >= 0 && i < shown.size()) {
                    showReport(shown.get(i).id);
                    goTo(shown.get(i));
                }
            }
        });
        drawButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (manager.isDrawing())
                    manager.cancelDrawing();
                else
                    manager.drawArea();
            }
        });
        header.findViewById(R.id.btn_settings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSettings();
            }
        });
        areasButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                manager.setAreasVisible(!manager.areasVisible());
            }
        });
        root.findViewById(R.id.btn_settings_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMain();
            }
        });
        root.findViewById(R.id.btn_report_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMain();
            }
        });
        root.findViewById(R.id.btn_report_goto).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final Report r = openId == null ? null : manager.find(openId);
                if (r != null)
                    goTo(r);
            }
        });
        root.findViewById(R.id.btn_report_refresh).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final Report r = openId == null ? null : manager.find(openId);
                if (r != null)
                    manager.refresh(r);
            }
        });
        root.findViewById(R.id.btn_report_delete).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmDelete();
            }
        });
        buildSettings();
        refresh();
    }

    public View getView() {
        return root;
    }

    // ------------------------------------------------------------------ pages

    /** The main screen, as the pane opens from the toolbar every time. */
    public void showMain() {
        openId = null;
        settingsPage.setVisibility(View.GONE);
        reportPage.setVisibility(View.GONE);
        list.setVisibility(View.VISIBLE);
        refresh();
    }

    private void showSettings() {
        openId = null;
        list.setVisibility(View.GONE);
        reportPage.setVisibility(View.GONE);
        settingsPage.setVisibility(View.VISIBLE);
        refresh();
    }

    public void showReport(String id) {
        if (manager.find(id) == null) {
            showMain();
            return;
        }
        final boolean other = !id.equals(openId);
        openId = id;
        list.setVisibility(View.GONE);
        settingsPage.setVisibility(View.GONE);
        reportPage.setVisibility(View.VISIBLE);
        refresh();
        if (other)
            reportScroll.post(new Runnable() {
                @Override
                public void run() {
                    reportScroll.scrollTo(0, 0);
                }
            });
    }

    /** Everything on screen from the manager's state. Main thread. */
    public void refresh() {
        status.setText(manager.status());
        if (manager.isDrawing()) {
            drawButton.setText("Drawing...");
            drawButton.setTextColor(WORKING);
        } else {
            drawButton.setText(R.string.draw_area);
            drawButton.setTextColor(0xFFFFFFFF);
        }
        setState(areasButton, "Areas", manager.areasVisible());
        shown = manager.reports();
        adapter.notifyDataSetChanged();
        if (nearbyFold != null)
            nearbyFold.label("Nearby", "within " + manager.nearbyMiles() + " mi");
        if (openId != null) {
            final Report r = manager.find(openId);
            if (r == null)
                showMain();
            else
                renderReport(r);
        }
    }

    // ------------------------------------------------------------------ report page

    private void renderReport(Report r) {
        reportTitle.setText(r.name());
        reportAbout.setText("Made " + clock(r.made) + ". " + Units.area(r.area.squareMeters()) + ", "
                + Units.distance(r.area.spanMeters()) + " across.");
        reportSections.removeAllViews();
        for (Section s : Sections.all()) {
            final Report.Answer a = r.answer(s.key);
            if (a == null)
                continue;
            final Fold f = new Fold(reportSections, "sitrep.fold." + s.key);
            f.head.setText(head(r, s, a));
            f.head.setTextColor(a.state == Report.State.LOADING || manager.isRefreshing(r, s.key) ? WORKING : 0xFFFFFFFF);
            for (String line : a.lines)
                f.body.addView(body(line, 14));
            if (a.state == Report.State.OK && a.lastTryFailed)
                f.body.addView(body((a.lastTryOffline ? "No network" : "The last Refresh did not answer")
                        + ", showing what this device saved " + ago(a.at) + ".", 13));
            if (a.state == Report.State.FAILED || a.state == Report.State.OFFLINE)
                f.body.addView(body("Tap Refresh to try again.", 14));
            final TextView src = body("Source: " + s.source
                    + (a.at > 0 ? " Fetched " + clock(a.at) + "." : ""), 12);
            src.setPadding(0, dp(6), 0, dp(4));
            f.body.addView(src);
            f.show();
        }
    }

    /** The row head: the section's one-line answer, a live one with its age. */
    private String head(Report r, Section s, Report.Answer a) {
        final String name = s.name + ": ";
        switch (a.state) {
            case LOADING:
                return name + "Loading...";
            case OFFLINE:
                return name + "Not available: no network";
            case FAILED:
                return name + "Not available";
            case NOT_HERE:
                return name + (a.value != null ? a.value : "Not published for this state");
            default:
                String v = name + (a.value == null ? "" : a.value);
                if (manager.isRefreshing(r, s.key))
                    v += " (refreshing)";
                else if (s.live || a.lastTryFailed)
                    v += " (" + ago(a.at) + ")";
                return v;
        }
    }

    private void confirmDelete() {
        final Report r = openId == null ? null : manager.find(openId);
        if (r == null)
            return;
        new AlertDialog.Builder(mapView.getContext())
                .setTitle("Delete this sit rep?")
                .setMessage(r.name())
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        manager.delete(r);
                        showMain();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ------------------------------------------------------------------ settings

    private void buildSettings() {
        nearbyFold = new Fold(settingsContainer, "sitrep.fold.nearby");
        final LinearLayout row = new LinearLayout(plugin);
        row.setOrientation(LinearLayout.HORIZONTAL);
        final List<Button> tiles = new ArrayList<>();
        for (final int mi : NEARBY_MI) {
            final View v = PluginLayoutInflater.inflate(plugin, R.layout.action_button, null);
            final Button b = v.findViewById(R.id.action);
            b.setText(mi + " mi");
            tiles.add(b);
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            if (row.getChildCount() > 0)
                lp.leftMargin = dp(4);
            row.addView(v, lp);
        }
        for (int i = 0; i < NEARBY_MI.length; i++) {
            final int mi = NEARBY_MI[i];
            tiles.get(i).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    manager.setNearbyMiles(mi);
                    paintTiles(tiles);
                }
            });
        }
        paintTiles(tiles);
        nearbyFold.body.addView(row);
        nearbyFold.body.addView(body("Shelters, hospitals, history, fires and dams outside the area itself are counted within this distance of it.", 13));
        nearbyFold.label("Nearby", "within " + manager.nearbyMiles() + " mi");

        final Fold key = new Fold(settingsContainer, "sitrep.fold.key");
        key.label("Map key", null);
        final TextView line = body("■  A saved report's area. Tap it to open the report.", 14);
        line.setTextColor(CYAN);
        key.body.addView(line);
    }

    private void paintTiles(List<Button> tiles) {
        for (int i = 0; i < tiles.size(); i++)
            tiles.get(i).setTextColor(NEARBY_MI[i] == manager.nearbyMiles()
                    ? plugin.getResources().getColor(R.color.state_on) : 0xFFFFFFFF);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Fits the map to the area in the part of the screen the pane does not cover
     * (FireCast's {@code fitVisible}).
     */
    private void goTo(Report r) {
        try {
            double south = r.area.south, west = r.area.west, north = r.area.north, east = r.area.east;
            final int mw = mapView.getWidth(), mh = mapView.getHeight();
            if (root.isShown() && mw > 0 && mh > 0) {
                final int pw = root.getWidth(), ph = root.getHeight();
                if (ph >= mh * 0.8 && pw > 0 && pw < mw * 0.9)
                    east = east + (east - west) * pw / (double) (mw - pw);
                else if (pw >= mw * 0.8 && ph > 0 && ph < mh * 0.9)
                    south = south - (north - south) * ph / (double) (mh - ph);
            }
            com.atakmap.android.util.ATAKUtilities.scaleToFit(mapView, new GeoPoint[] {
                    new GeoPoint(Math.max(-90, south), Math.max(-180, west)),
                    new GeoPoint(Math.min(90, north), Math.min(180, east)) }, 0.1d, mw, mh);
        } catch (RuntimeException e) {
            Log.w(TAG, "could not go to the area", e);
        }
    }

    private static void setState(Button b, String thing, boolean on) {
        b.setText(thing + (on ? " ON" : " OFF"));
        b.setTextColor(on ? 0xFF3DDC61 : 0xFFFF5B52);
    }

    private TextView body(String text, int sp) {
        final TextView t = new TextView(plugin);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(0xFFFFFFFF);
        t.setPadding(0, dp(2), 0, dp(2));
        return t;
    }

    private int dp(int v) {
        return Math.round(v * plugin.getResources().getDisplayMetrics().density);
    }

    private static String clock(long ms) {
        return new SimpleDateFormat("MMM d HH:mm", Locale.US).format(new Date(ms));
    }

    private static String ago(long ms) {
        if (ms <= 0)
            return "time not known";
        final long min = Math.max(0, (System.currentTimeMillis() - ms) / 60_000L);
        if (min < 1)
            return "just now";
        if (min < 60)
            return min + " min ago";
        if (min < 48 * 60)
            return (min / 60) + " h ago";
        return (min / (24 * 60)) + " days ago";
    }

    /** One drop-down row: a head naming the thing and its value, an arrow, a body. */
    private final class Fold {
        final Button head;
        final ImageButton chevron;
        final LinearLayout body;
        final String pref;
        boolean open;

        Fold(LinearLayout parent, String pref) {
            final View v = PluginLayoutInflater.inflate(plugin, R.layout.fold_row, null);
            parent.addView(v);
            head = v.findViewById(R.id.fold_head);
            chevron = v.findViewById(R.id.fold_chev);
            body = v.findViewById(R.id.fold_body);
            this.pref = pref;
            open = prefs.getBoolean(pref, false);
            final View.OnClickListener flip = new View.OnClickListener() {
                @Override
                public void onClick(View x) {
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

    /** The REPORTS list: name, then size and what it found. */
    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int i) {
            return shown.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            final View v = convert != null ? convert
                    : PluginLayoutInflater.inflate(plugin, R.layout.report_row, null);
            final Report r = shown.get(i);
            ((TextView) v.findViewById(R.id.row_name)).setText(r.name());
            final StringBuilder s = new StringBuilder(Units.area(r.area.squareMeters()));
            boolean busy = false;
            for (Section sec : Sections.all()) {
                final Report.Answer a = r.answer(sec.key);
                if (a == null)
                    continue;
                busy |= a.state == Report.State.LOADING;
                if (a.state == Report.State.OK && a.summary != null)
                    s.append(", ").append(a.summary);
            }
            if (busy)
                s.append(", loading...");
            ((TextView) v.findViewById(R.id.row_summary)).setText(s);
            return v;
        }
    }
}

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
    /** The place a radial's Details asked for: its row is marked and scrolled to once. */
    private String focusKey;
    /** The report the Settings page was opened from: its Back goes there. */
    private String settingsFrom;
    private int settingsFromY;
    private View focusRow;
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
                else if (manager.pick.isActive()) {
                    manager.pick.cancel();
                    manager.setNote(null);
                } else
                    chooseNewArea();
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
                // Back to the report Settings was opened from, if any.
                final String back = settingsFrom;
                final int y = settingsFromY;
                settingsFrom = null;
                if (back != null && manager.find(back) != null) {
                    showReport(back);
                    // Where the operator was in the report, not its top.
                    reportScroll.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            reportScroll.scrollTo(0, y);
                        }
                    }, 60);
                } else
                    showMain();
            }
        });
        root.findViewById(R.id.btn_report_settings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Settings from inside a report, so the places zoom can be changed
                // without leaving it (operator, 2026-11-14).
                final String from = openId;
                final int y = reportScroll.getScrollY();
                showSettings();
                settingsFrom = from;
                settingsFromY = y;
            }
        });
        root.findViewById(R.id.btn_report_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMain();
            }
        });
        // ATAK's own icons, from ATAK at run time (operator, 2026-11-14: Go to,
        // Refresh and Delete as icons at the top).
        hostIcon((android.widget.ImageButton) root.findViewById(R.id.btn_report_goto), "panto_selected");
        hostIcon((android.widget.ImageButton) root.findViewById(R.id.btn_report_rename), "ic_edit");
        hostIcon((android.widget.ImageButton) root.findViewById(R.id.btn_report_settings), "nav_settings");
        hostIcon((android.widget.ImageButton) root.findViewById(R.id.btn_report_refresh), "ic_refresh");
        hostIcon((android.widget.ImageButton) root.findViewById(R.id.btn_report_delete), "ic_menu_delete");
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
        final View.OnClickListener wide = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (wideToggle != null)
                    wideToggle.run();
            }
        };
        header.findViewById(R.id.btn_wide_main).setOnClickListener(wide);
        root.findViewById(R.id.btn_wide_report).setOnClickListener(wide);
        root.findViewById(R.id.btn_report_rename).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                askRename();
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

    /** Puts one of ATAK's own drawables on a button; logs when ATAK has none by that name. */
    private void hostIcon(android.widget.ImageButton b, String name) {
        try {
            final Context host = mapView.getContext();
            final int id = host.getResources().getIdentifier(name, "drawable", host.getPackageName());
            if (id != 0)
                b.setImageDrawable(host.getResources().getDrawable(id));
            else
                Log.w(TAG, "ATAK has no drawable " + name);
        } catch (RuntimeException e) {
            Log.w(TAG, "could not load ATAK's " + name, e);
        }
    }

    /** What the wide button does; the drop-down hosting the pane sets it. */
    private Runnable wideToggle;

    public void setWideToggle(Runnable r) {
        wideToggle = r;
    }

    public View getView() {
        return root;
    }

    // ------------------------------------------------------------------ pages

    /** The main screen, as the pane opens from the toolbar every time. */
    public void showMain() {
        openId = null;
        focusKey = null;
        settingsFrom = null;
        settingsPage.setVisibility(View.GONE);
        reportPage.setVisibility(View.GONE);
        list.setVisibility(View.VISIBLE);
        refresh();
    }

    private void showSettings() {
        settingsFrom = null;
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
        if (other)
            focusKey = null;
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

    /**
     * Details on a place's radial: its report, the Places group and its category
     * opened, and its row marked with what the source said.
     */
    public void showPlace(String reportId, String category, String key) {
        if ("calls".equals(category)) {
            // A call: the 911 calls section and its agency's fold.
            folds.put(reportId + "|sitrep.fold.calls", true);
            final Report r = manager.find(reportId);
            final Report.Answer a = r == null ? null : r.answer("calls");
            for (int i = 0; a != null && a.points != null && i < a.points.length(); i++) {
                final org.json.JSONObject p = a.points.optJSONObject(i);
                if (p != null && key.equals(com.atakmap.android.sitrep.PlaceLayer.placeKey(p)))
                    folds.put(reportId + "|sitrep.fold.calls." + p.optString("ag", "Calls").toLowerCase(java.util.Locale.US), true);
            }
        } else if ("lessons".equals(category)) {
            // A fatality or entrapment review: History and its group.
            folds.put(reportId + "|sitrep.fold.history", true);
            final Report r = manager.find(reportId);
            final Report.Answer a = r == null ? null : r.answer("history");
            for (int i = 0; a != null && i < a.lines.size(); i++)
                if (a.lines.get(i).startsWith(Section.GROUP + "Fatalities"))
                    folds.put(reportId + "|sitrep.fold.history."
                            + groupName(a.lines.get(i)).toLowerCase(java.util.Locale.US), true);
        } else {
            folds.put(reportId + "|sitrep.fold.places", true);
            folds.put(reportId + "|sitrep.fold.places." + category, true);
        }
        if (!reportId.equals(openId))
            showReport(reportId);
        focusKey = key;
        refresh();
        reportScroll.postDelayed(new Runnable() {
            @Override
            public void run() {
                final View row = focusRow;
                if (row == null)
                    return;
                int y = 0;
                for (View v = row; v != null && v != reportScroll; v = (View) v.getParent())
                    y += v.getTop();
                reportScroll.smoothScrollTo(0, Math.max(0, y - dp(48)));
            }
        }, 150);
    }

    /** Everything on screen from the manager's state. Main thread. */
    public void refresh() {
        status.setText(manager.status());
        if (manager.pick.isActive()) {
            drawButton.setText("Cancel tap");
            drawButton.setTextColor(WORKING);
        } else if (manager.isDrawing()) {
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
        paintGate();
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
        focusRow = null;
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
            // Section heads are cyan, the category color of the plugin-ui standard, so
            // Alerts and Structures read as categories and the groups inside as white.
            f.head.setTextColor(a.state == Report.State.LOADING || manager.isRefreshing(r, s.key) ? WORKING : CATEGORY);
            if ("places".equals(s.key) && a.points != null) {
                placeSwitches(f.body, r, a.points);
                // The category lists are in the switches' own drop-downs; only the
                // notes (California only, a source that did not answer) stay as text.
                final List<String> notes = new ArrayList<>();
                for (String line : a.lines)
                    if (!line.startsWith(Section.GROUP) && !line.startsWith("  "))
                        notes.add(line);
                addLines(f.body, s.key, notes);
            } else if ("calls".equals(s.key) && a.points != null) {
                callRows(f.body, r, a.points);
                final List<String> notes = new ArrayList<>();
                for (String line : a.lines)
                    if (!line.startsWith(Section.GROUP) && !line.startsWith("  "))
                        notes.add(line);
                addLines(f.body, s.key, notes);
            } else if ("history".equals(s.key) && a.points != null) {
                // The fatality and entrapment group holds rows that go to the place,
                // not plain lines.
                final List<String> kept = new ArrayList<>();
                String lessonsGroup = null;
                boolean inLessons = false;
                for (String line : a.lines) {
                    if (line.startsWith(Section.GROUP)) {
                        inLessons = line.startsWith(Section.GROUP + "Fatalities");
                        if (inLessons)
                            lessonsGroup = groupName(line);
                    } else if (!line.startsWith("  "))
                        inLessons = false;
                    else if (inLessons)
                        continue;
                    kept.add(line);
                }
                final java.util.Map<String, Fold> groups = addLines(f.body, s.key, kept);
                final Fold lessons = lessonsGroup == null ? null : groups.get(lessonsGroup);
                if (lessons != null)
                    lessonRows(lessons.body, r, a.points);
            } else
                addLines(f.body, s.key, a.lines);
            if (a.state == Report.State.OK && a.lastTryFailed)
                f.body.addView(body((a.lastTryOffline ? "No network" : "The last Refresh did not answer")
                        + ", showing what this device saved " + ago(a.at) + ".", 13));
            if (a.state == Report.State.FAILED || a.state == Report.State.OFFLINE)
                f.body.addView(body("Tap Refresh to try again.", 14));
            if (s.seeIn != null && (a.state == Report.State.OK || a.state == Report.State.NOT_HERE)) {
                if (s.openName == null) {
                    final TextView see = body(s.seeIn, 14);
                    see.setTextColor(CATEGORY);
                    see.setPadding(0, dp(6), 0, 0);
                    f.body.addView(see);
                } else {
                    pluginLine(f.body, r, s.openName, s.openPackage, s.openAction,
                            s.openAction == null ? s.seeIn : null);
                    if (s.alsoName != null)
                        pluginLine(f.body, r, s.alsoName, s.alsoPackage, s.alsoAction, null);
                }
            }
            final TextView src = body("Source: " + s.source
                    + (a.at > 0 ? " Fetched " + clock(a.at) + "." : ""), 12);
            src.setPadding(0, dp(6), 0, dp(4));
            f.body.addView(src);
            f.show();
        }
    }

    /**
     * Detail lines under a section. A group line ({@link Section#GROUP}) becomes its
     * own drop-down, closed until tapped, holding the indented lines after it.
     */
    private java.util.Map<String, Fold> addLines(LinearLayout into, String sectionKey, List<String> lines) {
        final java.util.Map<String, Fold> groups = new java.util.HashMap<>();
        Fold group = null;
        for (String line : lines) {
            if (line.startsWith(Section.GROUP)) {
                final String text = line.substring(Section.GROUP.length());
                final String name = groupName(line);
                group = new Fold(into, "sitrep.fold." + sectionKey + "." + name.toLowerCase(Locale.US));
                group.head.setText(text);
                group.show();
                groups.put(name, group);
            } else if (group != null && line.startsWith("  ")) {
                group.body.addView(lineView(line.trim()));
            } else {
                group = null;
                into.addView(lineView(line));
            }
        }
        return groups;
    }

    /** A group line's name: its text before the colon. */
    private static String groupName(String line) {
        final String text = line.substring(Section.GROUP.length());
        final int colon = text.indexOf(':');
        return colon > 0 ? text.substring(0, colon) : text;
    }

    /**
     * History's fatality and entrapment reviews: a map switch, then one row each. A
     * tap puts them on the map, marks the row, takes the map there, and opens a line
     * under it to the review on lessons.wildfire.gov; a second tap closes it
     * (operator, 2026-11-14).
     */
    private void lessonRows(LinearLayout into, final Report r, org.json.JSONArray points) {
        final Button map = (Button) PluginLayoutInflater.inflate(plugin, R.layout.open_button, null);
        setState(map, "On map", r.placesOn.contains("lessons"));
        map.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                manager.setPlaceOn(r, "lessons", !r.placesOn.contains("lessons"));
            }
        });
        into.addView(map);
        for (int i = 0; i < points.length(); i++) {
            final org.json.JSONObject p = points.optJSONObject(i);
            if (p == null || !"lessons".equals(p.optString("c")))
                continue;
            final boolean focus = focusKey != null
                    && focusKey.equals(com.atakmap.android.sitrep.PlaceLayer.placeKey(p));
            final org.json.JSONArray info = p.optJSONArray("i");
            final String where = info != null && info.length() > 0 ? ", " + info.optString(0) : "";
            final TextView row = body(p.optString("n") + where, 14);
            row.setPadding(0, dp(6), 0, dp(6));
            if (focus) {
                row.setTextColor(CATEGORY);
                row.setBackgroundColor(0x2200E5FF);
                focusRow = row;
            }
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (focus) {
                        focusKey = null;
                        refresh();
                        return;
                    }
                    focusKey = com.atakmap.android.sitrep.PlaceLayer.placeKey(p);
                    if (!r.placesOn.contains("lessons"))
                        manager.setPlaceOn(r, "lessons", true);
                    refresh();
                    goToPlace(p);
                }
            });
            into.addView(row);
            if (!focus)
                continue;
            for (int k = 1; info != null && k < info.length(); k++) {
                final TextView t = body(info.optString(k), 13);
                t.setPadding(dp(16), 0, 0, dp(2));
                into.addView(t);
            }
            final String url = p.optString("u", "");
            if (url.startsWith(com.atakmap.android.sitrep.section.History.LESSONS)) {
                final TextView open = body("Open Lessons Learned report", 14);
                open.setTextColor(CATEGORY);
                open.setPaintFlags(open.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
                open.setPadding(dp(16), dp(4), 0, dp(8));
                open.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openPage(url);
                    }
                });
                into.addView(open);
            }
        }
    }

    /**
     * One detail line. A line that carries a web page ({@link Section#link}) shows its
     * text underlined, and a tap opens the page in the device's browser.
     */
    private TextView lineView(String line) {
        final int k = line.indexOf(Section.LINK);
        if (k < 0)
            return body(line, 14);
        final String url = line.substring(k + Section.LINK.length()).trim();
        final TextView t = body(line.substring(0, k).trim(), 14);
        if (!url.startsWith("https://"))
            return t;
        t.setPaintFlags(t.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openPage(url);
            }
        });
        return t;
    }

    /** Opens an https page in the device's browser. */
    private void openPage(String url) {
        try {
            final android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(url));
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            mapView.getContext().startActivity(i);
        } catch (RuntimeException e) {
            new AlertDialog.Builder(mapView.getContext()).setTitle("No browser")
                    .setMessage("Open " + url + " in a browser.")
                    .setPositiveButton("OK", null).show();
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

    /** New area: how the area is chosen, as TakwerxButton tiles (popup standard). */
    private void chooseNewArea() {
        final View v = PluginLayoutInflater.inflate(plugin, R.layout.new_area, null);
        final AlertDialog d = new AlertDialog.Builder(mapView.getContext())
                .setTitle("New area")
                .setView(v)
                .setNegativeButton("Cancel", null)
                .create();
        v.findViewById(R.id.new_draw).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View x) {
                d.dismiss();
                manager.drawArea();
            }
        });
        v.findViewById(R.id.new_here).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View x) {
                d.dismiss();
                pickPlaceHere();
            }
        });
        v.findViewById(R.id.new_shape).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View x) {
                d.dismiss();
                pickShape();
            }
        });
        v.findViewById(R.id.new_name).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View x) {
                d.dismiss();
                askPlaceName();
            }
        });
        d.show();
    }

    /** County or city here: one tap on the map, then the places at that point. */
    private void pickPlaceHere() {
        final boolean ok = manager.pick.start(false, new com.atakmap.android.sitrep.MapPick.Callback() {
            @Override
            public void picked(GeoPoint point, GeoPoint[] outline) {
                manager.setNote("Finding the county and city there...");
                manager.placesAt(point, new SitRepManager.PlaceAnswer<List<com.atakmap.android.sitrep.net.Places.Place>>() {
                    @Override
                    public void done(List<com.atakmap.android.sitrep.net.Places.Place> found, Throwable error) {
                        manager.setNote(null);
                        if (found != null && found.isEmpty()) {
                            new AlertDialog.Builder(mapView.getContext()).setTitle("County or city here")
                                    .setMessage("No US county or city there.")
                                    .setPositiveButton("OK", null).show();
                            return;
                        }
                        showPlaces("", found, error);
                    }
                });
            }

            @Override
            public void missed(String why) {
                manager.setNote(why);
            }
        });
        manager.setNote(ok ? "Tap a point on the map. Sit Rep lists the city and county there."
                : "Could not take the map for a tap. Try again.");
    }

    /** Use a shape: one tap on a shape already on the map; its outline is the area. */
    private void pickShape() {
        final boolean ok = manager.pick.start(true, new com.atakmap.android.sitrep.MapPick.Callback() {
            @Override
            public void picked(GeoPoint point, GeoPoint[] outline) {
                manager.reportForShape(outline, null);
            }

            @Override
            public void missed(String why) {
                manager.setNote(why);
            }
        });
        manager.setNote(ok ? "Tap the edge of a polygon, circle or rectangle on the map."
                : "Could not take the map for a tap. Try again.");
    }

    /** Type a name: a city, town, community or county, then pick one from the list. */
    private void askPlaceName() {
        final android.content.Context host = mapView.getContext();
        final android.widget.EditText box = new android.widget.EditText(host);
        box.setSingleLine(true);
        box.setHint("Type a city, town or county");
        box.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        // No full-screen keyboard in landscape: it covers the dialog with a white edit box.
        box.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN);
        final int pad = (int) (20 * host.getResources().getDisplayMetrics().density);
        final android.widget.FrameLayout frame = new android.widget.FrameLayout(host);
        frame.setPadding(pad, 0, pad, 0);
        frame.addView(box);
        final AlertDialog d = new AlertDialog.Builder(host)
                .setView(frame)
                .setPositiveButton("Search", null)
                .setNegativeButton("Cancel", null)
                .create();
        final Runnable search = new Runnable() {
            @Override
            public void run() {
                final String typed = box.getText().toString().trim();
                if (typed.length() < 2) {
                    box.setError("Two letters or more");
                    return;
                }
                d.dismiss();
                manager.centerForSearch = mapView.getCenterPoint().get();
                manager.searchPlaces(typed, new SitRepManager.PlaceAnswer<List<com.atakmap.android.sitrep.net.Places.Place>>() {
                    @Override
                    public void done(List<com.atakmap.android.sitrep.net.Places.Place> found, Throwable error) {
                        showPlaces(typed, found, error);
                    }
                });
            }
        };
        box.setOnEditorActionListener(new android.widget.TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(android.widget.TextView t, int action, android.view.KeyEvent e) {
                search.run();
                return true;
            }
        });
        d.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface di) {
                d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View x) {
                        search.run();
                    }
                });
            }
        });
        showAboveKeyboard(d, box);
    }

    /**
     * A one-box text dialog that stays readable in landscape (S22, 2026-11-14: the
     * box was squashed to a line and the typing could not be seen). The keyboard
     * leaves about 130 dp; a title, a box and the buttons do not fit, and with
     * adjustResize the dialog crushed the box. So: no title (the hint says what to
     * type), a box that cannot shrink below a touch target, and the dialog pinned to
     * the top with the keyboard laid over the map below it.
     */
    private void showAboveKeyboard(AlertDialog d, android.widget.EditText box) {
        final float dp = mapView.getContext().getResources().getDisplayMetrics().density;
        box.setMinHeight((int) (48 * dp));
        box.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18);
        final android.view.Window w = d.getWindow();
        if (w != null) {
            w.setGravity(android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL);
            w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                    | android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        }
        d.show();
        box.requestFocus();
    }

    private void showPlaces(String typed, final List<com.atakmap.android.sitrep.net.Places.Place> found, Throwable error) {
        final android.content.Context host = mapView.getContext();
        if (error != null || found == null) {
            new AlertDialog.Builder(host).setTitle("Type a name")
                    .setMessage(com.atakmap.android.sitrep.net.Http.noNetwork(error)
                            ? "No network. Try again when this device is connected."
                            : "The Census place search did not answer. Try again.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        if (found.isEmpty()) {
            new AlertDialog.Builder(host).setTitle("Type a name")
                    .setMessage("No city, town or county starts with \"" + typed + "\".")
                    .setPositiveButton("OK", null).show();
            return;
        }
        final String[] names = new String[found.size()];
        for (int i = 0; i < names.length; i++)
            names[i] = found.get(i).label;
        new AlertDialog.Builder(host)
                .setTitle("Pick a place")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int which) {
                        manager.reportForPlace(found.get(which));
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void askRename() {
        final Report r = openId == null ? null : manager.find(openId);
        if (r == null)
            return;
        final android.content.Context host = mapView.getContext();
        final android.widget.EditText box = new android.widget.EditText(host);
        box.setSingleLine(true);
        box.setText(r.name());
        box.setSelectAllOnFocus(true);
        box.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN);
        box.setHint("New name (empty = automatic name)");
        box.setFilters(new android.text.InputFilter[] {
                new android.text.InputFilter.LengthFilter(60)
        });
        box.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        final int pad = (int) (20 * host.getResources().getDisplayMetrics().density);
        final android.widget.FrameLayout frame = new android.widget.FrameLayout(host);
        frame.setPadding(pad, 0, pad, 0);
        frame.addView(box);
        final AlertDialog d = new AlertDialog.Builder(host)
                .setView(frame)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dlg, int which) {
                        manager.rename(r, box.getText().toString());
                    }
                })
                .setNegativeButton("Cancel", null)
                .create();
        showAboveKeyboard(d, box);
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

        buildGate();

        final Fold key = new Fold(settingsContainer, "sitrep.fold.key");
        key.label("Map key", null);
        final TextView line = body("■  A saved report's area. Tap it to open the report.", 14);
        line.setTextColor(CYAN);
        key.body.addView(line);
    }

    private Fold gateFold;
    private TextView gateNow;
    private final List<Button> gateTiles = new ArrayList<>();
    private double[] gateValues;

    /**
     * One zoom gate for every place on the map (operator, 2026-11-13): stored and
     * compared as the distance ATAK's scale bar reads (plugin-ui skill, section 3).
     */
    private void buildGate() {
        gateFold = new Fold(settingsContainer, "sitrep.fold.gate");
        final boolean metric = com.atakmap.android.sitrep.Units.type() == com.atakmap.coremap.conversions.Span.METRIC;
        final double unit = metric ? 1_000d : 1_609.344;
        gateValues = new double[] { 0.25 * unit, 1 * unit, 5 * unit, 15 * unit, 50 * unit, -1 };
        final String[] names = metric ? new String[] { "0.25 km", "1 km", "5 km", "15 km", "50 km", "Always" }
                : new String[] { "0.25 mi", "1 mi", "5 mi", "15 mi", "50 mi", "Always" };
        LinearLayout row = null;
        for (int i = 0; i < gateValues.length; i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(plugin);
                row.setOrientation(LinearLayout.HORIZONTAL);
                if (i > 0)
                    row.setPadding(0, dp(4), 0, 0);
                gateFold.body.addView(row);
            }
            final View v = PluginLayoutInflater.inflate(plugin, R.layout.action_button, null);
            final Button b = v.findViewById(R.id.action);
            b.setText(names[i]);
            final double value = gateValues[i];
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    manager.setPlacesGate(value);
                    paintGate();
                }
            });
            gateTiles.add(b);
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            if (row.getChildCount() > 0)
                lp.leftMargin = dp(4);
            row.addView(v, lp);
        }
        final View uv = PluginLayoutInflater.inflate(plugin, R.layout.action_button, null);
        final Button use = uv.findViewById(R.id.action);
        use.setText("Use this zoom");
        use.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                manager.setPlacesGate(com.atakmap.android.sitrep.ScaleBar.meters(mapView));
                paintGate();
            }
        });
        final LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        ulp.topMargin = dp(4);
        gateFold.body.addView(uv, ulp);
        gateNow = body("", 13);
        gateFold.body.addView(gateNow);
        gateFold.body.addView(body("Every place category draws only when the scale bar reads this or less. The lists in the report are not changed.", 13));
        paintGate();
    }

    private void paintGate() {
        if (gateFold == null)
            return;
        final double gate = manager.placesGate();
        gateFold.label("Places show at", gate > 0 ? com.atakmap.android.sitrep.ScaleBar.describe(gate) + " or closer" : "always");
        for (int i = 0; i < gateTiles.size(); i++)
            gateTiles.get(i).setTextColor(Math.abs(gateValues[i] - gate) < 0.5
                    ? plugin.getResources().getColor(R.color.state_on) : 0xFFFFFFFF);
        gateNow.setText("Scale bar now " + com.atakmap.android.sitrep.ScaleBar.text(mapView)
                + (manager.placesGated() ? ": places hidden." : ": places shown."));
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
        goTo(r, null);
    }

    /** Puts a report's area in view; for a report just made. */
    public void goToReport(String id) {
        final Report r = manager.find(id);
        if (r != null)
            goTo(r);
    }

    /** Fits the map to one place: a point with about 400 m around it, a line whole. */
    private void goToPlace(org.json.JSONObject p) {
        try {
            double south = p.optDouble("lat"), north = south, west = p.optDouble("lon"), east = west;
            final org.json.JSONArray g = p.optJSONArray("g");
            for (int k = 0; g != null && k < g.length(); k++) {
                final org.json.JSONArray path = g.optJSONArray(k);
                for (int v = 0; path != null && v < path.length(); v++) {
                    final org.json.JSONArray xy = path.optJSONArray(v);
                    if (xy == null)
                        continue;
                    south = Math.min(south, xy.optDouble(1, south));
                    north = Math.max(north, xy.optDouble(1, north));
                    west = Math.min(west, xy.optDouble(0, west));
                    east = Math.max(east, xy.optDouble(0, east));
                }
            }
            if (!(Math.abs(south) <= 90) || !(Math.abs(north) <= 90) || !(Math.abs(west) <= 180) || !(Math.abs(east) <= 180))
                return;
            final double padLat = Math.max(0.004, (north - south) * 0.15);
            final double padLon = Math.max(0.004, (east - west) * 0.15);
            com.atakmap.android.util.ATAKUtilities.scaleToFit(mapView, new GeoPoint[] {
                    new GeoPoint(Math.max(-90, south - padLat), Math.max(-180, west - padLon)),
                    new GeoPoint(Math.min(90, north + padLat), Math.min(180, east + padLon)) }, 0d,
                    mapView.getWidth(), mapView.getHeight());
        } catch (RuntimeException e) {
            Log.w(TAG, "could not go to the place", e);
        }
    }

    /**
     * Fits the map to the area and, when a category is given, every place of that
     * category the report found: the nearest hospital or shelter is often outside the
     * area (operator, 2026-11-13: "it needs to zoom in on the city or area when i turn
     * on hospitals").
     */
    private void goTo(Report r, String category) {
        try {
            double south = r.area.south, west = r.area.west, north = r.area.north, east = r.area.east;
            final Report.Answer pa = category == null ? null : r.answer("places");
            if (pa != null && pa.points != null)
                for (int i = 0; i < pa.points.length(); i++) {
                    final org.json.JSONObject p = pa.points.optJSONObject(i);
                    if (p == null || !category.equals(p.optString("c")))
                        continue;
                    south = Math.min(south, p.optDouble("lat", south));
                    north = Math.max(north, p.optDouble("lat", north));
                    west = Math.min(west, p.optDouble("lon", west));
                    east = Math.max(east, p.optDouble("lon", east));
                }
            // Feature Layer's and IPAWS's way: the whole map and a 15% margin; ATAK
            // keeps the framed box clear of the open pane itself. A correction for the
            // pane's width here moved the map out and south (s10-dev-3, 2026-11-13).
            final double padLat = Math.max(0.002, (north - south) * 0.15);
            final double padLon = Math.max(0.002, (east - west) * 0.15);
            com.atakmap.android.util.ATAKUtilities.scaleToFit(mapView, new GeoPoint[] {
                    new GeoPoint(Math.max(-90, south - padLat), Math.max(-180, west - padLon)),
                    new GeoPoint(Math.min(90, north + padLat), Math.min(180, east + padLon)) }, 0d,
                    mapView.getWidth(), mapView.getHeight());
        } catch (RuntimeException e) {
            Log.w(TAG, "could not go to the area", e);
        }
    }


    private static void setState(Button b, String thing, boolean on) {
        b.setText(thing + (on ? " ON" : " OFF"));
        b.setTextColor(on ? 0xFF3DDC61 : 0xFFFF5B52);
    }

    /**
     * Whether another plugin is loaded in ATAK. A plugin cannot ask Android which
     * packages are installed, so ATAK's own registry is asked; if that fails (an
     * obfuscated build), the button shows and a plugin that is not there ignores it.
     */
    private static boolean pluginLoaded(String pkg) {
        try {
            final com.atak.plugins.impl.AtakPluginRegistry reg = com.atak.plugins.impl.AtakPluginRegistry.get();
            return reg == null || reg.isPluginLoaded(pkg) || reg.isPluginLoaded(pkg + ".civ");
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * One row per place category the report found: a tap on the name switches it on
     * or off on the map (green ON, red OFF, with its count), the arrow opens the list
     * of its places (operator, 2026-11-13: one row, not a switch and a list apart).
     */
    private void placeSwitches(LinearLayout into, final Report r, org.json.JSONArray points) {
        final java.util.Map<String, List<org.json.JSONObject>> byCat = new java.util.HashMap<>();
        for (int i = 0; i < points.length(); i++) {
            final org.json.JSONObject p = points.optJSONObject(i);
            if (p == null)
                continue;
            List<org.json.JSONObject> l = byCat.get(p.optString("c"));
            if (l == null) {
                l = new ArrayList<>();
                byCat.put(p.optString("c"), l);
            }
            l.add(p);
        }
        for (final String[] c : com.atakmap.android.sitrep.section.Places.CATEGORIES) {
            final List<org.json.JSONObject> l = byCat.get(c[0]);
            if (l == null)
                continue;
            final Fold fold = new Fold(into, "sitrep.fold.places." + c[0]);
            setState(fold.head, c[1] + " (" + l.size() + ")", r.placesOn.contains(c[0]));
            fold.head.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    // No zoom: the operator keeps the view they have (2026-11-13).
                    manager.setPlaceOn(r, c[0], !r.placesOn.contains(c[0]));
                }
            });
            // The area's own agency first, its headquarters then its stations by
            // number, inside the line or just past it; then other agencies inside
            // the area by name; then the ones outside, nearest first as the section
            // found them (operator, 2026-11-14: "list their HQ first then stations 1
            // and on then outside agencies").
            final String own = ownName(r);
            final List<org.json.JSONObject> inside = new ArrayList<>(l);
            java.util.Collections.sort(inside, new java.util.Comparator<org.json.JSONObject>() {
                @Override
                public int compare(org.json.JSONObject a, org.json.JSONObject b) {
                    final int ra = rank(a, own), rb = rank(b, own);
                    if (ra != rb)
                        return ra - rb;
                    // Outside the area: keep the nearest-first order (the sort is stable).
                    return ra == 3 ? 0 : naturalCompare(a.optString("n"), b.optString("n"));
                }
            });
            int shown = 0;
            for (org.json.JSONObject p : inside) {
                final boolean focus = focusKey != null
                        && focusKey.equals(com.atakmap.android.sitrep.PlaceLayer.placeKey(p));
                // The place a radial asked for is listed even past the first 40.
                if (++shown > 40 && !focus) {
                    if (shown == 41)
                        fold.body.addView(body("... and " + (l.size() - 40) + " more on the map.", 14));
                    continue;
                }
                String where = "";
                final org.json.JSONArray info = p.optJSONArray("i");
                if (info != null && info.length() > 0 && info.optString(info.length() - 1).endsWith("of the area."))
                    where = ", " + info.optString(info.length() - 1).replace(" of the area.", "");
                final TextView row = body(p.optString("n") + where, 14);
                row.setPadding(0, dp(6), 0, dp(6));
                final org.json.JSONObject place = p;
                row.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        // A tap on the marked row takes the mark off (operator,
                        // 2026-11-14). Any other row is marked with its details and
                        // the map goes there, its category switched on so it is drawn.
                        if (focus) {
                            focusKey = null;
                            refresh();
                            return;
                        }
                        focusKey = com.atakmap.android.sitrep.PlaceLayer.placeKey(place);
                        if (!r.placesOn.contains(c[0]))
                            manager.setPlaceOn(r, c[0], true);
                        refresh();
                        goToPlace(place);
                    }
                });
                fold.body.addView(row);
                if (focus) {
                    row.setTextColor(CATEGORY);
                    row.setBackgroundColor(0x2200E5FF);
                    final StringBuilder d = new StringBuilder();
                    for (int k = 0; info != null && k < info.length(); k++)
                        d.append(k == 0 ? "" : "\n").append(info.optString(k));
                    if (d.length() > 0) {
                        final TextView more = body(d.toString(), 13);
                        more.setPadding(dp(12), 0, 0, dp(6));
                        more.setBackgroundColor(0x2200E5FF);
                        fold.body.addView(more);
                    }
                    focusRow = row;
                }
            }
            fold.show();
        }
    }

    /**
     * Where a place goes in its list: 0 the area's own headquarters, 1 the area's own
     * stations, 2 another agency inside the area, 3 another agency outside it.
     */
    private static int rank(org.json.JSONObject p, String own) {
        final String n = p.optString("n");
        if (startsWithWord(n, own))
            return n.matches("(?i).*\\b(headquarters|HQ)\\b.*") ? 0 : 1;
        return outsideArea(p) ? 3 : 2;
    }

    /** A place the section found outside the area: its last line says where. */
    private static boolean outsideArea(org.json.JSONObject p) {
        final org.json.JSONArray info = p.optJSONArray("i");
        return info != null && info.length() > 0
                && info.optString(info.length() - 1).endsWith("of the area.");
    }

    /**
     * The area's own name as an agency would start with it: "Portland city, OR" is
     * Portland, "Riverside County" stays Riverside County. Null when there is none.
     */
    private static String ownName(Report r) {
        String n = r.label != null ? r.label : r.place;
        if (n == null)
            return null;
        final int comma = n.indexOf(',');
        if (comma > 0)
            n = n.substring(0, comma);
        n = n.trim().replaceFirst("(?i)\\s+(city|town|village|CDP|borough|municipality)$", "").trim();
        return n.length() < 3 ? null : n;
    }

    /** Whether a name starts with the given words, as whole words. */
    private static boolean startsWithWord(String name, String prefix) {
        if (prefix == null || name.length() < prefix.length()
                || !name.regionMatches(true, 0, prefix, 0, prefix.length()))
            return false;
        return name.length() == prefix.length() || !Character.isLetterOrDigit(name.charAt(prefix.length()));
    }

    /** Text order, except that runs of digits compare as numbers. */
    private static int naturalCompare(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            final char ca = a.charAt(i), cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int ei = i, ej = j;
                while (ei < a.length() && Character.isDigit(a.charAt(ei)))
                    ei++;
                while (ej < b.length() && Character.isDigit(b.charAt(ej)))
                    ej++;
                final String na = a.substring(i, ei).replaceFirst("^0+(?=.)", "");
                final String nb = b.substring(j, ej).replaceFirst("^0+(?=.)", "");
                if (na.length() != nb.length())
                    return na.length() - nb.length();
                final int c = na.compareTo(nb);
                if (c != 0)
                    return c;
                i = ei;
                j = ej;
                continue;
            }
            final int c = Character.compare(Character.toLowerCase(ca), Character.toLowerCase(cb));
            if (c != 0)
                return c;
            i++;
            j++;
        }
        return (a.length() - i) - (b.length() - j);
    }

    /**
     * The 911 calls: a map switch, then one fold per agency, newest first. A tap on a
     * call puts the calls on the map, marks the row and takes the map there, as a
     * listed place does (operator, 2026-11-14: "if i click on the call can we have
     * those rendered on the map").
     */
    private void callRows(LinearLayout into, final Report r, org.json.JSONArray points) {
        final Button map = (Button) PluginLayoutInflater.inflate(plugin, R.layout.open_button, null);
        setState(map, "Calls on map", r.placesOn.contains("calls"));
        map.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                manager.setPlaceOn(r, "calls", !r.placesOn.contains("calls"));
            }
        });
        into.addView(map);
        final java.util.Map<String, List<org.json.JSONObject>> byAgency = new java.util.LinkedHashMap<>();
        for (int i = 0; i < points.length(); i++) {
            final org.json.JSONObject p = points.optJSONObject(i);
            if (p == null)
                continue;
            List<org.json.JSONObject> l = byAgency.get(p.optString("ag", "Calls"));
            if (l == null) {
                l = new ArrayList<>();
                byAgency.put(p.optString("ag", "Calls"), l);
            }
            l.add(p);
        }
        for (java.util.Map.Entry<String, List<org.json.JSONObject>> e : byAgency.entrySet()) {
            final List<org.json.JSONObject> l = e.getValue();
            java.util.Collections.sort(l, new java.util.Comparator<org.json.JSONObject>() {
                @Override
                public int compare(org.json.JSONObject a, org.json.JSONObject b) {
                    return Long.compare(b.optLong("t", 0), a.optLong("t", 0));
                }
            });
            final Fold fold = new Fold(into, "sitrep.fold.calls." + e.getKey().toLowerCase(java.util.Locale.US));
            fold.label(e.getKey(), String.valueOf(l.size()));
            int shown = 0;
            for (final org.json.JSONObject p : l) {
                final boolean focus = focusKey != null
                        && focusKey.equals(com.atakmap.android.sitrep.PlaceLayer.placeKey(p));
                if (++shown > 40 && !focus) {
                    if (shown == 41)
                        fold.body.addView(body("... and " + (l.size() - 40) + " more on the map.", 14));
                    continue;
                }
                final org.json.JSONArray info = p.optJSONArray("i");
                final String where = info != null && info.length() > 2 ? ", " + info.optString(2) : "";
                final TextView row = body(ago(p.optLong("t", 0)) + ": " + p.optString("n") + where, 14);
                row.setPadding(0, dp(6), 0, dp(6));
                if (focus) {
                    row.setTextColor(CATEGORY);
                    row.setBackgroundColor(0x2200E5FF);
                    focusRow = row;
                }
                row.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (focus) {
                            focusKey = null;
                            refresh();
                            return;
                        }
                        focusKey = com.atakmap.android.sitrep.PlaceLayer.placeKey(p);
                        if (!r.placesOn.contains("calls"))
                            manager.setPlaceOn(r, "calls", true);
                        refresh();
                        goToPlace(p);
                    }
                });
                fold.body.addView(row);
            }
            fold.show();
        }
    }

    /**
     * The line that names a plugin showing this on the map: Open when it is loaded and
     * answers SHOW, a line of text when it is loaded and does not, Get when it is not
     * installed.
     */
    private void pluginLine(LinearLayout into, final Report r, String name, String pkg, final String action,
            String seeText) {
        final boolean loaded = pkg == null || pluginLoaded(pkg);
        if (loaded && action == null) {
            final TextView see = body(seeText != null ? seeText : "See it on the map in " + name + ".", 14);
            see.setTextColor(CATEGORY);
            see.setPadding(0, dp(6), 0, 0);
            into.addView(see);
            return;
        }
        if (!loaded) {
            final TextView see = body(name + " shows this on the map.", 14);
            see.setTextColor(CATEGORY);
            see.setPadding(0, dp(6), 0, 0);
            into.addView(see);
        }
        final Button b = (Button) PluginLayoutInflater.inflate(plugin, R.layout.open_button, null);
        b.setText((loaded ? "Open " : "Get ") + name);
        b.setTextColor(CATEGORY);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (loaded)
                    openPlugin(r, action);
                else
                    openPluginPage();
            }
        });
        into.addView(b);
    }

    /** Where every takwerx plugin is listed with its download. */
    static final String PLUGIN_PAGE = "https://thetaksyndicate.org/plugins/";

    /** Opens the plugin page in the device's browser. */
    private void openPluginPage() {
        try {
            final android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(PLUGIN_PAGE));
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            mapView.getContext().startActivity(i);
        } catch (RuntimeException e) {
            new AlertDialog.Builder(mapView.getContext()).setTitle("No browser")
                    .setMessage("Open " + PLUGIN_PAGE + " in a browser to get the plugin.")
                    .setPositiveButton("OK", null).show();
        }
    }

    /** Puts the report's area in view, then asks the other plugin to open its pane. */
    private void openPlugin(Report r, String action) {
        goTo(r);
        try {
            com.atakmap.android.ipc.AtakBroadcast.getInstance()
                    .sendSystemBroadcast(new android.content.Intent(action));
        } catch (RuntimeException e) {
            Log.w(TAG, "could not open the other plugin", e);
        }
    }

    /** TakwerxSectionHeading cyan. */
    private static final int CATEGORY = 0xFF00E5FF;

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
    /**
     * Which folds are open, per report, for as long as ATAK runs: a new report opens
     * with every section closed (operator, 2026-11-14: "certain sections were
     * expanded on their own" when one report's open folds carried to the next). The
     * main screen's and Settings' folds stay saved in the preferences.
     */
    private final java.util.Map<String, Boolean> folds = new java.util.HashMap<>();

    private boolean isOpen(String pref) {
        if (openId == null)
            return prefs.getBoolean(pref, false);
        final Boolean b = folds.get(openId + "|" + pref);
        return b != null && b;
    }

    private void setOpen(String pref, boolean open) {
        if (openId == null)
            prefs.edit().putBoolean(pref, open).apply();
        else
            folds.put(openId + "|" + pref, open);
    }

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
            open = isOpen(pref);
            final View.OnClickListener flip = new View.OnClickListener() {
                @Override
                public void onClick(View x) {
                    open = !open;
                    setOpen(Fold.this.pref, open);
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
            final Button map = v.findViewById(R.id.row_map);
            setState(map, "Map", r.onMap);
            map.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View x) {
                    manager.setOnMap(r, !r.onMap);
                }
            });
            // A row with a Button can stop delivering list item clicks: the row
            // opens its report itself (Cam Depot, 2026-09).
            v.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View x) {
                    showReport(r.id);
                    goTo(r);
                }
            });
            return v;
        }
    }
}

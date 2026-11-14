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
        prefs.edit().putBoolean("sitrep.fold.places", true)
                .putBoolean("sitrep.fold.places." + category, true).apply();
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
                    pluginLine(f.body, r, s.openName, s.openPackage, s.openAction);
                    if (s.alsoName != null)
                        pluginLine(f.body, r, s.alsoName, s.alsoPackage, s.alsoAction);
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
    private void addLines(LinearLayout into, String sectionKey, List<String> lines) {
        Fold group = null;
        for (String line : lines) {
            if (line.startsWith(Section.GROUP)) {
                final String text = line.substring(Section.GROUP.length());
                final int colon = text.indexOf(':');
                final String name = colon > 0 ? text.substring(0, colon) : text;
                group = new Fold(into, "sitrep.fold." + sectionKey + "." + name.toLowerCase(Locale.US));
                group.head.setText(text);
                group.show();
            } else if (group != null && line.startsWith("  ")) {
                group.body.addView(body(line.trim(), 14));
            } else {
                group = null;
                into.addView(body(line, 14));
            }
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
        box.setHint("City, town or county");
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
                .setTitle("Type a name")
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
        if (d.getWindow() != null)
            d.getWindow().setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                            | android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
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
        box.setHint("Empty = automatic name");
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
                .setTitle("Rename")
                .setView(frame)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dlg, int which) {
                        manager.rename(r, box.getText().toString());
                    }
                })
                .setNegativeButton("Cancel", null)
                .create();
        if (d.getWindow() != null)
            d.getWindow().setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                            | android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        d.show();
        box.requestFocus();
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
            int shown = 0;
            for (org.json.JSONObject p : l) {
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
                        // A tap on a listed place takes the map there, with its
                        // category switched on so it is drawn (operator, 2026-11-13).
                        if (!r.placesOn.contains(c[0]))
                            manager.setPlaceOn(r, c[0], true);
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
     * The line that names a plugin showing this on the map: Open when it is loaded and
     * answers SHOW, a line of text when it is loaded and does not, Get when it is not
     * installed.
     */
    private void pluginLine(LinearLayout into, final Report r, String name, String pkg, final String action) {
        final boolean loaded = pkg == null || pluginLoaded(pkg);
        if (loaded && action == null) {
            final TextView see = body("See it on the map in " + name + ".", 14);
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

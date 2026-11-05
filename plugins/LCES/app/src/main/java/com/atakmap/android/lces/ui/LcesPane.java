package com.atakmap.android.lces.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.lces.irpg.IrpgPages;
import com.atakmap.android.lces.iwi.IwiPane;
import com.atakmap.android.lces.iwi.TraumaManager;
import com.atakmap.android.lces.lessons.Incident;
import com.atakmap.android.lces.lessons.LessonsManager;
import com.atakmap.android.lces.plugin.R;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.conversions.Span;
import com.atakmap.coremap.conversions.SpanUtilities;
import com.atakmap.coremap.maps.coords.GeoPoint;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

/**
 * The pane: a main screen (the map switch, Settings, the search box, the IRPG
 * tiles, then the list of lessons nearest first), a Settings page, and a page being
 * read (an IRPG page or an incident), and IWI's own pages ({@link IwiPane}), all in
 * one view swapped by visibility. It opens on the main screen every time.
 *
 * <p>The pane never filters lessons: {@link LessonsManager} decides what is kept,
 * once, for the list and the map together, and this shows what it is handed.
 */
public final class LcesPane implements PageView.Opener {

    /** The page last read, offered again at the top of the contents. */
    static final String PREF_LAST_PAGE = "lces.lastPage";
    static final String PREF_TEXT_LARGE = "lces.textLarge";

    /** Search results show at most this many incidents. */
    private static final int SEARCH_CAP = 50;
    /** Area presets, in the operator's large unit. */
    private static final int[] AREA_PRESETS = {10, 25, 50, 100};

    private final MapView mapView;
    private final Context pluginContext;
    private final LessonsManager lessonsManager;
    private final SharedPreferences prefs;
    private final View root;
    private final ListView list;
    private final View settingsPage;
    private final View readerPage;
    private final TextView status;
    private final TextView readerTitle;
    private final LinearLayout readerBody;
    private final ScrollView readerScroll;
    private final Button lessons;
    private final Button textSize;
    private final Button prev, next;
    private final EditText search;
    private final PageView pageView;
    private final IncidentPage incidentPage;
    private final View settingsWide;
    private final View readerWide;
    private final RowAdapter rows;
    private final Fold areaFold, typesFold, catsFold, keyFold;
    private IrpgPages pages;
    private LcesDropDown host;
    /** The page being read was opened from the contents; Back returns there. */
    private boolean fromContents;
    private int contentsScroll;
    private IrpgPages.Page current;
    /** The search whose words the page being read marks, or null. */
    private String highlight;
    private LessonsManager.Result lessonsResult;
    /** IWI's pages, in their own block of this pane; null without a trauma manager. */
    private final View iwiHost;
    private final IwiPane iwi;
    /** Settings was opened from the IWI page; its Back goes there. */
    private boolean settingsFromIwi;

    public LcesPane(MapView mapView, Context pluginContext, LessonsManager lessonsManager,
            TraumaManager traumaManager) {
        this.mapView = mapView;
        this.pluginContext = pluginContext;
        this.lessonsManager = lessonsManager;
        prefs = PreferenceManager.getDefaultSharedPreferences(mapView.getContext());
        pageView = new PageView(pluginContext, this);
        incidentPage = new IncidentPage(pluginContext, mapView, new IncidentPage.Actions() {
            @Override
            public void goTo(Incident i) {
                showOnMap(i);
            }
        });

        root = PluginLayoutInflater.inflate(pluginContext, R.layout.main_layout, null);
        status = root.findViewById(R.id.status);
        list = root.findViewById(R.id.list);
        settingsPage = root.findViewById(R.id.settings_page);
        readerPage = root.findViewById(R.id.reader_page);
        readerTitle = root.findViewById(R.id.reader_title);
        readerBody = root.findViewById(R.id.reader_body);
        readerScroll = root.findViewById(R.id.reader_scroll);
        iwiHost = root.findViewById(R.id.iwi_host);

        final View header = PluginLayoutInflater.inflate(pluginContext,
                R.layout.main_header, null);
        list.addHeaderView(header, null, false);
        rows = new RowAdapter();
        list.setAdapter(rows);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                final int k = position - list.getHeaderViewsCount();
                if (k < 0 || k >= rows.getCount())
                    return;
                final Object item = rows.getItem(k);
                if (item instanceof IrpgPages.Hit) {
                    hideKeyboard();
                    fromContents = false;
                    highlight = search.getText().toString().trim();
                    read(((IrpgPages.Hit) item).page.id);
                } else if (item instanceof Incident) {
                    hideKeyboard();
                    showOnMap((Incident) item);
                }
            }
        });

        lessons = header.findViewById(R.id.btn_lessons);
        lessons.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (lessonsManager != null)
                    lessonsManager.setOn(!lessonsManager.isOn());
                refresh();
            }
        });
        header.findViewById(R.id.btn_settings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                settingsFromIwi = false;
                refreshSettings();
                show(settingsPage);
            }
        });

        search = header.findViewById(R.id.search);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                fillList();
                // The map follows the search once typing pauses, not every letter:
                // each change rewrites the map's store.
                search.removeCallbacks(searchToMap);
                search.postDelayed(searchToMap, 400);
            }
        });
        // While the keyboard is up, the pane above it is a strip a few rows tall. The
        // status line grows from one line to three when a search starts, which pushed
        // the box out of that strip; the list then detached its header and the box lost
        // focus after the second letter. Hold the status at its height while typing.
        search.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    status.setMaxLines(Math.max(1, status.getLineCount()));
                    status.setEllipsize(android.text.TextUtils.TruncateAt.END);
                } else {
                    status.setEllipsize(null);
                    status.setMaxLines(Integer.MAX_VALUE);
                }
            }
        });
        search.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    hideKeyboard();
                    return true;
                }
                return false;
            }
        });
        header.findViewById(R.id.btn_clear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                search.setText("");
                hideKeyboard();
            }
        });

        tile(header, R.id.tile_orders, "orders");
        tile(header, R.id.tile_lces, "lces");
        tile(header, R.id.tile_briefing, "briefing");
        tile(header, R.id.tile_denominators, "denominators");
        tile(header, R.id.tile_irpg, IrpgPages.CONTENTS);
        // IWI opens its report straight away (PLAN-LCES decision 9); the book's own
        // Medical Incident Report page stays in the contents.
        header.findViewById(R.id.tile_iwi).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openIwi();
            }
        });

        final View.OnClickListener backToMain = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                back();
            }
        };
        root.findViewById(R.id.btn_settings_back).setOnClickListener(backToMain);
        root.findViewById(R.id.btn_reader_back).setOnClickListener(backToMain);

        final View.OnClickListener wide = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (host != null)
                    host.toggleWide();
            }
        };
        settingsWide = root.findViewById(R.id.btn_settings_wide);
        readerWide = root.findViewById(R.id.btn_reader_wide);
        settingsWide.setOnClickListener(wide);
        readerWide.setOnClickListener(wide);

        prev = root.findViewById(R.id.btn_prev);
        next = root.findViewById(R.id.btn_next);
        prev.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (current != null && book().previous(current) != null)
                    openPage(book().previous(current).id);
            }
        });
        next.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (current != null && book().next(current) != null)
                    openPage(book().next(current).id);
            }
        });

        textSize = root.findViewById(R.id.btn_text_size);
        textSize.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseTextSize();
            }
        });
        final float scale = prefs.getBoolean(PREF_TEXT_LARGE, false) ? 1.25f : 1f;
        pageView.setScale(scale);
        incidentPage.setScale(scale);

        iwi = traumaManager == null ? null : buildIwi(traumaManager);

        final LinearLayout lessonsSettings = root.findViewById(R.id.lessons_settings);
        areaFold = new Fold(lessonsSettings, "area");
        typesFold = new Fold(lessonsSettings, "types");
        catsFold = new Fold(lessonsSettings, "categories");
        keyFold = new Fold(lessonsSettings, "key");
        refreshSettings();
        // A new pane starts with an empty search box, so the map shows the whole rule.
        if (lessonsManager != null)
            lessonsManager.setSearch(null);
    }

    void setHost(LcesDropDown host) {
        this.host = host;
    }

    /** IWI's pages into their block, its trauma center rows onto Settings. */
    private IwiPane buildIwi(TraumaManager traumaManager) {
        final View pages = PluginLayoutInflater.inflate(pluginContext, R.layout.iwi_layout,
                null);
        ((LinearLayout) iwiHost).addView(pages, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        final View settings = PluginLayoutInflater.inflate(pluginContext,
                R.layout.iwi_settings, null);
        ((LinearLayout) root.findViewById(R.id.iwi_settings_host)).addView(settings);
        return new IwiPane(pluginContext, mapView, pages, settings, traumaManager,
                new IwiPane.Exits() {
                    @Override
                    public void leaveIwi() {
                        show(list);
                        refresh();
                    }

                    @Override
                    public void openSettings() {
                        settingsFromIwi = true;
                        refreshSettings();
                        show(settingsPage);
                    }

                    @Override
                    public void toggleWide() {
                        if (host != null)
                            host.toggleWide();
                    }
                });
    }

    /** The IWI tile, or a tap on this device's IWI marker: IWI's page, current. */
    public void openIwi() {
        if (iwi == null) {
            // No trauma manager means no IWI state either; the book's report page.
            read("mir");
            return;
        }
        hideKeyboard();
        settingsFromIwi = false;
        show(iwiHost);
        iwi.open();
    }

    /** The drop-down closed: an IWI map pick lets go of the map. */
    void onClosed() {
        if (iwi != null)
            iwi.onClosed();
    }

    void dispose() {
        if (iwi != null)
            iwi.dispose();
    }

    /** The host changed size. The wide buttons are IWI's icon, the same either way. */
    void onResized() {
    }

    /**
     * Back from a page opened from the contents goes to the contents, from any other
     * page to the main screen; on the main screen it closes the pane.
     */
    boolean back() {
        if (list.getVisibility() == View.VISIBLE)
            return false;
        if (iwi != null && iwiHost.getVisibility() == View.VISIBLE) {
            iwi.back();
            return true;
        }
        if (iwi != null && settingsFromIwi && settingsPage.getVisibility() == View.VISIBLE) {
            settingsFromIwi = false;
            show(iwiHost);
            iwi.refreshSettings();
            return true;
        }
        if (fromContents && readerPage.getVisibility() == View.VISIBLE) {
            fromContents = false;
            highlight = null;
            read(IrpgPages.CONTENTS);
            readerScroll.post(new Runnable() {
                @Override
                public void run() {
                    readerScroll.scrollTo(0, contentsScroll);
                }
            });
            return true;
        }
        show(list);
        return true;
    }

    public View getView() {
        return root;
    }

    /** Called each time the pane is shown: back to the main screen, current state. */
    public void reset() {
        fromContents = false;
        settingsFromIwi = false;
        show(list);
        refresh();
    }

    /** A new pass from the manager: the list, the status line and the Settings rows. */
    public void onLessons(LessonsManager.Result r) {
        lessonsResult = r;
        refresh();
        if (settingsPage.getVisibility() == View.VISIBLE)
            refreshSettings();
    }

    /** An incident tapped on the map, or picked from the list: its page in the pane. */
    public void openIncident(Incident i) {
        if (i == null)
            return;
        hideKeyboard();
        fromContents = false;
        current = null;
        readerTitle.setText(i.name);
        final LessonsManager.Result r = lessonsResult;
        final GeoPoint origin = r == null ? null : r.origin;
        final double m = origin == null ? Double.NaN
                : origin.distanceTo(new GeoPoint(i.lat, i.lon));
        incidentPage.render(i, readerBody, m, pluginContext.getString(
                r != null && r.fromCenter ? R.string.the_map_center : R.string.your_location),
                r == null ? "" : r.sourceEdited);
        root.findViewById(R.id.reader_nav).setVisibility(View.GONE);
        readerScroll.scrollTo(0, 0);
        show(readerPage);
    }

    private void showOnMap(Incident i) {
        if (lessonsManager == null)
            return;
        // Shown on the map means the layer is on: going there to find nothing is the
        // one thing the list must never do.
        if (!lessonsManager.isOn()) {
            lessonsManager.setOn(true);
            refresh();
        }
        lessonsManager.goTo(i);
    }

    private IrpgPages book() {
        if (pages == null)
            pages = IrpgPages.load(pluginContext);
        return pages;
    }

    private void refresh() {
        setState(lessons, pluginContext.getString(R.string.lessons),
                lessonsManager != null && lessonsManager.isOn());
        fillList();
    }

    /** Hands the search to the map, so the map shows only what the list found. */
    private final Runnable searchToMap = new Runnable() {
        @Override
        public void run() {
            if (lessonsManager != null)
                lessonsManager.setSearch(search.getText().toString());
        }
    };

    /** The list under the tiles: the lessons nearest first, or what a search found. */
    private void fillList() {
        final String q = search.getText().toString().trim();
        final List<Object> items = new ArrayList<>();
        if (q.length() >= 2) {
            final List<IrpgPages.Hit> found = book().search(q);
            final List<Incident> incidents = lessonsManager == null
                    ? new ArrayList<Incident>() : lessonsManager.search(q);
            if (!found.isEmpty()) {
                items.add(pluginContext.getString(R.string.heading_irpg_search));
                items.addAll(found);
            }
            if (!incidents.isEmpty()) {
                items.add(pluginContext.getString(R.string.heading_lessons_search));
                items.addAll(incidents.subList(0, Math.min(SEARCH_CAP, incidents.size())));
            }
            final StringBuilder s = new StringBuilder(found.isEmpty()
                    ? pluginContext.getString(R.string.results_none, q)
                    : pluginContext.getString(R.string.results_some, found.size(), q));
            if (!incidents.isEmpty()) {
                s.append(' ').append(incidents.size() > SEARCH_CAP
                        ? "The " + SEARCH_CAP + " best of " + count(incidents.size())
                                + " lessons that match are under them."
                        : count(incidents.size()) + (incidents.size() == 1
                                ? " lesson matches too." : " lessons match too."));
                s.append(' ').append(lessonsManager.isOn()
                        ? (incidents.size() == 1 ? "The map shows only that one."
                                : "The map shows only those " + count(incidents.size()) + ".")
                                + " Clear to show the rest."
                        : pluginContext.getString(R.string.search_map_off));
            }
            status.setText(s);
        } else {
            final LessonsManager.Result r = lessonsResult;
            if (r != null && r.ready && !r.nearest.isEmpty()) {
                items.add(pluginContext.getString(r.fromCenter
                        ? R.string.heading_lessons_near_center : R.string.heading_lessons_near_you));
                for (LessonsManager.Near n : r.nearest)
                    items.add(n.incident);
            }
            status.setText(lessonsStatus(r));
        }
        rows.set(items);
    }

    /** Every reason something is not shown, each in its own words (plugin-ui section 6). */
    private String lessonsStatus(LessonsManager.Result r) {
        if (r == null || !r.ready)
            return pluginContext.getString(R.string.lessons_loading);
        final StringBuilder b = new StringBuilder();
        if (lessonsManager != null && !lessonsManager.isOn())
            b.append(pluginContext.getString(R.string.lessons_map_off)).append(' ');
        final double area = lessonsManager == null ? -1 : lessonsManager.areaMeters();
        if (r.kept == 0) {
            b.append(pluginContext.getString(R.string.lessons_none));
        } else if (area > 0) {
            b.append(count(r.kept)).append(r.kept == 1 ? " lesson within " : " lessons within ")
                    .append(areaLabel(area)).append('.');
            if (r.kept > r.nearest.size())
                b.append(" The nearest ").append(r.nearest.size()).append(" are listed.");
        } else if (r.kept > r.nearest.size()) {
            b.append("Nearest ").append(r.nearest.size()).append(" of ").append(count(r.kept))
                    .append(" lessons.");
        } else {
            b.append(count(r.kept)).append(r.kept == 1 ? " lesson." : " lessons, nearest first.");
        }
        if (lessonsManager != null) {
            final int types = lessonsManager.shownTypes().size();
            final Set<String> off = lessonsManager.hiddenCategories();
            int cats = 0;
            for (String c : r.categoryCounts.keySet())
                if (!off.contains(c))
                    cats++;
            final List<String> hidden = new ArrayList<>();
            if (types < Incident.Type.values().length)
                hidden.add(types + " of " + Incident.Type.values().length + " types");
            if (cats < r.categoryCounts.size())
                hidden.add(cats == 1 ? "only " + categoryLabel(firstShown(r, off))
                        : cats + " of " + r.categoryCounts.size() + " categories");
            if (!hidden.isEmpty())
                b.append(" Showing ").append(android.text.TextUtils.join(", ", hidden))
                        .append('.');
        }
        if (r.noFix)
            b.append(' ').append(pluginContext.getString(R.string.lessons_no_fix));
        return b.toString();
    }

    private static String firstShown(LessonsManager.Result r, Set<String> off) {
        for (String c : r.categoryCounts.keySet())
            if (!off.contains(c))
                return c;
        return "";
    }

    private static String count(int n) {
        return NumberFormat.getIntegerInstance(Locale.US).format(n);
    }

    /** "25 mi", in the large unit the operator uses, whole numbers only. */
    private static String areaLabel(double meters) {
        final double n = meters / Units.bigToMeters(1);
        return Math.round(n) + " " + Units.bigLabel();
    }

    // ---- Settings ---------------------------------------------------------------------

    private void refreshSettings() {
        final boolean large = prefs.getBoolean(PREF_TEXT_LARGE, false);
        textSize.setText(pluginContext.getString(R.string.text_size) + ": "
                + pluginContext.getString(large ? R.string.text_large : R.string.text_normal));
        if (iwi != null)
            iwi.refreshSettings();
        if (lessonsManager == null)
            return;
        buildArea();
        buildTypes();
        buildCategories();
        buildKey();
    }

    private void buildArea() {
        final double area = lessonsManager.areaMeters();
        final boolean fromCenter = lessonsManager.fromCenterPref();
        final LessonsManager.Result r = lessonsResult;
        final String where = pluginContext.getString(fromCenter ? R.string.map_center
                : R.string.my_location);
        String head = area <= 0 ? pluginContext.getString(R.string.area_everything)
                : "Within " + areaLabel(area) + " of " + where;
        if (area > 0 && !fromCenter && r != null && r.noFix)
            head = "Within " + areaLabel(area) + " - no GPS fix, measuring from the map center";
        areaFold.label(pluginContext.getString(R.string.area), head);

        final LinearLayout body = areaFold.body;
        body.removeAllViews();
        final List<Button> tiles = new ArrayList<>();
        final Button all = tile(pluginContext.getString(R.string.area_everything), area <= 0);
        all.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                lessonsManager.setAreaMeters(-1);
                refreshSettings();
            }
        });
        tiles.add(all);
        for (final int n : AREA_PRESETS) {
            final double m = Units.bigToMeters(n);
            final Button b = tile(n + " " + Units.bigLabel(), area > 0 && Math.abs(area - m) < 1);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    lessonsManager.setAreaMeters(Units.bigToMeters(n));
                    refreshSettings();
                }
            });
            tiles.add(b);
        }
        addTileRows(body, tiles, 3);
        final Button from = tile(pluginContext.getString(R.string.measuring_from) + ": " + where,
                false);
        from.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                lessonsManager.setFromCenter(!lessonsManager.fromCenterPref());
                refreshSettings();
            }
        });
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        body.addView(from, lp);
    }

    private void buildTypes() {
        final Set<Incident.Type> on = lessonsManager.shownTypes();
        typesFold.label(pluginContext.getString(R.string.types),
                on.size() + " of " + Incident.Type.values().length);
        final LinearLayout body = typesFold.body;
        body.removeAllViews();
        final LessonsManager.Result r = lessonsResult;
        final List<Button> tiles = new ArrayList<>();
        for (final Incident.Type t : Incident.Type.values()) {
            final boolean shown = on.contains(t);
            final SpannableStringBuilder sb = new SpannableStringBuilder();
            final Integer n = r == null ? null : r.counts.get(t);
            colored(sb, t.label + (n == null ? "" : " (" + count(n) + ")"), t.color);
            sb.append('\n');
            colored(sb, shown ? "ON" : "OFF", pluginContext.getResources().getColor(
                    shown ? R.color.state_on : R.color.state_off));
            final Button b = new Button(pluginContext, null, 0, R.style.TakwerxTwoLineTile);
            b.setText(sb);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    lessonsManager.setTypeShown(t, !lessonsManager.shownTypes().contains(t));
                    refreshSettings();
                }
            });
            tiles.add(b);
        }
        addTileRows(body, tiles, 2);
    }

    /**
     * The LLC's own categories (Aviation, Chainsaw, Medical ...), with All and None
     * so one category alone is two taps.
     */
    private void buildCategories() {
        final LessonsManager.Result r = lessonsResult;
        final Map<String, Integer> cats = r == null
                ? java.util.Collections.<String, Integer>emptyMap() : r.categoryCounts;
        final Set<String> off = lessonsManager.hiddenCategories();
        int shown = 0;
        for (String c : cats.keySet())
            if (!off.contains(c))
                shown++;
        catsFold.label(pluginContext.getString(R.string.categories),
                cats.isEmpty() ? null : shown + " of " + cats.size());
        final LinearLayout body = catsFold.body;
        body.removeAllViews();

        final List<Button> bulk = new ArrayList<>();
        final Button all = new Button(pluginContext, null, 0, R.style.TakwerxButton);
        all.setText(R.string.categories_all);
        all.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                lessonsManager.setHiddenCategories(new HashSet<String>());
                refreshSettings();
            }
        });
        bulk.add(all);
        final Button none = new Button(pluginContext, null, 0, R.style.TakwerxButton);
        none.setText(R.string.categories_none);
        none.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                lessonsManager.setHiddenCategories(new HashSet<>(cats.keySet()));
                refreshSettings();
            }
        });
        bulk.add(none);
        addTileRows(body, bulk, 2);

        final List<Button> tiles = new ArrayList<>();
        for (final Map.Entry<String, Integer> e : cats.entrySet()) {
            final boolean isOn = !off.contains(e.getKey());
            final SpannableStringBuilder sb = new SpannableStringBuilder();
            sb.append(categoryLabel(e.getKey())).append(" (").append(count(e.getValue()))
                    .append(")\n");
            colored(sb, isOn ? "ON" : "OFF", pluginContext.getResources().getColor(
                    isOn ? R.color.state_on : R.color.state_off));
            final Button b = new Button(pluginContext, null, 0, R.style.TakwerxTwoLineTile);
            b.setText(sb);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    lessonsManager.setCategoryShown(e.getKey(),
                            lessonsManager.hiddenCategories().contains(e.getKey()));
                    refreshSettings();
                }
            });
            tiles.add(b);
        }
        addTileRows(body, tiles, 2);
    }

    /** The LLC's category, shortened where its own name would not fit on a tile. */
    static String categoryLabel(String c) {
        switch (c) {
            case "":
                return "Not Given";
            case "Equipment/Facilities Damage or Malfunction":
                return "Equipment Damage";
            case "Prescribed Fire (Rx) Wildfire Declaration":
                return "Rx Declared Wildfire";
            case "Tools (hand and power)":
                return "Hand and Power Tools";
            case "Motor Vehicle Accident":
                return "Vehicle Accident";
            default:
                return c;
        }
    }

    /** Built from the same colors the map's icons are drawn in, so the two agree. */
    private void buildKey() {
        keyFold.label(pluginContext.getString(R.string.map_key), null);
        final LinearLayout body = keyFold.body;
        body.removeAllViews();
        final Set<Incident.Type> on = lessonsManager.shownTypes();
        for (Incident.Type t : Incident.Type.values()) {
            if (!on.contains(t))
                continue;
            final SpannableStringBuilder sb = new SpannableStringBuilder();
            colored(sb, "●  ", t.color);
            sb.append(t.label);
            final TextView line = new TextView(pluginContext);
            line.setText(sb);
            line.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
            line.setTextColor(0xFFFFFFFF);
            line.setPadding(dp(8), dp(4), 0, dp(4));
            body.addView(line);
        }
    }

    private Button tile(String text, boolean chosen) {
        final Button b = new Button(pluginContext, null, 0, R.style.TakwerxButton);
        b.setText(text);
        b.setTextColor(chosen ? pluginContext.getResources().getColor(R.color.state_on)
                : 0xFFFFFFFF);
        return b;
    }

    private void addTileRows(LinearLayout body, List<Button> tiles, int across) {
        LinearLayout row = null;
        for (int k = 0; k < tiles.size(); k++) {
            if (k % across == 0) {
                row = new LinearLayout(pluginContext);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setBaselineAligned(false);
                final LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                rp.topMargin = dp(6);
                body.addView(row, rp);
            }
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.MATCH_PARENT, 1f);
            if (k % across != 0)
                lp.leftMargin = dp(6);
            row.addView(tiles.get(k), lp);
        }
        // An unfilled last row keeps its tiles the width of the rest.
        if (row != null) {
            for (int k = tiles.size() % across; k != 0 && k < across; k++) {
                final View gap = new View(pluginContext);
                // MATCH_PARENT, not a fixed height: a 1 px spacer sets the row's
                // height and squeezes the tile beside it to 1 px.
                final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.MATCH_PARENT, 1f);
                lp.leftMargin = dp(6);
                row.addView(gap, lp);
            }
        }
    }

    private static void colored(SpannableStringBuilder sb, String s, int color) {
        final int start = sb.length();
        sb.append(s);
        sb.setSpan(new ForegroundColorSpan(color), start, sb.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private int dp(int v) {
        return Math.round(v * pluginContext.getResources().getDisplayMetrics().density);
    }

    /** One Settings row: a head reading "Name: value", an arrow, a body (plugin-ui). */
    private final class Fold {
        final Button head;
        final ImageButton chevron;
        final LinearLayout body;
        final String pref;
        boolean open;

        Fold(LinearLayout parent, String name) {
            final View v = PluginLayoutInflater.inflate(pluginContext, R.layout.fold_row, null);
            parent.addView(v);
            head = v.findViewById(R.id.fold_head);
            chevron = v.findViewById(R.id.fold_chev);
            body = v.findViewById(R.id.fold_body);
            chevron.setContentDescription(pluginContext.getString(R.string.show_settings));
            pref = "lces.fold." + name;
            open = prefs.getBoolean(pref, false);
            final View.OnClickListener flip = new View.OnClickListener() {
                @Override
                public void onClick(View x) {
                    open = !open;
                    prefs.edit().putBoolean(pref, open).apply();
                    showFold();
                }
            };
            head.setOnClickListener(flip);
            chevron.setOnClickListener(flip);
            showFold();
        }

        void label(String name, String value) {
            head.setText(value == null ? name : name + ": " + value);
        }

        void showFold() {
            chevron.setRotation(open ? 180f : 0f);
            body.setVisibility(open ? View.VISIBLE : View.GONE);
        }
    }

    // ---- reading ----------------------------------------------------------------------

    private void setState(Button b, String thing, boolean on) {
        b.setText(thing + (on ? " ON" : " OFF"));
        b.setTextColor(pluginContext.getResources().getColor(
                on ? R.color.state_on : R.color.state_off));
    }

    private void tile(View header, int id, final String pageId) {
        header.findViewById(id).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                fromContents = false;
                highlight = null;
                read(pageId);
            }
        });
    }

    /** A contents line, a "see page N", a Continue button, Previous or Next. */
    @Override
    public void openPage(String pageId) {
        if (current != null && IrpgPages.CONTENTS.equals(current.id)) {
            fromContents = true;
            contentsScroll = readerScroll.getScrollY();
        }
        highlight = null;
        read(pageId);
    }

    @Override
    public void openPrintedPage(int n) {
        final IrpgPages.Page p = book().byPrintedPage(n);
        if (p != null)
            openPage(p.id);
    }

    private void read(String pageId) {
        final IrpgPages.Page page = book().get(pageId);
        if (page == null)
            return;
        current = page;
        readerTitle.setText(page.title);
        final boolean contents = IrpgPages.CONTENTS.equals(page.id);
        pageView.render(book(), page, readerBody, highlight,
                contents ? prefs.getString(PREF_LAST_PAGE, null) : null);
        if (!contents)
            prefs.edit().putString(PREF_LAST_PAGE, page.id).apply();
        final IrpgPages.Page before = book().previous(page);
        final IrpgPages.Page after = book().next(page);
        root.findViewById(R.id.reader_nav).setVisibility(contents ? View.GONE : View.VISIBLE);
        prev.setEnabled(before != null);
        prev.setText(before == null ? pluginContext.getString(R.string.previous) : "◀ " + before.title);
        next.setEnabled(after != null);
        next.setText(after == null ? pluginContext.getString(R.string.next) : after.title + " ▶");
        readerScroll.scrollTo(0, 0);
        show(readerPage);
    }

    private void hideKeyboard() {
        final InputMethodManager imm = (InputMethodManager)
                mapView.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null)
            imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
        search.clearFocus();
    }

    /** Settings: Normal or Large text on the pages. A dialog on the map's context. */
    private void chooseTextSize() {
        final boolean large = prefs.getBoolean(PREF_TEXT_LARGE, false);
        final String[] names = {pluginContext.getString(R.string.text_normal),
                pluginContext.getString(R.string.text_large)};
        new AlertDialog.Builder(mapView.getContext())
                .setTitle(pluginContext.getString(R.string.text_size))
                .setSingleChoiceItems(names, large ? 1 : 0, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        prefs.edit().putBoolean(PREF_TEXT_LARGE, which == 1).apply();
                        pageView.setScale(which == 1 ? 1.25f : 1f);
                        incidentPage.setScale(which == 1 ? 1.25f : 1f);
                        refreshSettings();
                        dialog.dismiss();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void show(View page) {
        // The status line belongs to the main screen; a page being read gets the room.
        status.setVisibility(page == list ? View.VISIBLE : View.GONE);
        list.setVisibility(page == list ? View.VISIBLE : View.GONE);
        settingsPage.setVisibility(page == settingsPage ? View.VISIBLE : View.GONE);
        readerPage.setVisibility(page == readerPage ? View.VISIBLE : View.GONE);
        iwiHost.setVisibility(page == iwiHost ? View.VISIBLE : View.GONE);
        if (page != list)
            hideKeyboard();
    }

    // ---- the list ---------------------------------------------------------------------

    /**
     * The rows under the tiles: cyan headings, IRPG search hits (the page in its
     * section's color and the line it matched), and incidents (name and distance, then
     * the kind in its map color, the year and the state, and a Details button).
     */
    private final class RowAdapter extends BaseAdapter {
        private static final int HEADING = 0, HIT = 1, INCIDENT = 2;
        private List<Object> items = new ArrayList<>();

        void set(List<Object> next) {
            items = next;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return 3;
        }

        @Override
        public int getItemViewType(int position) {
            final Object o = items.get(position);
            return o instanceof String ? HEADING : o instanceof IrpgPages.Hit ? HIT : INCIDENT;
        }

        @Override
        public boolean isEnabled(int position) {
            return getItemViewType(position) != HEADING;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final Object o = items.get(position);
            switch (getItemViewType(position)) {
                case HEADING: {
                    final View v = convertView != null ? convertView
                            : PluginLayoutInflater.inflate(pluginContext, R.layout.heading_row, null);
                    ((TextView) v.findViewById(R.id.heading_text)).setText((String) o);
                    return v;
                }
                case HIT: {
                    final View v = convertView != null ? convertView
                            : PluginLayoutInflater.inflate(pluginContext, R.layout.search_row, null);
                    final IrpgPages.Hit h = (IrpgPages.Hit) o;
                    final TextView title = v.findViewById(R.id.hit_title);
                    title.setText(h.count == 0 ? h.page.title : h.page.title + " · "
                            + pluginContext.getResources().getQuantityString(R.plurals.matches,
                                    h.count, h.count));
                    title.setTextColor(PageView.paperColor(h.page.color));
                    ((TextView) v.findViewById(R.id.hit_snippet)).setText(h.snippet);
                    return v;
                }
                default: {
                    final View v = convertView != null ? convertView
                            : PluginLayoutInflater.inflate(pluginContext, R.layout.lesson_row, null);
                    final Incident i = (Incident) o;
                    ((TextView) v.findViewById(R.id.lesson_name)).setText(i.name);
                    final LessonsManager.Result r = lessonsResult;
                    final GeoPoint origin = r == null ? null : r.origin;
                    ((TextView) v.findViewById(R.id.lesson_distance)).setText(origin == null ? ""
                            : SpanUtilities.formatType(Units.type(),
                                    origin.distanceTo(new GeoPoint(i.lat, i.lon)), Span.METER));
                    final SpannableStringBuilder sb = new SpannableStringBuilder();
                    colored(sb, i.type.label, i.type.color);
                    sb.append("  ·  ").append(i.year());
                    if (!i.state.isEmpty())
                        sb.append("  ·  ").append(i.state);
                    ((TextView) v.findViewById(R.id.lesson_line2)).setText(sb);
                    // A row holding a Button needs the button's own listener.
                    v.findViewById(R.id.lesson_details).setOnClickListener(
                            new View.OnClickListener() {
                                @Override
                                public void onClick(View b) {
                                    openIncident(i);
                                }
                            });
                    return v;
                }
            }
        }
    }
}

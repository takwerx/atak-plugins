package com.atakmap.android.lces.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.lces.irpg.IrpgPages;
import com.atakmap.android.lces.plugin.R;
import com.atakmap.android.maps.MapView;

import java.util.ArrayList;
import java.util.List;

/**
 * The pane: a main screen (the map switch, Settings, the search box, the six
 * IRPG tiles, then the list), a Settings page, and a page being read, all in one
 * view swapped by visibility. It opens on the main screen every time.
 */
public final class LcesPane implements PageView.Opener {

    static final String PREF_LESSONS_ON = "lces.lessonsOn";
    /** The page last read, offered again at the top of the contents. */
    static final String PREF_LAST_PAGE = "lces.lastPage";
    static final String PREF_TEXT_LARGE = "lces.textLarge";

    private final MapView mapView;
    private final Context pluginContext;
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
    private final Button settingsWide;
    private final Button readerWide;
    private final HitAdapter hits;
    private IrpgPages pages;
    private LcesDropDown host;
    /** The page being read was opened from the contents; Back returns there. */
    private boolean fromContents;
    private int contentsScroll;
    private IrpgPages.Page current;
    /** The search whose words the page being read marks, or null. */
    private String highlight;

    public LcesPane(MapView mapView, Context pluginContext) {
        this.mapView = mapView;
        this.pluginContext = pluginContext;
        prefs = PreferenceManager.getDefaultSharedPreferences(mapView.getContext());
        pageView = new PageView(pluginContext, this);

        root = PluginLayoutInflater.inflate(pluginContext, R.layout.main_layout, null);
        status = root.findViewById(R.id.status);
        list = root.findViewById(R.id.list);
        settingsPage = root.findViewById(R.id.settings_page);
        readerPage = root.findViewById(R.id.reader_page);
        readerTitle = root.findViewById(R.id.reader_title);
        readerBody = root.findViewById(R.id.reader_body);
        readerScroll = root.findViewById(R.id.reader_scroll);

        final View header = PluginLayoutInflater.inflate(pluginContext,
                R.layout.main_header, null);
        list.addHeaderView(header, null, false);
        hits = new HitAdapter();
        list.setAdapter(hits);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                final int k = position - list.getHeaderViewsCount();
                if (k < 0 || k >= hits.getCount())
                    return;
                hideKeyboard();
                fromContents = false;
                highlight = search.getText().toString().trim();
                read(hits.getItem(k).page.id);
            }
        });

        lessons = header.findViewById(R.id.btn_lessons);
        lessons.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.edit().putBoolean(PREF_LESSONS_ON,
                        !prefs.getBoolean(PREF_LESSONS_ON, false)).apply();
                refresh();
            }
        });
        header.findViewById(R.id.btn_settings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
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
                runSearch(s.toString().trim());
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
        tile(header, R.id.tile_watchouts, "watchouts");
        tile(header, R.id.tile_lces, "lces");
        tile(header, R.id.tile_briefing, "briefing");
        tile(header, R.id.tile_denominators, "denominators");
        tile(header, R.id.tile_irpg, IrpgPages.CONTENTS);

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
        pageView.setScale(prefs.getBoolean(PREF_TEXT_LARGE, false) ? 1.25f : 1f);
        refreshSettings();
    }

    void setHost(LcesDropDown host) {
        this.host = host;
    }

    /** The host changed size: the Wide buttons say what a tap will do now. */
    void onResized() {
        final int label = host != null && host.isWide() ? R.string.half : R.string.wide;
        settingsWide.setText(label);
        readerWide.setText(label);
    }

    /**
     * Back from a page opened from the contents goes to the contents, from any other
     * page to the main screen; on the main screen it closes the pane.
     */
    boolean back() {
        if (list.getVisibility() == View.VISIBLE)
            return false;
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
        show(list);
        refresh();
    }

    private IrpgPages book() {
        if (pages == null)
            pages = IrpgPages.load(pluginContext);
        return pages;
    }

    private void refresh() {
        setState(lessons, pluginContext.getString(R.string.lessons),
                prefs.getBoolean(PREF_LESSONS_ON, false));
        final String q = search.getText().toString().trim();
        if (q.isEmpty())
            status.setText(R.string.status_no_layer);
    }

    private void refreshSettings() {
        final boolean large = prefs.getBoolean(PREF_TEXT_LARGE, false);
        textSize.setText(pluginContext.getString(R.string.text_size) + ": "
                + pluginContext.getString(large ? R.string.text_large : R.string.text_normal));
    }

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

    private void runSearch(String q) {
        if (q.length() < 2) {
            hits.set(new ArrayList<IrpgPages.Hit>());
            status.setText(R.string.status_no_layer);
            return;
        }
        final List<IrpgPages.Hit> found = book().search(q);
        hits.set(found);
        status.setText(found.isEmpty()
                ? pluginContext.getString(R.string.results_none, q)
                : pluginContext.getString(R.string.results_some, found.size(), q));
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
        if (page != list)
            hideKeyboard();
    }

    /** Search hits under the tiles: the page in its section's color, the line it matched. */
    private final class HitAdapter extends BaseAdapter {
        private List<IrpgPages.Hit> items = new ArrayList<>();

        void set(List<IrpgPages.Hit> hits) {
            items = hits;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public IrpgPages.Hit getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final View v = convertView != null ? convertView
                    : PluginLayoutInflater.inflate(pluginContext, R.layout.search_row, null);
            final IrpgPages.Hit h = items.get(position);
            final TextView title = v.findViewById(R.id.hit_title);
            title.setText(h.count == 0 ? h.page.title : h.page.title + " · "
                    + pluginContext.getResources().getQuantityString(R.plurals.matches, h.count, h.count));
            title.setTextColor(PageView.paperColor(h.page.color));
            ((TextView) v.findViewById(R.id.hit_snippet)).setText(h.snippet);
            return v;
        }
    }
}

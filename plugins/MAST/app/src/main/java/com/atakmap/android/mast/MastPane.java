package com.atakmap.android.mast;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.preference.PreferenceManager;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.atak.plugins.impl.AtakPluginRegistry;
import com.atak.plugins.impl.PluginLayoutInflater;
import com.atak.plugins.impl.PluginMapComponent;
import com.atakmap.android.dropdown.DropDown;
import com.atakmap.android.dropdown.DropDownReceiver;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.mast.plugin.R;
import com.atakmap.coremap.log.Log;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The MAST pane: the front door to the mission plugins (PLAN-MAST-v0.1, direction
 * change 2026-11-22). A pinned Mission button over either the mission picker or the
 * picked mission's plugins, one tile each with the plugin's own toolbar icon. The
 * last mission is remembered, so the toolbar button from inside FOBS lands back on
 * the Wildfire page.
 *
 * <p>A tile opens its plugin the way its toolbar button does: ATAK registers a local
 * receiver for {@code <identifier>.ACTION} on every toolbar item it is given
 * ({@code ATAKUIService.addToolbarItem}, 5.8 jar) and calls the item's own listener,
 * and every takwerx plugin uses its package as the identifier. So no plugin needs a
 * MAST-specific action, FOBS included, which has none.
 *
 * <p>Every dialog here is built on the MapView's context; the plugin context cannot
 * own a window and takes ATAK down with it.
 */
public class MastPane extends DropDownReceiver implements DropDown.OnStateListener {

    private static final String TAG = "MAST.Pane";
    private static final String PREF_MISSION = "mast_mission";
    /** Which tiles a mission page shows; remembered like the mission. */
    private static final String PREF_VIEW = "mast_view";
    private static final String VIEW_TOOLS = "tools";
    private static final String VIEW_PLUGINS = "plugins";
    /** TakwerxSectionHeading's cyan. */
    private static final int MISSION_CYAN = 0xFF00E5FF;
    private static final String MARKET_PKG = "com.atakmap.android.takwerxmarket.plugin";

    private final Context plugin;
    private final Context host;
    private final SharedPreferences prefs;
    private final View root;
    private final Button missionBtn;
    private final ScrollView scroll;
    private final TextView section;
    private final LinearLayout tiles;
    private final View viewRow;
    private final Button showToolsBtn;
    private final Button showPluginsBtn;
    private List<Missions.Mission> missions;
    private boolean picking;

    public MastPane(MapView mapView, Context pluginContext) {
        super(mapView);
        this.plugin = pluginContext;
        this.host = mapView.getContext();
        this.prefs = PreferenceManager.getDefaultSharedPreferences(host);

        root = PluginLayoutInflater.inflate(plugin, R.layout.mast_pane, null);
        missionBtn = root.findViewById(R.id.mast_mission);
        scroll = root.findViewById(R.id.mast_scroll);
        section = root.findViewById(R.id.mast_section);
        tiles = root.findViewById(R.id.mast_tiles);
        viewRow = root.findViewById(R.id.mast_view_row);
        showToolsBtn = root.findViewById(R.id.mast_show_tools);
        showPluginsBtn = root.findViewById(R.id.mast_show_plugins);
        missionBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPicker();
            }
        });
        showToolsBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.edit().putString(PREF_VIEW, VIEW_TOOLS).apply();
                showCurrent();
            }
        });
        showPluginsBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.edit().putString(PREF_VIEW, VIEW_PLUGINS).apply();
                showCurrent();
            }
        });
    }

    public void setMissions(List<Missions.Mission> missions) {
        this.missions = missions;
    }

    private Missions.Mission current() {
        if (missions == null)
            return null;
        final String id = prefs.getString(PREF_MISSION, null);
        for (Missions.Mission m : missions)
            if (m.id.equals(id))
                return m;
        return null;
    }

    // ---- the two pages --------------------------------------------------------------

    /** The remembered mission's page, or the picker when none is remembered yet. */
    private void showCurrent() {
        final Missions.Mission m = current();
        if (m == null) {
            showPicker();
            return;
        }
        picking = false;
        missionBtn.setText(missionLabel(m));
        tiles.removeAllViews();
        viewRow.setVisibility(View.VISIBLE);
        final boolean showTools = VIEW_TOOLS.equals(prefs.getString(PREF_VIEW, VIEW_PLUGINS));
        showToolsBtn.setSelected(showTools);
        showPluginsBtn.setSelected(!showTools);
        final String view = showTools ? VIEW_TOOLS : VIEW_PLUGINS;
        final List<String> ids = page(m, view);
        section.setText(plugin.getString(showTools ? R.string.section_tools
                : R.string.section_plugins, m.name));
        for (int i = 0; i < ids.size(); i++) {
            final String id = ids.get(i);
            final Button b;
            if (showTools) {
                final AtakTools.Tool t = AtakTools.load(host, id);
                if (t == null)
                    continue; // this ATAK has no such tool
                b = toolTile(t);
            } else {
                b = pluginTile(new Missions.Plugin(pluginName(id), id));
            }
            final int at = i;
            b.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    editTile(m, view, at);
                    return true;
                }
            });
            addTile(tiles, b);
        }
        // Blank slots after the last tile, like an empty place in ATAK's toolbar:
        // always at least one, and enough to finish the row.
        final int filled = countTiles(tiles);
        final int blanks = 3 - (filled % 3);
        for (int i = 0; i < blanks; i++)
            addTile(tiles, blankTile(m, view));
        padLastRow(tiles);
        toTop();
    }

    private void showPicker() {
        picking = true;
        viewRow.setVisibility(View.GONE);
        final Missions.Mission m = current();
        missionBtn.setText(m == null ? plugin.getString(R.string.mission_none)
                : missionLabel(m));
        section.setText(R.string.section_pick);
        tiles.removeAllViews();
        if (missions != null)
            for (final Missions.Mission mission : missions)
                addTile(tiles, missionTile(mission));
        padLastRow(tiles);
        toTop();
    }

    /**
     * Back to the top once the new tiles are laid out; a scrollTo before layout kept
     * the picker's scroll and hid the page heading (s10-dev-3, 2026-11-22).
     */
    private void toTop() {
        scroll.post(new Runnable() {
            @Override
            public void run() {
                scroll.scrollTo(0, 0);
            }
        });
    }

    /**
     * "Mission: Search and Rescue" with the mission's name in the section cyan, so the
     * mission in use is the first thing the eye finds at the top of the pane.
     */
    private CharSequence missionLabel(Missions.Mission m) {
        final String text = plugin.getString(R.string.mission_current, m.name);
        final int at = text.lastIndexOf(m.name);
        final SpannableString s = new SpannableString(text);
        if (at >= 0)
            s.setSpan(new ForegroundColorSpan(MISSION_CYAN), at, at + m.name.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }

    // ---- tiles ----------------------------------------------------------------------

    private Button missionTile(final Missions.Mission m) {
        final Button b = tile(m.name);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.edit().putString(PREF_MISSION, m.id).apply();
                showCurrent();
            }
        });
        return b;
    }

    private Button pluginTile(final Missions.Plugin p) {
        final boolean loaded = isLoaded(p.pkg);
        final Button b = tile(loaded ? p.name
                : plugin.getString(R.string.plugin_not_installed, p.name));
        final Drawable icon = loaded ? icon(p.pkg) : null;
        if (icon != null) {
            final int size = dp(48);
            icon.setBounds(0, 0, size, size);
            b.setCompoundDrawables(null, icon, null, null);
        }
        if (!loaded)
            b.setAlpha(0.45f);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isLoaded(p.pkg))
                    open(p);
                else
                    missing(p);
            }
        });
        return b;
    }

    /** An ATAK tool: closes MAST and sends the broadcast ATAK's own menu item sends. */
    private Button toolTile(final AtakTools.Tool t) {
        final Button b = tile(t.title);
        if (t.icon != null) {
            final int size = dp(48);
            t.icon.setBounds(0, 0, size, size);
            b.setCompoundDrawables(null, t.icon, null, null);
        }
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeDropDown();
                try {
                    AtakBroadcast.getInstance().sendBroadcast(t.intent());
                } catch (RuntimeException e) {
                    Log.w(TAG, "could not start ATAK tool " + t.file, e);
                }
            }
        });
        return b;
    }

    private Button tile(String label) {
        final Button b = new Button(plugin, null, 0, R.style.MastTile);
        b.setText(label);
        return b;
    }

    private void addTile(LinearLayout tiles, Button b) {
        // The last child is a row of tiles, or a heading that starts a new section.
        final View last = tiles.getChildCount() == 0 ? null
                : tiles.getChildAt(tiles.getChildCount() - 1);
        LinearLayout row = last instanceof LinearLayout ? (LinearLayout) last : null;
        if (row == null || row.getChildCount() == 3) {
            row = new LinearLayout(plugin);
            row.setOrientation(LinearLayout.HORIZONTAL);
            // A two-line label would otherwise drop its tile below the others.
            row.setBaselineAligned(false);
            final LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rp.topMargin = last instanceof LinearLayout ? dp(6) : 0;
            tiles.addView(row, rp);
        }
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(120), 1f);
        lp.setMarginEnd(dp(6));
        row.addView(b, lp);
    }

    /** Pad the last row so its tiles keep the width of a full row. */
    private void padLastRow(LinearLayout tiles) {
        final View last = tiles.getChildCount() == 0 ? null
                : tiles.getChildAt(tiles.getChildCount() - 1);
        if (!(last instanceof LinearLayout))
            return;
        final LinearLayout row = (LinearLayout) last;
        while (row.getChildCount() < 3) {
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 1, 1f);
            lp.setMarginEnd(dp(6));
            row.addView(new View(plugin), lp);
        }
    }

    private static int countTiles(LinearLayout tiles) {
        int n = 0;
        for (int i = 0; i < tiles.getChildCount(); i++)
            if (tiles.getChildAt(i) instanceof LinearLayout)
                n += ((LinearLayout) tiles.getChildAt(i)).getChildCount();
        return n;
    }

    // ---- editing a page, like ATAK's toolbar -----------------------------------------

    /**
     * The tiles on one mission page, as tool item files or plugin packages. MAST ships
     * a base set per mission (missions.json); once the operator changes a page, the
     * page is kept in a preference and the base set no longer applies to it, until
     * Reset Page.
     */
    private List<String> page(Missions.Mission m, String view) {
        final String saved = prefs.getString(pageKey(m, view), null);
        final List<String> ids = new ArrayList<>();
        if (saved != null) {
            try {
                final JSONArray a = new JSONArray(saved);
                for (int i = 0; i < a.length(); i++)
                    ids.add(a.getString(i));
                return ids;
            } catch (JSONException e) {
                Log.w(TAG, "page " + pageKey(m, view) + " unreadable, base set used", e);
                ids.clear();
            }
        }
        if (VIEW_TOOLS.equals(view))
            ids.addAll(m.tools);
        else
            for (Missions.Plugin p : m.plugins)
                ids.add(p.pkg);
        return ids;
    }

    private void savePage(Missions.Mission m, String view, List<String> ids) {
        prefs.edit().putString(pageKey(m, view), new JSONArray(ids).toString()).apply();
        showCurrent();
    }

    private static String pageKey(Missions.Mission m, String view) {
        return "mast_page_" + m.id + "_" + view;
    }

    /** An empty place on the page: a tap picks what goes there. */
    private Button blankTile(final Missions.Mission m, final String view) {
        final boolean tools = VIEW_TOOLS.equals(view);
        final Button b = tile(plugin.getString(tools ? R.string.choose_tool
                : R.string.choose_plugin));
        b.setAlpha(0.6f);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                choose(m, view, -1);
            }
        });
        b.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                confirmReset(m, view);
                return true;
            }
        });
        return b;
    }

    /** Long press on a tile: replace, move, remove, or put the base set back. */
    private void editTile(final Missions.Mission m, final String view, final int at) {
        final List<String> ids = page(m, view);
        if (at < 0 || at >= ids.size())
            return;
        final String[] items = {
                plugin.getString(R.string.edit_replace),
                plugin.getString(R.string.edit_left),
                plugin.getString(R.string.edit_right),
                plugin.getString(R.string.edit_remove),
                plugin.getString(R.string.edit_reset)
        };
        new AlertDialog.Builder(host)
                .setTitle(VIEW_TOOLS.equals(view) ? toolTitle(ids.get(at))
                        : pluginName(ids.get(at)))
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        switch (which) {
                            case 0:
                                choose(m, view, at);
                                return;
                            case 1:
                                if (at > 0) {
                                    ids.add(at - 1, ids.remove(at));
                                    savePage(m, view, ids);
                                }
                                return;
                            case 2:
                                if (at < ids.size() - 1) {
                                    ids.add(at + 1, ids.remove(at));
                                    savePage(m, view, ids);
                                }
                                return;
                            case 3:
                                ids.remove(at);
                                savePage(m, view, ids);
                                return;
                            default:
                                confirmReset(m, view);
                        }
                    }
                })
                .setNegativeButton(plugin.getString(R.string.cancel), null)
                .show();
    }

    private void confirmReset(final Missions.Mission m, final String view) {
        new AlertDialog.Builder(host)
                .setTitle(plugin.getString(R.string.edit_reset))
                .setMessage(plugin.getString(R.string.reset_message, m.name))
                .setPositiveButton(plugin.getString(R.string.edit_reset),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                prefs.edit().remove(pageKey(m, view)).apply();
                                showCurrent();
                            }
                        })
                .setNegativeButton(plugin.getString(R.string.cancel), null)
                .show();
    }

    /**
     * The chooser: a grid of tiles in a dialog, every ATAK tool or every loaded plugin
     * with a toolbar button, less what the page already has. {@code at} is the tile to
     * replace, or -1 to add at the end.
     */
    private void choose(final Missions.Mission m, final String view, final int at) {
        final boolean tools = VIEW_TOOLS.equals(view);
        final List<String> ids = page(m, view);
        final LinearLayout grid = new LinearLayout(plugin);
        grid.setOrientation(LinearLayout.VERTICAL);
        final ScrollView sv = new ScrollView(plugin);
        sv.setPadding(dp(8), dp(8), dp(2), dp(8));
        sv.addView(grid);
        final AlertDialog[] dlg = new AlertDialog[1];
        final List<String> offered = tools ? allTools() : allPlugins();
        for (final String id : offered) {
            if (ids.contains(id))
                continue;
            final Button b;
            if (tools) {
                final AtakTools.Tool t = AtakTools.load(host, id);
                if (t == null)
                    continue;
                b = tile(t.title);
                setIcon(b, t.icon);
            } else {
                b = tile(pluginName(id));
                setIcon(b, icon(id));
            }
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (dlg[0] != null)
                        dlg[0].dismiss();
                    if (at >= 0 && at < ids.size())
                        ids.set(at, id);
                    else
                        ids.add(id);
                    savePage(m, view, ids);
                }
            });
            addTile(grid, b);
        }
        padLastRow(grid);
        final AlertDialog.Builder d = new AlertDialog.Builder(host)
                .setTitle(plugin.getString(tools ? R.string.choose_tool_title
                        : R.string.choose_plugin_title))
                .setNegativeButton(plugin.getString(R.string.cancel), null);
        if (grid.getChildCount() == 0)
            d.setMessage(plugin.getString(R.string.choose_none));
        else
            d.setView(sv);
        dlg[0] = d.show();
    }

    /** Every item in ATAK's tool menu, by title, from atak.apk's own item files. */
    private List<String> allTools() {
        final List<AtakTools.Tool> found = new ArrayList<>();
        try {
            final String[] files = host.getAssets().list("actionbar/items");
            if (files != null)
                for (String f : files) {
                    if (!f.endsWith(".xml") || "quit.xml".equals(f))
                        continue;
                    final AtakTools.Tool t = AtakTools.load(host, f);
                    if (t != null)
                        found.add(t);
                }
        } catch (Exception e) {
            Log.w(TAG, "could not list ATAK's tools", e);
        }
        Collections.sort(found, new Comparator<AtakTools.Tool>() {
            @Override
            public int compare(AtakTools.Tool a, AtakTools.Tool b) {
                return a.title.compareToIgnoreCase(b.title);
            }
        });
        final List<String> out = new ArrayList<>();
        for (AtakTools.Tool t : found)
            out.add(t.file);
        return out;
    }

    /**
     * Every loaded plugin that has a toolbar button, MAST itself left out. A tile opens
     * a plugin through its toolbar button, so one without a button cannot be offered.
     */
    private List<String> allPlugins() {
        final List<String> out = new ArrayList<>();
        try {
            final AtakPluginRegistry reg = AtakPluginRegistry.get();
            if (reg != null)
                for (String pkg : reg.getPluginsLoaded())
                    if (!pkg.equals(plugin.getPackageName()) && icon(pkg) != null)
                        out.add(pkg);
        } catch (Throwable t) {
            Log.w(TAG, "could not list loaded plugins", t);
        }
        Collections.sort(out, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return pluginName(a).compareToIgnoreCase(pluginName(b));
            }
        });
        return out;
    }

    /**
     * A plugin's name: the name missions.json gives it, else the app label ATAK holds
     * for it, else the package.
     */
    private String pluginName(String pkg) {
        final String known = Missions.name(pkg);
        if (known != null)
            return known;
        if (missions != null)
            for (Missions.Mission m : missions)
                for (Missions.Plugin p : m.plugins)
                    if (p.pkg.equals(pkg))
                        return p.name;
        // Someone else's plugin: its own label, read from its own resources.
        try {
            final AtakPluginRegistry reg = AtakPluginRegistry.get();
            if (reg != null)
                for (ApplicationInfo ai : reg.getAssociatedApplications()) {
                    if (!pkg.equals(ai.packageName))
                        continue;
                    if (ai.nonLocalizedLabel != null)
                        return ai.nonLocalizedLabel.toString();
                    if (ai.labelRes != 0)
                        return host.createPackageContext(pkg, 0).getResources()
                                .getString(ai.labelRes);
                }
        } catch (Throwable t) {
            Log.w(TAG, "no label for " + pkg, t);
        }
        // Last resort: com.example.android.foo.plugin -> Foo.
        final String[] parts = pkg.split("\\.");
        String word = parts.length > 1 && "plugin".equals(parts[parts.length - 1])
                ? parts[parts.length - 2] : parts[parts.length - 1];
        return word.isEmpty() ? pkg
                : Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }

    private String toolTitle(String file) {
        final AtakTools.Tool t = AtakTools.load(host, file);
        return t == null ? file : t.title;
    }

    private void setIcon(Button b, Drawable icon) {
        if (icon == null)
            return;
        final int size = dp(48);
        icon.setBounds(0, 0, size, size);
        b.setCompoundDrawables(null, icon, null, null);
    }

    // ---- opening another plugin -----------------------------------------------------

    /** Closes MAST, then taps the plugin's toolbar button through ATAK's own receiver. */
    private void open(Missions.Plugin p) {
        closeDropDown();
        try {
            AtakBroadcast.getInstance().sendBroadcast(new Intent(p.pkg + ".ACTION"));
        } catch (RuntimeException e) {
            Log.w(TAG, "could not open " + p.pkg, e);
        }
    }

    private void missing(final Missions.Plugin p) {
        final AlertDialog.Builder d = new AlertDialog.Builder(host)
                .setTitle(p.name)
                .setMessage(plugin.getString(R.string.plugin_missing, p.name));
        if (isLoaded(MARKET_PKG)) {
            d.setPositiveButton(plugin.getString(R.string.open_market),
                    new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface dlg, int which) {
                            open(new Missions.Plugin("TAKwerx Market", MARKET_PKG));
                        }
                    });
            d.setNegativeButton(plugin.getString(R.string.cancel), null);
        } else {
            d.setPositiveButton(plugin.getString(R.string.ok), null);
        }
        d.show();
    }

    /**
     * Whether a plugin is loaded in ATAK. A plugin cannot ask Android which packages are
     * installed, so ATAK's own registry is asked (Sit Rep's check); if that fails, the
     * tile shows as loaded and a missing plugin ignores the tap.
     */
    private static boolean isLoaded(String pkg) {
        try {
            final AtakPluginRegistry reg = AtakPluginRegistry.get();
            return reg == null || reg.isPluginLoaded(pkg) || reg.isPluginLoaded(pkg + ".civ");
        } catch (Throwable t) {
            return true;
        }
    }

    /** The plugin's own toolbar icon, as ATAK keeps it for the Tools list. */
    private static Drawable icon(String pkg) {
        try {
            final Drawable d = PluginMapComponent.getPluginIconWithId(pkg);
            return d == null ? null : d.getConstantState() == null ? d
                    : d.getConstantState().newDrawable().mutate();
        } catch (Throwable t) {
            return null;
        }
    }

    private int dp(int v) {
        return (int) (v * plugin.getResources().getDisplayMetrics().density + 0.5f);
    }

    // ---- pane plumbing --------------------------------------------------------------

    /** Opens on the remembered mission's page; on the picker the first time. */
    public void show() {
        if (isVisible())
            return;
        setRetain(true);
        showCurrent();
        // ignoreBackButton false: Back closes the pane (memory: a true here makes
        // the pane unclosable); onBackButtonPressed steps the picker back first.
        showDropDown(root, HALF_WIDTH, FULL_HEIGHT, FULL_WIDTH, HALF_HEIGHT, false, this);
    }

    public void toggle() {
        if (isVisible())
            closeDropDown();
        else
            show();
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        // Opened by the toolbar item or by SHOW_ACTION (MAST.java).
    }

    @Override
    protected boolean onBackButtonPressed() {
        if (picking && current() != null) {
            showCurrent();
            return true;
        }
        return false;
    }

    @Override
    public void onDropDownSelectionRemoved() {
    }

    @Override
    public void onDropDownVisible(boolean v) {
    }

    @Override
    public void onDropDownSizeChanged(double width, double height) {
    }

    @Override
    public void onDropDownClose() {
    }

    @Override
    protected void disposeImpl() {
    }
}

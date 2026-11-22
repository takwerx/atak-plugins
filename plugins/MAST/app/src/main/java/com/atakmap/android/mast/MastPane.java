package com.atakmap.android.mast;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.preference.PreferenceManager;
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
    /** Tag on a mission tile: drawn taller than a plugin tile. */
    private static final String TALL = "tall";
    private static final String MARKET_PKG = "com.atakmap.android.takwerxmarket.plugin";

    private final Context plugin;
    private final Context host;
    private final SharedPreferences prefs;
    private final View root;
    private final Button missionBtn;
    private final ScrollView scroll;
    private final TextView section;
    private final LinearLayout tiles;
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
        missionBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPicker();
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
        missionBtn.setText(plugin.getString(R.string.mission_current, m.name));
        section.setText(plugin.getString(R.string.section_plugins, m.name));
        tiles.removeAllViews();
        for (final Missions.Plugin p : m.plugins)
            addTile(pluginTile(p));
        padLastRow();
        scroll.scrollTo(0, 0);
    }

    private void showPicker() {
        picking = true;
        final Missions.Mission m = current();
        missionBtn.setText(m == null ? plugin.getString(R.string.mission_none)
                : plugin.getString(R.string.mission_current, m.name));
        section.setText(R.string.section_pick);
        tiles.removeAllViews();
        if (missions != null)
            for (final Missions.Mission mission : missions)
                addTile(missionTile(mission));
        padLastRow();
        scroll.scrollTo(0, 0);
    }

    // ---- tiles ----------------------------------------------------------------------

    /**
     * A mission tile is bigger than a plugin tile: the picker is the first page, read
     * at a glance, three per row (operator, 2026-11-22: "first page of MAST is big
     * buttons", keep 3 per row, larger).
     */
    private Button missionTile(final Missions.Mission m) {
        final Button b = tile(m.name);
        b.setTextSize(20);
        b.setTypeface(b.getTypeface(), android.graphics.Typeface.BOLD);
        b.setMaxLines(3);
        b.setTag(TALL);
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

    private Button tile(String label) {
        final Button b = new Button(plugin, null, 0, R.style.MastTile);
        b.setText(label);
        return b;
    }

    private void addTile(Button b) {
        LinearLayout row = tiles.getChildCount() == 0 ? null
                : (LinearLayout) tiles.getChildAt(tiles.getChildCount() - 1);
        if (row == null || row.getChildCount() == 3) {
            row = new LinearLayout(plugin);
            row.setOrientation(LinearLayout.HORIZONTAL);
            // A two-line label would otherwise drop its tile below the others.
            row.setBaselineAligned(false);
            final LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rp.topMargin = tiles.getChildCount() == 0 ? 0 : dp(6);
            tiles.addView(row, rp);
        }
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                dp(TALL.equals(b.getTag()) ? 170 : 120), 1f);
        lp.setMarginEnd(dp(6));
        row.addView(b, lp);
    }

    /** Pad the last row so its tiles keep the width of a full row. */
    private void padLastRow() {
        if (tiles.getChildCount() == 0)
            return;
        final LinearLayout row = (LinearLayout) tiles.getChildAt(tiles.getChildCount() - 1);
        while (row.getChildCount() < 3) {
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 1, 1f);
            lp.setMarginEnd(dp(6));
            row.addView(new View(plugin), lp);
        }
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

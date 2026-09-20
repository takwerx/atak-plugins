package com.atakmap.android.mast;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.drawing.mapItems.DrawingShape;
import com.atakmap.android.dropdown.DropDown;
import com.atakmap.android.dropdown.DropDownReceiver;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.mast.feed.FeedPublisher;
import com.atakmap.android.mast.plugin.R;
import com.atakmap.android.toolbar.ToolManagerBroadcastReceiver;
import com.atakmap.coremap.log.Log;

import java.util.Map;

/**
 * The MAST pane: header value buttons (mission, mode, feed, floor) over the mission's
 * tool tiles, FOBS-style, three per row, built from the mission file.
 *
 * <p>A {@link DropDownReceiver} rather than the stable {@code Pane}, and that is a
 * documented exception to the prefer-{@code gov.tak.api} rule: Command mode's board
 * needs full width and the stable Pane cannot resize after it is shown
 * ({@code memory: atak-dropdown-full-width-resize}). Half is the operating state.
 *
 * <p>Every dialog and toast here is built on the MapView's context; the plugin context
 * cannot own a window and takes ATAK down with it.
 */
public class MastPane extends DropDownReceiver implements DropDown.OnStateListener, MapsList.Host {

    private static final String TAG = "MAST.Pane";
    private static final String SHAPE_TOOL = "com.atakmap.android.drawing.SHAPE_TOOL";
    /** Callback ATAK's shape tool fires when the shape is finished; carries the tile. */
    private static final String SHAPE_DONE = "com.atakmap.android.mast.SHAPE_DONE";
    private static final String EXTRA_TILE = "mast_tile";
    private static final String PREF_MODE = "mast_mode_command";
    static final String PREF_FLOOR = "mast_floor";
    static final String[] FLOORS = { "B2", "B1", "F1", "F2", "F3", "F4", "F5", "F6" };

    private final Context plugin;
    private final Context host;
    private final FeedPublisher feeds;
    private final SharedPreferences prefs;
    private final View root;
    private final Button missionBtn;
    private final Button modeBtn;
    private final Button feedBtn;
    private final Button floorBtn;
    private final TextView section;
    private final LinearLayout tiles;
    private final View grid;
    private final View mapsView;
    private final MapsList maps;
    private final MarkerDrop drop;
    private Mission mission;
    private double currentWidth = HALF_WIDTH;
    private double currentHeight = FULL_HEIGHT;

    private final BroadcastReceiver shapeDone = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            onShapeDone(intent.getStringExtra("uid"), intent.getIntExtra(EXTRA_TILE, -1));
        }
    };

    public MastPane(MapView mapView, Context pluginContext, FeedPublisher feeds) {
        super(mapView);
        this.plugin = pluginContext;
        this.host = mapView.getContext();
        this.feeds = feeds;
        this.prefs = PreferenceManager.getDefaultSharedPreferences(host);
        this.drop = new MarkerDrop(mapView, pluginContext, feeds, FLOORS,
                pluginContext.getString(R.string.floor_all), PREF_FLOOR);

        root = PluginLayoutInflater.inflate(plugin, R.layout.mast_pane, null);
        missionBtn = root.findViewById(R.id.mast_mission);
        modeBtn = root.findViewById(R.id.mast_mode);
        feedBtn = root.findViewById(R.id.mast_feed);
        floorBtn = root.findViewById(R.id.mast_floor);
        section = root.findViewById(R.id.mast_section);
        tiles = root.findViewById(R.id.mast_tiles);
        grid = root.findViewById(R.id.mast_grid);
        mapsView = root.findViewById(R.id.mast_maps);
        maps = new MapsList(mapView, pluginContext, root, this);

        missionBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toast(plugin.getString(R.string.next_build, "Mission picker"));
            }
        });
        modeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean command = !prefs.getBoolean(PREF_MODE, false);
                prefs.edit().putBoolean(PREF_MODE, command).apply();
                refreshHeader();
                if (command)
                    toast(plugin.getString(R.string.next_build, "Command mode board"));
            }
        });
        feedBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                feeds.pick(new FeedPublisher.OnFeed() {
                    @Override
                    public void go(FeedPublisher.Feed feed) {
                        refreshHeader();
                    }
                });
            }
        });
        floorBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickFloor();
            }
        });

        AtakBroadcast.getInstance().registerReceiver(shapeDone,
                new AtakBroadcast.DocumentedIntentFilter(SHAPE_DONE,
                        "MAST: ATAK's shape tool finished; carries the shape uid and the tile"));
    }

    // ---- building the grid ---------------------------------------------------------

    public void setMission(Mission m) {
        this.mission = m;
        tiles.removeAllViews();
        final float dp = plugin.getResources().getDisplayMetrics().density;
        LinearLayout row = null;
        for (final Mission.Tile t : m.tiles) {
            if (row == null || row.getChildCount() == 3) {
                row = new LinearLayout(plugin);
                row.setOrientation(LinearLayout.HORIZONTAL);
                // A two-line label would otherwise drop its tile below the others.
                row.setBaselineAligned(false);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                rp.topMargin = tiles.getChildCount() == 0 ? 0 : (int) (6 * dp);
                tiles.addView(row, rp);
            }
            Button b = new Button(plugin, null, 0, R.style.MastTile);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, (int) (120 * dp), 1f);
            lp.setMarginEnd((int) (6 * dp));
            b.setLayoutParams(lp);
            b.setText(t.label);
            int id = plugin.getResources().getIdentifier(t.icon, "drawable", plugin.getPackageName());
            if (id != 0) {
                Drawable d = plugin.getResources().getDrawable(id);
                b.setCompoundDrawablesWithIntrinsicBounds(null, d, null, null);
            } else {
                Log.w(TAG, "no drawable '" + t.icon + "' for tile " + t.label);
            }
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onTile(t);
                }
            });
            row.addView(b);
        }
        // Pad the last row so its tiles keep the width of a full row.
        while (row != null && row.getChildCount() < 3) {
            View spacer = new View(plugin);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 1, 1f);
            lp.setMarginEnd((int) (6 * dp));
            row.addView(spacer, lp);
        }
        floorBtn.setVisibility(m.floors ? View.VISIBLE : View.GONE);
        refreshHeader();
    }

    private void refreshHeader() {
        missionBtn.setText(plugin.getString(R.string.mission_label,
                mission == null ? "" : mission.name));
        modeBtn.setText(prefs.getBoolean(PREF_MODE, false) ? R.string.mode_command
                : R.string.mode_tactical);
        FeedPublisher.Feed f = feeds.current();
        if (f == null)
            feedBtn.setText(R.string.feed_none);
        else
            feedBtn.setText(plugin.getString(feeds.isCurrentConnected()
                    ? R.string.feed_current : R.string.feed_offline, f.name));
        floorBtn.setText(plugin.getString(R.string.floor_label,
                prefs.getString(PREF_FLOOR, plugin.getString(R.string.floor_all))));
        section.setText(prefs.getBoolean(PREF_MODE, false) ? R.string.section_command
                : R.string.section_tactical);
    }

    private void pickFloor() {
        final String[] rows = new String[FLOORS.length + 1];
        rows[0] = plugin.getString(R.string.floor_all);
        System.arraycopy(FLOORS, 0, rows, 1, FLOORS.length);
        String cur = prefs.getString(PREF_FLOOR, rows[0]);
        int checked = 0;
        for (int i = 0; i < rows.length; i++)
            if (rows[i].equals(cur))
                checked = i;
        new AlertDialog.Builder(host)
                .setTitle(plugin.getString(R.string.floor_pick))
                .setSingleChoiceItems(rows, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        prefs.edit().putString(PREF_FLOOR, rows[which]).apply();
                        refreshHeader();
                        d.dismiss();
                    }
                })
                .setNegativeButton(plugin.getString(R.string.cancel), null)
                .show();
    }

    // ---- tiles ---------------------------------------------------------------------

    private void onTile(Mission.Tile t) {
        switch (t.kind) {
            case ATAK:
                atakScreen(t);
                break;
            case SHAPE:
                startShape(t);
                break;
            case MAPS:
                grid.setVisibility(View.GONE);
                mapsView.setVisibility(View.VISIBLE);
                maps.open(t.view, t.search);
                break;
            case MARKER:
                closeDropDown();
                drop.begin(t, mission != null && mission.floors);
                break;
            case PLUGIN:
                openPlugin(t);
                break;
            default:
                break;
        }
    }

    /** An ATAK dropdown: set the preferences the tile names, ask for text if it wants some, send. */
    private void atakScreen(final Mission.Tile t) {
        if (t.ask == null) {
            sendAtak(t, null);
            return;
        }
        final EditText input = new EditText(host);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setSingleLine(true);
        new AlertDialog.Builder(host)
                .setTitle(t.askTitle)
                .setView(input)
                .setPositiveButton(plugin.getString(R.string.ok), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String s = input.getText().toString().trim();
                        if (s.length() > 0)
                            sendAtak(t, s);
                    }
                })
                .setNegativeButton(plugin.getString(R.string.cancel), null)
                .show();
        input.requestFocus();
    }

    private void sendAtak(Mission.Tile t, String asked) {
        if (!t.prefs.isEmpty()) {
            SharedPreferences.Editor e = prefs.edit();
            for (Map.Entry<String, Boolean> p : t.prefs.entrySet())
                e.putBoolean(p.getKey(), p.getValue());
            e.apply();
        }
        Intent i = new Intent(t.action);
        if (asked != null && t.ask != null)
            i.putExtra(t.ask, asked);
        if (!t.paths.isEmpty())
            i.putStringArrayListExtra("list_item_paths", new java.util.ArrayList<String>(t.paths));
        Log.d(TAG, "tile " + t.label + " -> " + t.action + (asked == null ? "" : " " + t.ask + "=" + asked));
        closeDropDown();
        AtakBroadcast.getInstance().sendBroadcast(i);
        if (!t.paths.isEmpty() && !t.prefs.isEmpty())
            getMapView().postDelayed(new Runnable() {
                @Override
                public void run() {
                    reapplyPrefs(t);
                }
            }, 700);
    }

    /**
     * Overlay Manager turns Show All back on whenever it navigates into a list
     * ({@code openMenu} ticks the box for any non-empty path, and the box writes
     * {@code fov_filter=false}), so a tile that wants both a folder and the in-view
     * filter sets the preference again once the navigation has landed and sends the
     * toolbar-unset broadcast, which is the one intent that makes Overlay Manager
     * re-read the preference into the box ({@code setViewToMode}, 4.6 source).
     */
    private void reapplyPrefs(Mission.Tile t) {
        SharedPreferences.Editor e = prefs.edit();
        for (Map.Entry<String, Boolean> p : t.prefs.entrySet())
            e.putBoolean(p.getKey(), p.getValue());
        e.apply();
        AtakBroadcast.getInstance().sendBroadcast(
                new Intent("com.atakmap.android.maps.toolbar.UNSET_TOOLBAR"));
        Log.d(TAG, "tile " + t.label + ": prefs re-applied after navigation");
    }

    /** ATAK's own polygon tool; the shape gets the tile's name and colors when it is done. */
    private void startShape(Mission.Tile t) {
        Bundle b = new Bundle();
        Intent cb = new Intent(SHAPE_DONE);
        cb.putExtra(EXTRA_TILE, t.index);
        b.putParcelable("callback", cb);
        closeDropDown();
        ToolManagerBroadcastReceiver.getInstance().startTool(SHAPE_TOOL, b);
    }

    private void onShapeDone(String uid, int tileIndex) {
        if (uid == null || mission == null || tileIndex < 0 || tileIndex >= mission.tiles.size())
            return;
        Mission.Tile t = mission.tiles.get(tileIndex);
        MapItem item = getMapView().getRootGroup().deepFindUID(uid);
        if (!(item instanceof DrawingShape)) {
            Log.w(TAG, "shape callback for " + uid + " found " + item);
            return;
        }
        DrawingShape shape = (DrawingShape) item;
        // The tool takes no title or color from its bundle, so they are set here.
        shape.setTitle(t.title + " " + countTitled(t.title));
        shape.setColor(t.color);
        shape.setFillColor(t.fill);
        shape.persist(getMapView().getMapEventDispatcher(), null, MastPane.class);
        Log.d(TAG, "shape " + uid + " is " + shape.getTitle());
        if (t.hasGeofence())
            toast(plugin.getString(R.string.next_build, "Geofence on " + t.title));
    }

    /** How many items already carry this title prefix, so the next one is numbered. */
    private int countTitled(String prefix) {
        final int[] n = { 0 };
        final String p = prefix + " ";
        getMapView().getRootGroup().deepForEachItem(new com.atakmap.android.maps.MapGroup.MapItemsCallback() {
            @Override
            public boolean onItemFunction(MapItem item) {
                String title = item.getTitle();
                if (title != null && title.startsWith(p))
                    n[0]++;
                return false;
            }
        });
        return n[0] + 1;
    }

    /** Another takwerx plugin's pane: greyed with a Market pointer when it is not installed. */
    private void openPlugin(Mission.Tile t) {
        try {
            host.getPackageManager().getPackageInfo(t.pkg, 0);
        } catch (PackageManager.NameNotFoundException e) {
            toast(plugin.getString(R.string.plugin_missing, t.pluginName));
            return;
        }
        toast(plugin.getString(R.string.next_build, t.pluginName));
    }

    // ---- pane plumbing (IapDropDown's shape) ---------------------------------------

    public void show() {
        if (isVisible())
            return;
        setRetain(true);
        refreshHeader();
        // ignoreBackButton: Back steps a full-width pane back to half before it closes.
        showDropDown(root, HALF_WIDTH, FULL_HEIGHT, FULL_WIDTH, HALF_HEIGHT, true, this);
    }

    public void toggle() {
        if (isVisible())
            closeDropDown();
        else
            show();
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        // Opened by the toolbar item, not by broadcast.
    }

    @Override
    protected void onStateRequested(int state) {
        if (state == DROPDOWN_STATE_FULLSCREEN)
            goWide();
        else if (state == DROPDOWN_STATE_NORMAL)
            goNarrow();
    }

    private void goWide() {
        if (!isPortrait())
            resize(FULL_WIDTH - HANDLE_THICKNESS_LANDSCAPE, FULL_HEIGHT);
        else
            resize(FULL_WIDTH, FULL_HEIGHT - HANDLE_THICKNESS_PORTRAIT);
    }

    private void goNarrow() {
        if (!isPortrait())
            resize(HALF_WIDTH, FULL_HEIGHT);
        else
            resize(FULL_WIDTH, HALF_HEIGHT);
    }

    private boolean isWide() {
        return !isPortrait() ? currentWidth > HALF_WIDTH + 0.01 : currentHeight > HALF_HEIGHT + 0.01;
    }

    /** The maps list's Back: the tile grid again. */
    @Override
    public void onBack() {
        maps.close();
        mapsView.setVisibility(View.GONE);
        grid.setVisibility(View.VISIBLE);
    }

    private boolean showingMaps() {
        return mapsView.getVisibility() == View.VISIBLE;
    }

    @Override
    protected boolean onBackButtonPressed() {
        if (showingMaps()) {
            onBack();
            return true;
        }
        if (isWide()) {
            goNarrow();
            return true;
        }
        return false;
    }

    @Override
    public void onDropDownSizeChanged(double width, double height) {
        currentWidth = width;
        currentHeight = height;
    }

    @Override
    public void onDropDownVisible(boolean visible) {
        if (visible)
            refreshHeader();
    }

    @Override
    public void onDropDownSelectionRemoved() {
    }

    @Override
    public void onDropDownClose() {
        if (showingMaps())
            onBack();
    }

    @Override
    protected void disposeImpl() {
        drop.dispose();
        try {
            AtakBroadcast.getInstance().unregisterReceiver(shapeDone);
        } catch (Exception e) {
            Log.w(TAG, "shape receiver was not registered", e);
        }
    }

    private void toast(String s) {
        Toast.makeText(host, s, Toast.LENGTH_SHORT).show();
    }
}

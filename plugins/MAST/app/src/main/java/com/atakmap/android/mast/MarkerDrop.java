package com.atakmap.android.mast;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.PointF;
import android.preference.PreferenceManager;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.atakmap.android.maps.MapEvent;
import com.atakmap.android.maps.MapEventDispatcher;
import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.android.mast.feed.FeedPublisher;
import com.atakmap.android.mast.plugin.R;
import com.atakmap.android.toolbar.widgets.TextContainer;
import com.atakmap.android.user.PlacePointTool;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.coremap.maps.coords.GeoPointMetaData;

/**
 * A Marker tile, end to end: one popup per drop (a keypad for a tile with {@code text},
 * − N + for one with {@code count}, − floor + when the mission has floors), then
 * <b>On the map</b> (tap to place, with ATAK's prompt bar) or <b>My location</b>;
 * the marker is placed through ATAK's own {@link PlacePointTool}, tagged with the
 * {@code <mast>} detail, and published to the feed picked in the pane header.
 *
 * <p>Map-tap placement is WASP's {@code MapPlacementHelper} pattern: push the
 * dispatcher's listeners, take {@code map_click} for one tap, pop them back. Back
 * cancels. The marker is movable afterwards the ATAK way, hold and drag; ATAK then
 * persists it and the pane's {@code ITEM_PERSIST} listener re-publishes it.
 */
public class MarkerDrop {

    private static final String TAG = "MAST.Drop";
    private static final String PREF_FLOOR_LAST = "mast_floor_last";
    private static final int MAX_COUNT = 999;

    private final MapView mapView;
    private final Context plugin;
    private final Context host;
    private final FeedPublisher feeds;
    private final SharedPreferences prefs;
    private final String[] floors;
    private final String floorAll;
    private final String prefFloor;

    private boolean placing;
    private Runnable onDone;
    private MapEventDispatcher.MapEventDispatchListener tapListener;
    private final View.OnKeyListener backListener = new View.OnKeyListener() {
        @Override
        public boolean onKey(View v, int keyCode, KeyEvent event) {
            if (keyCode != KeyEvent.KEYCODE_BACK)
                return false;
            endPlacing();
            done();
            return true;
        }
    };

    /** What the popup settled on, handed to the placement step. */
    private static final class Spec {
        Mission.Tile tile;
        String text;
        int count;
        String floor;
    }

    public MarkerDrop(MapView mapView, Context pluginContext, FeedPublisher feeds,
            String[] floors, String floorAll, String prefFloor) {
        this.mapView = mapView;
        this.plugin = pluginContext;
        this.host = mapView.getContext();
        this.feeds = feeds;
        this.prefs = PreferenceManager.getDefaultSharedPreferences(host);
        this.floors = floors;
        this.floorAll = floorAll;
        this.prefFloor = prefFloor;
    }

    public void dispose() {
        endPlacing();
    }

    /** Runs when a drop is placed or given up, so the pane can come back to the tools. */
    public void setOnDone(Runnable r) {
        onDone = r;
    }

    private void done() {
        if (onDone != null)
            onDone.run();
    }

    // ---- the popup -----------------------------------------------------------------

    public void begin(final Mission.Tile tile, final boolean missionHasFloors) {
        if (feeds.current() == null) {
            toast(plugin.getString(R.string.pick_feed_first));
            done();
            return;
        }
        final Spec spec = new Spec();
        spec.tile = tile;
        spec.count = 1;
        final float dp = plugin.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(host);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (12 * dp);
        box.setPadding(pad, pad, pad, 0);

        final EditText input;
        if (tile.text != null) {
            TextView l = label(tile.text);
            box.addView(l);
            input = new EditText(host);
            input.setInputType(InputType.TYPE_CLASS_NUMBER);
            input.setSingleLine(true);
            box.addView(input);
        } else {
            input = null;
        }

        final TextView countView;
        if (tile.count) {
            box.addView(label(plugin.getString(R.string.count_label)));
            countView = stepper(box, dp, String.valueOf(spec.count), new Step() {
                @Override
                public String step(int dir) {
                    spec.count = Math.max(1, Math.min(MAX_COUNT, spec.count + dir));
                    return String.valueOf(spec.count);
                }
            });
        } else {
            countView = null;
        }

        if (missionHasFloors) {
            spec.floor = startingFloor();
            box.addView(label(plugin.getString(R.string.floor_pick)));
            stepper(box, dp, spec.floor, new Step() {
                @Override
                public String step(int dir) {
                    int i = indexOf(spec.floor) + dir;
                    if (i < 0 || i >= floors.length)
                        return spec.floor;
                    spec.floor = floors[i];
                    return spec.floor;
                }
            });
        }

        // Two placement buttons, MAST's own dark tiles, on the dialog's own row.
        LinearLayout actions = new LinearLayout(host);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setBaselineAligned(false);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        alp.topMargin = (int) (14 * dp);
        box.addView(actions, alp);
        final Button onMap = button(plugin.getString(R.string.place_on_map), dp);
        final Button here = button(plugin.getString(R.string.place_my_location), dp);
        actions.addView(onMap);
        actions.addView(here);
        final GeoPoint self = selfPosition();
        if (self == null) {
            here.setEnabled(false);
            here.setText(plugin.getString(R.string.place_no_gps));
        }

        final AlertDialog dlg = new AlertDialog.Builder(host)
                .setTitle(tile.label)
                .setView(box)
                .setNegativeButton(plugin.getString(R.string.cancel), null)
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface d) {
                        done();
                    }
                })
                .create();
        dlg.setButton(DialogInterface.BUTTON_NEGATIVE, plugin.getString(R.string.cancel),
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        done();
                    }
                });
        onMap.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!readText(spec, input))
                    return;
                dlg.dismiss();
                placeByTap(spec);
            }
        });
        here.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!readText(spec, input))
                    return;
                dlg.dismiss();
                place(spec, GeoPointMetaData.wrap(self));
            }
        });
        dlg.show();
        if (input != null)
            input.requestFocus();
        if (countView != null)
            countView.setText(String.valueOf(spec.count));
    }

    private boolean readText(Spec spec, EditText input) {
        if (input == null)
            return true;
        String s = input.getText().toString().trim();
        if (s.length() == 0) {
            toast(plugin.getString(R.string.text_needed, spec.tile.text));
            return false;
        }
        spec.text = s;
        return true;
    }

    private interface Step {
        String step(int dir);
    }

    private TextView label(String text) {
        TextView t = new TextView(host);
        t.setText(text);
        t.setTextSize(10);
        t.setAllCaps(true);
        t.setAlpha(0.6f);
        return t;
    }

    private Button button(String text, float dp) {
        Button b = new Button(plugin, null, 0, R.style.TakwerxButton);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginEnd((int) (6 * dp));
        b.setLayoutParams(lp);
        b.setText(text);
        return b;
    }

    /** − value + on one row; the value view is returned so the caller can refresh it. */
    private TextView stepper(LinearLayout box, float dp, String start, final Step step) {
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBaselineAligned(false);
        final Button minus = button("−", dp);
        final TextView value = new TextView(host);
        value.setText(start);
        value.setTextSize(22);
        value.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1.2f);
        value.setLayoutParams(vlp);
        final Button plus = button("+", dp);
        minus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                value.setText(step.step(-1));
            }
        });
        plus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                value.setText(step.step(+1));
            }
        });
        row.addView(minus);
        row.addView(value);
        row.addView(plus);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = (int) (4 * dp);
        rlp.bottomMargin = (int) (8 * dp);
        box.addView(row, rlp);
        return value;
    }

    private int indexOf(String floor) {
        for (int i = 0; i < floors.length; i++)
            if (floors[i].equals(floor))
                return i;
        return -1;
    }

    /** The header's floor when one is chosen, else the last floor stamped, else F1. */
    private String startingFloor() {
        String header = prefs.getString(prefFloor, floorAll);
        if (!floorAll.equals(header) && indexOf(header) >= 0)
            return header;
        String last = prefs.getString(PREF_FLOOR_LAST, null);
        if (last != null && indexOf(last) >= 0)
            return last;
        int f1 = indexOf("F1");
        return f1 >= 0 ? floors[f1] : floors[0];
    }

    private GeoPoint selfPosition() {
        Marker self = mapView.getSelfMarker();
        GeoPoint p = self == null ? null : self.getPoint();
        if (p == null || !p.isValid() || (p.getLatitude() == 0 && p.getLongitude() == 0))
            return null;
        return p;
    }

    // ---- placing -------------------------------------------------------------------

    private void placeByTap(final Spec spec) {
        if (placing)
            endPlacing();
        MapEventDispatcher d = mapView.getMapEventDispatcher();
        d.pushListeners();
        d.clearListeners(MapEvent.MAP_CLICK);
        d.clearListeners(MapEvent.MAP_LONG_PRESS);
        d.clearListeners(MapEvent.ITEM_CLICK);
        tapListener = new MapEventDispatcher.MapEventDispatchListener() {
            @Override
            public void onMapEvent(MapEvent event) {
                PointF pf = event.getPointF();
                if (pf == null)
                    return;
                GeoPointMetaData gp = mapView.inverseWithElevation(pf.x, pf.y);
                endPlacing();
                if (gp == null || gp.get() == null || !gp.get().isValid())
                    return;
                place(spec, gp);
            }
        };
        d.addMapEventListener(MapEvent.MAP_CLICK, tapListener);
        d.addMapEventListener(MapEvent.ITEM_CLICK, tapListener);
        mapView.addOnKeyListener(backListener);
        TextContainer.getInstance().displayPrompt(
                plugin.getString(R.string.tap_to_place, spec.tile.label));
        placing = true;
    }

    private void endPlacing() {
        if (!placing)
            return;
        placing = false;
        TextContainer.getInstance().closePrompt();
        MapEventDispatcher d = mapView.getMapEventDispatcher();
        if (tapListener != null) {
            d.removeMapEventListener(MapEvent.MAP_CLICK, tapListener);
            d.removeMapEventListener(MapEvent.ITEM_CLICK, tapListener);
            tapListener = null;
        }
        d.popListeners();
        mapView.removeOnKeyListener(backListener);
    }

    private void place(Spec spec, GeoPointMetaData at) {
        final Mission.Tile t = spec.tile;
        int n = countKind(t.id) + 1;
        StringBuilder title = new StringBuilder(t.label);
        if (spec.text != null)
            title.append(' ').append(spec.text);
        else
            title.append(' ').append(n);
        if (t.count && spec.count > 1)
            title.append(" x").append(spec.count);
        if (spec.floor != null)
            title.append(' ').append(spec.floor);

        Marker m = new PlacePointTool.MarkerCreator(at)
                .setType(t.type)
                .setCallsign(title.toString())
                .setHow("h-g-i-g-o")
                .setArchive(true)
                .showCotDetails(false)
                .placePoint();
        if (m == null) {
            toast(plugin.getString(R.string.place_failed, t.label));
            done();
            return;
        }
        m.setMovable(true);
        m.setMetaString(MastDetailHandler.META_KIND, t.id);
        if (t.count)
            m.setMetaString(MastDetailHandler.META_COUNT, String.valueOf(spec.count));
        if (spec.floor != null) {
            m.setMetaString(MastDetailHandler.META_FLOOR, spec.floor);
            SharedPreferences.Editor e = prefs.edit().putString(PREF_FLOOR_LAST, spec.floor);
            // When the header is on a floor it follows the drop, so the map keeps
            // showing the floor being worked.
            if (!floorAll.equals(prefs.getString(prefFloor, floorAll)))
                e.putString(prefFloor, spec.floor);
            e.apply();
        }
        if (spec.text != null)
            m.setMetaString(MastDetailHandler.META_TEXT, spec.text);
        FeedPublisher.Feed feed = feeds.current();
        feeds.attach(m, feed);
        // Saved again now that the detail is on it; the first save happened inside
        // placePoint before the metadata existed. This save also publishes.
        m.persist(mapView.getMapEventDispatcher(), null, MarkerDrop.class);
        feeds.onPersisted(m);
        Log.d(TAG, "placed " + m.getUID() + " '" + m.getTitle() + "' type=" + t.type
                + " feed=" + (feed == null ? "none" : feed.name));
        done();
    }

    /** How many MAST markers of this kind are on the map already, so the next is numbered. */
    private int countKind(final String kind) {
        final int[] n = { 0 };
        mapView.getRootGroup().deepForEachItem(new MapGroup.MapItemsCallback() {
            @Override
            public boolean onItemFunction(MapItem item) {
                if (kind.equals(item.getMetaString(MastDetailHandler.META_KIND, null)))
                    n[0]++;
                return false;
            }
        });
        return n[0];
    }

    private void toast(String s) {
        Toast.makeText(host, s, Toast.LENGTH_SHORT).show();
    }
}

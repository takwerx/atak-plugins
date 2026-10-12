package com.atakmap.android.firecast;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.dropdown.DropDown.OnStateListener;
import com.atakmap.android.dropdown.DropDownReceiver;
import com.atakmap.android.firecast.plugin.R;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Where is it burning?" - the panel that answers the question the rest of the plugin
 * cannot.
 *
 * <p>Everything else here works from the map extent, which is right for reading a fire
 * and useless for finding one. This asks the service to summarize instead: the whole
 * country, or one state, ranked by how hard it is burning, in two small queries that
 * carry no detections at all. Tapping a row flies the map there and the ordinary fetch
 * draws the footprints on arrival.
 */
public class ActivityReceiver extends DropDownReceiver implements OnStateListener {

    private static final String TAG = "FireCast";
    public static final String ACTION = "com.atakmap.android.firecast.ACTIVITY";

    private final Context pluginContext;
    private final DetectionFeed feed;
    private final View view;
    private final ListView list;
    private final Adapter adapter = new Adapter();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private List<Activity.Fire> fires = new ArrayList<>();
    private List<String> states = new ArrayList<>();
    /** Null is everywhere the feed covers. */
    private String state;
    private boolean loading;
    /** The box the last load answered, so a pan inside it does not ask again. */
    private Esri.Box loadedBox;
    /** The map followed while the panel is open on What is in view. GL thread: post only. */
    private final com.atakmap.map.AtakMapView.OnMapMovedListener moved =
            new com.atakmap.map.AtakMapView.OnMapMovedListener() {
                @Override
                public void onMapMoved(com.atakmap.map.AtakMapView v, boolean animate) {
                    main.removeCallbacks(settled);
                    main.postDelayed(settled, 500);
                }
            };
    private final Runnable settled = new Runnable() {
        @Override
        public void run() {
            if (!AREA_VIEW.equals(area) || loading || !isVisible())
                return;
            final Esri.Box now = feed.viewBox();
            final Esri.Box was = loadedBox;
            if (now == null)
                return;
            // Inside the box already answered, and not much smaller than it: nothing new.
            if (was != null && was.contains(now.south, now.west) && was.contains(now.north, now.east)
                    && now.spanLon() > was.spanLon() * 0.5)
                return;
            load();
        }
    };
    /** The area the list answers: {@link #AREA_ANY}, {@link #AREA_VIEW} or {@link #AREA_RADIUS}. */
    private String area;
    private double areaM;
    private static final String AREA_ANY = "any", AREA_VIEW = "view", AREA_RADIUS = "radius";
    /** Wider than the pane's: this list is for finding a fire, not reading the one in front of you. */
    private static final int[] AREA_CHOICES = { 25, 50, 100, 200 };
    private final android.content.SharedPreferences prefs;
    /** The point the last load measured from, for the distance on each row; null for none. */
    private double[] from;
    private boolean noFix;

    public ActivityReceiver(MapView mapView, Context pluginContext, DetectionFeed feed) {
        super(mapView);
        this.pluginContext = pluginContext;
        this.feed = feed;
        this.view = PluginLayoutInflater.inflate(pluginContext, R.layout.activity_panel, null);
        this.prefs = mapView.getContext().getSharedPreferences("firecast", Context.MODE_PRIVATE);
        area = prefs.getString("activity.area", AREA_ANY);
        areaM = prefs.getFloat("activity.areaM", (float) Units.bigToMeters(50));
        this.list = view.findViewById(R.id.activity_list);
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View v, int position, long id) {
                if (position >= 0 && position < fires.size())
                    goTo(fires.get(position));
            }
        });
        view.findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeDropDown();
                feed.reopenPane();
            }
        });
        view.findViewById(R.id.btn_reload).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                load();
            }
        });
        view.findViewById(R.id.btn_where).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickState();
            }
        });
        view.findViewById(R.id.btn_area).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickArea();
            }
        });
        labelButtons();
    }

    private void labelButtons() {
        ((Button) view.findViewById(R.id.btn_where)).setText(state == null ? "Where: Any state" : "Where: " + state);
        ((Button) view.findViewById(R.id.btn_area)).setText("Area: " + areaName());
    }

    private String areaName() {
        if (AREA_VIEW.equals(area))
            return "What is in view";
        if (AREA_RADIUS.equals(area))
            return "Within " + Units.formatBig(areaM) + " of My Location";
        return "Lower 48";
    }

    /** The same Area control as the pane, with Lower 48 in place of Everything. */
    private void pickArea() {
        final String[] labels = new String[2 + AREA_CHOICES.length];
        labels[0] = "Lower 48";
        labels[1] = "What is in view";
        int current = AREA_VIEW.equals(area) ? 1 : 0;
        for (int i = 0; i < AREA_CHOICES.length; i++) {
            labels[2 + i] = "Within " + AREA_CHOICES[i] + " " + Units.bigLabel() + " of My Location";
            if (AREA_RADIUS.equals(area) && Math.abs(Units.bigToMeters(AREA_CHOICES[i]) - areaM) < 1)
                current = 2 + i;
        }
        new AlertDialog.Builder(getMapView().getContext())
                .setTitle("Area")
                .setSingleChoiceItems(labels, current, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        if (which == 0)
                            area = AREA_ANY;
                        else if (which == 1)
                            area = AREA_VIEW;
                        else {
                            area = AREA_RADIUS;
                            areaM = Units.bigToMeters(AREA_CHOICES[which - 2]);
                        }
                        prefs.edit().putString("activity.area", area).putFloat("activity.areaM", (float) areaM).apply();
                        labelButtons();
                        load();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        showDropDown(view, HALF_WIDTH, FULL_HEIGHT, FULL_WIDTH, HALF_HEIGHT, this);
        load();
    }

    /** Takes the map to a fire and closes, so the detections are what is on screen. */
    private void goTo(Activity.Fire f) {
        try {
            // Wide enough to hold a fire and its detections, not so wide that the extent
            // fetch comes back with a county's worth of industry around it.
            final double half = 0.08d;
            final com.atakmap.coremap.maps.coords.GeoPoint[] corners = {
                    new com.atakmap.coremap.maps.coords.GeoPoint(f.lat - half, f.lon - half),
                    new com.atakmap.coremap.maps.coords.GeoPoint(f.lat + half, f.lon + half) };
            com.atakmap.android.util.ATAKUtilities.scaleToFit(getMapView(), corners, 0d,
                    getMapView().getWidth(), getMapView().getHeight());
        } catch (Exception e) {
            Log.w(TAG, "go to fire failed", e);
        }
        closeDropDown();
        // The map has moved somewhere new, so ask for that area now rather than waiting
        // out the tick that would have noticed; and the pane comes back with the list
        // for the place just flown to.
        feed.refresh();
        feed.reopenPane();
    }

    private void pickState() {
        final List<String> options = new ArrayList<>();
        options.add("Any state");
        options.addAll(states);
        int current = 0;
        for (int i = 0; i < states.size(); i++)
            if (states.get(i).equals(state))
                current = i + 1;
        new AlertDialog.Builder(getMapView().getContext())
                .setTitle("Where to look")
                .setSingleChoiceItems(options.toArray(new String[0]), current,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                dialog.dismiss();
                                state = which == 0 ? null : options.get(which);
                                labelButtons();
                                load();
                            }
                        })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Fetches the summary on the worker and redraws. */
    private void load() {
        if (loading)
            return;
        loading = true;
        status("Looking…");
        final long since = feed.filters.since(System.currentTimeMillis());
        final String want = state;
        // The area, read on main where the map can be asked. A radius is a box to the
        // service and a circle here; the view is the view.
        Esri.Box box = null;
        from = null;
        noFix = false;
        if (AREA_VIEW.equals(area)) {
            box = feed.viewBox();
            loadedBox = box;
            from = feed.areaPoint();
            noFix = feed.noFix;
        } else if (AREA_RADIUS.equals(area)) {
            from = feed.areaPoint();
            noFix = feed.noFix;
            if (from != null)
                box = DetectionFeed.boxAround(from, areaM);
        }
        final Esri.Box wantBox = box;
        final double[] measureFrom = from;
        final double within = AREA_RADIUS.equals(area) ? areaM : -1;
        worker.execute(new Runnable() {
            @Override
            public void run() {
                List<Activity.Fire> found = null;
                List<String> foundStates = null;
                String error = null;
                try {
                    found = Activity.fires(since, want, wantBox);
                    if (within > 0 && measureFrom != null) {
                        final List<Activity.Fire> inside = new ArrayList<>();
                        for (Activity.Fire f : found)
                            if (DetectionFeed.metersBetween(measureFrom, f.lat, f.lon) <= within)
                                inside.add(f);
                        found = inside;
                    }
                    // The state list is only worth rebuilding while looking everywhere;
                    // inside one state the feed would only ever name that one.
                    if (want == null)
                        foundStates = Activity.states(since);
                } catch (Exception e) {
                    Log.w(TAG, "activity summary failed", e);
                    error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                }
                final List<Activity.Fire> result = found;
                final List<String> resultStates = foundStates;
                final String err = error;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        loading = false;
                        if (err != null) {
                            status("Could not read the summary: " + err);
                            return;
                        }
                        fires = result;
                        if (resultStates != null)
                            states = resultStates;
                        adapter.notifyDataSetChanged();
                        describe();
                    }
                });
            }
        });
    }

    private void describe() {
        String where = state == null ? "the lower 48" : state;
        if (AREA_VIEW.equals(area))
            where = "the area on screen" + (state == null ? "" : " in " + state);
        else if (AREA_RADIUS.equals(area))
            where = "within " + Units.formatBig(areaM) + (noFix ? " of the map center (no GPS fix)" : " of you")
                    + (state == null ? "" : " in " + state);
        if (fires.isEmpty()) {
            status("Nothing burning in " + where + " in the last " + feed.filters.windowLabel()
                    + " that GOES has seen.");
            return;
        }
        // The limit goes on the panel, not in a manual nobody has open: this is GOES, and
        // GOES here is CONUS, so an empty list is not the same as empty ground.
        int established = 0;
        for (Activity.Fire f : fires)
            if (f.established())
                established++;
        final int onceOnly = fires.size() - established;
        final StringBuilder sb = new StringBuilder();
        sb.append(established).append(established == 1 ? " fire in " : " fires in ").append(where)
                .append(", last ").append(feed.filters.windowLabel()).append('.');
        if (onceOnly > 0)
            sb.append(' ').append(onceOnly).append(" seen once, often a burn pile.");
        sb.append(" MW is heat seen from space. GOES, lower 48 only.");
        status(sb.toString());
    }

    private void status(String s) {
        ((TextView) view.findViewById(R.id.activity_status)).setText(s);
    }

    private class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return fires.size();
        }

        @Override
        public Object getItem(int position) {
            return fires.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null)
                v = PluginLayoutInflater.inflate(pluginContext, R.layout.activity_row, null);
            final Activity.Fire f = fires.get(position);
            ((TextView) v.findViewById(R.id.fire_name)).setText(f.title());
            final StringBuilder sb = new StringBuilder();
            if (f.name != null && f.state != null)
                sb.append(f.state).append(" · ");
            if (f.durationHours >= 0.5)
                sb.append(String.format(Locale.US, "burning %.1f h", f.durationHours));
            else if (f.hits > 1)
                sb.append(f.hits).append(" detections");
            else
                // Said plainly, because it is the row's biggest caveat: one scan is not a
                // fire yet, and in farm country most of them are not fires at all.
                sb.append("one scan only");
            if (f.durationHours >= 0.5 && f.hits > 1)
                sb.append(" · ").append(f.hits).append(" detections");
            if (f.last > 0)
                sb.append(" · last ").append(ago(f.last));
            if (from != null)
                sb.append(" · ").append(Units.rangeBearing(from, f.lat, f.lon))
                        .append(noFix ? " of map center" : " of you");
            ((TextView) v.findViewById(R.id.fire_detail)).setText(sb.toString());
            ((TextView) v.findViewById(R.id.fire_power)).setText(f.powerText());
            return v;
        }
    }

    private static String ago(long when) {
        final long mins = Math.max(0L, System.currentTimeMillis() - when) / 60000L;
        if (mins < 60)
            return mins + " min ago";
        return (mins / 60) + " h ago";
    }

    @Override
    public void onDropDownSelectionRemoved() {
    }

    @Override
    public void onDropDownVisible(boolean v) {
        try {
            if (v)
                getMapView().addOnMapMovedListener(moved);
            else {
                getMapView().removeOnMapMovedListener(moved);
                main.removeCallbacks(settled);
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onDropDownSizeChanged(double width, double height) {
    }

    @Override
    public void onDropDownClose() {
        try {
            getMapView().removeOnMapMovedListener(moved);
        } catch (Exception ignored) {
        }
        main.removeCallbacks(settled);
    }

    @Override
    protected void disposeImpl() {
        worker.shutdownNow();
    }
}

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
    /**
     * NASA's fire event perimeters in the same area (operator, 2026-10-30: "can we do
     * both"): each GOES fire that sits in one says so on its row, and every one has its
     * own row in a section under the fires.
     */
    private List<com.atakmap.android.firecast.model.Feds.Fire> perimeters = new ArrayList<>();
    private final java.util.Map<Activity.Fire, com.atakmap.android.firecast.model.Feds.Fire> matched = new java.util.HashMap<>();
    /** County and state per perimeter, by its fire number; looked up after the list is up. */
    private final java.util.Map<String, String> places = new java.util.HashMap<>();
    private final ExecutorService placeWorker = Executors.newSingleThreadExecutor();
    private static final int NAMED_ROWS = 25;
    /** Which list is showing: the GOES fires, or NASA's perimeters. One at a time, a switch over them. */
    private boolean showPerimeters;
    /** Two tabs over the list, the one showing lit: GOES fires | NASA perimeters, each with its count. */
    private Button tabFires, tabPerimeters;
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
            // A move the list made itself (a row tapped) keeps the list it came from, so
            // the next fire is still there to tap; the operator's own pan or zoom reloads.
            if (System.currentTimeMillis() < keepListUntil)
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
    private long keepListUntil;
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
        DetectionFeed.coveredBy(view);
        this.prefs = mapView.getContext().getSharedPreferences("firecast", Context.MODE_PRIVATE);
        area = prefs.getString("activity.area", AREA_ANY);
        areaM = prefs.getFloat("activity.areaM", (float) Units.bigToMeters(50));
        this.list = view.findViewById(R.id.activity_list);
        final android.widget.LinearLayout tabs = new android.widget.LinearLayout(view.getContext());
        tabs.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        tabFires = tab(tabs, false);
        tabPerimeters = tab(tabs, true);
        list.addHeaderView(tabs, null, false);
        list.setAdapter(adapter);
        labelWhich();
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View v, int position, long id) {
                // The switch above the list is a header row: positions start after it.
                final int p = position - list.getHeaderViewsCount();
                if (showPerimeters) {
                    if (p >= 0 && p < perimeters.size())
                        goTo(perimeters.get(p));
                } else if (p >= 0 && p < fires.size())
                    goTo(fires.get(p));
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

    /** Takes the map to a fire; the list stays open. */
    private void goTo(Activity.Fire f) {
        keepListUntil = System.currentTimeMillis() + 4000;
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
        // The list stays open, so the next fire is one tap away (operator, 2026-10-30):
        // the map goes there and the feeds ask for that area now.
        keepListUntil = System.currentTimeMillis() + 4000;
        feed.refresh();
    }

    /** One tab: a TakwerxButton, half the row; tapping it shows its list. */
    private Button tab(android.widget.LinearLayout row, final boolean perimeterTab) {
        final View holder = PluginLayoutInflater.inflate(pluginContext, R.layout.model_run_row, null);
        final Button b = holder.findViewById(R.id.run_name);
        ((ViewGroup) b.getParent()).removeView(b);
        b.setGravity(android.view.Gravity.CENTER);
        final android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (perimeterTab)
            lp.leftMargin = (int) (6 * view.getContext().getResources().getDisplayMetrics().density);
        row.addView(b, lp);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (showPerimeters == perimeterTab)
                    return;
                showPerimeters = perimeterTab;
                labelWhich();
                adapter.notifyDataSetChanged();
                list.setSelection(0);
            }
        });
        return b;
    }

    /** Both tabs with their counts; the one showing in green, the other plain. */
    private void labelWhich() {
        if (tabFires == null)
            return;
        tabFires.setText("GOES fires (" + fires.size() + ")");
        tabPerimeters.setText("NASA perimeters (" + perimeters.size() + ")");
        tabFires.setTextColor(showPerimeters ? 0xFFFFFFFF : 0xFF4CD964);
        tabPerimeters.setTextColor(showPerimeters ? 0xFF4CD964 : 0xFFFFFFFF);
    }

    /** Takes the map to a NASA perimeter and draws it: the feed is turned on if it was off. */
    private void goTo(com.atakmap.android.firecast.model.Feds.Fire p) {
        keepListUntil = System.currentTimeMillis() + 4000;
        try {
            final double padLat = (p.north - p.south) * 0.3 + 0.01, padLon = (p.east - p.west) * 0.3 + 0.01;
            final com.atakmap.coremap.maps.coords.GeoPoint[] corners = {
                    new com.atakmap.coremap.maps.coords.GeoPoint(p.south - padLat, p.west - padLon),
                    new com.atakmap.coremap.maps.coords.GeoPoint(p.north + padLat, p.east + padLon) };
            com.atakmap.android.util.ATAKUtilities.scaleToFit(getMapView(), corners, 0d,
                    getMapView().getWidth(), getMapView().getHeight());
        } catch (Exception e) {
            Log.w(TAG, "go to perimeter failed", e);
        }
        if (!feed.filters.isOn("feds"))
            feed.filters.setOn("feds", true);
        keepListUntil = System.currentTimeMillis() + 4000;
        feed.refresh();
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
                List<com.atakmap.android.firecast.model.Feds.Fire> foundPerimeters = null;
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
                    // NASA's perimeters for the same area: the box asked for, or around the
                    // fires found when the area is a state or everywhere. A failure here
                    // leaves the fires as they are.
                    try {
                        Esri.Box pbox = wantBox;
                        if (pbox == null && !found.isEmpty()) {
                            double s0 = 90, w0 = 180, n0 = -90, e0 = -180;
                            for (Activity.Fire f : found) {
                                s0 = Math.min(s0, f.lat);
                                n0 = Math.max(n0, f.lat);
                                w0 = Math.min(w0, f.lon);
                                e0 = Math.max(e0, f.lon);
                            }
                            pbox = new Esri.Box(Math.max(-90, s0 - 0.3), Math.max(-180, w0 - 0.3),
                                    Math.min(90, n0 + 0.3), Math.min(180, e0 + 0.3));
                        }
                        foundPerimeters = pbox == null ? new ArrayList<com.atakmap.android.firecast.model.Feds.Fire>()
                                : com.atakmap.android.firecast.model.Feds.inBox(pbox.south, pbox.west, pbox.north, pbox.east);
                        if (within > 0 && measureFrom != null) {
                            final List<com.atakmap.android.firecast.model.Feds.Fire> inside = new ArrayList<>();
                            for (com.atakmap.android.firecast.model.Feds.Fire p : foundPerimeters)
                                if (DetectionFeed.metersBetween(measureFrom, p.lat(), p.lon()) <= within)
                                    inside.add(p);
                            foundPerimeters = inside;
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "NASA perimeters for the list: " + e);
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
                final List<com.atakmap.android.firecast.model.Feds.Fire> resultPerimeters = foundPerimeters;
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
                        perimeters = resultPerimeters != null ? resultPerimeters
                                : new ArrayList<com.atakmap.android.firecast.model.Feds.Fire>();
                        // Nearest first: a big outline far away is not what "in view" is asking.
                        if (measureFrom != null)
                            java.util.Collections.sort(perimeters, new java.util.Comparator<com.atakmap.android.firecast.model.Feds.Fire>() {
                                @Override
                                public int compare(com.atakmap.android.firecast.model.Feds.Fire a,
                                        com.atakmap.android.firecast.model.Feds.Fire b) {
                                    return Double.compare(DetectionFeed.metersBetween(measureFrom, a.lat(), a.lon()),
                                            DetectionFeed.metersBetween(measureFrom, b.lat(), b.lon()));
                                }
                            });
                        matched.clear();
                        for (Activity.Fire f : fires)
                            for (com.atakmap.android.firecast.model.Feds.Fire p : perimeters)
                                if (f.lat >= p.south - 0.01 && f.lat <= p.north + 0.01
                                        && f.lon >= p.west - 0.01 && f.lon <= p.east + 0.01) {
                                    matched.put(f, p);
                                    break;
                                }
                        namePerimeters();
                        // An empty tab is not where to land when the other has something.
                        if (!showPerimeters && fires.isEmpty() && !perimeters.isEmpty())
                            showPerimeters = true;
                        else if (showPerimeters && perimeters.isEmpty() && !fires.isEmpty())
                            showPerimeters = false;
                        labelWhich();
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
                    + " that GOES has seen." + (perimeters.isEmpty() ? ""
                    : " " + perimeters.size() + " NASA fire event " + (perimeters.size() == 1 ? "perimeter" : "perimeters")
                            + " from the last 3 days, under NASA perimeters."));
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
        if (!perimeters.isEmpty())
            sb.append(' ').append(matched.size()).append(matched.size() == 1 ? " sits" : " sit")
                    .append(" in a NASA fire event perimeter; ").append(perimeters.size())
                    .append(perimeters.size() == 1 ? " perimeter" : " perimeters").append(" from the last 3 days, under NASA perimeters.");
        status(sb.toString());
    }

    private void status(String s) {
        ((TextView) view.findViewById(R.id.activity_status)).setText(s);
    }

    private class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return showPerimeters ? perimeters.size() : fires.size();
        }

        @Override
        public Object getItem(int position) {
            return showPerimeters ? perimeters.get(position) : fires.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = PluginLayoutInflater.inflate(pluginContext, R.layout.activity_row, null);
                // Two lines: the second carries the NASA perimeter a fire sits in.
                final TextView d = v.findViewById(R.id.fire_detail);
                d.setSingleLine(false);
                d.setMaxLines(2);
            }
            if (showPerimeters)
                return perimeterRow(v, perimeters.get(position));
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
            final com.atakmap.android.firecast.model.Feds.Fire p = matched.get(f);
            if (p != null)
                sb.append("\nNASA perimeter: ").append(acres(p)).append(", outline ").append(age(p.atMs)).append(" old");
            ((TextView) v.findViewById(R.id.fire_detail)).setText(sb.toString());
            ((TextView) v.findViewById(R.id.fire_power)).setText(f.powerText());
            return v;
        }
    }

    /** "Coconino County, AZ / burning 3 days · outline 37 h old · 40 mi NE of you / 6,192 ac". */
    private View perimeterRow(View v, com.atakmap.android.firecast.model.Feds.Fire p) {
        final String place = places.get(p.id);
        ((TextView) v.findViewById(R.id.fire_name)).setText(place != null ? place : "NASA fire event perimeter");
        final StringBuilder sb = new StringBuilder();
        if (!Double.isNaN(p.burningDays))
            sb.append(p.burningDays <= 0 ? "first seen in this outline" : p.burningDays < 1 ? "burning half a day"
                    : "burning " + Math.round(p.burningDays) + (Math.round(p.burningDays) == 1 ? " day" : " days"));
        sb.append(sb.length() == 0 ? "" : " · ").append("outline ").append(age(p.atMs)).append(" old");
        if (from != null)
            sb.append(" · ").append(Units.rangeBearing(from, p.lat(), p.lon())).append(noFix ? " of map center" : " of you");
        ((TextView) v.findViewById(R.id.fire_detail)).setText(sb.toString());
        ((TextView) v.findViewById(R.id.fire_power)).setText(acres(p));
        return v;
    }

    private static String acres(com.atakmap.android.firecast.model.Feds.Fire p) {
        return Double.isNaN(p.areaKm2) ? "size not known" : String.format(Locale.US, "%,.0f ac", p.areaKm2 * 247.105d);
    }

    /** County and state for the first rows, one at a time, the list redrawn as they come. */
    private void namePerimeters() {
        final List<com.atakmap.android.firecast.model.Feds.Fire> want = new ArrayList<>();
        for (int i = 0; i < perimeters.size() && want.size() < NAMED_ROWS; i++)
            if (!places.containsKey(perimeters.get(i).id))
                want.add(perimeters.get(i));
        if (want.isEmpty())
            return;
        placeWorker.execute(new Runnable() {
            @Override
            public void run() {
                for (final com.atakmap.android.firecast.model.Feds.Fire p : want) {
                    String name;
                    try {
                        name = com.atakmap.android.firecast.model.Feds.place(p.lat(), p.lon());
                    } catch (Exception e) {
                        name = null;
                    }
                    if (name == null)
                        continue;
                    final String got = name;
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            places.put(p.id, got);
                            adapter.notifyDataSetChanged();
                        }
                    });
                }
            }
        });
    }

    /** "14 h", "40 min": how long ago, without the word. */
    private static String age(long when) {
        final long mins = Math.max(0L, System.currentTimeMillis() - when) / 60000L;
        return mins < 120 ? mins + " min" : (mins / 60) + " h";
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
        placeWorker.shutdownNow();
    }
}

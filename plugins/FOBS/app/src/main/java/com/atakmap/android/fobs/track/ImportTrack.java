package com.atakmap.android.fobs.track;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.InputType;
import android.util.SparseArray;
import android.widget.EditText;
import android.widget.Toast;

import com.atakmap.android.drawing.mapItems.DrawingShape;
import com.atakmap.android.fobs.plugin.R;
import com.atakmap.android.http.rest.HTTPRequestManager2;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.PointMapItem;
import com.atakmap.android.track.TrackDetails;
import com.atakmap.android.track.crumb.CrumbDatabase;
import com.atakmap.android.track.crumb.CrumbPoint;
import com.atakmap.android.track.http.QueryUserTracksOperation2;
import com.atakmap.android.track.http.QueryUserTracksRequest2;
import com.atakmap.android.track.maps.TrackPolyline;
import com.atakmap.android.track.task.GetTrackUsersTask;
import com.atakmap.android.track.ui.TrackUser;
import com.atakmap.android.util.DatePickerFragment;
import com.atakmap.android.util.ServerListDialog;
import com.atakmap.comms.CotStreamListener;
import com.atakmap.comms.TAKServer;
import com.atakmap.comms.datadroidlite.ConnectionException;
import com.atakmap.comms.datadroidlite.Request;
import com.atakmap.comms.datadroidlite.RequestManager;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.coremap.maps.coords.GeoPointMetaData;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Import a track from ATAK's own track history: this device's breadcrumb log, or the
 * TAK Server's record of any user's track (the beacon's reports land there too).
 *
 * <p>Both go through the machinery ATAK's Track History screen uses. Local segments
 * come straight from {@link CrumbDatabase}. Server tracks are fetched the way the
 * screen does it, a {@link QueryUserTracksRequest2} that ATAK answers by writing the
 * result into the same crumb database, so the two sources end in one code path.
 *
 * <p>Crumbs are run through the same {@link FixFilter} gate and cleanup as a live GPS
 * track, using each crumb's own accuracy, speed and time, so an imported walk is
 * cleaned to the same standard as a walked one. Nothing in either log is modified.
 */
public class ImportTrack implements RequestManager.RequestListener {

    private static final String TAG = "FOBS.ImportTrack";

    private final MapView mapView;
    private final Context plugin;
    private final Context host;
    private final TrackFinisher finisher;
    private final CotStreamListener servers;
    private int notificationId = 71000;
    private android.app.ProgressDialog busy;

    private void showBusy(String message) {
        hideBusy();
        busy = new android.app.ProgressDialog(host);
        busy.setMessage(message);
        busy.setIndeterminate(true);
        busy.setCancelable(false);
        busy.show();
    }

    private void hideBusy() {
        if (busy != null) {
            busy.dismiss();
            busy = null;
        }
    }

    public ImportTrack(MapView mapView, Context pluginContext) {
        this.mapView = mapView;
        this.plugin = pluginContext;
        this.host = mapView.getContext();
        this.finisher = new TrackFinisher(mapView, pluginContext);
        this.servers = new CotStreamListener(host, TAG, null);
    }

    public void dispose() {
        hideBusy();
        servers.dispose();
    }

    // ---- entry -------------------------------------------------------------------

    /**
     * Import goes straight to ATAK's Track History: its list, its on/off toggles, its
     * server search. Turn the track on, then tap its line and pick the FOBS button on
     * the radial menu (or FOBS -> Select a track and tap the line). The FOBS-side
     * lists (my track log, server tracks) stay in the code but are not offered.
     */
    public void begin() {
        openTrackHistory();
    }

    /** ATAK's Track History, with a reminder of how the track gets back to FOBS. */
    private void openTrackHistory() {
        com.atakmap.android.ipc.AtakBroadcast.getInstance().sendBroadcast(
                new android.content.Intent("com.atakmap.android.track.TRACK_HISTORY"));
        Toast.makeText(host, plugin.getString(R.string.import_track_history_hint),
                Toast.LENGTH_LONG).show();
    }

    /** A track line the operator turned on in Track History and tapped in Select a track. */
    public void importVisible(TrackPolyline track) {
        nameThenImport(track);
    }

    private TAKServer[] connectedServers() {
        List<TAKServer> out = new ArrayList<>();
        TAKServer[] all = servers.getServers();
        if (all != null)
            for (TAKServer s : all)
                if (s.isEnabled() && s.isConnected())
                    out.add(s);
        return out.toArray(new TAKServer[0]);
    }

    // ---- my track log ------------------------------------------------------------

    private void listLocal() {
        final String uid = MapView.getDeviceUid();
        final android.app.ProgressDialog loading = new android.app.ProgressDialog(host);
        loading.setMessage(plugin.getString(R.string.import_loading));
        loading.setIndeterminate(true);
        loading.setCancelable(false);
        loading.show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<TrackPolyline> tracks = CrumbDatabase.instance()
                        .getTracks(uid, false, true, null);
                mapView.post(new Runnable() {
                    @Override
                    public void run() {
                        loading.dismiss();
                        showTracks(plugin.getString(R.string.import_local), tracks);
                    }
                });
            }
        }, TAG + "-local").start();
    }

    // ---- server ------------------------------------------------------------------

    private void pickServer(TAKServer[] list) {
        if (list.length == 1) {
            pickUser(list[0]);
            return;
        }
        ServerListDialog.selectServer(host, plugin.getString(R.string.import_server), list,
                new ServerListDialog.Callback() {
                    @Override
                    public void onSelected(TAKServer server) {
                        if (server != null)
                            pickUser(server);
                    }
                });
    }

    /** Whose track: this device first, then everyone the server knows, filter as you type. */
    private void pickUser(final TAKServer server) {
        showBusy(plugin.getString(R.string.import_fetching_users));
        new GetTrackUsersTask(host, new GetTrackUsersTask.Callback() {
            @Override
            public void onComplete(List<TrackUser> users) {
                hideBusy();
                if (users == null)
                    users = new ArrayList<>();
                final List<TrackUser> ordered = new ArrayList<>();
                String me = MapView.getDeviceUid();
                for (TrackUser u : users)
                    if (me.equals(u.getUid()))
                        ordered.add(u);
                if (ordered.isEmpty())
                    ordered.add(new TrackUser(mapView.getDeviceCallsign(), me, 0));
                for (TrackUser u : users)
                    if (!me.equals(u.getUid()) && !contains(ordered, u))
                        ordered.add(u);
                showUserPicker(server, ordered, me);
            }
        }, server.getConnectString()).execute();
    }

    private void showUserPicker(final TAKServer server, final List<TrackUser> all,
            final String me) {
        android.widget.LinearLayout box = new android.widget.LinearLayout(host);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (8 * host.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, 0);
        final EditText search = new EditText(host);
        search.setHint(plugin.getString(R.string.import_search_hint));
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        box.addView(search);
        final android.widget.ListView list = new android.widget.ListView(host);
        box.addView(list, new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (280 * host.getResources().getDisplayMetrics().density)));

        final List<TrackUser> shown = new ArrayList<>(all);
        final List<String> labels = new ArrayList<>();
        final android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(
                host, android.R.layout.simple_list_item_1, labels);
        final Runnable refill = new Runnable() {
            @Override
            public void run() {
                String q = search.getText().toString().trim().toLowerCase();
                shown.clear();
                labels.clear();
                for (TrackUser u : all) {
                    String cs = u.getCallsign() == null ? "" : u.getCallsign();
                    if (q.isEmpty() || cs.toLowerCase().contains(q)) {
                        shown.add(u);
                        labels.add(me.equals(u.getUid())
                                ? plugin.getString(R.string.import_me, cs) : cs);
                    }
                }
                adapter.notifyDataSetChanged();
            }
        };
        refill.run();
        list.setAdapter(adapter);
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence c, int a, int b, int d) {
            }

            @Override
            public void onTextChanged(CharSequence c, int a, int b, int d) {
                refill.run();
            }

            @Override
            public void afterTextChanged(android.text.Editable e) {
            }
        });

        final AlertDialog dialog = new AlertDialog.Builder(host)
                .setTitle(plugin.getString(R.string.import_whose))
                .setView(box)
                .setNegativeButton(plugin.getString(R.string.cancel), null)
                .create();
        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, android.view.View v,
                    int position, long id) {
                if (position < 0 || position >= shown.size())
                    return;
                TrackUser picked = shown.get(position);
                dialog.dismiss();
                pickWindow(server, picked);
            }
        });
        dialog.show();
    }

    private static boolean contains(List<TrackUser> list, TrackUser u) {
        for (TrackUser t : list)
            if (t.getUid() != null && t.getUid().equals(u.getUid()))
                return true;
        return false;
    }

    private void pickWindow(final TAKServer server, final TrackUser user) {
        final String[] rows = {
                plugin.getString(R.string.import_window_today),
                plugin.getString(R.string.import_window_24h),
                plugin.getString(R.string.import_window_7d),
                plugin.getString(R.string.import_window_dates)
        };
        new AlertDialog.Builder(host)
                .setTitle(plugin.getString(R.string.import_when, user.getCallsign()))
                .setItems(rows, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        long now = System.currentTimeMillis();
                        long start;
                        if (which == 0) {
                            start = startOfDay(now);
                        } else if (which == 1) {
                            start = now - 24L * 3600_000L;
                        } else if (which == 2) {
                            start = now - 7L * 24L * 3600_000L;
                        } else {
                            pickDates(server, user);
                            return;
                        }
                        query(server, user, start, now);
                    }
                })
                .setNegativeButton(plugin.getString(R.string.cancel), null)
                .show();
    }

    private static long startOfDay(long t) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(t);
        c.set(java.util.Calendar.HOUR_OF_DAY, 0);
        c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /**
     * Start date, then end date, with ATAK's own date picker (the one Track History
     * uses). Whole days: the start day from 00:00, the end day through 23:59.
     */
    private void pickDates(final TAKServer server, final TrackUser user) {
        final long now = System.currentTimeMillis();
        final long min = now - 365L * 24L * 3600_000L;
        final android.app.FragmentManager fm = ((android.app.Activity) host).getFragmentManager();
        toast(plugin.getString(R.string.import_pick_start));
        DatePickerFragment startPicker = new DatePickerFragment();
        startPicker.init(startOfDay(now), new DatePickerFragment.DatePickerListener() {
            @Override
            public void onDatePicked(int y, int m, int d) {
                java.util.Calendar c = java.util.Calendar.getInstance();
                c.clear();
                c.set(y, m, d, 0, 0, 0);
                final long start = c.getTimeInMillis();
                toast(plugin.getString(R.string.import_pick_end));
                DatePickerFragment endPicker = new DatePickerFragment();
                endPicker.init(Math.max(start, startOfDay(now)),
                        new DatePickerFragment.DatePickerListener() {
                            @Override
                            public void onDatePicked(int y2, int m2, int d2) {
                                java.util.Calendar e = java.util.Calendar.getInstance();
                                e.clear();
                                e.set(y2, m2, d2, 23, 59, 59);
                                long end = Math.min(e.getTimeInMillis(), now);
                                if (end <= start) {
                                    toast(plugin.getString(R.string.import_bad_dates));
                                    return;
                                }
                                query(server, user, start, end);
                            }
                        }, start, now);
                endPicker.show(fm, "fobsEndDate");
            }
        }, min, now);
        startPicker.show(fm, "fobsStartDate");
    }

    /** The same request ATAK's Track History sends; ATAK stores the answer as crumbs. */
    private void query(TAKServer server, TrackUser user, long start, long end) {
        String baseUrl = ServerListDialog.getBaseUrl(server);
        QueryUserTracksRequest2 req = new QueryUserTracksRequest2(baseUrl, notificationId++,
                user.getCallsign(), user.getUid(), start, end, server.getConnectString());
        Log.d(TAG, "server track query sent");
        showBusy(plugin.getString(R.string.import_fetching_tracks, user.getCallsign()));
        HTTPRequestManager2.from(host).execute(req.createQueryUserTracksRequest(), this);
    }

    @Override
    public void onRequestFinished(Request request, Bundle result) {
        hideBusy();
        QueryUserTracksRequest2 q = result == null ? null
                : (QueryUserTracksRequest2) request.getParcelable(QueryUserTracksOperation2.PARAM_QUERY);
        if (q == null) {
            toast(plugin.getString(R.string.import_server_failed));
            return;
        }
        if (result.getBoolean(QueryUserTracksOperation2.PARAM_TRACKNOTFOUND, false)) {
            toast(plugin.getString(R.string.import_server_none, q.getCallsign()));
            return;
        }
        final int[] ids = result.getIntArray(QueryUserTracksOperation2.PARAM_TRACKDBIDS);
        if (ids == null || ids.length == 0) {
            toast(plugin.getString(R.string.import_server_none, q.getCallsign()));
            return;
        }
        final String title = plugin.getString(R.string.import_server_title, q.getCallsign());
        showBusy(plugin.getString(R.string.import_loading));
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<TrackPolyline> tracks = CrumbDatabase.instance().getTracks(ids, null);
                mapView.post(new Runnable() {
                    @Override
                    public void run() {
                        hideBusy();
                        showTracks(title, tracks);
                    }
                });
            }
        }, TAG + "-server").start();
    }

    @Override
    public void onRequestConnectionError(Request request, ConnectionException e) {
        hideBusy();
        Log.w(TAG, "server query failed", e);
        toast(plugin.getString(R.string.import_server_failed));
    }

    @Override
    public void onRequestDataError(Request request) {
        hideBusy();
        toast(plugin.getString(R.string.import_server_failed));
    }

    // ---- the list, shared --------------------------------------------------------

    private void showTracks(String title, final List<TrackPolyline> tracks) {
        if (tracks == null || tracks.isEmpty()) {
            toast(plugin.getString(R.string.import_none));
            return;
        }
        // Segments that never went anywhere (ATAK starts one at every launch) are
        // noise here: fewer than 5 fixes or under 15 m of travel are left out.
        final List<TrackPolyline> walks = new ArrayList<>();
        for (TrackPolyline t : tracks) {
            TrackDetails d = new TrackDetails(mapView, t);
            boolean keep = t.getNumPoints() >= 5 && d.getDistanceDouble() >= 15;
            d.dispose();
            if (keep)
                walks.add(t);
        }
        if (walks.isEmpty()) {
            toast(plugin.getString(R.string.import_none));
            return;
        }
        DateFormat df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
        String[] rows = new String[walks.size()];
        for (int i = 0; i < rows.length; i++) {
            TrackPolyline t = walks.get(i);
            TrackDetails d = new TrackDetails(mapView, t);
            String when = d.getStartTime() > 0 ? df.format(new Date(d.getStartTime())) : "";
            String dist = FobsShapes.formatDistance(host, d.getDistanceDouble());
            rows[i] = d.getTitle() + "\n"
                    + plugin.getString(R.string.import_row, when,
                            elapsed(d.getTimeElapsedLong()), dist, t.getNumPoints());
            d.dispose();
        }
        new AlertDialog.Builder(host)
                .setTitle(title)
                .setItems(rows, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        nameThenImport(walks.get(which));
                    }
                })
                .setNegativeButton(plugin.getString(R.string.cancel), null)
                .show();
    }

    private String elapsed(long ms) {
        long s = ms / 1000;
        long h = s / 3600, m = (s % 3600) / 60;
        if (h > 0)
            return plugin.getString(R.string.import_hours_minutes, h, m);
        return plugin.getString(R.string.import_minutes, m);
    }

    private void nameThenImport(final TrackPolyline source) {
        String pattern = plugin.getString(R.string.track_default_name);
        String callsign = mapView.getDeviceCallsign();
        final EditText name = new EditText(host);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        name.setSingleLine(true);
        name.setText(String.format(pattern, callsign,
                FobsShapes.nextTrackNumber(mapView, pattern, callsign)));
        name.setSelection(name.getText().length());
        new AlertDialog.Builder(host)
                .setTitle(plugin.getString(R.string.name_track))
                .setView(name)
                .setPositiveButton(plugin.getString(R.string.ok),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                importTrack(name.getText().toString().trim(), source);
                            }
                        })
                .setNegativeButton(plugin.getString(R.string.cancel), null)
                .show();
    }

    // ---- crumbs to track ---------------------------------------------------------

    private void importTrack(final String title, final TrackPolyline source) {
        final int dbId = source.getMetaInteger(CrumbDatabase.META_TRACK_DBID, -1);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<CrumbPoint> crumbs = dbId >= 0
                        ? CrumbDatabase.instance().getCrumbPoints(dbId) : null;
                mapView.post(new Runnable() {
                    @Override
                    public void run() {
                        build(title, source, crumbs);
                    }
                });
            }
        }, TAG + "-crumbs").start();
    }

    /** Main thread. Gate and clean the crumbs like a live walk, then make the track. */
    private void build(String title, TrackPolyline source, List<CrumbPoint> crumbs) {
        List<GeoPointMetaData> points = new ArrayList<>();
        List<FixFilter.Fix> fixes = new ArrayList<>();
        FixFilter.Thresholds t = new FixFilter.Thresholds();
        // A record, not a live feed: beacon reports are sparse and their timestamps
        // are the server's, so implied speed means nothing here. Three real fixes on
        // a server track were dropped as "too fast" on 2026-09-05. Accuracy and
        // spacing still apply.
        t.maxSpeed = Double.POSITIVE_INFINITY;
        FixFilter.LiveGate gate = new FixFilter.LiveGate(t);
        int raw = 0;
        if (crumbs != null && !crumbs.isEmpty()) {
            for (CrumbPoint c : crumbs) {
                if (c == null || c.gp == null || !c.gp.isValid())
                    continue;
                raw++;
                double ce = c.gp.getCE();
                if (ce == GeoPoint.UNKNOWN)
                    ce = Double.NaN;
                FixFilter.Fix f = new FixFilter.Fix(c.gp.getLatitude(), c.gp.getLongitude(), ce,
                        c.timestamp);
                if (gate.judge(f) != FixFilter.Verdict.ACCEPTED)
                    continue;
                GeoPointMetaData gpm = c.gpm != null ? new GeoPointMetaData(c.gpm)
                        : GeoPointMetaData.wrap(c.gp);
                if (gpm.getGeopointSource() == null)
                    gpm.setGeoPointSource(FobsShapes.SOURCE_GPS);
                fixes.add(f);
                points.add(gpm);
            }
        } else {
            // No crumb rows (older import): fall back to the polyline's own points.
            GeoPointMetaData[] pts = source.getMetaDataPoints();
            if (pts != null)
                for (GeoPointMetaData p : pts) {
                    if (p == null || p.get() == null || !p.get().isValid())
                        continue;
                    raw++;
                    GeoPoint g = p.get();
                    double ce = g.getCE() == GeoPoint.UNKNOWN ? Double.NaN : g.getCE();
                    FixFilter.Fix f = new FixFilter.Fix(g.getLatitude(), g.getLongitude(), ce, 0);
                    if (gate.judge(f) != FixFilter.Verdict.ACCEPTED)
                        continue;
                    fixes.add(f);
                    points.add(new GeoPointMetaData(p));
                }
        }
        if (points.size() < 2) {
            toast(plugin.getString(R.string.toast_nothing_recorded));
            return;
        }
        boolean[] keep = FixFilter.cleanup(fixes, t);
        int removed = FixFilter.removedCount(keep);
        List<GeoPointMetaData> cleaned = FixFilter.apply(points, keep);
        int dropped = gate.dropped() + removed;
        Log.d(TAG, "import raw=" + raw + " accepted=" + points.size()
                + " tooClose=" + gate.count(FixFilter.Verdict.TOO_CLOSE)
                + " badAccuracy=" + gate.count(FixFilter.Verdict.BAD_ACCURACY)
                + " tooFast=" + gate.count(FixFilter.Verdict.TOO_FAST)
                + " staleTime=" + gate.count(FixFilter.Verdict.STALE_TIME)
                + " endPassRemoved=" + removed + " final=" + cleaned.size());

        DrawingShape track = FobsShapes.newTrack(mapView, title, FobsShapes.SOURCE_GPS);
        track.setPoints(cleaned, new SparseArray<PointMapItem>());
        track.setMetaString(FobsShapes.META_RAW, String.valueOf(raw));
        track.setMetaString(FobsShapes.META_DROPPED, String.valueOf(dropped));
        FobsShapes.addToMap(track);
        if (dropped > 0)
            toast(plugin.getString(R.string.toast_removed_bad_fixes, dropped));
        finisher.ask(track, getClass());
    }

    private void toast(String s) {
        Toast.makeText(host, s, Toast.LENGTH_SHORT).show();
    }
}

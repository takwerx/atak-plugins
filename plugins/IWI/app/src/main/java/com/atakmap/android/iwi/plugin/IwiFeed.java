package com.atakmap.android.iwi.plugin;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.net.Uri;

import com.atakmap.android.maps.MapView;
import com.atakmap.android.util.ServerListDialog;
import com.atakmap.comms.CommsMapComponent;
import com.atakmap.comms.CotStreamListener;
import com.atakmap.comms.TAKServer;
import com.atakmap.comms.http.TakHttpClient;
import com.atakmap.comms.http.TakHttpResponse;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.log.Log;

import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.StringEntity;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The IWI in a Data Sync feed (operator, 2026-11-06: "datasync only ... that way we
 * dont have to worry about if someone is out of comms"). The feed is the record: a
 * device that was off the network gets the IWI from the server when it is back.
 *
 * <p>Copied forward from FOBS's FeedPublisher (shared helpers are copy-forward): list
 * the feeds with {@code api/missions}, send the CoT with commo's mission destination
 * ({@code sendCoTToServersByMission}, to the chosen server only), and the first time a
 * UID goes out, subscribe and add it to the feed's contents. Every later send is a
 * change to the same item.
 *
 * <p>This device off the network: the newest event per marker waits, on disk, and goes
 * out when the server is connected again. Clear takes the markers out of the feed; a
 * removal from the map alone never does, since a peer can make ATAK remove an item.
 */
public final class IwiFeed {

    private static final String TAG = "IWI-Feed";
    private static final String K_NAME = "iwi.feed.name";
    private static final String K_SERVER = "iwi.feed.server";
    /** The last feed picked, offered by name at the next Declare. */
    private static final String K_LAST_NAME = "iwi.feed.lastName";
    private static final String K_LAST_SERVER = "iwi.feed.lastServer";
    /** A UID already in the feed's contents. */
    private static final String K_PUB = "iwi.feed.pub.";
    /** The newest event for a UID that could not go out yet. */
    private static final String K_WAIT = "iwi.feed.wait.";

    private static IwiFeed instance;

    private final MapView mapView;
    private final Context host;
    private final SharedPreferences prefs;
    private final CotStreamListener servers;

    public interface Then {
        void done();
    }

    IwiFeed(MapView mapView, SharedPreferences prefs) {
        this.mapView = mapView;
        this.host = mapView.getContext();
        this.prefs = prefs;
        this.servers = new CotStreamListener(host, TAG, null);
        instance = this;
    }

    void dispose() {
        servers.dispose();
        if (instance == this)
            instance = null;
    }

    /** The feed's name, or "" when the IWI stays on this device. */
    String name() {
        return prefs.getString(K_NAME, "");
    }

    private String server() {
        return prefs.getString(K_SERVER, "");
    }

    boolean isSet() {
        return !name().isEmpty() && !server().isEmpty();
    }

    /** "Fire Crew 7 (tak.example)", for the Settings row. */
    String describe() {
        if (!isSet())
            return "None";
        final TAKServer s = find(server());
        return name() + (s == null ? "" : " on " + s.getDescription());
    }

    private TAKServer[] connected() {
        final List<TAKServer> out = new ArrayList<>();
        final TAKServer[] all = servers.getServers();
        if (all != null)
            for (TAKServer s : all)
                if (s.isEnabled() && s.isConnected())
                    out.add(s);
        return out.toArray(new TAKServer[0]);
    }

    private TAKServer find(String connectString) {
        final TAKServer[] all = servers.getServers();
        if (all != null)
            for (TAKServer s : all)
                if (connectString.equals(s.getConnectString()))
                    return s;
        return null;
    }

    private boolean isConnected(String connectString) {
        final TAKServer s = find(connectString);
        return s != null && s.isEnabled() && s.isConnected();
    }

    // ---- choosing ----

    /** Pick a server, then one of its feeds; or None. Calls back once chosen. */
    void choose(final Then then) {
        final TAKServer[] list = connected();
        if (list.length == 0) {
            new AlertDialog.Builder(host)
                    .setTitle("No TAK Server connected")
                    .setMessage("Connect to the server first; its Data Sync feeds are "
                            + "listed from it." + (isSet() ? "\n\nNow: " + describe() : ""))
                    .setPositiveButton("OK", null)
                    .setNegativeButton(isSet() ? "Stop Sharing" : null,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface d, int w) {
                                    setFeed("", "");
                                    then.done();
                                }
                            })
                    .show();
            return;
        }
        if (list.length == 1) {
            listFeeds(list[0], then);
            return;
        }
        ServerListDialog.selectServer(host, "Data Sync Feed: pick the server", list,
                new ServerListDialog.Callback() {
                    @Override
                    public void onSelected(TAKServer s) {
                        if (s != null)
                            listFeeds(s, then);
                    }
                });
    }

    /**
     * At Declare, every time a server is connected: where this IWI goes, asked right
     * there, the FOBS way (operator, 2026-11-06). The last feed is one tap; nothing has
     * to be set up beforehand.
     */
    void askAtDeclare(final Then then) {
        final TAKServer[] list = connected();
        if (list.length == 0)
            return;
        final String lastName = prefs.getString(K_LAST_NAME, "");
        final String lastServer = prefs.getString(K_LAST_SERVER, "");
        final boolean offerLast = !lastName.isEmpty() && isConnected(lastServer);
        final AlertDialog.Builder b = new AlertDialog.Builder(host)
                .setTitle("Send this IWI to a Data Sync feed?")
                .setMessage("Everyone subscribed to the feed gets the IWI and every "
                        + "update, including devices that are off the network now.")
                .setNegativeButton("This Device Only", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        setFeed("", "");
                        then.done();
                    }
                });
        if (offerLast) {
            b.setPositiveButton("Send to " + lastName, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    setFeed(lastName, lastServer);
                    then.done();
                }
            });
            b.setNeutralButton("Choose Feed", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    choose(then);
                }
            });
        } else {
            b.setPositiveButton("Choose Feed", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    choose(then);
                }
            });
        }
        b.setCancelable(false).show();
    }

    /**
     * Before a new IWI's first marker: with a server there, the question at Declare
     * decides, so an old choice sends nothing meanwhile. With no server, the choice in
     * Settings stands and the marker waits for it.
     */
    void holdForDeclare() {
        if (connected().length > 0)
            prefs.edit().putString(K_NAME, "").putString(K_SERVER, "").apply();
    }

    private void listFeeds(final TAKServer server, final Then then) {
        final android.app.ProgressDialog busy = new android.app.ProgressDialog(host);
        busy.setMessage("Listing the feeds…");
        busy.setIndeterminate(true);
        busy.setCancelable(false);
        busy.show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<String> names = new ArrayList<>();
                final List<Boolean> locked = new ArrayList<>();
                boolean failed = false;
                try {
                    final TakHttpClient client = client(server.getConnectString());
                    final String json = client.get(client.getUrl(
                            "api/missions?passwordProtected=true&defaultRole=true"));
                    final JSONArray data = new JSONObject(json).optJSONArray("data");
                    if (data != null)
                        for (int i = 0; i < data.length(); i++) {
                            final JSONObject m = data.optJSONObject(i);
                            final String n = m == null ? null : m.optString("name", null);
                            if (FileSystemUtils.isEmpty(n))
                                continue;
                            names.add(n);
                            locked.add(m.optBoolean("passwordProtected", false));
                        }
                } catch (Exception e) {
                    Log.w(TAG, "feed list failed", e);
                    failed = true;
                }
                final boolean err = failed;
                mapView.post(new Runnable() {
                    @Override
                    public void run() {
                        busy.dismiss();
                        if (err || names.isEmpty()) {
                            new AlertDialog.Builder(host)
                                    .setTitle(err ? "Could not list the feeds"
                                            : "No feeds on this server")
                                    .setMessage(err ? "The server did not answer. Try again "
                                            + "when the connection is steady."
                                            : "Make one in Data Sync, then pick it here.")
                                    .setPositiveButton("OK", null)
                                    .show();
                            return;
                        }
                        showFeeds(server, names, locked, then);
                    }
                });
            }
        }, TAG + "-list").start();
    }

    private void showFeeds(final TAKServer server, final List<String> names,
            final List<Boolean> locked, final Then then) {
        final String[] rows = new String[names.size() + 1];
        rows[0] = "None: this device only";
        for (int i = 0; i < names.size(); i++)
            rows[i + 1] = names.get(i) + (locked.get(i) ? " (password, not supported)" : "");
        new AlertDialog.Builder(host)
                .setTitle("Data Sync Feed on " + server.getDescription())
                .setItems(rows, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            setFeed("", "");
                        } else if (locked.get(which - 1)) {
                            return;
                        } else {
                            setFeed(names.get(which - 1), server.getConnectString());
                        }
                        then.done();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** A new feed: everything is new to it, so the next send adds it to the contents. */
    private void setFeed(String name, String server) {
        final SharedPreferences.Editor e = prefs.edit()
                .putString(K_NAME, name).putString(K_SERVER, server);
        if (!name.isEmpty())
            e.putString(K_LAST_NAME, name).putString(K_LAST_SERVER, server);
        for (String k : prefs.getAll().keySet())
            if (k.startsWith(K_PUB) || k.startsWith(K_WAIT))
                e.remove(k);
        e.apply();
    }

    // ---- sending ----

    /** Every marker write comes here; to the feed if one is set, else nowhere. */
    static void offer(CotEvent e) {
        final IwiFeed f = instance;
        if (f != null && e != null)
            f.send(e);
    }

    /** Take a marker out of the feed: Clear, the operator's own act. */
    static void withdraw(String uid) {
        final IwiFeed f = instance;
        if (f != null && uid != null && !uid.isEmpty())
            f.remove(uid);
    }

    private void send(CotEvent e) {
        send(e, true);
    }

    /** @param file whether the UID goes in the feed's contents; a delete never does. */
    private void send(CotEvent e, boolean file) {
        if (!isSet())
            return;
        final String uid = e.getUID();
        if (!isConnected(server())) {
            // Off the network: the newest version waits for the server.
            prefs.edit().putString(K_WAIT + uid, e.toString()).apply();
            return;
        }
        prefs.edit().remove(K_WAIT + uid).apply();
        // commo adds the mission destination itself, to this server only.
        CommsMapComponent.getInstance().sendCoTToServersByMission(server(), name(), e);
        if (file && !prefs.getBoolean(K_PUB + uid, false))
            addToContents(server(), name(), uid);
    }

    /** From the pane's tick: what waited goes out once the server is back. */
    void flush() {
        if (!isSet() || !isConnected(server()))
            return;
        for (String k : new ArrayList<>(prefs.getAll().keySet())) {
            if (!k.startsWith(K_WAIT))
                continue;
            final CotEvent e = CotEvent.parse(prefs.getString(k, ""));
            prefs.edit().remove(k).apply();
            if (e != null && e.isValid())
                send(e, !"t-x-d-d".equals(e.getType()));
        }
    }

    private void remove(String uid) {
        final boolean pub = prefs.getBoolean(K_PUB + uid, false);
        prefs.edit().remove(K_PUB + uid).remove(K_WAIT + uid).apply();
        if (!isSet())
            return;
        // Every device in the feed takes the marker off its map, Data Sync or not:
        // ATAK's own delete, t-x-d-d with __forcedelete (operator, 2026-11-06). Sent
        // like any other IWI event, so it waits for the server if this device is off.
        send(deleteEvent(uid), false);
        if (!pub)
            return;
        final String server = server();
        final String feed = name();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final TakHttpClient client = client(server);
                    final String url = Uri.parse(client.getUrl("api/missions/"
                            + Uri.encode(feed) + "/contents")).buildUpon()
                            .appendQueryParameter("uid", uid)
                            .appendQueryParameter("creatorUid", MapView.getDeviceUid())
                            .build().toString();
                    final TakHttpResponse r = client.execute(
                            new HttpDelete(FileSystemUtils.sanitizeURL(url)));
                    Log.d(TAG, "DELETE contents -> " + r.getStatusCode());
                    logContents(client, feed);
                } catch (Exception ex) {
                    Log.w(TAG, "remove from feed failed", ex);
                }
            }
        }, TAG + "-delete").start();
    }

    /** For the log: which IWI items the feed still holds after a Clear. */
    private static void logContents(TakHttpClient client, String feed) {
        try {
            final String json = client.get(client.getUrl("api/missions/"
                    + Uri.encode(feed)));
            final JSONArray data = new JSONObject(json).optJSONArray("data");
            final JSONArray items = data == null || data.length() == 0 ? null
                    : data.getJSONObject(0).optJSONArray("uids");
            int iwi = 0;
            final int n = items == null ? 0 : items.length();
            for (int i = 0; i < n; i++)
                if (items.getJSONObject(i).optString("data", "").startsWith("IWI"))
                    iwi++;
            Log.d(TAG, "feed holds " + n + " items, " + iwi + " of them IWI");
        } catch (Exception e) {
            Log.w(TAG, "could not read the feed back", e);
        }
    }

    /** ATAK's delete for a marker (CotDeleteImporter): link to it, forced. */
    private static CotEvent deleteEvent(String uid) {
        final CotEvent e = new CotEvent();
        e.setVersion("2.0");
        e.setUID("IWI-delete-" + uid);
        e.setType("t-x-d-d");
        e.setHow("h-g-i-g-o");
        final com.atakmap.coremap.maps.time.CoordinatedTime now =
                new com.atakmap.coremap.maps.time.CoordinatedTime();
        e.setTime(now);
        e.setStart(now);
        e.setStale(new com.atakmap.coremap.maps.time.CoordinatedTime(
                now.getMilliseconds() + 60L * 60L * 1000L));
        e.setPoint(new com.atakmap.coremap.cot.event.CotPoint(0, 0,
                com.atakmap.coremap.cot.event.CotPoint.UNKNOWN,
                com.atakmap.coremap.cot.event.CotPoint.UNKNOWN,
                com.atakmap.coremap.cot.event.CotPoint.UNKNOWN));
        final com.atakmap.coremap.cot.event.CotDetail detail =
                new com.atakmap.coremap.cot.event.CotDetail("detail");
        final com.atakmap.coremap.cot.event.CotDetail link =
                new com.atakmap.coremap.cot.event.CotDetail("link");
        link.setAttribute("uid", uid);
        link.setAttribute("relation", "none");
        link.setAttribute("type", "none");
        detail.addChild(link);
        detail.addChild(new com.atakmap.coremap.cot.event.CotDetail("__forcedelete"));
        e.setDetail(detail);
        return e;
    }

    /** First send of a UID: subscribe, then add it to the contents, as Data Sync does. */
    private void addToContents(final String server, final String feed, final String uid) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                // The CoT went out a moment ago; give the server time to file it.
                for (int attempt = 1; attempt <= 4; attempt++) {
                    try {
                        Thread.sleep(attempt == 1 ? 2000 : 3000);
                    } catch (InterruptedException ignored) {
                        return;
                    }
                    if (putContents(server, feed, uid)) {
                        prefs.edit().putBoolean(K_PUB + uid, true).apply();
                        return;
                    }
                }
                Log.w(TAG, "could not add " + uid + " to the feed; the next send tries again");
            }
        }, TAG + "-put").start();
    }

    private boolean subscribe(TakHttpClient client, String feed) throws Exception {
        final String url = Uri.parse(client.getUrl("api/missions/" + Uri.encode(feed)
                + "/subscription")).buildUpon()
                .appendQueryParameter("uid", MapView.getDeviceUid()).build().toString();
        final TakHttpResponse r = client.execute(new HttpPut(FileSystemUtils.sanitizeURL(url)));
        Log.d(TAG, "PUT subscription -> " + r.getStatusCode());
        return r.isOk() || r.isCreated();
    }

    private boolean putContents(String server, String feed, String uid) {
        try {
            final TakHttpClient client = client(server);
            if (!subscribe(client, feed))
                return false;
            final String url = Uri.parse(client.getUrl("api/missions/" + Uri.encode(feed)
                    + "/contents")).buildUpon()
                    .appendQueryParameter("creatorUid", MapView.getDeviceUid())
                    .build().toString();
            final HttpPut put = new HttpPut(FileSystemUtils.sanitizeURL(url));
            final JSONObject body = new JSONObject();
            body.put("uids", new JSONArray().put(uid));
            final StringEntity entity = new StringEntity(body.toString());
            entity.setContentType("application/json");
            put.setEntity(entity);
            put.addHeader("Content-Type", "application/json");
            final TakHttpResponse r = client.execute(put);
            Log.d(TAG, "PUT contents -> " + r.getStatusCode());
            return r.isOk() || r.isCreated();
        } catch (Exception e) {
            Log.w(TAG, "add to feed failed", e);
            return false;
        }
    }

    private static TakHttpClient client(String connectString) {
        return TakHttpClient.GetHttpClient(ServerListDialog.getBaseUrl(connectString),
                connectString);
    }
}

package com.atakmap.android.fobs.feed;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.widget.Toast;

import com.atakmap.comms.CommsMapComponent;
import com.atakmap.android.drawing.mapItems.DrawingShape;
import com.atakmap.android.fobs.plugin.R;
import com.atakmap.android.fobs.track.FobsShapes;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.util.ServerListDialog;
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
 * Live to a Data Sync feed. A track that is "in a feed" is published to a TAK Server
 * mission when it first reaches the map and re-sent every time it is saved, so on
 * other screens it grows like a breadcrumb but it is a feed item and it stays. Make
 * polygon is the same map item, same UID, so the feed item becomes an area on its own.
 *
 * <p>Two channels, both ATAK's own: the CoT goes out on the server's streaming
 * connection with a {@code <marti><dest mission="..."/></marti>} detail, and the UID
 * is added to the mission's contents with the same REST call ATAK's Data Sync uses
 * ({@code PUT /Marti/api/missions/{name}/contents}), over ATAK's HTTP client, which
 * carries the server's certificates and login. Split, join and lasso carry the feed
 * membership onto their result and take the consumed pieces out of the feed; a bare
 * delete from the map does not touch the feed.
 *
 * <p>Data Sync itself is not in the SDK; this is what Fire Area Survey did, read from
 * its APK, minus the forked UI. Password-protected feeds are listed but refused for now.
 */
public class FeedPublisher {

    private static final String TAG = "FOBS.Feed";
    private static final String PREF_LAST_FEED = "fobs_last_feed";
    private static final String PREF_LAST_SERVER = "fobs_last_feed_server";
    /** Feed name, on the shape and in the CoT detail so peers see which feed it is in. */
    public static final String META_FEED = "fobs_feed";
    /** Server connect string, on the shape only. */
    public static final String META_FEED_SERVER = "fobs_feed_server";
    private static final String META_PUBLISHED = "fobs_feed_published";

    private static FeedPublisher instance;

    public static FeedPublisher get() {
        return instance;
    }

    private final MapView mapView;
    private final Context plugin;
    private final Context host;
    private final CotStreamListener servers;
    private final SharedPreferences prefs;

    public FeedPublisher(MapView mapView, Context pluginContext) {
        this.mapView = mapView;
        this.plugin = pluginContext;
        this.host = mapView.getContext();
        this.servers = new CotStreamListener(host, TAG, null);
        this.prefs = PreferenceManager.getDefaultSharedPreferences(host);
        instance = this;
    }

    public void dispose() {
        servers.dispose();
        if (instance == this)
            instance = null;
    }

    // ---- choosing ------------------------------------------------------------------

    /** A feed choice: server plus mission name, or null for "just local". */
    public static final class Feed {
        public final String server;
        public final String name;

        public Feed(String server, String name) {
            this.server = server;
            this.name = name;
        }
    }

    public interface OnFeed {
        void go(Feed feed);
    }

    public TAKServer[] connectedServers() {
        List<TAKServer> out = new ArrayList<>();
        TAKServer[] all = servers.getServers();
        if (all != null)
            for (TAKServer s : all)
                if (s.isEnabled() && s.isConnected())
                    out.add(s);
        return out.toArray(new TAKServer[0]);
    }

    /**
     * Just local, or Send to feed. Offered only when a server is connected. The last
     * feed used is offered by name so the second track of the day is one tap.
     */
    public void ask(final OnFeed then) {
        final TAKServer[] list = connectedServers();
        if (list.length == 0) {
            then.go(null);
            return;
        }
        final String lastName = prefs.getString(PREF_LAST_FEED, null);
        final String lastServer = prefs.getString(PREF_LAST_SERVER, null);
        AlertDialog.Builder b = new AlertDialog.Builder(host)
                .setTitle(plugin.getString(R.string.feed_ask_title))
                .setPositiveButton(plugin.getString(R.string.feed_just_local),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                then.go(null);
                            }
                        })
                .setNegativeButton(plugin.getString(R.string.feed_choose),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                pickServer(list, then);
                            }
                        });
        if (lastName != null && lastServer != null && stillConnected(lastServer, list)) {
            b.setNeutralButton(plugin.getString(R.string.feed_send_to, lastName),
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int which) {
                            then.go(new Feed(lastServer, lastName));
                        }
                    });
        }
        b.setCancelable(false).show();
    }

    private static boolean stillConnected(String connectString, TAKServer[] list) {
        for (TAKServer s : list)
            if (connectString.equals(s.getConnectString()))
                return true;
        return false;
    }

    private void pickServer(TAKServer[] list, final OnFeed then) {
        if (list.length == 1) {
            listFeeds(list[0], then);
            return;
        }
        ServerListDialog.selectServer(host, plugin.getString(R.string.feed_choose), list,
                new ServerListDialog.Callback() {
                    @Override
                    public void onSelected(TAKServer server) {
                        if (server != null)
                            listFeeds(server, then);
                    }
                });
    }

    /** GET api/missions, the same list Data Sync shows. */
    private void listFeeds(final TAKServer server, final OnFeed then) {
        final android.app.ProgressDialog busy = new android.app.ProgressDialog(host);
        busy.setMessage(plugin.getString(R.string.feed_loading));
        busy.setIndeterminate(true);
        busy.setCancelable(false);
        busy.show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<String> names = new ArrayList<>();
                final List<Boolean> locked = new ArrayList<>();
                String error = null;
                try {
                    TakHttpClient client = TakHttpClient.GetHttpClient(
                            ServerListDialog.getBaseUrl(server), server.getConnectString());
                    String url = client.getUrl("api/missions?passwordProtected=true&defaultRole=true");
                    Log.d(TAG, "GET api/missions");
                    String json = client.get(url);
                    JSONObject root = new JSONObject(json);
                    JSONArray data = root.optJSONArray("data");
                    if (data != null)
                        for (int i = 0; i < data.length(); i++) {
                            JSONObject m = data.getJSONObject(i);
                            String name = m.optString("name", null);
                            if (FileSystemUtils.isEmpty(name))
                                continue;
                            names.add(name);
                            locked.add(m.optBoolean("passwordProtected", false));
                        }
                } catch (Exception e) {
                    Log.w(TAG, "feed list failed", e);
                    error = e.getMessage();
                }
                final String err = error;
                mapView.post(new Runnable() {
                    @Override
                    public void run() {
                        busy.dismiss();
                        if (err != null || names.isEmpty()) {
                            toast(plugin.getString(err != null ? R.string.feed_list_failed
                                    : R.string.feed_none));
                            return;
                        }
                        showFeeds(server, names, locked, then);
                    }
                });
            }
        }, TAG + "-list").start();
    }

    private void showFeeds(final TAKServer server, final List<String> names,
            final List<Boolean> locked, final OnFeed then) {
        String[] rows = new String[names.size()];
        for (int i = 0; i < rows.length; i++)
            rows[i] = locked.get(i) ? plugin.getString(R.string.feed_locked, names.get(i))
                    : names.get(i);
        new AlertDialog.Builder(host)
                .setTitle(plugin.getString(R.string.feed_pick, server.getDescription()))
                .setItems(rows, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (locked.get(which)) {
                            toast(plugin.getString(R.string.feed_locked_unsupported));
                            return;
                        }
                        Feed f = new Feed(server.getConnectString(), names.get(which));
                        prefs.edit().putString(PREF_LAST_FEED, f.name)
                                .putString(PREF_LAST_SERVER, f.server).apply();
                        then.go(f);
                    }
                })
                .setNegativeButton(plugin.getString(R.string.cancel), null)
                .show();
    }

    // ---- publishing ----------------------------------------------------------------

    /** Mark a shape as belonging to a feed. Publishing happens on its next save. */
    public void attach(DrawingShape shape, Feed feed) {
        if (feed == null)
            return;
        shape.setMetaString(META_FEED, feed.name);
        shape.setMetaString(META_FEED_SERVER, feed.server);
        // The feed name travels in the CoT detail and survives a restart; the server
        // and the published flag are local, so keep them in prefs by UID.
        prefs.edit().putString("fobs_feed_server_" + shape.getUID(), feed.server).apply();
    }

    /** The result of a split, join or lasso keeps the feed membership of its source. */
    public void inherit(MapItem from, DrawingShape to) {
        String feed = feedOf(from);
        String server = serverOf(from);
        if (feed == null || server == null)
            return;
        attach(to, new Feed(server, feed));
    }

    private String feedOf(MapItem item) {
        return item == null ? null : item.getMetaString(META_FEED, null);
    }

    private String serverOf(MapItem item) {
        if (item == null)
            return null;
        String server = item.getMetaString(META_FEED_SERVER, null);
        if (server == null)
            server = prefs.getString("fobs_feed_server_" + item.getUID(), null);
        return server;
    }

    private boolean published(MapItem item) {
        return item.getMetaBoolean(META_PUBLISHED, false)
                || prefs.getBoolean("fobs_feed_pub_" + item.getUID(), false);
    }

    public boolean isLive(MapItem item) {
        return item != null && feedOf(item) != null && serverOf(item) != null;
    }

    /**
     * Take a shape out of its feed, because FOBS consumed it (split, join, lasso) or
     * the operator asked. Never triggered by a bare map removal: a peer can make ATAK
     * remove an item with a forced-delete CoT, and that must not turn into a DELETE on
     * the server under this user's name (security review, 2026-09-05).
     */
    public void unpublish(MapItem item) {
        if (!isLive(item))
            return;
        String feed = feedOf(item);
        String server = serverOf(item);
        if (published(item))
            deleteContents(server, feed, item.getUID());
        prefs.edit().remove("fobs_feed_server_" + item.getUID())
                .remove("fobs_feed_pub_" + item.getUID()).apply();
        item.removeMetaData(META_FEED);
        item.removeMetaData(META_FEED_SERVER);
        item.removeMetaData(META_PUBLISHED);
    }

    /**
     * Called from {@link FobsShapes#persist} after every save. First time: CoT to the
     * server plus the UID into the mission's contents. After that: CoT only, which
     * the server records as a change to the mission's item.
     */
    public void onPersisted(final DrawingShape shape) {
        if (!isLive(shape) || shape.getGroup() == null)
            return;
        final String feed = feedOf(shape);
        final String server = serverOf(shape);
        final boolean first = !published(shape);
        CotEvent event = shape.toCot();
        if (event == null)
            return;
        // ATAK's own path for publishing a CoT to a mission on a streaming server;
        // commo adds the mission destination itself. dispatchToConnectString cannot
        // reach an SSL streaming server at all ("Unsupported endpoint protocol: ssl",
        // 2026-09-05). The key is the chosen server's connect string, so the track
        // goes to that server and no other (security review, 2026-09-05); an unknown
        // key sends nothing and logs "Invalid interface key".
        CommsMapComponent.getInstance().sendCoTToServersByMission(server, feed, event);
        Log.d(TAG, "sent " + shape.getUID() + " to its feed");
        if (!first)
            return;
        final String uid = shape.getUID();
        new Thread(new Runnable() {
            @Override
            public void run() {
                // The CoT went out on the streaming connection a moment ago; give the
                // server time to file it, and try a few times, before deciding.
                boolean ok = false;
                for (int attempt = 1; attempt <= 4 && !ok; attempt++) {
                    try {
                        Thread.sleep(attempt == 1 ? 2000 : 3000);
                    } catch (InterruptedException ignored) {
                        return;
                    }
                    Log.d(TAG, "server has CoT " + uid + ": " + serverHasCot(server, uid));
                    ok = putContents(server, feed, uid);
                }
                final boolean added = ok;
                final String msg = ok ? plugin.getString(R.string.feed_published, feed)
                        : plugin.getString(R.string.feed_publish_failed, feed);
                mapView.post(new Runnable() {
                    @Override
                    public void run() {
                        if (added) {
                            shape.setMetaBoolean(META_PUBLISHED, true);
                            prefs.edit().putBoolean("fobs_feed_pub_" + uid, true).apply();
                        }
                        toast(msg);
                    }
                });
            }
        }, TAG + "-put").start();
    }

    /** Diagnostic: does the server hold a CoT for this UID (GET api/cot/xml/{uid})? */
    int serverHasCot(String server, String uid) {
        try {
            TakHttpClient client = client(server);
            String url = client.getUrl("api/cot/xml/" + Uri.encode(uid));
            TakHttpResponse r = client.execute(new org.apache.http.client.methods.HttpGet(
                    FileSystemUtils.sanitizeURL(url)));
            return r.getStatusCode();
        } catch (Exception e) {
            Log.w(TAG, "cot check failed", e);
            return -1;
        }
    }

    private static String body(TakHttpResponse r) {
        try {
            if (r.getResponse() != null && r.getResponse().getEntity() != null)
                return org.apache.http.util.EntityUtils.toString(r.getResponse().getEntity());
        } catch (Exception ignored) {
        }
        return "";
    }

    private TakHttpClient client(String connectString) {
        String base = ServerListDialog.getBaseUrl(connectString);
        return TakHttpClient.GetHttpClient(base, connectString);
    }

    /**
     * PUT api/missions/{name}/subscription?uid=device. TAK Server records content
     * added by an unsubscribed UID nowhere and still answers 200 (measured on the
     * dev server, 2026-09-05); Data Sync subscribes before it publishes, so do we.
     * Idempotent: 201 the first time, 200 after.
     */
    boolean subscribe(String server, String feed) {
        try {
            TakHttpClient client = client(server);
            String url = client.getUrl("api/missions/" + Uri.encode(feed) + "/subscription");
            url = Uri.parse(url).buildUpon()
                    .appendQueryParameter("uid", MapView.getDeviceUid()).build().toString();
            HttpPut put = new HttpPut(FileSystemUtils.sanitizeURL(url));
            TakHttpResponse r = client.execute(put);
            Log.d(TAG, "PUT subscription -> " + r.getStatusCode());
            return r.isOk() || r.isCreated();
        } catch (Exception e) {
            Log.w(TAG, "subscribe failed", e);
            return false;
        }
    }

    /** PUT api/missions/{name}/contents with {"uids":[uid]}, as Data Sync does. */
    boolean putContents(String server, String feed, String uid) {
        if (!subscribe(server, feed))
            return false;
        try {
            TakHttpClient client = client(server);
            String url = client.getUrl("api/missions/" + Uri.encode(feed) + "/contents");
            url = Uri.parse(url).buildUpon()
                    .appendQueryParameter("creatorUid", MapView.getDeviceUid()).build().toString();
            HttpPut put = new HttpPut(FileSystemUtils.sanitizeURL(url));
            JSONObject body = new JSONObject();
            body.put("uids", new JSONArray().put(uid));
            StringEntity entity = new StringEntity(body.toString());
            entity.setContentType("application/json");
            put.setEntity(entity);
            put.addHeader("Content-Type", "application/json");
            TakHttpResponse r = client.execute(put);
            boolean ok = r.isOk() || r.isCreated();
            Log.d(TAG, "PUT contents -> " + r.getStatusCode() + (ok ? "" : " " + body(r)));
            return ok;
        } catch (Exception e) {
            Log.w(TAG, "add to feed failed", e);
            return false;
        }
    }

    /** DELETE api/missions/{name}/contents?uid=... */
    void deleteContents(final String server, final String feed, final String uid) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    TakHttpClient client = client(server);
                    String url = client.getUrl("api/missions/" + Uri.encode(feed) + "/contents");
                    url = Uri.parse(url).buildUpon()
                            .appendQueryParameter("uid", uid)
                            .appendQueryParameter("creatorUid", MapView.getDeviceUid())
                            .build().toString();
                    TakHttpResponse r = client.execute(new HttpDelete(FileSystemUtils.sanitizeURL(url)));
                    Log.d(TAG, "DELETE contents -> " + r.getStatusCode());
                } catch (Exception e) {
                    Log.w(TAG, "remove from feed failed", e);
                }
            }
        }, TAG + "-delete").start();
    }

    private void toast(String s) {
        Toast.makeText(host, s, Toast.LENGTH_SHORT).show();
    }
}

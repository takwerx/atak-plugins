package com.atakmap.android.mast.feed;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.widget.Toast;

import com.atakmap.android.importexport.CotEventFactory;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.mast.plugin.R;
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
 * Live to a Data Sync feed, copied forward from FOBS ({@code FOBS/feed/FeedPublisher},
 * see {@code memory: shared-helpers-are-copy-forward}) with two changes: the feed is
 * picked once in the pane header and remembered, never asked per item, because the
 * fleet shares nothing except through a feed (operator, 2026-09-18: "we do NOT use
 * broadcast. everything is done through a datasync feed"); and it publishes any
 * {@link MapItem}, marker or shape, through {@link CotEventFactory}.
 *
 * <p>Two channels, both ATAK's own: the CoT goes out on the server's streaming
 * connection with a mission destination, and the UID is added to the mission's
 * contents with the same REST call ATAK's Data Sync uses, over ATAK's HTTP client,
 * which carries the server's certificates and login. MAST never creates a feed and
 * never holds a feed password; password-protected feeds are listed and refused.
 */
public class FeedPublisher {

    private static final String TAG = "MAST.Feed";
    private static final String PREF_FEED = "mast_feed";
    private static final String PREF_SERVER = "mast_feed_server";
    /** Feed name, on the item and in its CoT detail so peers see which feed it is in. */
    public static final String META_FEED = "mast_feed";
    /** Server connect string, on the item only. */
    public static final String META_FEED_SERVER = "mast_feed_server";
    private static final String META_PUBLISHED = "mast_feed_published";

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
    }

    public void dispose() {
        servers.dispose();
    }

    // ---- choosing ------------------------------------------------------------------

    /** A feed choice: server plus mission name. */
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

    /** The remembered feed, or null when none was ever picked. */
    public Feed current() {
        String name = prefs.getString(PREF_FEED, null);
        String server = prefs.getString(PREF_SERVER, null);
        if (name == null || server == null)
            return null;
        return new Feed(server, name);
    }

    /** Is the remembered feed's server connected right now? */
    public boolean isCurrentConnected() {
        Feed f = current();
        return f != null && stillConnected(f.server, connectedServers());
    }

    public TAKServer[] connectedServers() {
        List<TAKServer> out = new ArrayList<TAKServer>();
        TAKServer[] all = servers.getServers();
        if (all != null)
            for (TAKServer s : all)
                if (s.isEnabled() && s.isConnected())
                    out.add(s);
        return out.toArray(new TAKServer[0]);
    }

    /** Pick a server (when there is more than one) and then a feed on it. */
    public void pick(final OnFeed then) {
        final TAKServer[] list = connectedServers();
        if (list.length == 0) {
            toast(plugin.getString(R.string.feed_no_server));
            return;
        }
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

    private static boolean stillConnected(String connectString, TAKServer[] list) {
        for (TAKServer s : list)
            if (connectString.equals(s.getConnectString()))
                return true;
        return false;
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
                final List<String> names = new ArrayList<String>();
                final List<Boolean> locked = new ArrayList<Boolean>();
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
                                    : R.string.feed_empty));
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
                        prefs.edit().putString(PREF_FEED, f.name)
                                .putString(PREF_SERVER, f.server).apply();
                        then.go(f);
                    }
                })
                .setNegativeButton(plugin.getString(R.string.cancel), null)
                .show();
    }

    // ---- publishing ----------------------------------------------------------------

    /** Mark an item as belonging to a feed. Publishing happens on its next save. */
    public void attach(MapItem item, Feed feed) {
        if (feed == null)
            return;
        item.setMetaString(META_FEED, feed.name);
        item.setMetaString(META_FEED_SERVER, feed.server);
        // The feed name travels in the CoT detail and survives a restart; the server
        // and the published flag are local, so keep them in prefs by UID.
        prefs.edit().putString("mast_feed_server_" + item.getUID(), feed.server).apply();
    }

    private String feedOf(MapItem item) {
        return item == null ? null : item.getMetaString(META_FEED, null);
    }

    private String serverOf(MapItem item) {
        if (item == null)
            return null;
        String server = item.getMetaString(META_FEED_SERVER, null);
        if (server == null)
            server = prefs.getString("mast_feed_server_" + item.getUID(), null);
        return server;
    }

    private boolean published(MapItem item) {
        return item.getMetaBoolean(META_PUBLISHED, false)
                || prefs.getBoolean("mast_feed_pub_" + item.getUID(), false);
    }

    public boolean isLive(MapItem item) {
        return item != null && feedOf(item) != null && serverOf(item) != null;
    }

    /**
     * Take an item out of its feed because the operator asked. Never triggered by a
     * bare map removal: a peer can make ATAK remove an item with a forced-delete CoT,
     * and that must not turn into a DELETE on the server under this user's name
     * (FOBS security review, 2026-09-05).
     */
    public void unpublish(MapItem item) {
        if (!isLive(item))
            return;
        String feed = feedOf(item);
        String server = serverOf(item);
        if (published(item))
            deleteContents(server, feed, item.getUID());
        prefs.edit().remove("mast_feed_server_" + item.getUID())
                .remove("mast_feed_pub_" + item.getUID()).apply();
        item.removeMetaData(META_FEED);
        item.removeMetaData(META_FEED_SERVER);
        item.removeMetaData(META_PUBLISHED);
    }

    /**
     * After every save of an attached item. First time: CoT to the server plus the UID
     * into the mission's contents. After that: CoT only, which the server records as a
     * change to the mission's item.
     */
    public void onPersisted(final MapItem item) {
        if (!isLive(item) || item.getGroup() == null)
            return;
        final String feed = feedOf(item);
        final String server = serverOf(item);
        final boolean first = !published(item);
        CotEvent event = CotEventFactory.createCotEvent(item);
        if (event == null)
            return;
        // ATAK's own path for publishing a CoT to a mission on a streaming server;
        // commo adds the mission destination itself. The key is the chosen server's
        // connect string, so the item goes to that server and no other.
        CommsMapComponent.getInstance().sendCoTToServersByMission(server, feed, event);
        Log.d(TAG, "sent " + item.getUID() + " to feed " + feed);
        if (!first)
            return;
        final String uid = item.getUID();
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
                    ok = putContents(server, feed, uid);
                }
                final boolean added = ok;
                final String msg = ok ? plugin.getString(R.string.feed_published, feed)
                        : plugin.getString(R.string.feed_publish_failed, feed);
                mapView.post(new Runnable() {
                    @Override
                    public void run() {
                        if (added) {
                            item.setMetaBoolean(META_PUBLISHED, true);
                            prefs.edit().putBoolean("mast_feed_pub_" + uid, true).apply();
                        }
                        toast(msg);
                    }
                });
            }
        }, TAG + "-put").start();
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

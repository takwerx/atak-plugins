
package com.atakmap.android.signaldf.net;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;

import com.atakmap.android.maps.MapView;
import com.atakmap.android.signaldf.map.BearingLayer;
import com.atakmap.android.signaldf.model.ArrayHeading;
import com.atakmap.android.signaldf.model.Bearing;
import com.atakmap.android.signaldf.model.FeedFrame;
import com.atakmap.android.util.ServerListDialog;
import com.atakmap.comms.CommsMapComponent;
import com.atakmap.comms.CotStreamListener;
import com.atakmap.comms.TAKServer;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoCalculations;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.coremap.maps.time.CoordinatedTime;
import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.cot.event.CotPoint;
import com.atakmap.comms.http.TakHttpClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Puts live bearings on a TAK Server feed, so people who are not holding the
 * radio can see what it is pointing at.
 *
 * <p><b>Live, not stored.</b> This is FOBS's feed publisher with one call
 * deliberately left out. FOBS does two things when a track joins a feed: it
 * sends the CoT with a {@code <marti><dest mission="..."/></marti>} detail, and
 * it adds the UID to the mission's contents with
 * {@code PUT /Marti/api/missions/{name}/contents}. The second call is what
 * makes a track permanent -- FOBS's own words are "it is a feed item and it
 * stays" -- and that is right for a survey polygon, which is a finished
 * product somebody will want tomorrow.
 *
 * <p>A bearing is not a product. It is true for a few seconds and then it is
 * noise. So this sends the addressed CoT and stops: the feed's subscribers see
 * it now, it ages off their maps on its own when the stale time passes, and
 * nothing accumulates in Data Sync. Same plumbing, one call shorter.
 *
 * <p><b>Why a feed and not a channel.</b> The operator asked for a channel, and
 * a CoT event cannot name one. ATAK's CoT layer builds exactly three kinds of
 * destination -- {@code uid}, {@code callsign} and {@code mission} (commoncommo
 * {@code CoTMessage::setTAKServerRecipients} and
 * {@code setTAKServerMissionRecipient}) -- and server groups are decided by who
 * the sender is, not by what a message asks for. A mission is the only
 * per-message address there is, and it behaves like a channel from the
 * operator's side: people who subscribe see it, people who do not, do not.
 * Better, in fact, because it is opt-in per feed rather than falling out of
 * group membership.
 *
 * <p>The other route considered was a raw TCP send to a dedicated TAK Server
 * input port mapped to a channel, via
 * {@code CommsMapComponent.sendCoTToEndpoint}. Three things ruled it out: it
 * is plaintext with no client certificate, so anything that can reach the port
 * can inject onto that channel; the message arrives as a connection the server
 * cannot tie back to this client, so the operator would receive their own
 * bearings back; and FOBS already recorded that the endpoint path "cannot
 * reach an SSL streaming server at all" anyway.
 *
 * <p><b>Plugin-lifetime, not pane-lifetime.</b> Transmitting has to outlive the
 * tap that starts it. The operator switches base maps, opens another tool,
 * presses Back, and the bearings must keep going out; FOBS 0.4 went to the
 * field with a GPS recording inside a Tool and switching base maps ended the
 * walk. So this listens to {@link KrakenLink} itself rather than being driven
 * by the pane, and the pane only changes its settings.
 */
public final class BearingPublisher implements KrakenLink.Listener {

    private static final String TAG = "SignalDF.Pub";

    private static final String PREF_FEED = "signaldf.share_feed";
    private static final String PREF_SERVER = "signaldf.share_server";
    private static final String PREF_STALE = "signaldf.share_stale_s";
    private static final String PREF_ON = "signaldf.share_on";

    /**
     * How long a shared bearing is worth looking at, in seconds.
     *
     * <p>Half a minute. A bearing is a measurement of where a transmitter was
     * when the radio heard it, and on a vehicle the receiver has moved by the
     * time anybody reads it. Long enough that a gap in the link does not make
     * the line flicker off somebody else's map, short enough that a line left
     * on screen is never more than half a minute wrong.
     */
    public static final int DEFAULT_STALE_S = 30;

    /** The stale times offered. Nothing here is longer than a traffic light. */
    public static final int[] STALE_CHOICES_S = { 15, 30, 60, 120, 300 };

    /**
     * The shortest gap between two transmissions, milliseconds.
     *
     * <p>The radio reports about once a second and the bearing moves by a
     * degree or two between frames. Sending every frame would put sixty
     * events a minute per emitter onto a feed that other people's phones have
     * to parse, for a picture that is no better. Three seconds is ten times
     * inside the stale window, so a subscriber's line never lapses between
     * updates even if two in a row are lost.
     */
    private static final long MIN_SEND_INTERVAL_MS = 3000;

    /**
     * How long a shared bearing line is drawn, in meters.
     *
     * <p>Fixed, unlike the line on the operator's own map, which scales with
     * their zoom so it stays a usable length whatever they are looking at.
     * Scaling a shared line would mean everyone else's picture changing
     * whenever the sender pinches, which reads as the bearing changing. The
     * length carries no information either way -- a bearing is a direction,
     * not a distance -- so it is stated in the line's own remarks.
     */
    private static final double SHARED_LENGTH_M = 15000.0;

    private final MapView mapView;
    private final Context host;
    private final CotStreamListener servers;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private long lastSendMs;
    private int sent;

    private static BearingPublisher instance;

    /**
     * The one publisher, or null before plugin start and after plugin stop.
     * Same shape as {@link KrakenLink#get()}: the pane reaches it this way
     * rather than holding a reference that would outlive a plugin stop.
     */
    public static BearingPublisher get() {
        return instance;
    }

    public BearingPublisher(MapView mapView) {
        this.mapView = mapView;
        this.host = mapView.getContext();
        this.servers = new CotStreamListener(host, TAG, null);
        this.prefs = PreferenceManager.getDefaultSharedPreferences(host);
        instance = this;
    }

    public void dispose() {
        servers.dispose();
        handler.removeCallbacksAndMessages(null);
        if (instance == this)
            instance = null;
    }

    // ---- settings ----------------------------------------------------------

    public boolean isOn() {
        return prefs.getBoolean(PREF_ON, false) && feed() != null;
    }

    /** Turn transmitting on or off. Off is the state a plugin starts in. */
    public void setOn(boolean on) {
        prefs.edit().putBoolean(PREF_ON, on).apply();
        if (!on)
            sent = 0;
    }

    public String feed() {
        String f = prefs.getString(PREF_FEED, null);
        return FileSystemUtils.isEmpty(f) ? null : f;
    }

    public String server() {
        return prefs.getString(PREF_SERVER, null);
    }

    public int staleSeconds() {
        return prefs.getInt(PREF_STALE, DEFAULT_STALE_S);
    }

    public void setStaleSeconds(int s) {
        prefs.edit().putInt(PREF_STALE, s).apply();
    }

    /** How many bearings have gone out since transmitting was turned on. */
    public int sentCount() {
        return sent;
    }

    /** Stop sharing without forgetting which feed was chosen. */
    public void clearFeed() {
        prefs.edit().remove(PREF_FEED).remove(PREF_SERVER)
                .putBoolean(PREF_ON, false).apply();
    }

    // ---- choosing a feed ---------------------------------------------------

    public interface OnChosen {
        void chosen(String server, String feed);
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
     * Pick a server, then a feed on it. Copied from FOBS, which read it off
     * Fire Area Survey's APK because Data Sync is not in the SDK.
     */
    public void choose(final OnChosen then) {
        final TAKServer[] list = connectedServers();
        if (list.length == 0) {
            toast("no TAK server connected -- connect one first");
            return;
        }
        if (list.length == 1) {
            listFeeds(list[0], then);
            return;
        }
        ServerListDialog.selectServer(host, "Share bearings to", list,
                new ServerListDialog.Callback() {
                    @Override
                    public void onSelected(TAKServer server) {
                        if (server != null)
                            listFeeds(server, then);
                    }
                });
    }

    /** GET api/missions, the same list Data Sync shows. */
    private void listFeeds(final TAKServer server, final OnChosen then) {
        final android.app.ProgressDialog busy = new android.app.ProgressDialog(host);
        busy.setMessage("Looking for feeds...");
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
                            ServerListDialog.getBaseUrl(server),
                            server.getConnectString());
                    String url = client.getUrl(
                            "api/missions?passwordProtected=true&defaultRole=true");
                    JSONObject root = new JSONObject(client.get(url));
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
                        if (err != null) {
                            toast("could not read the feed list from that server");
                            return;
                        }
                        if (names.isEmpty()) {
                            toast("that server has no feeds yet -- make one in Data Sync");
                            return;
                        }
                        showFeeds(server, names, locked, then);
                    }
                });
            }
        }, TAG + "-list").start();
    }

    private void showFeeds(final TAKServer server, final List<String> names,
            final List<Boolean> locked, final OnChosen then) {
        final String[] rows = new String[names.size()];
        for (int i = 0; i < rows.length; i++)
            rows[i] = locked.get(i) ? names.get(i) + "  (password)" : names.get(i);
        new AlertDialog.Builder(host)
                .setTitle("Feed on " + ServerListDialog.getBaseUrl(server))
                .setItems(rows, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (locked.get(which)) {
                            toast("password-protected feeds are not supported yet");
                            return;
                        }
                        String name = names.get(which);
                        prefs.edit().putString(PREF_FEED, name)
                                .putString(PREF_SERVER, server.getConnectString())
                                .apply();
                        then.chosen(server.getConnectString(), name);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---- transmitting ------------------------------------------------------

    @Override
    public void onLinkChanged() {
        // Nothing to send on a state change; bearings drive this.
    }

    @Override
    public void onBearings(FeedFrame frame) {
        if (!isOn() || frame == null || frame.bearings == null
                || frame.bearings.isEmpty())
            return;

        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastSendMs < MIN_SEND_INTERVAL_MS)
            return;
        lastSendMs = now;

        final String feed = feed();
        final String server = server();
        if (feed == null || server == null)
            return;

        // The same two rules the map draws by, so a subscriber's line and the
        // operator's own line are the same line. Heading first, because a
        // bearing with no heading behind it is relative to an antenna and must
        // never be published as if it were a compass bearing.
        ArrayHeading heading = ArrayHeading.resolve(operatorHeading(),
                frame.bearings.get(0));
        if (!heading.isKnown())
            return;

        GeoPoint fallback = BearingLayer.selfPoint(mapView);
        int staleS = staleSeconds();
        for (Bearing b : frame.bearings) {
            GeoPoint from = BearingLayer.receiverPoint(b, fallback);
            if (from == null)
                continue;
            double trueDeg = b.trueBearing(heading.degrees());
            GeoPoint to = GeoCalculations.pointAtDistance(from, trueDeg,
                    SHARED_LENGTH_M);
            if (to == null)
                continue;
            CotEvent event = lineEvent(b, from, to, trueDeg, staleS, heading);
            if (event == null || !event.isValid())
                continue;
            // ATAK's own path for a CoT addressed to a mission on a streaming
            // server; commo adds the <marti><dest mission=".."/> itself. The
            // key is the chosen server's connect string, so it goes to that
            // server and no other.
            CommsMapComponent.getInstance()
                    .sendCoTToServersByMission(server, feed, event);
            sent++;
        }
    }

    /** The operator's own heading override, or null for "use the radio's". */
    private Double operatorHeading() {
        String s = prefs.getString("signaldf.array_heading", null);
        if (FileSystemUtils.isEmpty(s))
            return null;
        try {
            return Double.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * One bearing as a CoT line.
     *
     * <p>A {@code u-d-f} drawing shape, which is what ATAK's own drawing tools
     * emit, so a teammate sees a line whether or not they have this plugin.
     * That is the same reasoning as IWI's CASEVAC: a message only somebody
     * with the plugin can read is not shared, it is hidden.
     *
     * <p>The UID is the one the plugin draws with locally. If the server does
     * turn out to send the operator their own bearings back, ATAK resolves an
     * incoming event against the whole root group by UID
     * ({@code MapItemImporter.findItem} calls {@code deepFindUID}), so the echo
     * lands on the existing line instead of stacking a second one beside it.
     */
    private CotEvent lineEvent(Bearing b, GeoPoint from, GeoPoint to,
            double trueDeg, int staleS, ArrayHeading heading) {
        CoordinatedTime now = new CoordinatedTime();
        CotEvent e = new CotEvent();
        e.setUID(BearingLayer.UID_PREFIX + b.vfo);
        e.setType("u-d-f");
        e.setHow("m-g");
        e.setVersion("2.0");
        e.setTime(now);
        e.setStart(now);
        e.setStale(now.addSeconds(staleS));
        e.setPoint(new CotPoint(from.getLatitude(), from.getLongitude(),
                CotPoint.UNKNOWN, CotPoint.UNKNOWN, CotPoint.UNKNOWN));

        CotDetail d = new CotDetail("detail");

        CotDetail a = new CotDetail("link");
        a.setAttribute("point", from.getLatitude() + "," + from.getLongitude());
        d.addChild(a);
        CotDetail z = new CotDetail("link");
        z.setAttribute("point", to.getLatitude() + "," + to.getLongitude());
        d.addChild(z);

        CotDetail stroke = new CotDetail("strokeColor");
        stroke.setAttribute("value", String.valueOf(SHARED_COLOR));
        d.addChild(stroke);
        CotDetail weight = new CotDetail("strokeWeight");
        weight.setAttribute("value", "3.0");
        d.addChild(weight);
        CotDetail labels = new CotDetail("labels_on");
        labels.setAttribute("value", "true");
        d.addChild(labels);

        CotDetail contact = new CotDetail("contact");
        contact.setAttribute("callsign", String.format(Locale.US,
                "%.4f MHz  %.0f deg", b.frequencyMHz(), trueDeg));
        d.addChild(contact);

        // Says what the line is and, deliberately, what its length is not.
        CotDetail remarks = new CotDetail("remarks");
        remarks.setInnerText(String.format(Locale.US,
                "Signal DF bearing %.0f degrees true at %.4f MHz. "
                        + "Direction only -- the line's length is fixed and "
                        + "says nothing about range. %s",
                trueDeg, b.frequencyMHz(), heading.provenance()));
        d.addChild(remarks);

        e.setDetail(d);
        return e;
    }

    /** Magenta: not a color ATAK's own drawing tools reach for by default. */
    private static final int SHARED_COLOR = 0xFFFF00FF;

    private void toast(final String msg) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                android.widget.Toast.makeText(host, msg,
                        android.widget.Toast.LENGTH_LONG).show();
            }
        });
    }
}

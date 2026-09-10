package com.atakmap.android.comms.model;

import com.atakmap.coremap.log.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code catalog.json}: the radio sites and the nets on them.
 *
 * <p>Two tables joined by net designator. A {@link Net} is a channel in the plan
 * (FIRESCOPE MACS 441-1): designator, name, RX/TX as programmed in a mobile, default
 * tones. A {@link Site} is where. A {@link Channel} is one net on one site: callsign,
 * the tone used at that site, notes. A channel never restates a frequency unless the
 * source disagrees with the plan, and then it says so in its notes.
 *
 * <p>Built by {@code tools/build_comms_catalog.py}; read from the depot host with the
 * copy in the APK as the fallback. The plugin assumes nothing about what is in it:
 * states, agencies and nets are whatever the catalog holds.
 */
public final class Catalog {

    private static final String TAG = "CommsCatalog";

    /** The format this build understands. A newer catalog is refused, not guessed at. */
    public static final int SUPPORTED_FORMAT = 1;

    /** One channel of the plan. Frequencies are strings as printed ("151.3175"). */
    public static final class Net {
        public final String id, name, agency, rx, tx, band, power, mode, config, usage, remarks;
        /** Hz, or NaN for carrier squelch. */
        public final double rxTone, txTone;
        /** "OST" or a NAC when the tone is not a fixed CTCSS; empty otherwise. */
        public final String rxToneText, txToneText;
        public final boolean portable;
        /** Sites carrying this net, filled in after parsing. */
        public final List<Site> sites = new ArrayList<>();

        Net(JSONObject o) throws JSONException {
            id = o.getString("id");
            name = o.optString("name", id);
            agency = o.optString("agency", "");
            rx = o.optString("rx", "");
            tx = o.optString("tx", "");
            rxTone = toneHz(o.opt("rx_tone"));
            txTone = toneHz(o.opt("tx_tone"));
            rxToneText = toneText(o.opt("rx_tone"));
            txToneText = toneText(o.opt("tx_tone"));
            band = o.optString("band", "");
            power = o.optString("power", "");
            mode = o.optString("mode", "");
            config = o.optString("config", "");
            usage = o.optString("usage", "");
            remarks = o.optString("remarks", "");
            portable = o.optBoolean("portable", false);
        }

        public boolean simplex() {
            return rx.equals(tx);
        }

        /** "151.3175 / 159.3525", or the one frequency for a simplex channel. */
        public String pair() {
            return simplex() ? rx : rx + " / " + tx;
        }
    }

    public static final class Site {
        public final String id, name, st, county;
        public final double lat, lon;
        /** Ground elevation in meters, or NaN. */
        public final double elevM;
        /** Antenna height above ground in meters, or NaN when the catalog does not say. */
        public final double antM;
        public final List<String> managers = new ArrayList<>();
        public final List<String> agencies = new ArrayList<>();
        public final List<String> sources = new ArrayList<>();
        /** The nets on this site, filled in after parsing. */
        public final List<Channel> channels = new ArrayList<>();

        Site(JSONObject o) throws JSONException {
            id = o.getString("id");
            name = o.optString("name", id);
            st = o.optString("st", "").toUpperCase(Locale.US);
            county = o.optString("county", "");
            lat = o.getDouble("lat");
            lon = o.getDouble("lon");
            elevM = o.isNull("elev_m") ? Double.NaN : o.optDouble("elev_m", Double.NaN);
            antM = o.isNull("ant_m") ? Double.NaN : o.optDouble("ant_m", Double.NaN);
            fill(o.optJSONArray("managers"), managers);
            fill(o.optJSONArray("agencies"), agencies);
            fill(o.optJSONArray("sources"), sources);
        }

        public boolean hasNets() {
            return !channels.isEmpty();
        }
    }

    /** One net on one site. */
    public static final class Channel {
        public final String siteId, netId, callsign, notes, source, asOf;
        /** The tone used at this site, Hz or NaN; the number is the CA standard tone number or 0. */
        public final double txTone, rxTone;
        public final int txToneNum, rxToneNum;
        /** A frequency the source gives for this site that differs from the plan; empty otherwise. */
        public final String rxOverride, txOverride;
        public Net net;
        public Site site;

        Channel(JSONObject o) throws JSONException {
            siteId = o.getString("site");
            netId = o.getString("net");
            callsign = o.optString("callsign", "");
            notes = o.optString("notes", "");
            source = o.optString("source", "");
            asOf = o.optString("as_of", "");
            txTone = o.isNull("tx_tone") ? Double.NaN : o.optDouble("tx_tone", Double.NaN);
            rxTone = o.isNull("rx_tone") ? Double.NaN : o.optDouble("rx_tone", Double.NaN);
            txToneNum = o.optInt("tx_tone_num", 0);
            rxToneNum = o.optInt("rx_tone_num", 0);
            rxOverride = o.optString("rx", "");
            txOverride = o.optString("tx", "");
        }

        /** What you listen on at this site: the override when the source disagrees with the plan. */
        public String rx() {
            return rxOverride.isEmpty() ? net.rx : rxOverride;
        }

        public String tx() {
            return txOverride.isEmpty() ? net.tx : txOverride;
        }

        public String pair() {
            return rx().equals(tx()) ? rx() : rx() + " / " + tx();
        }

        /** The tone the mobile transmits to reach this site: the site's own, else the plan's. */
        public double effectiveTxTone() {
            return Double.isNaN(txTone) ? net.txTone : txTone;
        }

        public double effectiveRxTone() {
            return Double.isNaN(rxTone) ? net.rxTone : rxTone;
        }
    }

    public final int format;
    public final String generated;
    /** Tone number to Hz, the California standard 32. */
    public final Map<Integer, Double> tones = new HashMap<>();
    public final List<Net> nets = new ArrayList<>();
    public final List<Site> sites = new ArrayList<>();
    public final List<Channel> channels = new ArrayList<>();
    private final Map<String, Net> netById = new HashMap<>();
    private final Map<String, Site> siteById = new HashMap<>();

    public Catalog(JSONObject o) throws JSONException {
        format = o.optInt("format", 0);
        generated = o.optString("generated", "");
        final JSONObject t = o.optJSONObject("tones");
        if (t != null) {
            final Iterator<String> it = t.keys();
            while (it.hasNext()) {
                final String k = it.next();
                try {
                    tones.put(Integer.parseInt(k), t.getDouble(k));
                } catch (NumberFormatException | JSONException ignored) {
                }
            }
        }
        JSONArray arr = o.optJSONArray("nets");
        for (int i = 0; arr != null && i < arr.length(); i++) {
            try {
                final Net n = new Net(arr.getJSONObject(i));
                if (!netById.containsKey(n.id)) {
                    nets.add(n);
                    netById.put(n.id, n);
                }
            } catch (JSONException e) {
                Log.w(TAG, "net skipped: " + e.getMessage());
            }
        }
        arr = o.optJSONArray("sites");
        for (int i = 0; arr != null && i < arr.length(); i++) {
            try {
                final Site s = new Site(arr.getJSONObject(i));
                if (!siteById.containsKey(s.id)) {
                    sites.add(s);
                    siteById.put(s.id, s);
                }
            } catch (JSONException e) {
                Log.w(TAG, "site skipped: " + e.getMessage());
            }
        }
        arr = o.optJSONArray("channels");
        for (int i = 0; arr != null && i < arr.length(); i++) {
            try {
                final Channel c = new Channel(arr.getJSONObject(i));
                c.net = netById.get(c.netId);
                c.site = siteById.get(c.siteId);
                if (c.net == null || c.site == null) {
                    // One bad row costs that row, not the catalog.
                    Log.w(TAG, "channel skipped: " + c.netId + " on " + c.siteId);
                    continue;
                }
                channels.add(c);
                c.site.channels.add(c);
                if (!c.net.sites.contains(c.site))
                    c.net.sites.add(c.site);
            } catch (JSONException e) {
                Log.w(TAG, "channel skipped: " + e.getMessage());
            }
        }
    }

    public Net net(String id) {
        return netById.get(id);
    }

    public Site site(String id) {
        return siteById.get(id);
    }

    /** Every state code in the catalog, sorted. */
    public List<String> states() {
        final List<String> out = new ArrayList<>();
        for (Site s : sites)
            if (!s.st.isEmpty() && !out.contains(s.st))
                out.add(s.st);
        java.util.Collections.sort(out);
        return out;
    }

    private static double toneHz(Object v) {
        if (v instanceof Number)
            return ((Number) v).doubleValue();
        return Double.NaN;
    }

    private static String toneText(Object v) {
        return v instanceof String ? (String) v : "";
    }

    private static void fill(JSONArray a, List<String> into) {
        for (int i = 0; a != null && i < a.length(); i++) {
            final String s = a.optString(i, "");
            if (!s.isEmpty())
                into.add(s);
        }
    }
}

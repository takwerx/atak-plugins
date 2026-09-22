package com.atakmap.android.atmosphere.data;

import com.atakmap.android.atmosphere.model.Snapshot;
import com.atakmap.android.atmosphere.net.EgressPolicy;
import com.atakmap.android.atmosphere.net.Http;
import com.atakmap.android.atmosphere.source.JsonPath;
import com.atakmap.android.atmosphere.source.WxParam;
import com.atakmap.android.atmosphere.source.WxSourceDef;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/**
 * Fetches a {@link Snapshot} for a point: cache first, network only when allowed.
 *
 * <p>Order of operations, and the reasoning behind it:
 *
 * <ol>
 *   <li><b>Fresh cache wins outright.</b> Repeatedly opening the pane must not hammer a
 *       free provider.</li>
 *   <li><b>Egress is checked before any request is built</b>, so a disabled source cannot
 *       leak a position through a resolve step either.</li>
 *   <li><b>A network failure falls back to stale cache</b> with its age, rather than an
 *       empty screen. Old weather that says it is old still helps; nothing does not.</li>
 * </ol>
 */
public final class WeatherClient {

    private static final String TAG = "WxClient";

    /** How long a forecast response is considered current. */
    public static final long FRESH_MS = 15 * 60 * 1000L;
    /** A resolve step (point to gridpoint URL) changes far more slowly than the data. */
    private static final long RESOLVE_FRESH_MS = 7 * 24 * 60 * 60 * 1000L;

    public interface Listener {
        /**
         * @param fromCache true when nothing was requested — the values are what the
         *                  device already had
         */
        void onSnapshot(Snapshot snapshot, boolean fromCache);

        /**
         * @param stale the best cached snapshot, or null if there is none — the UI shows
         *              it with its age alongside the error
         */
        void onError(String message, Snapshot stale);
    }

    private final EgressPolicy egress;
    private final SnapshotStore store;

    public WeatherClient(EgressPolicy egress, SnapshotStore store) {
        this.egress = egress;
        this.store = store;
    }

    /**
     * @param force skip the freshness check and re-request (the operator pulled to
     *              refresh); the egress rules still apply
     */
    public void fetch(final WxSourceDef def, final GeoPoint point, final boolean force,
            final Listener listener) {

        if (def == null || point == null) {
            listener.onError("no source or position", null);
            return;
        }

        final String lat = egress.latitude(point);
        final String lon = egress.longitude(point);
        final String cacheKey = SnapshotStore.key(def.id, lat, lon);
        final long now = System.currentTimeMillis();

        final SnapshotStore.Entry cached = store.read(cacheKey);
        if (cached != null && !force && cached.ageMillis(now) < FRESH_MS) {
            final Snapshot snapshot = parse(def, cached, listener);
            if (snapshot != null)
                listener.onSnapshot(snapshot, true);
            return;
        }

        final String refusal = egress.refuse(def);
        if (refusal != null) {
            listener.onError(refusal, cached == null ? null : parse(def, cached, null));
            return;
        }

        if (def.hasResolveStep())
            resolveThenFetch(def, point, lat, lon, cacheKey, cached, listener);
        else
            request(def, buildUrl(def, def.requestUrl, lat, lon), lat, lon, cacheKey,
                    cached, listener);
    }

    /**
     * Two-step providers (NWS): a point lookup returns the URL that actually serves the
     * forecast for that grid cell. The resolved URL is cached hard — it is stable for
     * weeks, and re-resolving on every refresh doubles the traffic for nothing.
     */
    private void resolveThenFetch(final WxSourceDef def, final GeoPoint point,
            final String lat, final String lon, final String cacheKey,
            final SnapshotStore.Entry cached, final Listener listener) {

        final String resolveKey = SnapshotStore.key(def.id + "-resolve", lat, lon);
        final SnapshotStore.Entry resolved = store.read(resolveKey);
        final long now = System.currentTimeMillis();

        if (resolved != null && resolved.ageMillis(now) < RESOLVE_FRESH_MS
                && !resolved.body.isEmpty()) {
            request(def, resolved.body, lat, lon, cacheKey, cached, listener);
            return;
        }

        Http.get(buildUrl(def, def.resolveUrl, lat, lon), egress.userAgent(), def.headers,
                new Http.Callback() {
                    @Override
                    public void onSuccess(String body) {
                        String dataUrl = null;
                        try {
                            dataUrl = JsonPath.getString(new JSONObject(body),
                                    def.resolvePath);
                        } catch (JSONException e) {
                            Log.w(TAG, def.id + ": resolve response was not JSON", e);
                        }
                        if (dataUrl == null || !dataUrl.startsWith("https://")) {
                            listener.onError(def.displayName
                                    + ": provider did not return a usable forecast URL",
                                    cached == null ? null : parse(def, cached, null));
                            return;
                        }
                        store.write(resolveKey, dataUrl, System.currentTimeMillis(),
                                Double.NaN, Double.NaN);
                        request(def, dataUrl, lat, lon, cacheKey, cached, listener);
                    }

                    @Override
                    public void onFailure(String error) {
                        listener.onError(def.displayName + ": " + error,
                                cached == null ? null : parse(def, cached, null));
                    }
                });
    }

    private void request(final WxSourceDef def, final String url, final String lat,
            final String lon, final String cacheKey, final SnapshotStore.Entry cached,
            final Listener listener) {

        Http.get(url, egress.userAgent(), def.headers, new Http.Callback() {
            @Override
            public void onSuccess(String body) {
                final long fetchedAt = System.currentTimeMillis();
                final double latitude = Double.parseDouble(lat);
                final double longitude = Double.parseDouble(lon);
                Snapshot snapshot;
                try {
                    snapshot = ResponseMapper.map(def, body, latitude, longitude, fetchedAt);
                } catch (JSONException e) {
                    Log.w(TAG, def.id + ": response did not match the source definition", e);
                    listener.onError(def.displayName
                            + ": response did not match its source definition",
                            cached == null ? null : parse(def, cached, null));
                    return;
                }
                store.write(cacheKey, body, fetchedAt, latitude, longitude);
                listener.onSnapshot(snapshot, false);
            }

            @Override
            public void onFailure(String error) {
                listener.onError(def.displayName + ": " + error,
                        cached == null ? null : parse(def, cached, null));
            }
        });
    }

    /** Fill {lat} {lon} {group:NAME} {apiKey} in a URL template. */
    String buildUrl(WxSourceDef def, String template, String lat, String lon) {
        String url = template.replace("{lat}", lat).replace("{lon}", lon);

        int open;
        while ((open = url.indexOf("{group:")) >= 0) {
            final int close = url.indexOf('}', open);
            if (close < 0)
                break;
            final String group = url.substring(open + "{group:".length(), close);
            url = url.substring(0, open) + groupKeys(def, group) + url.substring(close + 1);
        }

        // No key storage in this build; the parser refuses to enable such a source, so
        // this only ever removes a leftover placeholder.
        return url.replace("{apiKey}", "");
    }

    /** The selected parameter keys in a request group, comma-joined as APIs expect. */
    private static String groupKeys(WxSourceDef def, String group) {
        final List<WxParam> selected = ParamSelection.selected(def);
        final StringBuilder sb = new StringBuilder();
        for (WxParam p : selected) {
            if (!p.inGroup(group))
                continue;
            if (sb.length() > 0)
                sb.append(',');
            sb.append(p.key);
        }
        return sb.toString();
    }

    /** Re-read a cached body; null (and a reported error) when it no longer parses. */
    private Snapshot parse(WxSourceDef def, SnapshotStore.Entry entry, Listener listener) {
        try {
            return ResponseMapper.map(def, entry.body, entry.latitude, entry.longitude,
                    entry.fetchedAt);
        } catch (JSONException e) {
            Log.w(TAG, def.id + ": cached response no longer parses", e);
            if (listener != null)
                listener.onError(def.displayName + ": cached response could not be read",
                        null);
            return null;
        }
    }
}

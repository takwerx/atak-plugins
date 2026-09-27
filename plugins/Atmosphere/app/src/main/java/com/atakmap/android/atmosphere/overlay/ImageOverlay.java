package com.atakmap.android.atmosphere.overlay;

import android.content.SharedPreferences;
import android.graphics.Bitmap;

import com.atakmap.android.atmosphere.compat.MapCompat;
import com.atakmap.android.atmosphere.net.EgressPolicy;
import com.atakmap.android.atmosphere.net.Http;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoBounds;
import com.atakmap.map.AtakMapView;

import java.util.Locale;

/**
 * One picture of the map's neighborhood from a map server, drawn on the map
 * surface: the view padded to twice its span, capped, asked for at up to 1024
 * pixels on the long side in plain lon/lat so it lands on a quad, and asked
 * again when the view leaves that box, zooms well in or out of it, or the
 * picture is half an hour old. The subclass names the request and the words.
 * Used for the snow analysis and the sea surface temperature; the radar, wind
 * and smoke layers are time-scrubbed and keep their own.
 */
public abstract class ImageOverlay {

    private static final long POLL_MS = 30 * 60 * 1000L;
    private static final long MOVE_SETTLE_MS = 700L;
    private static final int MAX_PX = 1024;

    public interface Listener {
        void onStatus(String status);
    }

    protected final MapView mapView;
    protected final EgressPolicy egress;
    private final RasterLayer layer;
    private final String tag, layerId, prefOn;
    private Listener listener;
    private boolean started, on;
    private int generation;
    private GeoBounds region;
    private long fetchedAt;
    private String pendingKey;

    private final Runnable moveSettled = new Runnable() {
        @Override
        public void run() {
            if (on)
                ensureRegion(false);
        }
    };

    private final AtakMapView.OnMapMovedListener moved = new AtakMapView.OnMapMovedListener() {
        @Override
        public void onMapMoved(AtakMapView v, boolean animate) {
            // GL thread: post and coalesce. The picture is on the map surface and
            // moves with the map by itself.
            mapView.removeCallbacks(moveSettled);
            mapView.postDelayed(moveSettled, MOVE_SETTLE_MS);
        }
    };

    private final Runnable autoPoll = new Runnable() {
        @Override
        public void run() {
            if (!on || !started)
                return;
            ensureRegion(true);
            mapView.postDelayed(this, POLL_MS);
        }
    };

    protected ImageOverlay(MapView mapView, EgressPolicy egress, String tag, String layerId,
            String name) {
        this.mapView = mapView;
        this.egress = egress;
        this.tag = tag;
        this.layerId = layerId;
        this.prefOn = "weather.layer." + layerId + ".on";
        this.layer = new RasterLayer(name);
    }

    /** The picture of this box at this many pixels. */
    protected abstract String url(double west, double south, double east, double north, int px, int py);

    /** "snow analysis", for the status line. */
    protected abstract String noun();

    /** What the status says once the picture is up. */
    protected abstract String shown();

    /**
     * How opaque the picture is drawn, 0-255. An analysis that covers every pixel
     * of water or snow would otherwise hide the base map under it; the sea surface
     * temperature drew as a solid yellow sheet with no coastline (2026-09-26).
     */
    protected int alpha() {
        return 255;
    }

    /** The widest box asked for, degrees; a wider view is cropped around its center. */
    protected double maxSpanLon() {
        return 40;
    }

    protected double maxSpanLat() {
        return 30;
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void start() {
        if (started)
            return;
        started = true;
        GLRasterLayer.register();
        mapView.addLayer(MapView.RenderStack.MAP_SURFACE_OVERLAYS, layer);
        layer.setVisible(false);
        final SharedPreferences p = MapCompat.prefs();
        if (p != null && p.getBoolean(prefOn, false) && egress.isLayerEnabled(layerId))
            setOn(true);
    }

    public void stop() {
        if (!started)
            return;
        started = false;
        on = false;
        mapView.removeOnMapMovedListener(moved);
        mapView.removeCallbacks(moveSettled);
        mapView.removeCallbacks(autoPoll);
        generation++;
        layer.clear();
        mapView.removeLayer(MapView.RenderStack.MAP_SURFACE_OVERLAYS, layer);
        GLRasterLayer.unregister();
    }

    public boolean isOn() {
        return on;
    }

    public void setOn(boolean value) {
        if (!started || on == value)
            return;
        on = value;
        final SharedPreferences p = MapCompat.prefs();
        if (p != null)
            p.edit().putBoolean(prefOn, value).apply();
        mapView.removeCallbacks(autoPoll);
        mapView.removeCallbacks(moveSettled);
        generation++;
        pendingKey = null;
        if (value) {
            layer.setVisible(true);
            mapView.addOnMapMovedListener(moved);
            ensureRegion(true);
            mapView.postDelayed(autoPoll, POLL_MS);
        } else {
            mapView.removeOnMapMovedListener(moved);
            layer.clear();
            layer.setVisible(false);
            region = null;
            status("");
        }
    }

    /** Called when the pane opens: a stale picture is asked for again. */
    public void refresh(boolean force) {
        if (on)
            ensureRegion(force);
    }

    private void ensureRegion(boolean force) {
        final GeoBounds bounds = mapView.getBounds();
        if (bounds == null)
            return;
        final boolean wholeWorld = Double.isNaN(bounds.getNorth()) || Double.isNaN(bounds.getSouth())
                || Double.isNaN(bounds.getEast()) || Double.isNaN(bounds.getWest())
                || bounds.getEast() <= bounds.getWest()
                || bounds.getEast() - bounds.getWest() >= 355;
        if (wholeWorld) {
            region = null;
            layer.clear();
            status("Zoom in to see the " + noun());
            return;
        }
        double w = bounds.getWest(), e = bounds.getEast(), s = bounds.getSouth(), n = bounds.getNorth();
        final boolean stale = System.currentTimeMillis() - fetchedAt > POLL_MS;
        boolean refetch = force || stale || region == null || !contains(region, bounds);
        if (!refetch) {
            final double viewSpan = e - w, regionSpan = region.getEast() - region.getWest();
            refetch = viewSpan < regionSpan / 3.5 || viewSpan > regionSpan * 1.2;
        }
        if (!refetch)
            return;
        final double maxLon = maxSpanLon(), maxLat = maxSpanLat();
        if (e - w > maxLon) {
            final double c = (e + w) / 2;
            w = c - maxLon / 2;
            e = c + maxLon / 2;
        }
        if (n - s > maxLat) {
            final double c = (n + s) / 2;
            s = c - maxLat / 2;
            n = c + maxLat / 2;
        }
        final double padX = Math.min((e - w) * 0.5, Math.max(0, (maxLon - (e - w)) / 2));
        final double padY = Math.min((n - s) * 0.5, Math.max(0, (maxLat - (n - s)) / 2));
        final double rw = Math.max(-180, w - padX), re = Math.min(180, e + padX);
        final double rs = Math.max(-85, s - padY), rn = Math.min(85, n + padY);
        final GeoBounds r = new GeoBounds(rn, rw, rs, re);
        // Pixels follow the degrees: the picture is a plain lon/lat quad.
        int px = MAX_PX, py = (int) Math.round(MAX_PX * (rn - rs) / (re - rw));
        if (py > MAX_PX) {
            px = (int) Math.round(MAX_PX * (re - rw) / (rn - rs));
            py = MAX_PX;
        }
        final String key = String.format(Locale.US, "%.3f,%.3f,%.3f,%.3f", rw, rs, re, rn);
        if (key.equals(pendingKey))
            return;
        pendingKey = key;
        final int mine = generation;
        status("Getting the " + noun() + "…");
        Http.getBitmap(url(rw, rs, re, rn, px, py), egress.userAgent(), new Http.BitmapCallback() {
            @Override
            public void onSuccess(Bitmap bitmap) {
                if (key.equals(pendingKey))
                    pendingKey = null;
                if (mine != generation || !on || bitmap == null)
                    return;
                region = r;
                fetchedAt = System.currentTimeMillis();
                layer.setImage(translucent(bitmap, alpha()), r);
                Log.d(tag, "picture " + bitmap.getWidth() + "x" + bitmap.getHeight() + " for " + key);
                status(shown());
            }

            @Override
            public void onFailure(String error) {
                if (key.equals(pendingKey))
                    pendingKey = null;
                if (mine == generation && on)
                    status(capitalize(noun()) + ": " + error);
            }
        });
    }

    /** The same picture at the given opacity, or the picture itself when opaque. */
    private static Bitmap translucent(Bitmap src, int alpha) {
        if (alpha >= 255 || src == null)
            return src;
        final Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        final android.graphics.Canvas c = new android.graphics.Canvas(out);
        final android.graphics.Paint p = new android.graphics.Paint();
        p.setAlpha(Math.max(0, alpha));
        c.drawBitmap(src, 0, 0, p);
        src.recycle();
        return out;
    }

    private static boolean contains(GeoBounds outer, GeoBounds inner) {
        return inner.getWest() >= outer.getWest() && inner.getEast() <= outer.getEast()
                && inner.getSouth() >= outer.getSouth() && inner.getNorth() <= outer.getNorth();
    }

    private static String capitalize(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private void status(String s) {
        if (listener != null)
            listener.onStatus(s);
    }
}

package com.atakmap.android.atmosphere.overlay;

import android.graphics.Bitmap;

import com.atakmap.android.atmosphere.data.IsoTime;
import com.atakmap.android.atmosphere.data.Lightning;
import com.atakmap.android.atmosphere.net.EgressPolicy;
import com.atakmap.android.atmosphere.net.Http;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Lightning on the map: NOAA's 15-minute strike density from the ground networks,
 * the newest frame for the map's neighborhood, repainted into five bands that
 * never use green (NOAA's own ramp ends in green at the densest cores). The
 * operator asked for it in the Fire group (2026-09-30); a TAK user asked for it
 * in green / yellow / red the evening of 2026-09-21.
 *
 * <p>What is newest is read from the server's capabilities (about 10 KB): about 25
 * minutes after a frame's time, when the next one is due, then every two minutes
 * until it appears. A new frame is asked for at once; the picture is otherwise
 * asked again when the map leaves it or every 15 minutes.
 */
public final class LightningOverlay extends ImageOverlay {

    public static final String LAYER_ID = "lightning";
    public static final String HOST = Lightning.HOST;
    private static final String TAG = "AtmosphereLightning";
    /** Frames are 15 minutes apart and posted about 10 minutes after their time. */
    private static final long NEXT_DUE_MS = 25 * 60 * 1000L;
    private static final long CHECK_MS = 2 * 60 * 1000L;
    private static final long MIN_CHECK_MS = 60 * 1000L;
    /** Past this the newest frame has been missed, and the status says so. */
    private static final long OLD_MS = 45 * 60 * 1000L;

    private volatile String frame;
    private volatile long frameAt;
    private boolean checking;

    private final Runnable frameCheck = new Runnable() {
        @Override
        public void run() {
            checkFrame();
        }
    };

    public LightningOverlay(MapView mapView, EgressPolicy egress) {
        super(mapView, egress, TAG, LAYER_ID, "Lightning");
    }

    @Override
    public void setOn(boolean value) {
        super.setOn(value);
        mapView.removeCallbacks(frameCheck);
        if (isOn())
            checkFrame();
    }

    @Override
    public void stop() {
        mapView.removeCallbacks(frameCheck);
        super.stop();
    }

    private void checkFrame() {
        mapView.removeCallbacks(frameCheck);
        if (!isOn() || checking)
            return;
        checking = true;
        Http.get(Lightning.CAPABILITIES_URL, egress.userAgent(), null, new Http.Callback() {
            @Override
            public void onSuccess(String body) {
                checking = false;
                if (!isOn())
                    return;
                final String newest = Lightning.newestFrame(body);
                long next = CHECK_MS;
                if (newest != null && !newest.equals(frame)) {
                    frame = newest;
                    frameAt = IsoTime.parse(newest);
                    Log.d(TAG, "newest frame " + newest);
                    reask();
                    if (frameAt > 0)
                        next = Math.max(MIN_CHECK_MS,
                                frameAt + NEXT_DUE_MS - System.currentTimeMillis());
                }
                mapView.postDelayed(frameCheck, next);
            }

            @Override
            public void onFailure(String error) {
                checking = false;
                Log.w(TAG, "capabilities: " + error);
                if (isOn())
                    mapView.postDelayed(frameCheck, CHECK_MS);
            }
        });
    }

    @Override
    protected String url(double west, double south, double east, double north, int px, int py) {
        return Lightning.mapUrl(west, south, east, north, px, py, frame);
    }

    /** NOAA's 17 colors into our five bands; about 0.6 M pixels, once per picture. */
    @Override
    protected Bitmap repaint(Bitmap picture) {
        if (picture == null)
            return null;
        final int w = picture.getWidth(), h = picture.getHeight();
        final int[] px = new int[w * h];
        picture.getPixels(px, 0, w, 0, 0, w, h);
        int last = 0, lastOut = 0;
        for (int i = 0; i < px.length; i++) {
            final int c = px[i];
            if ((c >>> 24) == 0)
                continue;
            if (c != last) {
                last = c;
                lastOut = Lightning.repaint(c);
            }
            px[i] = lastOut;
        }
        final Bitmap out = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        picture.recycle();
        return out;
    }

    @Override
    protected long pollMs() {
        return 15 * 60 * 1000L;
    }

    /** Strong enough to read at a glance, the roads still showing through. */
    @Override
    protected int alpha() {
        return 215;
    }

    @Override
    protected double maxSpanLon() {
        return 60;
    }

    @Override
    protected double maxSpanLat() {
        return 40;
    }

    /** The product's own reach: 25 S to 80 N. */
    @Override
    protected double coverSouth() {
        return -25;
    }

    @Override
    protected double coverNorth() {
        return 80;
    }

    @Override
    protected String noun() {
        return "lightning";
    }

    @Override
    protected String shown() {
        final long at = frameAt;
        if (at <= 0)
            return "Lightning on the map: the newest 15 minutes NOAA has.";
        final long age = Math.max(0, System.currentTimeMillis() - at);
        final StringBuilder b = new StringBuilder("Lightning on the map: the 15 minutes of ")
                .append(new SimpleDateFormat("h:mm a", Locale.US).format(new Date(at)))
                .append(", ").append(Math.max(1, age / 60000L)).append(" min old.");
        if (age > OLD_MS)
            b.append(" NOAA has not posted a newer frame.");
        return b.toString();
    }
}

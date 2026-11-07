package com.atakmap.android.iwi.plugin;


import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.RectF;

import com.atakmap.android.maps.MapEvent;
import com.atakmap.android.maps.MapEventDispatcher;
import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.MapTextFormat;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.assets.Icon;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.coremap.maps.coords.GeoPointMetaData;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Where the IWI will go, shown before it is declared, and dragged into place.
 *
 * <p>The operator, 2026-10-14: "it should put the marker immediately", then "make a
 * different icon but make it larger, i should be able to move it where i want". So: a
 * large red target under an "IWI" pill, composed at the phone's own scale (Cam Depot's
 * Suggest marker's way, copied forward), that ATAK's own touch controller lets the
 * operator press and drag; where it is dropped is the new point.
 *
 * <p>A plain map item in IWI's own group, never CoT: not the CASEVAC type, because ATAK
 * raises its CASEVAC alert for any of those, and a point not yet chosen must not alert
 * anyone. The red cross is written when the IWI is declared.
 */
final class PreviewMarker {

    private static final String TAG = "IWI-Preview";
    private static final String GROUP = "IWI";
    static final String UID = "IWI-PREVIEW";
    /** Bump when the drawing changes; a stale file would be served under the same name. */
    private static final int VERSION = 1;
    private static final int RED = 0xFFFF3B30;
    private static final int FILL = 0xE6000000;
    private static final int EDGE = 0x66FFFFFF;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GAP = 6;

    /** Told where the marker was dropped after a drag. Main thread. */
    interface OnMoved {
        void onMoved(GeoPoint p);
    }

    private final MapView mapView;
    private final OnMoved onMoved;
    private Marker marker;

    PreviewMarker(MapView mapView, OnMoved onMoved) {
        this.mapView = mapView;
        this.onMoved = onMoved;
    }

    /** Put it at this point, or move it there. */
    void show(GeoPoint p, String label) {
        try {
            if (marker == null) {
                final Marker m = new Marker(p, UID);
                m.setType("b-m-p-s-p-i");
                m.setTitle(label);
                m.setMetaString("callsign", label);
                m.setMetaBoolean("nevercot", true);
                m.setMetaBoolean("removable", false);
                m.setMetaBoolean("movable", false);
                m.setMetaBoolean("editable", false);
                // Press and drag moves it (ATAK's touch controller sends ITEM_DRAG_*).
                m.setMetaBoolean("drag", true);
                // Or ATAK's icon adapter swaps in its own glyph for the type.
                m.setMetaBoolean("adapt_marker_icon", false);
                final Composed icon = compose("IWI");
                if (icon != null) {
                    m.setIcon(new Icon.Builder()
                            .setImageUri(Icon.STATE_DEFAULT, icon.uri)
                            .setSize(icon.widthDp, icon.heightDp)
                            .setAnchor(icon.anchorXDp, icon.anchorYDp)
                            .setColor(Icon.STATE_DEFAULT, WHITE)
                            .build());
                    m.setTextRenderFlag(Marker.TEXT_STATE_NEVER_SHOW);
                }
                m.setZOrder(-1000);
                group().addItem(m);
                marker = m;
                listen(true);
            } else {
                marker.setPoint(p);
                marker.setTitle(label);
                marker.setMetaString("callsign", label);
            }
            marker.setVisible(true);
        } catch (LinkageError | RuntimeException e) {
            Log.w(TAG, "could not place the preview marker", e);
        }
    }

    void hide() {
        if (marker == null)
            return;
        try {
            listen(false);
            marker.removeFromGroup();
        } catch (LinkageError | RuntimeException e) {
            Log.w(TAG, "could not remove the preview marker", e);
        }
        marker = null;
    }

    private MapGroup group() {
        MapGroup g = mapView.getRootGroup().findMapGroup(GROUP);
        if (g == null)
            g = mapView.getRootGroup().addGroup(GROUP);
        return g;
    }

    /** Moves the marker with the finger and reports where it lands. */
    private final MapEventDispatcher.MapEventDispatchListener drag =
            new MapEventDispatcher.MapEventDispatchListener() {
                @Override
                public void onMapEvent(MapEvent event) {
                    final Marker m = marker;
                    if (m == null || event.getItem() != m)
                        return;
                    final PointF pf = event.getPointF();
                    if (pf == null)
                        return;
                    final GeoPointMetaData gp = mapView.inverseWithElevation(pf.x, pf.y);
                    if (gp == null || gp.get() == null || !gp.get().isValid())
                        return;
                    final GeoPoint p = new GeoPoint(gp.get().getLatitude(),
                            gp.get().getLongitude());
                    m.setPoint(p);
                    if (MapEvent.ITEM_DRAG_DROPPED.equals(event.getType()))
                        onMoved.onMoved(p);
                }
            };

    private void listen(boolean on) {
        final MapEventDispatcher d = mapView.getMapEventDispatcher();
        for (String t : new String[] {
                MapEvent.ITEM_DRAG_STARTED, MapEvent.ITEM_DRAG_CONTINUED,
                MapEvent.ITEM_DRAG_DROPPED
        }) {
            if (on)
                d.addMapEventListener(t, drag);
            else
                d.removeMapEventListener(t, drag);
        }
    }

    // ---- the icon ----

    private static final class Composed {
        final String uri;
        final int widthDp, heightDp, anchorXDp, anchorYDp;

        Composed(String uri, int widthDp, int heightDp, int anchorXDp, int anchorYDp) {
            this.uri = uri;
            this.widthDp = widthDp;
            this.heightDp = heightDp;
            this.anchorXDp = anchorXDp;
            this.anchorYDp = anchorYDp;
        }
    }

    /**
     * The word in a black pill above a large red target, one bitmap at device pixels,
     * anchored on the target's center so the point is under the crosshair. Composed
     * once per scale; null when it cannot be written.
     */
    private static Composed compose(String text) {
        final File dir = FileSystemUtils.getItem("tools/iwi/icons");
        if (dir == null || (!dir.isDirectory() && !dir.mkdirs()))
            return null;
        final float scale = Math.max(1f,
                gov.tak.api.commons.graphics.DisplaySettings.getRelativeScaling());
        final MapTextFormat tf = MapView.getDefaultTextFormat();
        float textPx = tf == null ? 0f : tf.getDensityAdjustedFontSize();
        if (textPx <= 0f)
            textPx = 14f * scale;
        // Five text heights across: bigger than Cam Depot's Suggest point, and a
        // target big enough to find and grab with a gloved finger.
        final int gw = Math.round(textPx * 5f);
        final int gh = gw;

        Bitmap bmp = null;
        try {
            final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
            tp.setTypeface(tf == null ? null : tf.getTypeface());
            tp.setTextSize(textPx * 1.2f);
            tp.setFakeBoldText(true);
            tp.setColor(WHITE);
            final int textW = (int) Math.ceil(tp.measureText(text));
            final Paint.FontMetricsInt fm = tp.getFontMetricsInt();
            final int textH = fm.descent - fm.ascent;
            final int padY = Math.round(textPx * 0.28f);
            final int ph = textH + 2 * padY;
            final int pw = textW + ph;

            final int w = Math.max(pw, gw);
            // Room below the target to match the pill above, so the anchor is centered.
            final int h = 2 * (ph + GAP) + gh;
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            final Canvas c = new Canvas(bmp);

            final float px0 = (w - pw) / 2f;
            final RectF body = new RectF(px0 + 0.5f, 0.5f, px0 + pw - 0.5f, ph - 0.5f);
            final float r = ph / 2f;
            final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            fill.setColor(FILL);
            c.drawRoundRect(body, r, r, fill);
            final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(1f);
            edge.setColor(EDGE);
            c.drawRoundRect(body, r - 0.5f, r - 0.5f, edge);
            c.drawText(text, px0 + r, (ph - textH) / 2f - fm.ascent, tp);

            // The target: a red glow, a thick red ring with a white edge, crosshair
            // arms, and a white center on the exact point.
            final float cx = w / 2f, cy = ph + GAP + gh / 2f;
            final float R = gw / 2f;
            final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
            glow.setColor(0x55FF3B30);
            c.drawCircle(cx, cy, R, glow);
            final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
            halo.setStyle(Paint.Style.STROKE);
            halo.setStrokeWidth(R * 0.30f);
            halo.setColor(WHITE);
            c.drawCircle(cx, cy, R * 0.70f, halo);
            final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(R * 0.20f);
            ring.setColor(RED);
            c.drawCircle(cx, cy, R * 0.70f, ring);
            final Paint arm = new Paint(Paint.ANTI_ALIAS_FLAG);
            arm.setStrokeWidth(R * 0.10f);
            arm.setColor(RED);
            arm.setStrokeCap(Paint.Cap.ROUND);
            c.drawLine(cx, cy - R * 0.98f, cx, cy - R * 0.32f, arm);
            c.drawLine(cx, cy + R * 0.32f, cx, cy + R * 0.98f, arm);
            c.drawLine(cx - R * 0.98f, cy, cx - R * 0.32f, cy, arm);
            c.drawLine(cx + R * 0.32f, cy, cx + R * 0.98f, cy, arm);
            final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
            dot.setColor(WHITE);
            c.drawCircle(cx, cy, R * 0.12f, dot);
            dot.setColor(RED);
            c.drawCircle(cx, cy, R * 0.06f, dot);

            final String key = text + "_v" + VERSION + "_s" + Math.round(scale * 100)
                    + "_f" + Math.round(textPx * 10);
            final File out = new File(dir, "preview_" + key.replaceAll("[^A-Za-z0-9_]", "")
                    + ".png");
            if (!out.isFile()) {
                final File tmp = new File(out.getPath() + ".tmp");
                final FileOutputStream o = new FileOutputStream(tmp);
                try {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, o);
                } finally {
                    o.close();
                }
                if (!tmp.renameTo(out))
                    Log.w(TAG, "could not save the preview icon");
            }
            if (!out.isFile())
                return null;
            return new Composed("file://" + out.getAbsolutePath(),
                    Math.round(w / scale), Math.round(h / scale),
                    Math.round(w / 2f / scale), Math.round(cy / scale));
        } catch (Exception e) {
            Log.w(TAG, "preview icon", e);
            return null;
        } finally {
            if (bmp != null)
                bmp.recycle();
        }
    }
}

package com.atakmap.android.iwi.plugin;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;

import com.atakmap.android.maps.MapEvent;
import com.atakmap.android.maps.MapEventDispatcher;
import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapTextFormat;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.android.maps.PointMapItem;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.assets.Icon;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * The hospital markers' labels on a device with IWI: white text on a red, rounded box
 * above the dot, the way Atmosphere labels its pills (operator, 2026-11-10: "red
 * background with white letters ... can we do that for the hospitals?").
 *
 * <p>ATAK's marker label cannot take a background color, and a CoT marker cannot carry
 * one, so a device without IWI keeps ATAK's own label on the red dot. Here the label is
 * pixels in a bitmap, placed as the icon of a second, local marker at the same point:
 * never CoT, never clickable, so a tap still finds the hospital marker. The hospital
 * marker's own label is turned off on this device only. A second marker rather than a
 * new icon on the hospital marker, because ATAK rebuilds a CoT marker's icon from its
 * type and color on every update.
 *
 * <p>It covers every hospital marker on the map, this device's and those another
 * device's IWI put in the feed: their UIDs are {@code IWI-<uuid>-H-<id>}, and the
 * Hospitals page's own pick is {@link HospitalMarker#UID}. Lives for the plugin's life.
 */
final class HospitalLabels {

    private static final String TAG = "IWI-Labels";
    private static final String GROUP = "IWI Hospital Labels";
    private static final String SUFFIX = ".iwi-label";
    /** Bump when the drawing changes, or a stale file is served under the same name. */
    private static final int VERSION = 1;
    /** A red that white text reads on; the dot is ATAK red, see HospitalMarker. */
    private static final int FILL = 0xFFD00000;
    private static final int EDGE = 0x99FFFFFF;
    private static final int TEXT = 0xFFFFFFFF;
    /** Clear space between the box and the point, in dp: the dot's half height and a bit. */
    private static final int GAP_DP = 18;

    private final MapView mapView;
    private final File dir;
    private final Map<String, Marker> labels = new HashMap<>();
    private final Map<String, Icon> icons = new HashMap<>();
    private MapGroup group;

    HospitalLabels(MapView mapView) {
        this.mapView = mapView;
        final File d = FileSystemUtils.getItem("tools/iwi/labels");
        if (d != null && !d.isDirectory())
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        dir = d;
    }

    /** A hospital marker of any IWI's, by its UID. */
    static boolean isHospital(String uid) {
        return uid != null && !uid.endsWith(SUFFIX)
                && (HospitalMarker.UID.equals(uid)
                        || (uid.startsWith("IWI-") && uid.contains("-H-")));
    }

    void start() {
        final MapEventDispatcher d = mapView.getMapEventDispatcher();
        d.addMapEventListener(MapEvent.ITEM_ADDED, events);
        d.addMapEventListener(MapEvent.ITEM_REFRESH, events);
        d.addMapEventListener(MapEvent.ITEM_REMOVED, events);
        mapView.post(new Runnable() {
            @Override
            public void run() {
                // The hospitals already on the map when the plugin loads.
                final ArrayList<Marker> found = new ArrayList<>();
                mapView.getRootGroup().deepForEachItem(new MapGroup.MapItemsCallback() {
                    @Override
                    public boolean onItemFunction(MapItem item) {
                        if (item instanceof Marker && isHospital(item.getUID()))
                            found.add((Marker) item);
                        return false; // false: keep going
                    }
                });
                for (Marker m : found)
                    attach(m);
            }
        });
    }

    void stop() {
        final MapEventDispatcher d = mapView.getMapEventDispatcher();
        d.removeMapEventListener(MapEvent.ITEM_ADDED, events);
        d.removeMapEventListener(MapEvent.ITEM_REFRESH, events);
        d.removeMapEventListener(MapEvent.ITEM_REMOVED, events);
        mapView.post(new Runnable() {
            @Override
            public void run() {
                for (String uid : new ArrayList<>(labels.keySet()))
                    detach(uid, true);
                if (group != null) {
                    final MapGroup parent = group.getParentGroup();
                    if (parent != null)
                        parent.removeGroup(group);
                    group = null;
                }
            }
        });
    }

    private final MapEventDispatcher.MapEventDispatchListener events =
            new MapEventDispatcher.MapEventDispatchListener() {
                @Override
                public void onMapEvent(MapEvent event) {
                    final MapItem item = event.getItem();
                    if (item == null || !isHospital(item.getUID()))
                        return;
                    if (MapEvent.ITEM_REMOVED.equals(event.getType()))
                        detach(item.getUID(), false);
                    else if (item instanceof Marker)
                        attach((Marker) item);
                }
            };

    private final Marker.OnTitleChangedListener titles = new Marker.OnTitleChangedListener() {
        @Override
        public void onTitleChanged(Marker m) {
            attach(m);
        }
    };

    private final PointMapItem.OnPointChangedListener points =
            new PointMapItem.OnPointChangedListener() {
                @Override
                public void onPointChanged(PointMapItem m) {
                    final Marker l = labels.get(m.getUID());
                    if (l != null)
                        l.setPoint(m.getPoint());
                }
            };

    private final MapItem.OnVisibleChangedListener visibility =
            new MapItem.OnVisibleChangedListener() {
                @Override
                public void onVisibleChanged(MapItem m) {
                    final Marker l = labels.get(m.getUID());
                    if (l != null)
                        l.setVisible(m.getVisible());
                }
            };

    /** Put the box over this hospital, or bring it up to date. Main thread. */
    private void attach(Marker m) {
        final String text = m.getTitle();
        final Icon icon = icon(text);
        if (icon == null)
            return; // ATAK's own label stays on: better a plain label than none
        m.setTextRenderFlag(Marker.TEXT_STATE_NEVER_SHOW);
        Marker l = labels.get(m.getUID());
        if (l == null) {
            l = new Marker(m.getPoint(), m.getUID() + SUFFIX);
            l.setTitle("");
            l.setTextRenderFlag(Marker.TEXT_STATE_NEVER_SHOW);
            l.setClickable(false);
            l.setMovable(false);
            l.setMetaBoolean("nevercot", true);
            l.setMetaBoolean("addToObjList", false);
            l.setMetaBoolean("removable", false);
            l.setMetaBoolean("editable", false);
            l.setMetaBoolean("archive", false);
            l.setIcon(icon);
            l.setIconVisibility(Marker.ICON_VISIBLE);
            group().addItem(l);
            labels.put(m.getUID(), l);
            m.addOnTitleChangedListener(titles);
            m.addOnPointChangedListener(points);
            m.addOnVisibleChangedListener(visibility);
        } else {
            l.setIcon(icon);
            l.setPoint(m.getPoint());
        }
        l.setVisible(m.getVisible());
    }

    private void detach(String uid, boolean restore) {
        final Marker l = labels.remove(uid);
        if (l != null) {
            final MapGroup g = l.getGroup();
            if (g != null)
                g.removeItem(l);
        }
        final MapItem m = mapView.getRootGroup().deepFindUID(uid);
        if (m instanceof Marker) {
            final Marker hm = (Marker) m;
            hm.removeOnTitleChangedListener(titles);
            hm.removeOnPointChangedListener(points);
            hm.removeOnVisibleChangedListener(visibility);
            if (restore)
                hm.setTextRenderFlag(Marker.TEXT_STATE_DEFAULT);
        }
    }

    private MapGroup group() {
        if (group == null) {
            final MapGroup root = mapView.getRootGroup();
            MapGroup g = root.findMapGroup(GROUP);
            if (g == null) {
                g = root.addGroup(GROUP);
            } else {
                // A reinstall can leave the last instance's group behind, full of boxes
                // nobody tracks. Start from an empty group.
                for (MapItem it : new ArrayList<>(g.getItems()))
                    g.removeItem(it);
            }
            g.setMetaBoolean("addToObjList", false);
            group = g;
        }
        return group;
    }

    /** The box for this text, the clear space under it, anchored on the point. */
    private Icon icon(String text) {
        if (text == null || text.trim().isEmpty() || dir == null)
            return null;
        final float scale = Math.max(1f,
                gov.tak.api.commons.graphics.DisplaySettings.getRelativeScaling());
        final MapTextFormat tf = MapView.getDefaultTextFormat();
        float textPx = tf == null ? 0f : tf.getDensityAdjustedFontSize();
        if (textPx <= 0f)
            textPx = 14f * scale;
        final String key = text + "|" + VERSION + "|" + Math.round(textPx * 10);
        final Icon hit = icons.get(key);
        if (hit != null)
            return hit;

        final String[] lines = text.trim().split("\n");
        final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        final Typeface face = tf == null ? null : tf.getTypeface();
        tp.setTypeface(face);
        tp.setTextSize(textPx);
        tp.setFakeBoldText(true);
        tp.setColor(TEXT);
        final Paint.FontMetrics fm = tp.getFontMetrics();
        final float lineH = fm.descent - fm.ascent;
        float textW = 0f;
        for (String s : lines)
            textW = Math.max(textW, tp.measureText(s.trim()));
        final int padX = Math.round(textPx * 0.6f), padY = Math.round(textPx * 0.35f);
        final int boxW = (int) Math.ceil(textW) + 2 * padX;
        final int boxH = Math.round(lineH * lines.length) + 2 * padY;
        final int gap = Math.round(GAP_DP * scale);
        final int w = boxW + 2, h = boxH + gap + 1;

        final File out = new File(dir, "h_" + Integer.toHexString(key.hashCode()) + ".png");
        if (!out.isFile() && !compose(out, lines, tp, fm, lineH, padX, padY, boxW, boxH, w, h))
            return null;
        final Icon icon = new Icon.Builder()
                .setImageUri(Icon.STATE_DEFAULT, "file://" + out.getAbsolutePath())
                .setSize(Math.round(w / scale), Math.round(h / scale))
                .setAnchor(Math.round(w / 2f / scale), Math.round(h / scale))
                .build();
        icons.put(key, icon);
        return icon;
    }

    private static boolean compose(File out, String[] lines, Paint tp, Paint.FontMetrics fm,
            float lineH, int padX, int padY, int boxW, int boxH, int w, int h) {
        Bitmap bmp = null;
        try {
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            final Canvas c = new Canvas(bmp);
            final RectF body = new RectF(1.5f, 0.5f, boxW + 0.5f, boxH - 0.5f);
            final float r = Math.min(boxH / 2f, tp.getTextSize() * 0.6f);
            final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            fill.setColor(FILL);
            c.drawRoundRect(body, r, r, fill);
            final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(1f);
            edge.setColor(EDGE);
            c.drawRoundRect(body, r, r, edge);
            final float cx = 1 + boxW / 2f;
            tp.setTextAlign(Paint.Align.CENTER);
            for (int i = 0; i < lines.length; i++)
                c.drawText(lines[i].trim(), cx, padY + i * lineH - fm.ascent, tp);

            final File tmp = new File(out.getPath() + ".tmp");
            final FileOutputStream o = new FileOutputStream(tmp);
            try {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, o);
            } finally {
                o.close();
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(out);
            return out.isFile();
        } catch (Exception e) {
            Log.w(TAG, "hospital label", e);
            return false;
        } finally {
            if (bmp != null)
                bmp.recycle();
        }
    }
}

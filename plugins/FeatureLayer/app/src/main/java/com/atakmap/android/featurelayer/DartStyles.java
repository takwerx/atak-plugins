package com.atakmap.android.featurelayer;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

import com.atakmap.coremap.log.Log;
import com.atakmap.map.layer.feature.style.IconPointStyle;
import com.atakmap.map.layer.feature.style.Style;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Locale;

/**
 * DART symbology, as EGP draws it.
 *
 * <p>Neither service's own renderer is usable on a map. DART Personnel declares a 22.5
 * point picture marker; DART Vehicles declares an {@code esriSMS} dot of <b>size 4</b>,
 * which is an olive speck about thirteen pixels across and invisible over dry ground.
 * EGP's WildFireSA Advanced does not draw either one: it applies its own icon style, and
 * that style is a public ArcGIS Online item (26d310d186ee4677b896c38eece3f261, "EGP
 * WildFireSA Icons"), so the 25 {@code egp-dart-*} glyphs are bundled under
 * {@code assets/dart} and used here. A user who has seen the EGP viewer recognizes them.
 *
 * <p>The glyphs are pale by design -- a light blue hard hat, a mint green fire engine --
 * because EGP draws them over a muted basemap. Over imagery they disappear, so each one
 * is composited onto a dark disc with a light ring, the same trick {@link PointIcons}
 * uses for NWCG repair status. Composites are cached in the icon directory and drawn
 * once.
 */
final class DartStyles {
    private static final String TAG = "FeatureLayer";

    /**
     * The marker's edge in pixels: 32, which is what ATAK draws its own markers at. Every
     * icon in ATAK's iconsets.sqlite is a 32x32 PNG -- all 77 fire icons across its
     * GeoOps and FEMA sets included -- so a DART marker sits beside an ATAK fire engine
     * at the same size instead of shouting over it. Do not raise this without a reason
     * that beats "it matches ATAK".
     */
    private static final float PX = 32f;
    /**
     * Bumped when the composite itself changes, so cached ones are not reused. Never reuse
     * a number: 5 was used once for a 4x-wide test canvas, reverted, then used again for
     * the padded one, and the phone still had the wide test file for one glyph -- that
     * vehicle drew at a third the size of its neighbors while everything about its code
     * path was identical (2026-09-17).
     */
    private static final int MARK_V = 6;
    /**
     * A square canvas, and the disc is centered on the position.
     *
     * <p>It was briefly 96x288, the disc in the top square, to lift the marker off the
     * point by geometry baked into the PNG, because the alignment arguments looked
     * undocumented and unusable. They are neither, they are just not offsets: the
     * constructor collapses {@code alignX}/{@code alignY} to a three-way enum <b>by
     * sign</b> and discards the number, which is why an invented -160 behaved exactly like
     * -1. A tall canvas is the wrong tool anyway -- the drawn size comes from the style, so
     * a 1:3 PNG has to be asked for at 1:3 or the glyph is stretched, and the empty two
     * thirds still scale with it.
     */
    private static final int CANVAS = 96;
    /**
     * Transparent padding below the disc, as a fraction of the disc. GLMarker2 places a
     * marker's label at (icon height - anchorY) from the point, on the side opposite the
     * icon's bottom; with a square icon and a centered anchor that is exactly touching,
     * and a label that touches its icon's rectangle gets trimmed to the icon's width --
     * "CA-ANF-E327" drew as "-E3", while a neighbour whose label ATAK had floated clear
     * drew whole (2026-09-17). Padding below the disc, with the anchor still on the disc's
     * center, keeps the disc on the position and lifts the label clear of it. The cost is
     * a touch target that extends this far below the disc.
     */
    static final float PAD = 0.5f;
    private static final int CANVAS_H = CANVAS + (int) (CANVAS * PAD);
    private static final int DISC = 0xD9101010, RING = 0xFFE6E6E6;

    private DartStyles() {
    }

    /** The disc's drawn edge, so a marker drawing it can ask for the same size. */
    static float markerPx() {
        return PX;
    }

    /** The composite's drawn height: the disc plus the padding below it. */
    static float markerPxH() {
        return PX * (1f + PAD);
    }

    /**
     * The disc every DART point can fall back to, for a row whose own glyph would not
     * compose. "other-other" is EGP's own generic resource symbol and is always bundled.
     */
    static String genericMarkerUri(File iconDir) {
        final File f = marker("other-other", iconDir);
        return f == null ? null : "file://" + f.getAbsolutePath();
    }

    /** Bumped when the labelled composite's layout changes. */
    private static final int LABEL_V = 1;
    /** Gap between the callsign's pill and the disc, and the pill's padding, in device px. */
    private static final int GAP = 4, PAD_X = 8, PAD_Y = 4, RADIUS = 6;
    /** The backing ATAK draws behind its own marker labels: argb(153, 0, 0, 0). */
    private static final int LABEL_BG = 0x99000000;

    /**
     * The disc with its callsign drawn above it as pixels, at device resolution, so a
     * marker showing it needs no label from ATAK's label engine at all.
     *
     * <p>Why pixels: ATAK's native label manager trims a marker label to a fraction of its
     * icon's width under conditions that could not be found from a plugin -- an isolated
     * CA-ANF-E327 as "-E32", a stock marker titled BARETEST as "BARE", while identical
     * neighbours drew whole (2026-09-17, XCover, dev ATAK 5.8.0.3). A callsign is the
     * point of the marker; it cannot be left to a rule nobody can read. The text is drawn
     * with ATAK's own typeface and default font size and on the same dark backing its own
     * labels use, so it reads as part of the map rather than as a plugin.
     *
     * <p>Costs, stated: no deconfliction (two vehicles on one spot show two overlapping
     * callsigns rather than one trimmed), the global labels switch does not hide these,
     * and the touch target is as wide as the pill. One bitmap per distinct callsign and
     * glyph, cached on disk.
     *
     * @param textPx  the paint size ATAK's own marker labels use, in device px:
     *                {@code MapTextFormat.getDensityAdjustedFontSize()}.
     * @param scale   {@code DisplaySettings.getRelativeScaling()}: the px per dp ATAK draws
     *                marker icons at. The bitmap is composed at device px and drawn 1:1.
     * @return the file, and the anchor (px) of the disc's center in {@code anchorOut}.
     */
    static File labelled(String glyphMarkerUri, String callsign, android.graphics.Typeface face, float textPx,
            float scale, File iconDir, int[] anchorOut) {
        if (glyphMarkerUri == null || callsign == null || callsign.isEmpty())
            return null;
        final String base = new File(glyphMarkerUri.replace("file://", "")).getName().replace(".png", "");
        final String key = base + "_" + Integer.toHexString(callsign.hashCode()) + "_v" + LABEL_V + "_s"
                + Math.round(scale * 100) + "_f" + Math.round(textPx * 10);
        final File out = new File(iconDir, "dartl_" + key + ".png");
        final int discPx = Math.round(PX * scale);
        final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        tp.setTypeface(face);
        tp.setTextSize(textPx);
        tp.setColor(0xFFFFFFFF);
        final Rect tb = new Rect();
        tp.getTextBounds(callsign, 0, callsign.length(), tb);
        final int textW = (int) Math.ceil(tp.measureText(callsign));
        final Paint.FontMetricsInt fm = tp.getFontMetricsInt();
        final int textH = fm.descent - fm.ascent;
        final int pillW = textW + 2 * PAD_X, pillH = textH + 2 * PAD_Y;
        final int w = Math.max(pillW, discPx) + 2, h = pillH + GAP + discPx + 2;
        final int discCx = w / 2, discCy = pillH + GAP + discPx / 2;
        if (anchorOut != null && anchorOut.length >= 2) {
            anchorOut[0] = discCx;
            anchorOut[1] = discCy;
        }
        if (out.isFile())
            return out;
        try {
            final File src = new File(glyphMarkerUri.replace("file://", ""));
            final Bitmap disc = BitmapFactory.decodeFile(src.getAbsolutePath());
            if (disc == null)
                return null;
            final Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            final Canvas c = new Canvas(bmp);
            final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
            bg.setColor(LABEL_BG);
            final float pl = (w - pillW) / 2f;
            c.drawRoundRect(new RectF(pl, 1, pl + pillW, 1 + pillH), RADIUS, RADIUS, bg);
            c.drawText(callsign, pl + PAD_X, 1 + PAD_Y - fm.ascent, tp);
            // The glyph composite is square-ish with the disc in its top PX x PX; draw
            // just that square at disc size.
            final int side = Math.min(disc.getWidth(), disc.getHeight());
            c.drawBitmap(disc, new Rect(0, 0, side, side),
                    new RectF(discCx - discPx / 2f, discCy - discPx / 2f, discCx + discPx / 2f, discCy + discPx / 2f),
                    new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
            final File tmp = new File(out.getPath() + ".tmp");
            final FileOutputStream o = new FileOutputStream(tmp);
            try {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, o);
            } finally {
                o.close();
                bmp.recycle();
                disc.recycle();
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(out);
            return out.isFile() ? out : null;
        } catch (Exception e) {
            Log.w(TAG, "DART labelled marker " + callsign, e);
            return null;
        }
    }

    /** Whether a spec draws DART symbology at all. */
    static boolean handles(LayerSpec spec) {
        return "dart".equals(spec.iconSet);
    }

    /**
     * The marker for one DART row, or null when the glyph is missing and the service's own
     * symbol should stand.
     *
     * @param personnel true for the personnel feed, false for vehicles.
     */
    static Style style(JSONObject props, boolean personnel, File iconDir) {
        final String glyph = personnel ? personGlyph(props) : vehicleGlyph(props);
        final File marker = marker(glyph, iconDir);
        if (marker == null)
            return null;
        // Arguments five and six are alignX/alignY, not offsets, and 0/0 centers the disc
        // on the vehicle's own position -- where it belongs, and where every ATAK marker
        // sits. It spent a few hours shoved above and then below the point to keep a
        // feature label off it; the callsign is a marker label now (see DartMarkers), and
        // ATAK lays that out clear of the icon by itself.
        // The composite is PX wide and PX*(1+PAD) tall; asking for a square box squashed the
        // padded image to a third-size disc wherever the feature itself got drawn.
        return new IconPointStyle(0xFFFFFFFF, "file://" + marker.getAbsolutePath(), PX, PX * (1f + PAD), 0, 0, 0f, true);
    }

    /**
     * Which glyph a person gets. {@code resource_type} is the field that carries the kind
     * (IHC, ENG, OPS); {@code category} is <b>not</b> -- it holds a cohort code like
     * "TX2025". Three quarters of rows have no resource type at all, so the fallback is
     * the one that has to look right.
     *
     * <p>A Garmin inReach is called out with EGP's own device glyph rather than a person:
     * it is a satellite beacon, so its position can be hours old while a phone's is
     * minutes, and that is worth seeing on the map.
     */
    private static String personGlyph(JSONObject props) {
        if ("inreach".equalsIgnoreCase(str(props, "data_source")))
            return "inreach";
        final String t = str(props, "resource_type");
        if (t == null)
            return "personnel-crew";
        final String u = t.toUpperCase(Locale.US);
        if (u.startsWith("IHC") || u.contains("HOTSHOT") || u.contains("HOT SHOT"))
            return "personnel-ihc";
        if (u.startsWith("ENG"))
            return "personnel-engine-crew";
        if (u.startsWith("OPS") || u.startsWith("WFM") || u.contains("OVERHEAD") || u.contains("PIO"))
            return "personnel-overhead";
        if (u.contains("MEDIC") || u.startsWith("EMT") || u.startsWith("EMR"))
            return "personnel-medical";
        if (u.startsWith("HEQ") || u.contains("DOZER") || u.contains("TRACTOR"))
            return "personnel-bulldozer";
        if (u.startsWith("SMKJ") || u.contains("HELI") || u.contains("AIR") || u.contains("AVIATION"))
            return "personnel-aviation";
        if (u.startsWith("CRW") || u.contains("CREW"))
            return "personnel-crew";
        return "personnel-other";
    }

    /**
     * Which glyph a vehicle gets: EGP's set is agency crossed with kind. BLM rides with
     * DOI because it is a DOI bureau and EGP has no bureau-level glyph; anything that is
     * neither USFS nor DOI is "other", which is also what a null agency gets rather than
     * being dropped.
     */
    private static String vehicleGlyph(JSONObject props) {
        final String a = str(props, "Agency");
        final String agency = a == null ? "other"
                : a.toUpperCase(Locale.US).startsWith("USFS") ? "usfs"
                        : (a.toUpperCase(Locale.US).startsWith("BLM") || a.toUpperCase(Locale.US).startsWith("DOI")
                                || a.toUpperCase(Locale.US).startsWith("NPS") || a.toUpperCase(Locale.US).startsWith("FWS")
                                || a.toUpperCase(Locale.US).startsWith("BIA")) ? "doi" : "other";
        final String t = str(props, "ResourceType");
        final String u = t == null ? "" : t.toUpperCase(Locale.US);
        final String kind;
        if (u.startsWith("ENGINE") || u.contains("WATER TENDER") || u.startsWith("WT"))
            kind = "engine";
        else if (u.contains("CREW CARRIER") || u.contains("BUGGY") || u.contains("CREW"))
            kind = "buggy";
        else if (u.contains("DOZER") || u.contains("TRACTOR") || u.contains("PLOW") || u.contains("EXCAVATOR"))
            kind = "dozer";
        else if (u.contains("TRUCK") || u.contains("SUV") || u.contains("COMMAND") || u.contains("PICKUP")
                || u.contains("SEDAN") || u.contains("VAN") || u.contains("SUPT"))
            kind = "vehicle";
        else
            kind = "other";
        return agency + "-" + kind;
    }

    private static String str(JSONObject props, String field) {
        if (props == null || props.isNull(field))
            return null;
        final String v = props.optString(field, null);
        return v == null || v.trim().isEmpty() ? null : v.trim();
    }

    /**
     * The glyph on its dark disc, composed once and cached. Null when the glyph was not
     * unpacked, so a caller falls back rather than drawing nothing.
     */
    private static synchronized File marker(String glyph, File iconDir) {
        final File out = new File(iconDir, "dartm" + MARK_V + "_" + glyph.replace('-', '_') + ".png");
        if (out.isFile())
            return out;
        final File src = new File(iconDir, "dart_egp-dart-" + glyph + ".png");
        if (!src.isFile()) {
            Log.w(TAG, "DART glyph missing: " + src.getName());
            return null;
        }
        try {
            final Bitmap in = BitmapFactory.decodeFile(src.getAbsolutePath());
            if (in == null)
                return null;
            final Bitmap bmp = Bitmap.createBitmap(CANVAS, CANVAS_H, Bitmap.Config.ARGB_8888);
            final Canvas c = new Canvas(bmp);
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.FILL);
            p.setColor(DISC);
            c.drawCircle(CANVAS / 2f, CANVAS / 2f, CANVAS / 2f - 3f, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(4f);
            p.setColor(RING);
            c.drawCircle(CANVAS / 2f, CANVAS / 2f, CANVAS / 2f - 3f, p);
            // Fit inside the disc, aspect kept: EGP's vehicles are wide (130x88) and its
            // personnel glyphs are square, and a squashed fire engine reads as a smudge.
            final float fit = CANVAS * 0.74f;
            final float scale = Math.min(fit / in.getWidth(), fit / in.getHeight());
            final float w = in.getWidth() * scale, h = in.getHeight() * scale;
            final RectF dst = new RectF((CANVAS - w) / 2f, (CANVAS - h) / 2f, (CANVAS + w) / 2f, (CANVAS + h) / 2f);
            c.drawBitmap(in, new Rect(0, 0, in.getWidth(), in.getHeight()), dst,
                    new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
            // Written whole, then moved into place: a reader never sees a half file.
            final File tmp = new File(out.getPath() + ".tmp");
            final FileOutputStream o = new FileOutputStream(tmp);
            try {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, o);
            } finally {
                o.close();
                bmp.recycle();
                in.recycle();
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(out);
            return out.isFile() ? out : null;
        } catch (Exception e) {
            Log.w(TAG, "DART marker " + glyph + " failed", e);
            return null;
        }
    }
}

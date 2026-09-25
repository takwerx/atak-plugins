package com.atakmap.android.atmosphere.overlay;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;

import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.log.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A weather station as it is drawn on the map: the NWCG station diamond, a staff
 * showing where the wind is coming from, and the two numbers a fire weather forecaster
 * reads first -- wind speed and relative humidity.
 *
 * <p><b>The diamond carries the state, not a halo around it.</b> Normal is the symbol's
 * own color, amber is a station close to its Red Flag criteria, red is one meeting
 * them. This is the Comms convention, where a repeater a handheld can likely open
 * lights up rather than growing a ring (operator, 2026-09-25: "if its flirting we turn
 * it yellow and if its in red flag its red"). A halo reads as a selection or an
 * accuracy circle; a colored symbol reads as a condition.
 *
 * <p>Comms ships a second drawable per state because tinting an icon multiplies the
 * whole symbol and loses its inner marks. These are composed at runtime regardless --
 * the numbers change every hour -- so the diamond is drawn in the state color and the
 * anemometer marks are punched back through in white.
 *
 * <p>The staff shows <b>direction only</b>. A meteorological barb's feathers are read
 * in knots by convention, which would disagree with a speed shown beside it in the
 * operator's own unit; a station that reads "14" next to two and a half feathers is
 * worse than one that just points.
 */
final class StationIcons {

    private static final String TAG = "AtmosphereStations";

    /** Bump when the drawing changes, or stale files are served under the same names. */
    private static final int VERSION = 1;

    /** The NWCG symbol's own blue: nothing is wrong at this station. */
    static final int NORMAL = 0xFF1B3B8B;
    /** Close to its criteria. Comms' warn amber. */
    static final int NEAR = 0xFFFFB74D;
    /** Meeting its Red Flag criteria. */
    static final int CRITICAL = 0xFFE53935;

    /** Device pixels across. Bigger than the spot disc: this one carries two numbers. */
    private static final int SIZE = 116;

    /** Where the diamond sits, leaving the right side for the readings. */
    private static final float CX = 40f, CY = 58f, HALF = 21f;

    private final File dir;
    private final Map<String, String> cache = new HashMap<>();

    StationIcons() {
        dir = FileSystemUtils.getItem("tools/atmosphere/station-icons");
        if (dir != null && !dir.isDirectory())
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
    }

    /** The icon's pixel width, so the caller asks the renderer for the same size. */
    static int size() {
        return SIZE;
    }

    /** What a color means, for the legend under the layer's toggle. */
    static String stateLabel(int color) {
        if (color == CRITICAL)
            return "Red Flag criteria met";
        if (color == NEAR)
            return "Close to criteria";
        return "Below criteria";
    }

    /**
     * A {@code file://} uri for one station's reading, composed once and kept.
     *
     * @param speed      the wind speed to print, already in the operator's unit, or NaN
     * @param humidity   relative humidity in percent, or NaN
     * @param windFrom   degrees the wind is coming from, or NaN for no staff
     * @param state      {@link #NORMAL}, {@link #NEAR} or {@link #CRITICAL}
     */
    String uri(double speed, double humidity, double windFrom, int state) {
        final String key = round(speed) + "_" + round(humidity) + "_"
                + bucket(windFrom) + "_" + Integer.toHexString(state) + "_v" + VERSION;
        final String hit = cache.get(key);
        if (hit != null)
            return hit;
        final File out = new File(dir, "wx_" + key + ".png");
        if (!out.isFile() && !compose(out, speed, humidity, windFrom, state))
            return null;
        final String uri = "file://" + out.getAbsolutePath();
        cache.put(key, uri);
        return uri;
    }

    /** Whole numbers only: a station reading 14.3 mph and one reading 14.4 share a file. */
    private static String round(double v) {
        return Double.isNaN(v) ? "x" : String.valueOf(Math.round(v));
    }

    /** Ten degree steps, which is finer than a staff this long can show anyway. */
    private static String bucket(double deg) {
        if (Double.isNaN(deg))
            return "x";
        long d = Math.round(deg / 10.0) * 10L;
        return String.valueOf(((d % 360) + 360) % 360);
    }

    private boolean compose(File out, double speed, double humidity, double windFrom,
            int state) {
        Bitmap bmp = null;
        try {
            bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
            final Canvas c = new Canvas(bmp);

            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            staff(c, p, windFrom);
            diamond(c, p, state);
            anemometer(c, p);
            readings(c, p, speed, humidity);

            final FileOutputStream fos = new FileOutputStream(out);
            try {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
            } finally {
                fos.close();
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "could not compose a station icon", e);
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            return false;
        } finally {
            if (bmp != null)
                bmp.recycle();
        }
    }

    /**
     * The staff, drawn out of the diamond toward where the wind is blowing FROM --
     * the meteorological convention, and the opposite of an arrow showing where it is
     * going. A north wind puts the staff at the top of the symbol.
     */
    private void staff(Canvas c, Paint p, double windFrom) {
        if (Double.isNaN(windFrom))
            return;
        final double r = Math.toRadians(windFrom);
        final float ex = CX + (float) (Math.sin(r) * 34.0);
        final float ey = CY - (float) (Math.cos(r) * 34.0);
        p.setColor(Color.WHITE);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(5f);
        p.setStrokeCap(Paint.Cap.ROUND);
        c.drawLine(CX, CY, ex, ey, p);
        // A head at the far end, so which way it points survives at small sizes.
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(ex, ey, 5.5f, p);
    }

    private void diamond(Canvas c, Paint p, int state) {
        final Path d = new Path();
        d.moveTo(CX, CY - HALF);
        d.lineTo(CX + HALF, CY);
        d.lineTo(CX, CY + HALF);
        d.lineTo(CX - HALF, CY);
        d.close();
        p.setStyle(Paint.Style.FILL);
        p.setColor(state);
        c.drawPath(d, p);
        // A thin white edge, so a red diamond still reads on dark terrain.
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2f);
        p.setColor(0xFFFFFFFF);
        c.drawPath(d, p);
    }

    /** The NWCG mobile weather unit's mark: a mast with a cup at the top and two arms. */
    private void anemometer(Canvas c, Paint p) {
        p.setColor(Color.WHITE);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2.4f);
        p.setStrokeCap(Paint.Cap.ROUND);
        c.drawLine(CX, CY - 8f, CX, CY + 9f, p);
        c.drawLine(CX - 8f, CY + 2f, CX + 8f, CY + 2f, p);
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(CX, CY - 10f, 3.4f, p);
        c.drawCircle(CX - 9.5f, CY + 2f, 3f, p);
        c.drawCircle(CX + 9.5f, CY + 2f, 3f, p);
    }

    /**
     * Wind speed over humidity, to the right of the symbol. No units printed: they are
     * the same for every station on the map and the layer says which above the list.
     * A reading the station did not send is a dash, never a zero.
     */
    private void readings(Canvas c, Paint p, double speed, double humidity) {
        p.setStyle(Paint.Style.FILL);
        p.setTextSize(30f);
        p.setFakeBoldText(true);
        p.setTextAlign(Paint.Align.LEFT);
        text(c, p, value(speed), 66f, CY - 4f);
        text(c, p, value(humidity), 66f, CY + 28f);
    }

    private static String value(double v) {
        return Double.isNaN(v) ? "–" : String.format(Locale.US, "%d", Math.round(v));
    }

    /** White on a dark outline, which survives both a snow basin and a burn scar. */
    private void text(Canvas c, Paint p, String s, float x, float y) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(4.5f);
        p.setColor(0xCC000000);
        c.drawText(s, x, y, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.WHITE);
        c.drawText(s, x, y, p);
    }
}

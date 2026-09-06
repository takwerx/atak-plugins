package com.atakmap.android.fobs.track;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import com.atakmap.android.drawing.DrawingPreferences;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Shape;

/**
 * The default style every new FOBS track starts with: color, line style, thickness.
 * Set from the Default Style box in the FOBS popup, kept in ATAK's shared preferences
 * under plugin-namespaced keys. First use seeds from ATAK's own drawing preferences so
 * a track looks like the user's other drawings until they say otherwise.
 */
public final class StylePrefs {

    static final String KEY_COLOR = "fobs_default_color";
    static final String KEY_LINE_STYLE = "fobs_default_line_style";
    static final String KEY_STROKE_WEIGHT = "fobs_default_stroke_weight";

    /** Thickness choices, in ATAK stroke-weight units. */
    public static final double THIN = 2.0;
    public static final double MEDIUM = 4.0;
    public static final double THICK = 6.0;

    private final SharedPreferences prefs;
    private final DrawingPreferences atak;

    public StylePrefs(MapView mapView) {
        Context host = mapView.getContext();
        this.prefs = PreferenceManager.getDefaultSharedPreferences(host);
        this.atak = new DrawingPreferences(mapView);
    }

    public int color() {
        return prefs.getInt(KEY_COLOR, atak.getShapeColor());
    }

    public void setColor(int argb) {
        prefs.edit().putInt(KEY_COLOR, argb | 0xFF000000).apply();
    }

    /** One of {@link Shape#BASIC_LINE_STYLE_SOLID} and friends. */
    public int lineStyle() {
        int style = prefs.getInt(KEY_LINE_STYLE, atak.getStrokeStyle());
        return style == Shape.BASIC_LINE_STYLE_OUTLINED ? Shape.BASIC_LINE_STYLE_SOLID : style;
    }

    public void setLineStyle(int style) {
        prefs.edit().putInt(KEY_LINE_STYLE, style).apply();
    }

    public double strokeWeight() {
        return Double.longBitsToDouble(prefs.getLong(KEY_STROKE_WEIGHT,
                Double.doubleToLongBits(atak.getStrokeWeight())));
    }

    public void setStrokeWeight(double w) {
        prefs.edit().putLong(KEY_STROKE_WEIGHT, Double.doubleToLongBits(w)).apply();
    }

    /** The track color with ATAK's fill alpha, for a closed area. */
    public int fillColor() {
        int alpha = Math.max(0, Math.min(255, atak.getFillAlpha()));
        return (alpha << 24) | (color() & 0x00FFFFFF);
    }

    /** Index into the style choice list shown to the user. */
    public static int styleIndex(int style) {
        switch (style) {
            case Shape.BASIC_LINE_STYLE_DASHED:
                return 1;
            case Shape.BASIC_LINE_STYLE_DOTTED:
                return 2;
            default:
                // Solid, and also Outlined if ATAK's own preference carried it in.
                return 0;
        }
    }

    public static int styleFromIndex(int i) {
        switch (i) {
            case 1:
                return Shape.BASIC_LINE_STYLE_DASHED;
            case 2:
                return Shape.BASIC_LINE_STYLE_DOTTED;
            default:
                return Shape.BASIC_LINE_STYLE_SOLID;
        }
    }

    public static int weightIndex(double w) {
        if (w <= (THIN + MEDIUM) / 2)
            return 0;
        if (w <= (MEDIUM + THICK) / 2)
            return 1;
        return 2;
    }

    public static double weightFromIndex(int i) {
        return i == 0 ? THIN : i == 1 ? MEDIUM : THICK;
    }
}

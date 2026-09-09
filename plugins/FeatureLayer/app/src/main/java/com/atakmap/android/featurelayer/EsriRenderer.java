package com.atakmap.android.featurelayer;

import android.util.Base64;

import com.atakmap.coremap.log.Log;
import com.atakmap.map.layer.feature.geometry.Geometry;
import com.atakmap.map.layer.feature.geometry.GeometryCollection;
import com.atakmap.map.layer.feature.geometry.Point;
import com.atakmap.map.layer.feature.style.BasicFillStyle;
import com.atakmap.map.layer.feature.style.BasicPointStyle;
import com.atakmap.map.layer.feature.style.BasicStrokeStyle;
import com.atakmap.map.layer.feature.style.CompositeStyle;
import com.atakmap.map.layer.feature.style.IconPointStyle;
import com.atakmap.map.layer.feature.style.Style;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * An ArcGIS layer renderer (drawingInfo) translated to ATAK feature styles: simple,
 * unique value and class break renderers over simple markers, picture markers, simple
 * lines and simple fills. Anything it cannot read falls back to a plain style, so a
 * layer always draws.
 */
public class EsriRenderer {

    private static final String TAG = "FeatureLayer";

    private static class Rule {
        String label;              // the renderer's human label for the class
        String value;              // uniqueValue match (fields joined by delimiter)
        double min = Double.NaN, max = Double.NaN; // classBreaks
        Style style;
    }

    private final String type;
    private final String[] fields;
    private final String delimiter;
    private final List<Rule> rules = new ArrayList<>();
    private Style defaultStyle;
    private final Style fallback;
    /** Raw service fill color (alpha as served) per rule key, for later restyling. */
    private final java.util.Map<String, Integer> rawFills = new java.util.HashMap<>();
    private final File iconDir;
    private final int fillAlpha;

    public EsriRenderer(JSONObject drawingInfo, String geometryType, File iconDir, int fillAlpha) {
        this.iconDir = iconDir;
        this.fillAlpha = fillAlpha;
        this.fallback = fallbackFor(geometryType);
        final JSONObject r = drawingInfo == null ? null : drawingInfo.optJSONObject("renderer");
        if (r == null) {
            type = "none";
            fields = new String[0];
            delimiter = ",";
            return;
        }
        type = r.optString("type", "simple");
        delimiter = r.optString("fieldDelimiter", ",");
        final List<String> f = new ArrayList<>();
        for (String k : new String[] { "field1", "field2", "field3" })
            if (r.has(k) && !r.isNull(k) && !r.optString(k).isEmpty())
                f.add(r.optString(k));
        if (f.isEmpty() && r.has("field"))
            f.add(r.optString("field"));
        fields = f.toArray(new String[0]);
        try {
            if ("uniqueValue".equals(type)) {
                final JSONArray infos = r.optJSONArray("uniqueValueInfos");
                for (int i = 0; infos != null && i < infos.length(); i++) {
                    final JSONObject u = infos.getJSONObject(i);
                    final Rule rule = new Rule();
                    rule.value = u.optString("value", "");
                    rule.label = u.optString("label", rule.value);
                    rule.style = symbol(u.optJSONObject("symbol"), rule.value);
                    rules.add(rule);
                }
                defaultStyle = symbol(r.optJSONObject("defaultSymbol"), "default");
            } else if ("classBreaks".equals(type)) {
                final JSONArray infos = r.optJSONArray("classBreakInfos");
                double lastMax = r.optDouble("minValue", -Double.MAX_VALUE);
                for (int i = 0; infos != null && i < infos.length(); i++) {
                    final JSONObject u = infos.getJSONObject(i);
                    final Rule rule = new Rule();
                    rule.min = u.optDouble("classMinValue", lastMax);
                    rule.max = u.optDouble("classMaxValue", Double.MAX_VALUE);
                    rule.label = u.optString("label", null);
                    lastMax = rule.max;
                    rule.style = symbol(u.optJSONObject("symbol"), "break" + i);
                    rules.add(rule);
                }
                defaultStyle = symbol(r.optJSONObject("defaultSymbol"), "default");
            } else {
                defaultStyle = symbol(r.optJSONObject("symbol"), "simple");
            }
        } catch (Exception e) {
            Log.w(TAG, "renderer parse failed, using fallback styles", e);
        }
    }

    /** The renderer's readable class label for a feature ("Structure - Affected"), or null. */
    public String labelFor(JSONObject attrs) {
        final Rule rule = match(attrs);
        return rule == null ? null : rule.label;
    }

    /** The raw value the renderer matched on ("other_haz"), so a caller can tell a code from a name. */
    public String rawValueFor(JSONObject attrs) {
        final Rule rule = match(attrs);
        return rule == null ? null : rule.value;
    }

    /** The service's own fill color for this feature's class, 0 when it has none. */
    public int rawFillFor(JSONObject attrs) {
        final Rule rule = match(attrs);
        final String key = rule != null ? rule.value : (defaultStyle != null ? ("uniqueValue".equals(type) || "classBreaks".equals(type) ? "default" : "simple") : null);
        if (key == null)
            return 0;
        Integer v = rawFills.get(key);
        if (v == null && rule != null && rule.min == rule.min)
            v = rawFills.get("break" + rules.indexOf(rule));
        return v == null ? 0 : v;
    }

    /** The style for one feature's attributes. Never null. */
    public Style styleFor(JSONObject attrs) {
        final Rule rule = match(attrs);
        if (rule != null && rule.style != null)
            return rule.style;
        return defaultStyle != null ? defaultStyle : fallback;
    }

    private Rule match(JSONObject attrs) {
        if ("uniqueValue".equals(type) && fields.length > 0) {
            final StringBuilder key = new StringBuilder();
            for (int i = 0; i < fields.length; i++) {
                if (i > 0)
                    key.append(delimiter);
                key.append(attrs.isNull(fields[i]) ? "" : attrs.optString(fields[i]));
            }
            final String k = key.toString();
            for (Rule rule : rules)
                if (rule.value.equals(k))
                    return rule;
        } else if ("classBreaks".equals(type) && fields.length > 0) {
            final double v = attrs.optDouble(fields[0], Double.NaN);
            if (!Double.isNaN(v))
                for (Rule rule : rules)
                    if (v >= rule.min && v <= rule.max)
                        return rule;
        }
        return null;
    }

    /** The label ATAK draws for a point: the renderer does not say, the layer's display field does. */
    private Style symbol(JSONObject s, String key) throws Exception {
        if (s == null)
            return null;
        final String t = s.optString("type", "");
        switch (t) {
            case "esriSMS": {
                // Simple marker: a dot of the symbol's color, sized for a dense screen.
                final float px = (float) Math.max(8, s.optDouble("size", 6) * 4 / 3 * 2.5);
                return new BasicPointStyle(argb(s.optJSONArray("color"), 0xFFFFFFFF), px);
            }
            case "esriPMS": {
                if (!s.has("imageData"))
                    return null;
                final File png = new File(iconDir, "r_" + key.toLowerCase(Locale.US)
                        .replaceAll("[^a-z0-9]+", "_") + ".png");
                if (!png.isFile()) {
                    final byte[] bytes = Base64.decode(s.getString("imageData"), Base64.DEFAULT);
                    try (OutputStream out = new FileOutputStream(png)) {
                        out.write(bytes);
                    }
                }
                return new IconPointStyle(0xFFFFFFFF, "file://" + png.getAbsolutePath(), 2.0f, 0, 0, 0f, true);
            }
            case "esriSLS": {
                final int color = argb(s.optJSONArray("color"), 0xFFFFFFFF);
                final float width = (float) Math.max(2.0, s.optDouble("width", 1) * 4 / 3);
                final String style = s.optString("style", "esriSLSSolid");
                if ("esriSLSDash".equals(style) || "esriSLSDashDot".equals(style)
                        || "esriSLSDot".equals(style) || "esriSLSDashDotDot".equals(style))
                    return NwcgStyles.dashed(color, pattern(style), 3, width);
                return NwcgStyles.solid(color, width);
            }
            case "esriSFS": {
                // Feature fills sit over a base map: a service's opaque fill is meant for a web
                // map that also sets a layer opacity, so cap it at translucent here.
                final int raw = argb(s.optJSONArray("color"), 0x40FFFFFF);
                rawFills.put(key, raw);
                final int fill = translucent(raw, fillAlpha);
                final JSONObject o = s.optJSONObject("outline");
                Style stroke = o == null ? null : symbol(o, key + "_outline");
                if (fillAlpha == 0 && stroke == null)
                    stroke = new BasicStrokeStyle(argb(s.optJSONArray("color"), 0xFFFFFFFF) | 0xFF000000, 2.5f);
                final Style f = ("esriSFSNull".equals(s.optString("style")) || fillAlpha == 0) ? null : new BasicFillStyle(fill);
                if (f != null && stroke != null)
                    return new CompositeStyle(new Style[] { f, stroke });
                return f != null ? f : stroke;
            }
            default:
                return null;
        }
    }

    private static short pattern(String style) {
        switch (style) {
            case "esriSLSDot":
                return NwcgStyles.DOT;
            case "esriSLSDashDot":
            case "esriSLSDashDotDot":
                return NwcgStyles.DASH_DOT;
            default:
                return NwcgStyles.DASH;
        }
    }

    /** The color with its alpha capped at {@code maxAlpha}. */
    static int translucent(int argb, int maxAlpha) {
        final int a = (argb >>> 24) & 0xFF;
        return a <= maxAlpha ? argb : (maxAlpha << 24) | (argb & 0x00FFFFFF);
    }

    /** Esri colors are [r,g,b,a] with a in 0..255. */
    static int argb(JSONArray c, int dflt) {
        if (c == null || c.length() < 3)
            return dflt;
        final int a = c.length() > 3 ? c.optInt(3, 255) : 255;
        return (a << 24) | (c.optInt(0) << 16) | (c.optInt(1) << 8) | c.optInt(2);
    }

    private static Style fallbackFor(String geometryType) {
        if (geometryType == null)
            return new BasicPointStyle(0xFFFFFFFF, 10f);
        if (geometryType.contains("Polygon"))
            return new CompositeStyle(new Style[] { new BasicFillStyle(0x40FFFFFF), new BasicStrokeStyle(0xFFFFFFFF, 2f) });
        if (geometryType.contains("Polyline"))
            return new BasicStrokeStyle(0xFFFFFFFF, 2.5f);
        return new BasicPointStyle(0xFFFFFFFF, 10f);
    }

    static boolean isPoint(Geometry g) {
        if (g instanceof Point)
            return true;
        if (g instanceof GeometryCollection) {
            for (Geometry c : ((GeometryCollection) g).getGeometries())
                if (!(c instanceof Point))
                    return false;
            return true;
        }
        return false;
    }
}

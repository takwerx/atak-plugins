package com.atakmap.android.dozercountry.model;

import android.content.Context;
import android.graphics.Color;

import com.atakmap.coremap.log.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reads {@code assets/dozer_data.json} — the slope standard the overlay paints by.
 *
 * <p>The standard is data rather than constants so the person whose ground it is can
 * audit and correct it. That only holds if this loader stays tolerant of a file that
 * has been edited by hand: unknown keys are ignored (the asset is full of {@code
 * _comment} blocks explaining itself), and a standard that does not parse is skipped
 * with a log line rather than taking the plugin down.
 *
 * <p>Loaded once and cached. The file is a few kilobytes and never changes at runtime.
 */
public final class Standards {

    private static final String TAG = "DozerStandards";
    private static final String ASSET = "dozer_data.json";

    /** Fallback if the asset is missing or unreadable, in metres. One chain. */
    private static final double DEFAULT_WINDOW_M = 20d;

    private static final double DEFAULT_WATER_ACRES = 5d;
    private static final double SQ_M_PER_ACRE = 4046.8564224d;

    private static List<DozerStandard> cached;
    private static double cachedWindowMeters = DEFAULT_WINDOW_M;
    private static String cachedWindowLabel = "one chain (66 ft)";
    private static double cachedWaterMinAreaM2 = DEFAULT_WATER_ACRES * SQ_M_PER_ACRE;

    private Standards() {
    }

    /**
     * Every standard in the asset, in file order. Empty if the asset could not be
     * read at all — callers must handle that and say so on screen rather than
     * painting nothing.
     */
    public static synchronized List<DozerStandard> all(Context pluginContext) {
        if (cached == null)
            load(pluginContext);
        return cached;
    }

    /** The standard to paint with. The first in the file until there is a picker. */
    public static synchronized DozerStandard active(Context pluginContext) {
        final List<DozerStandard> list = all(pluginContext);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * How far from a cell the worst slope is taken, in metres.
     *
     * <p>A patch of good ground smaller than this is not called workable, which is the
     * difference between a pretty raster and something a DIVS can act on.
     */
    public static synchronized double workingWindowMeters(Context pluginContext) {
        all(pluginContext);
        return cachedWindowMeters;
    }

    /**
     * Smallest dead-flat region to call water, in square metres. Zero turns the water
     * mask off.
     */
    public static synchronized double waterMinAreaM2(Context pluginContext) {
        all(pluginContext);
        return cachedWaterMinAreaM2;
    }

    /** The working window in the words the operator uses, for the pane. */
    public static synchronized String workingWindowLabel(Context pluginContext) {
        all(pluginContext);
        return cachedWindowLabel;
    }

    private static void load(Context pluginContext) {
        cached = Collections.emptyList();
        final String text = read(pluginContext);
        if (text == null)
            return;

        try {
            final JSONObject root = new JSONObject(text);

            final JSONObject wm = root.optJSONObject("waterMask");
            if (wm != null) {
                cachedWaterMinAreaM2 = Math.max(0d,
                        wm.optDouble("minAreaAcres", DEFAULT_WATER_ACRES)) * SQ_M_PER_ACRE;
            }

            final JSONObject window = root.optJSONObject("workingWindow");
            if (window != null) {
                cachedWindowMeters = window.optDouble("meters", DEFAULT_WINDOW_M);
                cachedWindowLabel = window.optString("label", cachedWindowLabel);
            }

            final JSONArray arr = root.optJSONArray("standards");
            if (arr == null) {
                Log.e(TAG, ASSET + " has no 'standards' array");
                return;
            }

            final List<DozerStandard> out = new ArrayList<>(arr.length());
            for (int i = 0; i < arr.length(); i++) {
                final JSONObject o = arr.optJSONObject(i);
                if (o == null)
                    continue;
                try {
                    out.add(parseStandard(o));
                } catch (JSONException e) {
                    // One bad standard must not cost the others.
                    Log.e(TAG, "skipping standard " + i + " in " + ASSET, e);
                }
            }
            cached = Collections.unmodifiableList(out);
            Log.d(TAG, "loaded " + out.size() + " standard(s), window "
                    + cachedWindowMeters + " m");
        } catch (JSONException e) {
            Log.e(TAG, ASSET + " is not valid JSON", e);
        }
    }

    private static DozerStandard parseStandard(JSONObject o) throws JSONException {
        final List<String> sources = new ArrayList<>();
        final JSONArray src = o.optJSONArray("sources");
        if (src != null) {
            for (int i = 0; i < src.length(); i++)
                sources.add(src.optString(i, ""));
        }

        final List<String> caveat = new ArrayList<>();
        final JSONArray cv = o.optJSONArray("caveat");
        if (cv != null) {
            for (int i = 0; i < cv.length(); i++)
                caveat.add(cv.optString(i, ""));
        }

        final List<SlopeBand> bands = new ArrayList<>();
        final JSONArray ba = o.getJSONArray("bands");
        for (int i = 0; i < ba.length(); i++) {
            final JSONObject b = ba.getJSONObject(i);
            bands.add(new SlopeBand(
                    b.optInt("class", i + 1),
                    b.getDouble("minPercent"),
                    b.getDouble("maxPercent"),
                    b.optString("label", ""),
                    b.optString("meaning", ""),
                    argb(b.optString("argb", null), Color.GRAY)));
        }
        // The bands are compared in order by bandFor(), so a hand-edited file that
        // lists them out of order would classify wrongly and silently.
        Collections.sort(bands, new java.util.Comparator<SlopeBand>() {
            @Override
            public int compare(SlopeBand a, SlopeBand b) {
                return Double.compare(a.maxPercent, b.maxPercent);
            }
        });

        SlopeBand above = null;
        final JSONObject ab = o.optJSONObject("aboveStandard");
        if (ab != null) {
            above = new SlopeBand(0, Double.NaN, Double.POSITIVE_INFINITY,
                    ab.optString("label", ""), ab.optString("meaning", ""),
                    argb(ab.optString("argb", null), Color.DKGRAY));
        }

        DozerStandard.Limits limits = null;
        final JSONObject lm = o.optJSONObject("limits");
        if (lm != null) {
            limits = new DozerStandard.Limits(
                    lm.optDouble("sidehillPercent", Double.NaN),
                    lm.optDouble("uphillPercent", Double.NaN),
                    lm.optDouble("downhillPercent", Double.NaN));
        }

        final List<DozerStandard.Machine> machines = new ArrayList<>();
        final JSONArray ma = o.optJSONArray("machines");
        if (ma != null) {
            for (int i = 0; i < ma.length(); i++) {
                final JSONObject m = ma.optJSONObject(i);
                if (m == null)
                    continue;
                machines.add(new DozerStandard.Machine(
                        m.optString("type", ""), m.optString("name", ""),
                        m.optInt("minHorsepower", 0), m.optString("examples", "")));
            }
        }

        return new DozerStandard(
                o.optString("id", "standard"), o.optString("name", "Standard"),
                o.optString("legend", o.optString("name", "Standard")),
                sources, caveat, bands, above, limits, machines);
    }

    /**
     * Parses "#AARRGGBB" from the asset. Falls back rather than throwing: a mistyped
     * color should give a visible wrong color to report, not an overlay that refuses
     * to draw.
     */
    private static int argb(String s, int fallback) {
        if (s == null || s.isEmpty())
            return fallback;
        try {
            return Color.parseColor(s);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "bad color '" + s + "' in " + ASSET);
            return fallback;
        }
    }

    private static String read(Context pluginContext) {
        InputStream in = null;
        try {
            in = pluginContext.getAssets().open(ASSET);
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0)
                out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (IOException e) {
            Log.e(TAG, "cannot read " + ASSET, e);
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // closing a read-only asset stream
                }
            }
        }
    }
}

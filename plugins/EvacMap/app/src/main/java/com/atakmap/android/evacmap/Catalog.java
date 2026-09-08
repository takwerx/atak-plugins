package com.atakmap.android.evacmap;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * {@code catalog.json}: every evacuation service the plugin knows, one entry per layer.
 *
 * <p>Read from the depot host first, with the copy built into the APK as the fallback,
 * so a source can be added, corrected or dropped without a plugin release. The plugin
 * never assumes a fixed list: the pane shows whatever the catalog holds, grouped by
 * state, statewide sources ahead of county ones.
 */
public final class Catalog {

    /** The format this build understands. A newer catalog is refused, not guessed at. */
    public static final int SUPPORTED_FORMAT = 1;

    public static final class Source {
        public final String id;
        public final String title;
        public final String publisher;
        /** Two-letter state or province code, upper case. */
        public final String st;
        /** County or city; empty for a statewide source. */
        public final String county;
        /** {@code live} (status changes by the hour), {@code static} (zones that rarely change) or {@code support} (shelters, routes). */
        public final String kind;
        /** The FeatureServer base, no trailing slash. */
        public final String url;
        public final int layer;
        public final String where;
        /** The field holding the zone's status text, or empty when the renderer's class label is it. */
        public final String statusField;
        /** The field holding the zone's name, or empty for the layer's display field. */
        public final String nameField;
        public final int refreshMin;
        /** Feature count when the catalog was built; what the row promises before it is turned on. */
        public final int features;
        /** Cap per refresh, 0 for none. */
        public final int maxFeatures;
        /**
         * Server-side generalization tolerance in degrees, 0 for none. Florida's
         * statewide hurricane zones are 175 MB for 200 polygons as served; at a few
         * meters of tolerance they are a download a phone can make.
         */
        public final double simplify;
        /** South, west, north, east at catalog time, or null. */
        public final double[] bounds;
        public final String note;

        Source(JSONObject o) throws JSONException {
            id = o.getString("id");
            title = o.optString("title", id);
            publisher = o.optString("publisher", "");
            st = o.optString("st", "").toUpperCase(Locale.US);
            county = o.optString("co", "");
            kind = o.optString("kind", "live");
            url = o.getString("url").replaceAll("/+$", "");
            layer = o.optInt("layer", 0);
            where = o.optString("where", "1=1");
            statusField = o.optString("status_field", "");
            nameField = o.optString("name_field", "");
            refreshMin = o.optInt("refresh_min", "live".equals(kind) ? 5 : 1440);
            features = o.optInt("features", 0);
            maxFeatures = o.optInt("max_features", 0);
            simplify = o.optDouble("simplify", 0);
            note = o.optString("note", "");
            final JSONArray b = o.optJSONArray("bounds");
            bounds = b != null && b.length() == 4
                    ? new double[] { b.getDouble(0), b.getDouble(1), b.getDouble(2), b.getDouble(3) }
                    : null;
        }

        public boolean statewide() {
            return county.isEmpty();
        }

        /** "Active Evacuation Zones" for a statewide source, "Sonoma: Emergency Zones" for a county. */
        public String displayTitle() {
            return statewide() ? title : county + ": " + title;
        }

        public boolean contains(double lat, double lon) {
            return bounds != null && lat >= bounds[0] && lat <= bounds[2]
                    && lon >= bounds[1] && lon <= bounds[3];
        }

        /** A file-system-safe name for this source's store. */
        public String fileKey() {
            return id.toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", "_");
        }
    }

    public final int format;
    public final String generated;
    public final List<Source> sources = new ArrayList<>();

    public Catalog(JSONObject o) throws JSONException {
        format = o.optInt("format", 0);
        generated = o.optString("generated", "");
        final JSONArray arr = o.optJSONArray("sources");
        for (int i = 0; arr != null && i < arr.length(); i++)
            sources.add(new Source(arr.getJSONObject(i)));
    }

    /** Every state code in the catalog, sorted. */
    public List<String> states() {
        final List<String> out = new ArrayList<>();
        for (Source s : sources)
            if (!s.st.isEmpty() && !out.contains(s.st))
                out.add(s.st);
        Collections.sort(out);
        return out;
    }

    /** One state's sources: statewide first, then by county, then by title. */
    public List<Source> forState(String st) {
        final List<Source> out = new ArrayList<>();
        for (Source s : sources)
            if (s.st.equalsIgnoreCase(st))
                out.add(s);
        Collections.sort(out, new Comparator<Source>() {
            @Override
            public int compare(Source a, Source b) {
                if (a.statewide() != b.statewide())
                    return a.statewide() ? -1 : 1;
                final int c = a.county.compareToIgnoreCase(b.county);
                return c != 0 ? c : a.title.compareToIgnoreCase(b.title);
            }
        });
        return out;
    }

    public Source byId(String id) {
        for (Source s : sources)
            if (s.id.equals(id))
                return s;
        return null;
    }

    /** The state whose sources cover a point, statewide sources first; null when none does. */
    public String stateAt(double lat, double lon) {
        for (Source s : sources)
            if (s.statewide() && s.contains(lat, lon))
                return s.st;
        for (Source s : sources)
            if (s.contains(lat, lon))
                return s.st;
        return null;
    }
}

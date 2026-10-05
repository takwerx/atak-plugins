
package com.atakmap.android.atmosphere.overlay;

import android.content.Context;
import android.os.Looper;

import com.atakmap.android.features.FeatureDataStoreMapOverlay;
import com.atakmap.android.overlay.MapOverlay;
import com.atakmap.android.overlay.MapOverlayParent;
import com.atakmap.android.hierarchy.HierarchyListFilter;
import com.atakmap.android.hierarchy.HierarchyListItem;
import com.atakmap.android.hierarchy.action.Actions;
import android.widget.BaseAdapter;
import com.atakmap.android.menu.PluginMenuParser;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.map.layer.feature.AttributeSet;
import com.atakmap.map.layer.feature.FeatureSetCursor;
import com.atakmap.map.layer.feature.Feature;
import com.atakmap.map.layer.feature.FeatureDataStore2;
import com.atakmap.map.layer.feature.FeatureLayer3;
import com.atakmap.map.layer.feature.FeatureSet;
import com.atakmap.map.layer.feature.datastore.FeatureSetDatabase2;
import com.atakmap.map.layer.feature.geometry.LineString;
import com.atakmap.map.layer.feature.geometry.Point;
import com.atakmap.map.layer.feature.geometry.Polygon;
import com.atakmap.map.layer.feature.style.BasicFillStyle;
import com.atakmap.map.layer.feature.style.BasicStrokeStyle;
import com.atakmap.map.layer.feature.style.CompositeStyle;
import com.atakmap.map.layer.feature.style.IconPointStyle;
import com.atakmap.map.layer.feature.style.LabelPointStyle;
import com.atakmap.map.layer.feature.style.Style;
import com.atakmap.android.features.FeatureDataStoreDeepMapItemQuery;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Somebody else's GIS data as a read-only map layer, not as drawings: the storms,
 * the air quality contours, and whatever joins them. One instance per layer, each
 * with its own store and its own entry in Overlay Manager.
 *
 * <p>Written for the storms and parameterized when air quality became the second
 * layer to need it, rather than copied: the ordering rules below cost a day to find
 * and belong in one place.
 *
 * <p>They were {@code DrawingShape}s and {@code Marker}s, which is how a plugin makes
 * things the operator <em>owns</em>: a radial menu offering color, rename, delete and
 * send-to, and a row in the object list. That is wrong for somebody else's advisory
 * (operator, 2026-09-23: "i cant change shape color etc, it just gives me details ...
 * like the feature layer map thing"). An NHC cone is reference data drawn on your map,
 * not a shape you drew, and the difference is not cosmetic: it decides whether you can
 * accidentally recolor the forecast, or send "Polo's cone" to a crew as a drawing.
 *
 * <p>So this is the Feature Layer shape, carried forward: a {@link FeatureSetDatabase2}
 * of styled features, a {@link FeatureLayer3} over it on the vector stack, and a
 * {@link FeatureDataStoreDeepMapItemQuery} so a tap produces a transient item carrying
 * the advisory's own attributes for the details pane. Nothing in the store is editable,
 * because nothing in it is an object.
 *
 * <p>One feature set per storm and product ("Hurricane Polo - Cone"), so Overlay
 * Manager lists them the way it lists any other GIS layer and ATAK's own visibility
 * works on each.
 */
final class AtmosphereFeatures {

    /** The provider column, and what Overlay Manager groups these under. */
    private static final String PROVIDER = "Atmosphere";

    /**
     * Drawn at every zoom. minResolution is the COARSEST meters-per-pixel a set draws
     * at, so the permissive value is the large one; 0 there means it never draws at
     * all, which is the opposite of what it reads like.
     */
    /**
     * Coarse enough for any view of a storm, but a REAL number.
     *
     * <p>It was Double.MAX_VALUE, which draws at every zoom and looks right. ATAK
     * converts a feature set's resolution into a level of detail internally, though,
     * and MAX_VALUE has no sane level of detail to become: the features drew and the
     * hit test never reached the layer at all, at any zoom, dead on a line (XCover,
     * 2026-09-23). Feature Layer gates its sets at real resolutions -- 120 m/px for
     * points, 400 for lines -- and its features are tappable. 100 km/px is coarser
     * than the whole globe on this screen, so nothing is gated out, and it is a
     * number the conversion can carry.
     */
    private static final double MIN_GSD = 100_000d;
    private static final double MAX_GSD = 0d;

    private final MapView mapView;
    private final Context pluginContext;
    private final String tag;
    /** The layer's name in Overlay Manager and on the vector stack: "Hurricanes". */
    private final String layerName;
    /** The store's file under tools/atmosphere/. */
    private final String storeName;
    /** The feature sets' type column. */
    private final String type;
    /** True for the storms only: an older build of that layer left drawings behind. */
    private final boolean sweepOldStormDrawings;

    private File storeFile;
    private FeatureSetDatabase2 store;
    private FeatureLayer3 layer;
    private FeatureDataStoreMapOverlay overlay;
    /** The one "Atmosphere" row in ATAK's Overlay Manager every layer sits under. */
    static final String PARENT_ID = "atmosphere";
    private static final String PARENT_NAME = "Atmosphere";
    private MapOverlayParent parent;
    private final Map<String, Long> sets = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * Feature sets already in the store when this instance opened it, retired by the
     * first rewrite.
     *
     * <p>The store file outlives the plugin instance and {@link #sets} does not, so a
     * reinstall used to start with nothing to delete: the previous instance's
     * features stayed on the map forever and the new instance drew its own on top.
     * Every reinstall added a layer. The operator found three stale Wheeler Incident
     * discs stacked under the one that was current, all at the same coordinates
     * (2026-09-25: "now its listed 4 times").
     */
    /** Held by a rewrite on the worker and by detach, so a store is never closed mid-write. */
    private final Object lock = new Object();

    /** One thing to draw in a {@link #rewrite}: a shape, its set, its style, its fields. */
    static final class Drawn {
        final String setName, name;
        final com.atakmap.map.layer.feature.geometry.Geometry geometry;
        final Style style;
        final AttributeSet attrs;
        /**
         * The zoom band this feature's set draws in, coarsest and finest meters per
         * pixel. Taken from the first feature of a set. The defaults draw at every
         * zoom; a layer that wants a gate -- stations that would be a wall of icons
         * on a state-wide view, or labels that only earn their space close in --
         * names its own.
         */
        final double minGsd, maxGsd;

        Drawn(String setName, String name, com.atakmap.map.layer.feature.geometry.Geometry geometry,
                Style style, AttributeSet attrs) {
            this(setName, name, geometry, style, attrs, MIN_GSD, MAX_GSD);
        }

        Drawn(String setName, String name, com.atakmap.map.layer.feature.geometry.Geometry geometry,
                Style style, AttributeSet attrs, double minGsd, double maxGsd) {
            this.minGsd = minGsd;
            this.maxGsd = maxGsd;
            this.setName = setName;
            this.name = name;
            this.geometry = geometry;
            this.style = style;
            this.attrs = attrs;
        }
    }

    /**
     * A point drawn as an already-composed icon, as a style for {@link #rewrite}.
     * The label is inside the bitmap, so the composite carries an EMPTY label style
     * beside it: a named feature otherwise draws its name as well, and ATAK's Select
     * Item chooser reads that name, so it cannot simply be left off.
     */
    static Style icon(String iconUri, int width, int height) {
        return icon(iconUri, width, height, 0f, 0f);
    }

    /**
     * An icon placed by a pixel offset rather than by its own middle.
     *
     * <p>{@code alignX}/{@code alignY} are enums by sign -- left, center, right --
     * and cannot place anything; the constructor that takes real {@code offsetX} and
     * {@code offsetY} floats can (read from IconPointStyle, ATAK 5.8.0.3).
     *
     * <p>Which matters because a centered bitmap has to be big enough for everything
     * it might contain in any direction -- a wind barb at any angle, a label on
     * whichever side is free -- so it ends up mostly empty. That is invisible on the
     * map and ruinous anywhere the icon is scaled into a small box, like ATAK's
     * Select Item chooser, where the symbol became a few pixels across. With an
     * offset the bitmap can be trimmed to its own ink and still land on its point.
     */
    static Style icon(String iconUri, int width, int height, float offsetX, float offsetY) {
        return icon(iconUri, width, height, offsetX, offsetY, false);
    }

    /**
     * An icon that turns with the map, for a mark whose direction is a bearing --
     * a wind barb. It must be centered on its point: the renderer turns the bitmap
     * about its own middle and applies the offset on screen, unturned, so an offset
     * icon that turns swings off its point as the map spins.
     */
    static Style turning(String iconUri, int width, int height) {
        return icon(iconUri, width, height, 0f, 0f, true);
    }

    /**
     * Every other icon stays level when the map is spun, the way ATAK's own marker
     * labels do (operator, 2026-10-05: "all labels for all features should rotate").
     * They were drawn turning with the map, so a name read sideways at 90 degrees and
     * upside down at 180, and an offset icon slid off its point as it turned.
     */
    private static Style icon(String iconUri, int width, int height, float offsetX,
            float offsetY, boolean turnsWithMap) {
        return new CompositeStyle(new Style[] {
                new IconPointStyle(0xFFFFFFFF, iconUri, width, height,
                        offsetX, offsetY, 0, 0, 0f, turnsWithMap),
                new LabelPointStyle("", 0x00FFFFFF, 0x00000000,
                        LabelPointStyle.ScrollMode.DEFAULT) });
    }

    /** A point geometry, for building a {@link Drawn} off the worker. */
    static com.atakmap.map.layer.feature.geometry.Geometry point(double lat, double lon) {
        return new Point(lon, lat);
    }

    /** A line's geometry, for building a {@link Drawn} off the worker. */
    static com.atakmap.map.layer.feature.geometry.Geometry path(GeoPoint[] pts) {
        return ring(pts);
    }

    /** A closed shape's geometry, for building a {@link Drawn} off the worker. */
    static com.atakmap.map.layer.feature.geometry.Geometry polygon(GeoPoint[] pts) {
        return new Polygon(ring(pts));
    }

    /**
     * A line's style, with its name drawn along it when {@code labelled}.
     *
     * <p>ATAK does not label a line feature from its name the way it labels a point,
     * so a LabelPointStyle in the composite is what puts text on a line.
     */
    static Style stroke(String name, int color, float weight, boolean labelled) {
        final Style stroked = new BasicStrokeStyle(color, weight);
        if (!labelled || name == null || name.isEmpty())
            return stroked;
        return new CompositeStyle(new Style[] { stroked,
                new LabelPointStyle(name, 0xFFFFFFFF, 0x99000000,
                        LabelPointStyle.ScrollMode.DEFAULT) });
    }

    /** An area's style: a faint fill under a full-color edge. */
    static Style area(int stroke, float weight, int fill) {
        return new CompositeStyle(new Style[] {
                new BasicFillStyle(fill), new BasicStrokeStyle(stroke, weight) });
    }

    /**
     * An area's style with its name drawn in it. ATAK does not label a polygon from
     * its name any more than a line, so a LabelPointStyle in the composite is what
     * says what a colored area is; without it three days of outlook bands and a
     * gray avalanche zone were unlabeled shapes the operator could only name by
     * tapping (2026-09-26: "i have no idea what is what").
     */
    static Style area(int stroke, float weight, int fill, String label) {
        if (label == null || label.isEmpty())
            return area(stroke, weight, fill);
        return new CompositeStyle(new Style[] {
                new BasicFillStyle(fill), new BasicStrokeStyle(stroke, weight),
                new LabelPointStyle(label, 0xFFFFFFFF, 0x99000000,
                        LabelPointStyle.ScrollMode.DEFAULT) });
    }

    /**
     * An area whose label carries its own colors and zoom band rather than the
     * edge's: a rating's tile, the way the issuing site prints it (SAWTI, the
     * operator, 2026-10-01: "can the label ... change color based on the rating?").
     * The background must be opaque; that is how the center label tells it from
     * the default. {@code labelMaxResolution} is the coarsest map resolution, meters
     * per pixel, the label still draws at; ATAK's default is 14, which hid a label
     * on a zone a hundred miles across until the map was a few miles wide.
     */
    static Style area(int stroke, float weight, int fill, String label, int labelText,
            int labelBackground, double labelMaxResolution) {
        return new CompositeStyle(new Style[] {
                new BasicFillStyle(fill), new BasicStrokeStyle(stroke, weight),
                new LabelPointStyle(label, labelText, 0xFF000000 | labelBackground,
                        LabelPointStyle.ScrollMode.DEFAULT, 0f, 0, 0, 0f, false,
                        labelMaxResolution) });
    }

    AtmosphereFeatures(MapView mapView, Context pluginContext, String logTag, String layerName,
            String storeName, String type, boolean sweepOldStormDrawings) {
        this.mapView = mapView;
        this.pluginContext = pluginContext;
        this.tag = logTag;
        this.layerName = layerName;
        this.storeName = storeName;
        this.type = type;
        this.sweepOldStormDrawings = sweepOldStormDrawings;
    }

    /**
     * Remove the store and its journals, so a session never inherits old features.
     *
     * <p>This is the one structural difference from IPAWS and Feature Layer, whose
     * stores open holding the last session's features. Theirs can: they write sets
     * the store hands back when asked. This one could not -- see {@link #attach}.
     */
    private void deleteStoreFile() {
        if (storeFile == null)
            return;
        int gone = 0;
        for (String suffix : new String[] { "", "-journal", "-wal", "-shm" }) {
            final File f = new File(storeFile.getPath() + suffix);
            if (f.isFile() && f.delete())
                gone++;
        }
        if (gone > 0)
            Log.d(tag, layerName + ": removed " + gone + " old store file(s)");
    }

    /**
     * One row per feature in ATAK's "Select Item" list. A rewrite inserts the fresh
     * sets before it deletes the old ones, so the map is never momentarily empty --
     * and for the length of one pass the store holds every point twice. A tap that
     * lands inside that pass returns both copies, and the chooser listed one buoy
     * twice with the same name and position (operator, 2026-09-26, 40 ms before the
     * pass logged). Copies are told apart by name and place, since a rewrite gives
     * the fresh copy a new feature id; the newer one is kept, the older removed from
     * the set in place.
     */
    private static void onePerPlace(java.util.SortedSet<MapItem> hits,
            com.atakmap.coremap.maps.coords.GeoPoint tap) {
        if (hits == null || hits.isEmpty())
            return;
        // A polygon's center label is a point of its own in the store; the polygon
        // is the thing to pick, so its label never makes a row.
        final java.util.Iterator<MapItem> it = hits.iterator();
        while (it.hasNext())
            if (it.next().getMetaBoolean("_labelOnly", false))
                it.remove();
        if (hits.size() < 2)
            return;
        final Map<String, MapItem> keep = new HashMap<>();
        final java.util.List<MapItem> dropped = new java.util.ArrayList<>();
        for (MapItem m : hits) {
            final String key = placeKey(m);
            final MapItem have = keep.get(key);
            if (have == null) {
                keep.put(key, m);
                continue;
            }
            // Same name, same place: the fresher copy from a rewrite in progress.
            // Same name only (a layer that asked for one row per name): the one
            // under the finger. The river model cuts a creek into reaches a few
            // hundred meters long, and a tap on Galisteo Creek listed four rows
            // that read the same (XCover, 2026-09-27).
            final boolean byName = m.getMetaBoolean("_oneRowPerName", false);
            final boolean better = byName && tap != null
                    ? distance(m, tap) < distance(have, tap)
                    : m.getMetaLong("featureid", 0) > have.getMetaLong("featureid", 0);
            if (better) {
                dropped.add(have);
                keep.put(key, m);
            } else {
                dropped.add(m);
            }
        }
        hits.removeAll(dropped);
    }

    /**
     * A layer's own say in what a tap hit. ATAK's hit test on a polygon feature
     * returns the neighbors whose box holds the tap as well: a tap in the middle of
     * the San Gabriel Valley zone listed the Orange County zone beside it (XCover,
     * 2026-09-28). A layer that knows its shapes answers whether the tap is really
     * inside one.
     */
    interface HitFilter {
        boolean keep(MapItem item, com.atakmap.coremap.maps.coords.GeoPoint tap);
    }

    private volatile HitFilter hitFilter;

    void setHitFilter(HitFilter f) {
        hitFilter = f;
    }

    /** Drop the hits the layer says are not under the tap, but never all of them. */
    private void narrow(java.util.SortedSet<MapItem> hits,
            com.atakmap.coremap.maps.coords.GeoPoint tap) {
        final HitFilter f = hitFilter;
        if (f == null || hits == null || tap == null || hits.size() < 2)
            return;
        final java.util.List<MapItem> out = new java.util.ArrayList<>();
        for (MapItem m : hits)
            try {
                if (!f.keep(m, tap))
                    out.add(m);
            } catch (RuntimeException e) {
                // a filter that fails keeps the hit
            }
        if (!out.isEmpty() && out.size() < hits.size())
            hits.removeAll(out);
    }

    /** Meters from the tap to where the item says it is; far when it does not say. */
    private static double distance(MapItem m, com.atakmap.coremap.maps.coords.GeoPoint tap) {
        final com.atakmap.coremap.maps.coords.GeoPoint at = placeOf(m);
        return at == null ? Double.MAX_VALUE : at.distanceTo(tap);
    }

    private static com.atakmap.coremap.maps.coords.GeoPoint placeOf(MapItem m) {
        if (m instanceof com.atakmap.android.maps.PointMapItem)
            return ((com.atakmap.android.maps.PointMapItem) m).getPoint();
        if (m instanceof com.atakmap.android.maps.Shape) {
            final com.atakmap.coremap.maps.coords.GeoPointMetaData c =
                    ((com.atakmap.android.maps.Shape) m).getCenter();
            return c == null ? null : c.get();
        }
        return null;
    }

    /**
     * The feature's name and, for a point or a shape, where it is to five decimals;
     * the name alone for a feature that asked for one row per name.
     */
    private static String placeKey(MapItem m) {
        if (m.getMetaBoolean("_oneRowPerName", false))
            return m.getMetaString("title", m.getUID());
        com.atakmap.coremap.maps.coords.GeoPoint at = null;
        if (m instanceof com.atakmap.android.maps.PointMapItem) {
            at = ((com.atakmap.android.maps.PointMapItem) m).getPoint();
        } else if (m instanceof com.atakmap.android.maps.Shape) {
            final com.atakmap.coremap.maps.coords.GeoPointMetaData c =
                    ((com.atakmap.android.maps.Shape) m).getCenter();
            at = c == null ? null : c.get();
        }
        return m.getMetaString("title", m.getUID()) + "@" + (at == null ? ""
                : String.format(java.util.Locale.US, "%.5f,%.5f", at.getLatitude(), at.getLongitude()));
    }

    /**
     * Where a polygon's label goes: a point INSIDE it. The envelope's center is
     * not one for a multi-zone warning (a 19-zone Flood Watch's center fell in a
     * gap between zones, and "Flood Watch" sat over ground the watch did not
     * cover; operator, 2026-09-26) or for a concave shape. So: the polygon with
     * the largest envelope, and on it the midpoint of the widest run of inside at
     * its middle latitude, which is inside by construction. {lat, lon}, or the
     * envelope center when the shape is degenerate.
     */
    static double[] labelPoint(com.atakmap.map.layer.feature.geometry.Geometry g) {
        Polygon biggest = null;
        double biggestArea = -1;
        final java.util.List<com.atakmap.map.layer.feature.geometry.Geometry> stack = new java.util.ArrayList<>();
        stack.add(g);
        while (!stack.isEmpty()) {
            final com.atakmap.map.layer.feature.geometry.Geometry x = stack.remove(stack.size() - 1);
            if (x instanceof com.atakmap.map.layer.feature.geometry.GeometryCollection) {
                stack.addAll(((com.atakmap.map.layer.feature.geometry.GeometryCollection) x).getGeometries());
            } else if (x instanceof Polygon) {
                final com.atakmap.map.layer.feature.geometry.Envelope e = x.getEnvelope();
                final double area = e == null ? 0 : (e.maxX - e.minX) * (e.maxY - e.minY);
                if (area > biggestArea) {
                    biggestArea = area;
                    biggest = (Polygon) x;
                }
            }
        }
        if (biggest == null)
            return com.atakmap.android.atmosphere.data.GeoJson.center(g);
        final com.atakmap.map.layer.feature.geometry.Envelope e = biggest.getEnvelope();
        final LineString ring = biggest.getExteriorRing();
        if (e == null || ring == null || ring.getNumPoints() < 3)
            return com.atakmap.android.atmosphere.data.GeoJson.center(g);
        final double midLat = (e.minY + e.maxY) / 2;
        // Every crossing of the middle latitude, sorted: inside runs are between
        // pairs. The holes count too -- WPC cuts a Moderate area out of the Slight
        // polygon around it as an interior ring, and walking the outer ring alone put
        // "Slight" in the hole, on top of "Moderate" (operator, 2026-09-26).
        final java.util.List<Double> xs = new java.util.ArrayList<>();
        final java.util.List<LineString> rings = new java.util.ArrayList<>();
        rings.add(ring);
        final java.util.Collection<LineString> holes = biggest.getInteriorRings();
        if (holes != null)
            rings.addAll(holes);
        for (LineString r : rings) {
            final int n = r.getNumPoints();
            for (int i = 0, j = n - 1; i < n; j = i++) {
                final double xi = r.getX(i), yi = r.getY(i), xj = r.getX(j), yj = r.getY(j);
                if ((yi > midLat) != (yj > midLat))
                    xs.add(xj + (midLat - yj) * (xi - xj) / (yi - yj));
            }
        }
        java.util.Collections.sort(xs);
        double bestW = -1, bestMid = (e.minX + e.maxX) / 2;
        for (int i = 0; i + 1 < xs.size(); i += 2) {
            final double w = xs.get(i + 1) - xs.get(i);
            if (w > bestW) {
                bestW = w;
                bestMid = (xs.get(i) + xs.get(i + 1)) / 2;
            }
        }
        return new double[] { midLat, bestMid };
    }

    /** The label text of a polygon's style, or null when it is a point, a line or unlabeled. */
    private static String centerLabelOf(com.atakmap.map.layer.feature.geometry.Geometry g, Style s) {
        if (!(g instanceof Polygon) && !(g instanceof com.atakmap.map.layer.feature.geometry.GeometryCollection))
            return null;
        if (g instanceof com.atakmap.map.layer.feature.geometry.GeometryCollection) {
            // A collection of polygons (a multi-polygon) is labeled; one of lines is not.
            final com.atakmap.map.layer.feature.geometry.GeometryCollection gc =
                    (com.atakmap.map.layer.feature.geometry.GeometryCollection) g;
            boolean anyPolygon = false;
            for (com.atakmap.map.layer.feature.geometry.Geometry child : gc.getGeometries())
                if (child instanceof Polygon)
                    anyPolygon = true;
            if (!anyPolygon)
                return null;
        }
        if (s instanceof LabelPointStyle)
            return ((LabelPointStyle) s).getText();
        if (s instanceof CompositeStyle) {
            final CompositeStyle c = (CompositeStyle) s;
            for (int i = 0; i < c.getNumStyles(); i++)
                if (c.getStyle(i) instanceof LabelPointStyle) {
                    final String t = ((LabelPointStyle) c.getStyle(i)).getText();
                    return t == null || t.isEmpty() ? null : t;
                }
        }
        return null;
    }

    /** The label inside a style, or null. */
    private static LabelPointStyle labelStyleOf(Style s) {
        if (s instanceof LabelPointStyle)
            return (LabelPointStyle) s;
        if (s instanceof CompositeStyle) {
            final CompositeStyle c = (CompositeStyle) s;
            for (int i = 0; i < c.getNumStyles(); i++)
                if (c.getStyle(i) instanceof LabelPointStyle)
                    return (LabelPointStyle) c.getStyle(i);
        }
        return null;
    }

    /** Perceived brightness 0-255 of an ARGB color. */
    private static int luminance(int argb) {
        final int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        return (r * 299 + g * 587 + b * 114) / 1000;
    }

    private static Style withoutLabel(Style s) {
        if (!(s instanceof CompositeStyle))
            return s;
        final CompositeStyle c = (CompositeStyle) s;
        final java.util.List<Style> kept = new java.util.ArrayList<>();
        for (int i = 0; i < c.getNumStyles(); i++)
            if (!(c.getStyle(i) instanceof LabelPointStyle))
                kept.add(c.getStyle(i));
        return kept.size() == 1 ? kept.get(0) : new CompositeStyle(kept.toArray(new Style[0]));
    }

    /** A white rounded square, composed once, for the chooser to tint. */
    private static volatile String swatchUri;

    private static void ensureSwatch() {
        if (swatchUri != null)
            return;
        synchronized (AtmosphereFeatures.class) {
            if (swatchUri != null)
                return;
            try {
                final File dir = com.atakmap.android.atmosphere.compat.GeneratedFiles.root();
                if (dir == null)
                    return;
                if (!dir.isDirectory())
                    //noinspection ResultOfMethodCallIgnored
                    dir.mkdirs();
                final File out = new File(dir, "swatch_v1.png");
                if (!out.isFile()) {
                    final android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                            64, 64, android.graphics.Bitmap.Config.ARGB_8888);
                    final android.graphics.Canvas c = new android.graphics.Canvas(bmp);
                    final android.graphics.Paint p = new android.graphics.Paint(
                            android.graphics.Paint.ANTI_ALIAS_FLAG);
                    p.setColor(0xFFFFFFFF);
                    c.drawRoundRect(new android.graphics.RectF(4, 4, 60, 60), 8, 8, p);
                    final java.io.FileOutputStream o = new java.io.FileOutputStream(out);
                    try {
                        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, o);
                    } finally {
                        o.close();
                    }
                    bmp.recycle();
                }
                swatchUri = "file://" + out.getAbsolutePath();
            } catch (Exception e) {
                Log.w("AtmosphereFeatures", "no chooser swatch", e);
            }
        }
    }

    /** The stroke color of a style, the fill's if it has no stroke, white otherwise. */
    private static int strokeColorOf(Style s) {
        if (s instanceof BasicStrokeStyle)
            return ((BasicStrokeStyle) s).getColor();
        if (s instanceof CompositeStyle) {
            final CompositeStyle c = (CompositeStyle) s;
            for (int i = 0; i < c.getNumStyles(); i++)
                if (c.getStyle(i) instanceof BasicStrokeStyle)
                    return ((BasicStrokeStyle) c.getStyle(i)).getColor();
            for (int i = 0; i < c.getNumStyles(); i++)
                if (c.getStyle(i) instanceof BasicFillStyle)
                    return ((BasicFillStyle) c.getStyle(i)).getColor() | 0xFF000000;
        }
        if (s instanceof BasicFillStyle)
            return ((BasicFillStyle) s).getColor() | 0xFF000000;
        return 0xFFFFFFFF;
    }

    /** Every feature set id in the store, as the store reports it. */
    private java.util.List<Long> existingSetIds() {
        final java.util.List<Long> ids = new java.util.ArrayList<>();
        if (store == null)
            return ids;
        FeatureSetCursor c = null;
        try {
            // Named by provider and type, NOT the default parameters: those hand
            // back a handful of the sets the file actually holds -- six of sixty,
            // measured -- so a delete driven by them clears a fraction each pass.
            final FeatureDataStore2.FeatureSetQueryParameters ours =
                    new FeatureDataStore2.FeatureSetQueryParameters();
            ours.providers = java.util.Collections.singleton(PROVIDER);
            ours.types = java.util.Collections.singleton(type);
            c = store.queryFeatureSets(ours);
            while (c.moveToNext())
                ids.add(c.get().getId());
        } catch (Exception e) {
            Log.w(tag, "could not read existing sets", e);
        } finally {
            if (c != null)
                try {
                    c.close();
                } catch (Exception ignored) {
                    // nothing to do
                }
        }
        return ids;
    }

    /**
     * Open the store off the main thread, then put the layer on the map. Safe to call
     * twice.
     *
     * <p>Opening a {@code FeatureSetDatabase2} builds its tables and creates its
     * indices. One is not much; <b>nine layers starting together is an ANR</b>, and
     * that is exactly what plugin load does -- every overlay's start() in a row, on
     * the thread ATAK is loading the plugin on. The operator hit it enabling the
     * plugin from Package Management (2026-09-25); the trace is FeatureSetDatabase2's
     * constructor under AtmosphereFeatures.attach under Atmosphere.onStart on "main".
     * Emptying the file first, which this now does, means every one of those opens
     * builds from nothing every time rather than sometimes.
     *
     * <p>So the database work goes to one shared background thread -- shared, so nine
     * layers queue behind each other instead of nine threads fighting over the same
     * disk -- and only the map registration comes back to main, which is cheap and
     * has to be there. Anything that touches the store afterwards is worker-only by
     * contract and waits on {@link #ready}, so the wait lands on a worker and never
     * on the thread drawing the map.
     */
    void attach() {
        synchronized (lock) {
            if (store != null || attaching)
                return;
            attaching = true;
        }
        ATTACH.execute(new Runnable() {
            @Override
            public void run() {
                attachStore();
            }
        });
    }

    /** The one background thread every layer's store is opened on. */
    private static final java.util.concurrent.ExecutorService ATTACH =
            java.util.concurrent.Executors.newSingleThreadExecutor(
                    new java.util.concurrent.ThreadFactory() {
                        @Override
                        public Thread newThread(Runnable r) {
                            final Thread t = new Thread(r, "atmosphere-attach");
                            t.setDaemon(true);
                            return t;
                        }
                    });

    /** Called when the plugin stops: the static attach thread would otherwise pin this generation. */
    static void shutdownAttach() {
        ATTACH.shutdownNow();
    }

    /** Counts down once the store is usable, whether or not the layer is on the map. */
    private final java.util.concurrent.CountDownLatch ready =
            new java.util.concurrent.CountDownLatch(1);
    /**
     * Counts down once the layer is on the map, or never will be. A write that lands
     * between the store opening and the layer's registration is one the layer never
     * shows: the fire weather outlook's first fetch (six small answers, one second)
     * beat the registration by two seconds on 2026-09-26, "drew 1 areas" went into
     * the store, nothing drew, and the next rewrite found "0 of 0 old sets" to
     * clear -- the same unenumerable-set shape as the sixty-set file. The slow
     * layers never hit it because their first answer takes longer than the hop.
     */
    private final java.util.concurrent.CountDownLatch registered =
            new java.util.concurrent.CountDownLatch(1);
    private boolean attaching;
    private FeatureDataStoreDeepMapItemQuery query;
    private volatile boolean detached;

    /**
     * Wait for the store, on a worker.
     *
     * <p>Bounded: if the open failed there is nothing to wait for, and a layer that
     * hangs its own worker forever is worse than one that misses a refresh.
     *
     * <p>Refused on the main thread, loudly. {@link #registered} is released by
     * {@link #registerOnMap}, a Runnable posted to main, so main waiting for it is
     * main waiting for itself until the timeout. Air quality cleared its store from
     * start() on main and froze ATAK for 20 s on every start; a touch in that window
     * was "ATAK isn't responding" (Windows Dell, 2026-09-27, twice). The stack in the
     * log names whoever asked.
     */
    private boolean awaitStore() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.w(tag, layerName + ": store write asked for on the main thread, skipped",
                    new IllegalStateException("AtmosphereFeatures is worker-only"));
            return false;
        }
        try {
            if (store == null)
                ready.await(20, java.util.concurrent.TimeUnit.SECONDS);
            // And the map half: a worker-only wait, so the main-thread hop it waits
            // for is never the thread waiting.
            registered.await(20, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        // A stop that overtook the open leaves a store with no layer: nothing to write.
        return store != null && !detached;
    }

    private void attachStore() {
        try {
            storeFile = FileSystemUtils.getItem("tools/atmosphere/" + storeName);
            final File dir = storeFile.getParentFile();
            if (dir != null && !dir.isDirectory())
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            // The store is NOT emptied here. It was, on the reasoning that an advisory
            // is replaced whole every six hours so yesterday's storms are only a
            // chance to draw one that has dissipated -- and the refresh clears it
            // anyway, moments later. But that made the layer open over an EMPTY store,
            // which is the one structural difference from IPAWS and Feature Layer,
            // whose stores always carry the last session's features. Whether the
            // renderer's hit-test control survives being created over nothing is the
            // open question (XCover, 2026-09-23).
            // BEFORE the overlay is built, and that ordering is the whole point --
            // see sweepOldDrawings.
            if (sweepOldStormDrawings)
                sweepOldDrawings();

            // Start from an empty file.
            //
            // The store accumulates feature sets its own API cannot enumerate: the
            // file held sixty while queryFeatureSets returned six, however the
            // parameters were framed, so fifty-four were unreachable and undeletable
            // and their features kept drawing. That is why the same incident came
            // back twice after every attempt to clear it (operator, 2026-09-25, four
            // rounds of it). There is no API that reaches them, so the file goes.
            //
            // It costs nothing: the layer is replaced whole on the next refresh,
            // which is seconds away, and the alternative is a file that grows
            // garbage forever -- this one reached 7.7 MB and 1,887 features to draw
            // roughly 224.
            deleteStoreFile();
            ensureSwatch();
            store = new FeatureSetDatabase2(storeFile);
            // Everything that only needs the database can go now; the rest of this
            // is map registration and has to be on main.
            ready.countDown();
            final FeatureDataStore2.FeatureQueryParameters visibleOnly =
                    new FeatureDataStore2.FeatureQueryParameters();
            visibleOnly.visibleOnly = true;
            layer = new FeatureLayer3(layerName, store, visibleOnly);

            // A field, not a local: it is built here with the store and used when the
            // map registration runs, which is now a separate hop onto main.
            query = new FeatureDataStoreDeepMapItemQuery(layer) {
                        @Override
                        public java.util.SortedSet<MapItem> deepHitTest(MapView view,
                                com.atakmap.map.hittest.HitTestQueryParameters params,
                                java.util.Map<com.atakmap.map.layer.Layer2,
                                        java.util.Collection<com.atakmap.map.hittest.HitTestControl>> controls) {
                            final java.util.SortedSet<MapItem> hits =
                                    super.deepHitTest(view, params, controls);
                            final int raw = hits == null ? -1 : hits.size();
                            onePerPlace(hits, params == null ? null : params.geo);
                            narrow(hits, params == null ? null : params.geo);
                            Log.d(tag, "deepHitTest: " + (controls == null ? -1 : controls.size())
                                    + " controls, " + raw + " hits"
                                    + (hits != null && hits.size() != raw ? ", " + hits.size() + " kept" : ""));
                            return hits;
                        }

                        @Override
                        public java.util.SortedSet<MapItem> deepHitTestItems(int x, int y,
                                com.atakmap.coremap.maps.coords.GeoPoint point, MapView view) {
                            final java.util.SortedSet<MapItem> hits =
                                    super.deepHitTestItems(x, y, point, view);
                            final int raw = hits == null ? -1 : hits.size();
                            onePerPlace(hits, point);
                            narrow(hits, point);
                            Log.d(tag, "deepHitTestItems: " + raw + " hits"
                                    + (hits != null && hits.size() != raw ? ", " + hits.size() + " kept" : ""));
                            return hits;
                        }

                        @Override
                        protected MapItem featureToMapItem(Feature feature) {
                            final AttributeSet a = attributesOf(feature.getId());
                            // Built through ATAK's public static with NO store behind
                            // it, whenever the attributes are at hand.
                            //
                            // featureToMapItem hangs an OnIconChangedListener on the
                            // marker it makes, and that listener writes the marker's
                            // icon BACK INTO THE STORE as a bare IconPointStyle(color,
                            // uri): no width, no offset, no transparent label style.
                            // Every setIcon on the proxy fired it, and the tapped
                            // feature was redrawn by the map at "original" size,
                            // centered, with its name over it -- the small disc under
                            // ATAK's "MALLORY RIDGE" (2026-09-26), and the day before
                            // the substitute chooser icon turning the tapped station
                            // into a bare disc. The feature's style being rewritten,
                            // both times, not the proxy being drawn. The static takes
                            // a null store and updateFeatureStyle returns on null, so
                            // the listener is harmless there (read from 5.8). It needs
                            // the attributes on the feature -- the hit test's copy has
                            // none -- so they are fetched by id and put on a copy.
                            final boolean own = a != null;
                            final MapItem item = own
                                    ? FeatureDataStoreDeepMapItemQuery.featureToMapItem(
                                            new Feature(feature.getFeatureSetId(),
                                                    feature.getId(), feature.getName(),
                                                    feature.getGeometry(), feature.getStyle(),
                                                    a, feature.getAltitudeMode(),
                                                    feature.getExtrude(),
                                                    feature.getTimestamp(),
                                                    feature.getVersion()),
                                            uidPrefix)
                                    : super.featureToMapItem(feature);
                            // A READ-ONLY radial, not no radial. Blanking the menu
                            // removed the radial and with it the only route to the
                            // details pane, so a tap said nothing at all (operator,
                            // 2026-09-23: "i have no metadata about the storm at all
                            // i can read"). These are Feature Layer's own menus:
                            // details, bloodhound, pairing line, polar coords, drop a
                            // reference point. Nothing that recolors, renames, deletes
                            // or sends, which is the whole point.
                            final boolean point = feature.getGeometry()
                                    instanceof com.atakmap.map.layer.feature.geometry.Point;
                            item.setMetaString("menu", PluginMenuParser.getMenu(
                                    pluginContext,
                                    point ? "menu/feature.xml" : "menu/feature_shape.xml"));
                            item.setMetaBoolean("removable", false);
                            item.setMetaBoolean("editable", false);
                            item.setMetaBoolean("addToObjList", false);
                            item.setMetaBoolean("nevercot", true);
                            item.setMetaLong("featureid", feature.getId());
                            // The station's own icon, in its own pixels, and no ATAK
                            // label -- the pill already carries the name. Only on a
                            // proxy with no store behind it; on the fallback any icon
                            // change would rewrite the feature's style.
                            if (own && item instanceof com.atakmap.android.maps.Marker)
                                try {
                                    final com.atakmap.android.maps.Marker m =
                                            (com.atakmap.android.maps.Marker) item;
                                    m.setTextRenderFlag(
                                            com.atakmap.android.maps.Marker.TEXT_STATE_NEVER_SHOW);
                                    final String glyph = a.containsAttribute("_chooserIcon")
                                            ? a.getStringAttribute("_chooserIcon") : null;
                                    if (glyph != null && !glyph.isEmpty()) {
                                        final int w = a.getIntAttribute("_chooserW");
                                        final int h = a.getIntAttribute("_chooserH");
                                        final int ax = a.getIntAttribute("_chooserAnchorX");
                                        final int ay = a.getIntAttribute("_chooserAnchorY");
                                        m.setIcon(new com.atakmap.coremap.maps.assets.Icon
                                                .Builder()
                                                .setImageUri(com.atakmap.coremap.maps.assets
                                                        .Icon.STATE_DEFAULT, glyph)
                                                .setSize(w, h)
                                                .setAnchor(ax, ay)
                                                .setColor(com.atakmap.coremap.maps.assets
                                                        .Icon.STATE_DEFAULT, 0xFFFFFFFF)
                                                .build());
                                        Log.d(tag, "proxy icon " + w + "x" + h + " anchor "
                                                + ax + "," + ay);
                                    }
                                } catch (Exception noGlyph) {
                                    Log.d(tag, "no icon for this item", noGlyph);
                                }
                            // A shape's row in Select Item has no picture of its own: it
                            // showed a black square beside "Day 3 excessive rainfall"
                            // (operator, 2026-09-26). The chooser reads iconUri and tints
                            // it by the color meta, so a white square in the shape's own
                            // stroke color is its swatch.
                            if (a != null && a.containsAttribute("_labelOnly"))
                                item.setMetaBoolean("_labelOnly", true);
                            if (a != null && a.containsAttribute("_oneRowPerName"))
                                item.setMetaBoolean("_oneRowPerName", true);
                            if (!(item instanceof com.atakmap.android.maps.Marker)) {
                                final String sw = swatchUri;
                                if (sw != null) {
                                    final int c = strokeColorOf(feature.getStyle());
                                    item.setMetaString("iconUri", sw);
                                    item.setMetaInteger("iconColor", c);
                                    item.setMetaInteger("color", c);
                                }
                            }
                            if (a != null)
                                item.setMetaString("remarks",
                                        com.atakmap.android.atmosphere.ui
                                                .StormDetailsReceiver.render(a));
                            // What the details pane shows as its heading and subtitle.
                            String title = feature.getName();
                            if ((title == null || title.isEmpty()) && a != null)
                                try {
                                    title = a.getStringAttribute("Position");
                                } catch (Exception ignored) {
                                    // the set name is a good enough heading
                                }
                            if (title == null || title.isEmpty())
                                title = setOf(feature.getId());
                            item.setMetaString("title", title);
                            item.setMetaString("callsign", title);
                            item.setMetaString("storm_set", setOf(feature.getId()));
                            // Marks it as ours, so a tap opens its page or its details
                            // instead of ATAK's radial (the plugin's menu listener).
                            item.setMetaBoolean("atmosphere", true);
                            // A spot request knows how to open something better than
                            // a list of its own fields: the forecast NWS wrote for it.
                            // Carried as plain meta so the details receiver can route
                            // on it without knowing anything about spot forecasts.
                            if (a != null)
                                try {
                                    // AttributeSet throws on a key it does not hold, so
                                    // each is checked first: asking a gauge for spotId
                                    // threw before the gauge line ran, and the details
                                    // button opened the plain fields (2026-09-26).
                                    final String spotId = a.containsAttribute("spotId")
                                            ? a.getStringAttribute("spotId") : null;
                                    if (spotId != null && !spotId.isEmpty())
                                        item.setMetaString("spotId", spotId);
                                    if (a.containsAttribute("_gaugeId"))
                                        item.setMetaString("gaugeId",
                                                a.getStringAttribute("_gaugeId"));
                                    if (a.containsAttribute("_buoyId"))
                                        item.setMetaString("buoyId",
                                                a.getStringAttribute("_buoyId"));
                                    if (a.containsAttribute("_ref")) {
                                        item.setMetaString("atmosphereRef",
                                                a.getStringAttribute("_ref"));
                                        item.setMetaString("atmosphereLayer", type);
                                    }
                                    if (a.containsAttribute("_zoneId")) {
                                        item.setMetaString("zoneId",
                                                a.getStringAttribute("_zoneId"));
                                        if (a.containsAttribute("_zoneName"))
                                            item.setMetaString("zoneName",
                                                    a.getStringAttribute("_zoneName"));
                                        if (a.containsAttribute("_zoneCwa"))
                                            item.setMetaString("zoneCwa",
                                                    a.getStringAttribute("_zoneCwa"));
                                    }
                                } catch (Exception ignored) {
                                    // not a spot feature
                                }
                            return item;
                        }
                    };

            mapView.post(new Runnable() {
                @Override
                public void run() {
                    registerOnMap();
                }
            });
        } catch (Exception e) {
            Log.w(tag, layerName + " store would not open", e);
            ready.countDown();
            registered.countDown();
        }
    }

    /** Each layer's own pane icon on its Overlay Manager row, by store type. */
    private static int listIcon(String type) {
        switch (type) {
            case "stations": return com.atakmap.android.atmosphere.plugin.R.drawable.ic_layer_stations;
            case "snotel": return com.atakmap.android.atmosphere.plugin.R.drawable.ic_layer_snotel;
            case "spot": return com.atakmap.android.atmosphere.plugin.R.drawable.ic_layer_spot;
            case "highflow": return com.atakmap.android.atmosphere.plugin.R.drawable.ic_layer_highflow;
            case "gauges": return com.atakmap.android.atmosphere.plugin.R.drawable.ic_layer_gauges;
            case "firewx": return com.atakmap.android.atmosphere.plugin.R.drawable.ic_layer_firewx;
            case "sawti": return com.atakmap.android.atmosphere.plugin.R.drawable.ic_layer_sawti;
            case "psps": return com.atakmap.android.atmosphere.plugin.R.drawable.ic_layer_psps;
            case "flood": return com.atakmap.android.atmosphere.plugin.R.drawable.ic_layer_flood;
            case "beach": return com.atakmap.android.atmosphere.plugin.R.drawable.ic_layer_beach;
            case "buoys": return com.atakmap.android.atmosphere.plugin.R.drawable.ic_layer_buoys;
            case "avalanche": return com.atakmap.android.atmosphere.plugin.R.drawable.ic_layer_avalanche;
            case "airquality": return com.atakmap.android.atmosphere.plugin.R.drawable.ic_layer_air;
            case "tropical": return com.atakmap.android.atmosphere.plugin.R.drawable.ic_layer_hurricane;
            default: return com.atakmap.android.atmosphere.plugin.R.drawable.ic_toolbar;
        }
    }

    /** The map half of attaching: cheap, and it has to run on the main thread. */
    private void registerOnMap() {
        if (detached) {
            Log.d(tag, layerName + ": stopped before its store finished opening");
            return;
        }
        try {
            // Every layer is a row inside one "Atmosphere" row in ATAK's Overlay
            // Manager, with the plugin's glyph, the way IPAWS Alerts is one row, and
            // each layer carries its own pane icon. Until 2026-09-27 each layer was
            // its own top-level row with a blank icon ("file://asset/nothing");
            // shooting the manual on the S22 the operator found "Streams running
            // high" alone at the bottom of the list with nothing beside it. The icon
            // lives in the plugin APK, so the authority is the plugin's package;
            // ATAK's own package cannot resolve it (IPAWS).
            final String res = "android.resource://" + pluginContext.getPackageName() + "/";
            overlay = new FeatureDataStoreMapOverlay(mapView.getContext(), store, null,
                    layerName, res + listIcon(type), query, null, null) {
                /**
                 * The row never asks to be hidden when empty. MapOverlayParent's
                 * refresh runs the empty-list filter on a child's row the moment it
                 * makes it, before the row's own asynchronous refresh has counted
                 * anything, and never refreshes a row it rejected: every layer read
                 * as empty, so did the group, and the XCover showed no Atmosphere
                 * row at all with Hurricanes drawing (2026-09-27). Kept listed, an
                 * off layer shows as an empty row inside the group, which is also
                 * where someone looking for it would look.
                 */
                @Override
                public HierarchyListItem getListModel(BaseAdapter adapter, long capabilities,
                        HierarchyListFilter filter) {
                    if ((capabilities & (Actions.ACTION_GOTO | Actions.ACTION_VISIBILITY
                            | Actions.ACTION_DELETE | Actions.ACTION_EXPORT)) == 0)
                        return null;
                    return new ListItem(adapter, filter) {
                        @Override
                        public boolean hideIfEmpty() {
                            return false;
                        }
                    };
                }
            };
            final com.atakmap.android.maps.MapOverlayManager mgr = mapView.getMapOverlayManager();
            parent = MapOverlayParent.getOrAddParent(mapView, PARENT_ID, PARENT_NAME,
                    res + com.atakmap.android.atmosphere.plugin.R.drawable.ic_toolbar, 50, false);
            final String id = overlay.getIdentifier();
            // A reinstall does not reliably run the old instance's detach (see the
            // layer sweep below), and the parent is ATAK's, so it outlives us: take
            // out any child of that name an earlier instance left, and any top-level
            // row of that name from a build before the parent, before adding ours.
            if (parent != null) {
                final MapOverlay stale = parent.get(id);
                if (stale != null)
                    mgr.removeOverlay(parent, stale);
            }
            final MapOverlay oldTop = mgr.getOverlay(id);
            if (oldTop != null && !(oldTop instanceof MapOverlayParent))
                mgr.removeOverlay(oldTop);
            // addOverlay, not addFilesOverlay. With addFilesOverlay this overlay did
            // not appear anywhere in Overlay Manager on the XCover, while its polygons
            // drew on the map perfectly well -- the same thing IPAWS found on the same
            // phone. Adding under a parent through the manager registers the map
            // group and its tap query exactly as a top-level add does, so a tap on a
            // buoy still finds the buoy. The add reports whether it took, so ask
            // rather than assume.
            final boolean added = parent != null ? mgr.addOverlay(parent, overlay)
                    : mgr.addOverlay(overlay);
            Log.d(tag, "overlay registration: added=" + added + " identifier='" + id
                    + "' under=" + (parent != null ? PARENT_NAME : "root") + " findable="
                    + (parent != null ? parent.get(id) != null : mgr.getOverlay(id) != null));
            // Sweep any layer a previous instance of this plugin left on the map.
            //
            // A reinstall does not reliably run the old instance's detach: disposal
            // is a posted Runnable that may never run once its context is gone. Its
            // FeatureLayer3 keeps drawing from this same store file, so every reload
            // stacked another copy -- the vector stack measured 9 layers, then 15
            // across two reinstalls (2026-09-25). Feature Layer empties a found
            // MapGroup for the same reason; this is that, for layers.
            //
            // It is not what put the same incident on the map twice -- an empty
            // store file is -- so do not read this as that fix.
            int swept = 0;
            try {
                for (com.atakmap.map.layer.Layer other : new java.util.ArrayList<>(
                        mapView.getLayers(MapView.RenderStack.VECTOR_OVERLAYS))) {
                    if (other != layer && layerName.equals(other.getName())) {
                        mapView.removeLayer(MapView.RenderStack.VECTOR_OVERLAYS, other);
                        swept++;
                    }
                }
            } catch (Exception e) {
                Log.w(tag, "could not sweep old layers", e);
            }
            if (swept > 0)
                Log.d(tag, layerName + ": swept " + swept
                        + " layer(s) left by an earlier instance");
            mapView.addLayer(MapView.RenderStack.VECTOR_OVERLAYS, layer);
            try {
                final java.util.List<com.atakmap.map.layer.Layer> stack =
                        mapView.getLayers(MapView.RenderStack.VECTOR_OVERLAYS);
                Log.d(tag, "vector stack: " + stack.size() + " layers, ours present="
                        + stack.contains(layer) + ", layer='" + layer.getName()
                        + "' visible=" + layer.isVisible());
            } catch (Exception e) {
                Log.w(tag, "could not read the vector stack", e);
            }
        } catch (Exception e) {
            Log.w(tag, layerName + " store would not open", e);
            store = null;
        }
        registered.countDown();
    }

    /**
     * Take away the drawings an earlier build of this plugin left behind.
     *
     * <p>They were {@code DrawingShape}s, which is to say ATAK thought they were the
     * operator's own drawings -- so it SAVED them, and restored them on every start.
     * With the layer drawing the current advisory on top, the map showed two of
     * everything, slightly apart, because the saved copies were from the previous
     * advisory (XCover, 2026-09-23: "its like drawn twice or some shit"). That is the
     * clearest possible argument for the change this class makes: an object gets
     * persisted, a feature does not.
     *
     * <p>Matched on the titles that build wrote, because it left nothing else to
     * recognize it by. Runs once at start and costs nothing when there is nothing to
     * find; it can go once no phone has a build older than 2026-09-23 on it.
     *
     * <p><b>It must run BEFORE the overlay is created, and that is not a detail.</b>
     * {@link FeatureDataStoreMapOverlay}'s constructor makes its own
     * {@code DefaultMapGroup} named after the overlay, and {@code addOverlay} binds
     * the hit-test query to that group. This sweep removes a root group called
     * "Hurricanes" -- which, once the overlay exists, IS the overlay's own group --
     * and {@code RootMapGroup.removeGroupImpl} deletes the query along with it. The
     * result was an overlay that reported added=true and findable=true, a layer that
     * drew perfectly, and a query ATAK never asked anything, at any zoom, dead on a
     * line. The log said "removed a leftover Hurricanes group" on every single start
     * and was read as the cleanup working rather than as the bug reporting itself
     * (XCover, 2026-09-23).
     */
    private void sweepOldDrawings() {
        if (overlay != null)
            // Never while our own overlay is registered: see below.
            return;
        try {
            final com.atakmap.android.maps.MapGroup root = mapView.getRootGroup();
            final com.atakmap.android.maps.MapGroup mine = root.findMapGroup("Hurricanes");
            if (mine != null) {
                root.removeGroup(mine);
                Log.d(tag, "removed a leftover Hurricanes group");
            }
            int n = 0;
            for (String suffix : new String[] { " cone", " track", " watches",
                    " wind arrival" })
                n += sweepTitled(root, suffix);
            // The positions were titled with the storm or the forecast time, so they
            // are found by their own marker type instead.
            if (n > 0)
                Log.d(tag, "removed " + n + " drawings left by an older build");
        } catch (Exception e) {
            Log.w(tag, "sweep of old drawings failed", e);
        }
    }

    private int sweepTitled(com.atakmap.android.maps.MapGroup group, String suffix) {
        int n = 0;
        for (MapItem it : new java.util.ArrayList<>(group.getItems())) {
            final String t = it.getTitle();
            if (t != null && t.endsWith(suffix)
                    && (t.contains("Hurricane ") || t.contains("Tropical ")
                            || t.contains("Storm "))) {
                it.removeFromGroup();
                n++;
            }
        }
        for (com.atakmap.android.maps.MapGroup child
                : new java.util.ArrayList<>(group.getChildGroups()))
            n += sweepTitled(child, suffix);
        return n;
    }

    void detach() {
        synchronized (lock) {
            // Opening the store is asynchronous now, so a stop can overtake it. Say
            // so here: the map registration is a posted hop and would otherwise put a
            // layer on the map for a plugin that has already gone.
            detached = true;
            detachLocked();
        }
    }

    private void detachLocked() {
        try {
            if (overlay != null) {
                final com.atakmap.android.maps.MapOverlayManager mgr = mapView.getMapOverlayManager();
                if (parent != null) {
                    mgr.removeOverlay(parent, overlay);
                    // The last layer out takes the Atmosphere row with it, so an
                    // unloaded plugin leaves no empty group behind.
                    if (parent.getOverlays().isEmpty())
                        mgr.removeOverlay(parent);
                } else {
                    mgr.removeOverlay(overlay);
                }
            }
            if (layer != null)
                mapView.removeLayer(MapView.RenderStack.VECTOR_OVERLAYS, layer);
            // The store is deliberately NOT disposed.
            //
            // Removing the layer does not stop a query already running on the
            // renderer's own worker thread. That thread calls back into Java, the
            // closed store throws DataStoreException, and JNI aborts the PROCESS --
            // signal 6, "CallVoidMethodV called with pending exception", inside
            // GLAsynchronousMapRenderable3::WorkerThread. It took ATAK down on every
            // reinstall (08:38:38 and 08:45:42 on 2026-09-25, and on 2026-09-23),
            // which is what made this layer look unfixable: the crash landed a second
            // or two after each write, so the next build went into a dead process and
            // the same duplicate incident kept coming back however it was chased.
            //
            // Delaying the dispose only moves the window; there is no point at which
            // the renderer is known to be finished. So it is left open. ATAK owns the
            // process and closes the file when it exits; a leaked handle costs
            // nothing next to aborting the host.
        } catch (Exception e) {
            Log.w(tag, layerName + " store would not close", e);
        }
        overlay = null;
        layer = null;
        store = null;
        sets.clear();
        registered.countDown();
    }

    /**
     * Replace everything with this set. <b>Worker thread only</b>: it writes a database,
     * and a country's warnings with their zone shapes is not work for main.
     *
     * <p>IPAWS's {@code AlertOverlay.rewrite}, carried forward: one modify lock for the
     * whole write, so ATAK re-queries the store once rather than once per insert, and
     * the new sets go in BEFORE the old come out, so the map never blanks between two
     * polls. An empty list is honored -- when everything expires the map goes quiet --
     * so a failed poll must not call this at all.
     */
    void rewrite(java.util.List<Drawn> drawn) {
        // Waits for the store, which is opened on its own thread now. This is a
        // worker-only API by contract, so the wait is never on the map's thread --
        // and a layer whose first refresh beat the open would otherwise draw nothing
        // and say nothing until its next poll, ten minutes later.
        if (!awaitStore())
            return;
        synchronized (lock) {
            if (store == null || detached)
                return;
            boolean bulk = false;
            try {
                store.acquireModifyLock(true);
                bulk = true;
                // Write the new sets first, then delete the ones that were here on
                // the way in, both inside the modify lock, so the layer is never
                // momentarily empty on the map.
                //
                // What to delete is asked of the store, not remembered: an in-memory
                // list of "ours" is empty after a reinstall, and the file outlives
                // the instance. That is only sound because attach now starts from an
                // empty file -- while it did not, this query saw six of the sixty
                // sets the file held and each pass cleared a sixth of the problem
                // (2026-09-25).
                final java.util.List<Long> old = existingSetIds();
                sets.clear();
                final Map<String, Long> fresh = new HashMap<>();
                for (Drawn d : drawn) {
                    Long fsid = fresh.get(d.setName);
                    if (fsid == null) {
                        fsid = store.insertFeatureSet(
                                new FeatureSet(PROVIDER, type, d.setName,
                                        d.minGsd, d.maxGsd));
                        store.setFeatureSetVisible(fsid, true);
                        fresh.put(d.setName, fsid);
                    }
                    // A polygon's label rides its edge when it is part of the polygon's
                    // own style, and turned with it ("Flood Watch" up the side of a
                    // county; operator, 2026-09-26: "can the label be horizontal not
                    // follow the polygon edge?"). So a polygon is stored without its
                    // label and a label-only point is stored at its center, horizontal,
                    // in the same set; the hit-test drops the point again.
                    final String centered = centerLabelOf(d.geometry, d.style);
                    // The label point goes in BEFORE its polygon: the newest point in a
                    // rewrite was missing its label on the first draw and picked it up
                    // on the next pan (2026-09-26, the Moderate outlook area), so the
                    // newest row of every rewrite is a polygon now, and the renderer
                    // is asked to lay out again once the lock is released.
                    if (centered != null) {
                        final double[] c = labelPoint(d.geometry);
                        Log.d(tag, "label '" + centered + "' at " + (c == null ? "nowhere"
                                : String.format(java.util.Locale.US, "%.3f,%.3f", c[0], c[1])));
                        if (c != null) {
                            final AttributeSet la = new AttributeSet();
                            la.setAttribute("_labelOnly", 1);
                            // In the shape's own color, so a label met in the middle of a
                            // polygon wider than the screen still reads as that polygon's
                            // (operator, 2026-09-26: "some random flood watch label").
                            final int edge = strokeColorOf(d.style);
                            final LabelPointStyle own = labelStyleOf(d.style);
                            final boolean colored = own != null
                                    && (own.getBackgroundColor() >>> 24) == 0xFF;
                            final int bg = ((colored ? own.getBackgroundColor() : edge)
                                    & 0x00FFFFFF) | 0xD9000000;
                            final int text = colored ? own.getTextColor()
                                    : luminance(edge) > 150 ? 0xFF000000 : 0xFFFFFFFF;
                            store.insertFeature(new Feature(fsid, d.name, new Point(c[1], c[0]),
                                    colored
                                            ? new LabelPointStyle(centered, text, bg,
                                                    LabelPointStyle.ScrollMode.OFF, 0f, 0, 0, 0f,
                                                    false, own.getLabelMinRenderResolution())
                                            : new LabelPointStyle(centered, text, bg,
                                                    LabelPointStyle.ScrollMode.OFF, 0f, 0, 0, 0f, false),
                                    la, Feature.AltitudeMode.ClampToGround, 0d));
                        }
                    }
                    store.insertFeature(new Feature(fsid, d.name, d.geometry,
                            centered == null ? d.style : withoutLabel(d.style),
                            d.attrs, Feature.AltitudeMode.ClampToGround, 0d));
                }
                // One at a time. deleteFeatureSets(params) is a silent no-op on this
                // store -- the file held sixty sets before and sixty after, every
                // pass, while reporting success -- so the operator kept finding the
                // same incident twice (2026-09-25). deleteFeatureSet(id) does work;
                // what was broken was the enumeration feeding it, which with default
                // query parameters returned six of the sixty.
                int cleared = 0;
                for (Long id : old) {
                    try {
                        store.deleteFeatureSet(id);
                        cleared++;
                    } catch (Exception e) {
                        Log.w(tag, "old set " + id, e);
                    }
                }
                Log.d(tag, layerName + ": cleared " + cleared + " of " + old.size()
                        + " old set(s), wrote " + fresh.size());
                sets.clear();
                sets.putAll(fresh);
            } catch (Exception e) {
                Log.w(tag, layerName + " rewrite failed", e);
            } finally {
                if (bulk)
                    store.releaseModifyLock();
                // Ask the renderer to lay out again: the newest label of a rewrite was
                // sometimes missing until the next pan (2026-09-26). GLMapView is a
                // MapRenderer, and that is where requestRefresh lives.
                mapView.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            final Object r = mapView.getRenderer3();
                            if (r instanceof com.atakmap.map.MapRenderer)
                                ((com.atakmap.map.MapRenderer) r).requestRefresh();
                        } catch (Exception ignored) {
                            // the next map move lays the labels out anyway
                        }
                    }
                });
            }
        }
    }

    /**
     * Empty the store without taking the layer off the map. <b>Worker thread only</b>,
     * like {@link #rewrite}, because it is one: a rewrite with nothing in it.
     *
     * <p>It was its own write, and wrong in two ways. It deleted with
     * {@code deleteFeatureSets} and default parameters, the call the rewrite above
     * records as a silent no-op on this store (2026-09-25); and it took no lock, so a
     * stop could take the store away mid-delete. A rewrite deletes set by set, under
     * the lock, and not at all once the layer is detached.
     */
    void clear() {
        rewrite(java.util.Collections.<Drawn>emptyList());
    }

    private static LineString ring(GeoPoint[] pts) {
        final LineString l = new LineString(2);
        for (GeoPoint p : pts)
            l.addPoint(p.getLongitude(), p.getLatitude());
        return l;
    }

    /** Which feature set a feature belongs to, by name, for the details subtitle. */
    private String setOf(long fid) {
        if (store == null)
            return "";
        com.atakmap.map.layer.feature.FeatureCursor c = null;
        try {
            final FeatureDataStore2.FeatureQueryParameters p =
                    new FeatureDataStore2.FeatureQueryParameters();
            p.ids = java.util.Collections.singleton(fid);
            p.ignoredFeatureProperties = FeatureDataStore2.PROPERTY_FEATURE_GEOMETRY
                    | FeatureDataStore2.PROPERTY_FEATURE_STYLE
                    | FeatureDataStore2.PROPERTY_FEATURE_ATTRIBUTES;
            p.limit = 1;
            c = store.queryFeatures(p);
            if (c.moveToNext()) {
                final long fsid = c.get().getFeatureSetId();
                for (java.util.Map.Entry<String, Long> e : sets.entrySet())
                    if (e.getValue() == fsid)
                        return e.getKey();
            }
        } catch (Exception e) {
            Log.w(tag, "set of " + fid, e);
        } finally {
            if (c != null)
                try {
                    c.close();
                } catch (Exception ignored) {
                }
        }
        return "";
    }

    /** One feature's attributes by id: the hit-test query drops them, so fetch them. */
    private AttributeSet attributesOf(long fid) {
        if (store == null)
            return null;
        com.atakmap.map.layer.feature.FeatureCursor c = null;
        try {
            final FeatureDataStore2.FeatureQueryParameters p =
                    new FeatureDataStore2.FeatureQueryParameters();
            p.ids = java.util.Collections.singleton(fid);
            p.ignoredFeatureProperties = FeatureDataStore2.PROPERTY_FEATURE_GEOMETRY
                    | FeatureDataStore2.PROPERTY_FEATURE_STYLE;
            p.limit = 1;
            c = store.queryFeatures(p);
            if (c.moveToNext())
                return c.get().getAttributes();
        } catch (Exception e) {
            Log.w(tag, "attributes of " + fid, e);
        } finally {
            if (c != null)
                try {
                    c.close();
                } catch (Exception ignored) {
                }
        }
        return null;
    }


}

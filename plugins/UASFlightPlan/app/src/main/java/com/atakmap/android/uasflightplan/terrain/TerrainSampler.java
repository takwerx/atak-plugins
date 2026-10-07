package com.atakmap.android.uasflightplan.terrain;

import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.conversion.EGM96;
import com.atakmap.coremap.maps.coords.GeoBounds;
import com.atakmap.coremap.maps.coords.GeoCalculations;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.coremap.maps.coords.GeoPointMetaData;
import com.atakmap.map.elevation.ElevationData;
import com.atakmap.map.elevation.ElevationManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Reads the elevation ATAK already has around a launch point and turns it into a
 * {@link TerrainGrid}. Copied forward from Dozer Country, which measured this path
 * on the phones; the shape of the area (a circle around a point instead of a drawn
 * polygon) and the MSL conversion are the differences.
 *
 * <h3>One bulk call, not a lookup loop</h3>
 *
 * {@link ElevationManager#getElevation(Iterator, double[], ElevationManager.QueryParameters,
 * ElevationData.Hints)} fills the whole grid in a single call. The points are
 * generated lazily as it consumes them, so only the {@code double[]} of results is
 * ever in memory.
 *
 * <h3>This must not run on the main thread or the GL thread</h3>
 *
 * It reads from disk. Callers hand it a worker. Nothing here touches a View or a map
 * item.
 *
 * <h3>HAE in, MSL out</h3>
 *
 * The engine's numbers are height above the ellipsoid; its {@code AltitudeReference}
 * enum has no MSL member. The geoid offset is taken once at the launch point from
 * ATAK's own {@link EGM96} and applied to every cell. Over a few miles the geoid
 * changes by centimeters, and the altimeter this is for reads in tens of feet.
 */
public final class TerrainSampler {

    private static final String TAG = "UASTerrain";

    /**
     * Ground meters per cell we aim for. Finer than DTED2's 30 m posts so the paint
     * follows the terrain rather than stair-stepping; finer still would only
     * interpolate the same source and imply precision the data does not have.
     */
    public static final double PREFERRED_CELL_M = 10d;

    /**
     * Cap on grid width and height. 512 x 512 is 262,144 cells: a few megabytes of
     * texture and well under a second of arithmetic. A larger circle gets coarser
     * cells instead of a longer wait, and the pane reports the cell size used.
     */
    public static final int MAX_DIM = 512;

    /** Width of the edge ring, in cells. */
    public static final double RING_CELLS = 2.5d;

    private TerrainSampler() {
    }

    /**
     * Sources fine enough to paint a ceiling from: 30 m posts or finer.
     *
     * <p>DTED2 is 30 m, DTED3 is 10 m, SRTM1 is one arc-second which is also about
     * 30 m, and LIDAR is finer than any of them. DTED0 is 1,000 m between posts: a
     * ridge between two posts simply is not in the data, and painting it as water
     * would be the one lie this plugin must never tell.
     */
    private static final Set<String> GOOD_ENOUGH = new HashSet<>(Arrays.asList(
            GeoPointMetaData.DTED2,
            GeoPointMetaData.DTED3,
            GeoPointMetaData.SRTM1,
            GeoPointMetaData.LIDAR));

    /** What elevation actually covers the circle, and how good it is. */
    public static final class Coverage {
        /** Every altitude source seen across the probe grid, in the order first seen. */
        public final List<String> sources;
        /** Probe points whose source is 30 m or finer. */
        public final int good;
        /** Probe points that answered at all. */
        public final int probes;

        Coverage(List<String> sources, int good, int probes) {
            this.sources = sources;
            this.good = good;
            this.probes = probes;
        }

        /** True only when every probe came back from data fine enough to trust. */
        public boolean meetsDted2() {
            return probes > 0 && good == probes;
        }

        /** True when nothing at all answered: no elevation loaded for this ground. */
        public boolean isEmpty() {
            return sources.isEmpty();
        }

        /** "DTED2" or "DTED2, DTED0", for telling the pilot what IS there. */
        public String describe() {
            final StringBuilder sb = new StringBuilder();
            for (String src : sources) {
                if (sb.length() > 0)
                    sb.append(", ");
                sb.append(src);
            }
            return sb.toString();
        }
    }

    /** The square that just contains the circle. */
    public static GeoBounds boundsFor(GeoPoint center, double radiusM) {
        final GeoPoint n = GeoCalculations.pointAtDistance(center, 0d, radiusM);
        final GeoPoint s = GeoCalculations.pointAtDistance(center, 180d, radiusM);
        final GeoPoint e = GeoCalculations.pointAtDistance(center, 90d, radiusM);
        final GeoPoint w = GeoCalculations.pointAtDistance(center, 270d, radiusM);
        return new GeoBounds(new GeoPoint(n.getLatitude(), w.getLongitude()),
                new GeoPoint(s.getLatitude(), e.getLongitude()));
    }

    /**
     * Asks what elevation the circle would actually be read from, before reading it.
     *
     * <p>{@code getElevation} returns a number from whatever source it can find, so
     * DTED0 at a kilometer per post comes back as a perfectly valid elevation. The
     * question is not "did a number come back" but "did it come back from data fine
     * enough to mean anything". {@code getElevationMetadata} answers with ATAK's own
     * source constants, probed on a coarse grid inside the circle.
     */
    public static Coverage surveyCoverage(GeoPoint center, double radiusM) {
        final GeoBounds aoi = boundsFor(center, radiusM);
        final int n = 12;
        final List<String> sources = new ArrayList<>();
        int good = 0, probes = 0;

        final ElevationManager.QueryParameters params = new ElevationManager.QueryParameters();
        params.elevationModel = ElevationData.MODEL_TERRAIN;

        final double latStep = (aoi.getNorth() - aoi.getSouth()) / (n - 1);
        final double lonStep = (aoi.getEast() - aoi.getWest()) / (n - 1);

        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                final double lat = aoi.getSouth() + y * latStep;
                final double lon = aoi.getWest() + x * lonStep;
                if (GeoCalculations.distanceTo(center, new GeoPoint(lat, lon)) > radiusM)
                    continue;

                String src = null;
                try {
                    final GeoPointMetaData md =
                            ElevationManager.getElevationMetadata(lat, lon, params);
                    if (md != null && md.get() != null && md.get().isAltitudeValid())
                        src = md.getAltitudeSource();
                } catch (RuntimeException e) {
                    Log.e(TAG, "elevation metadata query failed", e);
                }

                if (src == null || src.isEmpty() || GeoPointMetaData.UNKNOWN.equals(src))
                    continue;       // nothing here; counted by absence, named by nothing

                probes++;
                if (GOOD_ENOUGH.contains(src))
                    good++;
                if (!sources.contains(src))
                    sources.add(src);
            }
        }

        Log.d(TAG, "coverage: " + good + "/" + probes + " probes good, sources " + sources);
        return new Coverage(sources, good, probes);
    }

    /**
     * Ground at one point, meters MSL, or NaN when there is none.
     *
     * <p>Used for the launch point itself, so the number on the pane is the ground
     * under the marker and not the nearest grid cell.
     */
    public static double groundMsl(GeoPoint p) {
        final ElevationManager.QueryParameters params = new ElevationManager.QueryParameters();
        params.elevationModel = ElevationData.MODEL_TERRAIN;
        params.interpolate = true;
        double hae = Double.NaN;
        String reference = "?";
        try {
            final GeoPointMetaData md = ElevationManager.getElevationMetadata(
                    p.getLatitude(), p.getLongitude(), params);
            if (md != null && md.get() != null && md.get().isAltitudeValid()) {
                hae = md.get().getAltitude();
                reference = String.valueOf(md.get().getAltitudeReference());
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "elevation query failed", e);
        }
        if (Double.isNaN(hae))
            return Double.NaN;
        final double msl = toMsl(p, hae, reference);
        Log.d(TAG, String.format(java.util.Locale.US,
                "ground at %.5f,%.5f: engine %.1f m %s, geoid offset %.1f m, %.1f m MSL",
                p.getLatitude(), p.getLongitude(), hae, reference, hae - msl, msl));
        return msl;
    }

    /**
     * The engine's number for a point, converted to MSL the way ATAK's own display
     * does it. If the engine ever reports something other than HAE, the value is
     * taken as it is and the log says so, because guessing a datum is worse than
     * either.
     */
    private static double toMsl(GeoPoint p, double value, String reference) {
        if (!"HAE".equals(reference)) {
            Log.w(TAG, "engine reported altitude reference " + reference
                    + "; using its value as MSL unconverted");
            return value;
        }
        try {
            return EGM96.getMSL(p.getLatitude(), p.getLongitude(), value);
        } catch (RuntimeException | LinkageError e) {
            Log.e(TAG, "EGM96 conversion failed; using HAE unconverted", e);
            return value;
        }
    }

    /**
     * Samples the circle.
     *
     * @param center  the launch point
     * @param radiusM the working radius, meters
     * @return the grid, never null; {@link TerrainGrid#unknownCells} says what is missing
     */
    public static TerrainGrid sample(GeoPoint center, double radiusM, Coverage coverage) {
        final GeoBounds aoi = boundsFor(center, radiusM);
        final GeoPoint nw = new GeoPoint(aoi.getNorth(), aoi.getWest());
        final GeoPoint ne = new GeoPoint(aoi.getNorth(), aoi.getEast());
        final GeoPoint sw = new GeoPoint(aoi.getSouth(), aoi.getWest());

        // Real ground distances rather than a degrees-to-meters constant, so the cell
        // size is right at any latitude without us owning an earth model.
        final double eastM = GeoCalculations.distanceTo(nw, ne);
        final double northM = GeoCalculations.distanceTo(nw, sw);

        final int width = dim(eastM);
        final int height = dim(northM);

        final double cellEastM = eastM / Math.max(1, width - 1);
        final double cellNorthM = northM / Math.max(1, height - 1);

        Log.d(TAG, "sampling " + width + "x" + height + " cells, "
                + Math.round(cellEastM) + "m x " + Math.round(cellNorthM) + "m, over "
                + Math.round(eastM) + "m x " + Math.round(northM) + "m, radius "
                + Math.round(radiusM) + "m");

        final double[] z = new double[width * height];

        final ElevationManager.QueryParameters params = new ElevationManager.QueryParameters();
        // Bare earth. MODEL_SURFACE would follow the treetops, and the ceiling is
        // measured against the ground.
        params.elevationModel = ElevationData.MODEL_TERRAIN;
        params.interpolate = true;

        final ElevationData.Hints hints = new ElevationData.Hints();
        hints.bounds = aoi;
        hints.resolution = Math.min(cellEastM, cellNorthM);
        hints.interpolate = true;
        // Accuracy over speed: this runs once per launch point, not per frame.
        hints.preferSpeed = false;

        boolean ok = false;
        try {
            ok = ElevationManager.getElevation(
                    new GridPoints(aoi, width, height), z, params, hints);
        } catch (RuntimeException e) {
            // A missing or corrupt elevation source should leave the overlay blank and
            // the pane explaining itself, not take ATAK down.
            Log.e(TAG, "elevation query failed", e);
        }
        if (!ok)
            Log.w(TAG, "elevation query reported no coverage for this circle");

        // The datum. The engine tags the metadata point with its reference; convert
        // with the geoid offset at the center and apply it everywhere.
        String reference = "?";
        double offset = 0d;
        try {
            final GeoPointMetaData md = ElevationManager.getElevationMetadata(
                    center.getLatitude(), center.getLongitude(), params);
            if (md != null && md.get() != null && md.get().isAltitudeValid()) {
                reference = String.valueOf(md.get().getAltitudeReference());
                final double hae = md.get().getAltitude();
                offset = hae - toMsl(center, hae, reference);
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "elevation metadata query failed at the center", e);
        }
        Log.d(TAG, "engine reference " + reference + ", geoid offset "
                + String.format(java.util.Locale.US, "%.2f", offset) + " m subtracted");

        final double cx = (width - 1) / 2d, cy = (height - 1) / 2d;
        final double r2 = radiusM * radiusM;
        // The edge ring is a fixed number of cells wide, so it stays one visible
        // line at the zoom the circle is looked at, whatever the radius.
        final double inner = Math.max(0d, radiusM - RING_CELLS * Math.min(cellEastM, cellNorthM));
        final double inner2 = inner * inner;
        final boolean[] in = new boolean[z.length];
        final boolean[] ring = new boolean[z.length];
        for (int i = 0; i < z.length; i++) {
            if (!GeoPoint.isAltitudeValid(z[i]))
                z[i] = Double.NaN;
            else
                z[i] -= offset;
            final int x = i % width, y = i / width;
            final double dx = (x - cx) * cellEastM, dy = (y - cy) * cellNorthM;
            final double d2 = dx * dx + dy * dy;
            in[i] = d2 <= r2;
            ring[i] = in[i] && d2 > inner2;
        }

        final double latStep = (aoi.getNorth() - aoi.getSouth()) / Math.max(1, height - 1);
        final double lonStep = (aoi.getEast() - aoi.getWest()) / Math.max(1, width - 1);

        return new TerrainGrid(z, width, height, aoi, latStep, lonStep,
                Math.min(cellEastM, cellNorthM), in, ring, center, radiusM, reference,
                offset, coverage == null ? "" : coverage.describe());
    }

    private static int dim(double spanM) {
        final int n = (int) Math.round(spanM / PREFERRED_CELL_M) + 1;
        return Math.max(2, Math.min(MAX_DIM, n));
    }

    /**
     * The sample grid, row-major with row 0 northmost, generated as it is consumed.
     *
     * <p>Row order matters twice over: {@link TerrainGrid} assumes it, and the texture
     * the layer uploads is read the same way, so north-up here is north-up on the map.
     */
    private static final class GridPoints implements Iterator<GeoPoint> {
        private final double north, west, latStep, lonStep;
        private final int width, height;
        private int i;

        GridPoints(GeoBounds aoi, int width, int height) {
            this.north = aoi.getNorth();
            this.west = aoi.getWest();
            this.width = width;
            this.height = height;
            this.latStep = (aoi.getNorth() - aoi.getSouth()) / Math.max(1, height - 1);
            this.lonStep = (aoi.getEast() - aoi.getWest()) / Math.max(1, width - 1);
        }

        @Override
        public boolean hasNext() {
            return i < width * height;
        }

        @Override
        public GeoPoint next() {
            if (!hasNext())
                throw new NoSuchElementException();
            final int x = i % width, y = i / width;
            i++;
            return new GeoPoint(north - y * latStep, west + x * lonStep);
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }
    }
}

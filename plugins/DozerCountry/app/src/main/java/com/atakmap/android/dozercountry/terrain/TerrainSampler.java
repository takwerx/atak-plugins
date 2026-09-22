package com.atakmap.android.dozercountry.terrain;

import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoBounds;
import com.atakmap.coremap.maps.coords.GeoCalculations;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.coremap.maps.coords.GeoPointMetaData;
import com.atakmap.map.elevation.ElevationData;
import com.atakmap.map.elevation.ElevationManager;


import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Reads the elevation ATAK already has for an area of interest and turns it into a
 * {@link SlopeField}.
 *
 * <h3>One bulk call, not a lookup loop</h3>
 *
 * {@link ElevationManager#getElevation(Iterator, double[], ElevationManager.QueryParameters,
 * ElevationData.Hints)} fills the whole grid in a single call, so a hundred thousand
 * cells cost one trip instead of a hundred thousand. The points are generated lazily
 * as it consumes them — the grid of {@code GeoPoint} is never all in memory at once,
 * only the {@code double[]} of results.
 *
 * <h3>This must not run on the main thread or the GL thread</h3>
 *
 * It reads from disk. Callers hand it a worker. Nothing here touches a View or a map
 * item, which is the rule that keeps {@code onMapMoved} from taking ATAK down with a
 * native SIGSEGV.
 *
 * <h3>No data is a result, not an error</h3>
 *
 * A device with no DTED for this ground returns a field of NaN. That is reported as
 * {@link SlopeField#unknownCells} and said out loud in the pane. The overlay never
 * fills an unknown cell with a color.
 */
public final class TerrainSampler {

    private static final String TAG = "DozerTerrain";

    /**
     * Ground metres per cell we aim for. Finer than DTED2's 30 m posts so the working
     * window spans more than one cell; finer still would only interpolate the same
     * source and imply precision the data does not have.
     */
    public static final double PREFERRED_CELL_M = 10d;

    /**
     * Cap on grid width and height. 512 x 512 is 262,144 cells: a few megabytes of
     * texture and well under a second of arithmetic, and past it the texture stops
     * being something a phone should hold. A larger area gets coarser cells instead of
     * a longer wait, and the pane reports the cell size it actually used.
     */
    public static final int MAX_DIM = 512;

    /**
     * DTED2's post spacing in metres, as ATAK itself defines it.
     *
     * <p>Read out of {@code Dt2ElevationData.DtedFormat}, which carries DTED0 = 1000,
     * DTED1 = 100, DTED2 = 30, DTED3 = 10. The number is copied rather than referenced
     * because that class is {@code com.atakmap.android.elev.dt2} internals and classes
     * in there come and go between ATAK releases; a constant that breaks the build in
     * three years is worse than one with its provenance written down.
     */
    public static final double DTED2_RESOLUTION_M = 30d;

    private TerrainSampler() {
    }

    /**
     * Sources that are good enough to class dozer ground: 30 m posts or finer.
     *
     * <p>DTED2 is 30 m, DTED3 is 10 m, SRTM1 is one arc-second which is also about
     * 30 m, and LIDAR is finer than any of them. DTED1 (100 m) and DTED0 (1000 m) are
     * not on this list and never should be: a 45% boundary decided from thousand-metre
     * posts is a number with no ground under it.
     */
    private static final java.util.Set<String> GOOD_ENOUGH = new java.util.HashSet<>(
            java.util.Arrays.asList(
                    GeoPointMetaData.DTED2,
                    GeoPointMetaData.DTED3,
                    GeoPointMetaData.SRTM1,
                    GeoPointMetaData.LIDAR));

    /** What elevation actually covers an area, and how good it is. */
    public static final class Coverage {
        /** Every altitude source seen across the probe grid, in the order first seen. */
        public final List<String> sources;
        /** Probe points whose source is 30 m or finer. */
        public final int good;
        /** Probe points total. */
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

        /** True when nothing at all answered — no elevation loaded for this ground. */
        public boolean isEmpty() {
            return sources.isEmpty();
        }

        /** "DTED1" or "DTED1, DTED0" — for telling the operator what IS there. */
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

    /**
     * Asks what elevation the area would actually be read from, before reading it.
     *
     * <p>Sampling alone cannot answer this. {@code getElevation} returns a number from
     * whatever source it can find, so DTED0 at a kilometre per post comes back as a
     * perfectly valid elevation and the overlay would paint confident safety bands off
     * terrain nothing can actually see. The question is not "did a number come back"
     * but "did it come back from data fine enough to mean anything".
     *
     * <p>This asks ATAK directly rather than inferring: {@code getElevationMetadata}
     * returns a {@link GeoPointMetaData} whose altitude source is one of ATAK's own
     * constants — DTED0..DTED3, SRTM1, LIDAR — so the answer is what was really used
     * at that point, not what happens to be installed somewhere nearby.
     *
     * <p>Probed on a coarse grid rather than every cell. A DTED tile is a degree
     * across; a probe every fifth of the area catches a missing tile or a change of
     * source, and the per-cell check that follows catches holes inside one.
     */
    public static Coverage surveyCoverage(GeoBounds aoi) {
        return surveyCoverage(aoi, null);
    }

    /**
     * @param area probe only inside the ring. A polygon can leave whole corners of its
     *             bounding box outside the area, and refusing because ground the
     *             operator did not draw has no DTED2 would be refusing the wrong
     *             question.
     */
    public static Coverage surveyCoverage(GeoBounds aoi, Polygon area) {
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
                if (area != null && !area.contains(lon, lat))
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
     * Samples the area and computes slope.
     *
     * @param aoi     the area of interest. Not tested across the antimeridian.
     * @param windowM the working window, metres across
     * @param waterMinAreaM2 smallest dead-flat region to treat as water
     * @return the field, never null; check {@link SlopeField#isEmpty()}
     */
    public static SlopeField sample(GeoBounds aoi, double windowM,
            double waterMinAreaM2) {
        return sample(aoi, windowM, waterMinAreaM2, null);
    }

    /**
     * @param area the ring the operator drew, or null to use the whole rectangle.
     *             Sampling still works the bounding box — a lat/lon grid is a
     *             rectangle — but everything outside the ring is left unpainted and
     *             excluded from every count reported to the operator.
     */
    public static SlopeField sample(GeoBounds aoi, double windowM,
            double waterMinAreaM2, Polygon area) {
        final GeoPoint nw = new GeoPoint(aoi.getNorth(), aoi.getWest());
        final GeoPoint ne = new GeoPoint(aoi.getNorth(), aoi.getEast());
        final GeoPoint sw = new GeoPoint(aoi.getSouth(), aoi.getWest());

        // Real ground distances rather than a degrees-to-metres constant, so the cell
        // size is right at any latitude without us owning an earth model.
        final double eastM = GeoCalculations.distanceTo(nw, ne);
        final double northM = GeoCalculations.distanceTo(nw, sw);

        final int width = dim(eastM);
        final int height = dim(northM);

        final double cellEastM = eastM / Math.max(1, width - 1);
        final double cellNorthM = northM / Math.max(1, height - 1);

        Log.d(TAG, "sampling " + width + "x" + height + " cells, "
                + Math.round(cellEastM) + "m x " + Math.round(cellNorthM) + "m, over "
                + Math.round(eastM) + "m x " + Math.round(northM) + "m");

        final double[] elevations = new double[width * height];

        final ElevationManager.QueryParameters params = new ElevationManager.QueryParameters();
        // Bare earth. MODEL_SURFACE would follow the treetops, which is the slope of
        // the canopy and not the slope a blade sits on.
        params.elevationModel = ElevationData.MODEL_TERRAIN;
        params.interpolate = true;

        final ElevationData.Hints hints = new ElevationData.Hints();
        hints.bounds = aoi;
        hints.resolution = Math.min(cellEastM, cellNorthM);
        hints.interpolate = true;
        // Accuracy over speed: this runs once when the operator draws the area, not
        // per frame, so there is nothing to gain by sampling coarsely.
        hints.preferSpeed = false;

        boolean ok = false;
        try {
            ok = ElevationManager.getElevation(
                    new GridPoints(aoi, width, height), elevations, params, hints);
        } catch (RuntimeException e) {
            // A missing or corrupt elevation source should leave the overlay blank and
            // the pane explaining itself, not take ATAK down.
            Log.e(TAG, "elevation query failed", e);
        }
        if (!ok)
            Log.w(TAG, "elevation query reported no coverage for this area");

        for (int i = 0; i < elevations.length; i++) {
            if (!GeoPoint.isAltitudeValid(elevations[i]))
                elevations[i] = Double.NaN;
        }

        final double latStep = (aoi.getNorth() - aoi.getSouth()) / Math.max(1, height - 1);
        final double lonStep = (aoi.getEast() - aoi.getWest()) / Math.max(1, width - 1);

        return SlopeField.compute(elevations, width, height,
                cellEastM, cellNorthM, windowM, waterMinAreaM2,
                area, aoi.getNorth(), aoi.getWest(), latStep, lonStep);
    }

    private static int dim(double spanM) {
        final int n = (int) Math.round(spanM / PREFERRED_CELL_M) + 1;
        return Math.max(2, Math.min(MAX_DIM, n));
    }

    /**
     * The sample grid, row-major with row 0 northmost, generated as it is consumed.
     *
     * <p>Row order matters twice over: {@link SlopeField} assumes it, and the texture
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

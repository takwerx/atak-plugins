package com.atakmap.android.dozercountry.terrain;

import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoBounds;
import com.atakmap.coremap.maps.coords.GeoCalculations;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.map.elevation.ElevationData;
import com.atakmap.map.elevation.ElevationManager;

import java.util.Iterator;
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

    private TerrainSampler() {
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

        return SlopeField.compute(elevations, width, height,
                cellEastM, cellNorthM, windowM, waterMinAreaM2);
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

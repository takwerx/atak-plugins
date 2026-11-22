package com.atakmap.android.uasflightplan.terrain;

import com.atakmap.coremap.maps.coords.GeoBounds;
import com.atakmap.coremap.maps.coords.GeoPoint;

/**
 * The terrain around a launch point, sampled once and held in memory: a north-up
 * grid of ground elevations in meters MSL, with a circle mask over it.
 *
 * <p>Everything the overlay paints is derived from this by comparing each cell with a
 * ceiling. Changing the ceiling recolors the grid and never resamples, so the
 * ceiling control is instant and only moving the launch point costs a disk read.
 *
 * <h3>The numbers are MSL</h3>
 *
 * ATAK's elevation engine works in HAE (its {@code AltitudeReference} has no MSL
 * member at all). {@link TerrainSampler} converts every cell with the geoid offset
 * ATAK's own {@code EGM96} reports, and records what it did in {@link #reference}
 * and {@link #geoidOffsetM} so the pane can say so. Aviation altitudes are MSL and
 * nothing in this class is ever anything else.
 *
 * <h3>No data is a result, not an error</h3>
 *
 * A cell with no elevation is {@code NaN} and is never painted: not as an island,
 * not as water. {@link #unknownCells} counts them inside the circle so the pane can
 * say how much of the picture is missing.
 */
public final class TerrainGrid {

    /** Ground in meters MSL, row-major, row 0 northmost; NaN where unknown. */
    public final double[] msl;
    public final int width;
    public final int height;
    /** The rectangle the grid covers: the box around the extent. */
    public final GeoBounds bounds;
    public final double latStep;
    public final double lonStep;
    /** Ground meters per cell, the smaller of the two axes. */
    public final double cellMeters;
    /** True for the cells inside the extent. */
    public final boolean[] inCircle;
    /**
     * True for the outermost cells of the circle: the edge of what was computed.
     * Drawn whatever is under them, so the limit of the picture is always on the
     * map; a sea with no shore reads as if the whole map had been checked.
     */
    public final boolean[] ring;
    /** Cells inside the extent. */
    public final int circleCells;
    /** Cells inside the extent with no elevation. */
    public final int unknownCells;
    /** The extent this grid covers. */
    public final Extent extent;
    /** Meters, for a circle; NaN for a drawn ring. */
    public final double radiusM;
    /** What the engine said its numbers were referenced to, e.g. "HAE". */
    public final String reference;
    /** Meters subtracted from the engine's value to reach MSL. */
    public final double geoidOffsetM;
    /** Every elevation source seen, e.g. "DTED2" or "DTED2, DTED0". */
    public final String sources;

    TerrainGrid(double[] msl, int width, int height, GeoBounds bounds,
            double latStep, double lonStep, double cellMeters, boolean[] inCircle,
            boolean[] ring, Extent extent, String reference,
            double geoidOffsetM, String sources) {
        this.msl = msl;
        this.width = width;
        this.height = height;
        this.bounds = bounds;
        this.latStep = latStep;
        this.lonStep = lonStep;
        this.cellMeters = cellMeters;
        this.inCircle = inCircle;
        this.ring = ring;
        this.extent = extent;
        this.radiusM = extent.radiusM;
        this.reference = reference;
        this.geoidOffsetM = geoidOffsetM;
        this.sources = sources;

        int in = 0, unknown = 0;
        for (int i = 0; i < msl.length; i++) {
            if (!inCircle[i])
                continue;
            in++;
            if (Double.isNaN(msl[i]))
                unknown++;
        }
        this.circleCells = in;
        this.unknownCells = unknown;
    }

    /** The ground inside the extent, square meters. */
    public double areaM2() {
        return circleCells * cellMeters * cellMeters;
    }

    /** Cells inside the extent that have an elevation. */
    public int knownCells() {
        return circleCells - unknownCells;
    }

    /** Highest ground in the circle, meters MSL, or NaN when nothing is known. */
    public double highestMsl() {
        double hi = Double.NaN;
        for (int i = 0; i < msl.length; i++) {
            if (!inCircle[i] || Double.isNaN(msl[i]))
                continue;
            if (Double.isNaN(hi) || msl[i] > hi)
                hi = msl[i];
        }
        return hi;
    }

    /** Cells inside the circle whose ground is at or above the ceiling. */
    public int islandCells(double ceilingMslM) {
        int n = 0;
        for (int i = 0; i < msl.length; i++) {
            if (inCircle[i] && !Double.isNaN(msl[i]) && msl[i] >= ceilingMslM)
                n++;
        }
        return n;
    }

    /**
     * Paints the circle for one ceiling.
     *
     * @param ceilingMslM the ceiling, meters MSL
     * @param island      ARGB for ground at or above the ceiling
     * @param water       ARGB for ground below it; 0 to leave the map showing through
     * @param edge        ARGB for the ring at the circle's edge
     * @return one ARGB per cell, 0 outside the circle and where the ground is unknown
     */
    public int[] toArgb(double ceilingMslM, int island, int water, int edge) {
        final int[] out = new int[msl.length];
        for (int i = 0; i < msl.length; i++) {
            if (!inCircle[i])
                continue;
            if (ring[i]) {
                out[i] = edge;
                continue;
            }
            final double z = msl[i];
            if (Double.isNaN(z))
                continue;
            out[i] = z >= ceilingMslM ? island : water;
        }
        return out;
    }
}

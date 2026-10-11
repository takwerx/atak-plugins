package com.atakmap.android.atmosphere.wind;

/**
 * A plain longitude/latitude lattice, GRIB2 grid template 3.0: what GFS and most
 * global models come on. No projection, so a cell's position is arithmetic.
 */
public final class LatLonGrid {

    public final int ni, nj;
    /** Edges in degrees, west &lt; east, south &lt; north. */
    public final double west, south, east, north;

    public LatLonGrid(int ni, int nj, double west, double south, double east, double north) {
        this.ni = ni;
        this.nj = nj;
        this.west = west;
        this.south = south;
        this.east = east;
        this.north = north;
    }

    /** Longitude of column i. */
    public double lonOf(int i) {
        return ni < 2 ? west : west + (east - west) * i / (ni - 1);
    }

    /** Latitude of row j, counted from the south. */
    public double latOf(int j) {
        return nj < 2 ? south : south + (north - south) * j / (nj - 1);
    }
}

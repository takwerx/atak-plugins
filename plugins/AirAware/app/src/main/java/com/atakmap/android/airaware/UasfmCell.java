package com.atakmap.android.airaware;

/**
 * One cell of the FAA UAS Facility Map: how high a drone may fly here without an
 * authorization.
 *
 * <p>A cell is 30 arc-seconds square on a uniform 1/120-degree grid, so it is carried as
 * the integer indices of its south-west corner rather than four corners -- see
 * {@code tools/build_uasfm_pack.py} for why that is the whole of it.
 *
 * <p>The ceiling is feet <b>above the ground</b>, and 0 is a real answer, not a missing
 * one: it means nothing may be flown there without further coordination. That is the
 * difference this layer exists to show, so nothing here ever treats 0 as unknown.
 */
public final class UasfmCell {

    /** Cells per degree; the grid the FAA publishes on. */
    public static final int PER_DEGREE = 120;

    public final int latIndex;
    public final int lonIndex;
    /**
     * How many cells wide this is, east from {@link #lonIndex}.
     *
     * <p>Normally one. Neighbouring cells that share a ceiling and an airport are joined
     * into a run before they are drawn: the grid comes in big uniform blocks, so 1,500
     * squares collapse to a fraction of that, and the store rewrite -- which was the
     * thing making a zoom stutter -- shrinks with it. A run says exactly what its cells
     * said, so nothing is lost but the internal lines.
     */
    public int span = 1;
    public final int ceilingFt;
    /** The airport whose airspace this cell belongs to, e.g. "ONT". */
    public final String airportId;
    public final String airportName;
    /** True when LAANC covers this airport: file in an app, rather than by request. */
    public final boolean laanc;

    /** Whether another cell continues this one eastward with the same answer. */
    public boolean joins(UasfmCell next) {
        return next != null && next.latIndex == latIndex
                && next.lonIndex == lonIndex + span
                && next.ceilingFt == ceilingFt
                && next.airportId.equals(airportId)
                && next.laanc == laanc;
    }

    public UasfmCell(int latIndex, int lonIndex, int ceilingFt, String airportId,
            String airportName, boolean laanc) {
        this.latIndex = latIndex;
        this.lonIndex = lonIndex;
        this.ceilingFt = ceilingFt;
        this.airportId = airportId;
        this.airportName = airportName;
        this.laanc = laanc;
    }

    public double south() {
        return latIndex / (double) PER_DEGREE;
    }

    public double west() {
        return lonIndex / (double) PER_DEGREE;
    }

    public double north() {
        return (latIndex + 1) / (double) PER_DEGREE;
    }

    public double east() {
        return (lonIndex + span) / (double) PER_DEGREE;
    }

    public boolean contains(double lat, double lon) {
        return lat >= south() && lat < north() && lon >= west() && lon < east();
    }

    /** What the operator is told, in the words the FAA uses. */
    public String label() {
        return ceilingFt == 0 ? "0 ft - further coordination required"
                : ceilingFt + " ft AGL without authorization";
    }

    public String where() {
        if (airportName.isEmpty())
            return airportId;
        return airportId.isEmpty() ? airportName : airportId + " " + airportName;
    }
}

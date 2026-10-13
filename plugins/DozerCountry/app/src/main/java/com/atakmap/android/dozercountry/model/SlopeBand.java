package com.atakmap.android.dozercountry.model;

/**
 * One production class out of the standard: a slope range, what it means in words,
 * and the color the overlay paints it.
 *
 * <p>The range is in <b>percent slope</b> (rise/run x 100), not degrees. That is the
 * unit the NWCG production tables are written in and the unit a dozer boss says out
 * loud, so it is the unit carried end to end — the only conversion to degrees happens
 * in the on-tap readout, for people who think that way.
 *
 * <p>Bands are half-open at the top in practice: the standard's printed ranges are
 * 0-25, 26-40, 41-55, 56-74, which leaves the fractional gaps between 25 and 26
 * unclaimed. {@link DozerStandard#bandFor(double)} closes them by taking the first
 * band whose {@code maxPercent} the value does not exceed, so 25.4% is class 1.
 */
public final class SlopeBand {

    /** The standard's own class number, 1-4. Zero for the above-standard entry. */
    public final int classNumber;
    public final double minPercent;
    public final double maxPercent;
    /** Short range text for the legend, e.g. "26-40%". */
    public final String label;
    /** What the class means in the field, e.g. "Production falls off". */
    public final String meaning;
    public final int argb;
    /**
     * Whether this band is shaded on the map.
     *
     * <p>The band that means "fine" is not, because an overlay should not spend the
     * operator's screen saying fine — a solid wash over every workable acre buries the
     * basemap they are reading the ground from. The color is still carried so the
     * legend can show what the band is.
     */
    public final boolean paint;

    public SlopeBand(int classNumber, double minPercent, double maxPercent,
            String label, String meaning, int argb) {
        this(classNumber, minPercent, maxPercent, label, meaning, argb, true);
    }

    public SlopeBand(int classNumber, double minPercent, double maxPercent,
            String label, String meaning, int argb, boolean paint) {
        this.classNumber = classNumber;
        this.minPercent = minPercent;
        this.maxPercent = maxPercent;
        this.label = label;
        this.meaning = meaning;
        this.argb = argb;
        this.paint = paint;
    }
}

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

    public SlopeBand(int classNumber, double minPercent, double maxPercent,
            String label, String meaning, int argb) {
        this.classNumber = classNumber;
        this.minPercent = minPercent;
        this.maxPercent = maxPercent;
        this.label = label;
        this.meaning = meaning;
        this.argb = argb;
    }
}

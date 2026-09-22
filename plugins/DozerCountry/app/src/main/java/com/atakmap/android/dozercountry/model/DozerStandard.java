package com.atakmap.android.dozercountry.model;

import java.util.Collections;
import java.util.List;

/**
 * A named slope standard: the production bands the overlay paints, the directional
 * safety limits, and the machine types it recognizes.
 *
 * <h3>Bands and limits are not the same axis</h3>
 *
 * {@link #bands} are production classes and are <b>magnitude only</b> — the NWCG rate
 * tables measure line built up or down the slope and have no sideslope column. {@link
 * #limits} are refusal points from S-232 Dozer Boss and are <b>directional</b>.
 *
 * <p>They disagree on purpose. A 50% slope is production class 3 (workable, reduced
 * rate) and at the same time over the 45% sidehill limit. Which is true depends on the
 * direction the line runs, which the AOI overlay does not know and the line profile
 * does. So the overlay paints bands, the profile can also check limits, and neither
 * pretends to be the other.
 */
public final class DozerStandard {

    public final String id;
    public final String name;
    /** What the on-screen legend calls this standard, e.g. "NWCG dozer production classes". */
    public final String legend;
    public final List<String> sources;
    /**
     * What the overlay does <em>not</em> account for, stated on screen.
     *
     * <p>This is not decoration. The band labels used to read "most fuel models near
     * zero", which told an operator that fuel had been considered; it has not been.
     * A colored overlay that implies an input it does not have is worse than one that
     * says plainly what it leaves out.
     */
    public final List<String> caveat;
    public final List<SlopeBand> bands;
    /**
     * Ground steeper than the last band. Not a fifth class — the production table
     * simply stops, so this is unmeasured ground and is colored to read that way.
     */
    public final SlopeBand aboveStandard;
    public final Limits limits;
    public final List<Machine> machines;

    /** Directional refusal points, percent slope. */
    public static final class Limits {
        public final double sidehillPercent;
        public final double uphillPercent;
        public final double downhillPercent;

        public Limits(double sidehill, double uphill, double downhill) {
            this.sidehillPercent = sidehill;
            this.uphillPercent = uphill;
            this.downhillPercent = downhill;
        }
    }

    public static final class Machine {
        public final String type;
        public final String name;
        public final int minHorsepower;
        public final String examples;

        public Machine(String type, String name, int minHorsepower, String examples) {
            this.type = type;
            this.name = name;
            this.minHorsepower = minHorsepower;
            this.examples = examples;
        }
    }

    public DozerStandard(String id, String name, String legend, List<String> sources,
            List<String> caveat, List<SlopeBand> bands, SlopeBand aboveStandard,
            Limits limits, List<Machine> machines) {
        this.id = id;
        this.name = name;
        this.legend = legend;
        this.sources = Collections.unmodifiableList(sources);
        this.caveat = Collections.unmodifiableList(caveat);
        this.bands = Collections.unmodifiableList(bands);
        this.aboveStandard = aboveStandard;
        this.limits = limits;
        this.machines = Collections.unmodifiableList(machines);
    }

    /**
     * The band a percent slope falls in, or {@link #aboveStandard} past the last band.
     *
     * <p>Takes the first band the value does not exceed rather than testing
     * {@code min <= v <= max}, which closes the fractional gaps the printed ranges
     * leave — the standard prints 0-25 then 26-40, so 25.4% has no band of its own and
     * belongs to class 1.
     *
     * @param percentSlope rise/run x 100; NaN if the terrain was unknown
     * @return the band, or null when {@code percentSlope} is NaN
     */
    public SlopeBand bandFor(double percentSlope) {
        if (Double.isNaN(percentSlope))
            return null;
        for (SlopeBand b : bands) {
            if (percentSlope <= b.maxPercent)
                return b;
        }
        return aboveStandard;
    }

    /**
     * The ARGB to paint a cell: 0 (fully transparent) where the terrain is unknown, and
     * also where the band it falls in is not shaded at all.
     *
     * <p>The overlay is exceptions only. Unshaded ground is ground under every limit,
     * and that reading is only safe because the plugin refuses to paint anything when
     * it cannot see the terrain — so unshaded can never quietly mean "no data".
     */
    public int colorFor(double percentSlope) {
        final SlopeBand b = bandFor(percentSlope);
        if (b == null || !b.paint)
            return 0;
        return b.argb;
    }
}

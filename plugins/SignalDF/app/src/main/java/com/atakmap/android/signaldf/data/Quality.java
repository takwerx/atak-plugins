package com.atakmap.android.signaldf.data;

import com.atakmap.android.signaldf.model.Bearing;

import java.util.Locale;

/**
 * Whether a bearing is worth believing, and what to say when it is not.
 *
 * <p>The radio reports a confidence and a power level with every bearing and
 * this plugin displayed both and acted on neither: a twelve percent bearing
 * drew the same line as a ninety-five percent one and went onto the team's
 * feed identically. KrakenRF's own app has had "Minimum Required Confidence"
 * and "Minimum Required Power" since the beginning, and discards results below
 * them rather than plotting them.
 *
 * <p><b>Weak is not the same as wrong, so it is not treated the same way.</b>
 * A bearing with no heading behind it is drawn nowhere, because it is a claim
 * about a direction on the earth that we know to be turned by an unknown
 * amount. A low-confidence bearing is a different thing: it is a real
 * measurement that happens to be poor, and hiding it entirely would leave the
 * operator staring at an empty map while the radio is plainly hearing
 * something. So a weak bearing is still drawn, drawn dimmer, and kept out of
 * everything derived from it -- the fix, and the team's feed. Those are
 * products, and a product built on data you would not show at full brightness
 * is not one to hand to somebody else.
 *
 * <p>Pure, so the thresholds can be checked off the phone.
 */
public final class Quality {

    /**
     * Default minimum confidence, 0 to 1.
     *
     * <p>KrakenRF's app ships this at zero -- everything plots -- and leaves
     * it to the operator. That is the wrong default for a plugin whose output
     * other people see on their own maps. A fifth is low enough to keep almost
     * everything a working array produces and high enough to drop the frames
     * where the DSP found no peak worth the name.
     */
    public static final double DEFAULT_MIN_CONFIDENCE = 0.2;

    /**
     * Default minimum power, dB, or {@link Double#NaN} for "do not gate on
     * power".
     *
     * <p>Off by default, deliberately. Confidence is comparable between
     * installs; power is not. It depends on the gain setting, the antennas,
     * the coax and how close the transmitter happens to be, so any number
     * picked here would be wrong for somebody. The control exists and starts
     * disabled, which is the honest arrangement for a threshold nobody can
     * choose on the operator's behalf.
     */
    public static final double DEFAULT_MIN_POWER_DB = Double.NaN;

    /** Why a bearing was rejected, or {@link #OK}. */
    public enum Verdict {
        OK,
        /** The DSP is not confident it found a direction at all. */
        LOW_CONFIDENCE,
        /** The signal is below the operator's floor. */
        LOW_POWER,
        /**
         * The front end is saturated. The radio told us; these bearings are
         * not weak, they are garbage, and the fix is to turn the gain down.
         */
        OVERDRIVEN
    }

    private Quality() {
    }

    /**
     * Judge one bearing.
     *
     * @param minConfidence 0 to 1, or NaN to accept any confidence
     * @param minPowerDb    dB, or NaN to accept any power
     */
    public static Verdict judge(Bearing b, double minConfidence,
            double minPowerDb) {
        if (b == null)
            return Verdict.LOW_CONFIDENCE;
        if (b.adcOverdrive)
            return Verdict.OVERDRIVEN;
        // A reported NaN is "the feed does not carry this", not "it failed".
        // The XML feed carries no power at all, and gating on a field that is
        // structurally absent would reject every bearing on that path.
        if (!Double.isNaN(minConfidence) && !Double.isNaN(b.confidence)
                && b.confidence < minConfidence)
            return Verdict.LOW_CONFIDENCE;
        if (!Double.isNaN(minPowerDb) && !Double.isNaN(b.powerDb)
                && b.powerDb < minPowerDb)
            return Verdict.LOW_POWER;
        return Verdict.OK;
    }

    /** True when this bearing may be drawn at full strength and used. */
    public static boolean usable(Bearing b, double minConfidence,
            double minPowerDb) {
        return judge(b, minConfidence, minPowerDb) == Verdict.OK;
    }

    /**
     * A per-verdict tally, so the pane can say what it threw away.
     *
     * <p>The house rule is that a panel says what it is not showing, and a
     * filter is the worst offender: one that silently eats data is
     * indistinguishable from a radio that has stopped working. A count alone
     * is not enough either -- "18 dropped" invites a shrug, where "18 dropped:
     * front end saturated, turn the gain down" is a thing to go and do.
     */
    public static final class Tally {
        public int lowConfidence;
        public int lowPower;
        public int overdriven;

        public void add(Verdict v) {
            switch (v) {
                case LOW_CONFIDENCE:
                    lowConfidence++;
                    break;
                case LOW_POWER:
                    lowPower++;
                    break;
                case OVERDRIVEN:
                    overdriven++;
                    break;
                default:
                    break;
            }
        }

        public void reset() {
            lowConfidence = 0;
            lowPower = 0;
            overdriven = 0;
        }

        public int total() {
            return lowConfidence + lowPower + overdriven;
        }

        /** One line for the pane, or null when nothing has been dropped. */
        public String describe() {
            if (total() == 0)
                return null;
            StringBuilder s = new StringBuilder();
            s.append(total()).append(" dropped: ");
            boolean first = true;
            if (overdriven > 0) {
                // First, because it is the only one with a fix the operator
                // can apply right now.
                s.append(String.format(Locale.US,
                        "%d with the front end saturated -- turn the radio's "
                                + "gain down", overdriven));
                first = false;
            }
            if (lowConfidence > 0) {
                if (!first)
                    s.append("; ");
                s.append(String.format(Locale.US,
                        "%d too uncertain", lowConfidence));
                first = false;
            }
            if (lowPower > 0) {
                if (!first)
                    s.append("; ");
                s.append(String.format(Locale.US,
                        "%d too weak", lowPower));
            }
            return s.append('.').toString();
        }
    }
}

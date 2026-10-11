package com.atakmap.android.signaldf.data;

/**
 * The one sentence that has to ride with every product this plugin makes.
 *
 * <p>Which way round each of the radio's two feeds writes its bearing was read
 * out of {@code krakensdr_doa}'s own source and confirmed against an
 * independent implementation. It has never been checked against a transmitter
 * at a measured bearing, and the radio's source carries a comment saying the
 * CSV's mirror has changed with an app release before.
 *
 * <p>At 0.1 that caveat lived in the README and in the wide pane, and that was
 * proportionate: the plugin drew one line and called it a direction. It is not
 * proportionate now. The plugin computes a position, puts a marker on it, draws
 * an error ellipse around it and offers to send all of that to a team. A marker
 * saying "the transmitter is here, within 200 m" is a far stronger claim than a
 * line, and an error ellipse is a claim about how wrong it can be -- which is
 * precisely the thing that is not yet known.
 *
 * <p><b>The part that matters most is the sharing.</b> An operator can be told
 * once, in a pane, and remember. A teammate who receives a bearing on their own
 * map was never told anything: they see a line, or a marker, drawn by ATAK in
 * the ordinary way, and nothing about it suggests it rests on an unvalidated
 * convention. So the caveat goes in the remarks of everything that leaves this
 * phone, and in the remarks of the fix marker, where a tap finds it.
 *
 * <p>This constant exists so there is exactly one wording and one place to
 * delete it from. When the bench run in
 * {@code BENCH-SignalDF-bearing-validation.md} passes, every use of this goes
 * at once -- and if it fails, the fix is a sign change and the same one place
 * still governs what is said.
 */
public final class Caveat {

    /**
     * Short enough to sit at the end of a CoT remark without burying it.
     */
    public static final String UNVERIFIED_BEARING =
            "Signal DF 0.2: the bearing convention has not yet been validated "
                    + "against a transmitter at a measured bearing. Treat the "
                    + "direction as unconfirmed.";

    private Caveat() {
    }
}

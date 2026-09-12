package com.atakmap.android.comms.data;

/**
 * How a net's designator is written for a person.
 *
 * <p>In the data package rather than beside the pane because the map layer writes
 * the same designators into a marker's remarks, and a layer has no business reaching
 * into the UI for a string helper.
 */
public final class Nets {

    private Nets() {
    }

    /**
     * A designator without its repeater suffix.
     *
     * <p>Everything in this catalog is a fixed repeater site, so an R on the end says
     * only what the list already says (operator, 2026-09-11: "can you take off the R
     * that is redundant we know its a repeater thats why its listed").
     *
     * <p>Both spellings the sources use are handled: "RVC C5 R" with a space, and
     * "XSD C11R" where the R rides straight on the channel number. In the second form
     * only an R directly after a digit is taken, so no name loses a letter of its own
     * -- FIR-A, UTAC42 and 8CALL90 are left alone.
     */
    public static String noRepeaterSuffix(String designator) {
        if (designator == null)
            return "";
        final String t = designator.trim();
        if (t.endsWith(" R"))
            return t.substring(0, t.length() - 2).trim();
        if (t.length() > 1 && t.charAt(t.length() - 1) == 'R'
                && Character.isDigit(t.charAt(t.length() - 2)))
            return t.substring(0, t.length() - 1);
        return t;
    }
}

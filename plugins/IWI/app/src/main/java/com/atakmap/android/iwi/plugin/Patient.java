package com.atakmap.android.iwi.plugin;

/**
 * One patient in an IWI: a number, never a name (IRPG p. 107: "Do not use patient
 * name(s) on the radio"), a triage color, and what the follow-ups have said.
 *
 * <p>No Android types, so the radio script can be run against it on a desktop JVM.
 */
public final class Patient {

    /** Stored colors are the triage keys: red, yellow, green, black. */
    public int n;
    public String color = IwiState.RED;
    public String assessment = "";
    public String treatment = "";
    public String weight = "";
    /** When anything on the card last changed, or 0 when nothing has. */
    public long updatedMs;

    public boolean hasNotes() {
        return !assessment.trim().isEmpty() || !treatment.trim().isEmpty()
                || !weight.trim().isEmpty();
    }

    /** Most severe first: Red, Yellow, Green, then Black. */
    public static int rank(String color) {
        if (IwiState.RED.equals(color))
            return 0;
        if (IwiState.YELLOW.equals(color))
            return 1;
        if (IwiState.GREEN.equals(color))
            return 2;
        return 3;
    }

    public static String name(String color) {
        if (IwiState.RED.equals(color))
            return "Red";
        if (IwiState.YELLOW.equals(color))
            return "Yellow";
        if (IwiState.GREEN.equals(color))
            return "Green";
        return "Black";
    }

    public static final String[] COLORS = {
            IwiState.RED, IwiState.YELLOW, IwiState.GREEN, IwiState.BLACK
    };
}

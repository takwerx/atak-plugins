package com.atakmap.android.comms.data;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * CTCSS tones by number and by name.
 *
 * <p>CAL FIRE numbers its tones, and so do FIRESCOPE, BLM, BIA and USFS in
 * California: "Tone 8" is 103.5 Hz on every one of their call plans. Operators say
 * both, so the plugin shows both, "103.5 (Tone 8)", everywhere a tone appears, and
 * the search matches either.
 */
public final class Tones {

    private static final Map<Integer, Double> BY_NUMBER = new HashMap<>();
    private static final Map<Long, Integer> BY_TENTHS = new HashMap<>();

    static {
        // The California standard 32, in case the catalog does not carry them.
        final double[] hz = { 110.9, 123.0, 131.8, 136.5, 146.2, 156.7, 167.9, 103.5,
                100.0, 107.2, 114.8, 127.3, 141.3, 151.4, 162.2, 192.8, 67.0, 71.9, 74.4,
                77.0, 79.7, 82.5, 85.4, 88.5, 91.5, 94.8, 97.4, 118.8, 173.8, 179.9, 186.2,
                203.5 };
        for (int i = 0; i < hz.length; i++)
            put(i + 1, hz[i]);
    }

    private Tones() {
    }

    private static void put(int number, double hz) {
        BY_NUMBER.put(number, hz);
        BY_TENTHS.put(Math.round(hz * 10), number);
    }

    /** Take the table from the catalog, so a plan change does not need a release. */
    public static synchronized void use(Map<Integer, Double> table) {
        if (table == null || table.isEmpty())
            return;
        BY_NUMBER.clear();
        BY_TENTHS.clear();
        for (Map.Entry<Integer, Double> e : table.entrySet())
            put(e.getKey(), e.getValue());
    }

    /** The CA standard number for a tone, or 0 when it has none. */
    public static synchronized int numberOf(double hz) {
        if (Double.isNaN(hz))
            return 0;
        final Integer n = BY_TENTHS.get(Math.round(hz * 10));
        return n == null ? 0 : n;
    }

    public static synchronized double hzOf(int number) {
        final Double d = BY_NUMBER.get(number);
        return d == null ? Double.NaN : d;
    }

    /** "103.5 (Tone 8)", "103.5", "CSQ" for none, or the text ("OST") as given. */
    public static String describe(double hz, String text) {
        if (!Double.isNaN(hz)) {
            final int n = numberOf(hz);
            final String v = String.format(Locale.US, "%.1f", hz);
            return n > 0 ? v + " (Tone " + n + ")" : v;
        }
        if (text != null && !text.isEmpty())
            return "OST".equals(text) ? "OST (site tone)" : text;
        return "CSQ";
    }

    /**
     * Whether a query names this tone: "103.5", "103", "tone 8", "t8", "T-8".
     * A bare small number is not a tone query on its own; "8" would match half the
     * catalog through tone numbers alone.
     */
    public static boolean matches(double hz, String q) {
        if (Double.isNaN(hz) || q == null || q.isEmpty())
            return false;
        final String s = q.trim().toLowerCase(Locale.US);
        final int n = numberOf(hz);
        if (n > 0) {
            if (s.equals("tone " + n) || s.equals("tone" + n) || s.equals("t" + n)
                    || s.equals("t-" + n) || s.equals("pl " + n) || s.equals("pl" + n))
                return true;
        }
        if (!s.matches("\\d{2,3}(\\.\\d)?"))
            return false;
        final String v = String.format(Locale.US, "%.1f", hz);
        return v.equals(s) || v.startsWith(s + ".") || v.startsWith(s);
    }
}

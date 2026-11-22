package com.atakmap.android.comms.data;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * CTCSS tones by number and by name.
 *
 * <p>CAL FIRE numbers its tones, and so do FIRESCOPE, BLM, BIA and USFS in
 * California: "Tone 8" is 103.5 Hz on every one of their call plans. Operators say
 * both, so the plugin shows both, "Tone 8 (103.5)", wherever the catalog gives a
 * tone its number, and the search matches either.
 *
 * <p>The number is the plan's, not the frequency's, so it comes from the catalog
 * and is never looked up from the Hz. Boise's interagency plan numbers its tones
 * too, and agrees with California for 1 to 8 and then does not: 100.0 Hz is Tone 9
 * in California and Tone 11 in Boise, where Tone 9 is 179.9. A reverse lookup put
 * "Tone 9" on Idaho sites, a number that exists in the local plan and opens
 * nothing (takwerx/comms#2).
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

    /**
     * The California standard number for a tone, or 0 when it has none. Only for a
     * site in California: anywhere else the number means a different frequency.
     */
    public static synchronized int californiaNumberOf(double hz) {
        if (Double.isNaN(hz))
            return 0;
        final Integer n = BY_TENTHS.get(Math.round(hz * 10));
        return n == null ? 0 : n;
    }

    public static synchronized double hzOf(int number) {
        final Double d = BY_NUMBER.get(number);
        return d == null ? Double.NaN : d;
    }

    /**
     * "Tone 8 (103.5)" when the plan numbers it, "103.5" when it does not, or the
     * text as given.
     *
     * @param number the plan's own number for this tone, or 0 for none
     */
    public static String describe(double hz, int number, String text) {
        if (!Double.isNaN(hz)) {
            final String v = String.format(Locale.US, "%.1f", hz);
            return number > 0 ? "Tone " + number + " (" + v + ")" : v;
        }
        // OST is a property of a frequency, never of a repeater: the plan leaves the
        // tone to the operator. The repeater still has one fixed tone that opens it,
        // and if this catalog does not carry it, the honest answer is that it does not
        // (operator, 2026-09-11: "a frequency can be OST, a site can never be OST, a
        // site has a fixed tone").
        if (text != null && !text.isEmpty())
            return "OST".equals(text) ? NOT_LISTED : text;
        return "none";
    }

    /** What a site's tone line says when the catalog does not carry the tone. */
    private static final String NOT_LISTED = "tone not listed";

    /**
     * The one tone an operator needs: what you transmit to open this repeater.
     *
     * <p>A call plan prints two. The access tone is the site's own and is the number
     * you dial to key that mountain; the other is the net's, identical at every site
     * on the net, so it can never tell you which repeater to use. Only the access
     * tone is shown (operator, 2026-09-10: "just need the tone to access the
     * repeater").
     *
     * <p>Every repeater has one fixed tone. When this catalog does not carry it the
     * line says so rather than implying the operator picks it: "tone not listed", or
     * the reason when a source gives one ("trunked" for a digital trunked channel,
     * which has no CTCSS tone at all, "tone not published" where the plan withholds
     * it). Never "site tone" -- a site's tone is never operator selectable.
     *
     * @param accessHz   the site's own tone in Hz, or NaN
     * @param accessNum  the plan's number for it, or 0
     * @param accessText what the plan printed in place of the site's tone
     * @param netHz      the net-wide tone in Hz, used only when the site has none
     * @param netNum     the plan's number for it, or 0
     * @param netText    what the plan printed for the net
     */
    public static String line(double accessHz, int accessNum, String accessText,
            double netHz, int netNum, String netText) {
        if (!Double.isNaN(accessHz))
            return describe(accessHz, accessNum, accessText);
        if (!Double.isNaN(netHz))
            return describe(netHz, netNum, netText);
        final String t = describe(Double.NaN, 0, accessText != null && !accessText.isEmpty()
                ? accessText : netText);
        if ("none".equals(t))
            return NOT_LISTED;
        // A trunked channel carries no CTCSS tone at all, which is a different fact
        // from one this catalog is missing, so it is said in full.
        return "trunked".equals(t) ? "trunked, no tone" : t;
    }

    /**
     * Whether a query names this tone: "103.5", "103", "tone 8", "t8", "T-8".
     * A bare small number is not a tone query on its own; "8" would match half the
     * catalog through tone numbers alone.
     *
     * @param n the plan's own number for this tone, or 0 for none
     */
    public static boolean matches(double hz, int n, String q) {
        if (Double.isNaN(hz) || q == null || q.isEmpty())
            return false;
        final String s = q.trim().toLowerCase(Locale.US);
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

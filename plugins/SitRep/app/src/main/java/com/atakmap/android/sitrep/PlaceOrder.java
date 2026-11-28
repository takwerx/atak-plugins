package com.atakmap.android.sitrep;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The order a place category is listed in, on the report page and in the shared
 * PDF alike: the area's own agency first, its headquarters then its stations by
 * number; then other agencies inside the area by name; then the ones outside,
 * nearest first as the section found them (operator, 2026-11-14). Plain Java, so
 * the PDF tests can use it.
 */
public final class PlaceOrder {

    private PlaceOrder() {
    }

    /** A sorted copy of one category's places. */
    public static List<JSONObject> sorted(List<JSONObject> places, Report r) {
        final String own = ownName(r);
        final List<JSONObject> out = new ArrayList<>(places);
        Collections.sort(out, new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject a, JSONObject b) {
                final int ra = rank(a, own), rb = rank(b, own);
                if (ra != rb)
                    return ra - rb;
                // Outside the area: keep the nearest-first order (the sort is stable).
                return ra == 3 ? 0 : naturalCompare(a.optString("n"), b.optString("n"));
            }
        });
        return out;
    }

    /**
     * Where a place goes in its list: 0 the area's own headquarters, 1 the area's own
     * stations, 2 another agency inside the area, 3 another agency outside it.
     */
    static int rank(JSONObject p, String own) {
        final String n = p.optString("n");
        if (startsWithWord(n, own))
            return n.matches("(?i).*\\b(headquarters|HQ)\\b.*") ? 0 : 1;
        return outsideArea(p) ? 3 : 2;
    }

    /** A place the section found outside the area: its last line says where. */
    static boolean outsideArea(JSONObject p) {
        final JSONArray info = p.optJSONArray("i");
        return info != null && info.length() > 0
                && info.optString(info.length() - 1).endsWith("of the area.");
    }

    /**
     * The area's own name as an agency would start with it: "Portland city, OR" is
     * Portland, "Riverside County" stays Riverside County. Null when there is none.
     */
    static String ownName(Report r) {
        String n = r.label != null ? r.label : r.place;
        if (n == null)
            return null;
        final int comma = n.indexOf(',');
        if (comma > 0)
            n = n.substring(0, comma);
        n = n.trim().replaceFirst("(?i)\\s+(city|town|village|CDP|borough|municipality)$", "").trim();
        return n.length() < 3 ? null : n;
    }

    /** Whether a name starts with the given words, as whole words. */
    static boolean startsWithWord(String name, String prefix) {
        if (prefix == null || name.length() < prefix.length()
                || !name.regionMatches(true, 0, prefix, 0, prefix.length()))
            return false;
        return name.length() == prefix.length() || !Character.isLetterOrDigit(name.charAt(prefix.length()));
    }

    /** Text order, except that runs of digits compare as numbers. */
    static int naturalCompare(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            final char ca = a.charAt(i), cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int ei = i, ej = j;
                while (ei < a.length() && Character.isDigit(a.charAt(ei)))
                    ei++;
                while (ej < b.length() && Character.isDigit(b.charAt(ej)))
                    ej++;
                final String na = a.substring(i, ei).replaceFirst("^0+(?=.)", "");
                final String nb = b.substring(j, ej).replaceFirst("^0+(?=.)", "");
                if (na.length() != nb.length())
                    return na.length() - nb.length();
                final int c = na.compareTo(nb);
                if (c != 0)
                    return c;
                i = ei;
                j = ej;
                continue;
            }
            final int c = Character.compare(Character.toLowerCase(ca), Character.toLowerCase(cb));
            if (c != 0)
                return c;
            i++;
            j++;
        }
        return (a.length() - i) - (b.length() - j);
    }
}

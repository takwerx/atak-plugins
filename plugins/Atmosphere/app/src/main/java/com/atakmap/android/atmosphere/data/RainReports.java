package com.atakmap.android.atmosphere.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Weather Forecast Office's rainfall summary, the RRM product ("Miscellaneous
 * Hydrologic Data"): the office's own table of what its gauges measured, the one
 * the operator reads on forecast.weather.gov (2026-10-11: "nws has all this ...
 * use it"). Thirty-three offices publish one, and no two write it the same way:
 * San Diego lists an id, a name, inches and an elevation; Los Angeles a name,
 * dots and inches; Seattle a name and seven columns headed 1H to 72H; Norman a
 * paragraph. This reads what it can into rows and keeps every line it cannot, so
 * the report is always shown as written.
 *
 * <p>No Android types; tested.
 */
public final class RainReports {

    /** A value cell: a number of inches, "T" for a trace, "M" or "MSG" for missing. */
    private static final Pattern VALUE = Pattern.compile("^(T|M|MSG|-?\\d+\\.\\d+|\\d+)$");
    private static final Pattern DOTTED = Pattern.compile("^(.+?)\\.{2,}\\s*(T|M|MSG|-?\\d+\\.\\d+)\\s*$");
    private static final Pattern RANK = Pattern.compile("^\\d+\\.\\s+");
    private static final Pattern ID = Pattern.compile("^[A-Z][A-Z0-9]{2,5}$");
    private static final Pattern COLUMN = Pattern.compile("^\\d+H$");

    /** One gauge's line. */
    public static final class Row {
        public final String name, id;
        /** The cells as written, "T", "M" and numbers alike. */
        public final String[] values;
        /** Feet, or Integer.MIN_VALUE when the line carries none. */
        public final int elevationFt;
        /** The inches to sort on: the 24 h column where there is one, else the first; NaN for T or missing. */
        public final double inches;

        Row(String name, String id, String[] values, int elevationFt, double inches) {
            this.name = name;
            this.id = id;
            this.values = values;
            this.elevationFt = elevationFt;
            this.inches = inches;
        }
    }

    /** A heading in the report and the rows under it. */
    public static final class Section {
        public final String name;
        public final List<Row> rows = new ArrayList<>();

        Section(String name) {
            this.name = name;
        }
    }

    public static final class Report {
        /** "NATIONAL WEATHER SERVICE SAN DIEGO", as written. */
        public String office = "";
        /** "1234 PM PDT SUN OCT 11 2026". */
        public String issued = "";
        /** The lines that say what period the numbers cover. */
        public final List<String> period = new ArrayList<>();
        /** Column headings when the table has them: "1H", "3H", ..., else empty. */
        public String[] columns = new String[0];
        public final List<Section> sections = new ArrayList<>();
        public final String text;

        Report(String text) {
            this.text = text;
        }

        public int rowCount() {
            int n = 0;
            for (Section s : sections)
                n += s.rows.size();
            return n;
        }
    }

    public static Report parse(String text) {
        final Report r = new Report(text == null ? "" : text);
        if (text == null)
            return r;
        Section current = null;
        final String[] lines = text.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            final String raw = lines[i];
            final String line = raw.trim();
            if (line.isEmpty() || line.equals("$$") || line.startsWith("&&"))
                continue;
            final String up = line.toUpperCase(Locale.US);
            if (r.office.isEmpty() && up.startsWith("NATIONAL WEATHER SERVICE ")) {
                r.office = line;
                if (i + 1 < lines.length)
                    r.issued = lines[i + 1].trim();
                continue;
            }
            if (up.contains(" AS OF ") || up.contains("ENDING AT") || up.contains("THROUGH ")
                    || up.contains("TOTALS IN INCHES") || up.contains("PERIOD")) {
                if (r.period.size() < 3 && !up.contains("H = HOURS"))
                    r.period.add(line);
                if (!up.contains("H = HOURS"))
                    continue;
            }
            // A column header: every token "1H" .. "72H", maybe after a "Name..." heading.
            final String[] cols = columnsOf(line);
            if (cols != null) {
                r.columns = cols;
                final int dots = line.indexOf("...");
                if (dots > 0) {
                    current = new Section(clean(line.substring(0, dots)));
                    r.sections.add(current);
                }
                continue;
            }
            // A section heading: ".SAN DIEGO COUNTY VALLEYS..." or ".LA CO COAST & METRO".
            // ".SAN DIEGO COUNTY VALLEYS..." is a section; "...PRELIMINARY TOTALS..." a banner.
            if (line.startsWith(".") && !line.startsWith("...") && !DOTTED.matcher(line).matches()) {
                current = new Section(clean(line));
                r.sections.add(current);
                continue;
            }
            if (up.startsWith("ID ") || up.startsWith("STATION ") || up.startsWith("* ") || up.startsWith("*STATIONS"))
                continue;
            final Row row = row(line, r.columns.length, sortColumn(r.columns));
            if (row == null)
                continue;
            if (current == null) {
                current = new Section("");
                r.sections.add(current);
            }
            current.rows.add(row);
        }
        return r;
    }

    /** The column headings on a line, or null when it is not a header line. */
    private static String[] columnsOf(String line) {
        String tail = line;
        final int dots = line.indexOf("...");
        if (dots >= 0)
            tail = line.substring(dots).replace(".", " ");
        final String[] toks = tail.trim().split("\\s+");
        if (toks.length < 2)
            return null;
        for (String t : toks)
            if (!COLUMN.matcher(t.toUpperCase(Locale.US)).matches())
                return null;
        return toks;
    }

    /** One gauge line into a row, or null when the line is prose. */
    static Row row(String line, int columns, int mainColumn) {
        final Matcher dotted = DOTTED.matcher(line);
        if (dotted.matches()) {
            final String v = dotted.group(2);
            final Matcher a = AIRPORT.matcher(dotted.group(1));
            return new Row(clean(dotted.group(1)), a.find() ? a.group(1) : "", new String[] { v },
                    Integer.MIN_VALUE, inches(v));
        }
        final String[] toks = RANK.matcher(line).replaceFirst("").trim().split("\\s+");
        // Values hang off the end of the line.
        int first = toks.length;
        while (first > 0 && VALUE.matcher(toks[first - 1]).matches())
            first--;
        final int nValues = toks.length - first;
        if (nValues == 0 || first == 0)
            return null;
        String[] values = new String[nValues];
        System.arraycopy(toks, first, values, 0, nValues);
        int elevation = Integer.MIN_VALUE;
        // With no column header, a trailing whole number after a decimal is the elevation.
        // Six digits at most: an elevation in feet, never a timestamp that happens to
        // trail a decimal (security review, 2026-10-11: an unguarded parse on main).
        if (columns == 0 && nValues >= 2 && values[nValues - 1].matches("\\d{1,6}")
                && values[nValues - 2].contains(".")) {
            elevation = Integer.parseInt(values[nValues - 1]);
            final String[] trimmed = new String[nValues - 1];
            System.arraycopy(values, 0, trimmed, 0, nValues - 1);
            values = trimmed;
        }
        // A bare whole number with no decimal anywhere is not a rainfall line.
        boolean decimal = false;
        for (String v : values)
            if (v.contains(".") || v.equals("T") || v.equals("M") || v.equals("MSG"))
                decimal = true;
        if (!decimal)
            return null;
        String id = "";
        int nameFrom = 0;
        if (first >= 2 && ID.matcher(toks[0]).matches() && toks[0].matches(".*\\d.*|[A-Z]{3,4}")) {
            id = toks[0];
            nameFrom = 1;
        }
        final StringBuilder name = new StringBuilder();
        for (int i = nameFrom; i < first; i++)
            name.append(i > nameFrom ? " " : "").append(toks[i]);
        if (name.length() == 0)
            return null;
        // Los Angeles writes names only, with the airport code in brackets: "LA AIRPORT(KLAX)".
        if (id.isEmpty()) {
            final Matcher a = AIRPORT.matcher(name);
            if (a.find())
                id = a.group(1);
        }
        // The 24 h column where the header names one, else the first value.
        String main = values[0];
        if (columns > 0 && values.length == columns)
            main = values[Math.min(values.length - 1, Math.max(0, mainColumn))];
        return new Row(clean(name.toString()), id, values, elevation, inches(main));
    }

    private static double inches(String v) {
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** "SAN DIEGO COUNTY VALLEYS" from ".SAN DIEGO COUNTY VALLEYS...", in title case. */
    /** An airport code in brackets inside a name, the one id some offices write. */
    private static final Pattern AIRPORT = Pattern.compile("\\((K[A-Z0-9]{3})\\)");

    public static String clean(String heading) {
        String s = heading.trim();
        while (s.startsWith("."))
            s = s.substring(1);
        while (s.endsWith("."))
            s = s.substring(0, s.length() - 1);
        s = s.trim();
        if (s.equals(s.toUpperCase(Locale.US))) {
            final StringBuilder b = new StringBuilder();
            boolean start = true;
            for (char c : s.toCharArray()) {
                b.append(start ? Character.toUpperCase(c) : Character.toLowerCase(c));
                start = !Character.isLetterOrDigit(c) && c != '\'';
            }
            s = b.toString().replace(" Raws", " RAWS").replace(" Crs", " CRS").replace("(k", "(K");
        }
        return s;
    }

    /** The column the sort should use for a header: 24H if present, else the last. */
    public static int sortColumn(String[] columns) {
        for (int i = 0; i < columns.length; i++)
            if (columns[i].equalsIgnoreCase("24H"))
                return i;
        return Math.max(0, columns.length - 1);
    }

    private RainReports() {
    }
}

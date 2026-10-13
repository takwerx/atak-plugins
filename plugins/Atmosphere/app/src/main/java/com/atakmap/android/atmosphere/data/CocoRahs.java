package com.atakmap.android.atmosphere.data;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * CoCoRaHS, the Community Collaborative Rain, Hail and Snow Network: volunteers read a
 * 4-inch gauge once a day, around 7 am, and report the 24 hours. Its export serves one
 * state's reports for one date as CSV, keyless ({@code data.cocorahs.org}); California
 * runs about 400 reports a day. The data policy (checked 2026-10-12) makes the data
 * free "for the purposes of ... protecting life and property" and asks that the source
 * be acknowledged, which the details and the credit line do.
 *
 * <p>Asked for because the Rain Gauges layer was empty across Northern California: no
 * office there publishes a rainfall report and USGS keeps ten rain gauges between San
 * Jose and Tahoe (operator, 2026-10-12: "so there are no rain gauges in nor cal").
 */
public final class CocoRahs {

    public static final String HOST = "data.cocorahs.org";

    /** The state's daily reports dated {@code date}, local to the observer. */
    public static String url(String state, Date date) {
        return "https://" + HOST + "/export/exportreports.aspx?ReportType=Daily&Format=CSV&State="
                + state + "&ReportDateType=reportdate&Date="
                + new SimpleDateFormat("MM/dd/yyyy", Locale.US).format(date)
                + "&TimesInGMT=False";
    }

    public static final class Report {
        public final String station, name, date, time;
        public final double lat, lon;
        /** Inches in the 24 hours; 0 with {@link #trace} for a trace; NaN when not reported. */
        public final double inches;
        public final boolean trace;

        Report(String station, String name, String date, String time, double lat, double lon,
                double inches, boolean trace) {
            this.station = station;
            this.name = name;
            this.date = date;
            this.time = time;
            this.lat = lat;
            this.lon = lon;
            this.inches = inches;
            this.trace = trace;
        }
    }

    /**
     * Every report with a position and a precipitation value. Fields are comma
     * separated with a space after each comma; names carry no commas or quotes in the
     * export, but a quoted field is read whole anyway.
     */
    public static List<Report> parse(String body) {
        final List<Report> out = new ArrayList<>();
        if (body == null || body.isEmpty())
            return out;
        final String[] lines = body.split("\r?\n");
        if (lines.length < 2 || !lines[0].startsWith("ObservationDate"))
            return out;
        final String[] header = split(lines[0]);
        final int iDate = index(header, "ObservationDate"), iTime = index(header, "ObservationTime"),
                iStation = index(header, "StationNumber"), iName = index(header, "StationName"),
                iLat = index(header, "Latitude"), iLon = index(header, "Longitude"),
                iPrecip = index(header, "TotalPrecipAmt");
        if (iStation < 0 || iLat < 0 || iLon < 0 || iPrecip < 0)
            return out;
        for (int i = 1; i < lines.length; i++) {
            final String[] f = split(lines[i]);
            if (f.length <= Math.max(Math.max(iStation, iLat), Math.max(iLon, iPrecip)))
                continue;
            try {
                final double lat = Double.parseDouble(f[iLat]);
                final double lon = Double.parseDouble(f[iLon]);
                if (Double.isNaN(lat) || Double.isNaN(lon) || Double.isInfinite(lat)
                        || Double.isInfinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180
                        || (lat == 0 && lon == 0))
                    continue;
                final String p = f[iPrecip].trim();
                double inches;
                boolean trace = false;
                if (p.equalsIgnoreCase("T")) {
                    inches = 0;
                    trace = true;
                } else if (p.equalsIgnoreCase("NA") || p.isEmpty()) {
                    continue;
                } else {
                    inches = Double.parseDouble(p);
                    if (Double.isNaN(inches) || Double.isInfinite(inches) || inches < 0 || inches > 60)
                        continue;
                }
                out.add(new Report(f[iStation].trim(), iName < 0 ? "" : f[iName].trim(),
                        iDate < 0 ? "" : f[iDate].trim(), iTime < 0 ? "" : f[iTime].trim(),
                        lat, lon, inches, trace));
            } catch (RuntimeException e) {
                // A row that is not a report.
            }
        }
        return out;
    }

    /** One report per station, the later date winning; order kept. */
    public static List<Report> newest(List<Report> all) {
        final Map<String, Report> by = new LinkedHashMap<>();
        for (Report r : all) {
            final Report have = by.get(r.station);
            if (have == null || r.date.compareTo(have.date) > 0)
                by.put(r.station, r);
        }
        return new ArrayList<>(by.values());
    }

    private static int index(String[] header, String name) {
        for (int i = 0; i < header.length; i++)
            if (header[i].trim().equalsIgnoreCase(name))
                return i;
        return -1;
    }

    static String[] split(String line) {
        final List<String> out = new ArrayList<>();
        final StringBuilder b = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            final char c = line.charAt(i);
            if (c == '"')
                quoted = !quoted;
            else if (c == ',' && !quoted) {
                out.add(b.toString().trim());
                b.setLength(0);
            } else
                b.append(c);
        }
        out.add(b.toString().trim());
        return out.toArray(new String[0]);
    }

    private CocoRahs() {
    }
}

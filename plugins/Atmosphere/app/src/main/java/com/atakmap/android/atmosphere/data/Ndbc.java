package com.atakmap.android.atmosphere.data;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Buoys and coastal stations and what they read right now, from NDBC.
 *
 * <p>{@code latest_obs.txt} is one fixed-width text file for the whole network --
 * 865 stations, 100 KB, no key -- with each station's latest observation on one
 * line. There is no query: the file is fetched whole and filtered here, so this
 * layer never sends a position. Names come from {@code station_table.txt}
 * (1,940 rows, 360 KB), fetched once a session.
 *
 * <p>Two kinds of station share the file. A met buoy carries wind, air and water
 * temperature, dewpoint, pressure; a waverider carries wave height, period and
 * direction and nothing else. A row shows what it has and says nothing about the
 * rest -- a waverider with no wind is not calm.
 *
 * <p><b>Parsed by the header row, never by counted offsets.</b> Eight fields precede
 * the wind direction, five of them the timestamp split apart, and reading by
 * position took the day of the month for a bearing on the first try (PLAN 10e).
 * {@code MM} is missing. Winds are m/s, waves meters, temperatures Celsius,
 * pressure hPa, visibility nautical miles, tide feet.
 *
 * <p>There is no relative humidity in the file; it is derived from air temperature
 * and dewpoint (Magnus), so a buoy reads beside a RAWS on the same terms.
 *
 * <p>No Android types, so the parsing is tested.
 */
public final class Ndbc {

    public static final String HOST = "www.ndbc.noaa.gov";
    public static final String OBS_URL = "https://" + HOST + "/data/latest_obs/latest_obs.txt";
    public static final String STATIONS_URL = "https://" + HOST + "/data/stations/station_table.txt";

    /** One station and its latest reading. Any value may be NaN. */
    public static final class Buoy {
        public final String id;
        public final double latitude, longitude;
        /** UTC millis, or 0. */
        public final long observedAt;
        /** Degrees the wind is from; m/s. */
        public final double windFromDeg, windMs, gustMs;
        /** Meters; seconds; degrees the waves are from. */
        public final double waveHeightM, dominantPeriodS, averagePeriodS, waveFromDeg;
        /** hPa, and its three-hour change. */
        public final double pressureHpa, pressureTendencyHpa;
        /** Celsius. */
        public final double airTempC, waterTempC, dewpointC;
        /** Nautical miles. */
        public final double visibilityNmi;
        /** Feet above MLLW. */
        public final double tideFt;
        /** From the station table; empty until it is loaded. */
        public String name = "";
        public String owner = "", type = "";

        Buoy(String id, double latitude, double longitude, long observedAt,
                double windFromDeg, double windMs, double gustMs, double waveHeightM,
                double dominantPeriodS, double averagePeriodS, double waveFromDeg,
                double pressureHpa, double pressureTendencyHpa, double airTempC,
                double waterTempC, double dewpointC, double visibilityNmi, double tideFt) {
            this.id = id;
            this.latitude = latitude;
            this.longitude = longitude;
            this.observedAt = observedAt;
            this.windFromDeg = windFromDeg;
            this.windMs = windMs;
            this.gustMs = gustMs;
            this.waveHeightM = waveHeightM;
            this.dominantPeriodS = dominantPeriodS;
            this.averagePeriodS = averagePeriodS;
            this.waveFromDeg = waveFromDeg;
            this.pressureHpa = pressureHpa;
            this.pressureTendencyHpa = pressureTendencyHpa;
            this.airTempC = airTempC;
            this.waterTempC = waterTempC;
            this.dewpointC = dewpointC;
            this.visibilityNmi = visibilityNmi;
            this.tideFt = tideFt;
        }

        public boolean hasWind() {
            return !Double.isNaN(windMs);
        }

        public boolean hasWaves() {
            return !Double.isNaN(waveHeightM);
        }

        /** Nothing a crew reads: no wind, no waves, no air temperature. */
        public boolean silent() {
            return !hasWind() && !hasWaves() && Double.isNaN(airTempC);
        }

        public double ageHours(long now) {
            if (observedAt <= 0)
                return Double.MAX_VALUE;
            return (now - observedAt) / 3_600_000.0;
        }

        /** Older than three hours: buoys report hourly, waveriders every half hour. */
        public boolean stale(long now) {
            return ageHours(now) > 3.0;
        }

        /** Relative humidity from air temperature and dewpoint, percent, or NaN. */
        public double relativeHumidity() {
            return humidity(airTempC, dewpointC);
        }

        /** Where the reading sits on the NWS marine ladder: sustained wind in knots, seas in feet. */
        public MarineBand band() {
            return MarineBand.of(windMs * 1.943844, waveHeightM / 0.3048);
        }

        /** The station's label: the short form of its name, or its id when the table has not loaded. */
        public String label() {
            final String s = shortName(name);
            return s.isEmpty() ? id : s;
        }
    }

    /**
     * The name a person calls the station: "Cape San Martin" from NDBC's "CAPE SAN
     * MARTIN - 55NM West NW of Morro Bay, CA" (operator, 2026-09-26: "can it just be
     * cape san martin?").
     *
     * <p>The table names a station four ways, measured over its 1,940 rows: a CO-OPS
     * id then the place ("9410170 - San Diego, CA"); a headline then a bearing
     * ("CAPE SAN MARTIN - 55NM ...", once with no dash at all, "WEST SANTA BARBARA
     * 38 NM West of ..."); a place with its state and CDIP number ("Santa Monica
     * Bay, CA (028)", "Ventura Nearshore, CA - 169", "Aptos Creek Nearshore, CA
     * 275"); or a bare place. The bearing, the state and the number stay in the
     * record; the pill, the list and the title get the place. A drifter's "SD 1063"
     * is left alone: a name with a number in it is not title-cased.
     */
    public static String shortName(String full) {
        if (full == null)
            return "";
        String s = full.trim().replaceAll("\\s+", " ");
        if (s.isEmpty())
            return "";
        final java.util.regex.Matcher coops = java.util.regex.Pattern
                .compile("^\\d{7} - (.+)$").matcher(s);
        if (coops.matches()) {
            s = coops.group(1);
        } else if (s.contains(" - ")) {
            s = s.substring(0, s.indexOf(" - "));
        } else {
            s = s.replaceFirst("\\s+\\d+\\s*NM\\b.*$", "");
        }
        s = s.replaceFirst("[\\s-]+\\(?\\d{3}\\)?\\s*$", "");    // the CDIP number
        s = s.replaceFirst(",\\s*[A-Z]{2}\\s*$", "");              // the state
        s = s.trim();
        if (s.equals(s.toUpperCase(Locale.US)) && !s.matches(".*\\d.*")) {
            final StringBuilder b = new StringBuilder();
            for (String w : s.split(" ")) {
                if (w.isEmpty())
                    continue;
                if (b.length() > 0)
                    b.append(' ');
                b.append(Character.toUpperCase(w.charAt(0)))
                        .append(w.substring(1).toLowerCase(Locale.US));
            }
            s = b.toString();
        }
        return s;
    }

    /** Magnus, over water: RH = 100 * e(Td) / e(T). Within half a percent of the tables. */
    public static double humidity(double tempC, double dewpointC) {
        if (Double.isNaN(tempC) || Double.isNaN(dewpointC))
            return Double.NaN;
        final double a = 17.625, b = 243.04;
        final double rh = 100.0 * Math.exp(a * dewpointC / (b + dewpointC))
                / Math.exp(a * tempC / (b + tempC));
        return Math.max(0, Math.min(100, rh));
    }

    /** Every station in {@code latest_obs.txt}. A row without a position is skipped. */
    public static List<Buoy> parseObs(String body) {
        final List<Buoy> out = new ArrayList<>();
        if (body == null || body.isEmpty())
            return out;
        String[] header = null;
        for (String line : body.split("\n")) {
            final String l = line.trim();
            if (l.isEmpty())
                continue;
            if (l.startsWith("#")) {
                // The first comment row is the header; the second is the units.
                if (header == null)
                    header = l.substring(1).trim().split("\\s+");
                continue;
            }
            if (header == null)
                continue;
            final String[] f = l.split("\\s+");
            if (f.length < header.length)
                continue;
            final Map<String, String> row = new HashMap<>();
            for (int i = 0; i < header.length; i++)
                row.put(header[i], f[i]);
            final double lat = num(row, "LAT"), lon = num(row, "LON");
            if (Double.isNaN(lat) || Double.isNaN(lon) || (lat == 0 && lon == 0))
                continue;
            final String id = row.get("STN");
            if (id == null || id.isEmpty())
                continue;
            out.add(new Buoy(id.toUpperCase(Locale.US), lat, lon, time(row),
                    num(row, "WDIR"), num(row, "WSPD"), num(row, "GST"), num(row, "WVHT"),
                    num(row, "DPD"), num(row, "APD"), num(row, "MWD"), num(row, "PRES"),
                    num(row, "PTDY"), num(row, "ATMP"), num(row, "WTMP"), num(row, "DEWP"),
                    num(row, "VIS"), num(row, "TIDE")));
        }
        return out;
    }

    private static double num(Map<String, String> row, String key) {
        final String v = row.get(key);
        if (v == null || v.equals("MM"))
            return Double.NaN;
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static long time(Map<String, String> row) {
        try {
            final Calendar c = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
            c.clear();
            c.set(Integer.parseInt(row.get("YYYY")), Integer.parseInt(row.get("MM")) - 1,
                    Integer.parseInt(row.get("DD")), Integer.parseInt(row.get("hh")),
                    Integer.parseInt(row.get("mm")), 0);
            return c.getTimeInMillis();
        } catch (Exception e) {
            return 0L;
        }
    }

    /** One row of the station table: what the station is called, and whose it is. */
    public static final class Station {
        public final String name, owner, type;

        Station(String name, String owner, String type) {
            this.name = name;
            this.owner = owner;
            this.type = type;
        }
    }

    /**
     * {@code station_table.txt}, by id. Pipe-separated: id | owner | type | hull |
     * name | payload | location | timezone | forecast | note. Entities in the
     * location column are HTML; the name column is plain.
     */
    public static Map<String, Station> parseStations(String body) {
        final Map<String, Station> out = new HashMap<>();
        if (body == null || body.isEmpty())
            return out;
        for (String line : body.split("\n")) {
            if (line.startsWith("#") || line.trim().isEmpty())
                continue;
            final String[] f = line.split("\\|", -1);
            if (f.length < 5)
                continue;
            final String id = f[0].trim().toUpperCase(Locale.US);
            if (id.isEmpty())
                continue;
            out.put(id, new Station(f[4].trim(), f[1].trim(), f[2].trim()));
        }
        return out;
    }

    /** Put the table's names on the buoys that have one. */
    public static void name(List<Buoy> buoys, Map<String, Station> table) {
        if (table == null)
            return;
        for (Buoy b : buoys) {
            final Station s = table.get(b.id);
            if (s != null) {
                b.name = s.name;
                b.owner = s.owner;
                b.type = s.type;
            }
        }
    }

    /** The buoys within {@code miles} of a point: the file is national, the layer is not. */
    public static List<Buoy> within(List<Buoy> all, double lat, double lon, double miles) {
        final List<Buoy> out = new ArrayList<>();
        final double dLat = miles / 69.0;
        final double dLon = miles / (69.0 * Math.max(0.1, Math.cos(Math.toRadians(lat))));
        for (Buoy b : all)
            if (Math.abs(b.latitude - lat) <= dLat && Math.abs(b.longitude - lon) <= dLon)
                out.add(b);
        return out;
    }

    private Ndbc() {
    }
}

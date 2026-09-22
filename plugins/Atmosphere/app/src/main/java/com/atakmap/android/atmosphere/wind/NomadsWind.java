package com.atakmap.android.atmosphere.wind;

import java.io.IOException;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The NOMADS grib filter as a wind source: 10 m wind for a lon/lat box, one small
 * GRIB2 per forecast hour. Pure request building and response reading; the overlay
 * does the fetching, so this is unit-tested against saved responses.
 *
 * <h3>Three models, chosen by how much ground is on screen</h3>
 *
 * One model cannot serve both a fire line and a continent. HRRR's 3 km cells are the
 * point of the thing close in, and asking for them across CONUS is seven megabytes;
 * RAP is the same ground at 13 km for a couple of hundred kilobytes; GFS is a quarter
 * degree and, unlike the other two, global, which is what serves Alaska, Hawaii and
 * the borders. {@link #forView} picks by the view's width and center, and the label
 * says which one answered, because a crew comparing two zooms deserves to know the
 * cells changed size.
 *
 * <p>Measured 2026-09-21/22 on real responses: HRRR 4x3 degrees 44 KB, RAP whole
 * CONUS 231 KB, GFS whole CONUS 75 KB, all in simple packing.
 */
public final class NomadsWind {

    public static final String HOST = "nomads.ncep.noaa.gov";
    private static final String FILTER = "https://" + HOST + "/cgi-bin/";

    /**
     * Forecast hours offered, whatever the model. HRRR carries 18 from an ordinary run
     * and 48 from a synoptic one, RAP 21, GFS 120; the scrubber would jump every time
     * the zoom changed models or the clock crossed a run. Eighteen hourly steps is the
     * shift a crew plans, and every model has them.
     */
    public static final int HOURS = 18;

    /** Where the HRRR and RAP CONUS grids reach, measured off their own headers. */
    private static final double CONUS_W = -132.7, CONUS_E = -60.2, CONUS_S = 21.2, CONUS_N = 52.1;

    public enum Model {
        /** 3 km, CONUS. Close in, where terrain shapes the wind. */
        HRRR("HRRR", "filter_hrrr_2d.pl", 1, 2, 8.0, 6.0,
                CONUS_W, CONUS_S, CONUS_E, CONUS_N),
        /** 13 km, the same CONUS ground, cheap enough to cover a region at once. */
        RAP("RAP", "filter_rap.pl", 1, 2, 40.0, 30.0,
                CONUS_W, CONUS_S, CONUS_E, CONUS_N),
        /** A quarter degree, global: Alaska, Hawaii, the borders, and any wide view. */
        GFS("GFS", "filter_gfs_0p25.pl", 6, 6, 120.0, 60.0,
                -180, -85, 180, 85);

        /** Shown to the operator beside the forecast hour. */
        public final String label;
        private final String script;
        /** Hours between runs. */
        private final int runStep;
        /** How far back the newest run that is certainly published sits. */
        private final int lag;
        /** The widest box worth asking this model for, degrees. */
        public final double maxSpanLon, maxSpanLat;
        public final double west, south, east, north;

        Model(String label, String script, int runStep, int lag, double maxSpanLon,
                double maxSpanLat, double west, double south, double east, double north) {
            this.label = label;
            this.script = script;
            this.runStep = runStep;
            this.lag = lag;
            this.maxSpanLon = maxSpanLon;
            this.maxSpanLat = maxSpanLat;
            this.west = west;
            this.south = south;
            this.east = east;
            this.north = north;
        }

        public boolean covers(double lat, double lon) {
            return lat >= south && lat <= north && lon >= west && lon <= east;
        }

        /** The newest run certainly published, as UTC millis on a run boundary. */
        public long latestRun(long nowUtc) {
            final Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            c.setTimeInMillis(nowUtc - lag * 3_600_000L);
            c.set(Calendar.HOUR_OF_DAY, (c.get(Calendar.HOUR_OF_DAY) / runStep) * runStep);
            c.set(Calendar.MINUTE, 0);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            return c.getTimeInMillis();
        }

        /** The previous run, for when a filter answers that it has no such file yet. */
        public long previousRun(long runUtc) {
            return runUtc - runStep * 3_600_000L;
        }

        private String dir(String day, String hh) {
            switch (this) {
                case HRRR:
                    return "%2Fhrrr." + day + "%2Fconus";
                case RAP:
                    return "%2Frap." + day;
                default:
                    return "%2Fgfs." + day + "%2F" + hh + "%2Fatmos";
            }
        }

        private String file(String hh, int forecastHour) {
            switch (this) {
                case HRRR:
                    return "hrrr.t" + hh + "z.wrfsfcf"
                            + String.format(Locale.US, "%02d", forecastHour) + ".grib2";
                case RAP:
                    return "rap.t" + hh + "z.awp130pgrbf"
                            + String.format(Locale.US, "%02d", forecastHour) + ".grib2";
                default:
                    return "gfs.t" + hh + "z.pgrb2.0p25.f"
                            + String.format(Locale.US, "%03d", forecastHour);
            }
        }
    }

    private NomadsWind() {
    }

    /**
     * The model for a view: the finest whose box covers it and whose center it holds.
     * Clamping the request to the model's own ground handles an edge; this only has to
     * decide which cells to ask for.
     */
    public static Model forView(double west, double south, double east, double north) {
        final double spanLon = east - west, spanLat = north - south;
        final double cLat = (north + south) / 2, cLon = (east + west) / 2;
        for (Model m : new Model[] { Model.HRRR, Model.RAP }) {
            if (spanLon <= m.maxSpanLon && spanLat <= m.maxSpanLat && m.covers(cLat, cLon))
                return m;
        }
        return Model.GFS;
    }

    /** The filter request for 10 m U and V over a box at a forecast hour of a run. */
    public static String url(Model model, long runUtc, int forecastHour, double west,
            double south, double east, double north) {
        final Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.setTimeInMillis(runUtc);
        final String day = String.format(Locale.US, "%04d%02d%02d", c.get(Calendar.YEAR),
                c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
        final String hh = String.format(Locale.US, "%02d", c.get(Calendar.HOUR_OF_DAY));
        return FILTER + model.script
                + "?dir=" + model.dir(day, hh)
                + "&file=" + model.file(hh, forecastHour)
                + "&var_UGRD=on&var_VGRD=on&lev_10_m_above_ground=on&subregion="
                + String.format(Locale.US, "&toplat=%.3f&leftlon=%.3f&rightlon=%.3f&bottomlat=%.3f",
                        north, west, east, south);
    }

    /**
     * A filter with no such file yet answers with an HTML page, not an error status,
     * so the magic decides whether a response is a forecast.
     */
    public static boolean looksLikeGrib(byte[] body) {
        return body != null && body.length > 16 && body[0] == 'G' && body[1] == 'R'
                && body[2] == 'I' && body[3] == 'B';
    }

    /**
     * Read a filter response into a regular lon/lat grid.
     *
     * @param targetNx cells across for a message that has to be resampled off a
     *                 projection; a lat/lon message is taken as it stands
     */
    public static WindGrid read(byte[] body, int targetNx) throws IOException {
        final List<Grib2.Message> msgs = Grib2.read(body);
        Grib2.Message u = null, v = null;
        for (Grib2.Message m : msgs) {
            if (m.discipline == 0 && m.category == 2 && m.number == 2)
                u = m;
            else if (m.discipline == 0 && m.category == 2 && m.number == 3)
                v = m;
        }
        if (u == null || v == null)
            throw new IOException("response holds " + msgs.size()
                    + " message(s) and not both UGRD and VGRD");
        if (u.nx != v.nx || u.ny != v.ny)
            throw new IOException("UGRD and VGRD grids differ");
        return u.latLon != null ? WindGrid.fromLatLon(u, v) : WindGrid.fromLambert(u, v, targetNx);
    }
}

package com.atakmap.android.atmosphere.wind;

import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The NOMADS grib filter as a wind source: HRRR 10 m wind for a lon/lat box, one
 * small GRIB2 per forecast hour. Pure request building and response reading; the
 * overlay does the fetching, so this is unit-tested against a saved response.
 *
 * <p>HRRR runs every hour and a run's files appear about an hour and three quarters
 * after its time, so the newest run worth asking for is two hours old; the caller
 * steps back a run when a request comes back as something other than GRIB. Hourly
 * forecasts run to 18 h, and to 48 h from the 00, 06, 12 and 18Z runs.
 */
public final class NomadsWind {

    public static final String HOST = "nomads.ncep.noaa.gov";
    private static final String FILTER = "https://" + HOST + "/cgi-bin/filter_hrrr_2d.pl";

    /** HRRR's cover, near enough: the CONUS grid's lon/lat envelope. */
    public static final double WEST = -134, EAST = -60.5, SOUTH = 21, NORTH = 52.5;

    private NomadsWind() {
    }

    /** The newest run likely to be complete, as UTC millis on the hour. */
    public static long latestRun(long nowUtc) {
        final Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.setTimeInMillis(nowUtc - 2 * 3_600_000L);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** How many forecast hours the run carries. */
    public static int hoursFor(long runUtc) {
        final Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.setTimeInMillis(runUtc);
        return c.get(Calendar.HOUR_OF_DAY) % 6 == 0 ? 48 : 18;
    }

    /** The filter request for 10 m U and V over a box at a forecast hour of a run. */
    public static String url(long runUtc, int forecastHour, double west, double south,
            double east, double north) {
        final Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.setTimeInMillis(runUtc);
        final String day = String.format(Locale.US, "%04d%02d%02d", c.get(Calendar.YEAR),
                c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
        final String hh = String.format(Locale.US, "%02d", c.get(Calendar.HOUR_OF_DAY));
        return FILTER + "?dir=%2Fhrrr." + day + "%2Fconus"
                + "&file=hrrr.t" + hh + "z.wrfsfcf" + String.format(Locale.US, "%02d", forecastHour)
                + ".grib2&var_UGRD=on&var_VGRD=on&lev_10_m_above_ground=on&subregion="
                + String.format(Locale.US, "&toplat=%.3f&leftlon=%.3f&rightlon=%.3f&bottomlat=%.3f",
                        north, west, east, south);
    }

    public static boolean looksLikeGrib(byte[] body) {
        return body != null && body.length > 16 && body[0] == 'G' && body[1] == 'R'
                && body[2] == 'I' && body[3] == 'B';
    }

    /** Read a filter response into a regular lon/lat grid. */
    public static WindGrid read(byte[] body, int targetNx) throws java.io.IOException {
        final List<Grib2.Message> msgs = Grib2.read(body);
        Grib2.Message u = null, v = null;
        for (Grib2.Message m : msgs) {
            if (m.discipline == 0 && m.category == 2 && m.number == 2)
                u = m;
            else if (m.discipline == 0 && m.category == 2 && m.number == 3)
                v = m;
        }
        if (u == null || v == null)
            throw new java.io.IOException("response holds " + msgs.size()
                    + " message(s) and not both UGRD and VGRD");
        if (u.nx != v.nx || u.ny != v.ny)
            throw new java.io.IOException("UGRD and VGRD grids differ");
        return WindGrid.fromLambert(u, v, targetNx);
    }
}

package com.atakmap.android.atmosphere.overlay;

import android.content.Context;

import com.atakmap.android.atmosphere.data.Sawti;
import com.atakmap.android.atmosphere.net.EgressPolicy;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * SAWTI, the Santa Ana Wildfire Threat Index, on the map: the four Southern California
 * zones filled in the day's level color, one day at a time over the four days the
 * website shows, the forecaster's words behind a tap. No Rating is an outline
 * only, so a quiet day (most of the year) does not paint the region gray.
 *
 * <p>Two small requests every half hour while on: the site's trouble flag, which
 * hides its forecast when set and so hides ours, and the forecast. The flag is
 * passed over if it does not answer; the forecast's own issue time still says
 * how old it is.
 */
public final class SawtiOverlay extends OutlookOverlay {

    public static final String LAYER_ID = "sawti";
    public static final String HOST = Sawti.HOST;
    private static final String TAG = "AtmosphereSawti";
    /** The feed posts daily; past this it has missed a day. */
    private static final long STALE_MS = 36L * 60 * 60 * 1000;

    /**
     * A No Rating zone's outline on the map: royal blue. Cyan read as a waterway,
     * purple is this scale's Extreme, pink is a Red Flag Warning, and yellow, orange
     * and red are its other levels (operator, 2026-10-05).
     */
    private static final int NO_RATING_EDGE = 0xFF2962FF;

    /** The levels, in order, with the site's own words; No Rating keyed by its blue outline. */
    public static final String[][] LEGEND;
    static {
        LEGEND = new String[Sawti.LEVELS.length][];
        for (int i = 0; i < Sawti.LEVELS.length; i++)
            LEGEND[i] = new String[] { Sawti.LEVELS[i] + (i == 0 ? " (blue outline)" : "") + ": "
                    + Sawti.MEANINGS[i], String.valueOf(i == 0 ? NO_RATING_EDGE : Sawti.COLORS[i]) };
    }

    private static final String[] URLS = { Sawti.TROUBLE_URL, Sawti.FORECAST_URL };

    private final JSONObject[] zones = new JSONObject[Sawti.ZONE_NAMES.length];
    private volatile Sawti.Forecast forecast;
    private volatile boolean troubled;

    public SawtiOverlay(MapView mapView, Context pluginContext, EgressPolicy egress) {
        super(mapView, pluginContext, egress, TAG, LAYER_ID, "SAWTI");
        readZones(pluginContext);
    }

    /** The newest issue held, or null before the first answer or while the site is in trouble. */
    public Sawti.Forecast forecast() {
        return forecast;
    }

    @Override
    protected String[] urls() {
        return URLS;
    }

    @Override
    protected boolean optional(int index) {
        return index == 0;
    }

    @Override
    public int days() {
        return Sawti.DAYS;
    }

    @Override
    public boolean allDays() {
        return false;
    }

    @Override
    public String dayLabel(int day) {
        final Sawti.Forecast f = forecast;
        if (f != null) {
            final List<String> dates = f.dates();
            if (day >= 1 && day <= dates.size())
                return Sawti.shortDate(dates.get(day - 1));
        }
        return super.dayLabel(day);
    }

    /**
     * Half strength, between the other outlooks' faint wash and the site's 70
     * percent: the level is the point of this layer, and the ground still shows.
     * No Rating keeps a fill too faint to see (1 of 255): an unfilled area answers
     * a tap on its edge only, and a tap anywhere in a zone is what opens its page
     * (operator, 2026-10-01: "when i click on the zone can it take me to the zone
     * report").
     */
    @Override
    protected int fillAlpha(Area a) {
        return Sawti.LEVELS[0].equals(a.label) ? 0x01 : 0x80;
    }

    /** Each zone's label is its level's tile, in the site's colors. */
    @Override
    protected int[] labelColors(Area a) {
        for (int i = 0; i < Sawti.LEVELS.length; i++)
            if (Sawti.LEVELS[i].equals(a.label))
                return new int[] { Sawti.textColor(i), Sawti.color(i) };
        return null;
    }

    /**
     * Drawn whenever a zone is big enough on screen to hold its label: 500 m per
     * pixel puts San Diego's zone about 220 px across (XCover, 2026-10-01).
     */
    @Override
    protected double labelMaxResolution() {
        return 500d;
    }

    /**
     * The site's own dark gray border on a rated zone, whose fill says what it is;
     * a strong color on a No Rating zone, which has no fill and otherwise could not
     * be seen (operator, 2026-10-01: "i need sawti zone when off like cyan so i can
     * see them"; blue since 2026-10-05, see NO_RATING_EDGE). The No Rating gray (#D9D9D9) as an edge had vanished on a light
     * base map, and the site's #5A5A5A was hard to pick out on the terrain.
     */
    @Override
    protected int strokeColor(Area a) {
        return Sawti.LEVELS[0].equals(a.label) ? NO_RATING_EDGE : 0xFF5A5A5A;
    }

    @Override
    protected List<Area> parse(int index, String body) {
        final List<Area> out = new ArrayList<>();
        if (index == 0) {
            troubled = Sawti.troubled(body);
            return out;
        }
        if (troubled) {
            forecast = null;
            return out;
        }
        final Sawti.Forecast f = Sawti.parse(body);
        forecast = f;
        final String issued = issued(f.issued);
        final List<String> dates = f.dates();
        for (int n = 1; n <= Math.min(Sawti.DAYS, dates.size()); n++) {
            final String date = dates.get(n - 1);
            for (int zone = 1; zone <= zones.length; zone++) {
                final Sawti.Day d = f.find(zone, date);
                final JSONObject shape = zones[zone - 1];
                if (d == null || shape == null)
                    continue;
                final StringBuilder t = new StringBuilder(Sawti.zoneTitle(zone));
                t.append('\n').append(Sawti.longDate(date)).append(": ").append(Sawti.level(d.value));
                t.append('\n').append(Sawti.meaning(d.value));
                if (!d.description.isEmpty())
                    t.append("\n\n").append(d.description);
                if (!d.actions.isEmpty())
                    t.append("\n\nRecommended: ").append(d.actions);
                if (!issued.isEmpty())
                    t.append("\n\nIssued: ").append(issued);
                t.append("\nFrom: USDA Forest Service Predictive Services, Santa Ana Wildfire Threat Index (SAWTI)");
                // Named as SAWTI on the map, so its zones are not taken for another
                // layer's areas beside them (operator, 2026-10-05).
                out.add(new Area(n, "SAWTI-" + Sawti.zoneName(zone) + ": "
                        + Sawti.level(d.value),
                        Sawti.level(d.value), Sawti.color(d.value), shape, t.toString(),
                        zone + "|" + date));
            }
        }
        return out;
    }

    @Override
    protected String summary(List<Area> areas) {
        if (troubled)
            return "The Forest Service's SAWTI site reports technical difficulties and is "
                    + "not showing its forecast, so neither is Atmosphere.";
        final Sawti.Forecast f = forecast;
        if (f == null || f.days.isEmpty())
            return "The SAWTI forecast came back empty.";
        final StringBuilder b = new StringBuilder();
        final List<String> dates = f.dates();
        for (int n = 1; n <= Math.min(Sawti.DAYS, dates.size()); n++) {
            final String date = dates.get(n - 1);
            b.append(b.length() > 0 ? "  " : "").append(Sawti.shortDate(date)).append(": ");
            final List<String> rated = new ArrayList<>();
            for (int zone = 1; zone <= zones.length; zone++) {
                final Sawti.Day d = f.find(zone, date);
                if (d != null && d.value > 0)
                    rated.add(Sawti.zoneName(zone) + " " + Sawti.level(d.value));
            }
            if (rated.isEmpty())
                b.append("No Rating");
            else
                for (int i = 0; i < rated.size(); i++)
                    b.append(i > 0 ? ", " : "").append(rated.get(i));
            b.append('.');
        }
        final String issued = issued(f.issued);
        if (!issued.isEmpty())
            b.append("\nIssued ").append(issued).append('.');
        if (f.issued > 0 && System.currentTimeMillis() - f.issued > STALE_MS)
            b.append(" Over a day old: the Forest Service has not posted a newer one.");
        return b.toString();
    }

    @Override
    protected String noun() {
        return "SAWTI forecast";
    }

    /** "Wed Sep 30, 2:01 AM" in the phone's time, or empty. */
    private static String issued(long millis) {
        if (millis <= 0)
            return "";
        return new SimpleDateFormat("EEE MMM d, h:mm a", Locale.US).format(new Date(millis));
    }

    private void readZones(Context pluginContext) {
        try (InputStream in = pluginContext.getAssets().open(Sawti.ZONES_ASSET)) {
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            final byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0)
                bytes.write(buf, 0, n);
            final JSONArray fs = new JSONObject(new String(bytes.toByteArray(),
                    StandardCharsets.UTF_8)).getJSONArray("features");
            for (int i = 0; i < fs.length(); i++) {
                final JSONObject f = fs.getJSONObject(i);
                final int zone = f.getJSONObject("properties").getInt("zone");
                if (zone >= 1 && zone <= zones.length)
                    zones[zone - 1] = f.getJSONObject("geometry");
            }
        } catch (Exception e) {
            Log.w(TAG, "zone outlines would not load", e);
        }
    }
}

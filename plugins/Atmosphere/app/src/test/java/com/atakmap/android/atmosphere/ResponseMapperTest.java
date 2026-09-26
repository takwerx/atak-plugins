package com.atakmap.android.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.atmosphere.data.ResponseMapper;
import com.atakmap.android.atmosphere.model.Reading;
import com.atakmap.android.atmosphere.data.IsoTime;
import com.atakmap.android.atmosphere.model.SeriesEntry;
import com.atakmap.android.atmosphere.model.Snapshot;
import com.atakmap.android.atmosphere.source.WxSourceDef;
import com.atakmap.android.atmosphere.source.WxSourceParser;
import com.atakmap.android.atmosphere.units.UnitSystem;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.util.Collections;
import java.util.Arrays;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Both response layouts, against responses shaped like the real ones.
 *
 * <p>This is the test that says the source-definition idea works: two providers with
 * completely different response structures, different unit conventions, and one of them
 * reporting wind as the string "10 mph" and direction as "NW" — mapped by configuration,
 * with no provider-specific Java anywhere.
 */
public class ResponseMapperTest {

    private static final double EPS = 1e-6;

    /**
     * The NWS hourly-periods shape the bundled definition had until 2026-09-26, kept
     * here because the records layout and the string parses it exercises still exist.
     */
    private static final String NWS_HOURLY = "{\"schemaVersion\": 1, \"sourceId\": \"nws-hourly\", \"displayName\": \"NWS hourly\","
            + " \"resolveUrl\": \"https://api.weather.gov/points/{lat},{lon}\","
            + " \"resolvePath\": \"properties.forecastHourly\","
            + " \"headers\": {\"Accept\": \"application/geo+json\"},"
            + " \"layout\": \"records\", \"recordsPath\": \"properties.periods\","
            + " \"timePath\": \"startTime\", \"parameters\": ["
            + "  {\"key\": \"temperature\", \"label\": \"Temperature\", \"quantity\": \"temperature\","
            + "   \"unitPath\": \"temperatureUnit\", \"seriesPath\": \"temperature\", \"defaultOn\": true},"
            + "  {\"key\": \"relativeHumidity\", \"label\": \"Relative humidity\", \"quantity\": \"percent\","
            + "   \"seriesPath\": \"relativeHumidity.value\", \"defaultOn\": true},"
            + "  {\"key\": \"windSpeed\", \"label\": \"Wind\", \"quantity\": \"speed\", \"unit\": \"mph\","
            + "   \"parse\": \"leadingNumber\", \"seriesPath\": \"windSpeed\", \"defaultOn\": true},"
            + "  {\"key\": \"windDirection\", \"label\": \"Wind direction\", \"quantity\": \"angle\","
            + "   \"parse\": \"compass\", \"seriesPath\": \"windDirection\", \"defaultOn\": true},"
            + "  {\"key\": \"probabilityOfPrecipitation\", \"label\": \"Chance of precipitation\","
            + "   \"quantity\": \"percent\", \"seriesPath\": \"probabilityOfPrecipitation.value\", \"defaultOn\": true},"
            + "  {\"key\": \"skyCover\", \"label\": \"Sky cover\", \"quantity\": \"percent\", \"seriesPath\": \"icon\","
            + "   \"parse\": \"lookup\", \"defaultOn\": true, \"lookup\": [[\"skc\", 0], [\"few\", 15], [\"sct\", 37],"
            + "   [\"bkn\", 69], [\"ovc\", 94], [\"blizzard\", 100], [\"snow\", 88], [\"sleet\", 88],"
            + "   [\"fzra\", 88], [\"tsra\", 69], [\"rain\", 69]]}"
            + "]}";

    private static WxSourceDef inline(String json, String name) {
        final WxSourceParser.Result r = WxSourceParser.parse(json,
                WxSourceDef.Origin.BUNDLED, name);
        assertTrue(r.errors.toString(), r.ok());
        return r.def;
    }

    private static WxSourceDef def(String file) throws Exception {
        final String json = new String(Files.readAllBytes(
                new File("src/main/assets/wx_sources/" + file).toPath()),
                StandardCharsets.UTF_8);
        final WxSourceParser.Result r = WxSourceParser.parse(json,
                WxSourceDef.Origin.BUNDLED, file);
        assertTrue(r.errors.toString(), r.ok());
        return r.def;
    }

    /** Open-Meteo: parallel arrays, metric units, a "current" block. */
    @Test
    public void columnsLayout() throws Exception {
        final String body = "{"
                + "\"current\": {\"time\": \"2025-08-22T14:00\","
                + "  \"temperature_2m\": 21.5, \"relative_humidity_2m\": 40,"
                + "  \"wind_speed_10m\": 5.0, \"wind_gusts_10m\": 9.0,"
                + "  \"wind_direction_10m\": 315, \"precipitation\": 0.0},"
                + "\"hourly\": {\"time\": [\"2025-08-22T14:00\", \"2025-08-22T15:00\"],"
                + "  \"temperature_2m\": [21.5, 22.0],"
                + "  \"wind_speed_10m\": [5.0, 6.0],"
                + "  \"precipitation_probability\": [10, 25]}"
                + "}";

        final Snapshot s = ResponseMapper.map(def("open-meteo.json"), body,
                39.5, -120.25, 1_000_000L);

        final Reading temp = current(s, "temperature_2m");
        assertNotNull(temp);
        assertEquals(21.5, temp.value, EPS);
        assertEquals("22 °C", temp.format(UnitSystem.METRIC));
        assertEquals("71 °F", temp.format(UnitSystem.IMPERIAL));

        // m/s in, canonical m/s stored, km/h shown.
        assertEquals("18 km/h", current(s, "wind_speed_10m").format(UnitSystem.METRIC));
        assertEquals("10 kt", current(s, "wind_speed_10m").format(UnitSystem.AVIATION));
        assertEquals("315° NW", current(s, "wind_direction_10m").format(UnitSystem.METRIC));

        assertEquals(2, s.series.size());
        assertEquals(1755871200000L, s.series.get(0).timeMillis);
        assertEquals(22.0, s.series.get(1).reading("temperature_2m").value, EPS);
        assertEquals(25.0, s.series.get(1).reading("precipitation_probability").value, EPS);
    }

    /** NWS: an array of period objects, US units, values embedded in strings. */
    @Test
    public void recordsLayout() throws Exception {
        final String body = "{\"properties\": {\"periods\": ["
                + "{\"startTime\": \"2025-08-22T08:00:00-06:00\","
                + " \"temperature\": 68, \"temperatureUnit\": \"F\","
                + " \"windSpeed\": \"10 mph\", \"windDirection\": \"NW\","
                + " \"relativeHumidity\": {\"value\": 42},"
                + " \"probabilityOfPrecipitation\": {\"value\": null}},"
                + "{\"startTime\": \"2025-08-22T09:00:00-06:00\","
                + " \"temperature\": 72, \"temperatureUnit\": \"F\","
                + " \"windSpeed\": \"10 to 15 mph\", \"windDirection\": \"SSW\","
                + " \"relativeHumidity\": {\"value\": 38},"
                + " \"probabilityOfPrecipitation\": {\"value\": 20}}"
                + "]}}";

        final Snapshot s = ResponseMapper.map(inline(NWS_HOURLY, "nws-hourly.json"), body,
                39.5, -120.25, 1L);

        assertTrue("NWS has no current block", s.current.isEmpty());
        assertEquals(2, s.series.size());

        final SeriesEntry first = s.series.get(0);
        assertEquals(20.0, first.reading("temperature").value, 0.01);   // 68F
        assertEquals("68 °F", first.reading("temperature").format(UnitSystem.IMPERIAL));
        assertEquals(4.4704, first.reading("windSpeed").value, EPS);    // "10 mph"
        assertEquals(315.0, first.reading("windDirection").value, EPS); // "NW"
        assertEquals(42.0, first.reading("relativeHumidity").value, EPS);

        // A JSON null must read as "no value", not as zero — 0% chance of rain and no
        // forecast at all are different answers.
        assertFalse(first.reading("probabilityOfPrecipitation").valid());
        assertEquals("—", first.reading("probabilityOfPrecipitation").format(UnitSystem.METRIC));

        // A range takes the low end: the conservative read for wind.
        assertEquals(4.4704, s.series.get(1).reading("windSpeed").value, EPS);
        assertEquals(202.5, s.series.get(1).reading("windDirection").value, EPS);
        // 08:00-06:00 is 14:00Z.
        assertEquals(1755871200000L, first.timeMillis);
    }

    /** A provider that drops a field must degrade to "no reading", never to a crash. */
    @Test
    public void missingFieldsAreNotFatal() throws Exception {
        final Snapshot s = ResponseMapper.map(def("open-meteo.json"),
                "{\"current\": {}, \"hourly\": {}}", 0.0, 0.0, 1L);
        assertTrue(s.series.isEmpty());
        assertFalse(current(s, "temperature_2m").valid());
    }

    private static Reading current(Snapshot s, String key) {
        for (Reading r : s.current) {
            if (r.key.equals(key))
                return r;
        }
        return null;
    }

    // ---- the place a resolve response names --------------------------------------

    private static final java.util.List<String> NWS_PLACE = Arrays.asList(
            "properties.relativeLocation.properties.city",
            "properties.relativeLocation.properties.state");

    @Test
    public void placeJoinsCityAndState() throws Exception {
        final JSONObject points = new JSONObject("{\"properties\":{\"relativeLocation\":"
                + "{\"properties\":{\"city\":\"Corona\",\"state\":\"CA\"}}}}");
        assertEquals("Corona, CA", ResponseMapper.place(points, NWS_PLACE));
    }

    @Test
    public void placeSkipsWhatIsMissing() throws Exception {
        final JSONObject cityOnly = new JSONObject("{\"properties\":{\"relativeLocation\":"
                + "{\"properties\":{\"city\":\"Corona\"}}}}");
        assertEquals("Corona", ResponseMapper.place(cityOnly, NWS_PLACE));
        assertEquals(null, ResponseMapper.place(new JSONObject("{}"), NWS_PLACE));
        assertEquals(null, ResponseMapper.place(cityOnly, Collections.<String>emptyList()));
    }

    @Test
    public void bundledNwsNamesThePlacePaths() throws Exception {
        final WxSourceDef nws = def("nws.json");
        assertEquals(NWS_PLACE, nws.placePaths);
    }

    /** NWS forecast grid: elements of timed spans, joined by the hour from the fetch on. */
    @Test
    public void gridLayout() throws Exception {
        final String body = "{\"properties\": {"
                + "\"temperature\": {\"uom\": \"wmoUnit:degC\", \"values\": ["
                + "  {\"validTime\": \"2026-09-26T12:00:00+00:00/PT2H\", \"value\": 20},"
                + "  {\"validTime\": \"2026-09-26T14:00:00+00:00/PT1H\", \"value\": 22}]},"
                + "\"twentyFootWindSpeed\": {\"uom\": \"wmoUnit:km_h-1\", \"values\": ["
                + "  {\"validTime\": \"2026-09-26T13:00:00+00:00/PT1H\", \"value\": 36}]},"
                + "\"twentyFootWindDirection\": {\"uom\": \"wmoUnit:degree_(angle)\", \"values\": ["
                + "  {\"validTime\": \"2026-09-26T12:00:00+00:00/PT3H\", \"value\": 240}]},"
                + "\"skyCover\": {\"uom\": \"wmoUnit:percent\", \"values\": ["
                + "  {\"validTime\": \"2026-09-26T12:00:00+00:00/P1D\", \"value\": 37}]},"
                + "\"mixingHeight\": {\"uom\": \"wmoUnit:m\", \"values\": ["
                + "  {\"validTime\": \"2026-09-26T13:00:00+00:00/PT1H\", \"value\": 1658.7216}]},"
                + "\"wetBulbGlobeTemperature\": {\"uom\": \"wmoUnit:degC\", \"values\": []}"
                + "}}";
        // Fetched at 13:20Z: the 12Z hour is gone, 13Z is now.
        final long fetchedAt = IsoTime.parse("2026-09-26T13:20:00Z");
        final Snapshot snap = ResponseMapper.map(def("nws.json"), body, 33.5, -117.2, fetchedAt);

        assertEquals(24 - 1, snap.series.size());   // sky cover spans the day from 12Z
        final SeriesEntry now = snap.series.get(0);
        assertEquals(IsoTime.parse("2026-09-26T13:00:00Z"), now.timeMillis);
        assertEquals("2026-09-26T13:00:00Z", now.timeRaw);
        assertEquals(20.0, now.reading("temperature").value, EPS);        // 12Z span, 2 h
        assertEquals(10.0, now.reading("twentyFootWindSpeed").value, EPS); // 36 km/h -> m/s
        assertEquals(240.0, now.reading("twentyFootWindDirection").value, EPS);
        assertEquals(37.0, now.reading("skyCover").value, EPS);
        assertEquals(1658.7216, now.reading("mixingHeight").value, EPS);
        // Defined for the office, never filled: a reading, NaN, on every hour.
        assertTrue(Double.isNaN(now.reading("wetBulbGlobeTemperature").value));

        final SeriesEntry next = snap.series.get(1);
        assertEquals(22.0, next.reading("temperature").value, EPS);
        // No 20-ft wind at 14Z: a reading, NaN, not a missing key.
        assertTrue(Double.isNaN(next.reading("twentyFootWindSpeed").value));
        assertEquals(240.0, next.reading("twentyFootWindDirection").value, EPS);
    }
}

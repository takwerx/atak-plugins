package com.atakmap.android.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.atmosphere.data.ResponseMapper;
import com.atakmap.android.atmosphere.model.Reading;
import com.atakmap.android.atmosphere.model.SeriesEntry;
import com.atakmap.android.atmosphere.model.Snapshot;
import com.atakmap.android.atmosphere.source.WxSourceDef;
import com.atakmap.android.atmosphere.source.WxSourceParser;
import com.atakmap.android.atmosphere.units.UnitSystem;

import org.junit.Test;

import java.io.File;
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

        final Snapshot s = ResponseMapper.map(def("nws.json"), body, 39.5, -120.25, 1L);

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
}

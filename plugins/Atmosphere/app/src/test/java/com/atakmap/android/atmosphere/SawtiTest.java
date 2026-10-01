package com.atakmap.android.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.atmosphere.data.IsoTime;
import com.atakmap.android.atmosphere.data.Sawti;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class SawtiTest {

    private static String read(InputStream in) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0)
            out.write(buf, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = SawtiTest.class.getClassLoader().getResourceAsStream(name)) {
            return read(in);
        }
    }

    @Test
    public void aQuietDayReadsAsFourZonesBySixDays() throws IOException {
        final Sawti.Forecast f = Sawti.parse(resource("sawti_forecast_quiet.json"));
        assertEquals(24, f.days.size());
        assertEquals(IsoTime.parse("2026-09-30T09:01:32Z"), f.issued);
        assertEquals(6, f.dates().size());
        assertEquals("2026-09-30", f.dates().get(0));
        assertEquals(1, f.dayNumber("2026-09-30"));
        assertEquals(0, f.dayNumber("2026-09-29"));
        final Sawti.Day d = f.find(3, "2026-09-30");
        assertNotNull(d);
        assertEquals(0, d.value);
        assertEquals("Santa Ana winds are either not expected, or will not contribute to significant fire activity.",
                d.description);
        assertNull(f.find(5, "2026-09-30"));
    }

    @Test
    public void anEventDayKeepsTheForecastersWordsWithoutTheHtml() throws IOException {
        // 2025-01-10 15:40Z, a forecaster's re-issue during the Palisades and Eaton fires.
        final Sawti.Forecast f = Sawti.parse(resource("sawti_forecast_event.json"));
        final Sawti.Day d = f.find(1, "2025-01-10");
        assertNotNull(d);
        assertEquals(2, d.value);
        assertEquals("Moderate", Sawti.level(d.value));
        assertTrue(d.description.startsWith("Moderate Santa Ana winds will diminish this afternoon."));
        assertFalse(d.description.contains("<"));
        assertFalse(d.actions.contains("  "));
        assertEquals("Wed 9/30", Sawti.shortDate("2026-09-30"));
        assertEquals("Fri Jan 10", Sawti.longDate("2025-01-10"));
    }

    @Test
    public void textDropsTagsKeepsParagraphsAndDecodes() {
        assertEquals("One.\nTwo & three.", Sawti.text("<p>One.</p><p>Two &amp; three.</p>"));
        assertEquals("Santa Ana winds are either not expected, or will not contribute.",
                Sawti.text("Santa Ana winds are either not expected, <b><i>or</i></b> will not contribute."));
        assertEquals("A. B.", Sawti.text("A.&nbsp; B."));
        assertEquals("", Sawti.text(null));
    }

    @Test
    public void levelsColorsAndOddValues() {
        assertEquals("No Rating", Sawti.level(0));
        assertEquals("Extreme", Sawti.level(4));
        assertEquals("Extreme", Sawti.level(9));
        assertEquals("No Rating", Sawti.level(-1));
        assertEquals(0xFFC00000, Sawti.color(3));
        assertEquals("Zone 2: Orange-Inland Empire", Sawti.zoneTitle(2));
        assertEquals(0, Sawti.parse("not json").days.size());
        assertEquals(0, Sawti.parse("").days.size());
        assertFalse(Sawti.troubled("{\"status\":false}"));
        assertTrue(Sawti.troubled("{\"status\":true}"));
        assertFalse(Sawti.troubled("<html>"));
    }

    @Test
    public void theShippedZonesAreTheFourInOrder() throws Exception {
        final String body = read(new FileInputStream(new File("src/main/assets/" + Sawti.ZONES_ASSET)));
        final JSONArray fs = new JSONObject(body).getJSONArray("features");
        assertEquals(4, fs.length());
        for (int i = 0; i < 4; i++) {
            final JSONObject p = fs.getJSONObject(i).getJSONObject("properties");
            assertEquals(i + 1, p.getInt("zone"));
            assertEquals(Sawti.zoneName(i + 1), p.getString("name"));
            assertEquals("Polygon", fs.getJSONObject(i).getJSONObject("geometry").getString("type"));
        }
    }
}

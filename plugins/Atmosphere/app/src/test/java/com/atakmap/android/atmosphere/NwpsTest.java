package com.atakmap.android.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.atmosphere.data.IsoTime;
import com.atakmap.android.atmosphere.data.Nwps;

import org.junit.Test;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The gauge list as the service writes it, and the box the request asks for. */
public class NwpsTest {

    private static final String BODY = "{\"gauges\":["
            + "{\"lid\":\"alwc1\",\"name\":\"Alhambra Wash at Klingerman  \","
            + " \"rfc\":{\"abbreviation\":\"CNRFC\"},\"wfo\":{\"abbreviation\":\"LOX\"},"
            + " \"state\":{\"abbreviation\":\"CA\"},\"latitude\":34.055556,\"longitude\":-118.086111,"
            + " \"pedts\":{\"observed\":\"HGIRG\",\"forecast\":\"\"},"
            + " \"status\":{\"observed\":{\"primary\":0.28,\"primaryUnit\":\"ft\",\"secondary\":-999,"
            + "   \"secondaryUnit\":\"kcfs\",\"floodCategory\":\"no_flooding\",\"validTime\":\"2026-09-26T18:00:00Z\"},"
            + "  \"forecast\":{\"primary\":-999,\"primaryUnit\":\"\",\"secondary\":-999,\"secondaryUnit\":\"\","
            + "   \"floodCategory\":\"fcst_not_current\",\"validTime\":\"0001-01-01T00:00:00Z\"}}},"
            + "{\"lid\":\"FLOWQ1\",\"name\":\"A flow gauge\",\"latitude\":35.0,\"longitude\":-119.0,"
            + " \"pedts\":{\"observed\":\"QRIRG\"},"
            + " \"status\":{\"observed\":{\"primary\":12.5,\"primaryUnit\":\"kcfs\",\"secondary\":4.1,"
            + "   \"secondaryUnit\":\"ft\",\"floodCategory\":\"action\",\"validTime\":\"2026-09-26T17:45:00Z\"},"
            + "  \"forecast\":{\"primary\":15.0,\"primaryUnit\":\"kcfs\",\"secondary\":5.2,\"secondaryUnit\":\"ft\","
            + "   \"floodCategory\":\"minor\",\"validTime\":\"2026-09-27T06:00:00Z\"}}},"
            + "{\"lid\":\"NOPOS1\",\"name\":\"No position\",\"status\":{}}"
            + "]}";

    @Test
    public void parsesGaugesAndTellsStageFromFlowByUnit() {
        final List<Nwps.Gauge> g = Nwps.parse(BODY);
        assertEquals(2, g.size());
        final Nwps.Gauge a = g.get(0);
        assertEquals("ALWC1", a.lid);                       // upper case, as the API wants it
        assertEquals("Alhambra Wash at Klingerman", a.name); // trailing spaces gone
        assertEquals("LOX", a.wfo);
        assertEquals("CNRFC", a.rfc);
        assertEquals("CA", a.state);
        assertEquals(0.28, a.observed.stage, 1e-9);
        assertTrue(Double.isNaN(a.observed.flow));           // -999 is not a number to show
        assertEquals("no_flooding", a.observed.category);
        assertEquals(IsoTime.parse("2026-09-26T18:00:00Z"), a.observed.at);
        assertFalse(a.forecast.any());
        assertEquals(0L, a.forecast.at);                     // year 1 is "none"

        final Nwps.Gauge f = g.get(1);
        assertEquals(12.5, f.observed.flow, 1e-9);
        assertEquals(4.1, f.observed.stage, 1e-9);
        assertEquals("action", f.observed.category);
        assertEquals("minor", f.forecast.category);
        assertEquals(5.2, f.forecast.stage, 1e-9);
    }

    @Test
    public void theBoxIsTheRadiusEachWayInDegrees() {
        final String u = Nwps.boxUrl(33.576, -117.241, 100);
        assertTrue(u, u.startsWith("https://" + Nwps.HOST + "/nwps/v1/gauges?srid=EPSG_4326"));
        final Matcher m = Pattern.compile("bbox\\.xmin=([-0-9.]+)&bbox\\.ymin=([-0-9.]+)"
                + "&bbox\\.xmax=([-0-9.]+)&bbox\\.ymax=([-0-9.]+)").matcher(u);
        assertTrue(u, m.find());
        assertEquals(-118.980, Double.parseDouble(m.group(1)), 1e-3);
        assertEquals(32.127, Double.parseDouble(m.group(2)), 1e-3);
        assertEquals(-115.502, Double.parseDouble(m.group(3)), 1e-3);
        assertEquals(35.025, Double.parseDouble(m.group(4)), 1e-3);
    }

    @Test
    public void categoriesReadAsWaterOrAsGauge() {
        assertEquals("Major flooding", Nwps.label("major"));
        assertEquals("No flood stages defined", Nwps.label("not_defined"));
        assertEquals("Observation not current", Nwps.label("obs_not_current"));
        assertEquals("some new thing", Nwps.label("some_new_thing"));
        assertTrue(Nwps.severity("major") > Nwps.severity("moderate"));
        assertTrue(Nwps.severity("action") > Nwps.severity("no_flooding"));
        assertTrue(Nwps.isWaterState("no_flooding"));
        assertFalse(Nwps.isWaterState("obs_not_current"));
        assertFalse(Nwps.isWaterState("not_defined"));
    }

    @Test
    public void parsesTheHydrographAndTheStages() {
        final String sf = "{\"observed\":{\"primaryUnits\":\"ft\",\"secondaryUnits\":\"kcfs\","
                + "\"data\":[{\"validTime\":\"2026-09-25T00:00:00Z\",\"primary\":2.5,\"secondary\":-999},"
                + "{\"validTime\":\"2026-09-25T01:00:00Z\",\"primary\":-999,\"secondary\":-999},"
                + "{\"validTime\":\"2026-09-25T02:00:00Z\",\"primary\":2.6,\"secondary\":0.1}]},"
                + "\"forecast\":{\"primaryUnits\":\"ft\",\"secondaryUnits\":\"kcfs\","
                + "\"issuedTime\":\"2026-09-26T15:11:00Z\","
                + "\"data\":[{\"validTime\":\"2026-09-26T16:00:00Z\",\"primary\":2.9,\"secondary\":0}]}}";
        final Nwps.Hydrograph h = Nwps.parseStageflow(sf);
        assertEquals(2, h.observed.size());               // the all-none point is skipped
        assertEquals(2.6, h.observed.get(1).stage, 1e-9);
        assertEquals(0.1, h.observed.get(1).flow, 1e-9);
        assertEquals(1, h.forecast.size());
        assertEquals(IsoTime.parse("2026-09-26T15:11:00Z"), h.forecastIssued);

        final Nwps.Stages s = Nwps.parseStages("{\"flood\":{\"stageUnits\":\"ft\",\"categories\":{"
                + "\"major\":{\"stage\":28,\"flow\":23400},\"moderate\":{\"stage\":26,\"flow\":18000},"
                + "\"minor\":{\"stage\":25,\"flow\":15600},\"action\":{\"stage\":-9999,\"flow\":-9999}}}}");
        assertEquals(25.0, s.minor, 1e-9);
        assertEquals(28.0, s.major, 1e-9);
        assertTrue(Double.isNaN(s.action));
        assertTrue(s.any());
        assertFalse(Nwps.parseStages("{}").any());
    }

    @Test
    public void nothingUsableIsAnEmptyList() {
        assertTrue(Nwps.parse("").isEmpty());
        assertTrue(Nwps.parse("not json").isEmpty());
        assertTrue(Nwps.parse("{\"gauges\":[]}").isEmpty());
    }
}

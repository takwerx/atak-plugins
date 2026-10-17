package com.atakmap.android.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.atmosphere.data.TornadoPaths;

import org.junit.Test;

import java.util.List;

public class TornadoPathsTest {

    private static final String BODY = "{\"type\":\"FeatureCollection\",\"features\":["
            + "{\"type\":\"Feature\",\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[-91.28,41.65],[-91.27,41.66]]},"
            + "\"properties\":{\"wfo\":\"DVN\",\"event_id\":\"Pawnee_County_E\",\"stormdate\":1775166301000,\"starttime\":1775166301000,"
            + "\"endtime\":1775166421000,\"length\":0.0832,\"width\":0.0,\"injuries\":0,\"fatalities\":0,\"efscale\":\"EFU\",\"efnum\":-1,"
            + "\"maxwind\":-99,\"comments\":\"Brief rope touchdown.\"}},"
            + "{\"type\":\"Feature\",\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[-97.5,35.5],[-97.4,35.6]]},"
            + "\"properties\":{\"wfo\":\"OUN\",\"event_id\":\"Moore\",\"stormdate\":1775000000000,\"length\":17.0,\"width\":1760.0,"
            + "\"injuries\":12,\"fatalities\":1,\"efscale\":\"EF3\",\"efnum\":3,\"maxwind\":150}}]}";

    @Test
    public void parsesTracks() {
        final List<TornadoPaths.Track> t = TornadoPaths.parse(BODY);
        assertEquals(2, t.size());
        assertEquals("Pawnee County E", t.get(0).event);
        assertEquals("EFU", t.get(0).rating);
        assertEquals("EFU", TornadoPaths.rating(t.get(0).rating));
        assertEquals(-1, t.get(0).efNum);
        assertEquals("Moore", t.get(1).event);
        assertEquals(3, t.get(1).efNum);
        assertEquals(150, t.get(1).maxWind);
        assertEquals(17.0, t.get(1).lengthMi, 1e-9);
        assertEquals(TornadoPaths.COLORS[3], TornadoPaths.color("EF3"));
        assertEquals(TornadoPaths.COLORS[3], TornadoPaths.color("EF3+"));
        assertEquals(TornadoPaths.COLORS[6], TornadoPaths.color("EFU"));
        assertEquals("Wind", TornadoPaths.rating("TSTM/Wind"));
        assertEquals("Unrated", TornadoPaths.rating("N/A"));
        assertTrue(TornadoPaths.parse("").isEmpty());
    }

    @Test
    public void urlCarriesTheDateInSqlForm() {
        assertEquals("2025-10-11", TornadoPaths.ymd(1760140800000L));
        final String u = TornadoPaths.url("2025-10-11", 0);
        assertTrue(u.startsWith("https://" + TornadoPaths.HOST + "/"));
        assertTrue(u.endsWith("stormdate%20%3E%3D%20DATE%20%272025-10-11%27"));
        assertTrue(TornadoPaths.url("2025-10-11", 2000).endsWith("&resultOffset=2000"));
    }
}

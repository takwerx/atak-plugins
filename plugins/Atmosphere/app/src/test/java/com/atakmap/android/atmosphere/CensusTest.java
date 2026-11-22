
package com.atakmap.android.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.atmosphere.data.Census;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.List;

public class CensusTest {

    private static String resource(String name) throws IOException {
        try (InputStream in = CensusTest.class.getClassLoader().getResourceAsStream(name)) {
            assertTrue("missing test resource " + name, in != null);
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0)
                out.write(buf, 0, n);
            return new String(out.toByteArray(), Charset.forName("UTF-8"));
        }
    }

    @Test
    public void theAddressIsEscapedIntoTheQuery() {
        final String u = Census.lookupUrl("24601 Jefferson Ave, Murrieta, CA");
        assertTrue(u, u.startsWith("https://" + Census.HOST + "/"));
        assertTrue(u, u.contains("benchmark=Public_AR_Current"));
        assertTrue(u, u.contains("format=json"));
        // Spaces and commas must not end the query string early.
        assertTrue(u, u.contains("address=24601+Jefferson+Ave%2C+Murrieta%2C+CA"));
        assertFalse("a raw comma or space would truncate the request",
                u.contains("address=24601 "));
    }

    @Test
    public void anAccentedOrHashedAddressStillMakesOneQuery() {
        final String u = Census.lookupUrl("123 Cañada Rd #4, Murrieta, CA");
        // UTF-8, percent encoded; a bare # would drop everything after it.
        assertTrue(u, u.contains("Ca%C3%B1ada"));
        assertTrue(u, u.contains("%234"));
        assertEquals("exactly one query string", 1, count(u, '?'));
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++)
            if (s.charAt(i) == c)
                n++;
        return n;
    }

    @Test
    public void theLiveAnswerParsesIntoAMatch() throws IOException {
        // The service's own answer for 1600 Pennsylvania Ave, 2026-09-25.
        final List<Census.Match> all = Census.parse(resource("census_pennsylvania_ave.json"));
        assertEquals(1, all.size());
        final Census.Match m = all.get(0);
        assertEquals("1600 PENNSYLVANIA AVE NW, WASHINGTON, DC, 20500", m.address);
        // x is longitude, y is latitude. Swapped, this lands in the Indian Ocean.
        assertEquals(38.8987, m.latitude, 0.001);
        assertEquals(-77.0352, m.longitude, 0.001);
    }

    @Test
    public void anAddressTheCensusDoesNotHoldIsNoMatchesRatherThanATown() {
        // The whole reason this service is asked first: it declines. Android's
        // geocoder answers with the city and calls it a confident match.
        assertEquals(0, Census.parse("{\"result\":{\"addressMatches\":[]}}").size());
    }

    @Test
    public void anAnswerThatCannotBeReadIsNoMatchesRatherThanACrash() {
        assertEquals(0, Census.parse(null).size());
        assertEquals(0, Census.parse("").size());
        assertEquals(0, Census.parse("not json").size());
        assertEquals(0, Census.parse("{}").size());
        assertEquals(0, Census.parse("{\"result\":{}}").size());
        // A match with no coordinates is not a place.
        assertEquals(0, Census.parse("{\"result\":{\"addressMatches\":"
                + "[{\"matchedAddress\":\"SOMEWHERE\"}]}}").size());
        assertEquals(0, Census.parse("{\"result\":{\"addressMatches\":"
                + "[{\"coordinates\":{\"x\":0,\"y\":0}}]}}").size());
    }
}

package com.atakmap.android.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.atmosphere.data.ResponseMapper;
import com.atakmap.android.atmosphere.source.WxParam;
import com.atakmap.android.atmosphere.source.WxSourceDef;
import com.atakmap.android.atmosphere.source.WxSourceParser;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * NWS's hourly periods carry sky cover only as an icon code inside a URL, so the
 * bundled definition reads it through {@code parse: "lookup"}. This pins the table and
 * the coercion: the first substring found wins, a code the table does not know gives
 * no value rather than a guess.
 */
public class SkyCoverLookupTest {

    private static WxParam skyCover() throws IOException {
        final String json = new String(Files.readAllBytes(
                Paths.get("src/main/assets/wx_sources/nws.json")), StandardCharsets.UTF_8);
        final WxSourceParser.Result r = WxSourceParser.parse(json, WxSourceDef.Origin.BUNDLED,
                "nws.json");
        assertTrue("nws.json must parse: " + r.errors, r.errors.isEmpty());
        for (WxParam p : r.def.params) {
            if (p.key.equals("skyCover"))
                return p;
        }
        throw new AssertionError("nws.json has no skyCover parameter");
    }

    @Test
    public void nwsSkyCoverIsALookupOnTheIconUrl() throws IOException {
        final WxParam p = skyCover();
        assertEquals("lookup", p.parse);
        assertEquals("icon", p.seriesPath);
        assertNotNull(p.lookupKeys);
        assertEquals(p.lookupKeys.length, p.lookupValues.length);
        assertEquals("skc", p.lookupKeys[0]);
    }

    @Test
    public void iconCodesMapToTheOktaMidpoints() throws IOException {
        final WxParam p = skyCover();
        assertEquals(0, ResponseMapper.coerce("https://api.weather.gov/icons/land/day/skc?size=small", p), 0);
        assertEquals(15, ResponseMapper.coerce("https://api.weather.gov/icons/land/night/few?size=small", p), 0);
        assertEquals(37, ResponseMapper.coerce("https://api.weather.gov/icons/land/day/sct?size=small", p), 0);
        assertEquals(69, ResponseMapper.coerce("https://api.weather.gov/icons/land/day/bkn?size=small", p), 0);
        assertEquals(94, ResponseMapper.coerce("https://api.weather.gov/icons/land/day/ovc?size=small", p), 0);
        // a windy variant still names its sky code
        assertEquals(15, ResponseMapper.coerce("https://api.weather.gov/icons/land/day/wind_few?size=small", p), 0);
        // two conditions: the sky code appears somewhere and is taken
        assertEquals(37, ResponseMapper.coerce("https://api.weather.gov/icons/land/day/tsra_sct,40/sct,20?size=small", p), 0);
        // rain without a sky code is at least broken
        assertEquals(69, ResponseMapper.coerce("https://api.weather.gov/icons/land/day/rain_showers,30?size=small", p), 0);
    }

    @Test
    public void unknownCodeIsNoValueNotAGuess() throws IOException {
        final WxParam p = skyCover();
        assertTrue(Double.isNaN(ResponseMapper.coerce("https://api.weather.gov/icons/land/day/fog?size=small", p)));
        assertTrue(Double.isNaN(ResponseMapper.coerce(null, p)));
    }

    @Test
    public void aNumberPassesThroughALookup() throws IOException {
        final WxParam p = skyCover();
        assertEquals(50, ResponseMapper.coerce(50, p), 0);
    }
}

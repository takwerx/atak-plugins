package com.atakmap.android.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.atmosphere.data.Raws;

import org.junit.Test;

import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The by-id query: quoted ids, a doubled quote, and a URL the gateway will take. */
public class RawsByIdTest {

    private static String where(String url) throws Exception {
        final int i = url.indexOf("&where=");
        assertTrue(i > 0);
        return URLDecoder.decode(url.substring(i + 7), "UTF-8");
    }

    @Test
    public void quotesEveryId() throws Exception {
        assertEquals("WXID IN ('17150523','40101')",
                where(Raws.byIdUrl(Arrays.asList("17150523", "40101"))));
    }

    @Test
    public void doublesAQuoteInsideAnId() throws Exception {
        assertEquals("WXID IN ('O''NEAL')", where(Raws.byIdUrl(Arrays.asList("O'NEAL"))));
    }

    @Test
    public void skipsBlankIds() throws Exception {
        assertEquals("WXID IN ('1')",
                where(Raws.byIdUrl(Arrays.asList("", null, "1"))));
    }

    @Test
    public void aFullChunkStaysUnderTheGatewayLimit() {
        final List<String> ids = new ArrayList<>();
        for (int i = 0; i < Raws.MAX_IDS_PER_QUERY; i++)
            ids.add(String.valueOf(10000000 + i));
        assertTrue(Raws.byIdUrl(ids).length() < 2000);
    }
}

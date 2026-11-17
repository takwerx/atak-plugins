package com.atakmap.android.sitrep.section;

import static org.junit.Assert.assertEquals;

import org.json.JSONObject;
import org.junit.Test;

/** A feed that publishes late (Chicago crashes) asks for a longer window; every other row keeps the live 2 hours. */
public class CallsWindowTest {

    @Test
    public void defaultIsTwoHours() throws Exception {
        assertEquals(2L * 3_600_000L, Calls.windowMs(new JSONObject("{\"id\":\"seattle-fire\"}")));
        assertEquals(2L * 3_600_000L, Calls.windowMs(new JSONObject("{\"window_hours\":0}")));
        assertEquals(2L * 3_600_000L, Calls.windowMs(new JSONObject("{\"window_hours\":-5}")));
    }

    @Test
    public void rowAsksForLonger() throws Exception {
        assertEquals(48L * 3_600_000L, Calls.windowMs(new JSONObject("{\"window_hours\":48}")));
    }

    @Test
    public void capped() throws Exception {
        assertEquals(72L * 3_600_000L, Calls.windowMs(new JSONObject("{\"window_hours\":9999}")));
    }
}

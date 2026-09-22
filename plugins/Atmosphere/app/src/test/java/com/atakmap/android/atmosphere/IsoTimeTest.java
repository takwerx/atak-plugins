package com.atakmap.android.atmosphere;

import static org.junit.Assert.assertEquals;

import com.atakmap.android.atmosphere.data.IsoTime;

import org.junit.Test;

/** Forecast times decide which row an operator reads. Zone handling is not optional. */
public class IsoTimeTest {

    @Test
    public void bareTimestampIsUtc() {
        // Open-Meteo queried with timezone=UTC returns no offset at all.
        assertEquals(1755871200000L, IsoTime.parse("2025-08-22T14:00"));
    }

    @Test
    public void explicitZuluMatchesBare() {
        assertEquals(IsoTime.parse("2025-08-22T14:00"),
                IsoTime.parse("2025-08-22T14:00:00Z"));
    }

    @Test
    public void negativeOffsetShiftsForward() {
        // NWS returns local time with an offset: 14:00-06:00 is 20:00Z.
        assertEquals(IsoTime.parse("2025-08-22T20:00:00Z"),
                IsoTime.parse("2025-08-22T14:00:00-06:00"));
    }

    @Test
    public void positiveOffsetShiftsBack() {
        assertEquals(IsoTime.parse("2025-08-22T10:00:00Z"),
                IsoTime.parse("2025-08-22T12:00:00+02:00"));
    }

    @Test
    public void rubbishParsesToZeroRatherThanThrowing() {
        assertEquals(0L, IsoTime.parse(null));
        assertEquals(0L, IsoTime.parse("tomorrow"));
        assertEquals(0L, IsoTime.parse("2025-08-22"));
    }
}

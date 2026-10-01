
package com.atakmap.android.atmosphere;

import static org.junit.Assert.assertEquals;

import com.atakmap.android.atmosphere.data.Snooper;

import org.junit.Test;

public class SnooperTest {

    @Test
    public void windGoesUpThroughTheBands() {
        assertEquals(Snooper.PLAIN, Snooper.windBand(0));
        assertEquals(Snooper.PLAIN, Snooper.windBand(14.9));
        assertEquals(Snooper.NOTABLE, Snooper.windBand(15));
        assertEquals(Snooper.NOTABLE, Snooper.windBand(24));
        assertEquals(Snooper.NEAR_CRITICAL, Snooper.windBand(25));
        assertEquals(Snooper.NEAR_CRITICAL, Snooper.windBand(34));
        assertEquals(Snooper.EXTREME, Snooper.windBand(35));
        assertEquals(Snooper.EXTREME, Snooper.windBand(80));
    }

    @Test
    public void humidityAndFuelsRunTheOtherWay() {
        // The trap: writing all three as one comparison makes damp air dangerous.
        assertEquals(Snooper.PLAIN, Snooper.humidityBand(87));
        assertEquals(Snooper.PLAIN, Snooper.humidityBand(21));
        assertEquals(Snooper.NOTABLE, Snooper.humidityBand(20));
        assertEquals(Snooper.NOTABLE, Snooper.humidityBand(16));
        assertEquals(Snooper.NEAR_CRITICAL, Snooper.humidityBand(15));
        assertEquals(Snooper.NEAR_CRITICAL, Snooper.humidityBand(11));
        assertEquals(Snooper.EXTREME, Snooper.humidityBand(10));
        assertEquals(Snooper.EXTREME, Snooper.humidityBand(3));

        assertEquals(Snooper.PLAIN, Snooper.fuelBand(14));
        assertEquals(Snooper.PLAIN, Snooper.fuelBand(10));
        assertEquals(Snooper.NOTABLE, Snooper.fuelBand(9));
        assertEquals(Snooper.NOTABLE, Snooper.fuelBand(8));
        assertEquals(Snooper.NEAR_CRITICAL, Snooper.fuelBand(7));
        assertEquals(Snooper.NEAR_CRITICAL, Snooper.fuelBand(5));
        assertEquals(Snooper.EXTREME, Snooper.fuelBand(4));
        assertEquals(Snooper.EXTREME, Snooper.fuelBand(0));
    }

    @Test
    public void theSnoopersOwnRowsBandTheWayItPrintsThem() {
        // Read off the Snooper for 2026-09-25, where these are the emphasized values.
        assertEquals("Saddleback Butte RH 15", Snooper.NEAR_CRITICAL,
                Snooper.humidityBand(15));
        assertEquals("Lake Palmdale RH 17", Snooper.NOTABLE, Snooper.humidityBand(17));
        assertEquals("Malibu Cyn gust 25", Snooper.NEAR_CRITICAL, Snooper.windBand(25));
        assertEquals("Pepperdine gust 15", Snooper.NOTABLE, Snooper.windBand(15));
        assertEquals("a fuel stick at 3", Snooper.EXTREME, Snooper.fuelBand(3));
        assertEquals("a fuel stick at 7", Snooper.NEAR_CRITICAL, Snooper.fuelBand(7));
    }

    @Test
    public void aReadingTheStationDidNotSendIsNotEmphasized() {
        assertEquals(Snooper.PLAIN, Snooper.windBand(Double.NaN));
        assertEquals(Snooper.PLAIN, Snooper.humidityBand(Double.NaN));
        assertEquals(Snooper.PLAIN, Snooper.fuelBand(Double.NaN));
    }
}

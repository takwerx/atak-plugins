package com.atakmap.android.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.atmosphere.units.Quantity;
import com.atakmap.android.atmosphere.units.UnitSystem;
import com.atakmap.android.atmosphere.units.Units;

import org.junit.Test;

/**
 * Units are where a weather plugin quietly lies to its operator: a wind speed shown in
 * mph but read as knots is a real decision made on a wrong number. Every conversion
 * that ships is pinned here.
 */
public class UnitsTest {

    private static final double EPS = 1e-6;

    @Test
    public void temperatureToCanonical() {
        assertEquals(0.0, Units.toCanonical(Quantity.TEMPERATURE, "celsius", 0.0), EPS);
        assertEquals(0.0, Units.toCanonical(Quantity.TEMPERATURE, "F", 32.0), EPS);
        assertEquals(100.0, Units.toCanonical(Quantity.TEMPERATURE, "fahrenheit", 212.0), EPS);
        assertEquals(0.0, Units.toCanonical(Quantity.TEMPERATURE, "K", 273.15), EPS);
    }

    /** NWS reports units as UCUM codes; they must not fall through as "unknown". */
    @Test
    public void heightIsFeetOrMetersNeverMiles() {
        assertEquals(1659.0, Units.toCanonical(Quantity.HEIGHT, "wmoUnit:m", 1659.0), EPS);
        assertEquals(304.8, Units.toCanonical(Quantity.HEIGHT, "ft", 1000.0), EPS);
        assertEquals("5443 ft", Units.format(Quantity.HEIGHT, 1659.0, UnitSystem.IMPERIAL));
        assertEquals("5443 ft", Units.format(Quantity.HEIGHT, 1659.0, UnitSystem.AVIATION));
        assertEquals("1659 m", Units.format(Quantity.HEIGHT, 1659.0, UnitSystem.METRIC));
    }

    @Test
    public void wmoUnitCodesAreUnderstood() {
        assertEquals(20.0, Units.toCanonical(Quantity.TEMPERATURE, "wmoUnit:degC", 20.0), EPS);
        assertEquals(10.0, Units.toCanonical(Quantity.SPEED, "wmoUnit:m_s-1", 10.0), EPS);
        assertEquals(10.0, Units.toCanonical(Quantity.SPEED, "wmoUnit:km_h-1", 36.0), EPS);
        assertEquals(55.0, Units.toCanonical(Quantity.PERCENT, "wmoUnit:percent", 55.0), EPS);
    }

    @Test
    public void speedToCanonical() {
        assertEquals(1.0, Units.toCanonical(Quantity.SPEED, "m/s", 1.0), EPS);
        assertEquals(10.0, Units.toCanonical(Quantity.SPEED, "km/h", 36.0), EPS);
        assertEquals(0.44704, Units.toCanonical(Quantity.SPEED, "mph", 1.0), EPS);
        assertEquals(0.514444, Units.toCanonical(Quantity.SPEED, "kn", 1.0), EPS);
    }

    @Test
    public void pressureAndPrecipitation() {
        assertEquals(1013.25, Units.toCanonical(Quantity.PRESSURE, "hPa", 1013.25), EPS);
        assertEquals(1013.25, Units.toCanonical(Quantity.PRESSURE, "Pa", 101325.0), EPS);
        assertEquals(25.4, Units.toCanonical(Quantity.PRECIPITATION, "in", 1.0), EPS);
    }

    /** A round trip must not drift: the operator can flip units all day. */
    @Test
    public void displayRoundTrips() {
        final double canonical = 12.3456;
        for (UnitSystem system : UnitSystem.values()) {
            final double shown = Units.toDisplay(Quantity.SPEED, canonical, system);
            final String unit = Units.displayUnit(Quantity.SPEED, system);
            final double back = Units.toCanonical(Quantity.SPEED, unit, shown);
            assertEquals("round trip via " + unit, canonical, back, 1e-9);
        }
    }

    @Test
    public void formattingCarriesTheUnit() {
        assertEquals("20 °C", Units.format(Quantity.TEMPERATURE, 20.0, UnitSystem.METRIC));
        assertEquals("68 °F", Units.format(Quantity.TEMPERATURE, 20.0, UnitSystem.IMPERIAL));
        assertEquals("19 kt", Units.format(Quantity.SPEED, 10.0, UnitSystem.AVIATION));
        assertEquals("270°", Units.format(Quantity.ANGLE, 270.0, UnitSystem.METRIC));
        assertEquals("65%", Units.format(Quantity.PERCENT, 65.0, UnitSystem.IMPERIAL));
    }

    @Test
    public void compassBothWays() {
        assertEquals(315.0, Units.compassToDegrees("NW"), EPS);
        assertEquals(0.0, Units.compassToDegrees("n"), EPS);
        assertTrue(Double.isNaN(Units.compassToDegrees("VRB")));
        assertEquals("NW", Units.degreesToCompass(315.0));
        assertEquals("N", Units.degreesToCompass(359.0));
    }

    /** An unknown unit passes the value through rather than inventing a conversion. */
    @Test
    public void unknownUnitIsPassedThrough() {
        assertEquals(7.0, Units.toCanonical(Quantity.SPEED, "furlongs/fortnight", 7.0), EPS);
    }
}

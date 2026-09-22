package com.atakmap.android.atmosphere.units;

import java.util.Locale;

/**
 * Unit handling for the whole plugin, in one place.
 *
 * <p>Two rules make this predictable:
 * <ol>
 *   <li>Every value is converted to a <b>canonical</b> unit the moment it is parsed —
 *       Celsius, metres per second, metres, millimetres, hectopascals, degrees. Nothing
 *       downstream has to know what the API sent.</li>
 *   <li>The unit system is applied at <b>display</b> time only. Switching it re-renders;
 *       it never refetches and never round-trips a value through a lossy conversion.</li>
 * </ol>
 *
 * <p>Source definitions declare the unit the API returns (e.g. {@code "fahrenheit"},
 * {@code "wmoUnit:km_h-1"}), so adding an API is a config change, not a code change.
 */
public final class Units {

    private Units() {
    }

    private static final String[] COMPASS = {
            "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
            "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"
    };

    /**
     * Convert a raw API value into the canonical unit for its quantity.
     *
     * @param unit the unit the source declares, or null/empty to mean "already canonical"
     * @return the canonical value, or the input unchanged when the unit is unrecognized
     */
    public static double toCanonical(Quantity quantity, String unit, double value) {
        String u = normalize(unit);
        if (u.isEmpty())
            return value;

        switch (quantity) {
            case TEMPERATURE:
                if (u.equals("c") || u.equals("degc") || u.equals("celsius"))
                    return value;
                if (u.equals("f") || u.equals("degf") || u.equals("fahrenheit"))
                    return (value - 32.0) * 5.0 / 9.0;
                if (u.equals("k") || u.equals("kelvin"))
                    return value - 273.15;
                return value;

            case SPEED:
                if (u.equals("ms") || u.equals("ms-1") || u.equals("mps"))
                    return value;
                if (u.equals("kmh") || u.equals("kmh-1") || u.equals("kph"))
                    return value / 3.6;
                if (u.equals("mph") || u.equals("mih-1"))
                    return value * 0.44704;
                if (u.equals("kn") || u.equals("kt") || u.equals("kts")
                        || u.equals("knot") || u.equals("knots"))
                    return value * 0.514444;
                return value;

            case LENGTH:
                if (u.equals("m"))
                    return value;
                if (u.equals("km"))
                    return value * 1000.0;
                if (u.equals("ft") || u.equals("feet"))
                    return value * 0.3048;
                if (u.equals("mi") || u.equals("mile") || u.equals("miles"))
                    return value * 1609.344;
                if (u.equals("nm") || u.equals("nmi"))
                    return value * 1852.0;
                return value;

            case PRECIPITATION:
                if (u.equals("mm"))
                    return value;
                if (u.equals("cm"))
                    return value * 10.0;
                if (u.equals("m"))
                    return value * 1000.0;
                if (u.equals("in") || u.equals("inch") || u.equals("inches"))
                    return value * 25.4;
                return value;

            case PRESSURE:
                if (u.equals("hpa") || u.equals("mb") || u.equals("mbar")
                        || u.equals("millibar"))
                    return value;
                if (u.equals("pa"))
                    return value / 100.0;
                if (u.equals("kpa"))
                    return value * 10.0;
                if (u.equals("inhg") || u.equals("inches_hg"))
                    return value * 33.8638866667;
                return value;

            default:
                return value;
        }
    }

    /** The value as shown for {@code system}, still a number (no unit suffix). */
    public static double toDisplay(Quantity quantity, double canonical, UnitSystem system) {
        boolean metric = system == UnitSystem.METRIC;
        switch (quantity) {
            case TEMPERATURE:
                return metric ? canonical : canonical * 9.0 / 5.0 + 32.0;
            case SPEED:
                if (metric)
                    return canonical * 3.6;                 // km/h reads better than m/s
                if (system == UnitSystem.AVIATION)
                    return canonical / 0.514444;            // knots
                return canonical / 0.44704;                 // mph
            case LENGTH:
                if (metric)
                    return canonical / 1000.0;              // km
                if (system == UnitSystem.AVIATION)
                    return canonical / 1852.0;              // nautical miles
                return canonical / 1609.344;                // statute miles
            case PRECIPITATION:
                return metric ? canonical : canonical / 25.4;
            case PRESSURE:
                if (system == UnitSystem.AVIATION)
                    return canonical / 33.8638866667;       // inHg
                return canonical;                           // hPa for metric and imperial
            default:
                return canonical;
        }
    }

    /** The unit suffix for a display value, e.g. {@code "°F"}. Empty for scalars. */
    public static String displayUnit(Quantity quantity, UnitSystem system) {
        boolean metric = system == UnitSystem.METRIC;
        switch (quantity) {
            case TEMPERATURE:
                return metric ? "°C" : "°F";
            case SPEED:
                if (metric)
                    return "km/h";
                return system == UnitSystem.AVIATION ? "kt" : "mph";
            case LENGTH:
                if (metric)
                    return "km";
                return system == UnitSystem.AVIATION ? "NM" : "mi";
            case PRECIPITATION:
                return metric ? "mm" : "in";
            case PRESSURE:
                return system == UnitSystem.AVIATION ? "inHg" : "hPa";
            case ANGLE:
                return "°";
            case PERCENT:
                return "%";
            default:
                return "";
        }
    }

    /** Display value plus unit, rounded to a sensible precision for the quantity. */
    public static String format(Quantity quantity, double canonical, UnitSystem system) {
        double v = toDisplay(quantity, canonical, system);
        String unit = displayUnit(quantity, system);
        int decimals = decimalsFor(quantity, system);
        String number = String.format(Locale.US, "%." + decimals + "f", v);
        if (unit.isEmpty())
            return number;
        // Bare degrees and percent read better closed up ("270°", "65%"); °C and °F keep
        // the SI space ("20.0 °C").
        if (unit.equals("°") || unit.equals("%"))
            return number + unit;
        return number + " " + unit;
    }

    private static int decimalsFor(Quantity quantity, UnitSystem system) {
        switch (quantity) {
            case PRECIPITATION:
                return system == UnitSystem.METRIC ? 1 : 2;
            case PRESSURE:
                return system == UnitSystem.AVIATION ? 2 : 0;
            case LENGTH:
                return 1;
            // Whole degrees and whole speeds: a crew reads "72 °F" and "10 mph" off a
            // mount; the tenth is noise the forecast does not have anyway.
            case TEMPERATURE:
            case SPEED:
                return 0;
            default:
                return 0;
        }
    }

    /**
     * A 16-point compass string to degrees true. Several public APIs report wind
     * direction this way ("NW") rather than numerically.
     *
     * @return degrees, or {@link Double#NaN} if this is not a compass point
     */
    public static double compassToDegrees(String compass) {
        if (compass == null)
            return Double.NaN;
        String c = compass.trim().toUpperCase(Locale.US);
        for (int i = 0; i < COMPASS.length; i++) {
            if (COMPASS[i].equals(c))
                return i * 22.5;
        }
        return Double.NaN;
    }

    /** Degrees true to a 16-point compass string, for labels. */
    public static String degreesToCompass(double degrees) {
        if (Double.isNaN(degrees))
            return "";
        double d = degrees % 360.0;
        if (d < 0)
            d += 360.0;
        int idx = (int) Math.round(d / 22.5) % COMPASS.length;
        return COMPASS[idx];
    }

    private static String normalize(String unit) {
        if (unit == null)
            return "";
        String u = unit.trim().toLowerCase(Locale.US);
        // NWS returns UCUM-ish codes: "wmoUnit:degC", "wmoUnit:km_h-1", "percent".
        int colon = u.indexOf(':');
        if (colon >= 0)
            u = u.substring(colon + 1);
        if (u.equals("percent"))
            return "";
        StringBuilder sb = new StringBuilder(u.length());
        for (int i = 0; i < u.length(); i++) {
            char ch = u.charAt(i);
            if (ch == '/' || ch == '_' || ch == ' ' || ch == '.')
                continue;
            sb.append(ch);
        }
        return sb.toString();
    }
}

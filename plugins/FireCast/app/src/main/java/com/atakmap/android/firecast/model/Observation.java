package com.atakmap.android.firecast.model;

import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.Locale;

/**
 * The operator's own weather, typed in or taken from a Kestrel Wx station: the run
 * uses it instead of the nearest station (operator, 2026-10-31: "bring in my own
 * weather observations ... manually input it or bring it in from this Kestrel
 * plugin"). Kept in prefs until changed or cleared, so it survives a restart.
 */
public final class Observation {

    public final double windMph, windFromDeg, gustMph, tempF, rh, fineFuelPct;
    /** When it was read, epoch ms. */
    public final long at;
    /** Read at eye level (a hand-held meter): used as the midflame wind. Otherwise at 20 ft. */
    public final boolean eyeLevel;
    /** Where it came from, for the page: "your observation" or "Kestrel <callsign>". */
    public final String source;

    /** The oldest observation a run takes; past it the station or forecast is nearer the truth. */
    public static final long MAX_AGE_MS = 6L * 3600_000L;
    private static final String PREF = "model.observation";

    public Observation(double windMph, double windFromDeg, double gustMph, double tempF, double rh, double fineFuelPct,
            long at, boolean eyeLevel, String source) {
        this.windMph = windMph;
        this.windFromDeg = windFromDeg;
        this.gustMph = gustMph;
        this.tempF = tempF;
        this.rh = rh;
        this.fineFuelPct = fineFuelPct;
        this.at = at;
        this.eyeLevel = eyeLevel;
        this.source = source;
    }

    /** Null when any number is outside what a real reading can be; the same limits the fire library's bridge holds. */
    public static Observation checked(double windMph, double windFromDeg, double gustMph, double tempF, double rh,
            double fineFuelPct, long at, boolean eyeLevel, String source) {
        if (!(windMph >= 0 && windMph <= 150) || !(windFromDeg >= 0 && windFromDeg <= 360))
            return null;
        if (!Double.isNaN(gustMph) && !(gustMph >= windMph && gustMph <= 200))
            gustMph = Double.NaN;
        if (!Double.isNaN(tempF) && !(tempF >= -40 && tempF <= 130))
            return null;
        if (!Double.isNaN(rh) && !(rh >= 1 && rh <= 100))
            return null;
        if (!Double.isNaN(fineFuelPct) && !(fineFuelPct >= 1 && fineFuelPct <= 60))
            return null;
        if (at <= 0)
            return null;
        final String s = source == null || source.trim().isEmpty() ? "your observation"
                : source.replaceAll("[^\\p{L}\\p{N} ._'()#/-]", "").trim();
        return new Observation(windMph, windFromDeg % 360, gustMph, tempF, rh, fineFuelPct, at, eyeLevel,
                s.length() > 60 ? s.substring(0, 60) : s);
    }

    public boolean usable(long now) {
        return at <= now + 60_000L && now - at <= MAX_AGE_MS;
    }

    /** "8 mph from SW, gust 15, 78°F, 14% RH, eye level, your observation at 23:40". */
    public String summary() {
        final StringBuilder b = new StringBuilder(String.format(Locale.US, "%.0f mph from %s",
                windMph, com.atakmap.android.firecast.Units.compass(windFromDeg)));
        if (!Double.isNaN(gustMph))
            b.append(String.format(Locale.US, ", gust %.0f", gustMph));
        if (!Double.isNaN(tempF))
            b.append(String.format(Locale.US, ", %.0f°F", tempF));
        if (!Double.isNaN(rh))
            b.append(String.format(Locale.US, ", %.0f%% RH", rh));
        if (!Double.isNaN(fineFuelPct))
            b.append(String.format(Locale.US, ", 1 h fuel %.0f%%", fineFuelPct));
        b.append(eyeLevel ? ", eye level" : ", 20 ft");
        return b.append(", ").append(source).append(" at ").append(FireModel.stamp(at)).toString();
    }

    public static Observation load(SharedPreferences prefs) {
        final String s = prefs.getString(PREF, null);
        if (s == null)
            return null;
        try {
            final JSONObject o = new JSONObject(s);
            return checked(o.optDouble("wind", Double.NaN), o.optDouble("from", Double.NaN), o.optDouble("gust", Double.NaN),
                    o.optDouble("temp", Double.NaN), o.optDouble("rh", Double.NaN), o.optDouble("fm1", Double.NaN),
                    o.optLong("at", 0), o.optBoolean("eye", true), o.optString("src", null));
        } catch (Exception e) {
            return null;
        }
    }

    public static void save(SharedPreferences prefs, Observation ob) {
        if (ob == null) {
            prefs.edit().remove(PREF).apply();
            return;
        }
        try {
            final JSONObject o = new JSONObject().put("wind", ob.windMph).put("from", ob.windFromDeg).put("at", ob.at)
                    .put("eye", ob.eyeLevel).put("src", ob.source);
            if (!Double.isNaN(ob.gustMph))
                o.put("gust", ob.gustMph);
            if (!Double.isNaN(ob.tempF))
                o.put("temp", ob.tempF);
            if (!Double.isNaN(ob.rh))
                o.put("rh", ob.rh);
            if (!Double.isNaN(ob.fineFuelPct))
                o.put("fm1", ob.fineFuelPct);
            prefs.edit().putString(PREF, o.toString()).apply();
        } catch (Exception ignored) {
        }
    }
}

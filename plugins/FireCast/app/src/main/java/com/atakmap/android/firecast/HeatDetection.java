package com.atakmap.android.firecast;

import com.atakmap.map.layer.feature.AttributeSet;

import java.util.Locale;

/**
 * One heat detection, from whichever feed served it.
 *
 * <p>There is deliberately one type here and not one per sensor. The differences
 * between VIIRS, MODIS, GOES and RADR are in how a value is arrived at - and that is
 * carried, in {@link Footprint#basis} and {@link #caveats} - but what an operator does
 * with a detection is the same in every case: where is it, how big is the uncertainty,
 * how hot, how old, how much do I trust it. Everything downstream (the map, the list,
 * the detail panel, and the fire model in a later version) is written against this
 * type, so an Earth Fire Alliance / FireSat feed is a new {@link DetectionSource} that
 * fills this in, not a second code path through the plugin.
 */
public class HeatDetection {

    /** How much the feed itself says it trusts the detection. */
    public enum Confidence { LOW, NOMINAL, HIGH, UNKNOWN }

    /** Which feed it came from, and the name the UI shows for that feed. */
    public final String sourceId, sourceTitle;
    /** The instrument, as the feed names it: "NOAA-20 VIIRS", "GOES-18", "landsat8". */
    public final String sensor;
    /**
     * Where it is. Always taken from the feature's geometry, never from the service's
     * own latitude/longitude fields: on VIIRS and MODIS those two fields hold each
     * other's values ({@code Latitude: "-155.29541"} beside {@code x: -155.29541}), and
     * on GOES they disagree with the geometry by a few kilometers.
     */
    public final double lat, lon;
    /** When the satellite saw it, epoch ms; 0 when the feed does not say. */
    public final long acquired;
    /** The ground it covers, and how that was arrived at. */
    public final Footprint footprint;

    /** Fire radiative power, megawatts; null when the feed carries none. */
    public Double frpMw;
    /** Brightness temperature, kelvin; null when the feed carries none. */
    public Double brightnessK;
    public Confidence confidence = Confidence.UNKNOWN;
    /** The confidence exactly as served, for the detail panel: "Nominal", "90", "nominal". */
    public String confidenceText;
    /** True day, false night, null when unsaid. */
    public Boolean day;
    /** The feed's own class for it: "Known Wildland Fire Incident", "intense heat". */
    public String classification;
    /** The incident the feed has already tied it to, when it has. */
    public String incidentName;
    /** How long this has been detected in one place, hours; GOES only. */
    public Double persistenceHours;
    /** The feed's identifier for the persisting feature, so the same fire can be followed. */
    public String trackingId;
    /** How many sensor pixels the feed merged into this record; null when it does not say. */
    public Integer pixelCount;

    /** Inside a known industrial heat source in the static mask. */
    public boolean inIndustrialMask;
    /** The feed's own class says this is probably not a wildfire (a solar farm, a structure). */
    public boolean classedNonFire;

    /** What this detection does not mean. Written by the source; shown on the detail panel. */
    public String[] caveats = new String[0];

    /** Every field the service served, for the bottom of the detail panel. */
    public AttributeSet raw;

    public HeatDetection(String sourceId, String sourceTitle, String sensor, double lat, double lon,
            long acquired, Footprint footprint) {
        this.sourceId = sourceId;
        this.sourceTitle = sourceTitle;
        this.sensor = sensor;
        this.lat = lat;
        this.lon = lon;
        this.acquired = acquired;
        this.footprint = footprint;
    }

    /** Anything the plugin or the operator has reason to treat as not a wildfire. */
    public boolean suspect() {
        return inIndustrialMask || classedNonFire;
    }

    public long ageMillis(long now) {
        return acquired > 0 ? Math.max(0L, now - acquired) : Long.MAX_VALUE;
    }

    /** "14 min", "3 h 20", "2 d"; "age unknown" when the feed served no time. */
    public String ageText(long now) {
        if (acquired <= 0)
            return "age unknown";
        final long mins = ageMillis(now) / 60000L;
        if (mins < 60)
            return mins + " min";
        if (mins < 60 * 48)
            return (mins / 60) + " h " + String.format(Locale.US, "%02d", mins % 60);
        return (mins / 1440) + " d";
    }

    /**
     * A stable identity for the detection, so the same pixel served twice is one thing.
     * Position, time and sensor together: these feeds have no id that survives their own
     * refresh - VIIRS and MODIS number rows from 1 on every rebuild, so OBJECTID names a
     * different detection tomorrow.
     */
    public String uid() {
        return sourceId + ":" + sensor + ":" + acquired + ":"
                + String.format(Locale.US, "%.5f,%.5f", lat, lon);
    }

    /** The single line the map list shows first. */
    public String headline() {
        final StringBuilder sb = new StringBuilder(sourceTitle);
        if (frpMw != null)
            sb.append(" · ").append(frpText());
        sb.append(" · ").append(footprint.sizeText());
        return sb.toString();
    }

    public String frpText() {
        if (frpMw == null)
            return "no power reading";
        return frpMw >= 100 ? String.format(Locale.US, "%.0f MW", frpMw)
                : String.format(Locale.US, "%.1f MW", frpMw);
    }

    public String brightnessText() {
        return brightnessK == null ? null : String.format(Locale.US, "%.1f K", brightnessK);
    }
}

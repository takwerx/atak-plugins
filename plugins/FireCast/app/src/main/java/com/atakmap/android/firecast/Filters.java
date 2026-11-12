package com.atakmap.android.firecast;

import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/**
 * What the operator has asked to see, remembered across restarts.
 *
 * <p>Every filter here is one an operator can state a reason for, and each one says on
 * its button what it will cost before it is used - the count goes on the control, not
 * in a status line somewhere else, so nobody has to toggle a thing and watch a total
 * move to learn what it does.
 */
public class Filters {

    /** What to do with a detection the industrial mask or the feed's own class flags. */
    public static final int INDUSTRIAL_HIDE = 0, INDUSTRIAL_FLAG = 1;

    private final SharedPreferences prefs;

    private final Set<String> sourcesOn = new HashSet<>();
    /** How far back to ask for, hours. */
    public int windowHours = 24;
    /** 0 everything, 1 nominal and high, 2 high only. */
    public int minConfidence = 0;
    public int industrial = INDUSTRIAL_FLAG;
    /** Draw the mask areas themselves, so the operator can see what is being flagged. */
    public boolean showMaskAreas = false;
    /** Minutes between automatic refreshes; 0 is manual only. */
    public int refreshMinutes = 10;
    /** {@link #AREA_VIEW} or {@link #AREA_RADIUS}: the area the map and the list answer. */
    public String area = AREA_VIEW;
    /** The radius, meters, when the area is a circle around the operator. */
    public double areaM = Units.bigToMeters(25);

    public static final String AREA_VIEW = "view", AREA_RADIUS = "radius";
    /** Radius presets in the operator's large unit, so the list reads in one unit. */
    public static final int[] AREA_CHOICES = { 5, 15, 25, 50 };

    public static final int[] WINDOW_CHOICES = { 3, 6, 12, 24, 48, 72 };
    public static final int[] REFRESH_CHOICES = { 0, 5, 10, 15, 30, 60 };

    public Filters(SharedPreferences prefs) {
        this.prefs = prefs;
        for (DetectionSource s : Sources.all())
            if (prefs.getBoolean("src." + s.id(), s.defaultOn()))
                sourcesOn.add(s.id());
        windowHours = prefs.getInt("windowHours", 24);
        minConfidence = prefs.getInt("minConfidence", 0);
        industrial = prefs.getInt("industrial", INDUSTRIAL_FLAG);
        showMaskAreas = prefs.getBoolean("showMaskAreas", false);
        refreshMinutes = prefs.getInt("refreshMinutes", 10);
        area = prefs.getString("area", AREA_VIEW);
        areaM = prefs.getFloat("areaM", (float) Units.bigToMeters(25));
    }

    public boolean areaIsRadius() {
        return AREA_RADIUS.equals(area);
    }

    public boolean isOn(String sourceId) {
        return sourcesOn.contains(sourceId);
    }

    public void setOn(String sourceId, boolean on) {
        if (on)
            sourcesOn.add(sourceId);
        else
            sourcesOn.remove(sourceId);
        prefs.edit().putBoolean("src." + sourceId, on).apply();
    }

    public void save() {
        prefs.edit()
                .putInt("windowHours", windowHours)
                .putInt("minConfidence", minConfidence)
                .putInt("industrial", industrial)
                .putBoolean("showMaskAreas", showMaskAreas)
                .putInt("refreshMinutes", refreshMinutes)
                .putString("area", area)
                .putFloat("areaM", (float) areaM)
                .apply();
    }

    public long windowMillis() {
        return windowHours * 3600000L;
    }

    /** The moment the window starts. */
    public long since(long now) {
        return now - windowMillis();
    }

    /** The shortest window NASA's fire event perimeters are shown with, the same three days their lists use. */
    public static final long FEDS_WINDOW_MS = 72L * 3600_000L;

    /** Whether a fetched detection should be on the map right now. */
    public boolean shows(HeatDetection d, long now) {
        if (!isOn(d.sourceId))
            return false;
        // NASA's outlines come every 12 hours and land up to a day late, so the window is
        // never under three days for them; a 6 h window hid every one (2026-10-30).
        final long window = "feds".equals(d.sourceId) ? Math.max(windowMillis(), FEDS_WINDOW_MS) : windowMillis();
        if (d.acquired > 0 && d.ageMillis(now) > window)
            return false;
        if (industrial == INDUSTRIAL_HIDE && d.suspect())
            return false;
        if (minConfidence == 0)
            return true;
        // An unknown confidence is never filtered out by a confidence gate: RADR states
        // none at all, and dropping those would quietly remove a whole feed from a
        // control that does not mention it.
        switch (d.confidence) {
            case HIGH:
                return true;
            case NOMINAL:
                return minConfidence <= 1;
            case LOW:
                return false;
            default:
                return true;
        }
    }

    public String windowLabel() {
        return windowHours >= 48 ? (windowHours / 24) + " days" : windowHours + " h";
    }

    public String confidenceLabel() {
        return minConfidence == 0 ? "Any confidence"
                : minConfidence == 1 ? "Nominal and high" : "High only";
    }

    public String industrialLabel() {
        return industrial == INDUSTRIAL_HIDE ? "Industrial hidden" : "Industrial flagged";
    }

    /** The Area row's head, "Name: value", the way every takwerx Settings row reads. */
    public String areaLabel() {
        return areaIsRadius()
                ? "Area: Within " + Units.formatBig(areaM) + " of My Location"
                : "Area: What is in view";
    }

    public String refreshLabel() {
        return refreshMinutes == 0 ? "Refresh: manual" : "Refresh: " + refreshMinutes + " min";
    }
}

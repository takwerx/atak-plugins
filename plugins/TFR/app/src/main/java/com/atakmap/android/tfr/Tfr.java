package com.atakmap.android.tfr;

import java.util.ArrayList;
import java.util.List;

/**
 * One Temporary Flight Restriction: the six fields the list API gives, plus everything
 * parsed out of its detail XML.
 *
 * <p>Plain Java, no ATAK or Android types — see {@link TfrArea}.
 */
public class Tfr {

    // ---- from the list API (https://tfr.faa.gov/tfrapi/exportTfrList) ----
    /** "6/3349". The list's number, not the XML's {@code txtLocalName}, which disagrees. */
    public String notamId = "";
    /** SECURITY, HAZARDS, AIR SHOWS/SPORTS, UAS PUBLIC GATHERING, SPACE OPERATIONS, VIP, SPECIAL. */
    public String type = "";
    /** Two-letter state, or "USA" for the standing nationwide notices. */
    public String state = "";
    /** ARTCC, e.g. ZOA. */
    public String facility = "";
    /** The list's prose, place and dates run together. */
    public String description = "";
    public String creationDate = "";

    // ---- from the detail XML ----
    /** "2NM E WAWONA" — the plain place name, on 93 of 104. The row leads with this. */
    public String city = "";
    /** "CALIFORNIA". */
    public String stateName = "";
    /** The NOTAM as published, for the details pane. */
    public String notamText = "";
    /** NOTAM-level window, UTC millis. {@code expireMs == 0} means it does not expire. */
    public long effectiveMs, expireMs;
    /**
     * True when the XML's {@code codeTimeZone} was not one we know how to offset. The window
     * is then a guess, and {@link #isActive} widens it rather than claiming a precise answer.
     */
    public boolean windowApproximate;
    /** What the XML said the zone was, for the details pane. */
    public String timeZone = "";
    public final List<TfrArea> areas = new ArrayList<>();
    /** True once the detail XML has been fetched and parsed; false when we only have the list row. */
    public boolean detailLoaded;

    /** The place to show. Falls back to the list prose when the XML had no city (11 of 104). */
    public String place() {
        if (city != null && !city.trim().isEmpty())
            return city.trim();
        String d = description == null ? "" : description.trim();
        int comma = d.indexOf(',');
        // The list prose is "<place>, <ST>, <dates>"; the dates are not a place.
        if (comma > 0) {
            int second = d.indexOf(',', comma + 1);
            return second > 0 ? d.substring(0, second) : d.substring(0, comma);
        }
        return d.isEmpty() ? notamId : d;
    }

    /** Every area that has a ring. The rest exist and are listed, but cannot be drawn. */
    public List<TfrArea> drawable() {
        List<TfrArea> out = new ArrayList<>();
        for (TfrArea a : areas)
            if (a.drawable())
                out.add(a);
        return out;
    }

    /**
     * True when this TFR has no shape anywhere — 12 of 104 nationally, all of them standing
     * nationwide notices. They are listed and marked, never silently dropped: a TFR that is
     * missing from the map reads as airspace that is clear.
     */
    public boolean hasNoMappedArea() {
        return detailLoaded && drawable().isEmpty();
    }

    /**
     * Whether the restriction is in effect at {@code nowMs}, which is what decides its color.
     *
     * <p>When the zone could not be resolved the window is widened by twelve hours in both
     * directions instead of being trusted: an airspace restriction wrongly drawn as live is a
     * cautious error, and one wrongly drawn as not-yet-in-effect is not.
     */
    public boolean isActive(long nowMs) {
        long slop = windowApproximate ? 12L * 3600L * 1000L : 0L;
        long from = effectiveMs == 0 ? Long.MIN_VALUE : effectiveMs - slop;
        long to = expireMs == 0 ? Long.MAX_VALUE : expireMs + slop;
        return nowMs >= from && nowMs <= to;
    }

    /** True when it has a start in the future: scheduled, not yet on. Drawn amber. */
    public boolean isUpcoming(long nowMs) {
        return effectiveMs != 0 && nowMs < effectiveMs && !isActive(nowMs);
    }

    /** The FAA's own human-readable page, for the details pane. */
    public String faaPageUrl() {
        return "https://tfr.faa.gov/tfr3/?page=detail_" + fileId();
    }

    public String faaPrintUrl() {
        return "https://tfr.faa.gov/save_pages/detail_" + fileId() + ".html";
    }

    public String detailXmlUrl() {
        return "https://tfr.faa.gov/download/detail_" + fileId() + ".xml";
    }

    /** "6/3349" is "6_3349" in every FAA URL and file name. */
    public String fileId() {
        return notamId.replace('/', '_');
    }
}

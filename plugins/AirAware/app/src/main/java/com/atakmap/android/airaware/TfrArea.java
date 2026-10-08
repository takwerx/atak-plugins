package com.atakmap.android.airaware;

import java.util.ArrayList;
import java.util.List;

/**
 * One area of a TFR: a ring, a floor and a ceiling, and its own schedule when it has one.
 *
 * <p>A TFR is often more than one area — a fire with an inner and an outer ring, a stadium
 * with a shelf above it. Ten of the 104 in the national picture on 2026-09-16 had more than
 * one, up to five, and nine of those had per-area schedules that differed from the NOTAM's
 * own window. So the area, not the TFR, is the unit that gets drawn and colored.
 *
 * <p>No ATAK or Android types here on purpose: this and {@link TfrParser} are plain Java so
 * the parsing can be run against the real feed on a desktop JVM without a device.
 */
public class TfrArea {

    /** How high, and measured from what. */
    public static class Vert {
        /** False when the XML carried no such limit at all. */
        public boolean present;
        /** Always feet. See {@link #label()} for why this one does not follow ATAK's units. */
        public int feet;
        /** HEI in the XML: above the surface. ALT: above mean sea level. */
        public boolean agl;
        /** The source said FL, so it is spoken as a flight level and shown as one. */
        public boolean flightLevel;
        /** The source said the surface, whatever number came with it. */
        public boolean surface;
        /** Exactly what the XML said, for the details pane. */
        public String raw = "";

        /**
         * What an operator should read.
         *
         * <p>Feet, always, and never converted through ATAK's range-units preference. That
         * preference is for distances on the ground; an altitude in a NOTAM is published in
         * feet or flight levels and is spoken that way by everyone in the airspace. Rendering
         * an 11,500 ft ceiling as "3505 m" because a phone is set to metric would invent a
         * number the NOTAM does not contain.
         */
        public String label() {
            if (!present)
                return "unstated";
            if (surface)
                return "surface";
            if (flightLevel)
                return "FL" + (feet / 100) + " (" + comma(feet) + " ft MSL)";
            return comma(feet) + " ft " + (agl ? "AGL" : "MSL");
        }

        private static String comma(int n) {
            String s = Integer.toString(n);
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < s.length(); i++) {
                if (i > 0 && (s.length() - i) % 3 == 0)
                    b.append(',');
                b.append(s.charAt(i));
            }
            return b.toString();
        }
    }

    /** {@code txtName} / {@code txtLocalName} off the area, when it has one. */
    public String name = "";
    /** The ring, {lat, lon} per vertex, from {@code abdMergedArea}. Empty means nothing to draw. */
    public final List<double[]> ring = new ArrayList<>();
    public final Vert floor = new Vert();
    public final Vert ceiling = new Vert();
    /**
     * The radius the NOTAM was written with, when the source description was a circle.
     * Purely for the label — "7 NM ring" reads better than "37 points" — never for the shape,
     * because the FAA already expanded it into {@link #ring}.
     */
    public double radiusNm;
    /** Center of that circle, when there was one. */
    public double[] center;
    /** Per-area window; 0 means "inherit the NOTAM's". */
    public long effectiveMs, expireMs;

    public boolean drawable() {
        return ring.size() >= 3;
    }
}

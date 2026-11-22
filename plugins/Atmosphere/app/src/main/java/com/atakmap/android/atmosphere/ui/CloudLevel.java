package com.atakmap.android.atmosphere.ui;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The three cloud decks a forecast can report as separate percentages: low (below about
 * 6,500 ft), mid and high (above about 20,000 ft). They are not the same reading as
 * total sky cover, which the pane draws as the okta circle, so the pane has to tell them
 * apart by name before it falls back to "anything with cloud in it is sky cover".
 */
public enum CloudLevel {
    LOW("Low cloud", "Low cl"),
    MID("Mid cloud", "Mid cl"),
    HIGH("High cloud", "High cl");

    /** Tile and chip text. */
    public final String label;
    /** Legend text for a narrow column. */
    public final String shortLabel;

    private static final Pattern WORD_LOW = Pattern.compile("(^|[^a-z])low([^a-z]|$)");
    private static final Pattern WORD_MID = Pattern.compile("(^|[^a-z])mid([^a-z]|$)");
    private static final Pattern WORD_HIGH = Pattern.compile("(^|[^a-z])high([^a-z]|$)");

    CloudLevel(String label, String shortLabel) {
        this.label = label;
        this.shortLabel = shortLabel;
    }

    /**
     * @param keyAndLabel a reading's key and label, for example
     *                    {@code "cloud_cover_low Low cloud"}
     * @return the deck the reading names, or null when it is not a cloud level (total
     *         sky cover, or anything else)
     */
    public static CloudLevel of(String keyAndLabel) {
        if (keyAndLabel == null)
            return null;
        final String k = keyAndLabel.toLowerCase(Locale.US);
        if (!k.contains("cloud"))
            return null;
        // whole words only: "cloud_cover_low" and "Low cloud", never "cloud_cover_highres"
        if (WORD_LOW.matcher(k).find()) return LOW;
        if (WORD_MID.matcher(k).find()) return MID;
        if (WORD_HIGH.matcher(k).find()) return HIGH;
        return null;
    }
}

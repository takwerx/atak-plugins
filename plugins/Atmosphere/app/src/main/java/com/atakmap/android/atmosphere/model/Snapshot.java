package com.atakmap.android.atmosphere.model;

import java.util.Collections;
import java.util.List;

/**
 * What one source said about one place at one time.
 *
 * <p>A snapshot always carries {@link #fetchedAt}, and the UI always renders its age.
 * Weather that might be four hours old must never look like weather from now — that is
 * the whole point of being usable after comms drop.
 */
public final class Snapshot {

    public final String sourceId;
    public final String sourceName;
    public final String attribution;
    /** The coordinates actually sent to the provider, after rounding. */
    public final double latitude;
    public final double longitude;
    /** When this response came off the network (UTC millis). */
    public final long fetchedAt;
    public final List<Reading> current;
    public final List<SeriesEntry> series;

    public Snapshot(String sourceId, String sourceName, String attribution,
            double latitude, double longitude, long fetchedAt,
            List<Reading> current, List<SeriesEntry> series) {
        this.sourceId = sourceId;
        this.sourceName = sourceName;
        this.attribution = attribution;
        this.latitude = latitude;
        this.longitude = longitude;
        this.fetchedAt = fetchedAt;
        this.current = Collections.unmodifiableList(current);
        this.series = Collections.unmodifiableList(series);
    }

    public long ageMillis(long now) {
        return Math.max(0L, now - fetchedAt);
    }

    /** "just now", "12 min old", "3 h 40 min old" — always shown next to the values. */
    public static String describeAge(long ageMillis) {
        final long minutes = ageMillis / 60000L;
        if (minutes < 1)
            return "just now";
        if (minutes < 60)
            return minutes + " min old";
        final long hours = minutes / 60;
        final long rest = minutes % 60;
        if (hours < 24)
            return rest == 0 ? hours + " h old" : hours + " h " + rest + " min old";
        final long days = hours / 24;
        return days + (days == 1 ? " day old" : " days old");
    }
}

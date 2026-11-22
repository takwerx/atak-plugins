package com.atakmap.android.atmosphere.model;

import java.util.Collections;
import java.util.List;

/** One step of a forecast series — a time and the readings valid at it. */
public final class SeriesEntry {

    /** UTC milliseconds, or 0 when the provider's time could not be read. */
    public final long timeMillis;
    /** The provider's own time string, kept for display when parsing failed. */
    public final String timeRaw;
    public final List<Reading> readings;

    public SeriesEntry(long timeMillis, String timeRaw, List<Reading> readings) {
        this.timeMillis = timeMillis;
        this.timeRaw = timeRaw;
        this.readings = Collections.unmodifiableList(readings);
    }

    public Reading reading(String key) {
        for (Reading r : readings) {
            if (r.key.equals(key))
                return r;
        }
        return null;
    }
}

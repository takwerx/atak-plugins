package com.atakmap.android.signaldf.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * What one read of one feed produced: the bearings in it, and what was thrown
 * away getting them.
 *
 * <p>The discard count is not bookkeeping. The Kraken App CSV is rewritten in
 * place every frame -- {@code seek(0)}, write, truncate -- so a poll that lands
 * mid-write reads a torn line, and the right response is to skip it rather than
 * fail the whole read. A parser that does that silently is indistinguishable
 * from a radio that has stopped transmitting, which is the failure this plugin
 * exists to not have. So the pane can show it.
 */
public final class FeedFrame {

    public final FeedSource source;
    public final List<Bearing> bearings;

    /** Lines or records that were read and rejected as malformed. */
    public final int discarded;

    /** Why, in words, if anything was discarded or noteworthy. May be empty. */
    public final String note;

    public FeedFrame(FeedSource source, List<Bearing> bearings, int discarded, String note) {
        this.source = source;
        this.bearings = Collections.unmodifiableList(
                new ArrayList<>(bearings == null ? Collections.<Bearing> emptyList() : bearings));
        this.discarded = discarded;
        this.note = note == null ? "" : note;
    }

    public static FeedFrame empty(FeedSource source, String note) {
        return new FeedFrame(source, Collections.<Bearing> emptyList(), 0, note);
    }

    public boolean isEmpty() {
        return bearings.isEmpty();
    }

    @Override
    public String toString() {
        return String.format(Locale.US, "FeedFrame[%s n=%d discarded=%d %s]",
                source.label(), bearings.size(), discarded, note);
    }
}

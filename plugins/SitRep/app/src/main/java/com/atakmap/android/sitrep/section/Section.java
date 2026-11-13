package com.atakmap.android.sitrep.section;

import com.atakmap.android.sitrep.Area;

import java.util.ArrayList;
import java.util.List;

/**
 * One line of the report and the one source behind it. One class per source (PLAN
 * section 3), so an agency moving a service is a change to one file.
 *
 * <p>{@link #fetch} runs on a report worker and may take as long as its server does;
 * it throws on any failure and the report says {@code Not available} for this
 * section alone.
 */
public abstract class Section {

    /** Stable id, the key a saved report keeps this section's answer under. */
    public final String key;
    /** The name at the head of the row: {@code Structures}. */
    public final String name;
    /**
     * Live sections (alerts, weather, fires) change by the hour: a saved one is shown
     * with its age and re-run by Refresh. Reference sections (people, structures,
     * history) are kept as fetched.
     */
    public final boolean live;
    /** Where the numbers come from, for the line behind the tap. No source names on the head. */
    public final String source;

    protected Section(String key, String name, boolean live, String source) {
        this.key = key;
        this.name = name;
        this.live = live;
        this.source = source;
    }

    public abstract Result fetch(Area area) throws Exception;

    /** What a section found. */
    public static final class Result {
        /** The head's value, read after the name: {@code 2,018; 1,925 homes}. */
        public final String value;
        /** A few words for the report's row in the list, or null: {@code 1,925 homes}. */
        public final String summary;
        /** The detail lines under the head. */
        public final List<String> lines = new ArrayList<>();
        /**
         * Set when the source does not cover this place ({@code Not published for this
         * state}): a different fact from "none found".
         */
        public boolean notPublishedHere;

        public Result(String value, String summary) {
            this.value = value;
            this.summary = summary;
        }

        public Result line(String s) {
            lines.add(s);
            return this;
        }
    }
}

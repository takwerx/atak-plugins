package com.atakmap.android.atmosphere.overlay;

import com.atakmap.android.atmosphere.compat.MapCompat;
import com.atakmap.coremap.maps.coords.GeoPoint;

/**
 * Where a layer's radius is measured from: the pane's point, chosen in the row at its
 * top (My position, Map center, a picked point, a favorite). The station, gauge and
 * buoy pages each carried a My position | Map center row of their own beside it, and
 * the operator wanted the room for the list (2026-10-16: "why do we have map center
 * and my position when it should be driven by that top menu right?").
 */
public interface Origin {

    /** The point now; null when there is none yet (no fix, nothing picked). */
    GeoPoint point();

    /** Whether the point is the middle of the map, which a layer follows as it moves. */
    boolean followsMap();

    /** Whether the point is the operator's own position. */
    boolean self();

    /** The point as a sentence ends: "you", "the map center", "the picked point", "Denver". */
    String words();

    /** The kind of point for a log line, never a favorite's name. */
    default String logWords() {
        return self() ? "me" : followsMap() ? "the map" : "a set point";
    }

    /** Until the pane hands one over: the middle of the map. */
    Origin MAP_CENTER = new Origin() {
        @Override
        public GeoPoint point() {
            return MapCompat.mapCenter();
        }

        @Override
        public boolean followsMap() {
            return true;
        }

        @Override
        public boolean self() {
            return false;
        }

        @Override
        public String words() {
            return "the map center";
        }
    };
}

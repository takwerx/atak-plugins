package com.atakmap.android.firecast;

import java.util.List;

/**
 * One feed of heat detections.
 *
 * <p>Everything a feed knows is turned into {@link HeatDetection} here, at the edge, so
 * nothing downstream has to know which satellite it is looking at. That is the whole
 * point of the interface: Earth Fire Alliance's FireSat constellation is flying and
 * more of it is coming, and when a public endpoint exists it becomes one new class
 * implementing this - not a change to the map, the list, the filters or the model.
 *
 * <p>A source is asked for a box and a time window because these feeds are global and
 * an operator's screen is not: VIIRS held 22,371 detections worldwide on 2026-09-11 and
 * 1,314 of them inside California.
 */
public interface DetectionSource {

    /** Stable key, used in preferences and in the feature set name. */
    String id();

    /** What the filter button says. */
    String title();

    /** One line under the title: what the feed is. */
    String summary();

    /** Whether it is on the first time the plugin runs. */
    boolean defaultOn();

    /**
     * Every detection in the box no older than the window.
     *
     * @param box    the area to ask about, never null
     * @param sinceMillis  epoch ms cutoff; detections older than this are not wanted
     * @param max    stop after this many; 0 for no limit
     * @return what the feed served, oldest or newest in no particular order
     */
    List<HeatDetection> fetch(Esri.Box box, long sinceMillis, int max) throws Exception;
    /**
     * One record, as the service served it, as a detection; null when it cannot be one.
     * The same reader serves a fetch and the file the last fetch was saved to, so what
     * comes back after a restart with no network is what was on the map before it.
     */
    HeatDetection read(org.json.JSONObject attributes,
            com.atakmap.map.layer.feature.geometry.Geometry geometry, org.json.JSONObject esriGeometry);

    /**
     * How many the feed holds for that box and window, without fetching them. Used to
     * tell the operator when what is drawn is not all there is - a filter that says
     * "VIIRS (1,013)" has told them what it costs before they use it.
     */
    int count(Esri.Box box, long sinceMillis) throws Exception;
}

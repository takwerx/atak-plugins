package com.atakmap.android.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.atmosphere.wind.WindRegion;
import com.atakmap.android.atmosphere.wind.WindRegion.Box;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * The wind layer's region and cache rules (ops #1051). With Wind on and the map locked on
 * self, the log showed a refetch about once a second, alternating between two regions:
 * showing a grid replaced the region the cache key is made from by the grid's own extent,
 * so the next look at the same hour could never find the grid it had just fetched.
 */
public class WindRegionTest {

    private static final double MAX_SPAN = 30;

    /** The view, padded by half its span, the way WindOverlay chooses a region. */
    private static Box padded(Box v) {
        final double px = v.spanLon() / 2, py = v.spanLat() / 2;
        return new Box(v.west - px, v.south - py, v.east + px, v.north + py);
    }

    /** What the grib filter sends back for a region: its box grown by a cell, as a grid extent. */
    private static Box gridFor(Box asked) {
        return new Box(asked.west - 0.05, asked.south - 0.05, asked.east + 0.05, asked.north + 0.05);
    }

    private static String key(WindRegion area) {
        return WindRegion.key("HRRR", "AGL_10", 1795320000000L, 2, area.asked());
    }

    /** One settled map move, the way WindOverlay.ensureRegion and showHour run it. */
    private static int settle(WindRegion area, Map<String, Box> cache, Box view) {
        if (area.needsRegion(false, view, MAX_SPAN)) {
            area.choose(padded(view));
            cache.clear();
        }
        final String k = key(area);
        final Box hit = cache.get(k);
        if (hit != null) {
            area.shown(hit);
            return 0;
        }
        final Box grid = gridFor(area.asked());
        cache.put(k, grid);
        area.shown(grid);
        return 1;
    }

    @Test
    public void aGridThatIsShownLeavesTheCacheKeyAlone() {
        final WindRegion area = new WindRegion();
        area.choose(new Box(-123.51, 38.98, -122.79, 39.53));
        final String before = key(area);
        area.shown(new Box(-123.77, 38.78, -122.56, 39.72));
        assertEquals(before, key(area));
    }

    @Test
    public void tenSettledMovesWithTheMapStillFetchOnce() {
        final WindRegion area = new WindRegion();
        final Map<String, Box> cache = new HashMap<>();
        final Box view = new Box(-123.30, 39.10, -123.00, 39.40);
        int fetches = 0;
        for (int i = 0; i < 10; i++)
            fetches += settle(area, cache, view);
        assertEquals("one request for the first look, then the cache answers", 1, fetches);
    }

    @Test
    public void aSmallPanInsideTheRegionIsServedFromTheCache() {
        final WindRegion area = new WindRegion();
        final Map<String, Box> cache = new HashMap<>();
        int fetches = settle(area, cache, new Box(-123.30, 39.10, -123.00, 39.40));
        fetches += settle(area, cache, new Box(-123.28, 39.11, -122.98, 39.41));
        fetches += settle(area, cache, new Box(-123.32, 39.09, -123.02, 39.39));
        assertEquals(1, fetches);
    }

    @Test
    public void aViewThatLeavesWhatTheGridCoversAsksAgain() {
        final WindRegion area = new WindRegion();
        final Map<String, Box> cache = new HashMap<>();
        settle(area, cache, new Box(-123.30, 39.10, -123.00, 39.40));
        // Panned a full screen away: outside the region and the grid.
        assertTrue(area.needsRegion(false, new Box(-122.50, 39.10, -122.20, 39.40), MAX_SPAN));
        // Zoomed far in or far out: asks again.
        assertTrue(area.needsRegion(false, new Box(-123.16, 39.24, -123.14, 39.26), MAX_SPAN));
        assertTrue(area.needsRegion(false, new Box(-124.30, 38.50, -122.00, 40.00), MAX_SPAN));
        // A different model always asks.
        assertTrue(area.needsRegion(true, new Box(-123.30, 39.10, -123.00, 39.40), MAX_SPAN));
    }

    @Test
    public void aViewWiderThanTheModelsBoxAsksOnlyWhenItsCenterLeaves() {
        final WindRegion area = new WindRegion();
        area.choose(new Box(-130, 30, -100, 50));
        area.shown(new Box(-130, 30, -100, 50));
        // Wider than the 30 degrees asked for, but still centered inside: no new request.
        assertFalse(area.needsRegion(false, new Box(-132, 31, -98, 49), MAX_SPAN));
        // Center now outside the region: asks.
        assertTrue(area.needsRegion(false, new Box(-110, 31, -70, 49), MAX_SPAN));
    }

    @Test
    public void nothingChosenYetAsks() {
        final WindRegion area = new WindRegion();
        assertTrue(area.needsRegion(false, new Box(-123.30, 39.10, -123.00, 39.40), MAX_SPAN));
    }
}

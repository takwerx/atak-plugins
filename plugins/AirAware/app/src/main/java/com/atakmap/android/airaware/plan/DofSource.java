package com.atakmap.android.airaware.plan;

import android.os.Handler;
import android.os.Looper;

import com.atakmap.android.airaware.Obstacle;
import com.atakmap.android.airaware.ObstacleTiles;
import com.atakmap.android.airaware.TilePack;
import com.atakmap.android.airaware.terrain.Extent;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoCalculations;
import com.atakmap.coremap.maps.coords.GeoPoint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The obstacles inside a plan's area, from the tiles on the phone.
 *
 * <p>The contract is UAS Flight Plan's unchanged -- {@link #fetch} on a worker, the
 * callback on the main thread, nearest first -- so {@link ObstacleManager} and the pane
 * above it are exactly as they were. What is behind it is not. There it asked the FAA's
 * ArcGIS service for each circle; here AirAware already holds the whole Digital
 * Obstacle File as two-degree tiles, 657,732 of them, so the same question is a read
 * from disk.
 *
 * <p>That is not tidiness. The service's quota is 6,000 request units a minute shared
 * with every other caller in the world, and AirAware's airspace layer had already spent
 * it twice in one afternoon; a planner that went back to it would be the one part of the
 * plugin that stops working in a fire camp. It also means a plan can be worked out with
 * no signal, which is the rule for anything the operator set up.
 */
public final class DofSource {

    private static final String TAG = "AirAwareObstacles";

    /**
     * Past this the list is capped and the pane says the circle holds more. Kept from
     * the original: a three-mile circle over a city can hold thousands.
     */
    static final int MAX_ROWS = 6000;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface Callback {
        /**
         * @param inCircle nearest first
         * @param capped true when the read stopped at {@link #MAX_ROWS} and the circle
         *            may hold more than was asked for
         */
        void onLoaded(List<Obstacle> inCircle, boolean capped);

        void onFailed(String reason);
    }

    private DofSource() {
    }

    /** Reads on a worker; the callback lands on the main thread. */
    public static void fetch(final Extent extent, final Callback cb) {
        WORKER.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<Obstacle> found = read(extent);
                    final boolean capped = found.size() > MAX_ROWS;
                    final List<Obstacle> out = capped
                            ? new ArrayList<>(found.subList(0, MAX_ROWS)) : found;
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            cb.onLoaded(out, capped);
                        }
                    });
                } catch (final Exception e) {
                    Log.w(TAG, "reading the obstacle tiles failed", e);
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            cb.onFailed("Obstacles for here are not downloaded yet.");
                        }
                    });
                }
            }
        });
    }

    private static List<Obstacle> read(Extent extent) throws Exception {
        final TilePack pack = ObstacleTiles.pack();
        final TilePack.Index index = pack.index();
        final List<String> keys = TilePack.keysFor(index,
                extent.bounds.getSouth(), extent.bounds.getWest(),
                extent.bounds.getNorth(), extent.bounds.getEast());
        final GeoPoint center = extent.center;
        final List<Obstacle> out = new ArrayList<>();
        Exception failure = null;
        for (String key : keys) {
            try {
                for (Obstacle o : ObstacleTiles.tile(pack, key)) {
                    // The area, not its bounding box: a circle query that returned the
                    // corners of its own square would put towers outside the plan into
                    // the list the pilot is reading.
                    if (!extent.contains(o.lat, o.lon))
                        continue;
                    o.distanceM = GeoCalculations.distanceTo(center,
                            new GeoPoint(o.lat, o.lon));
                    o.bearingDeg = GeoCalculations.bearingTo(center,
                            new GeoPoint(o.lat, o.lon));
                    out.add(o);
                }
            } catch (Exception e) {
                // One tile short is a thin answer; none at all is a failure.
                failure = e;
            }
        }
        if (out.isEmpty() && failure != null)
            throw failure;
        Collections.sort(out, new Comparator<Obstacle>() {
            @Override
            public int compare(Obstacle a, Obstacle b) {
                return Double.compare(a.distanceM, b.distanceM);
            }
        });
        return out;
    }
}

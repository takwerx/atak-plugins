package com.atakmap.android.airaware;

import com.atakmap.coremap.filesystem.FileSystemUtils;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * The airspace tiles, which are a {@link TilePack} plus what a tile means.
 *
 * <p>Shelves come back parsed by {@link AirspaceFeed#parse}: the tiles carry the FAA
 * service's own shape, so one reader covers a tile and a live answer and the two cannot
 * disagree about what a ring means.
 */
public final class AirspaceTiles {

    static final String BASE = "https://mapdepot.takwerx.org/airaware/airspace/";

    /**
     * Shapes too big to tile, loaded wherever the operator is.
     *
     * <p>Class A is one polygon from 18,000 ft to FL600 over the whole contiguous United
     * States. Tiling it wrote its outline into 390 tiles and made a three-degree test
     * slice 39.9 MB; in its own file the same slice is 0.2 MB.
     */
    private static final String WIDE = "wide.json.gz";

    private AirspaceTiles() {
    }

    public static TilePack pack() {
        return new TilePack(BASE, "airspace.json",
                new File(FileSystemUtils.getItem("tools/airaware"), "airspace"));
    }

    /** The wide shapes, which belong everywhere. */
    public static List<Airspace> wide(TilePack pack) throws IOException {
        return AirspaceFeed.parse(pack.file(WIDE));
    }

    public static List<Airspace> tile(TilePack pack, String key) throws IOException {
        return AirspaceFeed.parse(pack.tile(key));
    }
}

package com.atakmap.android.airaware;

import com.atakmap.coremap.filesystem.FileSystemUtils;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * The National Security UAS Flight Restrictions as one file we publish.
 *
 * <p>Not tiles: 2,300 facility outlines for the whole country are a few hundred KB
 * gzipped, less than one airspace tile, and a crew driving between incidents wants all
 * of it on the phone. Built from the FAA's three ArcGIS layers by
 * {@code tools/build_nsufr_pack.py} and republished weekly; the manifest carries the
 * pack's hash, so the file is fetched again only when it changed, and with no network
 * the copy on disk is what draws.
 */
public final class NsufrPack {

    static final String BASE = "https://mapdepot.takwerx.org/airaware/nsufr/";
    static final String FILE = "nsufr.json.gz";

    private NsufrPack() {
    }

    public static TilePack pack() {
        return new TilePack(BASE, "nsufr.json",
                new File(FileSystemUtils.getItem("tools/airaware"), "nsufr"));
    }

    /** Every restriction in the country. Worker thread only. */
    public static List<Nsufr> load(TilePack pack, TilePack.Index index) throws IOException {
        return Nsufr.parse(pack.file(FILE, index == null ? null : index.sha.get(FILE)));
    }
}


package com.atakmap.android.plss;

import android.graphics.Color;
import android.os.Environment;

import com.atakmap.coremap.log.Log;
import com.atakmap.map.layer.AbstractLayer;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The PLSS overlay's model object, mirroring GridLinesOverlay.
 *
 * Owns the packed store; the renderer asks it for geometry by bounding box. See
 * PLAN-PLSS-v0.1.md section 5.2.
 *
 * v0.1 reads a sideloaded per-state pack. The shipping plugin downloads a
 * national pack once on first run (section 5.4) -- the data is far too large to
 * ride inside a tak.gov source submission.
 */
public class PlssOverlay extends AbstractLayer {

    public static final String TAG = "PlssOverlay";

    /** Where a sideloaded pack is expected until managed download exists. */
    private static final String PACK_DIR = "atak/PLSS";

    /** Notified when the line colour changes, so the renderer can pick it up. */
    public interface OnPlssColorChangedListener {
        void onPlssColorChanged(PlssOverlay overlay);
    }

    // Separate colours so the two tiers read apart on the map: the township
    // grid is the coarse frame, sections the fine grid inside it.
    private int sectionColor = Color.YELLOW;
    private int townshipColor = Color.RED;

    /**
     * Label text, chosen independently of the lines and of each other. Tying
     * label colour to line colour made white lines draw white text; tying the
     * two tiers together left no way to tell a township label from a section
     * number at a glance.
     */
    private int sectionLabelColor = Color.WHITE;
    private int townshipLabelColor = Color.WHITE;

    private final ConcurrentLinkedQueue<OnPlssColorChangedListener> colorListeners = new ConcurrentLinkedQueue<>();

    /**
     * Every installed pack, opened at once. The plugin ships bare and the
     * operator downloads the states they work in, so the set changes at runtime
     * -- copy-on-write keeps the renderer's read path lock-free while a download
     * or delete swaps the contents underneath it.
     */
    private final CopyOnWriteArrayList<PlssStore> stores = new CopyOnWriteArrayList<>();

    public PlssOverlay(final String name) {
        super(name);
    }

    /** Directory holding the installed packs. */
    public static File packDir() {
        return new File(Environment.getExternalStorageDirectory(), PACK_DIR);
    }

    /** Two-letter codes of every installed pack, sorted. */
    public static List<String> installedStates() {
        final List<String> out = new ArrayList<>();
        final File[] files = packDir().listFiles();
        if (files == null)
            return out;

        for (File f : files) {
            final String n = f.getName();
            if (n.startsWith("plss_") && n.endsWith(".sqlite"))
                out.add(n.substring(5, n.length() - 7));
        }

        java.util.Collections.sort(out);
        return out;
    }

    /**
     * Opens every pack in the pack directory, replacing whatever was open.
     * Returns how many opened -- zero is the state a fresh install is in, not an
     * error.
     */
    public int openPacks() {
        closePacks();

        final File[] files = packDir().listFiles();
        if (files == null) {
            Log.d(TAG, "no pack directory at " + packDir());
            return 0;
        }

        Arrays.sort(files);
        for (File f : files) {
            final String n = f.getName();
            if (!n.startsWith("plss_") || !n.endsWith(".sqlite"))
                continue;

            final PlssStore s = PlssStore.open(f);
            if (s != null)
                stores.add(s);
        }

        Log.d(TAG, "opened " + stores.size() + " PLSS pack(s)");
        return stores.size();
    }

    public void closePacks() {
        for (PlssStore s : stores)
            s.close();
        stores.clear();
    }

    public List<PlssStore> getStores() {
        return stores;
    }

    public boolean hasData() {
        return !stores.isEmpty();
    }

    /** Meridians across every installed pack, deduplicated and sorted. */
    public List<String> meridians() {
        final java.util.TreeSet<String> set = new java.util.TreeSet<>();
        for (PlssStore s : stores)
            set.addAll(s.meridians());
        return new ArrayList<>(set);
    }

    /**
     * Finds a township across the installed packs. Returns {west, south, east,
     * north} or null.
     */
    public double[] findTownship(String meridian, String label) {
        for (PlssStore s : stores) {
            final double[] box = s.findTownship(meridian, label);
            if (box != null)
                return box;
        }
        return null;
    }

    public synchronized int getSectionColor() {
        return sectionColor;
    }

    public void setSectionColor(final int color) {
        synchronized (this) {
            if (this.sectionColor == color)
                return;

            this.sectionColor = color;
        }

        dispatchColorChanged();
    }

    public synchronized int getTownshipColor() {
        return townshipColor;
    }

    public void setTownshipColor(final int color) {
        synchronized (this) {
            if (this.townshipColor == color)
                return;

            this.townshipColor = color;
        }

        dispatchColorChanged();
    }

    public synchronized int getSectionLabelColor() {
        return sectionLabelColor;
    }

    public void setSectionLabelColor(final int color) {
        synchronized (this) {
            if (this.sectionLabelColor == color)
                return;

            this.sectionLabelColor = color;
        }

        dispatchColorChanged();
    }

    public synchronized int getTownshipLabelColor() {
        return townshipLabelColor;
    }

    public void setTownshipLabelColor(final int color) {
        synchronized (this) {
            if (this.townshipLabelColor == color)
                return;

            this.townshipLabelColor = color;
        }

        dispatchColorChanged();
    }

    /** Dispatched outside the lock -- listeners call back into the getters. */
    private void dispatchColorChanged() {
        for (OnPlssColorChangedListener l : colorListeners) {
            l.onPlssColorChanged(this);
        }
    }

    public void addOnPlssColorChangedListener(OnPlssColorChangedListener l) {
        if (l != null && !colorListeners.contains(l))
            colorListeners.add(l);
    }

    public void removeOnPlssColorChangedListener(OnPlssColorChangedListener l) {
        colorListeners.remove(l);
    }
}

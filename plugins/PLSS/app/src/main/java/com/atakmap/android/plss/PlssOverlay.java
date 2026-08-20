
package com.atakmap.android.plss;

import android.graphics.Color;
import android.os.Environment;

import com.atakmap.coremap.log.Log;
import com.atakmap.map.layer.AbstractLayer;

import java.io.File;
import java.util.concurrent.ConcurrentLinkedQueue;

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

    private final ConcurrentLinkedQueue<OnPlssColorChangedListener> colorListeners = new ConcurrentLinkedQueue<>();

    private PlssStore store;

    public PlssOverlay(final String name) {
        super(name);
    }

    /**
     * Opens the sideloaded pack for a state. Returns false when there is none,
     * which is not an error -- it is the state a fresh install is in.
     */
    public boolean openPack(String state) {
        closePack();

        final File path = new File(Environment.getExternalStorageDirectory(),
                PACK_DIR + "/plss_" + state + ".sqlite");

        store = PlssStore.open(path);
        if (store == null) {
            Log.w(TAG, "no PLSS pack for " + state + " at " + path);
            return false;
        }

        return true;
    }

    public void closePack() {
        if (store != null) {
            store.close();
            store = null;
        }
    }

    public PlssStore getStore() {
        return store;
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

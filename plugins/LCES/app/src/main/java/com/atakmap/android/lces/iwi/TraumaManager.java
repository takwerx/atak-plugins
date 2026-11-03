package com.atakmap.android.lces.iwi;


import android.content.Context;
import android.content.SharedPreferences;

import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;

import java.io.IOException;
import java.text.NumberFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import com.atakmap.android.lces.lessons.Http;

/**
 * Owns the trauma center pack for the plugin's life: the copy in memory, the one worker
 * that reads and fetches it, and the cruise speed the estimate is made at.
 *
 * <p>Lives in the plugin, never in a Tool: ATAK ends tools whenever something else
 * opens, and a download that dies with a pane close would leave the line empty until
 * the next restart. Nothing here runs on the main thread except the callbacks it posts.
 */
public final class TraumaManager {

    private static final String TAG = "LCES-IWI-Trauma";

    static final String PREFS = "lces_iwi";
    static final String PREF_CRUISE_KT = "iwi.cruiseKt";
    /** The cautious end of what a HEMS airframe cruises, so the estimate errs long. */
    public static final int DEFAULT_CRUISE_KT = 120;
    public static final int[] CRUISE_PRESETS_KT = {100, 110, 120, 130, 140, 150};

    /** What the pane hears. Always on the main thread. */
    public interface Listener {
        /** The centers in memory changed, or the attempt to change them finished. */
        void onPackChanged();

        /** A word for the status line about a download that just ran. */
        void onStatus(String message);
    }

    private final MapView mapView;
    private final SharedPreferences prefs;
    private final TraumaPack pack;
    private final ExecutorService worker;
    private volatile List<TraumaCenter> centers;
    private volatile TraumaPack.Index index = new TraumaPack.Index();
    private volatile boolean busy;
    private Listener listener;

    public TraumaManager(MapView mapView) {
        this.mapView = mapView;
        this.prefs = mapView.getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.pack = new TraumaPack();
        this.worker = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                final Thread t = new Thread(r, "LCES-IWI-trauma-pack");
                t.setDaemon(true);
                return t;
            }
        });
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    /** On plugin start: what is on disk first, then a refresh if the depot has newer. */
    public void start() {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                // Disk only, so the line is right the moment the pane opens.
                index = pack.index(false);
                try {
                    centers = pack.load(null, false);
                } catch (IOException e) {
                    Log.d(TAG, "no pack on disk yet: " + e.getMessage());
                }
                post(null);
                // Then the network, once, in the background.
                refresh(false);
            }
        });
    }

    /** The Settings button: ask the depot now and say how it went. */
    public void download() {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                refresh(true);
            }
        });
    }

    /** Worker thread. */
    private void refresh(boolean report) {
        busy = true;
        try {
            final TraumaPack.Index fresh = pack.index(true);
            if (!fresh.isEmpty())
                index = fresh;
            final List<TraumaCenter> loaded = pack.load(
                    fresh.isEmpty() ? null : fresh.sha, true);
            centers = loaded;
            post(report ? "Trauma center data updated: " + describe(index) : null);
        } catch (IOException e) {
            Log.d(TAG, "refresh: " + e.getMessage());
            post(report ? "Could not download trauma center data: "
                    + Http.describe(e) : null);
        } finally {
            busy = false;
        }
    }

    private void post(final String status) {
        mapView.post(new Runnable() {
            @Override
            public void run() {
                final Listener l = listener;
                if (l == null)
                    return;
                l.onPackChanged();
                if (status != null)
                    l.onStatus(status);
            }
        });
    }

    public void stop() {
        listener = null;
        worker.shutdownNow();
    }

    /** The centers in memory, or null when there is no pack on this device. */
    public List<TraumaCenter> centers() {
        return centers;
    }

    public TraumaPack.Index index() {
        return index;
    }

    public boolean busy() {
        return busy;
    }

    public int cruiseKt() {
        final int kt = prefs.getInt(PREF_CRUISE_KT, DEFAULT_CRUISE_KT);
        return kt > 0 && kt < 400 ? kt : DEFAULT_CRUISE_KT;
    }

    public void setCruiseKt(int kt) {
        prefs.edit().putInt(PREF_CRUISE_KT, kt).apply();
    }

    public SharedPreferences prefs() {
        return prefs;
    }

    /** "1,800 centers, helipads as of Oct 1, 2026, hospitals 2020", for Settings. */
    public static String describe(TraumaPack.Index index) {
        if (index == null || index.isEmpty())
            return "No trauma center data on this device";
        final StringBuilder b = new StringBuilder();
        b.append(NumberFormat.getIntegerInstance(Locale.US).format(index.count))
                .append(" trauma centers");
        final String cycle = date(index.cycle);
        if (!cycle.isEmpty())
            b.append(", helipads as of ").append(cycle);
        if (index.hospitalsDate.length() >= 4)
            b.append(", hospital list ").append(index.hospitalsDate.substring(0, 4));
        return b.toString();
    }

    /** "Oct 1, 2026" from "2026-10-01", or "" when it does not parse. */
    static String date(String iso) {
        if (iso == null || iso.length() < 10)
            return "";
        try {
            final Date d = new SimpleDateFormat("yyyy-MM-dd", Locale.US)
                    .parse(iso.substring(0, 10));
            return d == null ? "" : new SimpleDateFormat("MMM d, yyyy", Locale.US).format(d);
        } catch (ParseException e) {
            return "";
        }
    }
}

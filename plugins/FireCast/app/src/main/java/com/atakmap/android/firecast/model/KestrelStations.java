package com.atakmap.android.firecast.model;

import com.atakmap.android.cot.detail.CotDetailHandler;
import com.atakmap.android.cot.detail.CotDetailManager;
import com.atakmap.android.maps.MapItem;
import com.atakmap.comms.CommsMapComponent;
import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.log.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Kestrel Wx stations (mighkel/ATAK-Plugin-KestrelWx), heard as CoT on this phone with
 * or without that plugin installed: a station is an a-f-G-E-S event, uid kestrel-wx-*,
 * with a {@code __kestrelWeather} detail. Its attributes, read off Kestrel Wx 0.3:
 * windSpeed m/s, windDir degrees, temp Celsius, rh percent, and a stationName. The
 * reading is held here, newest per station, for the weather dialog; nothing is drawn
 * (Kestrel Wx draws its own stations, and without it ATAK draws the marker).
 */
public final class KestrelStations extends CotDetailHandler {

    private static final String TAG = "FireCast";
    static final String ELEMENT = "__kestrelWeather";
    /** Stations held at most; an incident's worth. */
    private static final int MAX = 50;

    /** One station's last reading. */
    public static final class Station {
        public final String uid, name;
        public final double lat, lon;
        public final long at;
        public final double windMph, windFromDeg, tempF, rh;

        Station(String uid, String name, double lat, double lon, long at, double windMph, double windFromDeg,
                double tempF, double rh) {
            this.uid = uid;
            this.name = name;
            this.lat = lat;
            this.lon = lon;
            this.at = at;
            this.windMph = windMph;
            this.windFromDeg = windFromDeg;
            this.tempF = tempF;
            this.rh = rh;
        }
    }

    private static final Map<String, Station> STATIONS = new LinkedHashMap<>();
    private static KestrelStations registered;

    private KestrelStations() {
        super(ELEMENT);
    }

    public static synchronized void start() {
        if (registered != null)
            return;
        registered = new KestrelStations();
        CotDetailManager.getInstance().registerHandler(registered);
    }

    public static synchronized void stop() {
        if (registered == null)
            return;
        try {
            CotDetailManager.getInstance().unregisterHandler(registered);
        } catch (Exception ignored) {
        }
        registered = null;
    }

    /** The stations heard, newest first, each its latest reading. */
    public static synchronized List<Station> stations() {
        final List<Station> out = new ArrayList<>(STATIONS.values());
        Collections.sort(out, new Comparator<Station>() {
            @Override
            public int compare(Station a, Station b) {
                return Long.compare(b.at, a.at);
            }
        });
        return out;
    }

    @Override
    public CommsMapComponent.ImportResult toItemMetadata(MapItem item, CotEvent event, CotDetail detail) {
        try {
            final Station s = read(event, detail);
            if (s != null)
                synchronized (KestrelStations.class) {
                    STATIONS.remove(s.uid);
                    STATIONS.put(s.uid, s);
                    while (STATIONS.size() > MAX)
                        STATIONS.remove(STATIONS.keySet().iterator().next());
                }
        } catch (Exception e) {
            Log.w(TAG, "Kestrel station unreadable: " + e);
        }
        // Not ours to import: Kestrel Wx, when it is here, handles the marker.
        return CommsMapComponent.ImportResult.IGNORE;
    }

    @Override
    public boolean toCotDetail(MapItem item, CotEvent event, CotDetail root) {
        return false;
    }

    /** A reading off the event, every number checked; null when it is not a usable one. */
    static Station read(CotEvent event, CotDetail d) {
        if (event == null || d == null)
            return null;
        final String uid = event.getUID();
        if (uid == null || uid.length() > 100)
            return null;
        final double lat = event.getGeoPoint() == null ? Double.NaN : event.getGeoPoint().getLatitude();
        final double lon = event.getGeoPoint() == null ? Double.NaN : event.getGeoPoint().getLongitude();
        if (!(Math.abs(lat) <= 90) || !(Math.abs(lon) <= 180))
            return null;
        final double ms = num(d.getAttribute("windSpeed")), dir = num(d.getAttribute("windDir"));
        final double c = num(d.getAttribute("temp")), rh = num(d.getAttribute("rh"));
        // m/s to mph; Celsius to Fahrenheit (Kestrel Wx sends SI, its labels convert).
        final double mph = ms * 2.2369363, f = c * 9 / 5 + 32;
        if (!(mph >= 0 && mph <= 150) || !(dir >= 0 && dir <= 360))
            return null;
        final long at = event.getTime() == null ? 0 : event.getTime().getMilliseconds();
        String name = d.getAttribute("stationName");
        if (name == null || name.trim().isEmpty()) {
            final CotDetail detail = event.getDetail(), contact = detail == null ? null : detail.getFirstChildByName(0, "contact");
            name = contact == null ? null : contact.getAttribute("callsign");
        }
        name = name == null ? "Kestrel" : name.replaceAll("[^\\p{L}\\p{N} ._'()#/-]", "").trim();
        if (name.isEmpty())
            name = "Kestrel";
        if (name.length() > 40)
            name = name.substring(0, 40);
        return new Station(uid, name, lat, lon, at, mph, dir,
                f >= -40 && f <= 130 ? f : Double.NaN, rh >= 1 && rh <= 100 ? rh : Double.NaN);
    }

    private static double num(String s) {
        if (s == null || s.length() > 20)
            return Double.NaN;
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}

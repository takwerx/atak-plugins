package com.atakmap.android.comms.map;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import com.atakmap.android.comms.data.Nets;
import com.atakmap.android.comms.model.Catalog.Site;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.android.menu.PluginMenuParser;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.assets.Icon;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.map.AtakMapView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The sites on the map: one marker per site, a zoom gate, and ATAK's own viewshed
 * drawn from the operator's position.
 *
 * <h3>Viewshed, and only from the operator</h3>
 *
 * ATAK's {@code com.atakmap.android.elev.ViewShedReceiver} takes a local broadcast:
 * {@code SHOW_VIEWSHED} with the marker's {@code uid}, a {@code point}, a
 * {@code radius} in meters, and {@code circle}. Read out of main.jar for 5.6, 5.7 and
 * 5.8 (identical constants): the layer's observer altitude is the point's
 * {@code getAltitude()} <em>added to the terrain grid at the center</em>
 * ({@code GLViewShed2.startAlt = point.getAltitude() + elevGrid[center]}), and
 * ATAK's own tool builds that point with {@code AltitudeReference.AGL} and its
 * "height above" preference. So the antenna height above ground is handed over as
 * an AGL altitude and nothing here looks up terrain; the DTED on the device is the
 * ground, as it is for ATAK's tool.
 *
 * <p>There is no viewshed here at all, from a site or from the operator. Both were
 * tried and both were removed: drawn from a repeater a viewshed reads as a coverage
 * map and is not one, and drawn from the operator it changed its answer about a
 * five-mile mountain depending on the range the operator had picked, because ATAK
 * samples it on a fixed grid however far it reaches. {@link Reach} answers the
 * question instead, from the terrain profile and a handheld's link budget, and it
 * gives the same answer at any range.
 *
 * <p>Markers are only ever created and mutated in place
 * from under ATAK.
 */
public final class SiteLayer {

    private static final String TAG = "CommsLayer";
    private static final String GROUP = "Comms";
    private static final String UID_PREFIX = "comms.";

    public static final String ACTION_DETAILS = "com.atakmap.android.comms.SITE_DETAILS";

    /** As far out as sites are ever checked against the operator's position. */
    public static final double MAX_CHECK_M = 100000;
    /** When the catalog does not say how tall the antenna is. */
    public static final double DEFAULT_ANTENNA_M = 10;
    /**
     * Labels draw from this resolution inward, in meters per pixel.
     *
     * <p>ATAK's own default is 10, which is several pinches closer than these markers
     * appear at, so a screen of repeaters was a screen of anonymous diamonds. A fixed
     * number rather than the marker threshold: that can be "always draw them", and
     * handing the renderer a number that large is not worth finding out about in the
     * field.
     */
    private static final double LABEL_RESOLUTION = 120;

    /** Resolution to fly to on Go to, in meters per pixel. */
    private static final double GOTO_RESOLUTION = 8;

    public interface Listener {
        void onDetails(Site site);
    }

    private final MapView mapView;
    private final Context pluginContext;
    private final MapGroup group;
    private final Map<String, Marker> markers = new HashMap<>();
    private final Map<String, Site> shown = new HashMap<>();
    private final List<Site> selected = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Listener listener;
    private Icon icon, iconSeen;
    private String menu;
    /** Site id to how likely it is to answer a handheld here; absent means not checked. */
    private final Map<String, Reach.State> reach = new HashMap<>();
    private double maxResolution = 500;
    private boolean mapOn = true;
    private double checkRangeM = 30000;

    /**
     * Runs on the GL render thread, not the UI thread. Mutating map items from inside
     * a render pass is a native SIGSEGV with no Java stack trace, so the gate is posted
     * to the main thread and coalesced.
     */
    private final AtakMapView.OnMapMovedListener moved = new AtakMapView.OnMapMovedListener() {
        @Override
        public void onMapMoved(AtakMapView view, boolean animate) {
            main.removeCallbacks(gateTick);
            main.postDelayed(gateTick, 150);
        }
    };

    private final Runnable gateTick = new Runnable() {
        @Override
        public void run() {
            applyZoomGate();
        }
    };

    /** The radial's Details button arrives here. */
    private final BroadcastReceiver radial = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String uid = intent.getStringExtra("targetUID");
            if (uid == null || !uid.startsWith(UID_PREFIX))
                return;
            final Site s = shown.get(uid.substring(UID_PREFIX.length()));
            if (s == null)
                return;
            if (ACTION_DETAILS.equals(intent.getAction())) {
                if (listener != null)
                    listener.onDetails(s);
            }
        }
    };

    public SiteLayer(MapView mapView, Context pluginContext) {
        this.mapView = mapView;
        this.pluginContext = pluginContext;
        MapGroup g = mapView.getRootGroup().findMapGroup(GROUP);
        if (g == null)
            g = mapView.getRootGroup().addGroup(GROUP);
        this.group = g;
        mapView.addOnMapMovedListener(moved);
        try {
            final AtakBroadcast.DocumentedIntentFilter f = new AtakBroadcast.DocumentedIntentFilter();
            f.addAction(ACTION_DETAILS);
                AtakBroadcast.getInstance().registerReceiver(radial, f);
        } catch (LinkageError | RuntimeException e) {
            Log.w(TAG, "radial actions unavailable", e);
        }
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void dispose() {
        main.removeCallbacks(gateTick);
        mapView.removeOnMapMovedListener(moved);
        try {
            AtakBroadcast.getInstance().unregisterReceiver(radial);
        } catch (LinkageError | RuntimeException ignored) {
        }
        for (String id : new ArrayList<>(markers.keySet()))
            remove(id);
        mapView.getRootGroup().removeGroup(group);
    }

    // ---- zoom gating ----------------------------------------------------------

    /** @param metersPerPixel sites draw at or below this; larger means zoomed out */
    public void setMaxResolution(double metersPerPixel) {
        maxResolution = metersPerPixel;
        applyZoomGate();
    }

    public double getMaxResolution() {
        return maxResolution;
    }

    public void setMapOn(boolean on) {
        mapOn = on;
        show(new ArrayList<>(selected));
    }

    public boolean isMapOn() {
        return mapOn;
    }

    public boolean isWithinZoom() {
        return mapView.getMapResolution() <= maxResolution * 1.001;
    }

    private void applyZoomGate() {
        final boolean visible = isWithinZoom();
        for (Map.Entry<String, Marker> e : markers.entrySet()) {
            if (e.getValue().getVisible() != visible)
                e.getValue().setVisible(visible);
        }
    }

    // ---- markers --------------------------------------------------------------

    /** Make the map show exactly these sites. */
    public void show(List<Site> sites) {
        selected.clear();
        selected.addAll(sites);
        final Set<String> wanted = new HashSet<>();
        if (mapOn)
            for (Site s : sites)
                wanted.add(s.id);
        for (String id : new ArrayList<>(markers.keySet()))
            if (!wanted.contains(id))
                remove(id);
        if (mapOn)
            for (Site s : sites)
                put(s);
    }

    private Marker put(Site s) {
        Marker m = markers.get(s.id);
        if (m == null) {
            m = create(s);
            markers.put(s.id, m);
            group.addItem(m);
        }
        update(m, s);
        shown.put(s.id, s);
        m.setVisible(isWithinZoom());
        return m;
    }

    private void remove(String id) {
        final Marker m = markers.remove(id);
        if (m != null)
            group.removeItem(m);
        shown.remove(id);
    }

    private Marker create(Site s) {
        // With the ground elevation on the point, ATAK's own marker bubble reads
        // "5,686 ft MSL" instead of "--- ft MSL".
        GeoPoint p = new GeoPoint(s.lat, s.lon);
        if (!Double.isNaN(s.elevM)) {
            try {
                p = new GeoPoint(s.lat, s.lon,
                        com.atakmap.coremap.maps.conversion.EGM96.getHAE(s.lat, s.lon, s.elevM),
                        GeoPoint.AltitudeReference.HAE);
            } catch (LinkageError | RuntimeException ignored) {
                // No geoid on this build: a point without altitude is still a site.
            }
        }
        final Marker m = new Marker(p, UID_PREFIX + s.id);
        // A waypoint: a type ATAK recognizes, so nothing about the marker reads as
        // broken, with this plugin's own icon and radial on top of it.
        m.setType("b-m-p-w");
        m.setMetaBoolean("readiness", true);
        m.setMetaBoolean("archive", false);
        m.setMetaBoolean("removable", false);
        m.setMetaBoolean("editable", false);
        m.setMetaString("how", "m-g");
        m.setMovable(false);
        m.setClickable(true);
        try {
            if (menu == null)
                menu = PluginMenuParser.getMenu(pluginContext, "menu/site.xml");
            m.setMetaString("menu", menu);
        } catch (LinkageError | RuntimeException e) {
            Log.w(TAG, "could not attach the radial menu", e);
        }
        m.setMetaBoolean("adapt_marker_icon", false);
        try {
            m.setMaxLabelRenderResolution(LABEL_RESOLUTION);
        } catch (LinkageError | RuntimeException notThisBuild) {
            Log.w(TAG, "could not widen the label range", notThisBuild);
        }
        applyIcon(m, s);
        return m;
    }

    /** An icon drawn as shipped: white multiplies to no tint, so the symbol keeps its colors. */
    private Icon buildIcon(int drawable) {
        return new Icon.Builder()
                .setImageUri(Icon.STATE_DEFAULT, "android.resource://"
                        + pluginContext.getPackageName() + "/" + drawable)
                .setAnchor(Icon.ANCHOR_CENTER, Icon.ANCHOR_CENTER)
                .setColor(Icon.STATE_DEFAULT, 0xFFFFFFFF)
                .build();
    }

    /**
     * The NWCG GeoOps repeater symbol, as Feature Layer draws it, with its marks lit
     * green when a handheld where the operator is standing is likely to open it.
     */
    private void applyIcon(Marker m, Site s) {
        try {
            if (icon == null)
                icon = buildIcon(com.atakmap.android.comms.plugin.R.drawable.ic_marker);
            if (iconSeen == null)
                iconSeen = buildIcon(com.atakmap.android.comms.plugin.R.drawable.ic_marker_seen);
            // Green is "go for it", so only a likely path lights the symbol up.
            // Marginal is left plain rather than half-promised.
            final Icon want = reach.get(s.id) == Reach.State.LIKELY ? iconSeen : icon;
            if (m.getIcon() != want)
                m.setIcon(want);
        } catch (LinkageError | RuntimeException e) {
            Log.w(TAG, "could not set the site icon", e);
        }
    }

    private void update(Marker m, Site s) {
        // The label on the map drops a source's own qualifier: the map is already
        // showing where the mountain is, so "(Corona)" after Sierra Peak is noise,
        // and Sierra Peak is not in Corona anyway.
        final String label = s.label();
        m.setTitle(label);
        m.setMetaString("callsign", label);
        m.setMetaString("remarks", remarks(s));
        applyIcon(m, s);
    }

    /** Take the reach answers and repaint; an empty map puts every site back to plain. */
    public void setReach(Map<String, Reach.State> states) {
        reach.clear();
        if (states != null)
            reach.putAll(states);
        for (Map.Entry<String, Marker> e : markers.entrySet()) {
            final Site s = shown.get(e.getKey());
            if (s != null)
                applyIcon(e.getValue(), s);
        }
    }

    private static String remarks(Site s) {
        final StringBuilder b = new StringBuilder();
        if (!s.county.isEmpty())
            b.append(s.county).append(" County, ");
        b.append(s.st);
        if (!s.channels.isEmpty()) {
            b.append('\n');
            for (int i = 0; i < s.channels.size(); i++) {
                if (i > 0)
                    b.append(" · ");
                b.append(Nets.noRepeaterSuffix(s.channels.get(i).netId));
            }
        }
        return b.toString();
    }

    public Site siteOf(String uid) {
        return uid != null && uid.startsWith(UID_PREFIX) ? shown.get(uid.substring(UID_PREFIX.length())) : null;
    }

    // ---- range and antenna height ----------------------------------------------

    /** How far out sites are checked against the operator's position. */
    public void setCheckRangeMeters(double m) {
        checkRangeM = Math.max(500, Math.min(MAX_CHECK_M, m));
    }

    public double getCheckRangeMeters() {
        return checkRangeM;
    }

    /** A site's antenna height in meters above ground, as {@link Reach} uses it. */
    public static double antennaHeight(Site s) {
        return Double.isNaN(s.antM) || s.antM <= 0 ? DEFAULT_ANTENNA_M : s.antM;
    }

    // ---- navigation -----------------------------------------------------------

    /** Fly to a site, close enough that it is drawn whatever the zoom gate says. */
    public void goTo(Site s) {
        if (s == null)
            return;
        final GeoPoint p = new GeoPoint(s.lat, s.lon);
        try {
            final double limit = Math.min(GOTO_RESOLUTION, maxResolution * 0.5);
            // Never zoom the operator back out to get to a site.
            final double target = Math.min(mapView.getMapResolution(), limit);
            mapView.getMapController().panZoomTo(p, mapView.mapResolutionAsMapScale(target), true);
        } catch (LinkageError | RuntimeException e) {
            Log.w(TAG, "panZoomTo failed; falling back to a plain pan", e);
            mapView.getMapController().panTo(p, true);
        }
    }
}

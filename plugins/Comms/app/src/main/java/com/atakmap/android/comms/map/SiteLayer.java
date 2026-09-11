package com.atakmap.android.comms.map;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The sites on the map: one marker per site, a zoom gate, and ATAK's own viewshed
 * drawn from the antenna.
 *
 * <h3>Viewshed</h3>
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
 * <p>The viewshed is line of sight from the antenna, not radio coverage, and the
 * pane says so beside the button.
 *
 * <p>Markers are only ever created and mutated in place; a site whose viewshed is on
 * is pinned, so filtering it out of the list does not tear the viewshed's marker out
 * from under ATAK.
 */
public final class SiteLayer {

    private static final String TAG = "CommsLayer";
    private static final String GROUP = "Comms";
    private static final String UID_PREFIX = "comms.";
    /** The marker metadata the radial reads to light the viewshed button. */
    private static final String META_VIEWSHED = "commsViewshed";

    public static final String ACTION_DETAILS = "com.atakmap.android.comms.SITE_DETAILS";
    public static final String ACTION_VIEWSHED = "com.atakmap.android.comms.SITE_VIEWSHED";

    // ViewShedReceiver's own action and extra names, as string literals so a build of
    // ATAK without the class costs the viewshed and not the plugin.
    private static final String VS_SHOW = "com.atakmap.android.elev.ViewShedReceiver.SHOW_VIEWSHED";
    private static final String VS_DISMISS = "com.atakmap.android.elev.ViewShedReceiver.DISMISS_VIEWSHED";

    /** ATAK's own tool caps its slider here; the receiver takes any radius. */
    public static final double MAX_VIEWSHED_M = 100000;
    /** When the catalog does not say how tall the antenna is. */
    public static final double DEFAULT_ANTENNA_M = 10;
    /** Resolution to fly to on Go to, in meters per pixel. */
    private static final double GOTO_RESOLUTION = 8;

    public interface Listener {
        void onDetails(Site site);

        /** A viewshed was switched on or off, from the pane or from the radial. */
        void onViewshedChanged();
    }

    private final MapView mapView;
    private final Context pluginContext;
    private final MapGroup group;
    private final Map<String, Marker> markers = new HashMap<>();
    private final Map<String, Site> shown = new HashMap<>();
    private final Set<String> viewsheds = new LinkedHashSet<>();
    /**
     * When each viewshed was asked for. The show intent is a local broadcast and is
     * delivered after this call returns, so a registry check made in the same tap
     * finds nothing and would dismiss what was just requested (measured on the
     * XCover, 2026-09-09). Young requests are trusted until ATAK has had time.
     */
    private final Map<String, Long> requestedAt = new HashMap<>();
    private static final long SETTLE_MS = 8000;
    private final List<Site> selected = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Listener listener;
    private Icon icon, iconSeen;
    private String menu;
    /** The invisible marker the operator's own viewshed hangs on. */
    private static final String ME_UID = UID_PREFIX + "me";
    private Marker me;
    private boolean meViewshed;
    /** Site id to whether it has line of sight from the operator; absent means not checked. */
    private final Map<String, Boolean> lineOfSight = new HashMap<>();
    private double maxResolution = 500;
    private boolean mapOn = true;
    private double viewshedRangeM = 30000;

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

    /** The radial's Details and Viewshed buttons arrive here. */
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
            } else if (ACTION_VIEWSHED.equals(intent.getAction())) {
                toggleViewshed(s);
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
            f.addAction(ACTION_VIEWSHED);
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
        // ATAK would otherwise keep the viewshed layers alive with nothing owning them.
        hideAllViewsheds();
        hideMeViewshed();
        for (String id : new ArrayList<>(markers.keySet()))
            remove(id);
        mapView.getRootGroup().removeGroup(group);
    }

    // ---- zoom gating ----------------------------------------------------------

    /** @param metersPerPixel sites draw at or below this; larger means zoomed out */
    public void setMaxResolution(double metersPerPixel) {
        maxResolution = metersPerPixel;
        for (Marker m : markers.values())
            labelWithTheIcon(m);
        applyZoomGate();
    }

    /**
     * Show a site's name whenever its marker is drawn.
     *
     * <p>ATAK labels a marker only from 10 m per pixel inward, which is several
     * pinches closer than the zoom these markers appear at, so a screen of repeaters
     * was a screen of anonymous diamonds. The label window is therefore the same
     * threshold the markers themselves use: if the dot is on the map its name is
     * readable, and the operator moves both together with the zoom presets.
     */
    private void labelWithTheIcon(Marker m) {
        try {
            m.setMaxLabelRenderResolution(maxResolution >= Float.MAX_VALUE / 2
                    ? Double.MAX_VALUE : maxResolution);
            m.setMinLabelRenderResolution(Marker.DEFAULT_MIN_LABEL_RENDER_RESOLUTION);
        } catch (LinkageError | RuntimeException notThisBuild) {
            Log.w(TAG, "could not widen the label range", notThisBuild);
        }
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
            // A site with its viewshed on stays visible at any zoom: the viewshed is
            // there, and a marker vanishing from under it reads as a bug.
            final boolean want = visible || viewsheds.contains(e.getKey());
            if (e.getValue().getVisible() != want)
                e.getValue().setVisible(want);
        }
    }

    // ---- markers --------------------------------------------------------------

    /** Make the map show exactly these sites (plus any whose viewshed is on). */
    public void show(List<Site> sites) {
        selected.clear();
        selected.addAll(sites);
        final Set<String> wanted = new HashSet<>();
        if (mapOn)
            for (Site s : sites)
                wanted.add(s.id);
        wanted.addAll(viewsheds);
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
        m.setVisible(isWithinZoom() || viewsheds.contains(s.id));
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
        m.setMetaBoolean(META_VIEWSHED, viewsheds.contains(s.id));
        try {
            if (menu == null)
                menu = PluginMenuParser.getMenu(pluginContext, "menu/site.xml");
            m.setMetaString("menu", menu);
        } catch (LinkageError | RuntimeException e) {
            Log.w(TAG, "could not attach the radial menu", e);
        }
        m.setMetaBoolean("adapt_marker_icon", false);
        labelWithTheIcon(m);
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
     * The NWCG GeoOps repeater symbol, as Feature Layer draws it, or the same on a
     * green ring when the site has line of sight from the operator.
     */
    private void applyIcon(Marker m, Site s) {
        try {
            if (icon == null)
                icon = buildIcon(com.atakmap.android.comms.plugin.R.drawable.ic_marker);
            if (iconSeen == null)
                iconSeen = buildIcon(com.atakmap.android.comms.plugin.R.drawable.ic_marker_seen);
            final Boolean seen = lineOfSight.get(s.id);
            final Icon want = seen != null && seen ? iconSeen : icon;
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
        m.setMetaBoolean(META_VIEWSHED, viewsheds.contains(s.id));
        applyIcon(m, s);
    }

    /** Take the line-of-sight answers and repaint; an empty map clears the tint. */
    public void setLineOfSight(Map<String, Boolean> los) {
        lineOfSight.clear();
        if (los != null)
            lineOfSight.putAll(los);
        for (Map.Entry<String, Marker> e : markers.entrySet()) {
            final Site s = shown.get(e.getKey());
            if (s != null)
                applyIcon(e.getValue(), s);
        }
    }

    // ---- the operator's own viewshed --------------------------------------------

    public boolean isMeViewshedOn() {
        return meViewshed;
    }

    /**
     * Draw ATAK's viewshed from the operator, {@code aboveGround} meters up, out to
     * the same range the site viewsheds use. Re-issuing with a new point moves it:
     * the receiver updates the layer it already holds for the uid.
     */
    public boolean showMeViewshed(GeoPoint at, double aboveGround) {
        try {
            final GeoPoint p = new GeoPoint(at.getLatitude(), at.getLongitude(), aboveGround,
                    GeoPoint.AltitudeReference.AGL);
            if (me == null) {
                // Invisible and unclickable: the self marker is already on the map, and
                // this one exists only so the viewshed has a uid to hang on.
                me = new Marker(p, ME_UID);
                me.setType("b-m-p-w");
                me.setTitle("Viewshed from you");
                me.setMetaBoolean("addToObjList", false);
                me.setMetaBoolean("removable", false);
                me.setMetaBoolean("editable", false);
                me.setMetaBoolean("archive", false);
                me.setMovable(false);
                me.setClickable(false);
                me.setVisible(false);
                group.addItem(me);
            } else {
                me.setPoint(p);
            }
            final Intent i = new Intent(VS_SHOW);
            i.putExtra("uid", ME_UID);
            i.putExtra("point", p);
            i.putExtra("radius", viewshedRangeM);
            i.putExtra("circle", true);
            i.putExtra("show_icon", false);
            i.putExtra("title", "Viewshed from you");
            AtakBroadcast.getInstance().sendBroadcast(i);
            meViewshed = true;
            Log.d(TAG, String.format(java.util.Locale.US, "viewshed from operator: %.5f, %.5f %.1f m up, radius %.0f m",
                    at.getLatitude(), at.getLongitude(), aboveGround, viewshedRangeM));
            return true;
        } catch (LinkageError | RuntimeException e) {
            Log.w(TAG, "viewshed from the operator failed", e);
            return false;
        }
    }

    public void hideMeViewshed() {
        if (!meViewshed && me == null)
            return;
        meViewshed = false;
        try {
            final Intent i = new Intent(VS_DISMISS);
            i.putExtra("uid", ME_UID);
            AtakBroadcast.getInstance().sendBroadcast(i);
        } catch (LinkageError | RuntimeException e) {
            Log.w(TAG, "viewshed dismiss failed for the operator", e);
        }
        if (me != null) {
            group.removeItem(me);
            me = null;
        }
        Log.d(TAG, "viewshed from operator off");
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
                b.append(s.channels.get(i).netId);
            }
        }
        return b.toString();
    }

    public Site siteOf(String uid) {
        return uid != null && uid.startsWith(UID_PREFIX) ? shown.get(uid.substring(UID_PREFIX.length())) : null;
    }

    // ---- viewshed -------------------------------------------------------------

    public void setViewshedRangeMeters(double m) {
        viewshedRangeM = Math.max(500, Math.min(MAX_VIEWSHED_M, m));
    }

    public double getViewshedRangeMeters() {
        return viewshedRangeM;
    }

    /** The antenna height the viewshed is drawn from, in meters above ground. */
    public static double antennaHeight(Site s) {
        return Double.isNaN(s.antM) || s.antM <= 0 ? DEFAULT_ANTENNA_M : s.antM;
    }

    public boolean isViewshedOn(Site s) {
        syncViewsheds();
        return viewsheds.contains(s.id);
    }

    public int viewshedCount() {
        syncViewsheds();
        return viewsheds.size();
    }

    public void toggleViewshed(Site s) {
        if (isViewshedOn(s))
            hideViewshed(s);
        else
            showViewshed(s);
    }

    public boolean showViewshed(Site s) {
        // The marker must exist for ATAK to hang the viewshed on; a site that is
        // filtered out or zoom-gated gets one now, and stays until the viewshed goes.
        final Marker m = put(s);
        try {
            final Intent i = new Intent(VS_SHOW);
            i.putExtra("uid", UID_PREFIX + s.id);
            i.putExtra("point", new GeoPoint(s.lat, s.lon, antennaHeight(s), GeoPoint.AltitudeReference.AGL));
            i.putExtra("radius", viewshedRangeM);
            i.putExtra("circle", true);
            i.putExtra("title", s.name);
            AtakBroadcast.getInstance().sendBroadcast(i);
            viewsheds.add(s.id);
            requestedAt.put(s.id, System.currentTimeMillis());
            m.setMetaBoolean(META_VIEWSHED, true);
            m.setVisible(true);
            Log.d(TAG, "viewshed on: " + s.id + " antenna " + antennaHeight(s) + " m, radius " + viewshedRangeM + " m");
            if (listener != null)
                listener.onViewshedChanged();
            return true;
        } catch (LinkageError | RuntimeException e) {
            Log.w(TAG, "viewshed request failed for " + s.id, e);
            return false;
        }
    }

    public void hideViewshed(Site s) {
        hideViewshedById(s.id);
        if (listener != null)
            listener.onViewshedChanged();
    }

    private void hideViewshedById(String id) {
        viewsheds.remove(id);
        requestedAt.remove(id);
        Log.d(TAG, "viewshed off: " + id);
        try {
            final Intent i = new Intent(VS_DISMISS);
            i.putExtra("uid", UID_PREFIX + id);
            AtakBroadcast.getInstance().sendBroadcast(i);
        } catch (LinkageError | RuntimeException e) {
            Log.w(TAG, "viewshed dismiss failed for " + id, e);
        }
        final Marker m = markers.get(id);
        if (m != null) {
            m.setMetaBoolean(META_VIEWSHED, false);
            // Pinned only for the viewshed: if the filters no longer want it, it goes.
            boolean wanted = false;
            for (Site s : selected)
                if (s.id.equals(id)) {
                    wanted = true;
                    break;
                }
            if (!wanted || !mapOn)
                remove(id);
            else
                m.setVisible(isWithinZoom());
        }
    }

    public int hideAllViewsheds() {
        final int n = viewsheds.size();
        for (String id : new ArrayList<>(viewsheds))
            hideViewshedById(id);
        if (n > 0 && listener != null)
            listener.onViewshedChanged();
        return n;
    }

    /**
     * ATAK can drop a viewshed without telling the plugin (its Overlay Manager, its
     * own tool taking the slot). The receiver keeps a public map of live viewsheds by
     * uid; read it when it is there, and keep our own record when it is not.
     */
    private void syncViewsheds() {
        try {
            final Map<String, ?> live = com.atakmap.android.elev.ViewShedReceiver.getSingleVsdLayerMap();
            if (live == null)
                return;
            final long now = System.currentTimeMillis();
            for (String id : new ArrayList<>(viewsheds)) {
                final Long at = requestedAt.get(id);
                if (at != null && now - at < SETTLE_MS)
                    continue;
                final Object layers = live.get(UID_PREFIX + id);
                final boolean present = layers instanceof java.util.Collection
                        && !((java.util.Collection<?>) layers).isEmpty();
                if (!present)
                    hideViewshedById(id);
            }
        } catch (LinkageError | RuntimeException ignored) {
            // Not this ATAK build; our own record stands.
        }
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

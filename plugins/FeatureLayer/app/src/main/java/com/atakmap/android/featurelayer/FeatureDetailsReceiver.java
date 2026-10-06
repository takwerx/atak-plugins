package com.atakmap.android.featurelayer;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.TextView;

import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.dropdown.DropDown.OnStateListener;
import com.atakmap.android.dropdown.DropDownReceiver;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.featurelayer.plugin.R;
import com.atakmap.coremap.log.Log;
import com.atakmap.map.layer.feature.AttributeSet;
import com.atakmap.map.layer.feature.Feature;
import com.atakmap.map.layer.feature.FeatureDataStore2;
import com.atakmap.map.layer.feature.Utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The details pane a tapped feature opens: title, layer, then every attribute. */
public class FeatureDetailsReceiver extends DropDownReceiver implements OnStateListener {

    private static final String TAG = "FeatureLayer";

    private final LayerManager manager;
    private final View view;

    public FeatureDetailsReceiver(MapView mapView, Context pluginContext, LayerManager manager) {
        super(mapView);
        this.manager = manager;
        this.view = PluginLayoutInflater.inflate(pluginContext, R.layout.details, null);
        // Back closes the details and leaves the map where it is; ATAK's own close would
        // otherwise be the only way out, and it is not where a thumb expects it.
        view.findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeDropDown();
            }
        });
    }

    /**
     * The attributes as Details prints them: sorted, the plugin's own "_" keys left out,
     * a "_title" handed back through {@code titleOut}. The pane's list uses the same
     * rendering, so a row and the radial show one thing.
     */
    public static String render(AttributeSet attrs, String[] titleOut) {
        final List<String> keys = new ArrayList<>();
        if (attrs != null)
            keys.addAll(attrs.getAttributeNames());
        // CAL FIRE's own lines first, together: on a fire it runs they are the current
        // acres and containment, and the IRWIN record's sixty fields follow.
        Collections.sort(keys, new java.util.Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                final boolean ca = a.startsWith(LoadedLayer.CALFIRE_PREFIX), cb = b.startsWith(LoadedLayer.CALFIRE_PREFIX);
                if (ca != cb)
                    return ca ? -1 : 1;
                return String.CASE_INSENSITIVE_ORDER.compare(a, b);
            }
        });
        final StringBuilder sb = new StringBuilder();
        for (String k : keys) {
            String v;
            try {
                v = attrs.getStringAttribute(k);
            } catch (Exception e) {
                v = "";
            }
            if (v == null || v.isEmpty())
                continue;
            if ("_title".equals(k)) {
                if (titleOut != null && titleOut.length > 0)
                    titleOut[0] = v;
                continue;
            }
            if (k.startsWith("_"))
                continue; // the plugin's own bookkeeping, not the feature's data
            final String ago = Esri.ago(v);
            sb.append(k).append(": ").append(v).append(ago == null ? "" : "  (" + ago + ")").append('\n');
        }
        return sb.toString().trim();
    }

    /**
     * Shows the InciWeb button when the feature carries a page and opens it in the
     * browser. The address is checked again here, not only when it was attached: the
     * store it was read back from sits on the shared card.
     */
    public static void bindInciWeb(View button, AttributeSet attrs, MapView mapView) {
        final String url = read(attrs, LoadedLayer.ATTR_INCIWEB);
        bindLink(button, InciWeb.isPage(url) ? url : null, mapView);
    }

    /** The same for the fire's CAL FIRE incident page. */
    public static void bindCalFire(View button, AttributeSet attrs, MapView mapView) {
        final String url = read(attrs, LoadedLayer.ATTR_CALFIRE);
        bindLink(button, CalFire.isPage(url) ? url : null, mapView);
    }

    /**
     * Only This Fire / Show All Fires on a burn's details: shown for Fire History burns,
     * reading what a tap will do now.
     */
    public static void bindOnly(View v, final LoadedLayer layer, final AttributeSet attrs, final LayerManager manager) {
        if (!(v instanceof android.widget.Button))
            return;
        final android.widget.Button b = (android.widget.Button) v;
        if (layer == null || manager == null || read(attrs, LoadedLayer.ATTR_FIRE) == null) {
            b.setVisibility(View.GONE);
            return;
        }
        b.setVisibility(View.VISIBLE);
        final boolean on = layer.isIsolated(attrs);
        b.setText(on ? "Show All Fires" : "Only This Fire");
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View x) {
                if (layer.isIsolated(attrs)) {
                    manager.showAllFires(layer);
                    b.setText("Only This Fire");
                } else {
                    manager.isolate(layer, attrs);
                    b.setText("Show All Fires");
                }
            }
        });
    }

    private static String read(AttributeSet attrs, String key) {
        try {
            if (attrs != null && attrs.containsAttribute(key))
                return attrs.getStringAttribute(key);
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Shows the button for a checked page and opens exactly that page; hides it for null. */
    private static void bindLink(View button, final String page, final MapView mapView) {
        if (button == null)
            return;
        if (page == null) {
            button.setVisibility(View.GONE);
            button.setOnClickListener(null);
            return;
        }
        button.setVisibility(View.VISIBLE);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    final Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(page));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    mapView.getContext().startActivity(i);
                } catch (Exception e) {
                    Log.w(TAG, "could not open " + page, e);
                    android.widget.Toast.makeText(mapView.getContext(), "No browser to open the page",
                            android.widget.Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        final String uid = intent.getStringExtra("targetUID");
        final MapItem item = uid == null ? null : getMapView().getRootGroup().deepFindItem("uid", uid);
        if (item == null) {
            Log.d(TAG, "details: no map item for " + uid);
            return;
        }
        show(item);
    }

    /** Opens the details of a tapped feature's map item; the radial's Details and a fire's tap both land here. */
    public void show(MapItem item) {
        final long fid = item.getMetaLong("featureid", -1);
        final String layerId = item.getMetaString("nifs_layer", null);
        final FeatureDataStore2 store = layerId == null ? null : manager.storeFor(layerId);
        Feature f = null;
        try {
            if (fid >= 0 && store != null)
                f = Utils.getFeature(store, fid);
        } catch (Exception e) {
            Log.w(TAG, "details: feature " + fid + " lookup failed", e);
        }
        if (f == null) {
            Log.d(TAG, "details: no feature for id " + fid + " in " + layerId);
            return;
        }
        final LoadedLayer layer = manager.find(layerId);
        final String[] title = { f.getName() };
        final String body = render(f.getAttributes(), title);
        ((TextView) view.findViewById(R.id.details_title)).setText(title[0]);
        ((TextView) view.findViewById(R.id.details_subtitle))
                .setText(layer == null ? "" : layer.displayName());
        ((TextView) view.findViewById(R.id.details_attributes)).setText(body);
        bindInciWeb(view.findViewById(R.id.btn_inciweb), f.getAttributes(), getMapView());
        bindCalFire(view.findViewById(R.id.btn_calfire), f.getAttributes(), getMapView());
        bindOnly(view.findViewById(R.id.btn_only), layer, f.getAttributes(), manager);
        showDropDown(view, HALF_WIDTH, FULL_HEIGHT, FULL_WIDTH, HALF_HEIGHT, this);
    }

    @Override
    public void onDropDownSelectionRemoved() {
    }

    @Override
    public void onDropDownVisible(boolean v) {
    }

    @Override
    public void onDropDownSizeChanged(double width, double height) {
    }

    @Override
    public void onDropDownClose() {
    }

    @Override
    protected void disposeImpl() {
    }
}

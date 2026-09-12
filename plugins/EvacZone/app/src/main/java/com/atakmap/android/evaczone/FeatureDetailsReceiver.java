package com.atakmap.android.evaczone;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.TextView;

import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.dropdown.DropDown.OnStateListener;
import com.atakmap.android.dropdown.DropDownReceiver;
import com.atakmap.android.evaczone.plugin.R;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;
import com.atakmap.map.layer.feature.AttributeSet;
import com.atakmap.map.layer.feature.Feature;
import com.atakmap.map.layer.feature.FeatureDataStore2;
import com.atakmap.map.layer.feature.Utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The details pane a tapped zone opens: status and name, the source, then every attribute. */
public class FeatureDetailsReceiver extends DropDownReceiver implements OnStateListener {

    private static final String TAG = "EvacZone";

    private final ZoneManager manager;
    private final View view;
    /** What Back does after closing, when the details came from the pane's list. */
    private Runnable onBack;
    private boolean returnToPane;

    public FeatureDetailsReceiver(MapView mapView, Context pluginContext, ZoneManager manager) {
        super(mapView);
        this.manager = manager;
        this.view = PluginLayoutInflater.inflate(pluginContext, R.layout.details, null);
        view.findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeDropDown();
            }
        });
        // Go there from the details, so a zone found in the list can be read and then
        // framed without going back to the list first. The details stay open.
        view.findViewById(R.id.btn_go).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (shownBounds != null)
                    manager.frame(shownBounds);
            }
        });
    }

    /** South, west, north, east of the zone on show, for Go there. */
    private double[] shownBounds;

    public void setOnBack(Runnable r) {
        onBack = r;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        final String uid = intent.getStringExtra("targetUID");
        final MapItem item = uid == null ? null : getMapView().getRootGroup().deepFindItem("uid", uid);
        if (item == null) {
            Log.d(TAG, "details: no map item for " + uid);
            return;
        }
        // From the map: Back returns to the map, not to a pane that was not open.
        show(item.getMetaString("evaczone_source", null), item.getMetaLong("featureid", -1), false);
    }

    /**
     * Opens the details of one zone. From a row in the list, Back puts the pane back;
     * from a tap on the map it just closes.
     */
    public void show(String sourceId, long fid, boolean fromPane) {
        returnToPane = fromPane;
        final FeatureDataStore2 store = sourceId == null ? null : manager.storeFor(sourceId);
        Feature f = null;
        try {
            if (fid >= 0 && store != null)
                f = Utils.getFeature(store, fid);
        } catch (Exception e) {
            Log.w(TAG, "details: feature " + fid + " lookup failed", e);
        }
        if (f == null) {
            Log.d(TAG, "details: no feature for id " + fid + " in " + sourceId);
            return;
        }
        final ZoneLayer layer = manager.find(sourceId);
        shownBounds = null;
        try {
            final com.atakmap.map.layer.feature.geometry.Envelope e = f.getGeometry() == null ? null
                    : f.getGeometry().getEnvelope();
            if (e != null && !Double.isNaN(e.minX))
                shownBounds = new double[] { e.minY, e.minX, e.maxY, e.maxX };
        } catch (Exception ignored) {
        }
        view.findViewById(R.id.btn_go).setEnabled(shownBounds != null);
        final AttributeSet attrs = f.getAttributes();
        final List<String> keys = new ArrayList<>();
        if (attrs != null)
            keys.addAll(attrs.getAttributeNames());
        Collections.sort(keys, String.CASE_INSENSITIVE_ORDER);
        final StringBuilder sb = new StringBuilder();
        String title = f.getName();
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
                title = v;
                continue;
            }
            if (k.startsWith("_"))
                continue;
            sb.append(k).append(": ").append(v).append('\n');
        }
        ((TextView) view.findViewById(R.id.details_title)).setText(title);
        final String sub = layer == null ? "" : layer.displayName()
                + (layer.source.publisher.isEmpty() ? "" : " · " + layer.source.publisher);
        ((TextView) view.findViewById(R.id.details_subtitle)).setText(sub);
        ((TextView) view.findViewById(R.id.details_attributes)).setText(sb.toString().trim());
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
        // Back, the Android back key, or ATAK closing it: the pane comes back when the
        // details came from it. Once, then the flag is spent.
        if (returnToPane && onBack != null) {
            returnToPane = false;
            onBack.run();
        }
    }

    @Override
    protected void disposeImpl() {
    }
}

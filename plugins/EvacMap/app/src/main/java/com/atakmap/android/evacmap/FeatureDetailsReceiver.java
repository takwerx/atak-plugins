package com.atakmap.android.evacmap;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.TextView;

import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.dropdown.DropDown.OnStateListener;
import com.atakmap.android.dropdown.DropDownReceiver;
import com.atakmap.android.evacmap.plugin.R;
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

    private static final String TAG = "EvacMap";

    private final ZoneManager manager;
    private final View view;

    public FeatureDetailsReceiver(MapView mapView, Context pluginContext, ZoneManager manager) {
        super(mapView);
        this.manager = manager;
        this.view = PluginLayoutInflater.inflate(pluginContext, R.layout.details, null);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        final String uid = intent.getStringExtra("targetUID");
        final MapItem item = uid == null ? null : getMapView().getRootGroup().deepFindItem("uid", uid);
        if (item == null) {
            Log.d(TAG, "details: no map item for " + uid);
            return;
        }
        final long fid = item.getMetaLong("featureid", -1);
        final String sourceId = item.getMetaString("evacmap_source", null);
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
    }

    @Override
    protected void disposeImpl() {
    }
}

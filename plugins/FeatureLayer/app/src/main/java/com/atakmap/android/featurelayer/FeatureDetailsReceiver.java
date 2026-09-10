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

    @Override
    public void onReceive(Context context, Intent intent) {
        final String uid = intent.getStringExtra("targetUID");
        final MapItem item = uid == null ? null : getMapView().getRootGroup().deepFindItem("uid", uid);
        if (item == null) {
            Log.d(TAG, "details: no map item for " + uid);
            return;
        }
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
                continue; // the plugin's own bookkeeping, not the feature's data
            sb.append(k).append(": ").append(v).append('\n');
        }
        ((TextView) view.findViewById(R.id.details_title)).setText(title);
        ((TextView) view.findViewById(R.id.details_subtitle))
                .setText(layer == null ? "" : layer.displayName());
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

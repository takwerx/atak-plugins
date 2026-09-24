
package com.atakmap.android.atmosphere.ui;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.TextView;

import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.atmosphere.overlay.TropicalOverlay;
import com.atakmap.android.atmosphere.plugin.R;
import com.atakmap.android.dropdown.DropDown.OnStateListener;
import com.atakmap.android.dropdown.DropDownReceiver;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;
import com.atakmap.map.layer.feature.AttributeSet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What a tapped storm feature opens: what it is, which storm, then everything the
 * advisory says about it.
 *
 * <p>ATAK's own feature details would do something, but not this: the pane the
 * operator asked for is Feature Layer's, which prints the source's own fields under
 * an ATTRIBUTES heading with a Back button that leaves the map where it is. This is
 * that shape, carried forward.
 *
 * <p>The radial's details button broadcasts {@link #ACTION} with the tapped item's
 * uid. The attributes are already on the item -- {@code TropicalFeatures} puts them
 * there during the hit test, where they have to be fetched by feature id because the
 * hit-test query returns features without them.
 */
public class StormDetailsReceiver extends DropDownReceiver implements OnStateListener {

    private static final String TAG = "AtmosphereTropical";

    public static final String ACTION = "com.atakmap.android.atmosphere.STORM_DETAILS";

    private final View view;

    public StormDetailsReceiver(MapView mapView, Context pluginContext) {
        super(mapView);
        this.view = PluginLayoutInflater.inflate(pluginContext, R.layout.storm_details, null);
        // Back closes the details and leaves the map where it is; ATAK's own close is
        // not where a thumb expects it.
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
        final MapItem item = uid == null ? null
                : getMapView().getRootGroup().deepFindItem("uid", uid);
        if (item == null) {
            Log.d(TAG, "storm details: no map item for " + uid);
            return;
        }
        final String title = item.getMetaString("title", item.getMetaString("callsign", ""));
        final String body = item.getMetaString("remarks", "");
        ((TextView) view.findViewById(R.id.details_title))
                .setText(title.isEmpty() ? "Storm" : title);
        ((TextView) view.findViewById(R.id.details_subtitle))
                .setText(item.getMetaString("storm_set", ""));
        ((TextView) view.findViewById(R.id.details_attributes)).setText(body);
        showDropDown(view, HALF_WIDTH, FULL_HEIGHT, FULL_WIDTH, HALF_HEIGHT, this);
    }

    /** The attributes as the pane prints them: sorted, one per line, blanks dropped. */
    public static String render(AttributeSet attrs) {
        if (attrs == null)
            return "";
        final List<String> keys = new ArrayList<>(attrs.getAttributeNames());
        Collections.sort(keys, String.CASE_INSENSITIVE_ORDER);
        final StringBuilder sb = new StringBuilder();
        for (String k : keys) {
            String v;
            try {
                if (attrs.getAttributeType(k) != String.class)
                    continue;
                v = attrs.getStringAttribute(k);
            } catch (Exception e) {
                continue;
            }
            if (v == null || v.isEmpty() || v.equals("null"))
                continue;
            if (sb.length() > 0)
                sb.append('\n');
            sb.append(k).append(": ").append(v);
        }
        return sb.toString();
    }

    @Override
    protected void disposeImpl() {
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
}

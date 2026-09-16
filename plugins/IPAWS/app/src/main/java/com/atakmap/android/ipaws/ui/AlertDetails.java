package com.atakmap.android.ipaws.ui;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.TextView;

import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.dropdown.DropDown.OnStateListener;
import com.atakmap.android.dropdown.DropDownReceiver;
import com.atakmap.android.ipaws.AlertManager;
import com.atakmap.android.ipaws.data.Alert;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.ipaws.plugin.R;
import com.atakmap.coremap.log.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * One alert, in full: the CAP headline, what is happening, and what to do.
 *
 * <p>A {@code DropDownReceiver} rather than an {@code AlertDialog}, because a dialog
 * blocks the map -- you could read the alert or look at where it is, not both, and the
 * whole point of putting this on a map is the second one.
 */
public class AlertDetails extends DropDownReceiver implements OnStateListener {

    private static final String TAG = "IPAWS";
    public static final String ACTION = "com.atakmap.android.ipaws.DETAILS";

    private final View view;
    private final AlertManager manager;
    private Alert showing;

    public AlertDetails(MapView mapView, Context pluginContext, AlertManager manager) {
        super(mapView);
        this.manager = manager;
        this.view = PluginLayoutInflater.inflate(pluginContext, R.layout.alert_details, null);
        // Back closes the details and leaves the map where it is; ATAK's own close is
        // otherwise the only way out, and it is not where a thumb expects it.
        view.findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeDropDown();
            }
        });
        view.findViewById(R.id.btn_zoom).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (showing != null)
                    manager.panTo(showing);
            }
        });
    }

    /** Shown from the list, so the alert comes straight in rather than by uid. */
    public void show(Alert a) {
        showing = a;
        bind(a);
        if (!isVisible())
            showDropDown(view, HALF_WIDTH, FULL_HEIGHT, FULL_WIDTH, HALF_HEIGHT, this);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        // Nothing arrives by intent yet; the list calls show() directly. Kept so a
        // radial menu entry can be added without moving anything.
    }

    private void bind(Alert a) {
        text(R.id.d_event, a.event);
        text(R.id.d_meta, meta(a));
        text(R.id.d_area, a.areaDesc);
        section(R.id.h_headline, R.id.d_headline, a.headline);
        section(R.id.h_what, R.id.d_description, a.description);
        section(R.id.h_do, R.id.d_instruction, a.instruction);
        text(R.id.d_sender, a.senderName == null || a.senderName.isEmpty()
                ? "" : "Issued by " + a.senderName);
    }

    private String meta(Alert a) {
        final StringBuilder sb = new StringBuilder();
        sb.append(a.severity);
        if (a.urgency != null && !a.urgency.isEmpty())
            sb.append("  -  ").append(a.urgency);
        if (a.certainty != null && !a.certainty.isEmpty())
            sb.append("  -  ").append(a.certainty);
        final String ends = when(a.until());
        if (ends != null)
            sb.append("\nUntil ").append(ends).append("  (")
                    .append(AlertRows.timing(a)).append(")");
        return sb.toString();
    }

    /** Local time, because an operator reads a clock, not an offset. */
    private static String when(long millis) {
        if (millis <= 0)
            return null;
        try {
            return new SimpleDateFormat("EEE d MMM, HH:mm", Locale.US).format(new Date(millis));
        } catch (RuntimeException e) {
            Log.w(TAG, "could not format " + millis, e);
            return null;
        }
    }

    private void text(int id, String s) {
        final TextView t = view.findViewById(id);
        t.setText(s == null ? "" : s);
        t.setVisibility(s == null || s.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** A CAP field that is empty takes its heading with it rather than leaving a stub. */
    private void section(int headingId, int bodyId, String s) {
        final boolean has = s != null && !s.trim().isEmpty();
        view.findViewById(headingId).setVisibility(has ? View.VISIBLE : View.GONE);
        text(bodyId, has ? s.trim() : null);
    }

    @Override
    protected void disposeImpl() {
        showing = null;
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
        showing = null;
    }
}

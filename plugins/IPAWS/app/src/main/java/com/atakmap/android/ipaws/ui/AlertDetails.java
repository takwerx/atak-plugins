package com.atakmap.android.ipaws.ui;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.ipaws.AlertManager;
import com.atakmap.android.ipaws.data.Alert;
import com.atakmap.android.ipaws.plugin.R;
import com.atakmap.coremap.log.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * One alert in full: the CAP headline, what is happening, and what to do.
 *
 * <p>A view in a pane rather than a dialog, because a dialog blocks the map -- you
 * could read the alert or look at where it is, not both, and the second one is the
 * point of putting this on a map at all.
 *
 * <p>It is a <b>second Pane</b>, not a DropDownReceiver. A drop-down closes the plugin
 * pane underneath it, so the back key left the operator on a bare map with the whole
 * list gone. Cam Depot hit the same thing: both panes live in the same slot, so opening
 * an alert REPLACES the list, and closing it has to put the list back. That is done by
 * listening for the pane closing rather than in the Back button's handler, because the
 * back key never goes through that button.
 */
public class AlertDetails {

    private static final String TAG = "IPAWS";

    /** Where the details view is put, and what puts the list back. */
    public interface Host {
        void showDetailPane(View v);

        void hideDetailPane();
    }

    private final View view;
    private final AlertManager manager;
    private final Host host;
    private Alert showing;

    public AlertDetails(Context pluginContext, AlertManager manager, Host host) {
        this.manager = manager;
        this.host = host;
        this.view = PluginLayoutInflater.inflate(pluginContext, R.layout.alert_details, null);
        view.findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.hideDetailPane();
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

    public void show(Alert a) {
        showing = a;
        bind(a);
        host.showDetailPane(view);
    }

    public View getView() {
        return view;
    }

    public void dispose() {
        showing = null;
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
}

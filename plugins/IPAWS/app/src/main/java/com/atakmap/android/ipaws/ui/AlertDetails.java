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
 * One page of metadata for one alert, and the only one.
 *
 * <p>There used to be two. Tapping a polygon on the map got ATAK's built-in feature
 * metadata -- no Back, and nothing like ours -- while the list got this. Two screens
 * for the same thing, and only one of them had a way out. A tap on the map now opens
 * this page directly, both routes call {@link #show}, and {@link #render} is the single
 * thing that turns an alert into text, so a row and the map cannot drift apart.
 *
 * <p>Feature Layer's frame, deliberately: Back pinned top-left so it stays reachable
 * while the metadata scrolls, the alert's name beside it, what it came from underneath,
 * then the fields. A user moving between takwerx plugins should not meet a different
 * details screen in each one.
 */
public class AlertDetails {

    private static final String TAG = "IPAWS";

    /** Where the page is put, and what puts the list back when it closes. */
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

    /** The one way in, from the list and from the map alike. */
    public void show(Alert a) {
        if (a == null)
            return;
        showing = a;
        ((TextView) view.findViewById(R.id.details_title)).setText(a.event);
        ((TextView) view.findViewById(R.id.details_subtitle)).setText(subtitle(a));
        ((TextView) view.findViewById(R.id.details_attributes)).setText(render(a));
        host.showDetailPane(view);
    }

    public View getView() {
        return view;
    }

    public void dispose() {
        showing = null;
    }

    private static String subtitle(Alert a) {
        final StringBuilder sb = new StringBuilder(a.severity == null ? "" : a.severity);
        if (a.senderName != null && !a.senderName.isEmpty())
            sb.append(sb.length() > 0 ? "  -  " : "").append(a.senderName);
        return sb.toString();
    }

    /**
     * The alert as metadata, one field per block.
     *
     * <p>Not a raw attribute dump the way an arbitrary GIS layer gets one. CAP fields
     * are known and three of them -- the headline, what is happening and what to do --
     * are paragraphs the issuing office wrote for a person to read, so they get a
     * heading and their own block rather than being run onto the end of a
     * "description:" line. The short fields follow as plain pairs.
     *
     * <p>An empty field is left out entirely. A heading over nothing is worse than a
     * shorter page.
     */
    public static String render(Alert a) {
        final StringBuilder sb = new StringBuilder();
        pair(sb, "Severity", a.severity);
        pair(sb, "Urgency", a.urgency);
        pair(sb, "Certainty", a.certainty);
        pair(sb, "Areas", a.areaDesc);
        pair(sb, "Effective", when(a.effective));
        pair(sb, "Until", when(a.until()));
        pair(sb, "Time left", timeLeft(a));
        pair(sb, "Issued by", a.senderName);
        block(sb, "HEADLINE", a.headline);
        block(sb, "WHAT IS HAPPENING", a.description);
        block(sb, "WHAT TO DO", a.instruction);
        return sb.toString().trim();
    }

    private static void pair(StringBuilder sb, String key, String value) {
        if (value == null || value.trim().isEmpty())
            return;
        sb.append(key).append(": ").append(value.trim()).append('\n');
    }

    private static void block(StringBuilder sb, String heading, String body) {
        if (body == null || body.trim().isEmpty())
            return;
        sb.append('\n').append(heading).append('\n').append(body.trim()).append('\n');
    }

    private static String timeLeft(Alert a) {
        final long until = a.until();
        if (until <= 0)
            return "no end time given";
        final long left = until - System.currentTimeMillis();
        return left <= 0 ? "ended" : AlertRows.remaining(left) + " left";
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
}

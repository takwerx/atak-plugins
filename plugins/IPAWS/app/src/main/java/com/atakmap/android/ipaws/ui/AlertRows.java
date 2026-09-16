package com.atakmap.android.ipaws.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.ipaws.AlertStyles;
import com.atakmap.android.ipaws.data.Alert;
import com.atakmap.android.ipaws.plugin.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The alert list: most severe first, and how long each one has left. */
public class AlertRows extends BaseAdapter {

    private final Context pluginContext;
    private final List<Alert> items = new ArrayList<>();

    public AlertRows(Context pluginContext) {
        this.pluginContext = pluginContext;
    }

    public void set(List<Alert> alerts) {
        items.clear();
        if (alerts != null)
            items.addAll(alerts);
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public Alert getItem(int position) {
        return position >= 0 && position < items.size() ? items.get(position) : null;
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View v = convertView;
        if (v == null)
            v = PluginLayoutInflater.inflate(pluginContext, R.layout.alert_row, null);
        final Alert a = getItem(position);
        if (a == null)
            return v;
        v.findViewById(R.id.severity_bar)
                .setBackgroundColor(AlertStyles.color(a.severity));
        ((TextView) v.findViewById(R.id.event)).setText(a.event);
        ((TextView) v.findViewById(R.id.area)).setText(a.areaDesc);
        ((TextView) v.findViewById(R.id.timing)).setText(timing(a));
        return v;
    }

    /**
     * How long is left, which is the thing an operator actually reads off a list of
     * alerts. An alert with no end time says so rather than inventing one.
     */
    public static String timing(Alert a) {
        final String sev = a.severity == null || a.severity.isEmpty() ? "" : a.severity;
        final long until = a.until();
        if (until <= 0)
            return sev + "  -  no end time given";
        final long left = until - System.currentTimeMillis();
        if (left <= 0)
            return sev + "  -  ended";
        return sev + "  -  " + remaining(left) + " left";
    }

    static String remaining(long ms) {
        final long minutes = ms / 60_000L;
        if (minutes < 60)
            return Math.max(1, minutes) + " min";
        final long hours = minutes / 60;
        if (hours < 24)
            return minutes % 60 == 0 ? hours + " h"
                    : String.format(Locale.US, "%d h %d min", hours, minutes % 60);
        final long days = hours / 24;
        return days == 1 ? "1 day" : days + " days";
    }
}

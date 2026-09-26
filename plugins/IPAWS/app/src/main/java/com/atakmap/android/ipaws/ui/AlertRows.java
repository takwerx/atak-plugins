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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/**
 * The alert list, grouped by event type.
 *
 * <p>The operator's words: "can alerts be categorized like Red Flag Warning and its
 * say 2, Fire Weather Watch 1, then you click on it and then get the list". Five
 * alerts where three of them say "Fire Weather Watch" is five rows saying almost
 * nothing; one row saying "Fire Weather Watch 3" is the picture, and the three are
 * there when they are wanted.
 *
 * <p>A group holding a single alert opens its details directly. Making somebody
 * expand a group of one to reach the only thing in it is a step that buys nothing.
 */
public class AlertRows extends BaseAdapter {

    private static final int TYPE_GROUP = 0;
    private static final int TYPE_CHILD = 1;

    private final Context pluginContext;

    /** Event name to its alerts, worst first, in the order the groups are shown. */
    private final Map<String, List<Alert>> groups = new LinkedHashMap<>();
    /** Event names currently expanded. Survives a refresh, so a poll does not collapse them. */
    private final Set<String> open = new HashSet<>();
    /** The flattened thing the ListView actually walks. */
    private final List<Object> rows = new ArrayList<>();

    /** A group header, which is a row in its own right. */
    public static class Group {
        public final String event;
        public final List<Alert> alerts;

        Group(String event, List<Alert> alerts) {
            this.event = event;
            this.alerts = alerts;
        }
    }

    public AlertRows(Context pluginContext) {
        this.pluginContext = pluginContext;
    }

    /**
     * The alerts arrive already sorted worst first, so grouping in encounter order
     * keeps the worst event type at the top without sorting again.
     */
    public void set(List<Alert> alerts) {
        groups.clear();
        if (alerts != null)
            for (Alert a : alerts) {
                List<Alert> g = groups.get(a.event);
                if (g == null) {
                    g = new ArrayList<>();
                    groups.put(a.event, g);
                }
                g.add(a);
            }
        // An event type that has gone should not stay open behind the scenes.
        open.retainAll(groups.keySet());
        flatten();
    }

    private void flatten() {
        rows.clear();
        for (Map.Entry<String, List<Alert>> e : groups.entrySet()) {
            rows.add(new Group(e.getKey(), e.getValue()));
            if (open.contains(e.getKey()))
                rows.addAll(e.getValue());
        }
        notifyDataSetChanged();
    }

    /**
     * What a tap on this row means.
     *
     * @return the alert to open, or null when the row was a group that toggled
     */
    public Alert onTap(int position) {
        final Object row = position >= 0 && position < rows.size() ? rows.get(position) : null;
        if (row instanceof Alert)
            return (Alert) row;
        if (!(row instanceof Group))
            return null;
        final Group g = (Group) row;
        if (g.alerts.size() == 1)
            return g.alerts.get(0);   // a group of one is just the alert
        if (!open.remove(g.event))
            open.add(g.event);
        flatten();
        return null;
    }

    @Override
    public int getCount() {
        return rows.size();
    }

    @Override
    public Object getItem(int position) {
        return position >= 0 && position < rows.size() ? rows.get(position) : null;
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public int getViewTypeCount() {
        return 2;
    }

    @Override
    public int getItemViewType(int position) {
        return getItem(position) instanceof Group ? TYPE_GROUP : TYPE_CHILD;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        final Object row = getItem(position);
        if (row instanceof Group)
            return groupView(convertView, (Group) row);
        return childView(convertView, (Alert) row);
    }

    private View groupView(View convertView, Group g) {
        View v = convertView;
        if (v == null)
            v = PluginLayoutInflater.inflate(pluginContext, R.layout.alert_group_row, null);
        v.findViewById(R.id.color_bar).setBackgroundColor(AlertStyles.color(g.event));
        ((TextView) v.findViewById(R.id.event)).setText(g.event);
        final TextView count = v.findViewById(R.id.count);
        final TextView expander = v.findViewById(R.id.expander);
        if (g.alerts.size() == 1) {
            // No count and no chevron: one of something is not a group to open, and
            // a "1" beside every single alert is noise on a busy list.
            count.setText("");
            expander.setText("");
        } else {
            count.setText(String.valueOf(g.alerts.size()));
            expander.setText(open.contains(g.event) ? "v" : ">");
        }
        return v;
    }

    private View childView(View convertView, Alert a) {
        View v = convertView;
        if (v == null)
            v = PluginLayoutInflater.inflate(pluginContext, R.layout.alert_child_row, null);
        if (a == null)
            return v;
        v.findViewById(R.id.color_bar).setBackgroundColor(AlertStyles.color(a.event));
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

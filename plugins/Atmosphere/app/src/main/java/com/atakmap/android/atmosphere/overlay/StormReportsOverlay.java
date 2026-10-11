package com.atakmap.android.atmosphere.overlay;

import android.content.Context;

import com.atakmap.android.atmosphere.compat.GeneratedFiles;
import com.atakmap.android.atmosphere.data.StormReports;
import com.atakmap.android.atmosphere.net.EgressPolicy;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.maps.assets.Icon;
import com.atakmap.map.layer.feature.AttributeSet;
import com.atakmap.map.layer.feature.geometry.Geometry;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Storm reports on the map: what the Weather Service offices logged in the last
 * three days, a dot per report in its family's color with the type and magnitude
 * in the pill. The day row is a window, 24, 48 or 72 hours, over the one file.
 *
 * <p>The national 72-hour layer is asked for whole every ten minutes, in pages of
 * 2,000 when a day of severe weather needs more than one. Nothing about the phone
 * leaves. Preliminary reports, as the offices received them; a survey comes later
 * and lands in Tornado Paths.
 */
public final class StormReportsOverlay extends OutlookOverlay {

    public static final String LAYER_ID = "reports";
    public static final String HOST = StormReports.HOST;
    private static final String TAG = "AtmosphereReports";
    private static final String[] WINDOWS = { "24 h", "48 h", "72 h" };

    /** The key: each family with its color. */
    public static final String[][] LEGEND;

    static {
        LEGEND = new String[StormReports.FAMILIES.length][];
        for (int i = 0; i < StormReports.FAMILIES.length; i++)
            LEGEND[i] = new String[] { StormReports.FAMILIES[i],
                    String.valueOf(StormReports.COLORS[i]) };
    }

    private final DotIcons icons;
    private final List<String> pages = new CopyOnWriteArrayList<>();
    private volatile int received;

    public StormReportsOverlay(MapView mapView, Context pluginContext, EgressPolicy egress) {
        super(mapView, pluginContext, egress, TAG, LAYER_ID, "Storm Reports");
        icons = new DotIcons(GeneratedFiles.icons("dots"));
        pages.add(StormReports.url(0));
    }

    @Override
    protected void onRefreshStart() {
        pages.clear();
        pages.add(StormReports.url(0));
        received = 0;
    }

    @Override
    protected String[] urls() {
        return pages.toArray(new String[0]);
    }

    @Override
    protected boolean optional(int index) {
        return index > 0;
    }

    @Override
    protected long pollMs() {
        return 10 * 60 * 1000L;
    }

    @Override
    public boolean allDays() {
        return false;
    }

    @Override
    public String dayLabel(int day) {
        return WINDOWS[Math.max(1, Math.min(3, day)) - 1];
    }

    @Override
    protected boolean shown(Area a, int showDay) {
        return a.day <= showDay;
    }

    @Override
    protected String setName(Area a) {
        return a.label;
    }

    @Override
    protected List<Area> parse(int index, String body) {
        final List<Area> out = new ArrayList<>();
        final List<StormReports.Report> got = StormReports.parse(body);
        received += got.size();
        if (!got.isEmpty() && StormReports.exceeded(body))
            pages.add(StormReports.url(received));
        final long now = System.currentTimeMillis();
        for (StormReports.Report r : got) {
            final long age = now - r.time;
            final int day = age <= StormReports.DAY_MS ? 1
                    : age <= 2 * StormReports.DAY_MS ? 2 : 3;
            final String where = r.place + (r.state.isEmpty() ? "" : ", " + r.state);
            final StringBuilder d = new StringBuilder();
            d.append(r.label()).append("\nWhere: ").append(where)
                    .append("\nWhen: ").append(when(r.time));
            if (!r.office.isEmpty())
                d.append("\nOffice: ").append(r.office);
            if (!r.remarks.isEmpty())
                d.append("\nRemarks: ").append(r.remarks);
            d.append("\nType as logged: ").append(r.type)
                    .append("\nFrom: NWS Local Storm Reports, preliminary");
            final JSONObject g = new JSONObject();
            try {
                g.put("type", "Point");
                g.put("coordinates", new JSONArray().put(r.lon).put(r.lat));
            } catch (Exception e) {
                continue;
            }
            out.add(new Area(day, r.label() + ", " + where, StormReports.FAMILIES[r.family],
                    StormReports.COLORS[r.family], g, d.toString(), "", r));
        }
        return out;
    }

    @Override
    protected AtmosphereFeatures.Drawn drawn(Area a, Geometry g, AttributeSet s) {
        if (!(a.extra instanceof StormReports.Report))
            return null;
        final StormReports.Report r = (StormReports.Report) a.extra;
        final Icon icon = icons.labelled(r.label(), a.color, 0xFFFFFFFF, 14);
        if (icon == null)
            return null;
        return new AtmosphereFeatures.Drawn(setName(a), a.title, g,
                AtmosphereFeatures.icon(icon.getImageUri(Icon.STATE_DEFAULT), icon.getWidth(),
                        icon.getHeight()), s);
    }

    @Override
    protected String summary(List<Area> areas) {
        final int window = day();
        final int[] counts = new int[StormReports.FAMILIES.length];
        int total = 0;
        long newest = 0;
        for (Area a : areas) {
            if (!shown(a, window) || !(a.extra instanceof StormReports.Report))
                continue;
            final StormReports.Report r = (StormReports.Report) a.extra;
            counts[r.family]++;
            total++;
            newest = Math.max(newest, r.time);
        }
        final StringBuilder b = new StringBuilder("Last ").append(dayLabel(window)).append(": ");
        if (total == 0)
            b.append("no reports logged.");
        else {
            final int[] order = { 0, 1, 2, 3, 4, 5, 6, 7 };
            boolean first = true;
            for (int i : order) {
                if (counts[i] == 0)
                    continue;
                b.append(first ? "" : ", ").append(counts[i]).append(' ')
                        .append(StormReports.FAMILIES[i].toLowerCase(Locale.US));
                first = false;
            }
            b.append(total == 1 ? " report." : " reports.");
            if (newest > 0)
                b.append(" Newest ").append(ago(newest)).append('.');
        }
        b.append("\nPreliminary, as spotters and the public told the offices; surveyed tornado"
                + " paths are the Tornado Paths layer.");
        return b.toString();
    }

    @Override
    protected String noun() {
        return "storm reports";
    }

    static String when(long millis) {
        return millis <= 0 ? "unknown"
                : new SimpleDateFormat("MMM d, h:mm a", Locale.US).format(new Date(millis));
    }

    static String ago(long millis) {
        final long m = Math.max(0, (System.currentTimeMillis() - millis) / 60000L);
        if (m < 60)
            return m + " min ago";
        final long h = m / 60;
        if (h < 48)
            return h + " h ago";
        return (h / 24) + " days ago";
    }
}

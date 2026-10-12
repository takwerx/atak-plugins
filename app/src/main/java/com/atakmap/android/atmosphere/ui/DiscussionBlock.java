package com.atakmap.android.atmosphere.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.atakmap.android.atmosphere.data.Afd;
import com.atakmap.android.atmosphere.data.Cwf;
import com.atakmap.android.atmosphere.net.EgressPolicy;
import com.atakmap.android.atmosphere.net.Http;
import com.atakmap.android.atmosphere.plugin.R;
import com.atakmap.coremap.maps.coords.GeoPoint;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The forecaster's discussion on the Forecast page: the Area Forecast Discussion
 * for the office covering the pane's point, its Synopsis open, every other
 * section a button that opens it in place (operator, 2026-10-11: "something we
 * can put that is sort of a synopsis ... on the forecast page"). The fire weather
 * zones page has the fire planning forecast's synopsis; this is the general one.
 *
 * <p>The office comes from the NWS points lookup by the half-degree cell the point
 * is in, the newest AFD from the products list; both every half hour at most.
 */
public final class DiscussionBlock {

    private static final long STALE_MS = 30 * 60 * 1000L;
    private static final double CELL = 0.5;

    public interface Host {
        GeoPoint point();

        String pointLabel();
    }

    private final Context pluginContext;
    private final EgressPolicy egress;
    private final Host host;
    private final LinearLayout container;
    private final TextView officeLine, status, synopsis;
    private final LinearLayout sections;
    private Afd.Discussion discussion;
    private String office = "", cell = "";
    private long fetchedAt;
    private int generation;
    /** Which sections the operator has opened, by name, kept across refreshes. */
    private final Set<String> open = new HashSet<>();

    public DiscussionBlock(Context pluginContext, EgressPolicy egress, LinearLayout container, Host host) {
        this.pluginContext = pluginContext;
        this.egress = egress;
        this.container = container;
        this.host = host;
        final TextView heading = new TextView(pluginContext);
        heading.setText(pluginContext.getString(R.string.heading_discussion));
        heading.setTextSize(10);
        heading.setAllCaps(true);
        heading.setAlpha(0.6f);
        heading.setPadding(0, dp(8), 0, 0);
        container.addView(heading);
        officeLine = text(13, true);
        officeLine.setTextColor(0xFFD0D0D0);
        container.addView(officeLine);
        status = text(13, false);
        container.addView(status);
        synopsis = text(14, false);
        synopsis.setLineSpacing(dp(2), 1f);
        synopsis.setPadding(0, dp(4), 0, 0);
        container.addView(synopsis);
        sections = new LinearLayout(pluginContext);
        sections.setOrientation(LinearLayout.VERTICAL);
        container.addView(sections);
    }

    /** The pane's point moved: another office, maybe. */
    public void pointChanged() {
        refresh(false);
    }

    public void refresh(boolean force) {
        final GeoPoint p = host.point();
        if (p == null)
            return;
        final double lat = Math.floor(p.getLatitude() / CELL) * CELL + CELL / 2;
        final double lon = Math.floor(p.getLongitude() / CELL) * CELL + CELL / 2;
        final String c = String.format(Locale.US, "%.2f,%.2f", lat, lon);
        if (!force && c.equals(cell) && System.currentTimeMillis() - fetchedAt < STALE_MS)
            return;
        final int mine = ++generation;
        if (discussion == null)
            status.setText("Getting the forecast discussion…");
        Http.get(Cwf.pointUrl(lat, lon), egress.userAgent(), new HashMap<String, String>(), new Http.Callback() {
            @Override
            public void onSuccess(String body) {
                if (mine != generation)
                    return;
                final String cwa = Cwf.office(body);
                if (cwa.isEmpty()) {
                    status.setText("No NWS office covers " + host.pointLabel().toLowerCase(Locale.US) + ".");
                    return;
                }
                cell = c;
                office = cwa;
                fetchLatest(mine);
            }

            @Override
            public void onFailure(String error) {
                if (mine == generation && discussion == null)
                    status.setText("Forecast discussion: " + error);
            }
        });
    }

    private void fetchLatest(final int mine) {
        Http.get(Cwf.latestUrl("AFD", office), egress.userAgent(), new HashMap<String, String>(), new Http.Callback() {
            @Override
            public void onSuccess(String body) {
                if (mine != generation)
                    return;
                final String id = Cwf.parseLatestId(body);
                if (id == null || id.isEmpty()) {
                    status.setText("The " + office + " office has no discussion posted.");
                    return;
                }
                Http.get(Cwf.productUrl(id), egress.userAgent(), new HashMap<String, String>(), new Http.Callback() {
                    @Override
                    public void onSuccess(String product) {
                        if (mine != generation)
                            return;
                        discussion = Afd.parse(Cwf.parseText(product));
                        fetchedAt = System.currentTimeMillis();
                        render();
                    }

                    @Override
                    public void onFailure(String error) {
                        if (mine == generation && discussion == null)
                            status.setText("Forecast discussion: " + error);
                    }
                });
            }

            @Override
            public void onFailure(String error) {
                if (mine == generation && discussion == null)
                    status.setText("Forecast discussion: " + error);
            }
        });
    }

    private void render() {
        sections.removeAllViews();
        if (discussion == null)
            return;
        status.setText("");
        status.setVisibility(View.GONE);
        officeLine.setText(com.atakmap.android.atmosphere.data.RainReports.clean(discussion.office)
                + (discussion.issued.isEmpty() ? "" : ", " + discussion.issued));
        final Afd.Section syn = discussion.synopsis();
        synopsis.setText(syn == null ? "" : syn.text);
        synopsis.setVisibility(syn == null ? View.GONE : View.VISIBLE);
        for (final Afd.Section s : discussion.sections) {
            // The watches and warnings list is IPAWS's, on the same map, and comes as a
            // bare list of zone codes here.
            if (s == syn || s.text.trim().isEmpty() || s.name.toUpperCase(Locale.US).contains("WATCHES"))
                continue;
            final Button b = (Button) LayoutInflater.from(pluginContext)
                    .inflate(R.layout.trend_chip, sections, false);
            final TextView body = text(14, false);
            body.setLineSpacing(dp(2), 1f);
            body.setPadding(dp(4), dp(4), dp(4), dp(8));
            body.setText(s.text + (s.stamp.isEmpty() ? "" : "\n\n(" + s.stamp + ")"));
            final boolean isOpen = open.contains(s.name);
            b.setText((isOpen ? "Hide " : "") + s.name);
            b.setTextSize(13);
            b.setTextColor(isOpen ? pluginContext.getResources().getColor(R.color.state_on) : Color.WHITE);
            body.setVisibility(isOpen ? View.VISIBLE : View.GONE);
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(4);
            sections.addView(b, lp);
            sections.addView(body);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    final boolean now = body.getVisibility() != View.VISIBLE;
                    body.setVisibility(now ? View.VISIBLE : View.GONE);
                    b.setText((now ? "Hide " : "") + s.name);
                    b.setTextColor(now ? pluginContext.getResources().getColor(R.color.state_on) : Color.WHITE);
                    if (now)
                        open.add(s.name);
                    else
                        open.remove(s.name);
                }
            });
        }
    }

    private TextView text(int sp, boolean bold) {
        final TextView t = new TextView(pluginContext);
        t.setTextSize(sp);
        t.setTextColor(Color.WHITE);
        if (bold)
            t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * pluginContext.getResources().getDisplayMetrics().density);
    }
}

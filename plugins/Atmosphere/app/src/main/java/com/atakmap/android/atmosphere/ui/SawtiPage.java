package com.atakmap.android.atmosphere.ui;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.atakmap.android.atmosphere.compat.MapCompat;
import com.atakmap.android.atmosphere.data.Sawti;
import com.atakmap.android.atmosphere.net.EgressPolicy;
import com.atakmap.android.atmosphere.net.Http;
import com.atakmap.android.atmosphere.overlay.SawtiOverlay;
import com.atakmap.android.atmosphere.plugin.R;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The SAWTI page: the Forest Service site's forecast page in the pane (operator,
 * 2026-09-30, showing the site: "can it show this page as well? like as a page?").
 * A zone picker opening on the zone the pane's point is in, four days with their
 * levels, the level, the three gauges, the forecaster's words, the recommended
 * actions and links, and the zones-by-days table, whose cells pick a zone and day.
 * A tap on a zone on the map opens the page on that zone and day.
 *
 * <p>Allowed once with the map layer (same server); fetched only while the page is
 * looked at, at most every half hour: the trouble flag, the forecast, and the model
 * numbers behind the wind and fuel gauges. Nothing about the phone's position is
 * sent; the zone is picked out on the phone.
 */
public final class SawtiPage {
    private static final String TAG = "AtmosphereSawtiPage";
    private static final String PREF_ZONE = "weather.sawti.zone";
    private static final long REFRESH_MS = 30 * 60 * 1000L;
    private static final long STALE_MS = 36L * 60 * 60 * 1000;
    /**
     * The wind and fuel file alone is asked again this soon after it failed: it
     * took over two minutes to answer at 11:20Z on 2026-10-01 and the phone gave up,
     * and waiting the full half hour left the gauges empty for no reason.
     */
    private static final long MODEL_RETRY_MS = 2 * 60 * 1000L;
    private static final int LINK_COLOR = 0xFF4FC3F7;

    /** What the page needs from the pane around it. */
    public interface Host {
        /** The point the pane is reading, or null. */
        GeoPoint point();
    }

    private final Context pluginContext;
    private final MapView mapView;
    private final EgressPolicy egress;
    private final Host host;
    private final View root, gate, body;
    private final Button zoneButton;
    private final LinearLayout dayRow, gaugeRow, links, table;
    private final TextView tile, meaning, event, actions, status;
    private final SawtiGaugeView threatGauge, windGauge, fuelGauge;
    private final double[][][] zones;
    private final float density;

    private Sawti.Forecast forecast;
    private Sawti.Model model;
    private boolean troubled, inFlight, pointDirty = true;
    private long fetchedAt, modelTriedAt;
    private int generation;
    private int zone;
    /** 1 to 4, the day showing. */
    private int day = 1;
    /** A date asked for (a map tap) before the forecast that holds it arrived. */
    private String pendingDate;
    private String error = "";

    public SawtiPage(Context pluginContext, MapView mapView, EgressPolicy egress, Host host) {
        this.pluginContext = pluginContext;
        this.mapView = mapView;
        this.egress = egress;
        this.host = host;
        density = pluginContext.getResources().getDisplayMetrics().density;
        root = LayoutInflater.from(pluginContext).inflate(R.layout.page_sawti, null);
        gate = root.findViewById(R.id.sawti_gate);
        body = root.findViewById(R.id.sawti_body);
        zoneButton = root.findViewById(R.id.sawti_zone);
        dayRow = root.findViewById(R.id.sawti_days);
        gaugeRow = root.findViewById(R.id.sawti_gauges);
        links = root.findViewById(R.id.sawti_links);
        table = root.findViewById(R.id.sawti_table);
        tile = root.findViewById(R.id.sawti_tile);
        meaning = root.findViewById(R.id.sawti_meaning);
        event = root.findViewById(R.id.sawti_event);
        actions = root.findViewById(R.id.sawti_actions);
        status = root.findViewById(R.id.sawti_status);
        ((TextView) root.findViewById(R.id.sawti_credit))
                .setText(pluginContext.getString(R.string.credit_sawti, Sawti.HOST));
        threatGauge = gauge(R.string.heading_sawti_threat, SawtiGaugeView.THREAT);
        windGauge = gauge(R.string.heading_sawti_wind, SawtiGaugeView.WIND);
        fuelGauge = gauge(R.string.heading_sawti_fuel, SawtiGaugeView.FUEL);
        zones = Sawti.parseZones(readAsset(Sawti.ZONES_ASSET));
        final SharedPreferences p = MapCompat.prefs();
        zone = p == null ? 3 : Math.max(1, Math.min(4, p.getInt(PREF_ZONE, 3)));

        root.findViewById(R.id.sawti_load).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                askToAllow();
            }
        });
        zoneButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickZone();
            }
        });
        showGateOrBody();
        render();
    }

    public View view() {
        return root;
    }

    /** The page came into view, or the pane opened on it. */
    public void onShown() {
        showGateOrBody();
        if (!egress.isLayerEnabled(SawtiOverlay.LAYER_ID))
            return;
        if (pointDirty)
            followPoint();
        fetch(false);
        render();
    }

    /** The pane is reading another point: the page follows it the next time it is looked at. */
    public void pointChanged(boolean pageShowing) {
        pointDirty = true;
        if (pageShowing && egress.isLayerEnabled(SawtiOverlay.LAYER_ID)) {
            followPoint();
            render();
        }
    }

    /** A zone tapped on the map: that zone, on the day the map was showing. */
    public void show(int zone, String date) {
        setZone(zone);
        pointDirty = false;
        pendingDate = date;
        resolvePendingDate();
        onShown();
        scrollToTop();
    }

    public void dispose() {
        generation++;
    }

    private void showGateOrBody() {
        final boolean allowed = egress.isLayerEnabled(SawtiOverlay.LAYER_ID);
        gate.setVisibility(allowed ? View.GONE : View.VISIBLE);
        body.setVisibility(allowed ? View.VISIBLE : View.GONE);
        status.setVisibility(allowed ? View.VISIBLE : View.GONE);
    }

    private void followPoint() {
        pointDirty = false;
        final GeoPoint p = host.point();
        if (p == null)
            return;
        final int z = Sawti.zoneAt(zones, p.getLatitude(), p.getLongitude());
        if (z > 0)
            setZone(z);
    }

    private void setZone(int z) {
        if (z < 1 || z > Sawti.ZONE_NAMES.length)
            return;
        zone = z;
        final SharedPreferences p = MapCompat.prefs();
        if (p != null)
            p.edit().putInt(PREF_ZONE, z).apply();
    }

    private void resolvePendingDate() {
        if (pendingDate == null || forecast == null)
            return;
        final int n = forecast.dayNumber(pendingDate);
        if (n >= 1 && n <= Sawti.DAYS)
            day = n;
        pendingDate = null;
    }

    // ---- fetching -----------------------------------------------------------------------

    private void fetch(boolean force) {
        if (inFlight)
            return;
        final long now = System.currentTimeMillis();
        if (!force && forecast != null && now - fetchedAt < REFRESH_MS) {
            if (model == null && !troubled && now - modelTriedAt > MODEL_RETRY_MS) {
                inFlight = true;
                fetchModel(++generation, false);
            }
            return;
        }
        inFlight = true;
        error = "";
        final int mine = ++generation;
        render();
        Http.get(Sawti.TROUBLE_URL, egress.userAgent(), null, new Http.Callback() {
            @Override
            public void onSuccess(String b) {
                if (mine != generation)
                    return;
                troubled = Sawti.troubled(b);
                fetchForecast(mine);
            }

            @Override
            public void onFailure(String e) {
                // The flag is a courtesy; the forecast's own issue time still says its age.
                if (mine != generation)
                    return;
                Log.w(TAG, "trouble flag: " + e);
                troubled = false;
                fetchForecast(mine);
            }
        });
    }

    private void fetchForecast(final int mine) {
        Http.get(Sawti.FORECAST_URL, egress.userAgent(), null, new Http.Callback() {
            @Override
            public void onSuccess(String b) {
                if (mine != generation)
                    return;
                final Sawti.Forecast f = Sawti.parse(b);
                if (f.days.isEmpty()) {
                    done(mine, "The SAWTI forecast came back empty.");
                    return;
                }
                forecast = f;
                resolvePendingDate();
                fetchModel(mine, true);
            }

            @Override
            public void onFailure(String e) {
                if (mine != generation)
                    return;
                done(mine, "Could not get SAWTI: " + e);
            }
        });
    }

    /** @param stamp whether this ends a whole refresh (the forecast's age restarts) or retries the model alone */
    private void fetchModel(final int mine, final boolean stamp) {
        modelTriedAt = System.currentTimeMillis();
        Http.get(Sawti.MODEL_URL, egress.userAgent(), null, new Http.Callback() {
            @Override
            public void onSuccess(String b) {
                if (mine != generation)
                    return;
                model = Sawti.parseModel(b);
                done(mine, "", stamp);
            }

            @Override
            public void onFailure(String e) {
                if (mine != generation)
                    return;
                Log.w(TAG, "model: " + e);
                model = null;
                done(mine, "", stamp);
            }
        });
    }

    private void done(int mine, String err) {
        done(mine, err, true);
    }

    private void done(int mine, String err, boolean stamp) {
        if (mine != generation)
            return;
        inFlight = false;
        error = err;
        if (stamp && err.isEmpty())
            fetchedAt = System.currentTimeMillis();
        render();
    }

    // ---- drawing ------------------------------------------------------------------------

    /**
     * Redraw from what is held, leaving the page where the operator scrolled it.
     * The day chips, links and table are rebuilt each time, and on the signed 0.8
     * (S22 Ultra, 2026-10-01) the page kept settling with the zone button under the
     * icon row and the heading out of sight, though the scroller takes focus
     * itself; so the position is put back after the rebuild as well.
     */
    private void render() {
        final int y = root instanceof ScrollView ? root.getScrollY() : 0;
        draw();
        if (root instanceof ScrollView)
            root.post(new Runnable() {
                @Override
                public void run() {
                    ((ScrollView) root).scrollTo(0, y);
                }
            });
    }

    private void scrollToTop() {
        if (root instanceof ScrollView)
            root.post(new Runnable() {
                @Override
                public void run() {
                    ((ScrollView) root).scrollTo(0, 0);
                }
            });
    }

    private void draw() {
        zoneButton.setText(Sawti.zoneTitle(zone) + "  ▾");
        final Sawti.Forecast f = troubled ? null : forecast;
        final List<String> dates = f == null ? null : f.dates();
        final int n = dates == null ? 0 : Math.min(Sawti.DAYS, dates.size());
        day = Math.max(1, Math.min(Math.max(1, n), day));
        buildDays(f, dates, n);
        final String date = n == 0 ? null : dates.get(day - 1);
        final Sawti.Day d = date == null ? null : f.find(zone, date);
        if (d == null) {
            tile.setVisibility(View.GONE);
            meaning.setText("");
            threatGauge.setValue(Double.NaN);
            windGauge.setValue(Double.NaN);
            fuelGauge.setValue(Double.NaN);
            event.setText("");
            actions.setText("");
        } else {
            tile.setVisibility(View.VISIBLE);
            tile.setText(Sawti.longDate(date) + ": " + Sawti.level(d.value));
            tile.setBackgroundColor(Sawti.color(d.value));
            tile.setTextColor(Sawti.textColor(d.value));
            meaning.setText(Sawti.meaning(d.value));
            threatGauge.setValue(d.value);
            windGauge.setValue(model == null ? Double.NaN : model.wind(zone, date));
            fuelGauge.setValue(model == null ? Double.NaN : model.fuel(zone, date));
            event.setText(d.description);
            actions.setText(d.actions);
        }
        buildLinks();
        buildTable(f, dates, n);
        status.setText(statusLine(f));
    }

    private String statusLine(Sawti.Forecast f) {
        if (troubled)
            return "The Forest Service's SAWTI site reports technical difficulties and is "
                    + "not showing its forecast, so neither is Atmosphere.";
        if (inFlight && f == null)
            return "Getting SAWTI…";
        if (f == null)
            return error.isEmpty() ? "" : error;
        final StringBuilder b = new StringBuilder();
        if (f.issued > 0) {
            b.append("Issued ").append(new SimpleDateFormat("EEE MMM d, h:mm a", Locale.US)
                    .format(new Date(f.issued))).append(", ").append(ago(f.issued)).append('.');
            if (System.currentTimeMillis() - f.issued > STALE_MS)
                b.append(" Over a day old: the Forest Service has not posted a newer one.");
        }
        if (model == null)
            b.append(b.length() > 0 ? " " : "").append("Wind and fuel moisture are not available.");
        if (!error.isEmpty())
            b.append(b.length() > 0 ? "\n" : "").append(error);
        return b.toString();
    }

    private void buildDays(final Sawti.Forecast f, List<String> dates, int n) {
        dayRow.removeAllViews();
        for (int i = 1; i <= n; i++) {
            final int value = i;
            final String date = dates.get(i - 1);
            final Sawti.Day d = f.find(zone, date);
            final Button b = (Button) LayoutInflater.from(pluginContext)
                    .inflate(R.layout.trend_chip, dayRow, false);
            b.setSingleLine(false);
            b.setMaxLines(2);
            b.setTextSize(13);
            final SpannableStringBuilder t = new SpannableStringBuilder(Sawti.shortDate(date));
            t.setSpan(new ForegroundColorSpan(i == day
                    ? pluginContext.getResources().getColor(R.color.state_on) : Color.WHITE),
                    0, t.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (d != null) {
                final int start = t.length();
                t.append('\n').append(Sawti.level(d.value));
                t.setSpan(new ForegroundColorSpan(Sawti.color(d.value)), start, t.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            b.setText(t);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    day = value;
                    render();
                }
            });
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = i < n ? dp(4) : 0;
            b.setLayoutParams(lp);
            dayRow.addView(b);
        }
    }

    private void buildLinks() {
        links.removeAllViews();
        final String[] l = Sawti.links(zone);
        for (int i = 0; i + 1 < l.length; i += 2) {
            final String url = l[i + 1];
            final TextView t = new TextView(pluginContext);
            t.setText(l[i]);
            t.setTextSize(14);
            t.setTextColor(LINK_COLOR);
            t.setPaintFlags(t.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
            t.setPadding(0, dp(6), 0, dp(6));
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    open(url);
                }
            });
            links.addView(t);
        }
    }

    /** Zone names down the side, the days across, each cell its level; a cell picks zone and day. */
    private void buildTable(Sawti.Forecast f, List<String> dates, int n) {
        table.removeAllViews();
        if (f == null || n == 0)
            return;
        final LinearLayout head = row();
        head.addView(cell("", 0, 0, false, 1.5f));
        for (int i = 1; i <= n; i++)
            head.addView(cell(Sawti.shortDate(dates.get(i - 1)), 0, Color.WHITE, false, 1f));
        table.addView(head);
        for (int z = 1; z <= Sawti.ZONE_NAMES.length; z++) {
            final LinearLayout r = row();
            final TextView name = cell(Sawti.zoneName(z), 0, Color.WHITE, false, 1.5f);
            name.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
            if (z == zone)
                name.setTypeface(null, android.graphics.Typeface.BOLD);
            r.addView(name);
            for (int i = 1; i <= n; i++) {
                final Sawti.Day d = f.find(z, dates.get(i - 1));
                final int v = d == null ? 0 : d.value;
                final TextView c = cell(d == null ? "" : Sawti.level(v), Sawti.color(v),
                        Sawti.textColor(v), z == zone && i == day, 1f);
                final int pickZone = z, pickDay = i;
                c.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        setZone(pickZone);
                        day = pickDay;
                        render();
                    }
                });
                r.addView(c);
            }
            table.addView(r);
        }
    }

    private LinearLayout row() {
        final LinearLayout r = new LinearLayout(pluginContext);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return r;
    }

    private TextView cell(String text, int fill, int textColor, boolean chosen, float weight) {
        final TextView t = new TextView(pluginContext);
        t.setText(text);
        t.setTextSize(11);
        t.setTextColor(textColor);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(36));
        t.setPadding(dp(2), dp(2), dp(2), dp(2));
        if (fill != 0) {
            final GradientDrawable g = new GradientDrawable();
            g.setColor(fill);
            g.setCornerRadius(dp(3));
            if (chosen)
                g.setStroke(dp(3), Color.WHITE);
            t.setBackground(g);
        }
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, weight);
        lp.setMargins(dp(1), dp(1), dp(1), dp(1));
        t.setLayoutParams(lp);
        return t;
    }

    private SawtiGaugeView gauge(int heading, int kind) {
        final LinearLayout col = new LinearLayout(pluginContext);
        col.setOrientation(LinearLayout.VERTICAL);
        final TextView h = new TextView(pluginContext);
        h.setText(heading);
        h.setTextSize(12);
        h.setTextColor(Color.WHITE);
        h.setGravity(Gravity.CENTER_HORIZONTAL);
        col.addView(h);
        final SawtiGaugeView g = new SawtiGaugeView(pluginContext, kind);
        col.addView(g, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = dp(4);
        gaugeRow.addView(col, lp);
        return g;
    }

    // ---- dialogs and links ----------------------------------------------------------------

    private void pickZone() {
        final Context ctx = MapCompat.atakContext();
        if (ctx == null)
            return;
        final String[] items = new String[Sawti.ZONE_NAMES.length];
        final Sawti.Forecast f = troubled ? null : forecast;
        final List<String> dates = f == null ? null : f.dates();
        final String date = dates == null || dates.size() < day ? null : dates.get(day - 1);
        for (int z = 1; z <= items.length; z++) {
            final Sawti.Day d = date == null ? null : f.find(z, date);
            items[z - 1] = Sawti.zoneTitle(z) + (d == null ? "" : " — " + Sawti.level(d.value));
        }
        new AlertDialog.Builder(ctx)
                .setTitle(pluginContext.getString(R.string.sawti_zone_pick))
                .setSingleChoiceItems(items, zone - 1, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        setZone(which + 1);
                        dialog.dismiss();
                        render();
                    }
                })
                .setNegativeButton(pluginContext.getString(R.string.close), null)
                .show();
    }

    private void askToAllow() {
        final Context ctx = MapCompat.atakContext();
        if (ctx == null)
            return;
        new AlertDialog.Builder(ctx)
                .setTitle(pluginContext.getString(R.string.sawti_allow_title))
                .setMessage(pluginContext.getString(R.string.sawti_page_allow_text))
                .setPositiveButton(pluginContext.getString(R.string.allow),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                egress.setLayerEnabled(SawtiOverlay.LAYER_ID, true);
                                onShown();
                                scrollToTop();
                            }
                        })
                .setNegativeButton(pluginContext.getString(R.string.close), null)
                .show();
    }

    /** One of the site's own links, in the phone's browser. */
    private void open(String url) {
        final Context ctx = MapCompat.atakContext();
        if (ctx == null)
            return;
        try {
            ctx.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "no browser for " + url);
        }
    }

    // ---- helpers --------------------------------------------------------------------------

    private String readAsset(String name) {
        try (InputStream in = pluginContext.getAssets().open(name)) {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0)
                out.write(buf, 0, r);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.w(TAG, "zone outlines would not load", e);
            return null;
        }
    }

    private static String ago(long at) {
        final long min = Math.max(0, (System.currentTimeMillis() - at) / 60000L);
        if (min < 60)
            return Math.max(1, min) + " min ago";
        if (min < 48 * 60)
            return (min / 60) + " h ago";
        return (min / (24 * 60)) + " days ago";
    }

    private int dp(int v) {
        return Math.round(v * density);
    }
}

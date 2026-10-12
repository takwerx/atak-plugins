package com.atakmap.android.atmosphere.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.atakmap.android.atmosphere.data.Cwf;
import com.atakmap.android.atmosphere.data.RainReports;
import com.atakmap.android.atmosphere.data.UsgsRain;
import com.atakmap.android.atmosphere.net.EgressPolicy;
import com.atakmap.android.atmosphere.net.Http;
import com.atakmap.android.atmosphere.overlay.RainGaugesOverlay;
import com.atakmap.android.atmosphere.plugin.R;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;

import com.atakmap.android.atmosphere.compat.GeneratedFiles;
import com.atakmap.android.atmosphere.data.Spot;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The Rain Gauges page: the Weather Forecast Office's own rainfall report for the
 * pane's point, parsed into rows and shown as written, and under it the USGS
 * gauges the map layer holds, most rain first, each a tap from the map.
 *
 * <p>The office comes from the NWS points lookup for the pane's point, which is
 * how the buoy page finds its office; the report is the newest RRM product. Only
 * some offices publish one, and the page says so when the one here does not.
 */
public final class RainPage {

    private static final String TAG = "AtmosphereRainPage";
    private static final long STALE_MS = 10 * 60 * 1000L;
    private static final double OFFICE_CELL = 0.5;

    public interface Host {
        GeoPoint point();

        String pointLabel();

        void askToAllowGauges();

        void setGaugesOn(boolean on);
    }

    private final Context pluginContext;
    private final MapView mapView;
    private final EgressPolicy egress;
    private final Host host;
    private final ScrollView root;
    private final TextView officeLine, periodLine, status, reportText;
    private final Button sortWritten, sortMost, showReport;
    private final LinearLayout rows, gaugeRows;
    private final TextView gaugeStatus;
    private final Button gaugeButton;
    private final HorizontalScrollView reportScroll;
    private RainGaugesOverlay gauges;
    private boolean mostFirst = true;
    /**
     * Where each gauge id is, looked up once through the NWS station service and
     * kept on disk: the report names gauges, it does not place them (operator,
     * 2026-10-11: "cant i order these by closest to myself or map center"). An id
     * the service does not know is remembered for the session only.
     */
    private final Map<String, double[]> located = new HashMap<>();
    private final Set<String> unknown = new HashSet<>();
    private final ArrayDeque<String> toLocate = new ArrayDeque<>();
    private boolean locating;
    private int sinceSaved;
    private boolean reportOpen;
    private RainReports.Report report;
    /** The office whose report is showing, and the half-degree cell it was found for. */
    private String office = "";
    private String officeCell = "";
    private long fetchedAt;
    private int generation;

    public RainPage(Context pluginContext, MapView mapView, EgressPolicy egress, Host host) {
        this.pluginContext = pluginContext;
        this.mapView = mapView;
        this.egress = egress;
        this.host = host;
        root = new ScrollView(pluginContext);
        final LinearLayout body = new LinearLayout(pluginContext);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, 0, 0, dp(8));
        root.addView(body);

        body.addView(heading(pluginContext.getString(R.string.rain_office_heading)));
        officeLine = text(15, true);
        officeLine.setPadding(0, dp(4), 0, 0);
        body.addView(officeLine);
        periodLine = text(13, false);
        periodLine.setTextColor(0xFFD0D0D0);
        body.addView(periodLine);
        final LinearLayout sort = row();
        sortMost = chip("Most rain first");
        sortWritten = chip("Closest");
        sort.addView(sortMost, weight(1f, true));
        sort.addView(sortWritten, weight(1f, false));
        body.addView(sort);
        status = text(13, false);
        status.setPadding(0, dp(6), 0, 0);
        body.addView(status);
        rows = new LinearLayout(pluginContext);
        rows.setOrientation(LinearLayout.VERTICAL);
        body.addView(rows);
        showReport = chip("Show the report as written");
        body.addView(showReport, matchWidth(8));
        reportScroll = new HorizontalScrollView(pluginContext);
        reportText = new TextView(pluginContext);
        reportText.setTypeface(Typeface.MONOSPACE);
        reportText.setTextSize(12);
        reportText.setTextColor(Color.WHITE);
        reportText.setTextIsSelectable(true);
        reportScroll.addView(reportText);
        reportScroll.setVisibility(View.GONE);
        body.addView(reportScroll, matchWidth(4));

        body.addView(heading(pluginContext.getString(R.string.rain_gauges_heading)));
        gaugeStatus = text(13, false);
        gaugeStatus.setPadding(0, dp(4), 0, 0);
        body.addView(gaugeStatus);
        gaugeButton = chip("");
        gaugeButton.setVisibility(View.GONE);
        body.addView(gaugeButton, matchWidth(6));
        gaugeRows = new LinearLayout(pluginContext);
        gaugeRows.setOrientation(LinearLayout.VERTICAL);
        body.addView(gaugeRows);

        sortMost.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mostFirst = true;
                renderReport();
            }
        });
        sortWritten.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mostFirst = false;
                renderReport();
            }
        });
        showReport.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                reportOpen = !reportOpen;
                reportScroll.setVisibility(reportOpen ? View.VISIBLE : View.GONE);
                showReport.setText(reportOpen ? "Hide the report" : "Show the report as written");
            }
        });
        gaugeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!egress.isLayerEnabled(RainGaugesOverlay.LAYER_ID))
                    host.askToAllowGauges();
                else
                    host.setGaugesOn(true);
                renderGauges();
            }
        });
        loadLocations();
        renderReport();
        renderGauges();
    }

    public View view() {
        return root;
    }

    public void setGauges(RainGaugesOverlay o) {
        gauges = o;
        renderGauges();
    }

    /** The gauge layer fetched or moved; with it on, the office's report is wanted too. */
    public void onGaugesChanged() {
        renderGauges();
        if (gauges != null && gauges.isOn())
            fetchReport(false);
    }

    /** Hand every placed gauge of the report to the map layer. */
    private void pushReportGauges() {
        if (gauges == null)
            return;
        final List<RainGaugesOverlay.ReportGauge> out = new ArrayList<>();
        if (report != null) {
            final StringBuilder period = new StringBuilder();
            for (String l : report.period)
                period.append(period.length() > 0 ? " " : "").append(l);
            final Set<String> seen = new HashSet<>();
            for (RainReports.Section sec : report.sections)
                for (RainReports.Row r : sec.rows) {
                    final double[] at = place(r);
                    if (at == null || !seen.add(r.id))
                        continue;
                    final StringBuilder d = new StringBuilder();
                    d.append(r.values.length == 1 ? r.values[0] + (Double.isNaN(r.inches) ? " (trace)" : " in")
                            : join(" ", r.values))
                            .append(period.length() > 0 ? "\n" + period : "")
                            .append('\n').append(r.name);
                    if (!r.id.isEmpty())
                        d.append("\nGauge id: ").append(r.id);
                    if (r.elevationFt != Integer.MIN_VALUE)
                        d.append("\nElevation: ").append(String.format(Locale.US, "%,d ft", r.elevationFt));
                    d.append("\nFrom: ").append(RainReports.clean(report.office)).append(" rainfall report, ")
                            .append(report.issued).append(", preliminary");
                    out.add(new RainGaugesOverlay.ReportGauge(r.name, at[0], at[1], r.inches, d.toString()));
                }
        }
        gauges.setReportGauges(office, out);
    }

    /** The pane's point moved: another office, maybe. */
    public void pointChanged() {
        fetchReport(false);
        if (!mostFirst)
            renderReport();
    }

    public void onShown() {
        fetchReport(false);
        renderGauges();
        if (gauges != null && gauges.isOn())
            gauges.refresh(false);
    }

    public void dispose() {
        generation++;
    }

    // ---- the office's report --------------------------------------------------------------

    private void fetchReport(boolean force) {
        final GeoPoint p = host.point();
        if (p == null) {
            status.setText("No position yet for " + host.pointLabel().toLowerCase(Locale.US) + ".");
            return;
        }
        // The office is looked up by the half-degree cell the point is in, the way the
        // zones page does: about 30 miles across, never the point itself.
        final double lat = Math.floor(p.getLatitude() / OFFICE_CELL) * OFFICE_CELL + OFFICE_CELL / 2;
        final double lon = Math.floor(p.getLongitude() / OFFICE_CELL) * OFFICE_CELL + OFFICE_CELL / 2;
        final String cell = String.format(Locale.US, "%.2f,%.2f", lat, lon);
        if (!force && cell.equals(officeCell) && System.currentTimeMillis() - fetchedAt < STALE_MS)
            return;
        final int mine = ++generation;
        if (!cell.equals(officeCell))
            status.setText("Finding the office for " + host.pointLabel().toLowerCase(Locale.US) + "…");
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
                officeCell = cell;
                office = cwa;
                fetchLatest(mine);
            }

            @Override
            public void onFailure(String error) {
                if (mine == generation)
                    status.setText("Could not find the office: " + error);
            }
        });
    }

    private void fetchLatest(final int mine) {
        status.setText("Getting the " + office + " office's rainfall report…");
        Http.get(Cwf.latestUrl("RRM", office), egress.userAgent(), new HashMap<String, String>(), new Http.Callback() {
            @Override
            public void onSuccess(String body) {
                if (mine != generation)
                    return;
                final String id = Cwf.parseLatestId(body);
                if (id == null || id.isEmpty()) {
                    report = null;
                    fetchedAt = System.currentTimeMillis();
                    officeLine.setText("Office " + office);
                    periodLine.setText("");
                    rows.removeAllViews();
                    reportText.setText("");
                    status.setText("The " + office + " office publishes no rainfall report. Thirty-three"
                            + " offices do; the map layer and the Rain Totals picture cover the rest.");
                    return;
                }
                Http.get(Cwf.productUrl(id), egress.userAgent(), new HashMap<String, String>(), new Http.Callback() {
                    @Override
                    public void onSuccess(String product) {
                        if (mine != generation)
                            return;
                        report = RainReports.parse(Cwf.parseText(product));
                        fetchedAt = System.currentTimeMillis();
                        queueLocations();
                        renderReport();
                        pushReportGauges();
                    }

                    @Override
                    public void onFailure(String error) {
                        if (mine == generation)
                            status.setText("Rainfall report: " + error);
                    }
                });
            }

            @Override
            public void onFailure(String error) {
                if (mine == generation)
                    status.setText("Rainfall report: " + error);
            }
        });
    }

    private void renderReport() {
        green(sortMost, mostFirst);
        green(sortWritten, !mostFirst);
        rows.removeAllViews();
        if (report == null) {
            showReport.setVisibility(View.GONE);
            reportScroll.setVisibility(View.GONE);
            return;
        }
        showReport.setVisibility(View.VISIBLE);
        reportText.setText(report.text);
        officeLine.setText(RainReports.clean(report.office.isEmpty() ? "Office " + office : report.office)
                + (report.issued.isEmpty() ? "" : ", " + report.issued));
        final StringBuilder period = new StringBuilder();
        for (String l : report.period)
            period.append(period.length() > 0 ? " " : "").append(l);
        periodLine.setText(period.toString());
        final int n = report.rowCount();
        if (n == 0) {
            status.setText("The report has no gauge table in it; read it as written below.");
            return;
        }
        final GeoPoint from = host.point();
        int placed = 0;
        for (RainReports.Section sec : report.sections)
            for (RainReports.Row r : sec.rows)
                if (place(r) != null)
                    placed++;
        status.setText(n + (n == 1 ? " gauge" : " gauges") + " in the " + office + " office's report"
                + (report.columns.length > 0 ? ", columns " + join(report.columns) : "")
                + (mostFirst ? ", most rain first." : ", closest to " + host.pointLabel().toLowerCase(Locale.US) + " first.")
                + (locating ? " Placing the gauges, " + placed + " so far\u2026" : ""));
        if (!mostFirst) {
            if (from == null) {
                status.setText("No position yet for " + host.pointLabel().toLowerCase(Locale.US) + ".");
                return;
            }
            final List<RainReports.Row> near = new ArrayList<>(), far = new ArrayList<>();
            for (RainReports.Section sec : report.sections)
                for (RainReports.Row r : sec.rows)
                    (place(r) != null ? near : far).add(r);
            final Set<String> seen = new HashSet<>();
            final Iterator<RainReports.Row> it = near.iterator();
            while (it.hasNext())
                if (!seen.add(it.next().name.toUpperCase(Locale.US)))
                    it.remove();
            Collections.sort(near, new Comparator<RainReports.Row>() {
                @Override
                public int compare(RainReports.Row a, RainReports.Row b) {
                    return Double.compare(distance(a, from), distance(b, from));
                }
            });
            for (RainReports.Row r : near)
                rows.addView(reportRow(r, distanceLabel(distance(r, from))));
            if (!far.isEmpty()) {
                rows.addView(heading(far.size() + " the report gives no location for"));
                final Set<String> seen2 = new HashSet<>();
                for (RainReports.Row r : far)
                    if (seen2.add(r.name.toUpperCase(Locale.US)))
                        rows.addView(reportRow(r, ""));
            }
            return;
        }
        if (mostFirst) {
            final List<RainReports.Row> all = new ArrayList<>();
            for (RainReports.Section s : report.sections)
                all.addAll(s.rows);
            Collections.sort(all, new Comparator<RainReports.Row>() {
                @Override
                public int compare(RainReports.Row a, RainReports.Row b) {
                    final double x = Double.isNaN(a.inches) ? -1 : a.inches;
                    final double y = Double.isNaN(b.inches) ? -1 : b.inches;
                    return Double.compare(y, x);
                }
            });
            // The office's own "top amounts" list repeats rows below it: one per name.
            final List<String> seen = new ArrayList<>();
            for (RainReports.Row r : all) {
                final String key = r.name.toUpperCase(Locale.US);
                if (seen.contains(key))
                    continue;
                seen.add(key);
                final double[] at = place(r);
                rows.addView(reportRow(r, at == null || from == null ? "" : distanceLabel(distance(r, from))));
            }
        }
    }

    // ---- placing the report's gauges ------------------------------------------------------

    /** The gauge's place, or null when nothing has placed it yet. */
    private double[] place(RainReports.Row r) {
        return r.id.isEmpty() ? null : located.get(r.id);
    }

    private double distance(RainReports.Row r, GeoPoint from) {
        final double[] at = place(r);
        return at == null ? Double.MAX_VALUE
                : Spot.distanceMeters(from.getLatitude(), from.getLongitude(), at[0], at[1]);
    }

    private String distanceLabel(double meters) {
        if (meters == Double.MAX_VALUE)
            return "";
        final double mi = meters / 1609.344;
        return (mi < 10 ? String.format(Locale.US, "%.1f", mi) : String.valueOf(Math.round(mi))) + " mi";
    }

    private File locationsFile() {
        final File root = GeneratedFiles.root();
        return root == null ? null : new File(root, "gauge-locations.json");
    }

    private void loadLocations() {
        final File f = locationsFile();
        if (f == null || !f.isFile())
            return;
        try {
            final java.io.FileInputStream in = new java.io.FileInputStream(f);
            final byte[] b = new byte[(int) f.length()];
            int n = 0;
            while (n < b.length) {
                final int k = in.read(b, n, b.length - n);
                if (k < 0)
                    break;
                n += k;
            }
            in.close();
            final JSONObject o = new JSONObject(new String(b, 0, n, "UTF-8"));
            final Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                final String id = keys.next();
                final org.json.JSONArray a = o.optJSONArray(id);
                if (a != null && a.length() == 2)
                    located.put(id, new double[] { a.getDouble(0), a.getDouble(1) });
            }
        } catch (Exception e) {
            Log.w(TAG, "gauge locations unreadable; starting over", e);
        }
    }

    private void saveLocations() {
        final File f = locationsFile();
        if (f == null)
            return;
        try {
            final JSONObject o = new JSONObject();
            for (Map.Entry<String, double[]> e : located.entrySet())
                o.put(e.getKey(), new org.json.JSONArray().put(e.getValue()[0]).put(e.getValue()[1]));
            //noinspection ResultOfMethodCallIgnored
            f.getParentFile().mkdirs();
            final File tmp = new File(f.getPath() + ".tmp");
            final FileOutputStream out = new FileOutputStream(tmp);
            out.write(o.toString().getBytes("UTF-8"));
            out.close();
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(f);
        } catch (Exception e) {
            Log.w(TAG, "could not save gauge locations", e);
        }
    }

    /** Every id in the report that nothing has placed goes on the queue, once. */
    private void queueLocations() {
        if (report == null)
            return;
        for (RainReports.Section sec : report.sections)
            for (RainReports.Row r : sec.rows)
                if (!r.id.isEmpty() && !located.containsKey(r.id) && !unknown.contains(r.id)
                        && !toLocate.contains(r.id))
                    toLocate.add(r.id);
        locateNext();
    }

    /** One lookup at a time, through the NWS station service; airports need a K in front. */
    private void locateNext() {
        if (locating || toLocate.isEmpty())
            return;
        final String id = toLocate.poll();
        locating = true;
        lookup(id, id.length() == 3 && !id.matches(".*\\d.*") ? "K" + id : id, true);
    }

    private void lookup(final String id, final String station, final boolean retryBare) {
        Http.get("https://" + Cwf.HOST + "/stations/" + station, egress.userAgent(),
                new HashMap<String, String>(), new Http.Callback() {
                    @Override
                    public void onSuccess(String body) {
                        double[] at;
                        try {
                            final org.json.JSONArray c = new JSONObject(body).getJSONObject("geometry")
                                    .getJSONArray("coordinates");
                            at = new double[] { c.getDouble(1), c.getDouble(0) };
                        } catch (Exception e) {
                            at = null;
                        }
                        if (at != null) {
                            located.put(id, at);
                            if (++sinceSaved >= 10) {
                                sinceSaved = 0;
                                saveLocations();
                            }
                        } else {
                            unknown.add(id);
                        }
                        done();
                    }

                    @Override
                    public void onFailure(String error) {
                        if (retryBare && !station.equals(id)) {
                            lookup(id, id, false);
                            return;
                        }
                        unknown.add(id);
                        done();
                    }

                    private void done() {
                        locating = false;
                        if (toLocate.isEmpty()) {
                            saveLocations();
                            sinceSaved = 0;
                            renderReport();
                            pushReportGauges();
                        } else {
                            if (toLocate.size() % 10 == 0) {
                                renderReport();
                                pushReportGauges();
                            }
                            locateNext();
                        }
                    }
                });
    }

    private View reportRow(RainReports.Row r, String distance) {
        final LinearLayout row = new LinearLayout(pluginContext);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(4), dp(5), dp(4), dp(5));
        row.setLayoutParams(matchWidth(0));
        final LinearLayout col = new LinearLayout(pluginContext);
        col.setOrientation(LinearLayout.VERTICAL);
        row.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        final TextView name = text(14, false);
        name.setText(r.name);
        col.addView(name);
        final String sub = join(" \u00b7 ", distance, r.id, r.elevationFt == Integer.MIN_VALUE ? ""
                : String.format(Locale.US, "%,d ft", r.elevationFt));
        if (!sub.isEmpty()) {
            final TextView s = text(11, false);
            s.setTextColor(0xFFA0A0A0);
            s.setText(sub);
            col.addView(s);
        }
        final TextView values = text(14, true);
        values.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        values.setGravity(android.view.Gravity.END);
        final StringBuilder v = new StringBuilder();
        for (int i = 0; i < r.values.length; i++)
            v.append(i > 0 ? "  " : "").append(String.format(Locale.US, "%5s", r.values[i]));
        values.setText(v.toString());
        values.setTextColor(Double.isNaN(r.inches) ? 0xFFA0A0A0 : RainGaugesOverlay.color(r.inches));
        row.addView(values);
        return row;
    }

    // ---- the USGS gauges ------------------------------------------------------------------

    private void renderGauges() {
        gaugeRows.removeAllViews();
        final boolean allowed = egress.isLayerEnabled(RainGaugesOverlay.LAYER_ID);
        final boolean on = gauges != null && gauges.isOn();
        if (!allowed || !on) {
            gaugeStatus.setText(allowed ? pluginContext.getString(R.string.raingauges_off_text)
                    : pluginContext.getString(R.string.raingauges_gate_text));
            gaugeButton.setText(allowed ? pluginContext.getString(R.string.raingauges_switch_on)
                    : pluginContext.getString(R.string.raingauges_load));
            gaugeButton.setVisibility(View.VISIBLE);
            return;
        }
        gaugeButton.setVisibility(View.GONE);
        if (gauges.zoomedOut()) {
            gaugeStatus.setText("Zoom the map in to see the USGS gauges around it.");
            return;
        }
        final List<UsgsRain.Gauge> list = gauges.gauges();
        if (list.isEmpty()) {
            gaugeStatus.setText("No USGS rain gauges around the map.");
            return;
        }
        gaugeStatus.setText(list.size() + (list.size() == 1 ? " USGS gauge" : " USGS gauges")
                + " around the map, last 24 h, most rain first. Tap one to go to it.");
        for (final UsgsRain.Gauge g : list) {
            final LinearLayout row = new LinearLayout(pluginContext);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setBackgroundResource(R.drawable.btn_gray);
            row.setPadding(dp(10), dp(6), dp(10), dp(8));
            row.setClickable(true);
            row.setLayoutParams(matchWidth(4));
            final View dot = new View(pluginContext);
            dot.setBackgroundColor(RainGaugesOverlay.color(g.total));
            final LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(dp(14), dp(14));
            dp.rightMargin = dp(10);
            dp.topMargin = dp(5);
            row.addView(dot, dp);
            final LinearLayout col = new LinearLayout(pluginContext);
            col.setOrientation(LinearLayout.VERTICAL);
            row.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            final TextView t = text(15, true);
            t.setText(RainGaugesOverlay.inches(g.total));
            col.addView(t);
            final TextView n = text(13, false);
            n.setTextColor(0xFFD0D0D0);
            n.setText(g.name);
            col.addView(n);
            final TextView go = text(13, true);
            go.setText(pluginContext.getString(R.string.go_to));
            go.setTextColor(pluginContext.getResources().getColor(R.color.state_on));
            go.setPadding(dp(8), dp(4), 0, 0);
            row.addView(go);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        mapView.getMapController().panZoomTo(new GeoPoint(g.lat, g.lon),
                                mapView.mapResolutionAsMapScale(20d), true);
                    } catch (LinkageError | RuntimeException e) {
                        Log.w(TAG, "panZoomTo failed; plain pan", e);
                        mapView.getMapController().panTo(new GeoPoint(g.lat, g.lon), true);
                    }
                }
            });
            gaugeRows.addView(row);
        }
    }

    // ---- widgets ---------------------------------------------------------------------------

    private static String join(String[] parts) {
        final StringBuilder b = new StringBuilder();
        for (String p : parts)
            b.append(b.length() > 0 ? " " : "").append(p);
        return b.toString();
    }

    private static String join(String sep, String... parts) {
        final StringBuilder b = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isEmpty())
                continue;
            if (b.length() > 0)
                b.append(sep);
            b.append(p);
        }
        return b.toString();
    }

    private TextView heading(String words) {
        final TextView t = new TextView(pluginContext);
        t.setText(words);
        t.setTextSize(10);
        t.setAllCaps(true);
        t.setAlpha(0.6f);
        t.setPadding(0, dp(8), 0, 0);
        return t;
    }

    private TextView text(int sp, boolean bold) {
        final TextView t = new TextView(pluginContext);
        t.setTextSize(sp);
        t.setTextColor(Color.WHITE);
        if (bold)
            t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button chip(String words) {
        final Button b = (Button) LayoutInflater.from(pluginContext)
                .inflate(R.layout.trend_chip, null, false);
        b.setText(words);
        return b;
    }

    private LinearLayout row() {
        final LinearLayout r = new LinearLayout(pluginContext);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setLayoutParams(matchWidth(4));
        return r;
    }

    private LinearLayout.LayoutParams weight(float w, boolean gapRight) {
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, w);
        if (gapRight)
            lp.rightMargin = dp(4);
        return lp;
    }

    private LinearLayout.LayoutParams matchWidth(int topDp) {
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp);
        return lp;
    }

    private void green(Button b, boolean on) {
        b.setTextColor(on ? pluginContext.getResources().getColor(R.color.state_on) : Color.WHITE);
    }

    private int dp(int v) {
        return Math.round(v * pluginContext.getResources().getDisplayMetrics().density);
    }
}

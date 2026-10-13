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
import com.atakmap.android.atmosphere.data.Raws;
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
import java.util.LinkedHashMap;
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

        /** Whether NWS is the picked forecast service: the office lookups go to its host. */
        boolean nwsPicked();

        void askToAllowGauges();

        void setGaugesOn(boolean on);
    }

    private final Context pluginContext;
    private final MapView mapView;
    private final EgressPolicy egress;
    private final Host host;
    private final ScrollView root;
    /** The list, and the one gauge opened from it; one shows at a time. */
    private final LinearLayout body, detail;
    private final TextView detailTitle, detailFacts;
    private Runnable detailGoTo;
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
        final LinearLayout page = new LinearLayout(pluginContext);
        page.setOrientation(LinearLayout.VERTICAL);
        root.addView(page);
        body = new LinearLayout(pluginContext);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, 0, 0, dp(8));
        page.addView(body);
        // One gauge: Back and Go to, the name, then what is known about it (operator,
        // 2026-10-12: "I said go and it just hovered over a map and I don't know which
        // one is which at that zoom level").
        detail = new LinearLayout(pluginContext);
        detail.setOrientation(LinearLayout.VERTICAL);
        detail.setPadding(0, 0, 0, dp(8));
        detail.setVisibility(View.GONE);
        final LinearLayout bar = row();
        final Button back = chip(pluginContext.getString(R.string.back));
        final Button goTo = chip(pluginContext.getString(R.string.go_to));
        bar.addView(back, weight(1f, true));
        bar.addView(goTo, weight(1f, false));
        detail.addView(bar);
        detailTitle = text(17, true);
        detailTitle.setPadding(0, dp(8), 0, 0);
        detail.addView(detailTitle);
        detailFacts = text(14, false);
        detailFacts.setPadding(0, dp(4), 0, 0);
        detailFacts.setLineSpacing(dp(2), 1f);
        detail.addView(detailFacts);
        page.addView(detail);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeDetail();
            }
        });
        goTo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (detailGoTo != null)
                    detailGoTo.run();
            }
        });

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
        // The office and its report come from api.weather.gov, the forecast's own host
        // only while NWS is the picked service; picking it is the allow (security
        // review, 2026-10-11).
        if (!host.nwsPicked()) {
            status.setText("Pick NWS as the forecast service in Forecast settings to see the"
                    + " office's rainfall report.");
            return;
        }
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
                if (!cwa.equals(office))
                    closeDetail();
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
                        // The product is free text from 33 offices; a parser slip reads as
                        // "unreadable", never as ATAK going down on the main thread.
                        try {
                            report = RainReports.parse(Cwf.parseText(product));
                        } catch (RuntimeException e) {
                            Log.w(TAG, "rainfall report unreadable", e);
                            status.setText("The " + office + " office's report could not be read.");
                            return;
                        }
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
        // Plain words first, the office's line after: "2 DAY PRECIPITATION TOTALS AS OF
        // 1234 PM SUNDAY" read as a season to the operator (2026-10-11: "thats like the
        // last how much time for the year?").
        periodLine.setText("Inches of rain, each gauge's own total for the period the office"
                + " names: " + (period.length() > 0 ? period : "see the report as written"));
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

    /**
     * The gauge's place, or null when nothing has placed it yet. A row with no id (the
     * office's "top amounts" list) takes the id of the row with the same name below it.
     */
    private double[] place(RainReports.Row r) {
        String id = r.id.isEmpty() ? idByName(r.name) : r.id;
        if ((id == null || id.isEmpty()) && isRawsRow(r))
            id = rawsKey(r);
        return id == null || id.isEmpty() ? null : located.get(id);
    }

    /** A row the office wrote as "... RAWS"; NIFC knows it by that name even with no id. */
    private static boolean isRawsRow(RainReports.Row r) {
        return r.name.toUpperCase(Locale.US).trim().endsWith(" RAWS");
    }

    /** The key a RAWS row with no id is placed under: its name, never sent to NWS. */
    private static String rawsKey(RainReports.Row r) {
        return "RAWS:" + r.name.toUpperCase(Locale.US).trim();
    }

    private RainReports.Report indexedReport;
    private final Map<String, String> idByName = new HashMap<>();

    private String idByName(String name) {
        if (report == null)
            return null;
        if (indexedReport != report) {
            idByName.clear();
            for (RainReports.Section sec : report.sections)
                for (RainReports.Row r : sec.rows)
                    if (!r.id.isEmpty() && !idByName.containsKey(r.name.toUpperCase(Locale.US)))
                        idByName.put(r.name.toUpperCase(Locale.US), r.id);
            indexedReport = report;
        }
        return idByName.get(name.toUpperCase(Locale.US));
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

    private boolean batching;

    /**
     * Every id in the report that nothing has placed, in two passes: the rows written
     * "... RAWS" against NIFC's RAWS by station name, in batches, then NWS's station
     * service one id at a time for the rest. NWS places about half of San Diego's
     * report, RAWS included once the lookups get to them (operator, 2026-10-11: "how do
     * you not know where vista grande raws is"); the Cal OES station extract was tried
     * for the rest on 2026-10-12 and every id it knew NWS knew too, so it is not asked.
     * What stays unplaced is the counties' own flood-control gauges.
     */
    private void queueLocations() {
        if (report == null || batching)
            return;
        final Map<String, String> pending = new LinkedHashMap<>();
        for (RainReports.Section sec : report.sections)
            for (RainReports.Row r : sec.rows) {
                final String key = !r.id.isEmpty() ? r.id : isRawsRow(r) ? rawsKey(r) : "";
                if (!key.isEmpty() && !located.containsKey(key) && !unknown.contains(key)
                        && !toLocate.contains(key) && !pending.containsKey(key))
                    pending.put(key, r.name.toUpperCase(Locale.US).trim());
            }
        if (pending.isEmpty())
            return;
        batching = true;
        placeRaws(pending, new Runnable() {
            @Override
            public void run() {
                batching = false;
                toLocate.addAll(pending.keySet());
                placed();
                locateNext();
            }
        });
    }

    /** A position a service answered that can be drawn and saved: finite and on the globe. */
    private static boolean placeable(double lat, double lon) {
        return !Double.isNaN(lat) && !Double.isNaN(lon) && !Double.isInfinite(lat)
                && !Double.isInfinite(lon) && Math.abs(lat) <= 90 && Math.abs(lon) <= 180;
    }

    /** After a batch placed some gauges: on disk, in the list, on the map. */
    private void placed() {
        saveLocations();
        sinceSaved = 0;
        renderReport();
        pushReportGauges();
    }

    private double meters(GeoPoint from, double lat, double lon) {
        return from == null ? 0 : Spot.distanceMeters(from.getLatitude(), from.getLongitude(), lat, lon);
    }

    private static final int RAWS_NAMES_PER_QUERY = 40;

    /** The "... RAWS" rows by name; a name on more than one NIFC station takes the nearest. */
    private void placeRaws(final Map<String, String> pending, final Runnable then) {
        final Map<String, List<String>> byName = new HashMap<>();
        for (Map.Entry<String, String> e : pending.entrySet()) {
            final String n = e.getValue();
            if (!n.endsWith(" RAWS"))
                continue;
            final String key = n.substring(0, n.length() - 5).trim();
            if (key.isEmpty())
                continue;
            List<String> ids = byName.get(key);
            if (ids == null)
                byName.put(key, ids = new ArrayList<>());
            ids.add(e.getKey());
        }
        Log.d(TAG, "placing " + pending.size() + " gauge ids, " + byName.size()
                + " RAWS names to ask NIFC for first");
        if (byName.isEmpty()) {
            then.run();
            return;
        }
        rawsBatch(new ArrayList<>(byName.keySet()), 0, byName, pending, then);
    }

    private void rawsBatch(final List<String> names, final int from,
            final Map<String, List<String>> byName, final Map<String, String> pending,
            final Runnable then) {
        if (from >= names.size()) {
            then.run();
            return;
        }
        final int to = Math.min(names.size(), from + RAWS_NAMES_PER_QUERY);
        final GeoPoint p = host.point();
        Http.get(Raws.byNameUrl(names.subList(from, to)), egress.userAgent(),
                new HashMap<String, String>(), new Http.Callback() {
                    @Override
                    public void onSuccess(String body) {
                        final Map<String, Raws.Station> best = new HashMap<>();
                        for (Raws.Station s : Raws.parse(body)) {
                            if (!placeable(s.latitude, s.longitude))
                                continue;
                            final String key = s.name.toUpperCase(Locale.US).trim();
                            final Raws.Station have = best.get(key);
                            if (have == null || meters(p, s.latitude, s.longitude)
                                    < meters(p, have.latitude, have.longitude))
                                best.put(key, s);
                        }
                        for (Map.Entry<String, Raws.Station> e : best.entrySet()) {
                            final List<String> ids = byName.get(e.getKey());
                            if (ids == null)
                                continue;
                            for (String id : ids) {
                                located.put(id, new double[] { e.getValue().latitude,
                                        e.getValue().longitude });
                                pending.remove(id);
                            }
                        }
                        Log.d(TAG, "NIFC placed " + best.size() + " of " + (to - from)
                                + " RAWS names; " + pending.size() + " ids left");
                        if (!best.isEmpty())
                            placed();
                        rawsBatch(names, to, byName, pending, then);
                    }

                    @Override
                    public void onFailure(String error) {
                        rawsBatch(names, to, byName, pending, then);
                    }
                });
    }

    /** One lookup at a time, through the NWS station service; airports need a K in front. */
    private void locateNext() {
        if (locating || toLocate.isEmpty())
            return;
        final String id = toLocate.poll();
        if (id.startsWith("RAWS:")) {
            // A name NIFC did not have: nothing else can place it by name.
            unknown.add(id);
            locateNext();
            return;
        }
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
        if (r.values.length == 1)
            v.append(Double.isNaN(r.inches) ? (r.values[0].equals("T") ? " trace" : "") : " in");
        values.setText(v.toString());
        values.setTextColor(Double.isNaN(r.inches) ? 0xFFA0A0A0 : RainGaugesOverlay.color(r.inches));
        row.addView(values);
        // Once the gauge is placed, a green Go to under the name, and the row flies there
        // (operator, 2026-10-11: "the little green Go to under it so I can go to the gauge").
        final double[] at = place(r);
        if (at != null) {
            final TextView go = text(13, true);
            go.setText(pluginContext.getString(R.string.go_to));
            go.setTextColor(pluginContext.getResources().getColor(R.color.state_on));
            go.setPadding(0, dp(2), 0, 0);
            col.addView(go);
            row.setClickable(true);
            row.setBackgroundResource(R.drawable.btn_gray);
            final String dist = distance;
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    flyTo(at[0], at[1]);
                    openDetail(r.name, reportFacts(r, dist), new Runnable() {
                        @Override
                        public void run() {
                            flyTo(at[0], at[1]);
                        }
                    });
                }
            });
        }
        return row;
    }

    /** What the office's report says about one gauge, in lines. */
    private String reportFacts(RainReports.Row r, String distance) {
        final StringBuilder b = new StringBuilder();
        if (r.values.length == 1)
            b.append(Double.isNaN(r.inches) ? (r.values[0].equals("T") ? "A trace" : r.values[0])
                    : r.values[0] + " in");
        else
            b.append(join(" ", r.values));
        if (report != null && !report.period.isEmpty())
            b.append('\n').append(join(" ", report.period.toArray(new String[0])));
        if (!distance.isEmpty())
            b.append("\nDistance: ").append(distance);
        if (!r.id.isEmpty())
            b.append("\nGauge id: ").append(r.id);
        if (r.elevationFt != Integer.MIN_VALUE)
            b.append("\nElevation: ").append(String.format(Locale.US, "%,d ft", r.elevationFt));
        b.append("\nFrom the ").append(office.isEmpty() ? "forecast office's" : office + " office's")
                .append(" rainfall report, as written.");
        return b.toString();
    }

    private void openDetail(String title, String facts, Runnable goTo) {
        detailTitle.setText(title);
        detailFacts.setText(facts);
        detailGoTo = goTo;
        body.setVisibility(View.GONE);
        detail.setVisibility(View.VISIBLE);
        root.scrollTo(0, 0);
    }

    private void closeDetail() {
        detailGoTo = null;
        detail.setVisibility(View.GONE);
        body.setVisibility(View.VISIBLE);
    }

    /** Pans and zooms the map to a gauge, falling back to a plain pan. */
    private void flyTo(double lat, double lon) {
        try {
            mapView.getMapController().panZoomTo(new GeoPoint(lat, lon),
                    mapView.mapResolutionAsMapScale(8d), true);
        } catch (LinkageError | RuntimeException e) {
            Log.w(TAG, "panZoomTo failed; plain pan", e);
            mapView.getMapController().panTo(new GeoPoint(lat, lon), true);
        }
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
                    flyTo(g.lat, g.lon);
                    openDetail(g.name, RainGaugesOverlay.details(g), new Runnable() {
                        @Override
                        public void run() {
                            flyTo(g.lat, g.lon);
                        }
                    });
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
        t.setTextSize(12f);
        t.setAllCaps(true);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(0xFF00E5FF);
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

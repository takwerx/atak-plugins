package com.atakmap.android.sitrep;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.atakmap.android.importexport.send.SendDialog;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.missionpackage.api.MissionPackageApi;
import com.atakmap.android.missionpackage.file.MissionPackageManifest;
import com.atakmap.android.sitrep.plugin.R;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.Ellipsoid;
import com.atakmap.coremap.maps.coords.MGRSPoint;

import org.json.JSONObject;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Share a saved report as a PDF (takwerx/sit-rep#1, ops#786): a read-only snapshot
 * of one area at one time, in an ATAK data package, handed to ATAK's own send
 * dialog (contacts, teams, servers). Nothing here sends anything itself.
 *
 * <p>The report is copied on main, where its sections change, and everything after
 * that (layout, drawing, writing) runs on the manager's share worker, with a
 * dialog saying how far it has got. No network is used.
 */
public final class ReportShare {

    private static final String TAG = "SitRep";
    /** Old PDFs and packages in the share folder are removed after this long. */
    private static final long KEEP_MS = 7L * 24 * 3600 * 1000;

    private final MapView mapView;
    private final Context plugin;
    private final SitRepManager manager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean busy;

    public ReportShare(MapView mapView, Context plugin, SitRepManager manager) {
        this.mapView = mapView;
        this.plugin = plugin;
        this.manager = manager;
    }

    /** Starts the share; call on main. */
    public void share(Report r) {
        if (busy)
            return;
        if (r.loading() > 0) {
            new AlertDialog.Builder(mapView.getContext()).setTitle("Share as PDF")
                    .setMessage(r.loading() + " section" + (r.loading() == 1 ? " is" : "s are")
                            + " still loading. Share what the report has now, or wait for them?")
                    .setPositiveButton("Share now", new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface d, int which) {
                            start(r);
                        }
                    })
                    .setNegativeButton("Wait", null).show();
            return;
        }
        start(r);
    }

    private void start(Report r) {
        final String json;
        final ReportDoc.Facts f;
        try {
            json = r.toJson().toString();
            f = facts(r);
        } catch (Exception e) {
            Log.w(TAG, "could not copy the report for a PDF", e);
            failed();
            return;
        }
        final String base = ReportDoc.fileName(r.label != null ? r.label : r.place, f.now, f.zone);
        final File dir = new File(manager.folder(), "share");
        final File pdf = new File(dir, base);

        final TextView say = new TextView(mapView.getContext());
        say.setText("Making the PDF...");
        say.setTextSize(15);
        final ProgressBar bar = new ProgressBar(mapView.getContext());
        bar.setIndeterminate(true);
        final LinearLayout box = new LinearLayout(mapView.getContext());
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(android.view.Gravity.CENTER_VERTICAL);
        final int pad = (int) (16 * mapView.getContext().getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);
        box.addView(bar);
        say.setPadding(pad, 0, 0, 0);
        box.addView(say);
        final AlertDialog progress = new AlertDialog.Builder(mapView.getContext()).setTitle("Share as PDF")
                .setView(box).setCancelable(false).create();
        progress.show();
        busy = true;

        final boolean queued = manager.shareWork(new Runnable() {
            @Override
            public void run() {
                Throwable error = null;
                try {
                    sweep(dir);
                    final Report copy = Report.fromJson(new JSONObject(json));
                    if (copy == null)
                        throw new IllegalStateException("the report copy did not read back");
                    final ReportDoc doc = ReportDoc.build(copy, f);
                    final List<PdfLayout.Page> pages = PdfLayout.layout(doc, new ReportPdf.AndroidMeasure());
                    ReportPdf.write(pages, pdf, new ReportPdf.Progress() {
                        @Override
                        public void page(final int done, final int of) {
                            main.post(new Runnable() {
                                @Override
                                public void run() {
                                    say.setText("Drawing page " + done + " of " + of + "...");
                                }
                            });
                        }
                    });
                    Log.d(TAG, "PDF made: " + pages.size() + " pages, " + pdf.length() + " bytes, "
                            + doc.cuiLeftOut + " CUI rows and " + doc.contactsLeftOut + " shelter contact details left out");
                } catch (Throwable e) {
                    error = e;
                    Log.w(TAG, "could not make the PDF", e);
                }
                final boolean ok = error == null;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        busy = false;
                        try {
                            progress.dismiss();
                        } catch (RuntimeException e) {
                            // the pane was closed under it
                        }
                        if (ok)
                            send(pdf, base.substring(0, base.length() - 4));
                        else
                            failed();
                    }
                });
            }
        });
        if (!queued) {
            busy = false;
            progress.dismiss();
        }
    }

    /** The PDF in a data package, to ATAK's own send dialog. */
    private void send(File pdf, String name) {
        try {
            final MissionPackageManifest mpm = MissionPackageApi.CreateTempManifest(name, true, false, null);
            mpm.setPath(new File(pdf.getParentFile(), name + ".zip").getAbsolutePath());
            if (!mpm.addFile(pdf, null))
                throw new IllegalStateException("the data package did not take the PDF");
            Drawable icon = null;
            try {
                icon = plugin.getResources().getDrawable(R.drawable.ic_launcher);
            } catch (RuntimeException e) {
                // no icon then
            }
            final SendDialog.Builder b = new SendDialog.Builder(mapView).setName(name).setMissionPackage(mpm);
            if (icon != null)
                b.setIcon(icon);
            b.show();
        } catch (RuntimeException e) {
            Log.w(TAG, "could not open the send dialog", e);
            failed();
        }
    }

    private void failed() {
        new AlertDialog.Builder(mapView.getContext()).setTitle("Share as PDF")
                .setMessage("The PDF could not be made. Try again; if it happens again, report it from the plugin's page.")
                .setPositiveButton("OK", null).show();
    }

    /** The values only ATAK knows, as text; on main. */
    private ReportDoc.Facts facts(Report r) {
        final ReportDoc.Facts f = new ReportDoc.Facts();
        f.now = System.currentTimeMillis();
        f.zone = TimeZone.getDefault();
        final double lat = (r.area.south + r.area.north) / 2, lon = (r.area.west + r.area.east) / 2;
        f.latLon = String.format(Locale.US, "%.5f, %.5f", lat, lon);
        try {
            final MGRSPoint m = MGRSPoint.fromLatLng(Ellipsoid.WGS_84, lat, lon, null);
            f.mgrs = m.getZoneDescriptor() + " " + m.getGridDescriptor() + " " + m.getEastingDescriptor() + " "
                    + m.getNorthingDescriptor();
            // Plain spaces only: ATAK's own MGRS string carries invisible marks.
            f.mgrs = f.mgrs.replaceAll("[^0-9A-Za-z ]", "").trim();
        } catch (RuntimeException e) {
            f.mgrs = null;
        }
        f.size = Units.area(r.area.squareMeters());
        f.radius = Units.distance(ReportDoc.radiusMeters(r.area.ring, lat, lon));
        f.callsign = mapView.getDeviceCallsign();
        f.version = version();
        return f;
    }

    /** {@code 0.9}: the plugin's version name up to its first space. */
    private String version() {
        try {
            final String v = plugin.getPackageManager().getPackageInfo(plugin.getPackageName(), 0).versionName;
            if (v == null)
                return "";
            final int sp = v.indexOf(' ');
            return sp > 0 ? v.substring(0, sp) : v;
        } catch (Exception e) {
            return "";
        }
    }

    /** Shares older than a week go, so the folder does not grow. */
    private static void sweep(File dir) {
        final File[] files = dir.listFiles();
        if (files == null)
            return;
        final long cut = System.currentTimeMillis() - KEEP_MS;
        for (File f : files) {
            final String n = f.getName();
            if (f.isFile() && n.startsWith("SitRep_") && f.lastModified() < cut
                    && (n.endsWith(".pdf") || n.endsWith(".zip") || n.endsWith(".tmp")))
                f.delete();
        }
    }
}

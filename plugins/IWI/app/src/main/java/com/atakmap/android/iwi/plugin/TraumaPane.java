package com.atakmap.android.iwi.plugin;


import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.android.util.ATAKUtilities;
import com.atakmap.coremap.maps.coords.DirectionType;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.coremap.maps.coords.NorthReference;

import java.util.List;
import java.util.Locale;

/**
 * The "nearest trauma center" drop-down, its two Settings rows and its details page.
 *
 * <p>A planning reference for a crew in the middle of nowhere, not a destination
 * choice: whether and where a patient flies is the air crew's lane, and every number
 * here says "estimate". The pane that holds it owns the pages; this fills them.
 */
public final class TraumaPane implements TraumaManager.Listener {

    /** Where the line measures from, and what to call it. */
    public interface PointSource {
        /** The IWI's point, else the self marker's, else null. */
        GeoPoint point();

        /** "from the patient" or "from My Location". */
        String label();
    }

    /** The pane's pages and status line. */
    public interface Pages {
        void showMain();

        void showDetails();

        void status(String message);
    }

    private final MapView mapView;
    private final TraumaManager manager;
    private final PointSource source;
    private final Pages pages;

    private final Fold fold;

    /** After Clear: the trauma center row closed again. */
    void closeFold() {
        fold.setOpen(false);
    }
    private final TextView line1;
    private final TextView line2;
    private final TextView note;
    private final Button speedButton;
    private final TextView dataDetail;
    private final Button downloadButton;
    /** The download's progress and result, under the button that started it. */
    private final TextView dataResult;
    private final TextView detailsTitle;
    private final TextView detailsSubtitle;
    private final TextView detailsBody;
    private final Fold speedFold;
    private final Fold dataFold;

    private TraumaFinder.Result first;
    private TraumaFinder.Result better;
    private TraumaFinder.Result showing;

    public TraumaPane(MapView mapView, View root, View settingsRoot, TraumaManager manager,
            PointSource source, Pages pages) {
        this.mapView = mapView;
        this.manager = manager;
        this.source = source;
        this.pages = pages;
        final SharedPreferences prefs = manager.prefs();

        fold = new Fold(root, R.id.iwi_fold_trauma_head, R.id.iwi_fold_trauma_chev,
                R.id.iwi_fold_trauma_body, prefs, "iwi.fold.trauma");
        line1 = root.findViewById(R.id.iwi_trauma_line1);
        line2 = root.findViewById(R.id.iwi_trauma_line2);
        note = root.findViewById(R.id.iwi_trauma_note);
        speedButton = settingsRoot.findViewById(R.id.iwi_btn_cruise_speed);
        dataDetail = settingsRoot.findViewById(R.id.iwi_data_detail);
        downloadButton = settingsRoot.findViewById(R.id.iwi_btn_download);
        dataResult = settingsRoot.findViewById(R.id.iwi_data_result);
        detailsTitle = root.findViewById(R.id.iwi_details_title);
        detailsSubtitle = root.findViewById(R.id.iwi_details_subtitle);
        detailsBody = root.findViewById(R.id.iwi_details_body);

        root.findViewById(R.id.iwi_btn_details_back).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showing = null;
                        TraumaPane.this.pages.showMain();
                    }
                });
        root.findViewById(R.id.iwi_btn_go_to).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (showing != null)
                    TraumaPane.this.mapView.getMapController()
                            .panTo(showing.center.target(), true);
            }
        });
        line1.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (first != null)
                    showDetails(first);
            }
        });
        line2.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (better != null)
                    showDetails(better);
            }
        });
        speedButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickSpeed();
            }
        });
        downloadButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                downloadButton.setEnabled(false);
                showResult("Downloading trauma center data...");
                TraumaPane.this.pages.status("Downloading trauma center data...");
                TraumaPane.this.manager.download();
            }
        });

        speedFold = new Fold(settingsRoot, R.id.iwi_fold_speed_head, R.id.iwi_fold_speed_chev,
                R.id.iwi_fold_speed_body, prefs, "iwi.fold.speed");
        dataFold = new Fold(settingsRoot, R.id.iwi_fold_data_head, R.id.iwi_fold_data_chev,
                R.id.iwi_fold_data_body, prefs, "iwi.fold.data");

        manager.setListener(this);
        refresh();
        refreshSettings();
    }

    // ---- the drop-down ----

    /** Recompute the head and both lines from the reference point. Main thread. */
    public void refresh() {
        final List<TraumaCenter> centers = manager.centers();
        final GeoPoint from = source.point();
        first = null;
        better = null;
        line2.setVisibility(View.GONE);

        if (centers == null || centers.isEmpty()) {
            fold.label("Nearest Trauma Center", "no data on this device");
            line1.setText("No trauma center data on this device");
            note.setText(manager.busy() ? "Downloading..." : "Settings > Trauma Center Data");
            return;
        }
        if (from == null) {
            fold.label("Nearest Trauma Center", "no position yet");
            line1.setText("No position yet");
            note.setText("Needs a declared IWI or a GPS fix.");
            return;
        }
        first = TraumaFinder.nearest(centers, from);
        if (first == null) {
            fold.label("Nearest Trauma Center",
                    "none within " + Units.distance(TraumaFinder.CAP_M));
            line1.setText("None within " + Units.distance(TraumaFinder.CAP_M) + " "
                    + source.label());
            note.setText(estimateNote());
            return;
        }
        fold.label("Nearest Trauma Center", first.center.levelLabel() + ", "
                + Units.distance(first.meters) + " " + cardinal(first.bearing));
        line1.setText(lineText(first));
        better = TraumaFinder.nearestBetter(centers, from, first.center.level);
        if (better != null) {
            line2.setText(lineText(better));
            line2.setVisibility(View.VISIBLE);
        }
        note.setText(estimateNote());
    }

    private String estimateNote() {
        return capitalize(source.label()) + ". Straight-line estimate at "
                + manager.cruiseKt() + " kt. Tap a line for details. Where a patient "
                + "flies is the air crew's call.";
    }

    /** "Riverside Community Hospital (Level II), 15 mi NE, about 6 min by air (estimate)". */
    String lineText(TraumaFinder.Result r) {
        final TraumaCenter c = r.center;
        return c.name + " (" + c.levelLabel() + "), " + Units.distance(r.meters) + " "
                + cardinal(r.bearing) + ", " + minutesText(r.meters) + " (estimate)";
    }

    private String minutesText(double meters) {
        final int min = TraumaFinder.minutes(meters, manager.cruiseKt());
        if (min < 1)
            return "under 1 min by air";
        return "about " + min + " min by air";
    }

    private static String cardinal(double bearingTrue) {
        try {
            final DirectionType d = DirectionType.getDirection(bearingTrue);
            if (d != null && d != DirectionType.UNKNOWN)
                return d.getAbbreviation();
        } catch (RuntimeException e) {
            // Fall through to the number.
        }
        return Math.round(bearingTrue) + "°";
    }

    // ---- the details page ----

    private void showDetails(TraumaFinder.Result r) {
        showing = r;
        final TraumaCenter c = r.center;
        detailsTitle.setText(c.name);
        detailsSubtitle.setText(c.levelLabel() + " trauma center");
        detailsBody.setText(detailsText(r));
        pages.showDetails();
    }

    private String detailsText(TraumaFinder.Result r) {
        final TraumaCenter c = r.center;
        final StringBuilder b = new StringBuilder();
        if (!c.city.isEmpty() || !c.state.isEmpty())
            b.append(c.city).append(c.city.isEmpty() ? "" : ", ").append(c.state)
                    .append("\n\n");
        if (!c.levelText.isEmpty() && c.levelText.contains(","))
            b.append(c.levelText).append("\n\n");
        b.append(Units.distance(r.meters)).append(' ').append(cardinal(r.bearing))
                .append(' ').append(source.label())
                .append(", bearing ").append(bearingText(r.bearing)).append('\n');
        b.append(capitalize(minutesText(r.meters))).append(" at ")
                .append(manager.cruiseKt()).append(" kt, straight line.\n");
        b.append("Estimate only. Whether and where a patient flies is the air crew's call.\n\n");
        if (c.padCharted) {
            b.append("Helipad charted by the FAA");
            if (!c.padId.isEmpty())
                b.append(": ").append(c.padId);
            if (!c.padName.isEmpty())
                b.append(' ').append(c.padName);
            if (c.padElevFt >= 0)
                b.append(", ").append(c.padElevFt).append(" ft");
            b.append(".\nThe distance above is to the pad.\n");
        } else {
            b.append("Helipad not charted by the FAA. The hospital reports a pad; ")
                    .append("the distance above is to the hospital.\n");
        }
        final TraumaPack.Index index = manager.index();
        b.append("\nTrauma centers from HIFLD hospitals");
        if (index.hospitalsDate.length() >= 4)
            b.append(" (").append(index.hospitalsDate.substring(0, 4)).append(')');
        b.append("; helipads from FAA NASR");
        final String cycle = TraumaManager.date(index.cycle);
        if (!cycle.isEmpty())
            b.append(", cycle ").append(cycle);
        b.append('.');
        return b.toString();
    }

    /** The bearing in ATAK's own north reference, "047° M" or "047° T". */
    private String bearingText(double bearingTrue) {
        final GeoPoint from = source.point();
        double shown = bearingTrue;
        String abbrev = "T";
        try {
            final SharedPreferences atak = PreferenceManager
                    .getDefaultSharedPreferences(mapView.getContext());
            final int ref = Integer.parseInt(atak.getString("rab_north_ref_pref",
                    String.valueOf(NorthReference.MAGNETIC.getValue())));
            if (ref == NorthReference.MAGNETIC.getValue() && from != null) {
                shown = ATAKUtilities.convertFromTrueToMagnetic(from, bearingTrue);
                abbrev = NorthReference.MAGNETIC.getAbbrev();
            } else {
                abbrev = NorthReference.TRUE.getAbbrev();
            }
        } catch (RuntimeException e) {
            // A malformed preference leaves it true.
        }
        shown = (shown % 360 + 360) % 360;
        return String.format(Locale.US, "%03d° %s", Math.round(shown), abbrev);
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ---- settings ----

    public void refreshSettings() {
        speedFold.label("Helicopter Cruise Speed", manager.cruiseKt() + " kt");
        speedButton.setText(manager.cruiseKt() + " kt");
        final TraumaPack.Index index = manager.index();
        final boolean have = manager.centers() != null && !manager.centers().isEmpty();
        dataFold.label("Trauma Center Data", have ? "on this device" : "none on this device");
        dataDetail.setText(have ? TraumaManager.describe(index)
                : "No trauma center data on this device. Download it once; after that the line works with no network.");
        downloadButton.setEnabled(!manager.busy());
        downloadButton.setText(have ? "Check for an Update" : "Download Now");
    }

    private void showResult(String message) {
        final boolean has = message != null && !message.isEmpty();
        dataResult.setText(has ? message : "");
        dataResult.setVisibility(has ? View.VISIBLE : View.GONE);
    }

    private void pickSpeed() {
        final int[] presets = TraumaManager.CRUISE_PRESETS_KT;
        final String[] labels = new String[presets.length];
        int checked = -1;
        for (int i = 0; i < presets.length; i++) {
            labels[i] = presets[i] + " kt";
            if (presets[i] == manager.cruiseKt())
                checked = i;
        }
        new AlertDialog.Builder(mapView.getContext())
                .setTitle("Helicopter Cruise Speed")
                .setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        manager.setCruiseKt(presets[which]);
                        dialog.dismiss();
                        refreshSettings();
                        refresh();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---- manager callbacks, main thread ----

    @Override
    public void onPackChanged() {
        refresh();
        refreshSettings();
    }

    @Override
    public void onStatus(String message) {
        showResult(message);
        pages.status(message);
        downloadButton.setEnabled(true);
    }

    /** The self marker's point when it is a real fix. */
    static GeoPoint selfPoint(MapView mapView) {
        final Marker self = mapView.getSelfMarker();
        if (self == null)
            return null;
        final GeoPoint p = self.getPoint();
        if (p == null || !p.isValid() || (p.getLatitude() == 0 && p.getLongitude() == 0))
            return null;
        return p;
    }
}

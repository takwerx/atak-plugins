package com.atakmap.android.iwi.plugin;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
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
 * The "nearest trauma center" line, its Settings rows and its details page.
 *
 * <p>One block the Report face and the Manage face both include. It is a planning
 * reference for a crew in the middle of nowhere, not a destination choice: whether and
 * where a patient flies is the air crew's lane, and every number here says "estimate".
 *
 * <p>The pages are the one pane by visibility swap, the way IPAWS does it: the main
 * page, a Settings page with Back, a details page with Back. Every dialog is built on
 * the MapView's context, never the plugin's.
 */
public final class TraumaPane implements TraumaManager.Listener {

    /** Where the line measures from, and what to call it. */
    public interface PointSource {
        /** The incident marker's point, else the self marker's, else null. */
        GeoPoint point();

        /** "from the incident" or "from My Location". */
        String label();
    }

    private final MapView mapView;
    private final TraumaManager manager;
    private final PointSource source;
    private final SharedPreferences prefs;

    private final View mainPage;
    private final View settingsPage;
    private final View detailsPage;
    private final TextView heading;
    private final TextView line1;
    private final TextView line2;
    private final TextView note;
    private final TextView status;
    private final Button speedButton;
    private final TextView dataDetail;
    private final Button downloadButton;
    private final TextView detailsTitle;
    private final TextView detailsSubtitle;
    private final TextView detailsBody;
    private final Fold speedFold;
    private final Fold dataFold;

    private TraumaFinder.Result first;
    private TraumaFinder.Result better;
    private TraumaFinder.Result showing;

    public TraumaPane(MapView mapView, View root, TraumaManager manager, PointSource source) {
        this.mapView = mapView;
        this.manager = manager;
        this.source = source;
        this.prefs = manager.prefs();

        mainPage = root.findViewById(R.id.main_page);
        settingsPage = root.findViewById(R.id.settings_page);
        detailsPage = root.findViewById(R.id.details_page);
        heading = root.findViewById(R.id.trauma_heading);
        line1 = root.findViewById(R.id.trauma_line1);
        line2 = root.findViewById(R.id.trauma_line2);
        note = root.findViewById(R.id.trauma_note);
        status = root.findViewById(R.id.status);
        speedButton = root.findViewById(R.id.btn_cruise_speed);
        dataDetail = root.findViewById(R.id.data_detail);
        downloadButton = root.findViewById(R.id.btn_download);
        detailsTitle = root.findViewById(R.id.details_title);
        detailsSubtitle = root.findViewById(R.id.details_subtitle);
        detailsBody = root.findViewById(R.id.details_body);

        root.findViewById(R.id.btn_settings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSettings();
            }
        });
        root.findViewById(R.id.btn_settings_back).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showMain();
                    }
                });
        root.findViewById(R.id.btn_details_back).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showMain();
                    }
                });
        root.findViewById(R.id.btn_go_to).setOnClickListener(new View.OnClickListener() {
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
                status("Downloading trauma center data...");
                TraumaPane.this.manager.download();
            }
        });

        speedFold = new Fold(root, R.id.fold_speed_head, R.id.fold_speed_chev,
                R.id.fold_speed_body, "iwi.fold.speed");
        dataFold = new Fold(root, R.id.fold_data_head, R.id.fold_data_chev,
                R.id.fold_data_body, "iwi.fold.data");

        manager.setListener(this);
        refresh();
    }

    /** The pane opens on the main page every time. */
    public void onShown() {
        showMain();
        refresh();
    }

    // ---- pages ----

    private void showMain() {
        settingsPage.setVisibility(View.GONE);
        detailsPage.setVisibility(View.GONE);
        mainPage.setVisibility(View.VISIBLE);
        showing = null;
    }

    private void showSettings() {
        refreshSettings();
        mainPage.setVisibility(View.GONE);
        detailsPage.setVisibility(View.GONE);
        settingsPage.setVisibility(View.VISIBLE);
    }

    private void showDetails(TraumaFinder.Result r) {
        showing = r;
        final TraumaCenter c = r.center;
        detailsTitle.setText(c.name);
        detailsSubtitle.setText(c.levelLabel() + " trauma center");
        detailsBody.setText(detailsText(r));
        mainPage.setVisibility(View.GONE);
        settingsPage.setVisibility(View.GONE);
        detailsPage.setVisibility(View.VISIBLE);
    }

    // ---- the line ----

    /** Recompute both lines from the reference point. Main thread. */
    public void refresh() {
        final List<TraumaCenter> centers = manager.centers();
        final GeoPoint from = source.point();
        first = null;
        better = null;
        line2.setVisibility(View.GONE);

        if (centers == null || centers.isEmpty()) {
            heading.setText("Nearest trauma center");
            line1.setText("No trauma center data on this phone");
            note.setText(manager.busy() ? "Downloading..." : "Settings > Trauma center data");
            return;
        }
        if (from == null) {
            heading.setText("Nearest trauma center");
            line1.setText("No position yet");
            note.setText("Needs the incident marker or a GPS fix.");
            return;
        }
        heading.setText("Nearest trauma center " + source.label());
        first = TraumaFinder.nearest(centers, from);
        if (first == null) {
            line1.setText("None within " + Units.distance(TraumaFinder.CAP_M));
            note.setText(estimateNote());
            return;
        }
        line1.setText(lineText(first));
        better = TraumaFinder.nearestBetter(centers, from, first.center.level);
        if (better != null) {
            line2.setText(lineText(better));
            line2.setVisibility(View.VISIBLE);
        }
        note.setText(estimateNote());
    }

    private String estimateNote() {
        return "Straight-line estimate at " + manager.cruiseKt()
                + " kt. Tap a line for details. Where a patient flies is the air crew's call.";
    }

    /** "Riverside Community Hospital (Level II), 23 mi NE, about 12 min by air (estimate)". */
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

    private void refreshSettings() {
        speedFold.label("Helicopter cruise speed", manager.cruiseKt() + " kt");
        speedButton.setText(manager.cruiseKt() + " kt");
        final TraumaPack.Index index = manager.index();
        final boolean have = manager.centers() != null && !manager.centers().isEmpty();
        dataFold.label("Trauma center data", have ? "on this phone" : "none on this phone");
        dataDetail.setText(have ? TraumaManager.describe(index)
                : "No trauma center data on this phone. Download it once; after that the line works with no network.");
        downloadButton.setEnabled(!manager.busy());
        downloadButton.setText(have ? "Check for an update" : "Download now");
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
                .setTitle("Helicopter cruise speed")
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

    private void status(String message) {
        status.setText(message);
        status.setVisibility(message == null || message.isEmpty() ? View.GONE : View.VISIBLE);
    }

    // ---- manager callbacks, main thread ----

    @Override
    public void onPackChanged() {
        refresh();
        if (settingsPage.getVisibility() == View.VISIBLE)
            refreshSettings();
    }

    @Override
    public void onStatus(String message) {
        status(message);
        downloadButton.setEnabled(true);
    }

    /** One Settings row: a head naming the setting and its value, an arrow, a body. */
    private final class Fold {
        final Button head;
        final ImageButton chevron;
        final View body;
        final String pref;
        boolean open;

        Fold(View page, int headId, int chevronId, int bodyId, String pref) {
            head = page.findViewById(headId);
            chevron = page.findViewById(chevronId);
            body = page.findViewById(bodyId);
            this.pref = pref;
            open = prefs.getBoolean(pref, false);
            final View.OnClickListener flip = new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    open = !open;
                    prefs.edit().putBoolean(Fold.this.pref, open).apply();
                    show();
                }
            };
            head.setOnClickListener(flip);
            chevron.setOnClickListener(flip);
            show();
        }

        void label(String name, String value) {
            head.setText(value == null ? name : name + ": " + value);
        }

        void show() {
            chevron.setRotation(open ? 180f : 0f);
            body.setVisibility(open ? View.VISIBLE : View.GONE);
        }
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

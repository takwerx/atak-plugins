package com.atakmap.android.uasflightplan.ui;

import android.content.Context;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.android.uasflightplan.data.Units;
import com.atakmap.android.uasflightplan.map.IslandOverlay;
import com.atakmap.android.uasflightplan.map.LaunchPoint;
import com.atakmap.android.uasflightplan.plugin.R;
import com.atakmap.android.uasflightplan.terrain.TerrainGrid;
import com.atakmap.coremap.maps.coords.GeoPoint;

import java.util.Locale;

/**
 * The side pane: place the launch point, read the ground there, and see the islands
 * above the ceiling.
 *
 * <p>The pane drives {@link IslandOverlay} but never owns it. Closing the pane, or
 * having ATAK close it, leaves the overlay exactly where it was. Reopening reads the
 * current state back out of the overlay rather than from a field that went away
 * with the view.
 *
 * <h3>Fixed for now</h3>
 *
 * The ceiling is the ground at the launch point plus {@link #FIXED_ABOVE_LAUNCH_FT},
 * rounded up to the next hundred feet, and the working radius is
 * {@link #FIXED_RADIUS_M}. Both are placeholders for the pickers the plan describes;
 * the pane says so in words so nobody mistakes the number for a choice they made.
 */
public final class UASFlightPlanPane implements IslandOverlay.Listener,
        LaunchPoint.Callback {

    /** Feet above the launch ground, until the ceiling picker exists. */
    static final double FIXED_ABOVE_LAUNCH_FT = 1000d;
    /** One statute mile, until the radius presets exist. */
    static final double FIXED_RADIUS_M = 1609.344d;

    private final View root;
    private final Context pluginContext;
    private final MapView mapView;
    private final IslandOverlay overlay;
    private final LaunchPoint launch;

    private final Button tapButton;
    private final Button hereButton;
    private final TextView ground;
    private final TextView ceiling;
    private final Button toggleButton;
    private final Button clearButton;
    private final TextView status;

    public UASFlightPlanPane(View root, Context pluginContext, MapView mapView,
            IslandOverlay overlay) {
        this.root = root;
        this.pluginContext = pluginContext;
        this.mapView = mapView;
        this.overlay = overlay;
        this.launch = new LaunchPoint(mapView, pluginContext, this);

        tapButton = root.findViewById(R.id.tap_map);
        hereButton = root.findViewById(R.id.use_position);
        ground = root.findViewById(R.id.ground);
        ceiling = root.findViewById(R.id.ceiling);
        toggleButton = root.findViewById(R.id.toggle_islands);
        clearButton = root.findViewById(R.id.clear);
        status = root.findViewById(R.id.status);

        overlay.setListener(this);
        wire();
        syncFromOverlay();
    }

    private void wire() {
        tapButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (launch.isPicking()) {
                    launch.cancelPick();
                    onPickCancelled();
                } else {
                    launch.startPick();
                    tapButton.setText(R.string.cancel);
                    status.setText(R.string.status_picking);
                }
            }
        });

        hereButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final GeoPoint self = selfPosition();
                if (self == null) {
                    status.setText(R.string.status_no_gps);
                    return;
                }
                onPicked(self);
            }
        });

        toggleButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                overlay.setVisible(!overlay.isVisible());
                syncToggle();
            }
        });

        clearButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (launch.isPicking())
                    launch.cancelPick();
                tapButton.setText(R.string.tap_map);
                launch.clear();
                overlay.clear();
            }
        });
    }

    /* ----- LaunchPoint.Callback ----- */

    @Override
    public void onPicked(GeoPoint point) {
        tapButton.setText(R.string.tap_map);
        launch.place(point);
        ground.setText(R.string.status_working);
        ceiling.setText("");
        status.setText(String.format(Locale.US,
                pluginContext.getString(R.string.status_reading),
                Units.distance(FIXED_RADIUS_M)));
        overlay.computeFor(point, FIXED_RADIUS_M);
    }

    @Override
    public void onPickCancelled() {
        tapButton.setText(R.string.tap_map);
        if (overlay.getGrid() == null)
            status.setText(R.string.status_idle);
        else
            describe(overlay.getGrid());
    }

    /* ----- IslandOverlay.Listener ----- */

    @Override
    public void onSampled(TerrainGrid grid, double groundMslM) {
        ground.setText(pluginContext.getString(R.string.ground_here,
                Units.altitudeMsl(groundMslM)));
        // The fixed rule, in feet because that is how the ceiling will be asked for.
        final double groundFt = Units.metersToFeet(groundMslM);
        final double ceilingFt = Math.ceil((groundFt + FIXED_ABOVE_LAUNCH_FT) / 100d) * 100d;
        overlay.setCeiling(Units.feetToMeters(ceilingFt));
    }

    @Override
    public void onPainted(TerrainGrid grid, double ceilingMslM, int islandCells) {
        final double above = ceilingMslM - overlay.getGroundMslM();
        ceiling.setText(pluginContext.getString(R.string.ceiling_line,
                Units.altitudeMsl(ceilingMslM), Units.height(above)));
        describe(grid);
        syncToggle();
    }

    @Override
    public void onFailed(String reason) {
        ground.setText("");
        ceiling.setText("");
        status.setText(reason);
        syncToggle();
    }

    @Override
    public void onCleared() {
        ground.setText("");
        ceiling.setText("");
        status.setText(R.string.status_idle);
        syncToggle();
    }

    /**
     * Says what was computed, including what is not shown.
     *
     * <p>A silently trimmed or partly blank overlay reads as the whole picture, so the
     * cell size, the data it came from and any gap in the elevation are stated in
     * words rather than left to be inferred from a hole in the paint.
     */
    private void describe(TerrainGrid grid) {
        final StringBuilder sb = new StringBuilder();
        final Double c = overlay.getCeilingMslM();
        if (c != null) {
            final int known = Math.max(1, grid.knownCells());
            final int islands = grid.islandCells(c);
            if (islands == 0)
                sb.append(pluginContext.getString(R.string.all_under_water));
            else
                sb.append(String.format(Locale.US,
                        pluginContext.getString(R.string.islands_share),
                        Math.max(1d, 100d * islands / known)));
            final double hi = grid.highestMsl();
            if (!Double.isNaN(hi))
                sb.append(' ').append(pluginContext.getString(R.string.highest_ground,
                        Units.altitudeMsl(hi)));
        }
        sb.append('\n').append(String.format(Locale.US,
                pluginContext.getString(R.string.terrain_detail),
                Units.distance(grid.radiusM), grid.sources,
                Units.distance(grid.cellMeters)));
        if (grid.unknownCells > 0) {
            sb.append('\n').append(String.format(Locale.US,
                    pluginContext.getString(R.string.missing_share),
                    Math.max(1d, 100d * grid.unknownCells / Math.max(1, grid.circleCells))));
        }
        sb.append('\n').append(pluginContext.getString(R.string.fixed_note));
        status.setText(sb.toString());
    }

    /**
     * Reads the overlay's live state into the controls.
     *
     * <p>Called when the pane is built, which is also when it is reopened after being
     * closed: the overlay kept running underneath, so the pane takes its truth from
     * there and not from a default.
     */
    private void syncFromOverlay() {
        syncToggle();
        final TerrainGrid grid = overlay.getGrid();
        if (grid == null) {
            status.setText(R.string.status_idle);
            return;
        }
        ground.setText(pluginContext.getString(R.string.ground_here,
                Units.altitudeMsl(overlay.getGroundMslM())));
        final Double c = overlay.getCeilingMslM();
        if (c != null)
            ceiling.setText(pluginContext.getString(R.string.ceiling_line,
                    Units.altitudeMsl(c), Units.height(c - overlay.getGroundMslM())));
        describe(grid);
    }

    /**
     * ON in green, OFF in red, on a plain dark button: the same toggle every takwerx
     * plugin uses. The face never changes color; only the word does.
     */
    private void syncToggle() {
        final boolean has = overlay.getLayer().hasField();
        final boolean on = overlay.isVisible();
        toggleButton.setText(on ? R.string.islands_on : R.string.islands_off);
        toggleButton.setTextColor(on ? 0xFF4CAF50 : 0xFFE05252);
        toggleButton.setEnabled(has);
        toggleButton.setAlpha(has ? 1f : 0.5f);
    }

    private GeoPoint selfPosition() {
        final Marker self = mapView.getSelfMarker();
        final GeoPoint p = self == null ? null : self.getPoint();
        if (p == null || !p.isValid()
                || (p.getLatitude() == 0 && p.getLongitude() == 0))
            return null;
        return new GeoPoint(p.getLatitude(), p.getLongitude());
    }

    /**
     * The pane was closed by the user. Cancels a pick in progress and leaves
     * everything else alone.
     *
     * <p>The pane closing is routine and the overlay must survive it. What must NOT
     * survive it is a half-finished pick: {@link LaunchPoint} holds ATAK's map event
     * listeners exclusively while it waits for the tap, and if nothing pops them the
     * map stops responding to ATAK's own handlers.
     */
    public void onPaneClosed() {
        if (launch.isPicking()) {
            launch.cancelPick();
            tapButton.setText(R.string.tap_map);
        }
    }

    /** Called when the plugin itself is stopping. */
    public void onClosed() {
        onPaneClosed();
        launch.dispose();
        overlay.setListener(null);
    }

    public View getRoot() {
        return root;
    }
}

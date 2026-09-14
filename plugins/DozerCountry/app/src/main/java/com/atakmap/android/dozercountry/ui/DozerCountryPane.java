package com.atakmap.android.dozercountry.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.atakmap.android.dozercountry.data.Units;
import com.atakmap.android.dozercountry.map.AreaPicker;
import com.atakmap.android.dozercountry.map.SlopeOverlay;
import com.atakmap.android.dozercountry.model.DozerStandard;
import com.atakmap.android.dozercountry.model.SlopeBand;
import com.atakmap.android.dozercountry.model.Standards;
import com.atakmap.android.dozercountry.plugin.R;
import com.atakmap.android.dozercountry.terrain.SlopeField;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.maps.coords.GeoBounds;
import com.atakmap.coremap.maps.coords.GeoPoint;

import java.util.List;
import java.util.Locale;

/**
 * The side pane: draw an area, turn the painting on and off, and read what the
 * classes mean.
 *
 * <p>The pane drives {@link SlopeOverlay} but never owns it. Closing the pane, or
 * having ATAK close it, leaves the overlay exactly where it was — the operator turning
 * it off is the only thing that takes it down. Reopening reads the current state back
 * out of the overlay rather than from a field that went away with the view.
 */
public final class DozerCountryPane implements SlopeOverlay.Listener,
        AreaPicker.Callback {

    private final View root;
    private final Context pluginContext;
    private final MapView mapView;
    private final SlopeOverlay overlay;
    private final AreaPicker picker;

    private final Button drawButton;
    private final Button clearButton;
    private final Button toggleButton;
    private final TextView status;
    private final TextView opacityLabel;
    private final SeekBar opacity;
    private final LinearLayout bandList;
    private final TextView standardSource;
    private final TextView caveatView;

    public DozerCountryPane(View root, Context pluginContext, MapView mapView,
            SlopeOverlay overlay) {
        this.root = root;
        this.pluginContext = pluginContext;
        this.mapView = mapView;
        this.overlay = overlay;
        this.picker = new AreaPicker(mapView, this);

        drawButton = root.findViewById(R.id.draw_area);
        clearButton = root.findViewById(R.id.clear_area);
        toggleButton = root.findViewById(R.id.toggle_overlay);
        status = root.findViewById(R.id.status);
        opacityLabel = root.findViewById(R.id.opacity_label);
        opacity = root.findViewById(R.id.opacity);
        bandList = root.findViewById(R.id.band_list);
        standardSource = root.findViewById(R.id.standard_source);
        caveatView = root.findViewById(R.id.standard_caveat);

        overlay.setListener(this);
        wire();
        buildBandList();
        syncFromOverlay();
    }

    private void wire() {
        drawButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (picker.isActive())
                    picker.cancel();
                else
                    startPicking();
            }
        });

        clearButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (picker.isActive())
                    picker.cancel();
                overlay.clear();
            }
        });

        toggleButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                overlay.setVisible(!overlay.isVisible());
                syncToggle();
            }
        });

        opacity.setProgress(Math.round(overlay.getOpacity() * 100f));
        opacity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                overlay.setOpacity(value / 100f);
                syncOpacityLabel();
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
            }
        });
    }

    private void startPicking() {
        picker.start();
        drawButton.setText(R.string.cancel_draw);
        status.setText(R.string.status_pick_first);
    }

    /**
     * Reads the overlay's live state into the controls.
     *
     * <p>Called when the pane is built, which is also when it is reopened after being
     * closed — the overlay kept running underneath, so the pane has to take its truth
     * from there and not from a default.
     */
    private void syncFromOverlay() {
        syncToggle();
        syncOpacityLabel();
        opacity.setProgress(Math.round(overlay.getOpacity() * 100f));

        final SlopeField field = overlay.getLastField();
        if (field != null)
            describe(field);
        else
            status.setText(R.string.status_idle);
    }

    /**
     * ON in green, OFF in red, on a plain dark button — the same toggle every takwerx
     * plugin uses. The face never changes color; only the word does.
     */
    private void syncToggle() {
        final boolean on = overlay.isVisible();
        toggleButton.setText(on ? R.string.overlay_on : R.string.overlay_off);
        toggleButton.setTextColor(on ? 0xFF4CAF50 : 0xFFE05252);
        toggleButton.setEnabled(overlay.getLayer().hasField());
        toggleButton.setAlpha(overlay.getLayer().hasField() ? 1f : 0.5f);
    }

    private void syncOpacityLabel() {
        opacityLabel.setText(pluginContext.getString(R.string.opacity)
                + "  " + Math.round(overlay.getOpacity() * 100f) + "%");
    }

    /**
     * One row per class: a swatch in the overlay's own color, the slope range, and what
     * the class means in the field.
     *
     * <p>Built from the same asset the overlay paints from, so the pane cannot drift
     * out of step with the map. The on-map legend gives ranges; this gives the words,
     * which is what makes a color a decision rather than a decoration.
     */
    private void buildBandList() {
        bandList.removeAllViews();
        final DozerStandard standard = Standards.active(pluginContext);
        if (standard == null) {
            standardSource.setText(
                    "The slope standard could not be read from dozer_data.json.");
            caveatView.setVisibility(View.GONE);
            return;
        }

        // Steepest first, and that means the above-standard row goes at the TOP, not
        // appended at the end. It was appended, which put "over every limit" below
        // "within all three" and broke the one thing a severity list has to do.
        if (standard.aboveStandard != null)
            bandList.addView(bandRow(standard.aboveStandard));
        final List<SlopeBand> bands = standard.bands;
        for (int i = bands.size() - 1; i >= 0; i--)
            bandList.addView(bandRow(bands.get(i)));

        // The caveat goes FIRST and on its own, before the provenance. What the
        // overlay leaves out matters more to someone about to act on it than whose
        // table it came from.
        final StringBuilder sb = new StringBuilder();
        if (!standard.caveat.isEmpty()) {
            final StringBuilder c = new StringBuilder();
            for (String line : standard.caveat) {
                if (line.isEmpty())
                    break;          // the rest is the note explaining why this exists
                if (c.length() > 0)
                    c.append(' ');
                c.append(line);
            }
            caveatView.setText(c.toString());
            caveatView.setVisibility(View.VISIBLE);
        } else {
            caveatView.setVisibility(View.GONE);
        }

        // The bands ARE the limits now, so do not restate them underneath: the source
        // line is provenance, nothing more. Whose guide this is, and that it is a
        // guideline rather than a clearance, is what an operator needs from it.
        sb.append(standard.legend);

        standardSource.setText(sb.toString());
    }

    private View bandRow(SlopeBand band) {
        final LinearLayout row = new LinearLayout(pluginContext);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(3), 0, dp(3));

        final View swatch = new View(pluginContext);
        final GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF000000 | (band.argb & 0x00FFFFFF));
        bg.setStroke(dp(1), 0x66FFFFFF);
        bg.setCornerRadius(dp(2));
        swatch.setBackground(bg);
        final LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(22), dp(22));
        sp.rightMargin = dp(8);
        row.addView(swatch, sp);

        final TextView label = new TextView(pluginContext);
        label.setTextColor(Color.WHITE);
        label.setTextSize(13f);
        label.setText(band.label);
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(62),
                LinearLayout.LayoutParams.WRAP_CONTENT);
        row.addView(label, lp);

        final TextView meaning = new TextView(pluginContext);
        meaning.setTextColor(Color.WHITE);
        meaning.setAlpha(0.75f);
        meaning.setTextSize(12f);
        meaning.setText(band.meaning);
        row.addView(meaning, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        return row;
    }

    private int dp(int v) {
        return Math.round(v * pluginContext.getResources()
                .getDisplayMetrics().density);
    }

    /* ----- AreaPicker.Callback ----- */

    @Override
    public void onFirstCorner(GeoPoint corner) {
        status.setText(R.string.status_pick_second);
    }

    @Override
    public void onAreaPicked(GeoBounds bounds) {
        drawButton.setText(R.string.draw_area);
        status.setText(R.string.status_working);
        overlay.computeFor(bounds);
    }

    @Override
    public void onCancelled() {
        drawButton.setText(R.string.draw_area);
        status.setText(R.string.status_cancelled);
    }

    /* ----- SlopeOverlay.Listener ----- */

    @Override
    public void onComputed(SlopeField field, DozerStandard standard) {
        describe(field);
        syncToggle();
    }

    @Override
    public void onComputeFailed(String reason) {
        status.setText(reason);
        syncToggle();
    }

    @Override
    public void onCleared() {
        status.setText(R.string.status_idle);
        syncToggle();
    }

    /**
     * Says what was actually computed, including what is missing.
     *
     * <p>A silently trimmed or partly blank overlay reads as the whole picture, so the
     * cell size and any gap in the elevation are stated in words the operator can act
     * on rather than left to be inferred from a hole in the paint.
     */
    private void describe(SlopeField field) {
        final StringBuilder sb = new StringBuilder();
        sb.append(field.width).append(" x ").append(field.height)
                .append(" cells of ").append(Units.format(field.cellMeters))
                .append(". ");

        // When the area is large enough that a cell is already wider than the working
        // window, there is no neighbourhood left to take a worst case over. Say that,
        // and say what to do about it -- silently showing per-cell slope under a label
        // that promises a window would be the overlay quietly changing its meaning.
        if (field.windowMeters <= field.cellMeters * 1.01d) {
            sb.append("A cell is already wider than ")
                    .append(Standards.workingWindowLabel(pluginContext))
                    .append(", so each shows its own slope. Draw a smaller area for a ")
                    .append("finer read.");
        } else {
            sb.append("Each cell shows the worst slope within ")
                    .append(Units.format(field.windowMeters)).append(".");
        }

        final double cells = field.width * (double) field.height;
        if (field.unknownCells > 0) {
            sb.append(String.format(Locale.US,
                    "\n%.0f%% of the area has no elevation data and is not painted.",
                    100d * field.unknownCells / cells));
        }
        // Water and missing data are both unpainted, and an operator cannot tell them
        // apart by looking at a hole. Counting them separately is the only way to say
        // which is which -- and it is also how an over-eager water mask gets noticed,
        // because a dry lake bed is as flat as a real one.
        if (field.waterCells > 0) {
            sb.append(String.format(Locale.US,
                    "\n%.0f%% is water and is not classed.",
                    100d * field.waterCells / cells));
        }
        status.setText(sb.toString());
    }

    /**
     * Called when the pane goes away. Only the transient picker is torn down; the
     * overlay keeps painting, which is the whole point of it living in the plugin.
     */
    public void onClosed() {
        if (picker.isActive())
            picker.cancel();
        overlay.setListener(null);
    }

    public View getRoot() {
        return root;
    }
}

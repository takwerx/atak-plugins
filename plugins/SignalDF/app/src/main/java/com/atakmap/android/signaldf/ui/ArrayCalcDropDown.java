package com.atakmap.android.signaldf.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.dropdown.DropDown.OnStateListener;
import com.atakmap.android.dropdown.DropDownReceiver;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.signaldf.data.ArrayCalc;
import com.atakmap.android.signaldf.data.ArrayCalc.Geometry;
import com.atakmap.android.signaldf.data.ArrayCalc.Result;
import com.atakmap.android.signaldf.data.ShortDistance;
import com.atakmap.android.signaldf.net.KrakenLink;
import com.atakmap.android.signaldf.plugin.R;

import java.util.Locale;

/**
 * Antenna array sizing, on the phone, at the vehicle.
 *
 * <p>KrakenRF publish this as a spreadsheet. A spreadsheet is the wrong place
 * for it: the question comes up standing next to a truck with a tape measure,
 * deciding where to bolt five antennas, and it comes up again every time the
 * frequency of interest changes. {@link ArrayCalc} is the arithmetic, tested
 * against the workbook's own cells; this is the screen.
 *
 * <p>It answers two questions with the same controls, which is the reason it is
 * one screen and not two:
 * <ul>
 * <li><b>What should I build?</b> Enter the frequency, press <i>Size it for
 *     me</i>, and it fills in an array sized just under the ambiguity limit --
 *     the biggest aperture, and so the best resolution, that still cannot
 *     alias.
 * <li><b>Will what I have work?</b> Enter the array you already have and it
 *     says yes, or says which way to move it and why.
 * </ul>
 *
 * <p><b>It reads the radio when the radio is there.</b> <i>From radio</i> pulls
 * the frequency, the arrangement and the spacing the Kraken is actually
 * configured with, so the answer is about the operator's real array rather than
 * one they typed from memory. That is the thing a spreadsheet cannot do, and
 * the reason this is worth having in the plugin at all.
 *
 * <p>Opens wide: this is a screen with a drawing and numbers on it, and the map
 * is not the point while somebody is holding a tape measure.
 */
public class ArrayCalcDropDown extends DropDownReceiver implements OnStateListener {

    /**
     * What KrakenRF say they build to: "we typically set our arrays to s=0.33".
     * Not 0.45. A bigger multiplier resolves better and 0.45 is legal, but the
     * number the vendor uses is the one that lands on their template's holes,
     * and the operator is laying this out with their printed arms.
     */
    private static final double RECOMMENDED_MULTIPLIER = ArrayCalc.TYPICAL_MULTIPLIER;

    private final Context pluginContext;
    private final View root;

    private final Button freqButton;
    private final Button fromRadioButton;
    private final Button geometryButton;
    private final Button elementsButton;
    private final Button sizeButton;
    private final Button sizeItButton;
    private final TextView verdict;
    private final TextView numbers;
    private final Button templateButton;
    private final TextView antenna;
    private final TextView band;
    private final TextView note;
    private final ArrayPlanView plan;

    private Geometry geometry = Geometry.CIRCULAR;
    private int elements = 5;
    private double freqMHz = 416.588;
    private double sizeCm = 30.0;

    public ArrayCalcDropDown(MapView mapView, Context pluginContext) {
        super(mapView);
        this.pluginContext = pluginContext;
        root = PluginLayoutInflater.inflate(pluginContext, R.layout.array_calc, null);

        freqButton = root.findViewById(R.id.freq);
        fromRadioButton = root.findViewById(R.id.from_radio);
        geometryButton = root.findViewById(R.id.geometry);
        elementsButton = root.findViewById(R.id.elements);
        sizeButton = root.findViewById(R.id.size);
        sizeItButton = root.findViewById(R.id.size_it);
        verdict = root.findViewById(R.id.verdict);
        numbers = root.findViewById(R.id.numbers);
        templateButton = root.findViewById(R.id.template);
        antenna = root.findViewById(R.id.antenna);
        band = root.findViewById(R.id.band);
        note = root.findViewById(R.id.note);
        plan = root.findViewById(R.id.plan);

        freqButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                askNumber("Frequency", "Megahertz. What you are trying to find.",
                        freqMHz, new OnNumber() {
                            @Override
                            public void got(double value) {
                                if (value <= 0) {
                                    toast("a frequency has to be above zero");
                                    return;
                                }
                                freqMHz = value;
                                refresh();
                            }
                        });
            }
        });

        fromRadioButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                fromRadio();
            }
        });

        geometryButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickGeometry();
            }
        });

        elementsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickElements();
            }
        });

        sizeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                askNumber(geometry == Geometry.CIRCULAR ? "Array radius" : "Array length",
                        (geometry == Geometry.CIRCULAR
                                ? "Center of the array to any element, in "
                                : "First element to last, in ")
                                + (ShortDistance.imperial() ? "inches." : "centimeters."),
                        ShortDistance.valueFromCm(sizeCm), new OnNumber() {
                            @Override
                            public void got(double value) {
                                if (value <= 0) {
                                    toast("a size has to be above zero");
                                    return;
                                }
                                // Typed in the operator's unit, kept in cm.
                                sizeCm = ShortDistance.toCm(value);
                                refresh();
                            }
                        });
            }
        });

        sizeItButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Snap to a hole on KrakenRF's printed template when one fits.
                // An exact radius is no use to somebody holding arms drilled at
                // 50 mm intervals; "use the 15 cm hole" is.
                double hole = geometry == Geometry.CIRCULAR
                        && template == TEMPLATE_KRAKENRF
                        ? ArrayCalc.templateRadiusCm(elements, freqMHz) : -1;
                sizeCm = hole > 0 ? hole
                        : ArrayCalc.sizeFor(geometry, freqMHz, elements,
                                RECOMMENDED_MULTIPLIER).sizeCm;
                refresh();
            }
        });

        templateButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickTemplate();
            }
        });

        root.findViewById(R.id.close).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeDropDown();
            }
        });
    }

    public void show() {
        if (isVisible())
            return;
        setRetain(true);
        if (!isPortrait())
            showDropDown(root, FULL_WIDTH - HANDLE_THICKNESS_LANDSCAPE, FULL_HEIGHT,
                    FULL_WIDTH, HALF_HEIGHT, false, this);
        else
            showDropDown(root, FULL_WIDTH, FULL_HEIGHT - HANDLE_THICKNESS_PORTRAIT,
                    FULL_WIDTH, HALF_HEIGHT, false, this);
        // Start from the radio if there is one, so the first thing on screen is
        // about the operator's own array rather than a default.
        fromRadioIfConnected();
        refresh();
    }

    /** Takes the frequency and array from the radio, and says so. */
    private void fromRadio() {
        if (!applyRadio()) {
            toast("not connected to a radio -- connect first, or type it in");
            return;
        }
        refresh();
    }

    private void fromRadioIfConnected() {
        applyRadio();
    }

    /**
     * Reads the radio's own configuration. The Kraken reports
     * {@code ant_spacing_meters}, and for a circular array that value is the
     * RADIUS, not the element spacing -- the two differ by the chord factor and
     * confusing them is exactly the mistake this screen exists to prevent.
     */
    private boolean applyRadio() {
        KrakenLink link = KrakenLink.get();
        if (link == null || link.latest() == null || link.latest().isEmpty())
            return false;
        double f = link.latest().get(0).frequencyMHz();
        if (f > 0 && !Double.isNaN(f))
            freqMHz = f;
        return true;
    }

    /**
     * Which jig the operator is laying the array out with. It changes nothing
     * about the arithmetic and everything about the answer: the same radius is
     * "use the 15 cm hole" on one template, "use the position labelled with
     * the next frequency above yours" on another, and a tape measurement on
     * neither.
     */
    private void pickTemplate() {
        final String[] names = {
                "KrakenRF printed arms -- holes at fixed radii",
                "3D-printed template -- positions labelled by frequency",
                "None -- measuring it myself"
        };
        new AlertDialog.Builder(getMapView().getContext())
                .setTitle("What are you laying it out with?")
                .setSingleChoiceItems(names, template,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                template = which;
                                d.dismiss();
                                refresh();
                            }
                        })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void pickGeometry() {
        final String[] names = {
                "Circular (UCA) -- elements on a circle",
                "Linear (ULA) -- elements in a line"
        };
        // No Spinner, ever, and on the MapView context.
        new AlertDialog.Builder(getMapView().getContext())
                .setTitle("Array shape")
                .setSingleChoiceItems(names, geometry == Geometry.CIRCULAR ? 0 : 1,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                geometry = which == 0 ? Geometry.CIRCULAR : Geometry.LINEAR;
                                d.dismiss();
                                refresh();
                            }
                        })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void pickElements() {
        final int[] counts = { 3, 4, 5 };
        final String[] names = { "3 elements", "4 elements", "5 elements (KrakenSDR)" };
        int current = 2;
        for (int i = 0; i < counts.length; i++)
            if (counts[i] == elements)
                current = i;
        new AlertDialog.Builder(getMapView().getContext())
                .setTitle("How many elements")
                .setSingleChoiceItems(names, current, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        elements = counts[which];
                        d.dismiss();
                        refresh();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private interface OnNumber {
        void got(double value);
    }

    private void askNumber(String title, String message, double current, final OnNumber cb) {
        final EditText input = new EditText(getMapView().getContext());
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setSingleLine(true);
        input.setText(String.format(Locale.US, "%.3f", current).replaceAll("0+$", "")
                .replaceAll("\\.$", ""));
        new AlertDialog.Builder(getMapView().getContext())
                .setTitle(title)
                .setMessage(message)
                .setView(input)
                .setPositiveButton("Set", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        try {
                            cb.got(Double.parseDouble(input.getText().toString().trim()));
                        } catch (NumberFormatException e) {
                            toast("that is not a number");
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void toast(String s) {
        Toast.makeText(getMapView().getContext(), s, Toast.LENGTH_LONG).show();
    }

    private static final int TEMPLATE_KRAKENRF = 0;
    private static final int TEMPLATE_3D = 1;
    private static final int TEMPLATE_NONE = 2;

    private int template = TEMPLATE_KRAKENRF;

    private void refresh() {
        Result r = ArrayCalc.atSize(geometry, freqMHz, elements, sizeCm);

        freqButton.setText(String.format(Locale.US, "%.4f MHz", freqMHz));
        geometryButton.setText(geometry == Geometry.CIRCULAR ? "Circular" : "Linear");
        elementsButton.setText(elements + " elements");
        templateButton.setText("Template: "
                + (template == TEMPLATE_KRAKENRF ? "KrakenRF printed arms"
                        : template == TEMPLATE_3D ? "3D-printed" : "none"));
        sizeButton.setText((geometry == Geometry.CIRCULAR ? "Radius " : "Length ")
                + ShortDistance.fromCm(sizeCm));

        plan.set(geometry, elements, sizeCm, r.spacingCm, r.usable());

        if (r.usable()) {
            verdict.setText("This array works at this frequency.");
            verdict.setTextColor(pluginContext.getResources().getColor(R.color.on_green));
        } else {
            verdict.setText(r.problem());
            verdict.setTextColor(pluginContext.getResources().getColor(R.color.off_red));
        }

        // Said the way it gets measured, not the way it gets calculated. The
        // operator is laying antennas out with a tape and KrakenRF's printed
        // arms: the hub sets the angles, the arms set the radius, and the
        // neighbor-to-neighbor distance is how you check the result without
        // a protractor.
        StringBuilder n = new StringBuilder();
        if (geometry == Geometry.CIRCULAR) {
            n.append(String.format(Locale.US,
                    "Measure %s out from the center, along each arm\n"
                            + "Arms %.0f degrees apart, numbered clockwise "
                            + "from the forward one\n"
                            + "Check: neighboring antennas %s apart\n",
                    ShortDistance.fromCm(sizeCm), 360.0 / elements,
                    ShortDistance.fromCm(r.spacingCm)));
        } else {
            n.append(String.format(Locale.US,
                    "%s from the first antenna to the last\n"
                            + "%s between neighbors, in a straight line\n",
                    ShortDistance.fromCm(sizeCm), ShortDistance.fromCm(r.spacingCm)));
        }
        n.append(String.format(Locale.US,
                "Spacing is %.2f wavelengths (wavelength %.2f m)\n"
                        + "Resolution about %.1f degrees",
                r.multiplier, r.wavelengthM, r.resolutionDeg));
        numbers.setText(n.toString());

        // How long each whip is, which the vendor's workbook does not answer
        // and somebody standing at a vehicle with a telescopic antenna in
        // their hand needs before the array geometry is any use to them.
        StringBuilder a = new StringBuilder(String.format(Locale.US,
                "Extend each whip to %s, a %s wavelength\n",
                ShortDistance.fromCm(ArrayCalc.whipLengthCm(freqMHz)),
                freqMHz > ArrayCalc.RETRACTED_QUARTER_WAVE_MHZ
                        ? "three quarter" : "quarter"));
        if (freqMHz > ArrayCalc.RETRACTED_QUARTER_WAVE_MHZ)
            a.append("A quarter wave would be shorter than these whips "
                    + "retract to, so go three quarters instead; all five "
                    + "shift together, so the bearing is unaffected\n");
        int ext = ArrayCalc.krakenTennaExtensions(freqMHz);
        if (ext == 0)
            a.append("On a KrakenTenna, leave the sections collapsed\n");
        else if (ext > 0)
            a.append(String.format(Locale.US,
                    "On a KrakenTenna, extend %d section%s\n",
                    ext, ext == 1 ? "" : "s"));
        else
            a.append("KrakenRF publish no section count for this frequency; "
                    + "extend to the closest one they list\n");
        a.append("All five identical, on a metal ground plane such as a "
                + "vehicle roof");
        antenna.setText(a.toString());

        double hi = ArrayCalc.highestUsableMHz(geometry, elements, sizeCm);
        double lo = ArrayCalc.lowestUsableMHz(geometry, elements, sizeCm);
        StringBuilder b = new StringBuilder(String.format(Locale.US,
                "%.0f to %.0f MHz", lo, hi));
        if (geometry == Geometry.CIRCULAR && template == TEMPLATE_KRAKENRF) {
            double hole = ArrayCalc.templateRadiusCm(elements, freqMHz);
            if (hole > 0)
                b.append("\n\nOn KrakenRF's printed arms, use the "
                        + ShortDistance.fromCm(hole) + " hole.");
            else
                b.append("\n\nNo hole on KrakenRF's printed arms covers this "
                        + "frequency; the array has to be built to size.");
        } else if (geometry == Geometry.CIRCULAR && template == TEMPLATE_3D) {
            // Its seven positions are labelled with the highest frequency each
            // one is good for, so the rule is the author's own and needs no
            // table: take the next label above the frequency being hunted.
            // Deliberately not naming a label -- the seven numbers are in the
            // OpenSCAD source behind a login, and a position named wrong is
            // found out on a car roof.
            b.append(String.format(Locale.US,
                    "\n\nOn the 3D-printed template, use the position "
                            + "labelled with the next frequency above %.1f MHz. "
                            + "Its arm points antenna 0 forward.", freqMHz));
            if (freqMHz < 150)
                b.append(" Below 150 MHz this template runs out; "
                        + "its widest position is only good down to there.");
        }
        band.setText(b.toString());

        String jig = template == TEMPLATE_KRAKENRF
                ? "KrakenRF publish printable arms and a hub for laying this "
                        + "out: the hub sets the angles, the arms set the "
                        + "radius. "
                : template == TEMPLATE_3D
                        ? "The 3D-printed template is a magnetic hub and one "
                                + "arm you move round five positions; its "
                                + "seven layout positions are each labelled "
                                + "with the highest frequency that position is "
                                + "good for, and the arm doubles as a scale "
                                + "for setting the whips. "
                        : "";
        note.setText(jig
                + "Spacing must stay under 0.5 wavelengths or the array cannot "
                + "tell some directions apart. Bigger arrays resolve better, so aim "
                + "just under the limit unless the vehicle says otherwise. Resolution "
                + "is KrakenRF's own figure, which allows for the super-resolution "
                + "the radio's MUSIC processing gets.");
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        // Opened by the Signal DF pane, not by broadcast.
    }

    @Override
    public void onDropDownVisible(boolean visible) {
    }

    @Override
    public void onDropDownSizeChanged(double width, double height) {
    }

    @Override
    public void onDropDownClose() {
    }

    @Override
    public void onDropDownSelectionRemoved() {
    }

    @Override
    protected void disposeImpl() {
    }
}

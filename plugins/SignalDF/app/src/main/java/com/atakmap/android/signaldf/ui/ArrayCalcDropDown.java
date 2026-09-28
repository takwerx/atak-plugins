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
 * <p><b>One input: the frequency.</b> Everything else on screen is an answer.
 * The first build of this screen was not like that -- it had the array shape,
 * the element count, a radius, a "size it for me" button and a template picker
 * across the top -- and the operator's reaction was one question per control:
 * what does From radio mean, when would I use linear, why would I enter my own
 * radius, what does size it for me mean, why am I picking a template when you
 * know all the sizes anyway. Every one of those was fair. A screen whose whole
 * purpose is "tell me how to set my array up" must not begin by asking the
 * operator to configure the thing that tells them.
 *
 * <p>So the array is sized from the frequency, and the answer names every jig
 * at once -- the hole on KrakenRF's paper arms, the position on the 3D-printed
 * template, and the tape measurement -- because the operator has whichever one
 * they have and reads past the other two in a second. Picking between them was
 * a control that existed only to hide two lines of text.
 *
 * <p>Circular with five elements, because that is a KrakenSDR: five coherent
 * channels, and the vendor's templates are all circular. Somebody with a
 * different array reaches it through <i>My array is a different size</i>, which
 * is also where the "will what I already have work" question lives, with its
 * verdict.
 *
 * <p><b>It reads the radio when the radio is there.</b> <i>From radio</i> pulls
 * the frequency the Kraken is actually tuned to, so the answer is about what
 * the operator is really hunting rather than a number typed from memory. That
 * is the thing a spreadsheet cannot do, and the reason this is worth having in
 * the plugin at all.
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
    private final TextView verdict;
    private final TextView layoutSteps;
    private final TextView numbers;
    private final TextView antenna;
    private final TextView note;
    private final ArrayPlanView plan;

    private Geometry geometry = Geometry.CIRCULAR;
    private int elements = 5;
    private double freqMHz = 416.588;



    public ArrayCalcDropDown(MapView mapView, Context pluginContext) {
        super(mapView);
        this.pluginContext = pluginContext;
        root = PluginLayoutInflater.inflate(pluginContext, R.layout.array_calc, null);

        freqButton = root.findViewById(R.id.freq);
        verdict = root.findViewById(R.id.verdict);
        layoutSteps = root.findViewById(R.id.layout_steps);
        numbers = root.findViewById(R.id.numbers);
        antenna = root.findViewById(R.id.antenna);
        note = root.findViewById(R.id.note);
        plan = root.findViewById(R.id.plan);

        freqButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                askFrequency();
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

    /**
     * The one control on this screen. Type a frequency, or take the radio's.
     *
     * <p>Taking the radio's used to be a button of its own beside this one,
     * labeled "From radio" and then "Radio's frequency", and the operator
     * asked what it meant three times. Two words cannot carry it, and the
     * screen is supposed to have one input. So it lives in here, where there
     * is room to say what it gives you AND to show the number, which settles
     * the question completely: a button reading "Use 416.5880 MHz from the
     * radio" needs no explaining.
     */
    private void askFrequency() {
        final EditText input = new EditText(getMapView().getContext());
        input.setInputType(InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setSingleLine(true);
        input.setText(String.format(Locale.US, "%.4f", freqMHz));

        AlertDialog.Builder b = new AlertDialog.Builder(getMapView().getContext())
                .setTitle("Frequency you are hunting")
                .setMessage("Megahertz.")
                .setView(input)
                .setPositiveButton("Set", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        try {
                            double value = Double.parseDouble(
                                    input.getText().toString().trim());
                            if (value <= 0) {
                                toast("a frequency has to be above zero");
                                return;
                            }
                            freqMHz = value;
                            refresh();
                        } catch (NumberFormatException e) {
                            toast("that is not a number");
                        }
                    }
                })
                .setNegativeButton("Cancel", null);

        final Double tuned = radioFrequency();
        if (tuned != null)
            b.setNeutralButton(String.format(Locale.US,
                    "Use %.4f MHz from the radio", tuned),
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int which) {
                            freqMHz = tuned;
                            refresh();
                        }
                    });
        b.show();
    }

    /** What the Kraken is tuned to, or null when no radio is answering. */
    private Double radioFrequency() {
        KrakenLink link = KrakenLink.get();
        if (link == null || link.latest() == null || link.latest().isEmpty())
            return null;
        double f = link.latest().get(0).frequencyMHz();
        return f > 0 && !Double.isNaN(f) ? Double.valueOf(f) : null;
    }

    /**
     * Fills the frequency in with whatever the Kraken is currently tuned to,
     * so nobody types a number they are already looking at on the radio.
     *
     * <p>The screen does this by itself when it opens and a radio is
     * connected. The button is for the retune afterwards: change the VFO in
     * the radio's own setup and press it, rather than closing and reopening
     * this screen. It was called "From radio", which the operator asked the
     * meaning of twice, so it is now called what it gives you.
     */
    private void fromRadio() {
        if (!applyRadio()) {
            toast("no radio connected -- connect one, or tap the frequency "
                    + "and type it in");
            return;
        }
        toast(String.format(Locale.US,
                "the radio is tuned to %.4f MHz", freqMHz));
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

    private void toast(String s) {
        Toast.makeText(getMapView().getContext(), s, Toast.LENGTH_LONG).show();
    }

    /**
     * The radius this screen works to: the nearest hole on KrakenRF's printed
     * arms when one suits the frequency, and otherwise the vendor's own
     * multiplier.
     *
     * <p>Those two agree by construction -- {@code templateRadiusCm} snaps to
     * the hole nearest s=0.33, which is what KrakenRF say they build to -- so
     * the measurement on screen and the hole named beside it are the same
     * place. That is the whole reason this screen can size itself without
     * asking anything: there is one right answer and the operator does not
     * have to choose it.
     */
    private double sizeCm() {
        if (geometry == Geometry.CIRCULAR) {
            double hole = ArrayCalc.templateRadiusCm(elements, freqMHz);
            if (hole > 0)
                return hole;
        }
        return ArrayCalc.sizeFor(geometry, freqMHz, elements,
                RECOMMENDED_MULTIPLIER).sizeCm;
    }

    private void refresh() {
        final double size = sizeCm();
        final Result r = ArrayCalc.atSize(geometry, freqMHz, elements, size);

        freqButton.setText(String.format(Locale.US, "%.4f MHz", freqMHz));

        plan.set(geometry, elements, size, r.spacingCm, r.usable());

        verdict.setVisibility(View.GONE);
        layoutSteps.setText(layoutText(r, size));
        antenna.setText(antennaText());

        // What it buys, in the order somebody cares: how tight a bearing will
        // be, and whether they will have to rebuild when the frequency moves.
        numbers.setText(String.format(Locale.US,
                "Bearings good to about %.0f degrees\n"
                        + "Works from %.0f to %.0f MHz without rebuilding\n"
                        + "Antennas sit %.2f wavelengths apart "
                        + "(a wavelength is %.2f m)",
                r.resolutionDeg,
                ArrayCalc.lowestUsableMHz(geometry, elements, size),
                ArrayCalc.highestUsableMHz(geometry, elements, size),
                r.multiplier, r.wavelengthM));

        note.setText("Antennas closer than half a wavelength apart, or the "
                + "array cannot tell some directions apart. Bigger arrays "
                + "resolve better, so this sizes as big as it safely can. "
                + "The resolution figure is KrakenRF's own, which allows for "
                + "the super-resolution the radio's MUSIC processing gets.");
    }

    /** The instructions, naming every jig so nobody has to pick one first. */
    private String layoutText(Result r, double size) {
        StringBuilder n = new StringBuilder();
        if (geometry != Geometry.CIRCULAR) {
            n.append(String.format(Locale.US,
                    "%d antennas in a straight line\n"
                            + "%s from the first to the last\n"
                            + "%s between neighbors",
                    elements, ShortDistance.fromCm(size),
                    ShortDistance.fromCm(r.spacingCm)));
            return n.toString();
        }

        // Counted out rather than written "1, 2, 3", which is what it said
        // when the array has five antennas in it. The operator spotted that
        // the moment they read it.
        StringBuilder rest = new StringBuilder();
        for (int i = 1; i < elements; i++)
            rest.append(i < elements - 1 ? i + ", " : String.valueOf(i));
        n.append(String.format(Locale.US,
                "%d antennas in a circle, %.0f degrees apart\n"
                        + "Antenna 0 points the way the vehicle faces, "
                        + "then %s clockwise\n",
                elements, 360.0 / elements, rest));

        n.append("\nUse whichever you have:\n");
        int hole = ArrayCalc.templateHoleNumber(elements, freqMHz);
        if (hole > 0)
            n.append("  KrakenRF paper arms -- the ")
                    .append(ArrayCalc.ordinal(hole))
                    .append(" hole out from the center\n");
        else
            n.append("  KrakenRF paper arms -- no hole fits this, measure it\n");
        // The 3D template labels its seven positions with the highest
        // frequency each is good for, so its rule needs no table from us.
        n.append(String.format(Locale.US,
                "  3D-printed template -- the position labeled just above "
                        + "%.1f MHz\n", freqMHz));
        n.append("  A tape measure -- ")
                .append(ShortDistance.fromCm(size))
                .append(" from the center to each antenna\n");
        n.append("\nCheck it: neighboring antennas end up ")
                .append(ShortDistance.fromCm(r.spacingCm))
                .append(" apart.");
        return n.toString();
    }

    /** How long each whip goes, which the vendor's workbook does not answer. */
    private String antennaText() {
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
        a.append("All ").append(elements).append(" identical, on a metal "
                + "ground plane such as a vehicle roof");
        return a.toString();
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

package com.atakmap.android.signaldf.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.dropdown.DropDown.OnStateListener;
import com.atakmap.android.dropdown.DropDownReceiver;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.preference.AtakPreferences;
import com.atakmap.android.signaldf.data.Age;
import com.atakmap.android.signaldf.map.BearingLayer;
import com.atakmap.android.signaldf.model.ArrayHeading;
import com.atakmap.android.signaldf.model.Bearing;
import com.atakmap.android.signaldf.model.FeedFrame;
import com.atakmap.android.signaldf.net.KrakenHost;
import com.atakmap.android.signaldf.net.KrakenLink;
import com.atakmap.android.signaldf.plugin.R;
import com.atakmap.coremap.log.Log;

import java.util.List;
import java.util.Locale;

/**
 * The Signal DF pane: what the radio is doing, which way it says the signal is,
 * and on whose authority.
 *
 * <p><b>Why this is a {@link DropDownReceiver} and not a
 * {@code gov.tak.api.ui.Pane}, deliberately, and against CLAUDE.md's usual
 * preference for the stable {@code gov.tak.api.*} API.</b> Verified against
 * 5.8.0.3's {@code main.jar}: {@code gov.tak.api.ui.IHostUIService} has
 * {@code showPane}, {@code closePane}, {@code isPaneVisible}, {@code queueEvent},
 * the toast and prompt calls and the toolbar calls -- and no resize.
 * {@code gov.tak.api.ui.Pane} carries only {@code PREFERRED_WIDTH_RATIO},
 * {@code PREFERRED_HEIGHT_RATIO} and their pixel forms, all fixed when the pane
 * is built. So a stable {@code Pane} cannot be resized after it is shown and
 * never hears the handle being dragged. Half and full are the behavior that was
 * asked for and only the drop-down API has it. <b>There is no stable equivalent
 * to prefer, so do not "fix" this by porting it to {@code Pane}.</b>
 * {@code IAP/IapDropDown} documents the same conclusion independently.
 *
 * <p><b>Half is the operating state.</b> Direction finding is a map task -- the
 * bearing is on the map -- so a pane that covers the map has to earn every
 * second it is open. Full is for setting the radio up and reading detail; the
 * pane steps itself back to half the moment its result is on the map, which is
 * what makes full width usable rather than a mode the operator has to remember
 * to leave.
 */
public class SignalDfDropDown extends DropDownReceiver implements OnStateListener,
        KrakenLink.Listener {

    private static final String TAG = "SignalDF.Pane";

    /** Where the radio's address is remembered between sessions. */
    private static final String PREF_HOST = "signaldf.host";

    /** The Dash GUI's port, fixed by the radio and not the operator's to move. */
    private static final int GUI_PORT = 8080;

    /**
     * The operator's own array heading, degrees, or absent for "use the radio's".
     * Stored as a string so that absent and zero are different values -- which
     * is the entire heading problem in one preference.
     */
    private static final String PREF_HEADING = "signaldf.array_heading";

    private final Context pluginContext;
    private final AtakPreferences prefs;
    private final BearingLayer layer;
    private RadioSetupDropDown radioSetup;
    private final View root;

    private final TextView status;
    private final TextView feedLine;
    private final TextView headingProvenance;
    private final TextView bearingsNote;
    private final TextView unverified;
    private final Button hostButton;
    private final Button connectButton;
    private final Button setHeadingButton;
    private final Button clearHeadingButton;
    private final Button radioSetupButton;
    private final Button wideNarrowButton;
    private final LinearLayout vfoRows;

    /** Current pane size, tracked so the fullscreen request knows where it is. */
    private double currentWidth = HALF_WIDTH;
    private double currentHeight = FULL_HEIGHT;

    /** Guards the reflow against running on every layout pass. */
    private boolean appliedWide;
    private boolean appliedWideValid;

    /**
     * Set once per connection, so the pane narrows itself when the first
     * bearing reaches the map and not on every frame after it.
     */
    private boolean narrowedForThisConnection;

    private final Runnable reflow = new Runnable() {
        @Override
        public void run() {
            applyWidth();
        }
    };

    public SignalDfDropDown(MapView mapView, Context pluginContext) {
        super(mapView);
        this.pluginContext = pluginContext;
        this.prefs = new AtakPreferences(mapView.getContext());
        this.layer = new BearingLayer(mapView);

        root = PluginLayoutInflater.inflate(pluginContext, R.layout.signaldf_pane, null);
        status = root.findViewById(R.id.status);
        feedLine = root.findViewById(R.id.feed_line);
        headingProvenance = root.findViewById(R.id.heading_provenance);
        bearingsNote = root.findViewById(R.id.bearings_note);
        unverified = root.findViewById(R.id.unverified);
        hostButton = root.findViewById(R.id.host);
        connectButton = root.findViewById(R.id.connect);
        setHeadingButton = root.findViewById(R.id.set_heading);
        clearHeadingButton = root.findViewById(R.id.clear_heading);
        radioSetupButton = root.findViewById(R.id.radio_setup);
        wideNarrowButton = root.findViewById(R.id.wide_narrow);
        vfoRows = root.findViewById(R.id.vfo_rows);

        hostButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                askForHost();
            }
        });
        connectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleConnection();
            }
        });
        setHeadingButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                askForHeading();
            }
        });
        clearHeadingButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.remove(PREF_HEADING);
                refresh();
            }
        });
        radioSetupButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openRadioSetup();
            }
        });
        wideNarrowButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // The handle drag is not discoverable and a gloved hand on a
                // vehicle mount will not find it.
                if (isWide())
                    goNarrow();
                else
                    goWide();
            }
        });

        root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b,
                    int ol, int ot, int or, int ob) {
                // Posted: re-laying out during a layout pass throws a second
                // exception on top of the first. onDropDownSizeChanged is too
                // early -- the pane has not laid out at the new size there.
                root.post(reflow);
            }
        });

        KrakenLink link = KrakenLink.get();
        if (link != null)
            link.addListener(this);
    }

    // ---- drop down ---------------------------------------------------------

    public void show() {
        if (isVisible())
            return;
        setRetain(true);
        // ignoreBackButton: Back steps a wide pane back to half before it
        // closes, which is what the handle gesture does and what Back should do.
        showDropDown(root, HALF_WIDTH, FULL_HEIGHT, FULL_WIDTH, HALF_HEIGHT, true, this);
        refresh();
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        // Opened directly by the toolbar item, or by the plugin's own SHOW
        // receiver; this drop down is not registered for any broadcast itself.
    }

    /**
     * The handle was dragged. Full width is {@code FULL_WIDTH} less the handle,
     * never {@code FULL_WIDTH} itself: that strip is the grab handle, and a pane
     * covering it cannot be swiped closed again. Both orientations, because a
     * vehicle mount can be either.
     */
    @Override
    protected void onStateRequested(int state) {
        if (state == DROPDOWN_STATE_FULLSCREEN)
            goWide();
        else if (state == DROPDOWN_STATE_NORMAL)
            goNarrow();
    }

    private void goWide() {
        if (!isPortrait())
            resize(FULL_WIDTH - HANDLE_THICKNESS_LANDSCAPE, FULL_HEIGHT);
        else
            resize(FULL_WIDTH, FULL_HEIGHT - HANDLE_THICKNESS_PORTRAIT);
    }

    private void goNarrow() {
        if (!isPortrait())
            resize(HALF_WIDTH, FULL_HEIGHT);
        else
            resize(FULL_WIDTH, HALF_HEIGHT);
    }

    /** True when the pane is opened out past its normal half. */
    private boolean isWide() {
        return !isPortrait()
                ? currentWidth > HALF_WIDTH + 0.01
                : currentHeight > HALF_HEIGHT + 0.01;
    }

    /**
     * Back steps the pane down rather than closing it outright, so an operator
     * who opened the detail out to full width does not lose the pane to one
     * press.
     */
    @Override
    protected boolean onBackButtonPressed() {
        if (isWide()) {
            goNarrow();
            return true;
        }
        return false;
    }

    @Override
    public void onDropDownSizeChanged(double width, double height) {
        currentWidth = width;
        currentHeight = height;
        // The pane has not laid out at the new size yet; the layout listener
        // picks it up once it has.
    }

    @Override
    public void onDropDownVisible(boolean visible) {
        if (visible)
            refresh();
    }

    @Override
    public void onDropDownClose() {
        // The link keeps running: it is not owned by this pane, and a closed
        // pane is not a reason to stop collecting.
    }

    @Override
    public void onDropDownSelectionRemoved() {
        // no map item is selected by this pane
    }

    @Override
    protected void disposeImpl() {
        KrakenLink link = KrakenLink.get();
        if (link != null)
            link.removeListener(this);
        layer.dispose();
        if (radioSetup != null) {
            radioSetup.dispose();
            radioSetup = null;
        }
    }

    /** Applies the wide/narrow detail columns once the pane has laid out. */
    private void applyWidth() {
        boolean wide = isWide();
        if (appliedWideValid && wide == appliedWide)
            return;
        appliedWide = wide;
        appliedWideValid = true;
        wideNarrowButton.setText(wide ? "Back to half" : "Open wide");
        for (int i = 0; i < vfoRows.getChildCount(); i++) {
            View detail = vfoRows.getChildAt(i).findViewById(R.id.detail);
            if (detail != null)
                detail.setVisibility(wide ? View.VISIBLE : View.GONE);
        }
        unverified.setVisibility(wide ? View.VISIBLE : View.GONE);
    }

    // ---- KrakenLink.Listener -----------------------------------------------

    @Override
    public void onLinkChanged() {
        refresh();
    }

    @Override
    public void onBearings(FeedFrame frame) {
        draw();
        refresh();
        if (!narrowedForThisConnection && isWide()) {
            // The result is on the map now, so the map is what matters. This is
            // what keeps full width from becoming a state a DF session sits in.
            narrowedForThisConnection = true;
            goNarrow();
        }
    }

    // ---- the operator's controls -------------------------------------------

    private void toggleConnection() {
        KrakenLink link = KrakenLink.get();
        if (link == null)
            return;
        if (link.isRunning()) {
            link.stop();
            layer.clear();
            refresh();
            return;
        }
        KrakenHost host;
        try {
            host = KrakenHost.parse(prefs.get(PREF_HOST, KrakenHost.DEFAULT_HOST));
        } catch (KrakenHost.InvalidHost e) {
            toast(e.getMessage());
            askForHost();
            return;
        }
        narrowedForThisConnection = false;
        link.start(host);
        refresh();
    }

    /**
     * No {@code Spinner}, ever, and every dialog on the MapView context: a
     * dialog built from the plugin context is a {@code BadTokenException} and
     * ATAK dies.
     */
    private void askForHost() {
        final EditText input = new EditText(getMapView().getContext());
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setSingleLine(true);
        input.setText(prefs.get(PREF_HOST, KrakenHost.DEFAULT_HOST));
        input.setHint(KrakenHost.DEFAULT_HOST);

        new AlertDialog.Builder(getMapView().getContext())
                .setTitle("Radio address")
                .setMessage("Host name or IP of the Pi running krakensdr_doa. The "
                        + "stock image answers to " + KrakenHost.DEFAULT_HOST
                        + ". Add :port only if the status server was moved from "
                        + KrakenHost.DEFAULT_SHARE_PORT + ".")
                .setView(input)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String typed = input.getText().toString();
                        try {
                            KrakenHost host = KrakenHost.parse(typed);
                            prefs.set(PREF_HOST, typed.trim());
                            KrakenLink link = KrakenLink.get();
                            if (link != null && link.isRunning()) {
                                narrowedForThisConnection = false;
                                link.start(host);
                            }
                            refresh();
                        } catch (KrakenHost.InvalidHost e) {
                            toast(e.getMessage());
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void askForHeading() {
        final EditText input = new EditText(getMapView().getContext());
        input.setInputType(InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setSingleLine(true);
        input.setText(prefs.get(PREF_HEADING, ""));
        input.setHint("0 - 359");

        new AlertDialog.Builder(getMapView().getContext())
                .setTitle("Array heading")
                .setMessage("Degrees true that the antenna array's zero is pointing. "
                        + "The radio never adds this to a bearing, and it reports 0 "
                        + "both when it faces north and when it has no idea -- so "
                        + "setting it here is the only way the map shows a bearing "
                        + "to north rather than to the antenna.")
                .setView(input)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String typed = input.getText().toString().trim();
                        if (typed.isEmpty()) {
                            prefs.remove(PREF_HEADING);
                        } else {
                            try {
                                double deg = Double.parseDouble(typed);
                                prefs.set(PREF_HEADING, String.valueOf(deg));
                            } catch (NumberFormatException e) {
                                toast("that is not a number of degrees");
                                return;
                            }
                        }
                        draw();
                        refresh();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Opens the radio's own web GUI, in a drop down of our own.
     *
     * <p>See {@link RadioSetupDropDown} for why the radio's setup stays the
     * radio's rather than being rebuilt here, and why ATAK's own
     * {@code WebViewer} cannot show it.
     */
    private void openRadioSetup() {
        KrakenHost h;
        try {
            h = KrakenHost.parse(prefs.get(PREF_HOST, KrakenHost.DEFAULT_HOST));
        } catch (KrakenHost.InvalidHost e) {
            toast(e.getMessage());
            askForHost();
            return;
        }
        if (radioSetup == null)
            radioSetup = new RadioSetupDropDown(getMapView(), pluginContext);
        // The GUI is on the Dash port, not the status-file port.
        radioSetup.show("http://" + h.host() + ":" + GUI_PORT + "/");
    }

    private void toast(String message) {
        // MapView context, never the plugin context.
        Toast.makeText(getMapView().getContext(), message, Toast.LENGTH_LONG).show();
    }

    // ---- drawing and display -----------------------------------------------

    /** What the operator set, or null for "use whatever the radio says". */
    private Double operatorHeading() {
        String s = prefs.get(PREF_HEADING, "");
        if (s == null || s.trim().isEmpty())
            return null;
        try {
            return Double.valueOf(s.trim());
        } catch (NumberFormatException e) {
            Log.w(TAG, "unreadable heading preference: " + s);
            return null;
        }
    }

    private ArrayHeading heading() {
        KrakenLink link = KrakenLink.get();
        List<Bearing> latest = link == null ? null : link.latest();
        Bearing newest = latest == null || latest.isEmpty() ? null : latest.get(0);
        return ArrayHeading.resolve(operatorHeading(), newest);
    }

    private void draw() {
        KrakenLink link = KrakenLink.get();
        if (link == null)
            return;
        layer.draw(link.latest(), heading(), BearingLayer.selfPoint(getMapView()));
    }

    private void refresh() {
        KrakenLink link = KrakenLink.get();
        if (link == null)
            return;

        status.setText(link.status());
        hostButton.setText(prefs.get(PREF_HOST, KrakenHost.DEFAULT_HOST));

        boolean running = link.isRunning();
        connectButton.setText(running ? "Disconnect" : "Connect");
        connectButton.setTextColor(pluginContext.getResources().getColor(
                running ? R.color.on_green : R.color.off_red));

        if (link.activeFeed() != null) {
            feedLine.setVisibility(View.VISIBLE);
            feedLine.setText(link.activeFeedLimits());
        } else {
            feedLine.setVisibility(View.GONE);
        }

        ArrayHeading h = heading();
        headingProvenance.setText(h.provenance());
        headingProvenance.setTextColor(pluginContext.getResources().getColor(
                h.isKnown() ? R.color.white : R.color.warn_amber));

        buildRows(link, h);
        applyWidth();
        unverified.setText(unverifiedNote(link));
    }

    /**
     * The 0.1 honesty note, shown in the wide pane.
     *
     * <p>Which way each feed writes its bearing was read out of the Kraken's
     * source and has never been checked against a radio, and the source's own
     * comment says the CSV's mirror changes with an app release. Until the bench
     * run against a known transmitter, the pane says so rather than letting a
     * confident-looking line imply otherwise.
     */
    private String unverifiedNote(KrakenLink link) {
        String conv = link.activeConvention();
        StringBuilder sb = new StringBuilder();
        sb.append("Reading this feed as ");
        sb.append(conv.isEmpty() ? "-- " : conv);
        sb.append(". Not yet checked against a radio: bench it against a known ")
                .append("transmitter before trusting a bearing.");
        if (link.discardedInLastFrame() > 0)
            sb.append("  ").append(link.discardedInLastFrame())
                    .append(" reading(s) discarded in the last read.");
        if (!link.lastNote().isEmpty())
            sb.append("  ").append(link.lastNote());
        return sb.toString();
    }

    private void buildRows(KrakenLink link, ArrayHeading h) {
        List<Bearing> latest = link.latest();
        vfoRows.removeAllViews();

        if (latest == null || latest.isEmpty()) {
            bearingsNote.setVisibility(View.VISIBLE);
            bearingsNote.setText(link.isRunning()
                    ? "No bearing yet."
                    : "Not connected.");
            return;
        }

        boolean live = link.isLive();
        bearingsNote.setVisibility(live ? View.GONE : View.VISIBLE);
        if (!live) {
            long age = link.ageMs();
            // A two-minute-old bearing draws the same line as a live one, so
            // the number has to be on screen rather than implied.
            bearingsNote.setText(String.format(Locale.US,
                    "Nothing new for %s -- what is on the map is not current.",
                    Age.format(age)));
        }

        for (Bearing b : latest)
            vfoRows.addView(row(b, h, live));
    }

    private View row(Bearing b, ArrayHeading h, boolean live) {
        View v = PluginLayoutInflater.inflate(pluginContext, R.layout.vfo_row,
                (ViewGroup) null);
        TextView freq = v.findViewById(R.id.freq);
        TextView bearing = v.findViewById(R.id.bearing);
        TextView sub = v.findViewById(R.id.sub);
        TextView detail = v.findViewById(R.id.detail);

        freq.setText(String.format(Locale.US, "VFO %d   %.4f MHz", b.vfo, b.frequencyMHz()));

        // "rel" when the heading is unknown: a number of degrees on a north-up
        // map reads as a bearing to north, and this one is to an antenna.
        double shown = h.isKnown() ? b.trueBearing(h.degrees()) : b.arrayRelativeDeg;
        bearing.setText(String.format(Locale.US, "%.0f%s", shown,
                h.isKnown() ? "" : " rel"));
        bearing.setTextColor(pluginContext.getResources().getColor(
                live ? R.color.live_cyan : R.color.off_red));

        StringBuilder s = new StringBuilder();
        s.append(String.format(Locale.US, "confidence %.0f%%", b.confidence * 100.0));
        if (!Double.isNaN(b.powerDb))
            s.append(String.format(Locale.US, "   %.1f dB%s", b.powerDb,
                    b.powerClipped ? " (at the floor)" : ""));
        if (b.adcOverdrive)
            s.append("   FRONT END SATURATED");
        sub.setText(s.toString());

        StringBuilder d = new StringBuilder();
        d.append(String.format(Locale.US, "wire %.1f, array-relative %.1f",
                b.reportedDeg, b.arrayRelativeDeg));
        if (!Double.isNaN(b.snrDb))
            d.append(String.format(Locale.US, "   SNR %.1f dB", b.snrDb));
        if (b.correlatedSources >= 0)
            d.append("   sources ").append(b.correlatedSources);
        d.append(b.positionReported
                ? "   from the radio's GPS"
                : "   no radio GPS, drawn from this phone");
        if (b.spectrumSampleCount > 0)
            d.append(String.format(Locale.US,
                    "   spectrum %d pts (lobe in 0.2)", b.spectrumSampleCount));
        detail.setText(d.toString());
        detail.setVisibility(isWide() ? View.VISIBLE : View.GONE);

        return v;
    }
}

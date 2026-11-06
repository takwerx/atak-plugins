package com.atakmap.android.lces.iwi;

import com.atakmap.android.lces.plugin.R;
import com.atakmap.android.lces.ui.SlideConfirm;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.PointF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.atakmap.android.maps.MapEvent;
import com.atakmap.android.maps.MapEventDispatcher;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.PointMapItem;
import com.atakmap.android.maps.Shape;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.coremap.maps.coords.GeoPointMetaData;

import java.util.Date;
import java.util.List;

/**
 * IWI inside LCES: the IWI page, the trauma center details, the Medical Incident Report
 * and the Radio Script, by visibility swap (PLAN-IWI, "The pane"), in a block of
 * LCES's pane. Copied forward from the IWI plugin on 2026-11-03 (PLAN-LCES decision 9);
 * the trauma center Settings rows moved to LCES's Settings page.
 *
 * <p>Declare walks through where the patient is (My Location, or a tap on the map),
 * confirms it, and puts one CASEVAC marker on this map. Clear is two selections, the
 * button and a dialog naming what goes. Every dialog is built on the MapView's context.
 */
public final class IwiPane implements TraumaPane.Pages, TraumaPane.PointSource,
        ReportForm.Host {

    /** What IWI asks of the LCES pane around it. */
    public interface Exits {
        /** Back from the IWI page: the LCES main screen. */
        void leaveIwi();

        /** The IWI page's Settings: LCES's Settings page, whose Back comes here. */
        void openSettings();

        /** Wide or narrow. */
        void toggleWide();
    }

    private static final String TAG = "LCES-IWI-Pane";
    private static final long TICK_MS = 30_000L;
    /** Steps 1 to 6: what the initial call needs before the IWI is declared. */
    private static final int STEPS = 6;
    private static final String[] STEP_NAMES = {
            "Who You Call", "Patients", "What Happened", "Evacuation Request",
            "Medical on Scene", "Resources"
    };

    private final Context ctx;
    private final MapView mapView;
    private final IwiState state;
    private final TraumaPane trauma;
    private final ReportForm form;

    private final View mainPage;
    private final View mainScroll;
    private final View detailsPage;
    private final View reportPage;
    private final View scriptPage;
    private final TextView status;
    private final Button declareButton;
    private final View liveBlock;
    private final TextView liveTime;
    private final TextView liveWhere;
    private final TextView livePatients;
    private final Button moveButton;
    private final LinearLayout scriptContainer;
    /** Steps: what the initial call needs, one row each, red until filled in. */
    private Button stepsBar;
    /** 1 to 6 in the pinned bar: green filled in, red edge while missing. */
    private final TextView[] stepBoxes = new TextView[STEPS];
    private final Button[] stepRows = new Button[STEPS];
    private final Button scriptButton;
    /** Red, Yellow, Green, Black: the count boxes pinned over every page. */
    private final TextView[] tally = new TextView[4];
    private final TextView tallyTotal;
    private final Exits exits;
    /** Where the script page's Back goes: the main page or the report. */
    private View scriptReturn;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateLive();
            handler.postDelayed(this, TICK_MS);
        }
    };

    /** A location set and shown on the map, waiting for Declare, Pick again or Cancel. */
    private GeoPoint pendingPoint;
    private String pendingSource;
    private boolean pendingMove;
    private final View confirmBlock;
    private final TextView confirmTitle;
    private final TextView confirmWhere;
    private final SlideConfirm confirmSlider;
    /** Clear IWI, asked in place: what goes, a slider, Cancel. */
    private final View clearBlock;
    private final SlideConfirm clearSlider;
    /** The big draggable marker shown while a location waits. */
    private final PreviewMarker preview;
    /** The report page's scroller, so the next step opens at the top. */
    private final View reportScroll;

    /** True while the next tap on the map is the patient's location. */
    private boolean picking;
    /** Whether that pick moves a declared IWI rather than declaring one. */
    private boolean pickMoves;

    public IwiPane(Context pluginContext, MapView mapView, View root, View settingsRoot,
            TraumaManager manager, Exits exits) {
        this.ctx = pluginContext;
        this.mapView = mapView;
        this.state = new IwiState(manager.prefs());
        this.exits = exits;

        mainPage = root.findViewById(R.id.iwi_main_page);
        mainScroll = root.findViewById(R.id.iwi_main_scroll);
        detailsPage = root.findViewById(R.id.iwi_details_page);
        reportPage = root.findViewById(R.id.iwi_report_page);
        scriptPage = root.findViewById(R.id.iwi_script_page);
        status = root.findViewById(R.id.iwi_status);
        declareButton = root.findViewById(R.id.iwi_btn_declare);
        liveBlock = root.findViewById(R.id.iwi_live_block);
        liveTime = root.findViewById(R.id.iwi_live_time);
        liveWhere = root.findViewById(R.id.iwi_live_where);
        livePatients = root.findViewById(R.id.iwi_live_patients);
        moveButton = root.findViewById(R.id.iwi_btn_move);
        scriptContainer = root.findViewById(R.id.iwi_script_container);
        scriptButton = root.findViewById(R.id.iwi_btn_script);
        stepsBar = root.findViewById(R.id.iwi_steps_bar);
        final LinearLayout boxes = root.findViewById(R.id.iwi_steps_boxes);
        for (int i = 0; i < STEPS; i++) {
            final int step = i + 1;
            final TextView box = new TextView(pluginContext);
            box.setText(Integer.toString(step));
            box.setTextSize(18);
            box.setTypeface(Typeface.DEFAULT_BOLD);
            box.setGravity(android.view.Gravity.CENTER);
            box.setContentDescription("Step " + step + ": " + STEP_NAMES[i]);
            box.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    goToStep(step, false);
                }
            });
            final LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(38),
                    LinearLayout.LayoutParams.MATCH_PARENT);
            if (i > 0)
                blp.leftMargin = dp(4);
            boxes.addView(box, blp);
            stepBoxes[i] = box;
        }
        stepsBar.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                nextStep();
            }
        });
        tally[0] = root.findViewById(R.id.iwi_tally_red);
        tally[1] = root.findViewById(R.id.iwi_tally_yellow);
        tally[2] = root.findViewById(R.id.iwi_tally_green);
        tally[3] = root.findViewById(R.id.iwi_tally_black);
        tallyTotal = root.findViewById(R.id.iwi_tally_total);
        root.findViewById(R.id.iwi_btn_wide).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                IwiPane.this.exits.toggleWide();
            }
        });
        styleTally();
        for (int i = 0; i < tally.length; i++) {
            final String c = Patient.COLORS[i];
            tally[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openPatients(c);
                }
            });
        }
        tallyTotal.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openPatients(null);
            }
        });
        confirmBlock = root.findViewById(R.id.iwi_confirm_block);
        confirmTitle = root.findViewById(R.id.iwi_confirm_title);
        confirmWhere = root.findViewById(R.id.iwi_confirm_where);
        confirmSlider = new SlideConfirm(pluginContext);
        confirmSlider.setColor(color(R.color.triage_red));
        ((android.view.ViewGroup) root.findViewById(R.id.iwi_confirm_slider))
                .addView(confirmSlider);
        clearBlock = root.findViewById(R.id.iwi_clear_block);
        clearSlider = new SlideConfirm(pluginContext);
        clearSlider.setColor(color(R.color.state_off));
        clearSlider.setLabel("Slide to Clear IWI");
        ((android.view.ViewGroup) root.findViewById(R.id.iwi_clear_slider))
                .addView(clearSlider);
        reportScroll = (View) root.findViewById(R.id.iwi_report_container).getParent();
        preview = new PreviewMarker(mapView, new PreviewMarker.OnMoved() {
            @Override
            public void onMoved(final GeoPoint p) {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (pendingPoint == null)
                            return;
                        pendingPoint = p;
                        pendingSource = IwiState.SOURCE_MAP;
                        showWhere();
                    }
                });
            }
        });
        confirmSlider.setOnConfirmed(new SlideConfirm.OnConfirmed() {
            @Override
            public void onConfirmed() {
                confirmPending();
            }
        });
        clearSlider.setOnConfirmed(new SlideConfirm.OnConfirmed() {
            @Override
            public void onConfirmed() {
                clearNow();
            }
        });
        root.findViewById(R.id.iwi_btn_clear_cancel).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        hideClear();
                        updateMain();
                    }
                });
        root.findViewById(R.id.iwi_btn_confirm_cancel).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        cancelPending();
                        status(null);
                        updateMain();
                    }
                });

        declareButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (picking)
                    cancelPick();
                else if (state.isLive())
                    askClear();
                else if (firstMissing() > 0)
                    // The information first: an IWI declared without it is no use.
                    goToStep(firstMissing(), true);
                else
                    askWhere(false);
            }
        });
        moveButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (picking)
                    cancelPick();
                else
                    askWhere(true);
            }
        });
        root.findViewById(R.id.iwi_btn_go_iwi).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final GeoPoint p = state.point();
                if (p != null)
                    IwiPane.this.mapView.getMapController().panTo(p, true);
            }
        });
        root.findViewById(R.id.iwi_btn_settings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                trauma.refreshSettings();
                IwiPane.this.exits.openSettings();
            }
        });
        root.findViewById(R.id.iwi_btn_main_back).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        leave();
                    }
                });
        root.findViewById(R.id.iwi_btn_report).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                form.refresh();
                show(reportPage);
            }
        });
        root.findViewById(R.id.iwi_btn_script).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                buildScript();
                scriptReturn = mainPage;
                show(scriptPage);
            }
        });
        root.findViewById(R.id.iwi_btn_script_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (scriptReturn == reportPage)
                    show(reportPage);
                else
                    showMain();
            }
        });
        root.findViewById(R.id.iwi_btn_report_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // The marker carries what the report says, for a phone without IWI.
                if (state.isLive())
                    IwiMarker.write(state);
                showMain();
            }
        });
        root.findViewById(R.id.iwi_btn_report_script).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (state.isLive())
                            IwiMarker.write(state);
                        buildScript();
                        scriptReturn = reportPage;
                        show(scriptPage);
                    }
                });

        form = new ReportForm(pluginContext, mapView, state, manager.prefs(),
                (LinearLayout) root.findViewById(R.id.iwi_report_container), this);
        trauma = new TraumaPane(mapView, root, settingsRoot, manager, this, this);
        buildSteps((LinearLayout) root.findViewById(R.id.iwi_steps_block), manager);
        updateMain();
        handler.postDelayed(tick, TICK_MS);
    }

    /** Whether a tapped map item is this device's IWI marker or the waiting preview. */
    public static boolean isOurs(TraumaManager manager, String uid) {
        if (uid == null)
            return false;
        final IwiState state = new IwiState(manager.prefs());
        return PreviewMarker.UID.equals(uid) || (state.isLive() && state.uid().equals(uid));
    }

    /** The IWI tile, or a tap on this device's IWI marker: the IWI page, current. */
    public void open() {
        showMain();
        mainScroll.scrollTo(0, 0);
    }

    /**
     * Back, from LCES's pane: a page of IWI's goes to the IWI page, the way its own Back
     * button does; the IWI page goes to the LCES main screen.
     */
    public void back() {
        if (reportPage.getVisibility() == View.VISIBLE) {
            if (state.isLive())
                IwiMarker.write(state);
            showMain();
        } else if (scriptPage.getVisibility() == View.VISIBLE) {
            if (scriptReturn == reportPage)
                show(reportPage);
            else
                showMain();
        } else if (detailsPage.getVisibility() == View.VISIBLE) {
            showMain();
        } else {
            leave();
        }
    }

    /** Off to the LCES main screen: a pick or a waiting location lets go of the map. */
    private void leave() {
        onClosed();
        exits.leaveIwi();
    }

    /** LCES's Settings changed the cruise speed or fetched the data. */
    public void refreshSettings() {
        trauma.refreshSettings();
        trauma.refresh();
    }

    /** The pane closed, or LCES left IWI: a pick in progress lets go of the map. */
    public void onClosed() {
        cancelPick();
        cancelPending();
        hideClear();
    }

    public void dispose() {
        cancelPick();
        cancelPending();
        handler.removeCallbacks(tick);
    }

    // ---- pages ----

    private void show(View page) {
        // A word about what just happened belongs to the main page; on a page of its
        // own it is stale and costs the room the form needs. A pick prompt stays.
        if (page != mainPage && !picking)
            status(null);
        mainPage.setVisibility(page == mainPage ? View.VISIBLE : View.GONE);
        detailsPage.setVisibility(page == detailsPage ? View.VISIBLE : View.GONE);
        reportPage.setVisibility(page == reportPage ? View.VISIBLE : View.GONE);
        scriptPage.setVisibility(page == scriptPage ? View.VISIBLE : View.GONE);
    }

    @Override
    public void showMain() {
        updateMain();
        show(mainPage);
    }

    @Override
    public void showDetails() {
        show(detailsPage);
    }

    @Override
    public void status(String message) {
        status.setText(message == null ? "" : message);
        status.setVisibility(message == null || message.isEmpty() ? View.GONE : View.VISIBLE);
    }

    // ---- where the trauma line measures from ----

    @Override
    public GeoPoint point() {
        if (state.isLive())
            return state.point();
        return TraumaPane.selfPoint(mapView);
    }

    @Override
    public String label() {
        return state.isLive() ? "from the patient" : "from My Location";
    }

    // ---- the tally ----

    /** Each box filled in its triage color; text in the color that reads on it. */
    private void styleTally() {
        final int[] fill = {
                color(R.color.triage_red), color(R.color.triage_yellow),
                color(R.color.triage_green), Color.BLACK
        };
        final int[] text = {Color.WHITE, Color.BLACK, Color.BLACK, Color.WHITE};
        for (int i = 0; i < tally.length; i++) {
            final android.graphics.drawable.GradientDrawable box =
                    new android.graphics.drawable.GradientDrawable();
            box.setCornerRadius(dp(4));
            box.setColor(fill[i]);
            if (i == 3)
                box.setStroke(dp(2), Color.WHITE);
            tally[i].setBackground(box);
            tally[i].setTextColor(text[i]);
            tally[i].setContentDescription(Patient.name(Patient.COLORS[i]));
        }
        // The total is not a triage color: ATAK's own dark button face and edge.
        final android.graphics.drawable.GradientDrawable box =
                new android.graphics.drawable.GradientDrawable();
        box.setCornerRadius(dp(4));
        box.setColor(0xFF383838);
        box.setStroke(dp(2), 0xFF585858);
        tallyTotal.setBackground(box);
    }

    @Override
    public void countsChanged() {
        refreshTally();
        refreshSteps();
    }

    private void refreshTally() {
        for (int i = 0; i < tally.length; i++)
            tally[i].setText(Integer.toString(state.count(Patient.COLORS[i])));
        final SpannableStringBuilder b = new SpannableStringBuilder("Total ");
        b.setSpan(new android.text.style.RelativeSizeSpan(0.7f), 0, b.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        b.append(Integer.toString(state.patientCount()));
        tallyTotal.setText(b);
    }

    // ---- the main page ----

    private void updateMain() {
        refreshTally();
        final boolean live = state.isLive();
        if (picking) {
            // The button that started the pick is the one that stops it.
            (pickMoves ? moveButton : declareButton).setText("Cancel");
            (pickMoves ? moveButton : declareButton).setTextColor(color(R.color.amber));
        }
        final int left = stepsLeft();
        if (!picking || pickMoves) {
            if (live) {
                declareButton.setText("Clear IWI");
                declareButton.setTextColor(color(R.color.state_off));
            } else if (left > 0) {
                declareButton.setText("Declare IWI: " + left
                        + (left == 1 ? " step left" : " steps left"));
                declareButton.setTextColor(color(R.color.guide_grey));
            } else {
                declareButton.setText("Declare IWI");
                declareButton.setTextColor(Color.WHITE);
            }
        }
        refreshSteps();
        // Declared with every step filled in: the call is next, until it is made.
        scriptButton.setTextColor(live && left == 0 && reportedMs() == 0
                ? color(R.color.state_on) : Color.WHITE);
        if (!picking || !pickMoves) {
            moveButton.setText("Change Location");
            moveButton.setTextColor(Color.WHITE);
        }
        // While a location waits, the confirm above is the only Declare on the page.
        final boolean waiting = pendingPoint != null;
        if (!live)
            hideClear();
        final boolean clearing = clearBlock.getVisibility() == View.VISIBLE;
        declareButton.setVisibility(waiting || clearing ? View.GONE : View.VISIBLE);
        moveButton.setEnabled(!waiting);
        liveBlock.setVisibility(live ? View.VISIBLE : View.GONE);
        updateLive();
        trauma.refresh();
    }

    private void updateLive() {
        if (!state.isLive())
            return;
        final long at = state.declaredMs();
        final String clock = Clock.hhmm(at);
        liveTime.setText("Declared " + clock + ", " + ago(System.currentTimeMillis() - at));
        final GeoPoint p = state.point();
        liveWhere.setText(Coords.ddm(p.getLatitude(), p.getLongitude()) + "\n"
                + (IwiState.SOURCE_MAP.equals(state.source())
                        ? "Set on the map" : "My Location at the time"));
        final String who = RadioScript.summary(state);
        livePatients.setText(who.isEmpty() ? "No patients counted yet" : who);
    }

    static String ago(long ms) {
        final long min = Math.max(0, ms / 60_000L);
        if (min < 1)
            return "just now";
        if (min < 60)
            return min + " min ago";
        return (min / 60) + " h " + (min % 60) + " min ago";
    }

    // ---- declare, move: where is the patient? ----

    private void askWhere(final boolean move) {
        final GeoPoint self = TraumaPane.selfPoint(mapView);
        final String mine = self == null ? "My Location\nNo position yet"
                : "My Location\n" + Coords.ddm(self.getLatitude(), self.getLongitude());
        new AlertDialog.Builder(mapView.getContext())
                .setTitle("Where is the patient?")
                .setItems(new String[] {
                        mine, "Set it on the map"
                }, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0 && self != null)
                            placePending(self, IwiState.SOURCE_SELF, move);
                        else
                            beginPick(move, which == 0);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * The point is chosen: the marker goes on the map now, and the pane asks whether
     * this is the place, so nothing covers the map while the operator checks it.
     */
    private void placePending(GeoPoint p, String source, boolean move) {
        pendingPoint = p;
        pendingSource = source;
        pendingMove = move;
        preview.show(p, move ? "IWI new location" : "IWI (not declared)");
        // A map tap is already in view; My Location may not be.
        if (IwiState.SOURCE_SELF.equals(source))
            mapView.getMapController().panTo(p, true);
        confirmTitle.setText(move ? "Move the IWI here?" : "Confirm the IWI location");
        showWhere();
        confirmSlider.setLabel(move ? "Slide to Move IWI" : "Slide to Confirm IWI");
        confirmSlider.reset();
        hideClear();
        confirmBlock.setVisibility(View.VISIBLE);
        status(null);
        show(mainPage);
        mainScroll.scrollTo(0, 0);
        updateMain();
    }

    private void showWhere() {
        final GeoPoint p = pendingPoint;
        if (p == null)
            return;
        confirmWhere.setText(Coords.ddm(p.getLatitude(), p.getLongitude()) + " (DDM)\n"
                + (IwiState.SOURCE_MAP.equals(pendingSource) ? "Set on the map"
                        : "My Location"));
    }

    private void confirmPending() {
        final GeoPoint p = pendingPoint;
        final String source = pendingSource;
        final boolean move = pendingMove;
        cancelPending();
        if (p == null)
            return;
        if (move)
            state.move(p, source);
        else
            state.declare(p, source);
        if (!IwiMarker.write(state))
            status("Could not put the IWI marker on the map.");
        else
            status(move ? "IWI moved." : null);
        form.refresh();
        updateMain();
        if (!move) {
            // Declared: straight on to the initial report.
            form.openInitial();
            show(reportPage);
            // After layout, so item 2's position is known: it opens at the top.
            reportScroll.post(new Runnable() {
                @Override
                public void run() {
                    reportScroll.scrollTo(0, form.initialTop());
                }
            });
        }
    }

    /** Drop a location that was never confirmed, and its marker. */
    private void cancelPending() {
        pendingPoint = null;
        pendingSource = null;
        preview.hide();
        hideConfirm();
    }

    private void hideConfirm() {
        confirmBlock.setVisibility(View.GONE);
    }

    // ---- steps: the information first, then Declare, then the call ----

    private void buildSteps(LinearLayout body, TraumaManager manager) {
        for (int i = 0; i < STEPS; i++) {
            final int step = i + 1;
            final Button row = new Button(ctx, null, 0, R.style.TakwerxButton);
            row.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
            row.setSingleLine(false);
            row.setMaxLines(2);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    goToStep(step, false);
                }
            });
            final LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = dp(4);
            body.addView(row, rlp);
            stepRows[i] = row;
        }
    }

    /** Each row: its number and name, then what it says, or what is missing. */
    private void refreshSteps() {
        if (stepRows[0] == null)
            return;
        final int left = stepsLeft();
        final int first = firstMissing();
        final long reported = state.isLive() ? reportedMs() : 0L;
        if (reported > 0) {
            // The call is made: nothing left to nag about.
            stepsBar.setText("Initial report made " + Clock.hhmm(reported));
        } else {
            final String next = first > 0 ? first + ". " + STEP_NAMES[first - 1]
                    : !state.isLive() ? "Declare IWI" : "Radio Script";
            stepsBar.setText((STEPS - left) + " of " + STEPS + "   Next: " + next);
        }
        stepsBar.setTextColor(left == 0 ? color(R.color.state_on) : Color.WHITE);
        for (int i = 0; i < STEPS; i++) {
            final boolean done = stepDone(i + 1);
            final android.graphics.drawable.GradientDrawable box =
                    new android.graphics.drawable.GradientDrawable();
            box.setCornerRadius(dp(4));
            if (done) {
                box.setColor(color(R.color.state_on));
                stepBoxes[i].setTextColor(Color.BLACK);
            } else {
                box.setColor(0xFF1E1E1E);
                box.setStroke(dp(2), color(R.color.state_off));
                stepBoxes[i].setTextColor(color(R.color.state_off));
            }
            stepBoxes[i].setBackground(box);
        }
        for (int i = 0; i < STEPS; i++) {
            final int step = i + 1;
            final boolean done = stepDone(step);
            final SpannableStringBuilder b = new SpannableStringBuilder(
                    step + ". " + STEP_NAMES[i]);
            b.setSpan(new StyleSpan(Typeface.BOLD), 0, b.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            final String value = done ? stepValue(step) : stepMissing(step);
            if (!value.isEmpty()) {
                b.append('\n');
                final int at = b.length();
                b.append(value);
                b.setSpan(new android.text.style.RelativeSizeSpan(0.85f), at, b.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            stepRows[i].setText(b);
            stepRows[i].setTextColor(done ? color(R.color.state_on)
                    : color(R.color.state_off));
        }
    }

    private boolean stepDone(int step) {
        switch (step) {
            case 1: return RadioScript.contact(state).complete();
            case 2: return state.patientCount() > 0;
            case 3: return !state.text(IwiState.NATURE).isEmpty();
            case 4: return !state.text(IwiState.EVAC_REQUEST).isEmpty();
            case 5: return !state.text(IwiState.CARE_ROLE).isEmpty();
            default: return RadioScript.resources(state).complete();
        }
    }

    private String stepValue(int step) {
        switch (step) {
            case 1: return RadioScript.calling(state);
            case 2: return RadioScript.summary(state);
            case 3: return Vocab.labels(state.text(IwiState.NATURE), Vocab.MEDICAL,
                    Vocab.TRAUMA);
            case 4: return Vocab.labels(state.text(IwiState.EVAC_REQUEST), Vocab.EVAC);
            case 5: return Vocab.labels(state.text(IwiState.CARE_ROLE), Vocab.CARE);
            default: return Vocab.labels(state.text(IwiState.RESOURCES), Vocab.RESOURCES);
        }
    }

    private String stepMissing(int step) {
        switch (step) {
            case 1: return "Set your callsign in ATAK";
            case 2: return "Count them by triage color";
            case 3: return "Medical or trauma: pick from the list";
            case 4: return "Air ambulance, short-haul, ground...";
            case 5: return "None, EMT or Paramedic";
            default: return "What you need, or None needed";
        }
    }

    /** When the initial call was acknowledged, or 0 when it has not been made. */
    private long reportedMs() {
        try {
            return Long.parseLong(state.text(IwiState.REPORTED));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** 0 when every step is filled in. */
    private int firstMissing() {
        for (int step = 1; step <= STEPS; step++)
            if (!stepDone(step))
                return step;
        return 0;
    }

    private int stepsLeft() {
        int n = 0;
        for (int step = 1; step <= STEPS; step++)
            if (!stepDone(step))
                n++;
        return n;
    }

    /** The pinned bar: the first missing step, else Declare, else the call. */
    private void nextStep() {
        final int first = firstMissing();
        if (first > 0) {
            goToStep(first, false);
        } else if (!state.isLive()) {
            showMain();
            mainScroll.scrollTo(0, 0);
            if (!picking && pendingPoint == null)
                askWhere(false);
        } else if (reportedMs() > 0) {
            // Made: what comes next is the follow-ups, in the report.
            form.refresh();
            show(reportPage);
        } else {
            buildScript();
            scriptReturn = mainPage;
            show(scriptPage);
        }
    }

    /** A count box: the report at that color's patients, or all of them for Total. */
    private void openPatients(String color) {
        if (pendingPoint != null || picking)
            return;
        form.refresh();
        show(reportPage);
        form.openPatients(color);
    }

    /** The report, opened at that step's spot. */
    private void goToStep(int step, boolean fromDeclare) {
        form.refresh();
        show(reportPage);
        form.openStep(step);
        if (fromDeclare) {
            final int left = stepsLeft();
            status("Fill in the steps first: " + left + (left == 1 ? " left." : " left.")
                    + " Then Declare.");
        }
    }

    // ---- clear: Clear IWI, then a slide ----

    private void askClear() {
        clearSlider.reset();
        clearBlock.setVisibility(View.VISIBLE);
        status(null);
        updateMain();
        mainScroll.scrollTo(0, 0);
    }

    private void hideClear() {
        clearBlock.setVisibility(View.GONE);
    }

    private void clearNow() {
        hideClear();
        IwiMarker.remove(mapView, state.uid());
        state.clear();
        form.refresh();
        status("IWI cleared.");
        updateMain();
    }

    // ---- a tap on the map ----

    private final MapEventDispatcher.MapEventDispatchListener pickListener =
            new MapEventDispatcher.MapEventDispatchListener() {
                @Override
                public void onMapEvent(MapEvent event) {
                    GeoPoint gp = null;
                    if (MapEvent.MAP_CLICK.equals(event.getType())) {
                        final PointF pf = event.getPointF();
                        if (pf != null) {
                            final GeoPointMetaData m = mapView.inverseWithElevation(pf.x,
                                    pf.y);
                            gp = m == null ? null : m.get();
                        }
                    } else if (MapEvent.ITEM_CLICK.equals(event.getType())) {
                        // A tap on a marker takes the marker's own point.
                        final MapItem item = event.getItem();
                        if (item instanceof PointMapItem)
                            gp = ((PointMapItem) item).getPoint();
                        else if (item instanceof Shape)
                            gp = ((Shape) item).getClickPoint();
                        event.getExtras().putBoolean("eventNotHandled", gp == null);
                    }
                    if (gp == null || !gp.isValid())
                        return;
                    final GeoPoint chosen = new GeoPoint(gp.getLatitude(), gp.getLongitude());
                    final boolean move = pickMoves;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            endPick();
                            placePending(chosen, IwiState.SOURCE_MAP, move);
                        }
                    });
                }
            };

    private void beginPick(boolean move, boolean noFix) {
        if (picking)
            return;
        try {
            final MapEventDispatcher d = mapView.getMapEventDispatcher();
            d.pushListeners();
            d.clearListeners(MapEvent.MAP_CLICK);
            d.clearListeners(MapEvent.ITEM_CLICK);
            d.clearListeners(MapEvent.ITEM_LONG_PRESS);
            d.addMapEventListener(MapEvent.MAP_CLICK, pickListener);
            d.addMapEventListener(MapEvent.ITEM_CLICK, pickListener);
            mapView.getMapTouchController().setToolActive(true);
        } catch (RuntimeException e) {
            Log.w(TAG, "could not take the map for a tap", e);
            return;
        }
        picking = true;
        pickMoves = move;
        status((noFix ? "No position for My Location yet. " : "")
                + "Tap the patient's location on the map.");
        updateMain();
    }

    private void endPick() {
        if (!picking)
            return;
        picking = false;
        try {
            mapView.getMapEventDispatcher().popListeners();
            mapView.getMapTouchController().setToolActive(false);
        } catch (RuntimeException e) {
            Log.w(TAG, "could not give the map back", e);
        }
    }

    private void cancelPick() {
        if (!picking)
            return;
        endPick();
        cancelPending();
        status(null);
        updateMain();
    }

    // ---- the radio scripts ----

    /** The Radio Script page: the initial report, then whatever the follow-ups say. */
    private void buildScript() {
        scriptContainer.removeAllViews();
        scriptContainer.addView(note(ctx.getString(R.string.script_how)));
        final GeoPoint p = state.isLive() ? state.point() : null;
        final String coords = p == null ? null
                : Coords.ddm(p.getLatitude(), p.getLongitude());
        scriptContainer.addView(heading("Initial report"));
        boolean anyMissing = render(RadioScript.initial(state, coords));
        scriptContainer.addView(heading("Follow-up"));
        final List<RadioScript.Section> more = RadioScript.followUp(state);
        if (more.isEmpty()) {
            scriptContainer.addView(note(ctx.getString(R.string.script_nothing_yet)));
        } else {
            scriptContainer.addView(quoted(RadioScript.updateLead(state)));
            scriptContainer.addView(action(RadioScript.answer(state)));
            anyMissing |= render(more);
        }
        finishScript(anyMissing);
        addReportedSlider();
    }

    /**
     * The end of the script: "I made the call" (operator, 2026-11-06). Slid once the
     * initial report is acknowledged; the time is kept and the Steps bar stops pointing
     * at the Radio Script.
     */
    private void addReportedSlider() {
        if (!state.isLive())
            return;
        final long reported = reportedMs();
        if (reported > 0) {
            final TextView done = line("Initial report made " + Clock.hhmm(reported) + ".",
                    16, color(R.color.state_on));
            done.setTypeface(Typeface.DEFAULT_BOLD);
            scriptContainer.addView(done);
            return;
        }
        scriptContainer.addView(heading("When the call is acknowledged"));
        final SlideConfirm slider = new SlideConfirm(ctx);
        slider.setColor(color(R.color.state_on));
        slider.setLabel("Slide: Initial Report Made");
        slider.setOnConfirmed(new SlideConfirm.OnConfirmed() {
            @Override
            public void onConfirmed() {
                state.set(IwiState.REPORTED, Long.toString(System.currentTimeMillis()));
                showMain();
                status("Initial report made " + Clock.hhmm(reportedMs()) + ".");
            }
        });
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        lp.bottomMargin = dp(8);
        scriptContainer.addView(slider, lp);
    }

    /** One patient's follow-up, from its card's Radio update. */
    @Override
    public void patientScript(int n) {
        if (state.isLive())
            IwiMarker.write(state);
        final Patient patient = state.patient(n);
        if (patient == null)
            return;
        scriptContainer.removeAllViews();
        scriptContainer.addView(note(ctx.getString(R.string.script_how)));
        scriptContainer.addView(heading("Update: " + patient.label()));
        scriptContainer.addView(quoted(RadioScript.updateLead(state)));
        scriptContainer.addView(action(RadioScript.answer(state)));
        final RadioScript.Section one = RadioScript.patient(state, patient);
        one.endsWith(RadioScript.acknowledge(state));
        final List<RadioScript.Section> list = new java.util.ArrayList<>();
        list.add(one);
        finishScript(render(list));
        scriptReturn = reportPage;
        show(scriptPage);
    }

    /** @return whether anything in them is not filled in */
    /**
     * The IRPG's own pattern (operator, 2026-10-14): the step in yellow on its own
     * line, then what is said, in quotes, in white, "Break" included because it is a
     * word that is said, then what to do in yellow: pause, or wait for an answer.
     * What is not filled in shows in red brackets where it would be said.
     *
     * @return whether anything in them is not filled in
     */
    private boolean render(List<RadioScript.Section> sections) {
        boolean anyMissing = false;
        for (RadioScript.Section s : sections) {
            final TextView title = line(s.number + ". " + s.title, 17, color(R.color.amber));
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setPadding(0, dp(14), 0, 0);
            scriptContainer.addView(title);

            for (RadioScript.Lead lead : s.leads) {
                scriptContainer.addView(quoted(lead.said));
                scriptContainer.addView(action(lead.then));
            }

            final SpannableStringBuilder b = new SpannableStringBuilder("\"");
            for (RadioScript.Part part : s.parts) {
                final int start = b.length();
                b.append(part.text);
                if (part.missing) {
                    anyMissing = true;
                    b.setSpan(new ForegroundColorSpan(color(R.color.state_off)), start,
                            b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
            // A section's words never start with a space, but they can end with one.
            while (b.length() > 1 && b.charAt(b.length() - 1) == ' ')
                b.delete(b.length() - 1, b.length());
            if (s.saysBreak)
                b.append(" Break.");
            b.append("\"");
            final TextView said = line("", 20, Color.WHITE);
            said.setText(b);
            said.setPadding(0, dp(4), 0, dp(4));
            scriptContainer.addView(said);

            if (s.after != null)
                scriptContainer.addView(action(s.after));
        }
        return anyMissing;
    }

    /** What to do, not say: pause, or wait for an answer. Yellow. */
    private TextView action(String what) {
        final TextView t = line(what, 16, color(R.color.amber));
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, 0, 0, 0);
        return t;
    }

    /** The opening of a follow-up call, said, in quotes. */
    private TextView quoted(String words) {
        final TextView t = line("\"" + words + "\"", 20, Color.WHITE);
        t.setPadding(0, dp(8), 0, dp(4));
        return t;
    }

    private void finishScript(boolean anyMissing) {
        if (anyMissing)
            scriptContainer.addView(note(ctx.getString(R.string.script_missing)));
        final TextView credit = note(ctx.getString(R.string.script_credit));
        credit.setPadding(0, dp(16), 0, dp(4));
        scriptContainer.addView(credit);
    }

    private TextView line(String text, int sp, int color) {
        final TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.15f);
        t.setPadding(0, dp(12), 0, dp(4));
        t.setTextIsSelectable(true);
        return t;
    }

    private TextView heading(String text) {
        final TextView t = new TextView(ctx, null, 0, R.style.TakwerxSectionHeading);
        t.setText(text);
        t.setPadding(0, dp(18), 0, dp(2));
        return t;
    }

    private TextView note(String text) {
        final TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(13);
        t.setTextColor(color(R.color.guide_grey));
        t.setPadding(0, dp(6), 0, dp(2));
        return t;
    }

    private int color(int id) {
        return ctx.getResources().getColor(id);
    }

    private int dp(int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}

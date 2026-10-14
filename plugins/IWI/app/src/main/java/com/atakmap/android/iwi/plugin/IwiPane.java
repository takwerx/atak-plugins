package com.atakmap.android.iwi.plugin;

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
 * IWI's one pane: the main page, Settings, the trauma center details, the Medical
 * Incident Report and the Radio Script, by visibility swap (PLAN-IWI, "The pane").
 *
 * <p>Declare walks through where the patient is (My Location, or a tap on the map),
 * confirms it, and puts one CASEVAC marker on this map. Clear is two selections, the
 * button and a dialog naming what goes. Every dialog is built on the MapView's context.
 */
public final class IwiPane implements TraumaPane.Pages, TraumaPane.PointSource,
        ReportForm.Host {

    private static final String TAG = "IWI-Pane";
    private static final long TICK_MS = 30_000L;

    private final Context ctx;
    private final MapView mapView;
    private final IwiState state;
    private final TraumaPane trauma;
    private final ReportForm form;

    private final View mainPage;
    private final View settingsPage;
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
    private final Button confirmButton;
    /** The big draggable marker shown while a location waits. */
    private final PreviewMarker preview;
    /** The report page's scroller, so the next step opens at the top. */
    private final View reportScroll;

    /** True while the next tap on the map is the patient's location. */
    private boolean picking;
    /** Whether that pick moves a declared IWI rather than declaring one. */
    private boolean pickMoves;

    public IwiPane(Context pluginContext, MapView mapView, View root, TraumaManager manager) {
        this.ctx = pluginContext;
        this.mapView = mapView;
        this.state = new IwiState(manager.prefs());

        mainPage = root.findViewById(R.id.main_page);
        settingsPage = root.findViewById(R.id.settings_page);
        detailsPage = root.findViewById(R.id.details_page);
        reportPage = root.findViewById(R.id.report_page);
        scriptPage = root.findViewById(R.id.script_page);
        status = root.findViewById(R.id.status);
        declareButton = root.findViewById(R.id.btn_declare);
        liveBlock = root.findViewById(R.id.live_block);
        liveTime = root.findViewById(R.id.live_time);
        liveWhere = root.findViewById(R.id.live_where);
        livePatients = root.findViewById(R.id.live_patients);
        moveButton = root.findViewById(R.id.btn_move);
        scriptContainer = root.findViewById(R.id.script_container);
        confirmBlock = root.findViewById(R.id.confirm_block);
        confirmTitle = root.findViewById(R.id.confirm_title);
        confirmWhere = root.findViewById(R.id.confirm_where);
        confirmButton = root.findViewById(R.id.btn_confirm);
        reportScroll = (View) root.findViewById(R.id.report_container).getParent();
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
        confirmButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmPending();
            }
        });
        root.findViewById(R.id.btn_confirm_cancel).setOnClickListener(
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
                    confirmClear();
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
        root.findViewById(R.id.btn_go_iwi).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final GeoPoint p = state.point();
                if (p != null)
                    IwiPane.this.mapView.getMapController().panTo(p, true);
            }
        });
        root.findViewById(R.id.btn_settings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                trauma.refreshSettings();
                show(settingsPage);
            }
        });
        root.findViewById(R.id.btn_report).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                form.refresh();
                show(reportPage);
            }
        });
        root.findViewById(R.id.btn_script).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                buildScript();
                scriptReturn = mainPage;
                show(scriptPage);
            }
        });
        final View.OnClickListener back = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMain();
            }
        };
        root.findViewById(R.id.btn_settings_back).setOnClickListener(back);
        root.findViewById(R.id.btn_script_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (scriptReturn == reportPage)
                    show(reportPage);
                else
                    showMain();
            }
        });
        root.findViewById(R.id.btn_report_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // The marker carries what the report says, for a phone without IWI.
                if (state.isLive())
                    IwiMarker.write(state);
                showMain();
            }
        });
        root.findViewById(R.id.btn_report_script).setOnClickListener(
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
                (LinearLayout) root.findViewById(R.id.report_container), this);
        trauma = new TraumaPane(mapView, root, manager, this, this);
        updateMain();
        handler.postDelayed(tick, TICK_MS);
    }

    /** The pane opens on the main page every time. */
    public void onShown() {
        showMain();
    }

    /** The pane closed: a pick in progress lets go of the map. */
    public void onClosed() {
        cancelPick();
        cancelPending();
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
        settingsPage.setVisibility(page == settingsPage ? View.VISIBLE : View.GONE);
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

    // ---- the main page ----

    private void updateMain() {
        final boolean live = state.isLive();
        if (picking) {
            // The button that started the pick is the one that stops it.
            (pickMoves ? moveButton : declareButton).setText("Cancel");
            (pickMoves ? moveButton : declareButton).setTextColor(color(R.color.amber));
        }
        if (!picking || pickMoves) {
            declareButton.setText(live ? "Clear IWI" : "Declare IWI");
            declareButton.setTextColor(live ? color(R.color.state_off) : Color.WHITE);
        }
        if (!picking || !pickMoves) {
            moveButton.setText("Change location");
            moveButton.setTextColor(Color.WHITE);
        }
        // While a location waits, the confirm above is the only Declare on the page.
        final boolean waiting = pendingPoint != null;
        declareButton.setVisibility(waiting ? View.GONE : View.VISIBLE);
        moveButton.setEnabled(!waiting);
        liveBlock.setVisibility(live ? View.VISIBLE : View.GONE);
        updateLive();
        trauma.refresh();
    }

    private void updateLive() {
        if (!state.isLive())
            return;
        final long at = state.declaredMs();
        final String clock = android.text.format.DateFormat.getTimeFormat(ctx)
                .format(new Date(at));
        liveTime.setText("Declared " + clock + ", " + ago(System.currentTimeMillis() - at));
        final GeoPoint p = state.point();
        liveWhere.setText(Coords.ddm(p.getLatitude(), p.getLongitude()) + "\n"
                + (IwiState.SOURCE_MAP.equals(state.source())
                        ? "Set on the map" : "My Location when declared"));
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
        confirmTitle.setText(move ? "Move the IWI here?" : "Declare the IWI here?");
        showWhere();
        confirmButton.setText(move ? "Move IWI" : "Declare IWI");
        confirmBlock.setVisibility(View.VISIBLE);
        status(null);
        show(mainPage);
        mainPage.scrollTo(0, 0);
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

    // ---- clear: two selections ----

    private void confirmClear() {
        new AlertDialog.Builder(mapView.getContext())
                .setTitle("Clear this IWI?")
                .setMessage("This removes the IWI marker from your map and erases the "
                        + "patient details in the report on this phone.\n\n"
                        + "Who you call and the frequencies (items 1 and 6) are kept for "
                        + "the next one.\n\n"
                        + "It does not remove the marker from anyone you sent it to.")
                .setPositiveButton("Clear IWI", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        IwiMarker.remove(mapView, state.uid());
                        state.clear();
                        form.refresh();
                        status("IWI cleared.");
                        updateMain();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
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
        scriptContainer.addView(heading("Update: patient " + n));
        scriptContainer.addView(quoted(RadioScript.updateLead(state)));
        scriptContainer.addView(action(RadioScript.answer(state)));
        final RadioScript.Section one = RadioScript.patient(state, patient);
        one.after = RadioScript.acknowledge(state);
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

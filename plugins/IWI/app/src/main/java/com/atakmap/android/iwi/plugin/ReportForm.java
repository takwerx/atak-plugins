package com.atakmap.android.iwi.plugin;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.maps.coords.GeoPoint;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The NWCG Medical Incident Report, as an initial report and then follow-ups
 * (PLAN-IWI, "The workflow"), picked rather than typed.
 *
 * <p>The operator, 2026-10-14: "we get the iwi, we need to get the ball rolling, just
 * the initial data" and "i need to check box, pick etc as much of this as possible".
 * So {@code INITIAL REPORT} (1 Contact, 2 Incident Status, 5 Resources) is all tiles:
 * the callsign comes from ATAK, the location from the IWI marker, the rest is a tap.
 * The patient cards are the IRPG p. 108 assessment and the pp. 110-114 treatments as
 * tiles. Typing is left only where it cannot be helped (frequencies) and in the
 * optional and later items.
 *
 * <p>Every item is a drop-down row, closed until opened; a row's head turns green
 * when its {@link RadioScript} section has nothing missing. Patients are cards made
 * from the counts in item 2; the cards are the only count. Views are built on the
 * plugin context; dialogs and toasts on the MapView's, or ATAK dies.
 */
final class ReportForm {

    /** What the form asks of the pane. */
    interface Host {
        /** Show the radio update for one patient. */
        void patientScript(int n);
    }

    /** Where a tile group reads and writes its value. */
    private abstract static class Binding {
        abstract String get();

        abstract void set(String value);
    }

    /** A row of tiles over one list: one picked, or several. */
    private final class TileGroup {
        final Vocab.Choices choices;
        final boolean many;
        final Binding binding;
        final Map<String, Button> tiles = new LinkedHashMap<>();

        TileGroup(Vocab.Choices choices, boolean many, Binding binding) {
            this.choices = choices;
            this.many = many;
            this.binding = binding;
        }

        void recolor() {
            final String v = binding.get();
            for (Map.Entry<String, Button> t : tiles.entrySet()) {
                final boolean on = many
                        ? Vocab.parse(v, choices).contains(t.getKey())
                        : t.getKey().equals(v);
                t.getValue().setTextColor(on ? green() : Color.WHITE);
            }
        }
    }

    private final Context ctx;
    private final MapView mapView;
    private final IwiState state;
    private final SharedPreferences prefs;
    private final Host host;
    private final LinearLayout root;

    private final Map<String, EditText> fields = new HashMap<>();
    private final Map<String, TextView> counts = new HashMap<>();
    private final Map<Integer, Card> cards = new LinkedHashMap<>();
    private final List<TileGroup> groups = new ArrayList<>();
    /** Medical and Trauma: each a drop-down of its tiles. */
    private final Map<Fold, Vocab.Choices> natureFolds = new LinkedHashMap<>();
    private final Map<Fold, String> natureNames = new HashMap<>();

    private Fold contact;
    private Fold status;
    private Fold resources;
    private Fold patients;
    private Fold evacPlan;
    private Fold comms;
    private Fold contingency;
    private Fold additional;
    private LinearLayout patientsBox;
    private View radioContactBlock;
    private TextView latLong;
    private TextView callsignLine;
    /** True while boxes are being filled from the state, so no watcher writes back. */
    private boolean binding;

    ReportForm(Context pluginContext, MapView mapView, IwiState state,
            SharedPreferences prefs, LinearLayout container, Host host) {
        this.ctx = pluginContext;
        this.mapView = mapView;
        this.state = state;
        this.prefs = prefs;
        this.host = host;
        this.root = container;
        build();
        refresh();
    }

    /** Declared: the next step is the initial report, item 2 open. */
    void openInitial() {
        status.setOpen(true);
    }

    /** Where item 2's row starts on the page, to scroll it to the top. */
    int initialTop() {
        final View row = (View) status.head.getParent();
        return row == null ? 0 : row.getTop();
    }

    /** Put what is saved back in every box and every card, after a Clear or a reopen. */
    void refresh() {
        binding = true;
        for (Map.Entry<String, EditText> f : fields.entrySet()) {
            final String v = state.raw(f.getKey());
            if (!v.equals(f.getValue().getText().toString()))
                f.getValue().setText(v);
        }
        binding = false;
        final GeoPoint p = state.isLive() ? state.point() : null;
        latLong.setText(p == null
                ? s(R.string.mir_latlong_none)
                : Coords.ddm(p.getLatitude(), p.getLongitude()) + " (DDM, WGS84)");
        latLong.setTextColor(p == null ? color(R.color.amber) : green());
        final String me = state.callsign();
        callsignLine.setText(me.isEmpty()
                ? "Your callsign is not set in ATAK."
                : "Your callsign: " + me + " (from ATAK)");
        rebuildPatients();
        refreshChoices();
        refreshCounts();
        refreshHeads();
    }

    // ---- the page ----

    private void build() {
        // One line where it can be acted on; the IRPG's whole header one tap away.
        final TextView first = label(s(R.string.mir_header_short));
        first.setTextColor(color(R.color.amber));
        root.addView(first);
        final Fold about = section(root, "iwi.fold.about");
        about.label("Before you call (IRPG)", null);
        body(about).addView(guide(s(R.string.mir_header)));

        root.addView(heading("Initial report"));
        LinearLayout body;

        contact = section(root, "iwi.fold.r1");
        body = body(contact);
        body.addView(guide("(Verify correct frequency prior to starting report)"));
        body.addView(label("Calling"));
        tiles(body, Vocab.CONTACT, 2, false, new Binding() {
            @Override
            String get() {
                return RadioScript.calling(state);
            }

            @Override
            void set(String v) {
                state.set(IwiState.CONTACT_TO, v.isEmpty() ? "Communications" : v);
            }
        }, groups);
        callsignLine = guide("");
        body.addView(callsignLine);

        status = section(root, "iwi.fold.r2");
        body = body(status);
        body.addView(label("Patients"));
        stepper(body, IwiState.RED, R.color.triage_red);
        stepper(body, IwiState.YELLOW, R.color.triage_yellow);
        stepper(body, IwiState.GREEN, R.color.triage_green);
        stepper(body, IwiState.BLACK, R.color.white);
        final Fold meaning = section(body, "iwi.fold.triage");
        meaning.label("What Red, Yellow and Green mean", null);
        final LinearLayout mb = body(meaning);
        mb.addView(guide(s(R.string.mir_red)));
        mb.addView(guide(s(R.string.mir_yellow)));
        mb.addView(guide(s(R.string.mir_green)));
        mb.addView(guide(s(R.string.mir_black)));

        body.addView(label("What happened"));
        natureGroup(body, "Medical", Vocab.MEDICAL, "iwi.fold.medical");
        natureGroup(body, "Trauma", Vocab.TRAUMA, "iwi.fold.trauma_list");

        body.addView(label("Evacuation request"));
        tiles(body, Vocab.EVAC, 2, false, textBinding(IwiState.EVAC_REQUEST), groups);

        body.addView(label("Patient location"));
        latLong = new TextView(ctx);
        latLong.setTextSize(16);
        latLong.setPadding(0, dp(2), 0, dp(2));
        body.addView(latLong);
        body.addView(guide("Where the IWI marker is. Change location on the main page moves it."));

        body.addView(label("Medical on scene"));
        body.addView(guide("The highest level of medical training on scene."));
        tiles(body, Vocab.CARE, 3, false, textBinding(IwiState.CARE_ROLE), groups);

        final Fold more = section(body, "iwi.fold.more");
        more.label("More, if you have it", null);
        final LinearLayout mo = body(more);
        mo.addView(guide("Never required. Said on the radio when it is here."));
        field(mo, IwiState.INCIDENT, "Incident name", "Ex: Trout Meadow Medical", false);
        field(mo, IwiState.IC, "On-scene IC", "Blank: you", false);
        field(mo, IwiState.LOCATION, "Landmark", "Ex: Forest Road 1", false);
        radioContactBlock = block();
        ((LinearLayout) radioContactBlock).addView(guide(s(R.string.mir_shorthaul_guide)));
        field((LinearLayout) radioContactBlock, IwiState.RADIO_CONTACT,
                "On-scene radio contact", "Blank: you", false);
        mo.addView(radioContactBlock);

        resources = section(root, "iwi.fold.r5");
        body = body(resources);
        body.addView(guide("The IRPG's example list. Pick everything you need."));
        tiles(body, Vocab.RESOURCES, 2, true, textBinding(IwiState.RESOURCES), groups);

        root.addView(heading("As you learn more"));

        patients = section(root, "iwi.fold.r3");
        body = body(patients);
        body.addView(guide(s(R.string.mir_3_guide)));
        patientsBox = block();
        body.addView(patientsBox, full());

        evacPlan = section(root, "iwi.fold.r4");
        body = body(evacPlan);
        field(body, IwiState.EVAC_LOCATION, "Evacuation Location (if different)",
                "Descriptive Location (drop point, intersection, etc.) or Lat. / Long.", false);
        field(body, IwiState.ETA, "Patient's ETA to evacuation location", "Ex: 40 min", false);
        field(body, IwiState.HELISPOT, "Helispot / Extraction Site Size and Hazards", "", true);

        comms = section(root, "iwi.fold.r6");
        body = body(comms);
        body.addView(guide(s(R.string.mir_6_guide)));
        final String[] names = {"COMMAND", "AIR-TO-GROUND", "TACTICAL"};
        for (int i = 0; i < IwiState.COMMS_ROWS.length; i++)
            commsRow(body, IwiState.COMMS_ROWS[i], names[i]);

        contingency = section(root, "iwi.fold.r7");
        body = body(contingency);
        body.addView(guide(s(R.string.mir_7_guide)));
        field(body, IwiState.CONTINGENCY, "Contingency", "", true);

        additional = section(root, "iwi.fold.r8");
        body = body(additional);
        field(body, IwiState.ADDITIONAL, "Additional Information", "Updates/Changes, etc.", true);

        root.addView(heading("Remember"));
        root.addView(guide(s(R.string.mir_remember)));
        final TextView credit = guide(s(R.string.mir_credit));
        credit.setPadding(0, dp(12), 0, dp(4));
        root.addView(credit);
    }

    /** Every row's head: green with what it says once filled in, white with what is left. */
    private void refreshHeads() {
        final GeoPoint p = state.isLive() ? state.point() : null;
        final String coords = p == null ? null : Coords.ddm(p.getLatitude(), p.getLongitude());

        head(contact, "1. Contact", RadioScript.contact(state), RadioScript.calling(state));
        final int n = state.patientCount();
        final String evac = state.text(IwiState.EVAC_REQUEST);
        head(status, "2. Incident Status", RadioScript.status(state, coords),
                (n == 1 ? "1 patient" : n + " patients") + (evac.isEmpty() ? "" : ", " + evac));
        head(resources, "5. Resources", RadioScript.resources(state),
                Vocab.labels(state.text(IwiState.RESOURCES), Vocab.RESOURCES));

        final List<Patient> list = state.patients();
        int done = 0;
        for (Patient one : list) {
            final RadioScript.Section sec = RadioScript.patient(state, one);
            if (sec.complete())
                done++;
            final Card c = cards.get(one.n);
            if (c != null)
                c.head(one, sec.missing);
        }
        if (list.isEmpty()) {
            patients.label("3. Patients", "none counted yet");
            patients.complete(false, green());
        } else {
            patients.label("3. Patients", done == list.size()
                    ? "all " + list.size() + " filled in"
                    : done + " of " + list.size() + " filled in");
            patients.complete(done == list.size(), green());
        }

        head(evacPlan, "4. Evacuation Plan", RadioScript.evacPlan(state), "filled in");
        head(comms, "6. Communications", RadioScript.comms(state), commsNames());
        head(contingency, "7. Contingency", RadioScript.contingency(state), "filled in");
        head(additional, "8. Additional Information", RadioScript.additional(state),
                "filled in");
    }

    private void head(Fold f, String title, RadioScript.Section section, String done) {
        if (section.complete())
            f.label(title, done == null || done.isEmpty() ? "filled in" : done);
        else
            f.label(title, section.missing + " to fill in");
        f.complete(section.complete(), green());
    }

    private String commsNames() {
        final StringBuilder b = new StringBuilder();
        for (int i = 0; i < IwiState.COMMS_ROWS.length; i++) {
            boolean any = false;
            for (String col : IwiState.COMMS_COLS)
                any |= !state.text(IwiState.commsKey(IwiState.COMMS_ROWS[i], col)).isEmpty();
            if (any)
                b.append(b.length() > 0 ? ", " : "").append(IwiState.COMMS_ROW_NAMES[i]);
        }
        return b.toString();
    }

    /** Every tile's color, the Medical and Trauma heads, and what shows for short-haul. */
    private void refreshChoices() {
        for (TileGroup g : groups)
            g.recolor();
        final String nature = state.text(IwiState.NATURE);
        // A group's head is cyan, the section color, until something in it is picked;
        // then green naming the picks, so a closed row still says it.
        for (Map.Entry<Fold, Vocab.Choices> g : natureFolds.entrySet()) {
            final String which = Vocab.labels(nature, g.getValue());
            g.getKey().label(natureNames.get(g.getKey()), which.isEmpty() ? null : which);
            g.getKey().head.setTextColor(which.isEmpty() ? cyan() : green());
        }
        final boolean weight = RadioScript.needsWeight(state);
        radioContactBlock.setVisibility(weight ? View.VISIBLE : View.GONE);
        for (Card c : cards.values()) {
            c.weightBlock.setVisibility(weight ? View.VISIBLE : View.GONE);
            for (TileGroup g : c.groups)
                g.recolor();
        }
    }

    private void refreshCounts() {
        for (Map.Entry<String, TextView> c : counts.entrySet())
            c.getValue().setText(Integer.toString(state.count(c.getKey())));
    }

    /** After any tap: colors, then heads. */
    private void picked() {
        refreshChoices();
        refreshHeads();
    }

    // ---- tiles ----

    /**
     * A grid of tiles over one list, {@code cols} across. One-of: a tap picks, a tap on
     * the picked one clears. Pick-many: each tap switches one, and the list's
     * exclusive tile ("None needed") clears the rest.
     */
    private TileGroup tiles(LinearLayout into, final Vocab.Choices c, int cols,
            final boolean many, final Binding b, List<TileGroup> registry) {
        final TileGroup g = new TileGroup(c, many, b);
        LinearLayout r = null;
        for (int i = 0; i < c.codes.length; i++) {
            if (i % cols == 0) {
                r = row();
                final LinearLayout.LayoutParams lp = full();
                lp.topMargin = dp(4);
                into.addView(r, lp);
            }
            final String code = c.codes[i];
            final Button t = button(c.labels[i], new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    final String now = b.get();
                    if (many)
                        b.set(Vocab.toggle(now, code, c));
                    else
                        b.set(code.equals(now) ? "" : code);
                    picked();
                }
            });
            if (cols >= 3)
                t.setTextSize(13);
            g.tiles.put(code, t);
            r.addView(t, weighted(i % cols == 0 ? 0 : dp(4)));
        }
        // Keep the last row's tiles the same width as the rest.
        final int fill = (cols - c.codes.length % cols) % cols;
        for (int i = 0; i < fill; i++)
            r.addView(new View(ctx), weighted(dp(4)));
        registry.add(g);
        return g;
    }

    private Binding textBinding(final String key) {
        return new Binding() {
            @Override
            String get() {
                return state.text(key);
            }

            @Override
            void set(String v) {
                state.set(key, v);
            }
        };
    }

    /**
     * One group of what-happened tiles as a drop-down row, so a crew with a trauma
     * patient never scrolls past the medical list. Two across, any number picked.
     */
    private void natureGroup(LinearLayout parent, String name, Vocab.Choices c, String pref) {
        final Fold group = section(parent, pref);
        natureFolds.put(group, c);
        natureNames.put(group, name);
        tiles(body(group), c, 2, true, textBinding(IwiState.NATURE), groups);
    }

    // ---- item 2: the counts make the patients ----

    /** [RED]  [-]  n  [+], the label in its triage color, read at arm's length. */
    private void stepper(LinearLayout into, final String color, int colorRes) {
        final LinearLayout r = row();
        r.setGravity(Gravity.CENTER_VERTICAL);
        final TextView l = new TextView(ctx);
        l.setText(Patient.name(color).toUpperCase(java.util.Locale.US));
        l.setTextSize(17);
        l.setTypeface(Typeface.DEFAULT_BOLD);
        l.setTextColor(color(colorRes));
        r.addView(l, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        final TextView n = new TextView(ctx);
        n.setTextSize(22);
        n.setTypeface(Typeface.DEFAULT_BOLD);
        n.setTextColor(Color.WHITE);
        n.setGravity(Gravity.CENTER);
        counts.put(color, n);
        final Button minus = button("−", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (state.count(color) == 0)
                    return;
                if (state.removeNewestBlank(color) < 0) {
                    Toast.makeText(mapView.getContext(), "Every " + Patient.name(color)
                            + " patient has notes. Remove one from its card in 3. Patients.",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                patientsChanged();
            }
        });
        final Button plus = button("+", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                state.addPatient(color);
                patientsChanged();
            }
        });
        r.addView(minus, new LinearLayout.LayoutParams(dp(64),
                LinearLayout.LayoutParams.WRAP_CONTENT));
        r.addView(n, new LinearLayout.LayoutParams(dp(56),
                LinearLayout.LayoutParams.WRAP_CONTENT));
        r.addView(plus, new LinearLayout.LayoutParams(dp(64),
                LinearLayout.LayoutParams.WRAP_CONTENT));
        final LinearLayout.LayoutParams lp = full();
        lp.topMargin = dp(6);
        into.addView(r, lp);
    }

    private void patientsChanged() {
        rebuildPatients();
        refreshChoices();
        refreshCounts();
        refreshHeads();
    }

    // ---- item 3: one card per patient, all picks ----

    private final class Card {
        final int n;
        final TextView chip;
        final Fold fold;
        final Map<String, Button> colorTiles = new HashMap<>();
        final List<TileGroup> groups = new ArrayList<>();
        final TextView weightValue;
        final View weightBlock;

        Card(final Patient p) {
            this.n = p.n;
            final LinearLayout headRow = row();
            headRow.setGravity(Gravity.CENTER_VERTICAL);
            chip = new TextView(ctx);
            chip.setTextSize(15);
            chip.setTypeface(Typeface.DEFAULT_BOLD);
            chip.setGravity(Gravity.CENTER);
            headRow.addView(chip, new LinearLayout.LayoutParams(dp(76),
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            final Button head = stepHead();
            headRow.addView(head, weighted(0));
            final ImageButton chev = chevron();
            headRow.addView(chev, new LinearLayout.LayoutParams(dp(44), dp(44)));
            final LinearLayout.LayoutParams lp = full();
            lp.topMargin = dp(6);
            patientsBox.addView(headRow, lp);

            final LinearLayout body = block();
            body.setPadding(dp(8), dp(2), 0, dp(6));
            patientsBox.addView(body, full());
            fold = new Fold(head, chev, body, prefs, "iwi.fold.patient." + p.n);

            body.addView(label("Triage"));
            final LinearLayout colors = row();
            for (int i = 0; i < Patient.COLORS.length; i++) {
                final String c = Patient.COLORS[i];
                final Button t = button(Patient.name(c), new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        state.updatePatient(n, "color", c);
                        patientRecolored();
                    }
                });
                colorTiles.put(c, t);
                colors.addView(t, weighted(i == 0 ? 0 : dp(4)));
            }
            body.addView(colors);

            // IRPG p. 108, Patient Assessment, in its order.
            pick(body, "Consciousness", Patient.LOC, 2);
            pick(body, "Breathing", Patient.BREATHING, 3);
            pick(body, "Pulse", Patient.PULSE, 2);
            pick(body, "Major bleeding", Patient.BLEEDING, 3);
            final Fold more = section(body, "iwi.fold.skin." + p.n);
            more.label("Skin and pupils", null);
            final LinearLayout mb = body(more);
            pick(mb, "Skin color", Patient.SKIN_COLOR, 4);
            pick(mb, "Skin moisture", Patient.SKIN_MOIST, 4);
            pick(mb, "Skin temperature", Patient.SKIN_TEMP, 4);
            pick(mb, "Pupils", Patient.PUPILS, 2);
            pick(mb, "Reaction to light", Patient.REACTION, 3);

            // IRPG pp. 110-114, Specific Treatments.
            body.addView(label("Treatment"));
            tiles(body, Vocab.TREATMENT, 2, true, cardBinding(Patient.TREATMENT), groups);

            // Short-haul and hoist need the weight (IRPG p. 119): a stepper, not a box.
            final LinearLayout wb = block();
            wb.addView(label("Patient weight"));
            final LinearLayout wr = row();
            wr.setGravity(Gravity.CENTER_VERTICAL);
            weightValue = new TextView(ctx);
            weightValue.setTextSize(20);
            weightValue.setTypeface(Typeface.DEFAULT_BOLD);
            weightValue.setTextColor(Color.WHITE);
            wr.addView(weightValue, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            wr.addView(button("− 10", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    stepWeight(-10);
                }
            }), new LinearLayout.LayoutParams(dp(80), LinearLayout.LayoutParams.WRAP_CONTENT));
            final LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(80),
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            plp.leftMargin = dp(6);
            wr.addView(button("+ 10", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    stepWeight(10);
                }
            }), plp);
            wb.addView(wr);
            weightBlock = wb;
            body.addView(wb);
            showWeight(p.get(Patient.WEIGHT));

            final LinearLayout actions = row();
            actions.addView(button("Radio update", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    host.patientScript(n);
                }
            }), weighted(0));
            actions.addView(button("Remove patient", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    confirmRemove(n);
                }
            }), weighted(dp(6)));
            final LinearLayout.LayoutParams alp = full();
            alp.topMargin = dp(8);
            body.addView(actions, alp);
            color(p.color);
        }

        private void pick(LinearLayout into, String title, String field, int cols) {
            into.addView(label(title));
            tiles(into, Patient.choices(field), cols, false, cardBinding(field), groups);
        }

        private Binding cardBinding(final String field) {
            return new Binding() {
                @Override
                String get() {
                    final Patient p = state.patient(n);
                    return p == null ? "" : p.get(field);
                }

                @Override
                void set(String v) {
                    state.updatePatient(n, field, v);
                }
            };
        }

        /** Unset starts at 180 lb, a common estimate; then 10 lb a tap, 50 to 400. */
        private void stepWeight(int by) {
            final Patient p = state.patient(n);
            int w;
            try {
                w = Integer.parseInt(p == null ? "" : p.get(Patient.WEIGHT));
                w = Math.max(50, Math.min(400, w + by));
            } catch (NumberFormatException e) {
                w = 180;
            }
            state.updatePatient(n, Patient.WEIGHT, Integer.toString(w));
            showWeight(Integer.toString(w));
            refreshHeads();
        }

        private void showWeight(String w) {
            weightValue.setText(w.isEmpty() ? "Not set" : "About " + w + " lb");
            weightValue.setTextColor(w.isEmpty() ? ReportForm.this.color(R.color.amber) : Color.WHITE);
        }

        void color(String c) {
            chip.setText(Patient.name(c).toUpperCase(java.util.Locale.US));
            chip.setTextColor(triage(c));
            for (Map.Entry<String, Button> t : colorTiles.entrySet())
                t.getValue().setTextColor(t.getKey().equals(c) ? triage(c) : Color.WHITE);
        }

        /** "updated 1:45 PM", with what is still missing until there is nothing. */
        void head(Patient p, int missing) {
            String value;
            if (p.updatedMs > 0 && p.hasNotes()) {
                value = "updated " + android.text.format.DateFormat.getTimeFormat(ctx)
                        .format(new Date(p.updatedMs));
                if (missing > 0)
                    value += ", " + missing + " to fill in";
            } else {
                value = "no notes yet";
            }
            fold.label(p.label(), value);
            fold.complete(missing == 0, green());
        }
    }

    private void rebuildPatients() {
        patientsBox.removeAllViews();
        cards.clear();
        // Most severe first, Reds on top, so a new Red never lands under the Greens.
        final List<Patient> list = state.patientsBySeverity();
        if (list.isEmpty()) {
            patientsBox.addView(guide("No patients yet. Count them in 2. Incident Status; "
                    + "each one gets a card here for its follow-ups."));
            return;
        }
        for (Patient p : list)
            cards.put(p.n, new Card(p));
    }

    /** A re-triage moves the card to its new place in the order. */
    private void patientRecolored() {
        patientsChanged();
    }

    private String label(int n) {
        final Patient p = state.patient(n);
        return p == null ? "this patient" : p.label();
    }

    private void confirmRemove(final int n) {
        new AlertDialog.Builder(mapView.getContext())
                .setTitle("Remove " + label(n) + "?")
                .setMessage("The card and its notes are erased. Use this for someone "
                        + "counted by mistake.")
                .setPositiveButton("Remove patient", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        state.removePatient(n);
                        patientsChanged();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---- the typed follow-ups ----

    private void commsRow(LinearLayout into, String row, String name) {
        into.addView(label(name));
        final LinearLayout top = row();
        top.addView(commsField(row, "channel", "Channel Name/#"), weighted(0));
        into.addView(top);
        final LinearLayout cols = row();
        cols.addView(commsField(row, "rx", "Receive (RX)"), weighted(0));
        cols.addView(commsField(row, "rx_tone", "Tone/NAC"), weighted(dp(4)));
        cols.addView(commsField(row, "tx", "Transmit (TX)"), weighted(dp(4)));
        cols.addView(commsField(row, "tx_tone", "Tone/NAC"), weighted(dp(4)));
        into.addView(cols);
    }

    /** A small caption over its box, so the column still reads once the hint is gone. */
    private View commsField(String row, String col, String caption) {
        final LinearLayout cell = new LinearLayout(ctx);
        cell.setOrientation(LinearLayout.VERTICAL);
        final TextView cap = new TextView(ctx);
        cap.setText(caption);
        cap.setTextSize(11);
        cap.setTextColor(color(R.color.guide_grey));
        cell.addView(cap);
        cell.addView(edit(IwiState.commsKey(row, col), "", false));
        return cell;
    }

    // ---- pieces ----

    /** A drop-down row on the page: head, arrow, body. Closed until opened, remembered. */
    private Fold section(LinearLayout into, String pref) {
        final LinearLayout headRow = row();
        headRow.setGravity(Gravity.CENTER_VERTICAL);
        final Button head = stepHead();
        headRow.addView(head, weighted(0));
        final ImageButton chev = chevron();
        final LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(44), dp(44));
        clp.leftMargin = dp(2);
        headRow.addView(chev, clp);
        final LinearLayout.LayoutParams lp = full();
        lp.topMargin = dp(6);
        into.addView(headRow, lp);
        final LinearLayout body = block();
        body.setPadding(dp(8), dp(2), 0, dp(8));
        into.addView(body, full());
        return new Fold(head, chev, body, prefs, pref);
    }

    private static LinearLayout body(Fold f) {
        return (LinearLayout) f.body;
    }

    /** A step's head reads from the left, like a checklist (operator, 2026-10-13). */
    private Button stepHead() {
        final Button b = button("", null);
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        return b;
    }

    private ImageButton chevron() {
        final ImageButton b = new ImageButton(ctx, null, 0, R.style.TakwerxIconButton);
        b.setImageResource(R.drawable.ic_expand);
        b.setContentDescription(s(R.string.show_settings));
        return b;
    }

    private void field(LinearLayout into, String key, String label, String hint,
            boolean multiline) {
        into.addView(label(label));
        into.addView(edit(key, hint, multiline), full());
    }

    private EditText edit(final String key, String hint, boolean multiline) {
        final EditText e = new EditText(ctx);
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(color(R.color.hint_grey));
        e.setTextSize(15);
        e.setHint(hint);
        // No full-screen keyboard in landscape: the operator has to see the form.
        e.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | (multiline ? EditorInfo.IME_ACTION_NONE : EditorInfo.IME_ACTION_NEXT));
        if (multiline) {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            e.setMinLines(2);
            e.setGravity(Gravity.TOP | Gravity.START);
        } else {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
            e.setSingleLine(true);
        }
        e.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (binding)
                    return;
                state.set(key, s.toString());
                refreshHeads();
            }
        });
        fields.put(key, e);
        return e;
    }

    private TextView heading(String text) {
        final TextView t = new TextView(ctx, null, 0, R.style.TakwerxSectionHeading);
        t.setText(text);
        t.setPadding(0, dp(16), 0, dp(2));
        return t;
    }

    private TextView label(String text) {
        final TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(14);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(Color.WHITE);
        t.setPadding(0, dp(10), 0, 0);
        return t;
    }

    private TextView guide(String text) {
        final TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(13);
        t.setTextColor(color(R.color.guide_grey));
        t.setPadding(0, dp(2), 0, dp(2));
        return t;
    }

    private Button button(String text, View.OnClickListener l) {
        final Button b = new Button(ctx, null, 0, R.style.TakwerxButton);
        b.setText(text);
        if (l != null)
            b.setOnClickListener(l);
        return b;
    }

    private LinearLayout row() {
        final LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        return r;
    }

    private LinearLayout block() {
        final LinearLayout b = new LinearLayout(ctx);
        b.setOrientation(LinearLayout.VERTICAL);
        return b;
    }

    private static LinearLayout.LayoutParams full() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private static LinearLayout.LayoutParams weighted(int leftMargin) {
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = leftMargin;
        return lp;
    }

    private int triage(String c) {
        if (IwiState.RED.equals(c))
            return color(R.color.triage_red);
        if (IwiState.YELLOW.equals(c))
            return color(R.color.triage_yellow);
        if (IwiState.GREEN.equals(c))
            return color(R.color.triage_green);
        return Color.WHITE;
    }

    private int cyan() {
        return 0xFF00E5FF;
    }

    private int green() {
        return color(R.color.state_on);
    }

    private String s(int id) {
        return ctx.getString(id);
    }

    private int color(int id) {
        return ctx.getResources().getColor(id);
    }

    private int dp(int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}

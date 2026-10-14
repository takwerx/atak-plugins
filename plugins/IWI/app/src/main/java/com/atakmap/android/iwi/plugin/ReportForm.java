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

import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The NWCG Medical Incident Report, as an initial report and then follow-ups
 * (PLAN-IWI, "The workflow").
 *
 * <p>Every item is a drop-down row, closed until opened. {@code INITIAL REPORT} holds
 * what is said first: 1 Contact, 2 Incident Status, 5 Resources. {@code AS YOU LEARN
 * MORE} holds 3 Patients, one card each, and 4, 6, 7 and 8. A row's head turns green
 * when its section is filled in, computed from the same {@link RadioScript} section the
 * script reads, so a green head is a section with nothing amber in the script.
 *
 * <p>Patients are cards made from the counts in item 2; the cards are the only count
 * there is. Numbered, never named. Every field is saved as it is typed. Views are built
 * on the plugin context; dialogs and toasts on the MapView's, or ATAK dies.
 */
final class ReportForm {

    /** What the form asks of the pane. */
    interface Host {
        /** Show the radio update for one patient. */
        void patientScript(int n);
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
    private final Map<String, Button> natureTiles = new HashMap<>();
    /** Medical and Trauma: each a drop-down of its tiles, keyed by its name. */
    private final Map<String, Fold> natureFolds = new LinkedHashMap<>();
    private final Map<String, String[]> natureCodes = new HashMap<>();

    private Fold contact;
    private Fold status;
    private Fold resources;
    private Fold patients;
    private Fold evacPlan;
    private Fold comms;
    private Fold contingency;
    private Fold additional;
    private LinearLayout patientsBox;

    private Button evacButton;
    private View evacOtherBlock;
    private View radioContactBlock;
    private Button roleParamedic;
    private Button roleEmt;
    private TextView latLong;
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

        contact = section("iwi.fold.r1");
        body = body(contact);
        body.addView(guide(s(R.string.mir_1_guide)));
        field(body, IwiState.CONTACT_TO, "Who you are calling", "Ex: Communications", false);
        field(body, IwiState.CONTACT_FROM, "Your callsign", "Ex: Div. Alpha", false);

        status = section("iwi.fold.r2");
        body = body(status);
        body.addView(guide(s(R.string.mir_2_guide)));
        body.addView(label("Patients: Severity of Emergency / Transport Priority"));
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
        body.addView(label("Nature of Injury or Illness and Mechanism of Injury"));
        body.addView(guide(s(R.string.mir_nature_guide)));
        natureGroup(body, "Medical", Nature.MEDICAL, "iwi.fold.medical");
        natureGroup(body, "Trauma", Nature.TRAUMA, "iwi.fold.trauma_list");
        body.addView(label("Evacuation Request"));
        evacButton = button("", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickEvac();
            }
        });
        body.addView(evacButton, full());
        evacOtherBlock = block();
        field((LinearLayout) evacOtherBlock, IwiState.EVAC_OTHER, "Other: what",
                "Ex: Engine to the trailhead", false);
        body.addView(evacOtherBlock);
        radioContactBlock = block();
        ((LinearLayout) radioContactBlock).addView(guide(s(R.string.mir_shorthaul_guide)));
        field((LinearLayout) radioContactBlock, IwiState.RADIO_CONTACT,
                "On-scene radio contact", "Ex: TFLD Jones", false);
        body.addView(radioContactBlock);
        // The patient is where the IWI marker is: shown, not asked for.
        body.addView(label("Patient Location"));
        latLong = new TextView(ctx);
        latLong.setTextSize(16);
        latLong.setTextColor(Color.WHITE);
        latLong.setPadding(0, dp(2), 0, dp(2));
        body.addView(latLong);
        body.addView(guide("Where the IWI marker is. Change location on the main page moves it."));
        field(body, IwiState.LOCATION, "Landmark (optional)", "Ex: Forest Road 1", false);
        field(body, IwiState.INCIDENT, "Incident Name",
                "Geographic Name + Medical (Ex: Trout Meadow Medical)", false);
        field(body, IwiState.IC, "On-Scene Incident Commander",
                "Name of on-scene IC of Incident Within an Incident (Ex: TFLD Jones)", false);
        body.addView(label("Patient Care"));
        body.addView(guide(s(R.string.mir_care_guide)));
        final LinearLayout roles = row();
        roleParamedic = button(IwiState.ROLE_PARAMEDIC, role(IwiState.ROLE_PARAMEDIC));
        roleEmt = button(IwiState.ROLE_EMT, role(IwiState.ROLE_EMT));
        roles.addView(roleParamedic, weighted(0));
        roles.addView(roleEmt, weighted(dp(6)));
        body.addView(roles);
        field(body, IwiState.CARE_NAME, "Last name", "Ex: Smith", false);

        resources = section("iwi.fold.r5");
        body = body(resources);
        field(body, IwiState.RESOURCES, "Additional Resources / Equipment Needs",
                s(R.string.mir_5_hint), true);

        root.addView(heading("As you learn more"));

        patients = section("iwi.fold.r3");
        body = body(patients);
        body.addView(guide(s(R.string.mir_3_guide)));
        patientsBox = block();
        body.addView(patientsBox, full());
        final Fold checklist = section(body, "iwi.fold.checklist");
        checklist.label("Assessment checklist, IRPG p. 108", null);
        body(checklist).addView(guide(s(R.string.mir_assessment_checklist)));

        evacPlan = section("iwi.fold.r4");
        body = body(evacPlan);
        field(body, IwiState.EVAC_LOCATION, "Evacuation Location (if different)",
                "Descriptive Location (drop point, intersection, etc.) or Lat. / Long.", false);
        field(body, IwiState.ETA, "Patient's ETA to evacuation location", "Ex: 40 min", false);
        field(body, IwiState.HELISPOT, "Helispot / Extraction Site Size and Hazards", "", true);

        comms = section("iwi.fold.r6");
        body = body(comms);
        body.addView(guide(s(R.string.mir_6_guide)));
        final String[] names = {"COMMAND", "AIR-TO-GROUND", "TACTICAL"};
        for (int i = 0; i < IwiState.COMMS_ROWS.length; i++)
            commsRow(body, IwiState.COMMS_ROWS[i], names[i]);

        contingency = section("iwi.fold.r7");
        body = body(contingency);
        body.addView(guide(s(R.string.mir_7_guide)));
        field(body, IwiState.CONTINGENCY, "Contingency", "", true);

        additional = section("iwi.fold.r8");
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

        head(contact, "1. Contact", RadioScript.contact(state), state.text(IwiState.CONTACT_TO));
        final int n = state.patientCount();
        final String evac = state.text(IwiState.EVAC_REQUEST);
        head(status, "2. Incident Status", RadioScript.status(state, coords),
                (n == 1 ? "1 patient" : n + " patients") + (evac.isEmpty() ? "" : ", " + evac));
        head(resources, "5. Resources", RadioScript.resources(state),
                state.text(IwiState.RESOURCES));

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

    private void refreshChoices() {
        final String evac = state.text(IwiState.EVAC_REQUEST);
        evacButton.setText(evac.isEmpty() ? s(R.string.mir_evac_pick) : evac);
        evacButton.setTextColor(evac.isEmpty() ? Color.WHITE : green());
        evacOtherBlock.setVisibility("Other".equals(evac) ? View.VISIBLE : View.GONE);
        final boolean weight = "Short-Haul".equals(evac) || "Hoist".equals(evac);
        radioContactBlock.setVisibility(weight ? View.VISIBLE : View.GONE);
        for (Card c : cards.values())
            c.weightBlock.setVisibility(weight ? View.VISIBLE : View.GONE);
        final java.util.Set<String> picked = Nature.parse(state.text(IwiState.NATURE));
        for (Map.Entry<String, Button> t : natureTiles.entrySet())
            t.getValue().setTextColor(picked.contains(t.getKey()) ? green() : Color.WHITE);
        // A group's head is cyan, the color of a section change, until something in it
        // is picked; then green with what was picked, so a closed row still says it.
        for (Map.Entry<String, Fold> g : natureFolds.entrySet()) {
            final StringBuilder which = new StringBuilder();
            for (String code : natureCodes.get(g.getKey()))
                if (picked.contains(code))
                    which.append(which.length() > 0 ? ", " : "").append(Nature.label(code));
            g.getValue().label(g.getKey(), which.length() == 0 ? null : which.toString());
            g.getValue().head.setTextColor(which.length() == 0 ? cyan() : green());
        }
        final String role = state.text(IwiState.CARE_ROLE);
        roleParamedic.setTextColor(IwiState.ROLE_PARAMEDIC.equals(role) ? green() : Color.WHITE);
        roleEmt.setTextColor(IwiState.ROLE_EMT.equals(role) ? green() : Color.WHITE);
    }

    private void refreshCounts() {
        for (Map.Entry<String, TextView> c : counts.entrySet())
            c.getValue().setText(Integer.toString(state.count(c.getKey())));
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

    // ---- item 3: one card per patient ----

    private final class Card {
        final int n;
        final TextView chip;
        final Fold fold;
        final Map<String, Button> tiles = new HashMap<>();
        View weightBlock;

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
                tiles.put(c, t);
                colors.addView(t, weighted(i == 0 ? 0 : dp(4)));
            }
            body.addView(colors);
            patientField(body, p, "assessment", "Assessment",
                    "General impression, bleeding, airway, breathing, circulation, LOC", true);
            patientField(body, p, "treatment", "Treatment", "", true);
            weightBlock = block();
            patientField((LinearLayout) weightBlock, p, "weight", "Patient weight",
                    "Ex: 210 lb", false);
            body.addView(weightBlock);

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

        void color(String c) {
            chip.setText(Patient.name(c).toUpperCase(java.util.Locale.US));
            chip.setTextColor(triage(c));
            for (Map.Entry<String, Button> t : tiles.entrySet())
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
            fold.label("Patient " + p.n, value);
            fold.complete(missing == 0, green());
        }
    }

    private void rebuildPatients() {
        patientsBox.removeAllViews();
        cards.clear();
        final List<Patient> list = state.patients();
        if (list.isEmpty()) {
            patientsBox.addView(guide("No patients yet. Count them in 2. Incident Status; "
                    + "each one gets a card here for its follow-ups."));
            return;
        }
        for (Patient p : list)
            cards.put(p.n, new Card(p));
    }

    private void patientRecolored() {
        for (Patient p : state.patients()) {
            final Card c = cards.get(p.n);
            if (c != null)
                c.color(p.color);
        }
        refreshCounts();
        refreshHeads();
    }

    private void confirmRemove(final int n) {
        new AlertDialog.Builder(mapView.getContext())
                .setTitle("Remove patient " + n + "?")
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

    private void patientField(LinearLayout into, Patient p, final String field, String label,
            String hint, boolean multiline) {
        into.addView(label(label));
        final EditText e = editBox(hint, multiline);
        final String now = "assessment".equals(field) ? p.assessment
                : "treatment".equals(field) ? p.treatment : p.weight;
        e.setText(now);
        final int n = p.n;
        e.addTextChangedListener(new Watcher() {
            @Override
            public void afterTextChanged(Editable s) {
                state.updatePatient(n, field, s.toString());
                refreshHeads();
            }
        });
        into.addView(e, full());
    }

    // ---- the rest ----

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

    /**
     * One group of tiles as a drop-down row, so a crew with a trauma patient never
     * scrolls past the medical list. Two across, any number picked.
     */
    private void natureGroup(LinearLayout parent, String name, String[] codes, String pref) {
        final Fold group = section(parent, pref);
        natureFolds.put(name, group);
        natureCodes.put(name, codes);
        final LinearLayout into = body(group);
        LinearLayout r = null;
        for (int i = 0; i < codes.length; i++) {
            if (i % 2 == 0) {
                r = row();
                final LinearLayout.LayoutParams lp = full();
                lp.topMargin = dp(4);
                into.addView(r, lp);
            }
            final String code = codes[i];
            final Button t = button(Nature.label(code), new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    state.set(IwiState.NATURE,
                            Nature.toggle(state.text(IwiState.NATURE), code));
                    refreshChoices();
                    refreshHeads();
                }
            });
            natureTiles.put(code, t);
            r.addView(t, weighted(i % 2 == 0 ? 0 : dp(6)));
        }
    }

    private void pickEvac() {
        final String now = state.text(IwiState.EVAC_REQUEST);
        int checked = -1;
        for (int i = 0; i < IwiState.EVAC.length; i++)
            if (IwiState.EVAC[i].equals(now))
                checked = i;
        new AlertDialog.Builder(mapView.getContext())
                .setTitle("Evacuation Request")
                .setSingleChoiceItems(IwiState.EVAC, checked,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                state.set(IwiState.EVAC_REQUEST, IwiState.EVAC[which]);
                                dialog.dismiss();
                                refreshChoices();
                                refreshHeads();
                            }
                        })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private View.OnClickListener role(final String role) {
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // A second tap on the chosen one clears it.
                state.set(IwiState.CARE_ROLE,
                        role.equals(state.text(IwiState.CARE_ROLE)) ? "" : role);
                refreshChoices();
                refreshHeads();
            }
        };
    }

    // ---- pieces ----

    /** A drop-down row on the page: head, arrow, body. Closed until opened, remembered. */
    private Fold section(String pref) {
        return section(root, pref);
    }

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
        final EditText e = editBox(hint, multiline);
        e.addTextChangedListener(new Watcher() {
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

    private EditText editBox(String hint, boolean multiline) {
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
        return e;
    }

    /** A TextWatcher that only cares about the end result. */
    private abstract static class Watcher implements TextWatcher {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }
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
        t.setPadding(0, dp(8), 0, 0);
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

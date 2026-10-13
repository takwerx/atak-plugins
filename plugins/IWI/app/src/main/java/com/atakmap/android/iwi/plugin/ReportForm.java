package com.atakmap.android.iwi.plugin;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
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
import android.widget.LinearLayout;
import android.widget.TextView;

import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.maps.coords.GeoPoint;

import java.util.HashMap;
import java.util.Map;

/**
 * The NWCG Medical Incident Report as a form, the way ATAK's CASEVAC is a form.
 *
 * <p>The eight items in IRPG order, each with the guidance the IRPG prints for it
 * above its fields, quoted rather than paraphrased (PLAN-IWI, "The transcription").
 * Every field is saved as it is typed. No field asks for a patient's name: the IRPG
 * says not to put one on the radio, and IWI never puts one in a marker.
 *
 * <p>Views are built on the plugin context; the one picker dialog is built on the
 * MapView's, never the plugin's, or ATAK dies.
 */
final class ReportForm {

    private final Context ctx;
    private final MapView mapView;
    private final IwiState state;
    private final LinearLayout root;

    private final Map<String, EditText> fields = new HashMap<>();
    private final Map<String, TextView> counts = new HashMap<>();
    private Button evacButton;
    private View evacOtherBlock;
    private View weightBlock;
    private Button roleParamedic;
    private Button roleEmt;
    private TextView latLong;
    /** True while fields are being filled from the state, so no watcher writes back. */
    private boolean binding;

    ReportForm(Context pluginContext, MapView mapView, IwiState state, LinearLayout container) {
        this.ctx = pluginContext;
        this.mapView = mapView;
        this.state = state;
        this.root = container;
        build();
        refresh();
    }

    /** Put what is saved back in every box, after a Clear or a reopen. */
    void refresh() {
        binding = true;
        for (Map.Entry<String, EditText> f : fields.entrySet()) {
            final String v = state.raw(f.getKey());
            if (!v.equals(f.getValue().getText().toString()))
                f.getValue().setText(v);
        }
        for (Map.Entry<String, TextView> c : counts.entrySet())
            c.getValue().setText(Integer.toString(state.count(c.getKey())));
        binding = false;
        refreshChoices();
        final GeoPoint p = state.isLive() ? state.point() : null;
        latLong.setText(p == null
                ? s(R.string.mir_latlong_none)
                : "Lat. / Long.: " + Coords.ddm(p.getLatitude(), p.getLongitude())
                        + " (DDM, WGS84)");
    }

    private void refreshChoices() {
        final String evac = state.text(IwiState.EVAC_REQUEST);
        evacButton.setText(evac.isEmpty() ? s(R.string.mir_evac_pick) : evac);
        evacButton.setTextColor(evac.isEmpty() ? Color.WHITE : color(R.color.state_on));
        evacOtherBlock.setVisibility("Other".equals(evac) ? View.VISIBLE : View.GONE);
        weightBlock.setVisibility("Short-Haul".equals(evac) || "Hoist".equals(evac)
                ? View.VISIBLE : View.GONE);
        final String role = state.text(IwiState.CARE_ROLE);
        roleParamedic.setTextColor(IwiState.ROLE_PARAMEDIC.equals(role)
                ? color(R.color.state_on) : Color.WHITE);
        roleEmt.setTextColor(IwiState.ROLE_EMT.equals(role)
                ? color(R.color.state_on) : Color.WHITE);
    }

    // ---- the form, top to bottom ----

    private void build() {
        root.addView(guide(s(R.string.mir_header)));

        root.addView(heading(s(R.string.mir_1_title)));
        root.addView(guide(s(R.string.mir_1_guide)));
        field(IwiState.CONTACT_TO, "Who you are calling", "Ex: Communications", false);
        field(IwiState.CONTACT_FROM, "Your callsign", "Ex: Div. Alpha", false);

        root.addView(heading(s(R.string.mir_2_title)));
        root.addView(guide(s(R.string.mir_2_guide)));
        root.addView(label("Severity of Emergency / Transport Priority"));
        stepper(IwiState.RED, "RED", R.color.triage_red);
        root.addView(guide(s(R.string.mir_red)));
        stepper(IwiState.YELLOW, "YELLOW", R.color.triage_yellow);
        root.addView(guide(s(R.string.mir_yellow)));
        stepper(IwiState.GREEN, "GREEN", R.color.triage_green);
        root.addView(guide(s(R.string.mir_green)));
        stepper(IwiState.BLACK, "BLACK", R.color.white);
        root.addView(guide(s(R.string.mir_black)));

        field(IwiState.NATURE, "Nature of Injury or Illness and Mechanism of Injury",
                "Brief Summary of Injury or Illness (Ex: Unconscious, Struck by Falling Tree)",
                true);

        root.addView(label("Evacuation Request"));
        evacButton = button("", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickEvac();
            }
        });
        root.addView(evacButton, full());
        evacOtherBlock = block();
        fieldInto((LinearLayout) evacOtherBlock, IwiState.EVAC_OTHER, "Other: what",
                "Ex: Engine to the trailhead", false);
        root.addView(evacOtherBlock);
        weightBlock = block();
        ((LinearLayout) weightBlock).addView(guide(s(R.string.mir_shorthaul_guide)));
        fieldInto((LinearLayout) weightBlock, IwiState.WEIGHT, "Patient weight",
                "Ex: 210 lb", false);
        fieldInto((LinearLayout) weightBlock, IwiState.RADIO_CONTACT,
                "On-scene radio contact", "Ex: TFLD Jones", false);
        root.addView(weightBlock);

        field(IwiState.LOCATION, "Patient Location", "Descriptive Location", false);
        latLong = guide("");
        root.addView(latLong);

        field(IwiState.INCIDENT, "Incident Name",
                "Geographic Name + Medical (Ex: Trout Meadow Medical)", false);
        field(IwiState.IC, "On-Scene Incident Commander",
                "Name of on-scene IC of Incident Within an Incident (Ex: TFLD Jones)", false);

        root.addView(label("Patient Care"));
        root.addView(guide(s(R.string.mir_care_guide)));
        final LinearLayout roles = row();
        roleParamedic = button(IwiState.ROLE_PARAMEDIC, role(IwiState.ROLE_PARAMEDIC));
        roleEmt = button(IwiState.ROLE_EMT, role(IwiState.ROLE_EMT));
        roles.addView(roleParamedic, weighted(0));
        roles.addView(roleEmt, weighted(dp(6)));
        root.addView(roles);
        field(IwiState.CARE_NAME, "Last name", "Ex: Smith", false);

        root.addView(heading(s(R.string.mir_3_title)));
        root.addView(guide(s(R.string.mir_3_guide)));
        field(IwiState.ASSESSMENT, "Patient Assessment", "See IRPG page 108, below", true);
        field(IwiState.TREATMENT, "Treatment", "", true);
        root.addView(guide(s(R.string.mir_assessment_checklist)));

        root.addView(heading(s(R.string.mir_4_title)));
        field(IwiState.EVAC_LOCATION, "Evacuation Location (if different)",
                "Descriptive Location (drop point, intersection, etc.) or Lat. / Long.", false);
        field(IwiState.ETA, "Patient's ETA to evacuation location", "Ex: 40 min", false);
        field(IwiState.HELISPOT, "Helispot / Extraction Site Size and Hazards", "", true);

        root.addView(heading(s(R.string.mir_5_title)));
        field(IwiState.RESOURCES, "Additional Resources / Equipment Needs",
                s(R.string.mir_5_hint), true);

        root.addView(heading(s(R.string.mir_6_title)));
        root.addView(guide(s(R.string.mir_6_guide)));
        final String[] names = {"COMMAND", "AIR-TO-GROUND", "TACTICAL"};
        for (int i = 0; i < IwiState.COMMS_ROWS.length; i++)
            commsRow(IwiState.COMMS_ROWS[i], names[i]);

        root.addView(heading(s(R.string.mir_7_title)));
        root.addView(guide(s(R.string.mir_7_guide)));
        field(IwiState.CONTINGENCY, "Contingency", "", true);

        root.addView(heading(s(R.string.mir_8_title)));
        field(IwiState.ADDITIONAL, "Additional Information", "Updates/Changes, etc.", true);

        root.addView(heading("Remember"));
        root.addView(guide(s(R.string.mir_remember)));
        final TextView credit = guide(s(R.string.mir_credit));
        credit.setPadding(0, dp(12), 0, dp(4));
        root.addView(credit);
    }

    private void commsRow(String row, String name) {
        root.addView(label(name));
        final LinearLayout top = row();
        top.addView(commsField(row, "channel", "Channel Name/#"), weighted(0));
        root.addView(top);
        final LinearLayout cols = row();
        cols.addView(commsField(row, "rx", "Receive (RX)"), weighted(0));
        cols.addView(commsField(row, "rx_tone", "Tone/NAC"), weighted(dp(4)));
        cols.addView(commsField(row, "tx", "Transmit (TX)"), weighted(dp(4)));
        cols.addView(commsField(row, "tx_tone", "Tone/NAC"), weighted(dp(4)));
        root.addView(cols);
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

    private void field(String key, String label, String hint, boolean multiline) {
        fieldInto(root, key, label, hint, multiline);
    }

    private void fieldInto(LinearLayout into, String key, String label, String hint,
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
                if (!binding)
                    state.set(key, s.toString());
            }
        });
        fields.put(key, e);
        return e;
    }

    /** [label]  [-]  n  [+], the label in its triage color, read at arm's length. */
    private void stepper(final String key, String name, int colorRes) {
        final LinearLayout r = row();
        r.setGravity(Gravity.CENTER_VERTICAL);
        final TextView l = new TextView(ctx);
        l.setText(name);
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
        counts.put(key, n);
        final Button minus = button("−", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                state.setCount(key, state.count(key) - 1);
                n.setText(Integer.toString(state.count(key)));
            }
        });
        final Button plus = button("+", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                state.setCount(key, state.count(key) + 1);
                n.setText(Integer.toString(state.count(key)));
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
        root.addView(r, lp);
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
            }
        };
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

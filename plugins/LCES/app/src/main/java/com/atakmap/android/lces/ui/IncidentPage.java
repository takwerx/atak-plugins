package com.atakmap.android.lces.ui;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.atakmap.android.lces.lessons.Incident;
import com.atakmap.android.lces.plugin.R;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.conversions.Span;
import com.atakmap.coremap.conversions.SpanUtilities;

import java.util.List;

/**
 * A Lessons Learned incident's page in the pane: what kind, when, where, the LLC's
 * own summary, and the full report.
 *
 * <p>The words are the LLC's. This page reports what happened and never judges the
 * place: no "safe", no rating. The then-versus-now comparison is a later step.
 */
final class IncidentPage {

    private static final int HEADING_COLOR = 0xFF00E5FF;
    private static final int QUIET = 0xFFB0B0B0;

    interface Actions {
        void goTo(Incident i);
    }

    private final Context pluginContext;
    private final MapView mapView;
    private final Actions actions;
    private float scale = 1f;

    IncidentPage(Context pluginContext, MapView mapView, Actions actions) {
        this.pluginContext = pluginContext;
        this.mapView = mapView;
        this.actions = actions;
    }

    void setScale(float s) {
        scale = s;
    }

    /**
     * @param meters how far it is from where the list measures, or NaN
     * @param fromWhere "your location" or "the map center"
     */
    void render(final Incident i, LinearLayout body, double meters, String fromWhere,
            String sourceEdited) {
        body.removeAllViews();

        // Kind, in its map color, then the LLC's own category when it says more.
        final SpannableStringBuilder kind = new SpannableStringBuilder();
        append(kind, i.type.label, i.type.color, true);
        if (!i.category.isEmpty() && !i.category.equalsIgnoreCase(i.type.label))
            append(kind, "  ·  " + i.category, 0xFFFFFFFF, false);
        body.addView(text(kind, 16f, 0xFFFFFFFF, 8));

        final StringBuilder when = new StringBuilder(i.longDate());
        if (!i.clock().isEmpty())
            when.append(", ").append(i.clock());
        body.addView(text(when, 14f, 0xFFFFFFFF, 2));

        final StringBuilder where = new StringBuilder();
        if (!i.state.isEmpty())
            where.append(i.state).append('.');
        if (!Double.isNaN(meters)) {
            if (where.length() > 0)
                where.append(' ');
            where.append(SpanUtilities.formatType(Units.type(), meters, Span.METER))
                    .append(" from ").append(fromWhere).append('.');
        }
        if (where.length() > 0)
            body.addView(text(where, 14f, 0xFFFFFFFF, 2));

        final String toll = toll(i);
        if (!toll.isEmpty())
            body.addView(text(toll, 14f, 0xFFFFFFFF, 2));

        final Button go = button(R.string.lesson_go_to);
        go.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                actions.goTo(i);
            }
        });
        body.addView(go);

        if (!i.summary.isEmpty()) {
            body.addView(heading(R.string.lesson_what_happened));
            body.addView(text(i.summary, 14f, 0xFFFFFFFF, 2));
        }
        if (!i.quote.isEmpty()) {
            body.addView(heading(R.string.lesson_from_report));
            final TextView q = text("“" + i.quote + "”", 14f, 0xFFFFFFFF, 2);
            q.setTypeface(Typeface.DEFAULT, Typeface.ITALIC);
            body.addView(q);
        }

        final SpannableStringBuilder facts = new SpannableStringBuilder();
        fact(facts, R.string.lesson_fire, i.fire.equalsIgnoreCase(i.name) ? "" : i.fire);
        fact(facts, R.string.lesson_activity, i.activity);
        fact(facts, R.string.lesson_entrapment, join(i.entrapment));
        fact(facts, R.string.lesson_behavior, join(i.behavior));
        if (facts.length() > 0) {
            body.addView(heading(R.string.lesson_details));
            body.addView(text(facts, 14f, 0xFFFFFFFF, 2));
        }

        if (!i.report.isEmpty()) {
            final Button open = button(R.string.lesson_open_report);
            open.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openReport(i.report);
                }
            });
            body.addView(open);
            body.addView(text(pluginContext.getString(R.string.lesson_report_needs_network),
                    13f, QUIET, 2));
        }

        body.addView(text(sourceEdited.isEmpty()
                ? pluginContext.getString(R.string.lesson_source)
                : pluginContext.getString(R.string.lesson_source_dated, longDate(sourceEdited)),
                13f, QUIET, 14));
    }

    /** "19 fatalities" / "4 fatalities, 2 injuries" / "1 injury". */
    static String toll(Incident i) {
        final StringBuilder b = new StringBuilder();
        if (i.fatalities > 0)
            b.append(i.fatalities).append(i.fatalities == 1 ? " fatality" : " fatalities");
        if (i.injuries > 0) {
            if (b.length() > 0)
                b.append(", ");
            b.append(i.injuries).append(i.injuries == 1 ? " injury" : " injuries");
        }
        return b.toString();
    }

    /** The report opens in the device's browser; it is https, checked when it was read. */
    private void openReport(String url) {
        try {
            final Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            view.addCategory(Intent.CATEGORY_BROWSABLE);
            view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mapView.getContext().startActivity(view);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(mapView.getContext(),
                    pluginContext.getString(R.string.lesson_no_browser), Toast.LENGTH_LONG).show();
        }
    }

    static String longDate(String ymd) {
        final String[] months = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug",
                "Sep", "Oct", "Nov", "Dec"};
        try {
            return months[Integer.parseInt(ymd.substring(5, 7)) - 1] + " "
                    + Integer.parseInt(ymd.substring(8, 10)) + ", " + ymd.substring(0, 4);
        } catch (RuntimeException e) {
            return ymd;
        }
    }

    private static String join(List<String> parts) {
        final StringBuilder b = new StringBuilder();
        for (String p : parts) {
            if (b.length() > 0)
                b.append(", ");
            b.append(p);
        }
        return b.toString();
    }

    private void fact(SpannableStringBuilder sb, int label, String value) {
        if (value == null || value.isEmpty())
            return;
        if (sb.length() > 0)
            sb.append('\n');
        append(sb, pluginContext.getString(label) + ": ", 0xFFFFFFFF, true);
        sb.append(value);
    }

    private static void append(SpannableStringBuilder sb, String s, int color, boolean bold) {
        final int start = sb.length();
        sb.append(s);
        sb.setSpan(new ForegroundColorSpan(color), start, sb.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (bold)
            sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private TextView heading(int text) {
        final TextView t = new TextView(pluginContext);
        t.setText(pluginContext.getString(text));
        t.setAllCaps(true);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f * scale);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(HEADING_COLOR);
        t.setPadding(0, dp(14), 0, dp(2));
        return t;
    }

    private TextView text(CharSequence s, float sp, int color, int topDp) {
        final TextView t = new TextView(pluginContext);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * scale);
        t.setTextColor(color);
        t.setPadding(0, dp(topDp), dp(4), 0);
        t.setLineSpacing(0f, 1.1f);
        return t;
    }

    private Button button(int label) {
        final Button b = new Button(pluginContext, null, 0, R.style.TakwerxButton);
        b.setText(pluginContext.getString(label));
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * pluginContext.getResources().getDisplayMetrics().density);
    }
}

package com.atakmap.android.atmosphere.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.atakmap.android.atmosphere.data.Erc;
import com.atakmap.android.atmosphere.net.EgressPolicy;
import com.atakmap.android.atmosphere.net.Http;
import com.atakmap.android.atmosphere.overlay.ErcOverlay;

/**
 * The Fire Danger page: one Predictive Service Area's GACC chart as large as the pane
 * allows, its ERC and BI under it. A tap on an area of the Fire Danger layer, or the
 * button on a fire weather zone, opens it with the pane wide, the way a river gauge
 * opens on its hydrograph (operator, 2026-10-05: "go like the almost full screen like
 * we do on the river gauges so i can see the erc chart large").
 *
 * <p>The chart is the GACC's own picture, fetched when the area is shown; the numbers
 * are the national service's, which every area has, chart or not.
 */
public final class ErcPage {

    private final Context pluginContext;
    private final EgressPolicy egress;
    private final ScrollView root;
    private final TextView title, note, numbers, enlarge;
    private final ImageView chart;
    private int generation;
    /** The chart showing, for the full-screen viewer, and what to call it there. */
    private Bitmap chartBitmap;
    private String caption = "";

    public ErcPage(Context pluginContext, EgressPolicy egress) {
        this.pluginContext = pluginContext;
        this.egress = egress;
        root = new ScrollView(pluginContext);
        final LinearLayout body = new LinearLayout(pluginContext);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, dp(4), 0, dp(12));
        root.addView(body);

        title = text(17, true);
        body.addView(title);
        // Says the chart opens bigger, where the eye already is (operator, 2026-10-05:
        // "on the first page like a tap to enlarge helper").
        enlarge = text(14, true);
        enlarge.setText("Tap the chart to enlarge");
        enlarge.setTextColor(0xFF3DDC61);   // the plugin's own "on" green
        enlarge.setPadding(0, dp(4), 0, 0);
        // Invisible rather than gone until there is a chart: the chart is sized to the
        // room under this line, and a line that appeared later pushed its foot off
        // the screen (XCover, 2026-10-05).
        enlarge.setVisibility(View.INVISIBLE);
        enlarge.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openFullScreen();
            }
        });
        body.addView(enlarge);
        chart = new ImageView(pluginContext);
        chart.setAdjustViewBounds(true);
        chart.setScaleType(ImageView.ScaleType.FIT_CENTER);
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        body.addView(chart, lp);
        // A tap puts it on the whole screen to pinch and drag.
        chart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openFullScreen();
            }
        });
        note = text(13, false);
        note.setAlpha(0.8f);
        body.addView(note);
        numbers = text(15, false);
        numbers.setPadding(0, dp(8), 0, 0);
        body.addView(numbers);

        // The whole chart in view at once: never taller than the page itself.
        root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot,
                    int or, int ob) {
                final int h = b - t - title.getHeight() - enlarge.getHeight() - dp(16);
                if (h > 0 && h != chart.getMaxHeight())
                    chart.setMaxHeight(h);
            }
        });
        showNothing();
    }

    public View view() {
        return root;
    }

    /** Nothing chosen yet: say how to choose. */
    private void showNothing() {
        title.setText("Fire danger");
        note.setText("");
        numbers.setText("Turn on Fire Danger on the Layers page and tap an area on the map,"
                + " or open a fire weather zone and press its fire danger button.");
        chart.setImageDrawable(null);
    }

    /**
     * One area: its chart, as big as the page, and its numbers.
     *
     * @param fullScreen also put the chart on the whole screen as soon as it arrives:
     *                   a tap on the map is asking to read the chart
     */
    public void show(Erc.Psa p, final boolean fullScreen) {
        if (p == null) {
            showNothing();
            return;
        }
        final int mine = ++generation;
        root.scrollTo(0, 0);
        title.setText(p.name + " (" + p.code + ")");
        numbers.setText(ErcOverlay.details(p));
        chart.setImageDrawable(null);
        chartBitmap = null;
        enlarge.setVisibility(View.INVISIBLE);
        caption = p.name + " (" + p.code + ")"
                + (p.ercObserved.known() ? ":  ERC " + Math.round(p.ercObserved.value) + ", "
                        + Erc.ordinal(p.ercObserved.percentile) + " percentile on "
                        + Erc.observedDay(p.updated) : "");
        final String url = p.chartUrl();
        if (url == null) {
            note.setText(p.gacc.equals("USGASAC") || p.gacc.equals("USWIEACC")
                    ? "This area's GACC no longer publishes ERC charts."
                    : "No GACC chart for this area.");
            return;
        }
        note.setText("Getting the " + gaccShort(p) + " ERC chart…");
        Http.getBitmap(url, egress.userAgent(), new Http.BitmapCallback() {
            @Override
            public void onSuccess(Bitmap bitmap) {
                if (mine != generation)
                    return;
                chartBitmap = bitmap;
                chart.setImageBitmap(bitmap);
                enlarge.setVisibility(View.VISIBLE);
                note.setText(gaccShort(p) + "'s chart. Its percentile bands may cover a "
                        + "fire season rather than the whole year.");
                if (fullScreen)
                    openFullScreen();
            }

            @Override
            public void onFailure(String error) {
                if (mine == generation)
                    note.setText("Could not get the GACC chart: " + error);
            }
        });
    }

    private void openFullScreen() {
        final Bitmap b = chartBitmap;
        if (b == null)
            return;
        android.graphics.drawable.Drawable face = null;
        try {
            face = pluginContext.getResources().getDrawable(
                    com.atakmap.android.atmosphere.plugin.R.drawable.btn_gray);
        } catch (RuntimeException noFace) {
            // a plain button still closes it
        }
        ChartViewer.show(b, caption, face);
    }

    /** "Southern California GACC", or the unit id when the service gave no name. */
    private static String gaccShort(Erc.Psa p) {
        final String n = p.gaccName.replace("Geographic Area Coordination Center", "GACC")
                .replace("Coordination Center", "GACC").trim();
        return n.isEmpty() ? p.gacc : n;
    }

    private TextView text(int sp, boolean bold) {
        final TextView t = new TextView(pluginContext);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(0xFFFFFFFF);
        if (bold)
            t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * pluginContext.getResources().getDisplayMetrics().density);
    }
}

package com.atakmap.android.atmosphere.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.atakmap.android.atmosphere.compat.MapCompat;
import com.atakmap.android.atmosphere.data.Erc;
import com.atakmap.android.atmosphere.net.Http;
import com.atakmap.android.atmosphere.overlay.ErcOverlay;

/**
 * One Predictive Service Area's fire danger: its ERC and BI, observed and forecast, and
 * the GACC's own ERC chart under them -- the picture crews already know, with the
 * season's line, the average, the record and the forecast tail.
 *
 * <p>A dialog on ATAK's context, never the plugin's (a plugin-context dialog takes
 * ATAK down). The chart is fetched when the dialog opens and dropped with it.
 */
final class ErcDialog {

    private ErcDialog() {
    }

    static void show(Erc.Psa p, String userAgent) {
        final Context ctx = MapCompat.atakContext();
        if (ctx == null || p == null)
            return;
        final int pad = dp(ctx, 12);
        final LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(pad, pad, pad, pad);

        final TextView numbers = new TextView(ctx);
        numbers.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        numbers.setText(ErcOverlay.details(p));
        body.addView(numbers);

        final TextView note = new TextView(ctx);
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        note.setPadding(0, pad, 0, dp(ctx, 4));
        body.addView(note);

        final ImageView chart = new ImageView(ctx);
        chart.setAdjustViewBounds(true);
        chart.setScaleType(ImageView.ScaleType.FIT_CENTER);
        body.addView(chart, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final ScrollView scroll = new ScrollView(ctx);
        scroll.addView(body);
        final AlertDialog dialog = new AlertDialog.Builder(ctx)
                .setTitle(p.name + " (" + p.code + "): fire danger")
                .setView(scroll)
                .setNegativeButton("Close", null)
                .show();

        final String url = p.chartUrl();
        if (url == null) {
            note.setText(p.gacc.equals("USGASAC") || p.gacc.equals("USWIEACC")
                    ? "This area's GACC no longer publishes ERC charts."
                    : "No GACC chart for this area.");
            return;
        }
        note.setText("Getting the " + gaccShort(p) + " ERC chart…");
        Http.getBitmap(url, userAgent, new Http.BitmapCallback() {
            @Override
            public void onSuccess(Bitmap bitmap) {
                if (!dialog.isShowing())
                    return;
                chart.setImageBitmap(bitmap);
                note.setText(gaccShort(p) + "'s ERC chart. Its percentile bands may "
                        + "cover a fire season rather than the whole year.");
            }

            @Override
            public void onFailure(String error) {
                if (dialog.isShowing())
                    note.setText("Could not get the GACC chart: " + error);
            }
        });
    }

    /** "Southern California GACC", or the unit id when the service gave no name. */
    private static String gaccShort(Erc.Psa p) {
        final String n = p.gaccName.replace("Geographic Area Coordination Center", "GACC")
                .replace("Coordination Center", "GACC").trim();
        return n.isEmpty() ? p.gacc : n;
    }

    private static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}

package com.atakmap.android.lces.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.os.Build;
import android.text.Html;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.BackgroundColorSpan;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.URLSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.lces.irpg.IrpgPages;
import com.atakmap.android.lces.plugin.R;
import com.atakmap.coremap.log.Log;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Lays an IRPG page out as views: headings in the section's paper color, white
 * body text with the book's bold, numbers and bullets hanging so a wrapped line
 * starts under the text, tables that wrap, the book's own diagrams and tables as
 * pictures, and "see page N" as a tap.
 */
final class PageView {

    private static final String TAG = "LCES.PageView";
    private static final int HEADING_COLOR = 0xFF00E5FF;
    private static final int HIGHLIGHT_BG = 0xFFFFD54F;
    private static final float BODY_SP = 14f;

    /** What the pane does when the reader taps something that opens a page. */
    interface Opener {
        void openPage(String pageId);

        void openPrintedPage(int n);
    }

    private final Context ctx;
    private final float density;
    private final Opener opener;
    /** Contents sections left open, for as long as the plugin is loaded. */
    private final Set<String> open = new HashSet<>();
    /** 1.0 normal, larger for the Settings page's "Large". */
    private float scale = 1f;

    PageView(Context pluginContext, Opener opener) {
        ctx = pluginContext;
        density = pluginContext.getResources().getDisplayMetrics().density;
        this.opener = opener;
    }

    void setScale(float s) {
        scale = s;
    }

    /** The IRPG's paper colors, toned to read on ATAK's dark pane. */
    static int paperColor(String name) {
        if (name == null)
            return 0xFFFFFFFF;
        switch (name) {
            case "green":
                return 0xFF5FD47A;
            case "gray":
                return 0xFFB8B8B8;
            case "orange":
                return 0xFFFFA040;
            case "yellow":
                return 0xFFFFE15A;
            case "blue":
                return 0xFF5CB8FF;
            case "red":
                return 0xFFFF6B6B;
            default:
                return 0xFFFFFFFF;
        }
    }

    /** Headings take the paper color; the white sections take the pane's cyan. */
    private static int headingColor(IrpgPages.Page page) {
        return "white".equals(page.color) ? HEADING_COLOR : paperColor(page.color);
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    /**
     * @param highlight words to mark on the page, from a search; may be null
     * @param continueId for the contents: the page last read, to offer again
     */
    void render(IrpgPages pages, IrpgPages.Page page, LinearLayout into, String highlight,
            String continueId) {
        into.removeAllViews();
        final boolean isContents = IrpgPages.CONTENTS.equals(page.id);
        if (!isContents) {
            final TextView where = text(page.section + " · " + pages.where(page), 13f);
            where.setTextColor(paperColor(page.color));
            where.setTypeface(Typeface.DEFAULT_BOLD);
            where.setPadding(0, dp(8), 0, 0);
            into.addView(where);
        }
        final TextView source = text(pages.citation(), 12f);
        source.setTextColor(ctx.getResources().getColor(R.color.source_gray));
        source.setPadding(0, dp(isContents ? 8 : 2), 0, dp(4));
        into.addView(source);

        if (isContents) {
            contents(pages, page, into, continueId);
            return;
        }
        final int hColor = headingColor(page);
        for (IrpgPages.Block b : page.blocks) {
            switch (b.type) {
                case IrpgPages.Block.HEADING:
                    into.addView(heading(b.html, hColor, highlight));
                    break;
                case IrpgPages.Block.HEADING2: {
                    final TextView h = body(b.html, highlight);
                    h.setTypeface(Typeface.DEFAULT_BOLD);
                    h.setPadding(0, dp(10), 0, dp(2));
                    into.addView(h);
                    break;
                }
                case IrpgPages.Block.NUMBERED:
                    into.addView(hanging(b.number + ".", b, highlight, 28));
                    break;
                case IrpgPages.Block.BULLET:
                    into.addView(hanging(b.mark != null ? b.mark : "•", b, highlight, 18));
                    break;
                case IrpgPages.Block.CHECK:
                    into.addView(hanging("□", b, highlight, 22));
                    break;
                case IrpgPages.Block.SUB_BULLET:
                    into.addView(hanging("○", b, highlight, 18));
                    break;
                case IrpgPages.Block.DASH:
                    into.addView(hanging("–", b, highlight, 18));
                    break;
                case IrpgPages.Block.TABLE:
                    into.addView(table(b, hColor, highlight));
                    break;
                case IrpgPages.Block.FIGURE:
                    into.addView(figure(b));
                    break;
                default: {
                    final TextView p = body(b.html, highlight);
                    p.setPadding(dp(18 * b.level), dp(6), 0, dp(2));
                    into.addView(p);
                }
            }
        }
    }

    private TextView text(String s, float sp) {
        final TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * scale);
        t.setTextColor(0xFFFFFFFF);
        return t;
    }

    /** Body text from the book's markup, its page links live, search words marked. */
    private TextView body(String html, String highlight) {
        final TextView t = text("", BODY_SP);
        final Spanned spanned = fromHtml(html);
        final SpannableStringBuilder sb = new SpannableStringBuilder(spanned);
        boolean links = false;
        for (URLSpan u : sb.getSpans(0, sb.length(), URLSpan.class)) {
            final String url = u.getURL();
            if (url == null || !url.startsWith("pg:"))
                continue;
            final int start = sb.getSpanStart(u), end = sb.getSpanEnd(u);
            sb.removeSpan(u);
            final int n;
            try {
                n = Integer.parseInt(url.substring(3));
            } catch (NumberFormatException e) {
                continue;
            }
            sb.setSpan(new ClickableSpan() {
                @Override
                public void onClick(View widget) {
                    opener.openPrintedPage(n);
                }
            }, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            links = true;
        }
        if (highlight != null)
            mark(sb, highlight);
        t.setText(sb);
        t.setLinkTextColor(HEADING_COLOR);
        if (links)
            t.setMovementMethod(LinkMovementMethod.getInstance());
        return t;
    }

    private static void mark(SpannableStringBuilder sb, String highlight) {
        final String lower = sb.toString().toLowerCase(Locale.US);
        for (String w : highlight.toLowerCase(Locale.US).trim().split("\\s+")) {
            if (w.isEmpty())
                continue;
            int at = lower.indexOf(w);
            while (at >= 0) {
                sb.setSpan(new BackgroundColorSpan(HIGHLIGHT_BG), at, at + w.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new ForegroundColorSpan(0xFF000000), at, at + w.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                at = lower.indexOf(w, at + w.length());
            }
        }
    }

    @SuppressWarnings("deprecation")
    private static Spanned fromHtml(String html) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            return Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT);
        return Html.fromHtml(html);
    }

    private TextView heading(String html, int color, String highlight) {
        final TextView h = body(html, highlight);
        h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f * scale);
        h.setAllCaps(true);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setTextColor(color);
        h.setPadding(0, dp(12), 0, dp(2));
        return h;
    }

    /** A mark in a fixed column and the text beside it, wrapping under itself. */
    private View hanging(String mark, IrpgPages.Block b, String highlight, int markDp) {
        final LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(18 * b.level), dp(3), 0, dp(3));
        final TextView m = text(mark, BODY_SP);
        m.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(m, new LinearLayout.LayoutParams(dp(markDp * scale),
                LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(body(b.html, highlight), new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    /**
     * A table of equal columns that wrap their cells, the header in the section's
     * color. Equal widths, not fitted ones: the book's tables are a few short
     * columns, and a fitted layout let a long header starve the last column.
     */
    private View table(IrpgPages.Block b, int color, String highlight) {
        final LinearLayout t = new LinearLayout(ctx);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(0, dp(6), 0, dp(6));
        if (b.header != null)
            t.addView(tableRow(b.header, color, true, null, false));
        boolean shade = false;
        for (List<String> row : b.rows) {
            t.addView(tableRow(row, 0xFFFFFFFF, false, highlight, shade));
            shade = !shade;
        }
        return t;
    }

    private View tableRow(List<String> cells, int color, boolean bold, String highlight,
            boolean shade) {
        final LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        if (shade)
            r.setBackgroundColor(0x14FFFFFF);
        for (String cell : cells) {
            final TextView c = body(cell, highlight);
            if (bold)
                c.setTypeface(Typeface.DEFAULT_BOLD);
            c.setTextColor(color);
            c.setPadding(dp(4), dp(5), dp(6), dp(5));
            r.addView(c, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        return r;
    }

    /** One of the book's own diagrams or tables, cut from the PDF page. */
    private View figure(IrpgPages.Block b) {
        final ImageView v = new ImageView(ctx);
        v.setAdjustViewBounds(true);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        v.setPadding(0, dp(6), 0, dp(6));
        try (InputStream in = ctx.getAssets().open("irpg/" + b.src)) {
            final BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            v.setImageBitmap(BitmapFactory.decodeStream(in, null, o));
        } catch (IOException e) {
            Log.e(TAG, "picture missing: " + b.src, e);
        }
        return v;
    }

    /** Each section a drop-down named in its paper color; its lines open their pages. */
    private void contents(IrpgPages pages, IrpgPages.Page page, LinearLayout into,
            String continueId) {
        final IrpgPages.Page last = continueId == null ? null : pages.get(continueId);
        if (last != null && !IrpgPages.CONTENTS.equals(last.id)) {
            final Button cont = (Button) PluginLayoutInflater.inflate(ctx, R.layout.continue_button, null);
            cont.setText(ctx.getString(R.string.continue_reading, last.title));
            cont.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    opener.openPage(last.id);
                }
            });
            into.addView(cont);
        }
        LinearLayout body = null;
        for (IrpgPages.Block b : page.blocks) {
            if (IrpgPages.Block.HEADING.equals(b.type)) {
                body = fold(b.html, paperColor(b.color), into);
            } else if (body != null) {
                body.addView(entry(b));
            }
        }
    }

    private LinearLayout fold(final String title, int color, LinearLayout into) {
        final View row = PluginLayoutInflater.inflate(ctx, R.layout.fold_row, null);
        final Button head = row.findViewById(R.id.fold_head);
        final ImageButton chevron = row.findViewById(R.id.fold_chev);
        final LinearLayout body = row.findViewById(R.id.fold_body);
        head.setText(title);
        head.setTextColor(color);
        final View.OnClickListener flip = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!open.remove(title))
                    open.add(title);
                showFold(open.contains(title), chevron, body);
            }
        };
        head.setOnClickListener(flip);
        chevron.setOnClickListener(flip);
        showFold(open.contains(title), chevron, body);
        into.addView(row);
        return body;
    }

    private static void showFold(boolean isOpen, View chevron, View body) {
        chevron.setRotation(isOpen ? 180f : 0f);
        body.setVisibility(isOpen ? View.VISIBLE : View.GONE);
    }

    private View entry(final IrpgPages.Block b) {
        final LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setMinimumHeight(dp(40));
        // Clear of the edge and the scrollbar, under the arrow rather than past it.
        row.setPadding(dp(4), dp(8), dp(16), dp(8));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(text(IrpgPages.unmarkup(b.html), BODY_SP), new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        final TextView pg = text(b.number == null ? "" : b.number, BODY_SP);
        pg.setGravity(Gravity.END);
        pg.setTextColor(ctx.getResources().getColor(R.color.source_gray));
        pg.setPadding(dp(8), 0, 0, 0);
        row.addView(pg, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (b.pageId != null)
                    opener.openPage(b.pageId);
            }
        });
        return row;
    }
}

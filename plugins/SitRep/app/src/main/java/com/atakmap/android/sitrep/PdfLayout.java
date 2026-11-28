package com.atakmap.android.sitrep;

import java.util.ArrayList;
import java.util.List;

/**
 * Lays a {@link ReportDoc} out on US Letter pages, in points: text wraps at word
 * breaks (a word too wide for its line is cut), a table that runs past a page goes
 * on with its head row drawn again, a section head never sits alone at the foot of
 * a page, and every page gets the footer with its number. Plain Java: the device
 * supplies only how wide a string is ({@link Measure}), so the page count the tests
 * see is the page count the device draws.
 */
public final class PdfLayout {

    public static final float PAGE_W = 612, PAGE_H = 792;
    static final float MARGIN = 42, TOP = 46, FOOTER = 50;
    static final float CONTENT_W = PAGE_W - 2 * MARGIN;
    static final float BOTTOM = PAGE_H - FOOTER;

    public static final float TITLE = 24, META = 11.5f, SECTION = 14, GROUP = 12, BODY = 11.5f, NOTE = 9.5f,
            CELL = 10, FOOT = 8.5f;
    static final float CELL_PAD = 3;
    /** Most lines one table cell shows; a row is never taller than a page. */
    static final int MAX_CELL_LINES = 24;

    public static final int BLACK = 0xFF000000, HEAD = 0xFF0B4F6C, GREY = 0xFF555555, RULE = 0xFFBBBBBB,
            HEAD_FILL = 0xFFE4ECF0;

    /** How wide a string is drawn, in points. */
    public interface Measure {
        float width(String s, float size, boolean bold);
    }

    /** One thing drawn: text at a baseline, a line, or a filled box. */
    public static final class Op {
        public static final int TEXT = 0, LINE = 1, BOX = 2;
        public final int type;
        public final float x, y, x2, y2, size;
        public final boolean bold;
        public final int color;
        public final String text;

        Op(int type, float x, float y, float x2, float y2, float size, boolean bold, int color, String text) {
            this.type = type;
            this.x = x;
            this.y = y;
            this.x2 = x2;
            this.y2 = y2;
            this.size = size;
            this.bold = bold;
            this.color = color;
            this.text = text;
        }
    }

    public static final class Page {
        public final List<Op> ops = new ArrayList<>();

        /** Every string on the page, one per line, for the tests. */
        public String text() {
            final StringBuilder b = new StringBuilder();
            for (Op o : ops)
                if (o.type == Op.TEXT)
                    b.append(o.text).append('\n');
            return b.toString();
        }
    }

    private final Measure m;
    private final List<Page> pages = new ArrayList<>();
    private Page page;
    private float y;

    private PdfLayout(Measure m) {
        this.m = m;
    }

    public static List<Page> layout(ReportDoc doc, Measure m) {
        final PdfLayout l = new PdfLayout(m);
        l.newPage();
        for (ReportDoc.Block b : doc.blocks)
            l.block(b);
        l.footers(doc);
        return l.pages;
    }

    private static float lh(float size) {
        return size * 1.32f;
    }

    private void newPage() {
        page = new Page();
        pages.add(page);
        y = TOP;
    }

    private void need(float h) {
        if (y + h > BOTTOM && y > TOP)
            newPage();
    }

    private void block(ReportDoc.Block b) {
        switch (b.kind) {
            case TITLE:
                text(b.text, TITLE, true, HEAD, MARGIN, CONTENT_W, 0, 4);
                break;
            case META:
                text(b.text, META, false, BLACK, MARGIN, CONTENT_W, 0, 0);
                break;
            case SECTION:
                // The head, its rule, and room for two lines under it, or a new page.
                need(lh(SECTION) * wrap(b.text, SECTION, true, CONTENT_W).size() + 14 + 2 * lh(BODY));
                y += 10;
                page.ops.add(new Op(Op.LINE, MARGIN, y, PAGE_W - MARGIN, y, 0.8f, false, HEAD, null));
                y += 4;
                text(b.text, SECTION, true, HEAD, MARGIN, CONTENT_W, 0, 3);
                break;
            case GROUP:
                need(lh(GROUP) + 2 * lh(CELL) + 2 * CELL_PAD + 4);
                y += 4;
                text(b.text, GROUP, true, BLACK, MARGIN, CONTENT_W, 0, 2);
                break;
            case LINE: {
                final float indent = b.indent * 14;
                text(b.text, BODY, false, BLACK, MARGIN + indent, CONTENT_W - indent, 0, 1);
                break;
            }
            case NOTE:
                text(b.text, NOTE, false, GREY, MARGIN, CONTENT_W, 1, 1);
                break;
            case TABLE:
                table(b);
                break;
        }
    }

    /** Wrapped text, breaking to a new page between lines. */
    private void text(String s, float size, boolean bold, int color, float x, float w, float before, float after) {
        y += before;
        for (String line : wrap(s, size, bold, w)) {
            need(lh(size));
            y += lh(size);
            page.ops.add(new Op(Op.TEXT, x, y - size * 0.28f, 0, 0, size, bold, color, line));
        }
        y += after;
    }

    private void table(ReportDoc.Block t) {
        final int cols = t.header.length;
        final float[] w = new float[cols];
        for (int c = 0; c < cols; c++)
            w[c] = CONTENT_W * t.widths[c];
        final List<List<String>> head = cells(t.header, w, true);
        final float headH = rowHeight(head);
        if (t.rows.isEmpty())
            return;
        boolean headDrawn = false;
        for (String[] row : t.rows) {
            final List<List<String>> cells = cells(row, w, false);
            final float h = rowHeight(cells);
            if (!headDrawn || y + h > BOTTOM) {
                if (y + headH + h > BOTTOM && y > TOP)
                    newPage();
                drawRow(head, w, headH, true);
                headDrawn = true;
            }
            drawRow(cells, w, h, false);
        }
        y += 4;
    }

    private List<List<String>> cells(String[] row, float[] w, boolean bold) {
        final List<List<String>> out = new ArrayList<>();
        for (int c = 0; c < w.length; c++) {
            List<String> l = wrap(c < row.length && row[c] != null ? row[c] : "", CELL, bold, w[c] - 2 * CELL_PAD);
            if (l.size() > MAX_CELL_LINES) {
                l = new ArrayList<>(l.subList(0, MAX_CELL_LINES));
                l.set(MAX_CELL_LINES - 1, l.get(MAX_CELL_LINES - 1) + " ...");
            }
            out.add(l);
        }
        return out;
    }

    private static float rowHeight(List<List<String>> cells) {
        int n = 1;
        for (List<String> c : cells)
            n = Math.max(n, c.size());
        return n * lh(CELL) + 2 * CELL_PAD;
    }

    private void drawRow(List<List<String>> cells, float[] w, float h, boolean head) {
        final float top = y;
        if (head)
            page.ops.add(new Op(Op.BOX, MARGIN, top, PAGE_W - MARGIN, top + h, 0, false, HEAD_FILL, null));
        float x = MARGIN;
        for (int c = 0; c < cells.size(); c++) {
            float ly = top + CELL_PAD;
            for (String line : cells.get(c)) {
                ly += lh(CELL);
                page.ops.add(new Op(Op.TEXT, x + CELL_PAD, ly - CELL * 0.28f, 0, 0, CELL, head, BLACK, line));
            }
            x += w[c];
        }
        y = top + h;
        page.ops.add(new Op(Op.LINE, MARGIN, y, PAGE_W - MARGIN, y, 0.4f, false, RULE, null));
    }

    private void footers(ReportDoc doc) {
        final int n = pages.size();
        for (int i = 0; i < n; i++) {
            final Page p = pages.get(i);
            final float top = PAGE_H - FOOTER + 12;
            p.ops.add(new Op(Op.LINE, MARGIN, top, PAGE_W - MARGIN, top, 0.5f, false, RULE, null));
            final String label = ReportDoc.pageLabel(i + 1, n);
            p.ops.add(new Op(Op.TEXT, MARGIN, top + 13, 0, 0, FOOT, true, BLACK, label));
            final float lw = m.width(label, FOOT, true) + 12;
            final List<String> snap = wrap(doc.snapshot, FOOT, false, CONTENT_W - lw);
            p.ops.add(new Op(Op.TEXT, MARGIN + lw, top + 13, 0, 0, FOOT, false, BLACK, snap.get(0)));
            final List<String> src = wrap(ReportDoc.SOURCES, FOOT, false, CONTENT_W);
            p.ops.add(new Op(Op.TEXT, MARGIN, top + 25, 0, 0, FOOT, false, GREY, src.get(0)));
        }
    }

    /** Lines no wider than {@code w}: breaks at spaces, and inside a word only when it must. */
    List<String> wrap(String s, float size, boolean bold, float w) {
        final List<String> out = new ArrayList<>();
        if (s == null || s.isEmpty()) {
            out.add("");
            return out;
        }
        StringBuilder line = new StringBuilder();
        for (String word : s.split(" ")) {
            if (word.isEmpty())
                continue;
            final String tried = line.length() == 0 ? word : line + " " + word;
            if (m.width(tried, size, bold) <= w) {
                line.setLength(0);
                line.append(tried);
                continue;
            }
            if (line.length() > 0) {
                out.add(line.toString());
                line.setLength(0);
            }
            // A word wider than the line: cut it where it fills the line.
            String rest = word;
            while (m.width(rest, size, bold) > w && rest.length() > 1) {
                int k = rest.length() - 1;
                while (k > 1 && m.width(rest.substring(0, k), size, bold) > w)
                    k--;
                out.add(rest.substring(0, k));
                rest = rest.substring(k);
            }
            line.append(rest);
        }
        if (line.length() > 0 || out.isEmpty())
            out.add(line.toString());
        return out;
    }
}

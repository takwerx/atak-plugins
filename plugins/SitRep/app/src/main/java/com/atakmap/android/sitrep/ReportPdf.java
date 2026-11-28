package com.atakmap.android.sitrep;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/**
 * Draws laid-out pages into a PDF with Android's own {@link PdfDocument}: no
 * library, no network. Runs on a worker; the file is written to a temp name and
 * renamed, so a half-written PDF is never shared.
 */
final class ReportPdf {

    private ReportPdf() {
    }

    /** Called after each page is drawn. */
    interface Progress {
        void page(int done, int of);
    }

    /** Widths from the same paints that draw, so the layout matches the page. */
    static final class AndroidMeasure implements PdfLayout.Measure {
        private final Paint plain = paint(false), bold = paint(true);

        @Override
        public float width(String s, float size, boolean b) {
            final Paint p = b ? bold : plain;
            p.setTextSize(size);
            return p.measureText(s);
        }
    }

    static Paint paint(boolean bold) {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        return p;
    }

    static void write(List<PdfLayout.Page> pages, File out, Progress progress) throws IOException {
        final PdfDocument doc = new PdfDocument();
        final Paint plain = paint(false), bold = paint(true), line = new Paint(Paint.ANTI_ALIAS_FLAG),
                box = new Paint();
        line.setStyle(Paint.Style.STROKE);
        box.setStyle(Paint.Style.FILL);
        try {
            for (int i = 0; i < pages.size(); i++) {
                final PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(
                        (int) PdfLayout.PAGE_W, (int) PdfLayout.PAGE_H, i + 1).create();
                final PdfDocument.Page page = doc.startPage(info);
                final Canvas c = page.getCanvas();
                for (PdfLayout.Op o : pages.get(i).ops) {
                    switch (o.type) {
                        case PdfLayout.Op.TEXT: {
                            final Paint p = o.bold ? bold : plain;
                            p.setTextSize(o.size);
                            p.setColor(o.color);
                            c.drawText(o.text, o.x, o.y, p);
                            break;
                        }
                        case PdfLayout.Op.LINE:
                            line.setColor(o.color);
                            line.setStrokeWidth(o.size);
                            c.drawLine(o.x, o.y, o.x2, o.y2, line);
                            break;
                        case PdfLayout.Op.BOX:
                            box.setColor(o.color);
                            c.drawRect(o.x, o.y, o.x2, o.y2, box);
                            break;
                        default:
                            break;
                    }
                }
                doc.finishPage(page);
                if (progress != null)
                    progress.page(i + 1, pages.size());
            }
            final File dir = out.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs())
                throw new IOException("could not create the share folder");
            final File tmp = new File(dir, out.getName() + ".tmp");
            try (OutputStream os = new FileOutputStream(tmp)) {
                doc.writeTo(os);
            }
            if (out.exists() && !out.delete())
                throw new IOException("could not replace the old PDF");
            if (!tmp.renameTo(out))
                throw new IOException("could not move the PDF into place");
        } finally {
            doc.close();
        }
    }
}

package com.atakmap.android.lces.irpg;

import android.content.Context;

import com.atakmap.coremap.log.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The whole IRPG as carried in the APK, {@code assets/irpg/pages.json} plus the
 * picture crops under {@code assets/irpg/fig/}, all written by
 * {@code tools/irpg_build.py} from the PDF itself. Nothing in here is typed by
 * hand. One {@link Page} is one entry of the book's contents, in book order.
 */
public final class IrpgPages {

    private static final String TAG = "LCES.IrpgPages";
    private static final String ASSET = "irpg/pages.json";
    public static final String CONTENTS = "contents";

    /** One piece of a page, in reading order. */
    public static final class Block {
        public static final String HEADING = "h";
        public static final String HEADING2 = "h2";
        public static final String PARAGRAPH = "p";
        public static final String NUMBERED = "num";
        public static final String BULLET = "li";
        public static final String SUB_BULLET = "sub";
        public static final String CHECK = "chk";
        public static final String DASH = "dash";
        public static final String TABLE = "tbl";
        public static final String FIGURE = "fig";
        public static final String CONTENTS = "toc";

        public final String type;
        /** The text, with {@code <b>} for the book's bold and {@code <a href="pg:N">}. */
        public final String html;
        /** The item number, or the page a contents line points at. */
        public final String number;
        /** A mark the book draws itself ("\", "x"), shown in place of a bullet. */
        public final String mark;
        /** Nesting depth of an item or an indented line. */
        public final int level;
        /** A contents section's paper color, else null. */
        public final String color;
        /** A contents line's page id. */
        public final String pageId;
        /** A picture: asset path and pixel size. */
        public final String src;
        public final int width, height;
        /** A table: header cells (may be null) and rows. */
        public final List<String> header;
        public final List<List<String>> rows;
        private String plain;

        Block(JSONObject b) throws JSONException {
            type = b.getString("t");
            html = b.optString("x", "");
            number = b.has("n") ? b.getString("n") : b.optString("pg", null);
            mark = b.optString("m", null);
            level = b.optInt("lvl", 0);
            color = b.optString("c", null);
            pageId = b.optString("id", null);
            src = b.optString("src", null);
            width = b.optInt("w", 0);
            height = b.optInt("h", 0);
            if (b.has("rows")) {
                header = b.isNull("hdr") ? null : strings(b.getJSONArray("hdr"));
                final JSONArray rs = b.getJSONArray("rows");
                final List<List<String>> out = new ArrayList<>(rs.length());
                for (int i = 0; i < rs.length(); i++)
                    out.add(strings(rs.getJSONArray(i)));
                rows = Collections.unmodifiableList(out);
            } else {
                header = null;
                rows = null;
            }
        }

        private static List<String> strings(JSONArray a) throws JSONException {
            final List<String> out = new ArrayList<>(a.length());
            for (int i = 0; i < a.length(); i++)
                out.add(a.getString(i));
            return Collections.unmodifiableList(out);
        }

        /** The text without markup, for search. */
        public String plain() {
            if (plain == null) {
                final StringBuilder sb = new StringBuilder(unmarkup(html));
                if (rows != null) {
                    if (header != null)
                        for (String h : header)
                            sb.append(' ').append(unmarkup(h));
                    for (List<String> r : rows)
                        for (String c : r)
                            sb.append(' ').append(unmarkup(c));
                }
                plain = sb.toString();
            }
            return plain;
        }
    }

    public static final class Page {
        public final String id;
        public final String title;
        public final String section;
        /** The section's paper color: green, gray, orange, yellow, blue, red, white. */
        public final String color;
        /** Where the page is in the printed IRPG: "5", "ix", "back cover (outside)". */
        public final String printedPage;
        /** How many printed pages it runs over. */
        public final int pageCount;
        public final List<Block> blocks;
        /** Position in book order. */
        final int index;

        Page(int index, JSONObject p) throws JSONException {
            this.index = index;
            id = p.getString("id");
            title = p.getString("title");
            section = p.getString("section");
            color = p.getString("color");
            printedPage = p.getString("pg");
            pageCount = p.getJSONArray("pdf").length();
            final JSONArray bs = p.getJSONArray("blocks");
            final List<Block> out = new ArrayList<>(bs.length());
            for (int j = 0; j < bs.length(); j++)
                out.add(new Block(bs.getJSONObject(j)));
            blocks = Collections.unmodifiableList(out);
        }

        /** The printed page as a number, or -1 for a cover or the front matter. */
        int printedNumber() {
            try {
                return Integer.parseInt(printedPage);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
    }

    /** One page a search found its words on. */
    public static final class Hit {
        public final Page page;
        /** How many of the page's lines hold the words. */
        public final int count;
        /** The first matching line, with a little around it. */
        public final String snippet;
        /** The words are in the page's title. */
        public final boolean inTitle;

        Hit(Page page, int count, String snippet, boolean inTitle) {
            this.page = page;
            this.count = count;
            this.snippet = snippet;
            this.inTitle = inTitle;
        }
    }

    public final String edition;
    private final List<Page> pages;
    private final Map<String, Page> byId = new LinkedHashMap<>();

    private IrpgPages(String edition, List<Page> pages) {
        this.edition = edition;
        this.pages = pages;
        for (Page p : pages)
            byId.put(p.id, p);
    }

    public Page get(String id) {
        return byId.get(id);
    }

    public List<Page> all() {
        return pages;
    }

    /** The page that holds printed page N, for the book's "see page N". */
    public Page byPrintedPage(int n) {
        for (Page p : pages) {
            final int start = p.printedNumber();
            if (start >= 0 && n >= start && n < start + p.pageCount)
                return p;
        }
        return null;
    }

    /** The page before this one in the book, or null at the front. */
    public Page previous(Page p) {
        for (int i = p.index - 1; i >= 0; i--)
            if (!CONTENTS.equals(pages.get(i).id))
                return pages.get(i);
        return null;
    }

    /** The page after this one in the book, or null at the back. */
    public Page next(Page p) {
        for (int i = p.index + 1; i < pages.size(); i++)
            if (!CONTENTS.equals(pages.get(i).id))
                return pages.get(i);
        return null;
    }

    /** "page 5", or where a cover page is. */
    public String where(Page p) {
        return p.printedPage.matches("[0-9ivx]+") ? "page " + p.printedPage : p.printedPage;
    }

    /** "IRPG (NWCG PMS 461), January 2025", the line under every page's title. */
    public String citation() {
        return "IRPG (NWCG PMS 461), " + edition;
    }

    /**
     * Every page holding all the words of the query: the pages whose title has
     * them first, then book order, each with how many lines matched and the first
     * of them. A plain scan: the whole book is a few hundred KB and this takes a
     * few milliseconds.
     */
    public List<Hit> search(String query) {
        final List<Hit> titled = new ArrayList<>();
        final List<Hit> rest = new ArrayList<>();
        final String[] words = query.toLowerCase(Locale.US).trim().split("\\s+");
        if (words.length == 0 || words[0].isEmpty())
            return titled;
        for (Page p : pages) {
            if (CONTENTS.equals(p.id))
                continue;
            final boolean inTitle = containsAll(p.title.toLowerCase(Locale.US), words);
            int count = 0;
            String snippet = null;
            for (Block b : p.blocks) {
                final String text = b.plain();
                final String lower = text.toLowerCase(Locale.US);
                if (!containsAll(lower, words))
                    continue;
                count++;
                if (snippet == null) {
                    final int at = lower.indexOf(words[0]);
                    final int from = Math.max(0, at - 50);
                    final int to = Math.min(text.length(), at + 90);
                    snippet = (from > 0 ? "…" : "") + text.substring(from, to)
                            + (to < text.length() ? "…" : "");
                }
            }
            if (!inTitle && count == 0)
                continue;
            if (snippet == null) {
                final String first = p.blocks.isEmpty() ? "" : p.blocks.get(0).plain();
                snippet = first.length() > 120 ? first.substring(0, 120) + "…" : first;
            }
            (inTitle ? titled : rest).add(new Hit(p, count, snippet, inTitle));
        }
        titled.addAll(rest);
        return titled;
    }

    private static boolean containsAll(String text, String[] words) {
        for (String w : words)
            if (!text.contains(w))
                return false;
        return true;
    }

    /** Tags off, the three escaped characters back. */
    public static String unmarkup(String html) {
        return html.replaceAll("<[^>]+>", "").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&amp;", "&");
    }

    /** Reads the asset. A broken asset gives an empty book, never a dead pane. */
    public static IrpgPages load(Context pluginContext) {
        try (InputStream in = pluginContext.getAssets().open(ASSET)) {
            final ByteArrayOutputStream buf = new ByteArrayOutputStream();
            final byte[] chunk = new byte[16384];
            int n;
            while ((n = in.read(chunk)) > 0)
                buf.write(chunk, 0, n);
            final JSONObject doc = new JSONObject(
                    new String(buf.toByteArray(), StandardCharsets.UTF_8));
            final JSONArray arr = doc.getJSONArray("pages");
            final List<Page> pages = new ArrayList<>(arr.length());
            for (int i = 0; i < arr.length(); i++)
                pages.add(new Page(i, arr.getJSONObject(i)));
            return new IrpgPages(doc.getString("edition"), Collections.unmodifiableList(pages));
        } catch (IOException | JSONException e) {
            Log.e(TAG, "IRPG pages unreadable", e);
            return new IrpgPages("", Collections.<Page>emptyList());
        }
    }
}

package com.atakmap.android.firecast.model;

import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.log.Log;
import com.atakmap.map.layer.feature.geometry.LineString;
import com.atakmap.map.layer.feature.geometry.Polygon;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A run as one file, {@code <runId>.firerun.kmz}, so it travels as one thing: one
 * switch, one delete, one card of inputs (PLAN-FireCast-sharing-runs.md).
 *
 * <p>{@code doc.kml} is a KML document any ATAK imports as a single overlay: a ring per
 * step in the colors the map draws, the start, the perimeter it began from, and the
 * run's card as the document's description, so a phone without Fire Cast still reads
 * what the run assumed. {@code firecast-run.json} is the run's saved form, which a
 * phone with Fire Cast reads instead and reruns with its own engine. Nothing else goes
 * in: no links, no scripts, no other files.
 */
public final class RunFile {

    private static final String TAG = "FireCast";
    public static final String SUFFIX = ".firerun.kmz";
    public static final String KML_ENTRY = "doc.kml", JSON_ENTRY = "firecast-run.json", ICON_ENTRY = "start.png";

    private RunFile() {
    }

    /** Largest run file read or kept; a 3 hour run with buildings is tens of KB. */
    static final long MAX_FILE = 4L << 20;
    /** Largest saved form read out of a file: a 3 hour run with a long perimeter and 8,000 buildings is under 600 KB. */
    private static final int MAX_JSON = 1 << 20;
    /** Deepest nesting a run's saved form has is 3; org.json recurses, and a deep file overflows the stack. */
    private static final int MAX_DEPTH = 16;

    static File dir() {
        final File dir = FileSystemUtils.getItem("tools/firecast/runs");
        if (!dir.isDirectory() && !dir.mkdirs())
            Log.w(TAG, "could not create " + dir);
        return dir;
    }

    /** Where a run's file lives: {@code tools/firecast/runs/<runId>.firerun.kmz}. */
    static File fileFor(String runId) {
        final File dir = dir();
        // The id is ours (a UUID made at run time); anything else never names a file.
        if (runId == null || !runId.matches("[0-9a-f-]{36}"))
            throw new IllegalArgumentException("not a run id");
        return new File(dir, runId + SUFFIX);
    }

    /**
     * Writes the run's file, into a temp file and then into place. Worker thread.
     *
     * @param saved the run's saved form, with who ran it and the engine added
     * @param card  the run's inputs and outputs in plain words
     * @param icon  the start's icon, a PNG, or null for the viewer's own pin
     * @param by    who ran it, for the descriptions a viewer without Fire Cast reads
     */
    static File write(FireModel.Run r, JSONObject saved, String card, byte[] icon, String by) throws Exception {
        final File f = fileFor(r.runId);
        final File tmp = new File(f.getPath() + ".tmp");
        final ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(tmp));
        try {
            // The KML first: a KMZ reader takes the first .kml it finds as the document.
            zip.putNextEntry(new ZipEntry(KML_ENTRY));
            zip.write(kml(r, card, icon != null, by).getBytes("UTF-8"));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(JSON_ENTRY));
            zip.write(saved.toString().getBytes("UTF-8"));
            zip.closeEntry();
            if (icon != null) {
                zip.putNextEntry(new ZipEntry(ICON_ENTRY));
                zip.write(icon);
                zip.closeEntry();
            }
        } finally {
            zip.close();
        }
        if (!tmp.renameTo(f)) {
            if (!f.delete() || !tmp.renameTo(f))
                throw new IllegalStateException("could not keep " + f.getName());
        }
        return f;
    }

    /**
     * A copy of the run's file under a name a person can read, for a data package: the
     * name shows in the package's contents on every phone that gets it. The copy lives in
     * {@code tools/firecast/runs/outbox}; nothing reads a file back by this name.
     */
    static File readableCopy(File f, String title) throws Exception {
        final File dir = new File(dir(), "outbox");
        if (!dir.isDirectory() && !dir.mkdirs())
            Log.w(TAG, "could not create " + dir);
        final File out = new File(dir, safeName(title) + SUFFIX);
        final java.io.InputStream in = new java.io.FileInputStream(f);
        final FileOutputStream o = new FileOutputStream(out);
        try {
            final byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0)
                o.write(buf, 0, n);
        } finally {
            in.close();
            o.close();
        }
        return out;
    }

    /**
     * The id in a saved data package's manifest, or null when the file is not there or
     * not readable as one: {@code <Parameter name="uid" value="..."/>} in
     * {@code MANIFEST/manifest.xml}. Read only to tell our own last send from another
     * package under the same name.
     */
    static String packageUid(File zip) {
        if (zip == null || !zip.isFile() || zip.length() > (64L << 20))
            return null;
        java.util.zip.ZipFile z = null;
        try {
            z = new java.util.zip.ZipFile(zip);
            final ZipEntry e = z.getEntry("MANIFEST/manifest.xml");
            if (e == null || e.getSize() > (1 << 20))
                return null;
            final java.io.InputStream in = z.getInputStream(e);
            final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            try {
                final byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0 && out.size() < (1 << 20))
                    out.write(buf, 0, n);
            } finally {
                in.close();
            }
            final java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("name=\"uid\"\\s+value=\"([^\"]{1,64})\"").matcher(out.toString("UTF-8"));
            return m.find() ? m.group(1) : null;
        } catch (Throwable t) {
            return null;
        } finally {
            if (z != null)
                try {
                    z.close();
                } catch (Exception ignored) {
                }
        }
    }

    /** A title as a file name: letters, digits, spaces and a little punctuation, 80 characters at most. */
    static String safeName(String title) {
        final String s = title.replace(':', '-').replaceAll("[^A-Za-z0-9 .,_-]", "").replaceAll("\\s+", " ").trim();
        final String cut = s.length() > 80 ? s.substring(0, 80).trim() : s;
        return cut.isEmpty() ? "Fire Cast run" : cut;
    }

    /** Removes a run's file, if it has one; the operator's own Delete. */
    static void delete(String runId) {
        try {
            final File f = fileFor(runId);
            if (f.isFile() && !f.delete())
                Log.w(TAG, "could not delete " + f);
        } catch (IllegalArgumentException ignored) {
        }
    }

    /**
     * The saved form out of a run file someone sent, or null when it is not one of ours
     * or breaks a limit: the file under 4 MB, at most four entries looked at, the JSON
     * entry under 2 MB, a run id of our own shape, and the fields a run needs. Nothing
     * in the zip is ever written to disk by its name; only the JSON is read, in memory.
     */
    static JSONObject readSaved(File f) {
        if (f == null || !f.isFile() || f.length() > MAX_FILE)
            return null;
        java.util.zip.ZipInputStream zin = null;
        try {
            zin = new java.util.zip.ZipInputStream(new java.io.BufferedInputStream(new java.io.FileInputStream(f)));
            ZipEntry e;
            int entries = 0;
            while ((e = zin.getNextEntry()) != null && ++entries <= 4) {
                if (!JSON_ENTRY.equals(e.getName()))
                    continue;
                final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                final byte[] buf = new byte[16384];
                int n;
                while ((n = zin.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > MAX_JSON)
                        return null;
                }
                final String text = out.toString("UTF-8");
                if (!shallow(text))
                    return null;
                final JSONObject o = new JSONObject(text);
                if (!o.optString("runId", "").matches("[0-9a-f-]{36}") || !o.has("lat") || !o.has("lon")
                        || !o.has("fuelModel"))
                    return null;
                return o;
            }
        } catch (Throwable ex) {
            // Throwable: whatever a hostile file does here, it reaches ATAK's own import
            // thread, and an Error there takes ATAK down (security review, 2026-10-17).
            Log.w(TAG, "not a run file: " + f.getName() + ": " + ex);
        } finally {
            if (zin != null)
                try {
                    zin.close();
                } catch (Exception ignored) {
                }
        }
        return null;
    }

    /** Whether the JSON nests no deeper than {@link #MAX_DEPTH}, counted outside strings. */
    static boolean shallow(String json) {
        int depth = 0;
        boolean inString = false, escaped = false;
        for (int i = 0; i < json.length(); i++) {
            final char c = json.charAt(i);
            if (inString) {
                if (escaped)
                    escaped = false;
                else if (c == '\\')
                    escaped = true;
                else if (c == '"')
                    inString = false;
            } else if (c == '"') {
                inString = true;
            } else if (c == '{' || c == '[') {
                if (++depth > MAX_DEPTH)
                    return false;
            } else if (c == '}' || c == ']') {
                depth--;
            }
        }
        return true;
    }

    // ---- the KML ----------------------------------------------------------------

    static String kml(FireModel.Run r, String card) {
        return kml(r, card, false, null);
    }

    /**
     * What a phone without Fire Cast shows: the rings named by the clock ("By 10:52
     * (40 min), 131 acres"), each with a short description saying what it is and who
     * made it, and the start as a small marker of its own. The full card is the
     * document's description and the data package's remarks.
     */
    static String kml(FireModel.Run r, String card, boolean icon, String by) {
        final String who = by == null || by.isEmpty() ? "" : " by " + by;
        final StringBuilder k = new StringBuilder(64 * 1024);
        k.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n<Document>\n")
                .append("<name>").append(esc("Fire Cast: " + r.label())).append("</name>\n")
                .append("<description>").append(esc(card)).append("</description>\n");
        for (int i = 0; i < r.steps.length; i++) {
            final int rgb = FireModel.stepColor(i, r.steps.length);
            k.append("<Style id=\"step").append(i).append("\"><LineStyle><color>").append(abgr(0xFF, rgb))
                    .append("</color><width>3</width></LineStyle><PolyStyle><color>").append(abgr(0x30, rgb))
                    .append("</color></PolyStyle></Style>\n");
        }
        k.append("<Style id=\"perimeter\"><LineStyle><color>ffffffff</color><width>2</width></LineStyle>")
                .append("<PolyStyle><fill>0</fill></PolyStyle></Style>\n");
        if (icon)
            k.append("<Style id=\"start\"><IconStyle><scale>0.6</scale><Icon><href>").append(ICON_ENTRY)
                    .append("</href></Icon></IconStyle></Style>\n");
        // Largest first, the way the map draws them, so the first ring is on top.
        final double tolerance = r.cellM / 2d;
        for (int i = r.steps.length - 1; i >= 0; i--) {
            final List<Polygon> shapes = new ArrayList<>();
            if (r.ground != null)
                shapes.addAll(r.ground.rings(r.steps[i]));
            else if (r.result != null)
                shapes.add(FireModel.ellipse(r, r.steps[i]));
            if (shapes.isEmpty())
                continue;
            final String clock = FireModel.stamp(r.ranAt + r.steps[i] * 60000L);
            final String acres = FireModel.acresText(r.acres(r.steps[i]));
            k.append("<Placemark><name>").append(esc("By " + clock + " (" + r.steps[i] + " min), " + acres))
                    .append("</name><description>").append(esc("Fire Cast model run" + who + " at "
                            + FireModel.stamp(r.ranAt) + ": where the fire is expected to be by " + clock + ", "
                            + r.steps[i] + " minutes after the run, " + acres + ". A planning-grade estimate from the "
                            + "fuel, slope and weather it read; the data package's remarks list them. Run again as "
                            + "conditions change."))
                    .append("</description><styleUrl>#step").append(i).append("</styleUrl><MultiGeometry>\n");
            for (Polygon p : shapes)
                polygon(k, p.getExteriorRing(), tolerance);
            k.append("</MultiGeometry></Placemark>\n");
        }
        if (r.perimeter != null && r.perimeter.length >= 3) {
            k.append("<Placemark><name>Perimeter it began from</name><styleUrl>#perimeter</styleUrl><Polygon>")
                    .append("<altitudeMode>clampToGround</altitudeMode><outerBoundaryIs><LinearRing><coordinates>");
            for (double[] p : r.perimeter)
                coord(k, p[1], p[0]);
            coord(k, r.perimeter[0][1], r.perimeter[0][0]);
            k.append("</coordinates></LinearRing></outerBoundaryIs></Polygon></Placemark>\n");
        }
        k.append("<Placemark><name>").append(esc("Fire start, " + FireModel.stamp(r.ranAt))).append("</name><description>")
                .append(esc("Where the Fire Cast model run" + who + " at " + FireModel.stamp(r.ranAt) + " started."))
                .append("</description>").append(icon ? "<styleUrl>#start</styleUrl>" : "")
                .append("<Point><coordinates>");
        coord(k, r.lon, r.lat);
        k.append("</coordinates></Point></Placemark>\n</Document>\n</kml>\n");
        return k.toString();
    }

    /** One ring as a KML polygon, thinned to the tolerance in meters (Douglas-Peucker). */
    private static void polygon(StringBuilder k, LineString ring, double toleranceM) {
        if (ring == null || ring.getNumPoints() < 4)
            return;
        final int n = ring.getNumPoints();
        final double[] x = new double[n], y = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = ring.getX(i);
            y[i] = ring.getY(i);
        }
        final boolean[] keep = new boolean[n];
        keep[0] = keep[n - 1] = true;
        final double mLat = 111320d, mLon = 111320d * Math.max(0.02d, Math.cos(Math.toRadians(y[0])));
        simplify(x, y, 0, n - 1, toleranceM, mLon, mLat, keep);
        int kept = 0;
        for (boolean b : keep)
            if (b)
                kept++;
        if (kept < 4)
            return;
        k.append("<Polygon><altitudeMode>clampToGround</altitudeMode><outerBoundaryIs><LinearRing><coordinates>");
        for (int i = 0; i < n; i++)
            if (keep[i])
                coord(k, x[i], y[i]);
        k.append("</coordinates></LinearRing></outerBoundaryIs></Polygon>\n");
    }

    /** Douglas-Peucker on an index range, iteratively so a long ring cannot overflow the stack. */
    private static void simplify(double[] x, double[] y, int first, int last, double tol, double mLon, double mLat,
            boolean[] keep) {
        final java.util.ArrayDeque<int[]> stack = new java.util.ArrayDeque<>();
        stack.push(new int[] { first, last });
        while (!stack.isEmpty()) {
            final int[] seg = stack.pop();
            final int a = seg[0], b = seg[1];
            if (b <= a + 1)
                continue;
            final double ax = x[a] * mLon, ay = y[a] * mLat, bx = x[b] * mLon, by = y[b] * mLat;
            final double dx = bx - ax, dy = by - ay, len2 = dx * dx + dy * dy;
            double worst = -1;
            int at = -1;
            for (int i = a + 1; i < b; i++) {
                final double px = x[i] * mLon - ax, py = y[i] * mLat - ay;
                final double d = len2 == 0 ? Math.sqrt(px * px + py * py)
                        : Math.abs(px * dy - py * dx) / Math.sqrt(len2);
                if (d > worst) {
                    worst = d;
                    at = i;
                }
            }
            if (worst > tol && at > 0) {
                keep[at] = true;
                stack.push(new int[] { a, at });
                stack.push(new int[] { at, b });
            }
        }
    }

    private static void coord(StringBuilder k, double lon, double lat) {
        k.append(String.format(Locale.US, "%.6f,%.6f,0 ", lon, lat));
    }

    /** KML colors are alpha, blue, green, red. */
    private static String abgr(int alpha, int rgb) {
        return String.format(Locale.US, "%02x%02x%02x%02x", alpha & 0xFF, rgb & 0xFF, (rgb >> 8) & 0xFF, (rgb >> 16) & 0xFF);
    }

    /** Text into XML: the five characters that mean something there, and nothing else touched. */
    static String esc(String s) {
        if (s == null)
            return "";
        final StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            switch (c) {
                case '&': b.append("&amp;"); break;
                case '<': b.append("&lt;"); break;
                case '>': b.append("&gt;"); break;
                case '"': b.append("&quot;"); break;
                case '\'': b.append("&apos;"); break;
                default:
                    // Control characters other than line breaks are not allowed in XML 1.0.
                    if (c >= 0x20 || c == '\n' || c == '\t')
                        b.append(c);
            }
        }
        return b.toString();
    }
}

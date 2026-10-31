package com.atakmap.android.firecast.model;

import android.util.Pair;

import com.atakmap.coremap.log.Log;

import gov.tak.api.importfiles.ImportResolver;

import org.json.JSONObject;

import java.io.File;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Claims a FireCast run file wherever ATAK finds one: a file a contact sent, a Data
 * Sync download, a data package, the Import Manager. ATAK hands every file to the
 * resolvers that match it; this one matches a {@code .firerun.kmz} carrying our saved
 * form, takes ATAK's own KMZ resolver off the list for that file, and gives the run to
 * the model, so a phone with FireCast gets a run, not a second, plain KML overlay. A
 * phone without FireCast never sees this class and imports the KML.
 *
 * <p>The stable {@code gov.tak.api} resolver (present in 5.6, 5.7 and 5.8), per the
 * rule to prefer it where one exists.
 */
final class RunImportResolver extends ImportResolver {

    private static final String TAG = "FireCast";
    private final FireModel model;

    RunImportResolver(FireModel model, gov.tak.api.commons.graphics.Drawable icon) {
        super(".kmz", RunFile.dir(), "FireCast run", icon);
        this.model = model;
    }

    // All three run on ATAK's own threads, some of which do not catch: nothing a file
    // can make happen in here may escape as an Error (security review, 2026-10-17).

    @Override
    public boolean match(File f) {
        try {
            return f != null && f.getName().toLowerCase(Locale.US).endsWith(RunFile.SUFFIX) && RunFile.readSaved(f) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void filterFoundResolvers(List<ImportResolver> found, File f) {
        try {
            if (!match(f))
                return;
            for (Iterator<ImportResolver> it = found.iterator(); it.hasNext();)
                if (it.next() != this)
                    it.remove();
        } catch (Throwable t) {
            Log.w(TAG, "run importer filter failed: " + t);
        }
    }

    @Override
    public boolean beginImport(File f, EnumSet<SortFlags> flags) {
        try {
            final JSONObject saved = RunFile.readSaved(f);
            if (saved == null)
                return false;
            Log.d(TAG, "run file arrived: " + f.getName());
            model.importRun(saved, f);
            fileSorted.set(Boolean.TRUE);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "run import failed: " + t);
            return false;
        }
    }

    /**
     * In place: the run is read where ATAK put the file, and ATAK checks that the file
     * it records for the package exists. Left to the default, the record named a file in
     * {@code tools/firecast/runs} that was never written, the package reported "Failed
     * to extract", and its delete looked for the run under that path (dev-2, 2026-10-17).
     */
    @Override
    public File getDestinationPath(File f) {
        return f;
    }

    @Override
    public Pair<String, String> getContentMIME() {
        return new Pair<>("FireCast run", "application/vnd.google-earth.kmz");
    }
}

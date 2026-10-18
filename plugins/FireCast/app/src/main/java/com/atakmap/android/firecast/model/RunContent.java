package com.atakmap.android.firecast.model;

import android.graphics.drawable.Drawable;

import com.atakmap.android.data.FileContentHandler;
import com.atakmap.android.data.URIContentHandler;
import com.atakmap.android.data.URIContentResolver;

import java.io.File;

/**
 * A received run as ATAK content, so the data package it came in owns it: delete the
 * package and the run goes, the package's eye hides and shows it. ATAK asks this
 * resolver for a handler whenever it needs one for a file (the package's delete asks
 * for each file it holds); a received run's file is the one the package extracted.
 * Asked on demand and unregistered when Fire Cast stops, so a plugin reload leaves no
 * handler pointing at an old model.
 */
final class RunContent implements URIContentResolver {

    static final String CONTENT_TYPE = "Fire Cast run";
    private final FireModel model;

    RunContent(FireModel model) {
        this.model = model;
    }

    @Override
    public URIContentHandler getHandler(String tool, String uri) {
        try {
            if (uri == null)
                return null;
            final String path = uri.startsWith("file://") ? uri.substring(7) : uri;
            if (!path.endsWith(RunFile.SUFFIX))
                return null;
            final FireModel.Run r = model.receivedFrom(new File(path));
            return r == null ? null : new Handler(new File(path), r.runId);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * GoTo is a tap on the run in the package's contents: fly there and open its page.
     * Visibility is the package's eye: the run's own ON/OFF. Without them ATAK's list
     * showed the run and did nothing when it was tapped (dev-2, 2026-10-17).
     */
    private final class Handler extends FileContentHandler
            implements com.atakmap.android.hierarchy.action.GoTo, com.atakmap.android.hierarchy.action.Visibility {
        private final String runId;

        Handler(File file, String runId) {
            super(file);
            this.runId = runId;
        }

        @Override
        public String getContentType() {
            return CONTENT_TYPE;
        }

        @Override
        public String getMIMEType() {
            return "application/vnd.google-earth.kmz";
        }

        @Override
        public Drawable getIcon() {
            return model.icon();
        }

        @Override
        public String getTitle() {
            final FireModel.Run r = model.find(runId);
            return r == null ? CONTENT_TYPE : r.label();
        }

        @Override
        public boolean goTo(boolean select) {
            return model.open(runId);
        }

        @Override
        public void importContent() {
            // Imported already, by the resolver, when the package arrived.
        }

        @Override
        public void deleteContent() {
            model.deleteReceived(runId);
        }

        @Override
        protected boolean setVisibleImpl(boolean visible) {
            return model.setVisible(runId, visible);
        }

        @Override
        public boolean isVisible() {
            final FireModel.Run r = model.find(runId);
            return r != null && r.visible;
        }
    }
}

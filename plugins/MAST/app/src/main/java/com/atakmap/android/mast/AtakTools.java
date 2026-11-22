package com.atakmap.android.mast;

import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.util.Xml;

import com.atakmap.coremap.log.Log;

import org.xmlpull.v1.XmlPullParser;

import java.io.InputStream;

/**
 * One of ATAK's own tools (Range Tools, Routes, Red X ...) as a MAST tile. ATAK
 * defines each item of its tool menu in {@code assets/actionbar/items/<file>.xml}
 * inside atak.apk: an icon name, a title string name and the broadcast a tap sends.
 * MAST reads that file from the running ATAK and sends the same broadcast, so a tile
 * does exactly what the menu item does, on every ATAK version, with ATAK's own name
 * and icon. Nothing here is copied from ATAK; it is read from it at run time.
 */
public final class AtakTools {

    private static final String TAG = "MAST.Tools";
    private static final String DIR = "actionbar/items/";

    /** One tool, ready to draw and to tap. */
    public static final class Tool {
        public final String file;
        public final String title;
        public final Drawable icon;
        private final String action;
        private final android.os.Bundle extras;

        Tool(String file, String title, Drawable icon, String action,
                android.os.Bundle extras) {
            this.file = file;
            this.title = title;
            this.icon = icon;
            this.action = action;
            this.extras = extras;
        }

        /** The broadcast ATAK's own menu item sends on a tap. */
        public Intent intent() {
            final Intent i = new Intent(action);
            if (!extras.isEmpty())
                i.putExtras(extras);
            return i;
        }
    }

    private AtakTools() {
    }

    /**
     * Reads one item file from the host ATAK. Returns null when this ATAK has no such
     * item or it has no click broadcast; the tile is then left out, not drawn dead.
     */
    public static Tool load(Context host, String file) {
        String iconName = null, titleName = null, action = null;
        final android.os.Bundle extras = new android.os.Bundle();
        try (InputStream in = host.getAssets().open(DIR + file)) {
            final XmlPullParser x = Xml.newPullParser();
            x.setInput(in, "UTF-8");
            boolean inClick = false, done = false;
            String tag = null;
            for (int ev = x.getEventType(); ev != XmlPullParser.END_DOCUMENT && !done;
                    ev = x.next()) {
                if (ev == XmlPullParser.START_TAG) {
                    tag = x.getName();
                    if ("PersistedActionMenu".equals(tag)) {
                        iconName = x.getAttributeValue(null, "iconPath");
                        titleName = x.getAttributeValue(null, "title");
                    } else if ("ClickData".equals(tag)) {
                        final String type = x.getAttributeValue(null, "actionType");
                        inClick = type == null || "click".equals(type);
                    } else if (inClick && "string".equals(tag)) {
                        final String key = x.getAttributeValue(null, "key");
                        final String val = x.nextText();
                        if (key != null)
                            extras.putString(key, val);
                        tag = null;
                    } else if (inClick && "action".equals(tag) && action == null) {
                        action = x.nextText().trim();
                        tag = null;
                    } else if (inClick && !"broadcast".equals(tag)
                            && !"extras".equals(tag)) {
                        Log.w(TAG, file + ": extra type <" + tag + "> not passed on");
                    }
                } else if (ev == XmlPullParser.END_TAG && "ClickData".equals(x.getName())) {
                    if (inClick && action != null)
                        done = true;
                    inClick = false;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "ATAK has no tool " + file, e);
            return null;
        }
        if (action == null || action.isEmpty()) {
            Log.w(TAG, file + ": no click broadcast");
            return null;
        }
        final Resources r = host.getResources();
        final String pkg = host.getPackageName();
        String title = file.replace(".xml", "");
        if (titleName != null) {
            final int id = r.getIdentifier(titleName, "string", pkg);
            if (id != 0)
                title = r.getString(id);
        }
        Drawable icon = null;
        if (iconName != null) {
            final int id = r.getIdentifier(iconName, "drawable", pkg);
            if (id != 0) {
                try {
                    icon = host.getDrawable(id);
                    if (icon != null)
                        icon = icon.mutate();
                } catch (Resources.NotFoundException e) {
                    icon = null;
                }
            }
        }
        return new Tool(file, title, icon, action, extras);
    }
}

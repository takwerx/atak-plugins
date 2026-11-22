package com.atakmap.android.iwi.plugin;


import android.content.SharedPreferences;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;

/**
 * One drop-down row: a head naming the thing and its value, an arrow, a body.
 *
 * <p>The takwerx pane standard (plugin-ui skill). The head and the arrow both open and
 * close it, and open or closed is remembered per row, so the pane comes back the way
 * the operator left it.
 */
final class Fold {

    final Button head;
    final ImageButton chevron;
    final View body;
    private final SharedPreferences prefs;
    private final String pref;
    private boolean open;

    Fold(View page, int headId, int chevronId, int bodyId, SharedPreferences prefs,
            String pref) {
        this((Button) page.findViewById(headId), (ImageButton) page.findViewById(chevronId),
                page.findViewById(bodyId), prefs, pref);
    }

    /** For a row built in code. */
    Fold(Button head, ImageButton chevron, View body, SharedPreferences prefs, String pref) {
        this.head = head;
        this.chevron = chevron;
        this.body = body;
        this.prefs = prefs;
        this.pref = pref;
        this.open = prefs.getBoolean(pref, false);
        final View.OnClickListener flip = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                open = !open;
                Fold.this.prefs.edit().putBoolean(Fold.this.pref, open).apply();
                show();
            }
        };
        head.setOnClickListener(flip);
        chevron.setOnClickListener(flip);
        show();
    }

    void label(String name, String value) {
        head.setText(value == null ? name : name + ": " + value);
    }

    /** Green text once the row's section is filled in, white until then. */
    void complete(boolean done, int green) {
        head.setTextColor(done ? green : android.graphics.Color.WHITE);
    }

    boolean isOpen() {
        return open;
    }

    /** Open or close it from code, remembered like a tap. */
    void setOpen(boolean on) {
        open = on;
        prefs.edit().putBoolean(pref, open).apply();
        show();
    }

    private void show() {
        chevron.setRotation(open ? 180f : 0f);
        body.setVisibility(open ? View.VISIBLE : View.GONE);
    }
}

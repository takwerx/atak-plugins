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
        this.head = page.findViewById(headId);
        this.chevron = page.findViewById(chevronId);
        this.body = page.findViewById(bodyId);
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

    private void show() {
        chevron.setRotation(open ? 180f : 0f);
        body.setVisibility(open ? View.VISIBLE : View.GONE);
    }
}

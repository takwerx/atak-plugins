package com.atakmap.android.atmosphere.ui;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;

import com.atakmap.android.atmosphere.plugin.R;

/**
 * A page's filter tiles folded behind one button that names the filter in force,
 * so the list starts higher (operator, 2026-10-23, on the Buoys page's two rows of
 * tiles: "that should all be like a filter setting that expands so i can have more
 * of a list"). The button sits on the heading's line beside the Map switch; a tap
 * opens the tiles beneath, a pick closes them. Shared by the Stations, River Gauges
 * and Buoys pages, the Storm Reports page's Where button done once.
 */
final class FilterFold {
    final Button head;
    private final View body;
    private boolean open;
    private boolean listShown = true;

    FilterFold(Context pluginContext, View body) {
        this.body = body;
        head = (Button) LayoutInflater.from(pluginContext).inflate(R.layout.trend_chip, null, false);
        head.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                open = !open;
                paint();
            }
        });
        paint();
    }

    /** The filter in force, as its tile reads, in its color when it narrows the list. */
    void label(String words, int color, boolean narrows) {
        head.setText(words + " ▾");
        head.setTextColor(narrows && color != 0 ? color : Color.WHITE);
    }

    /** A tile was picked: the tiles fold away again. */
    void close() {
        open = false;
        paint();
    }

    /** The list is on screen (true) or one item's details replace it (false). */
    void listShown(boolean shown) {
        listShown = shown;
        paint();
    }

    private void paint() {
        head.setVisibility(listShown ? View.VISIBLE : View.INVISIBLE);
        body.setVisibility(listShown && open ? View.VISIBLE : View.GONE);
    }
}

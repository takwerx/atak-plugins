// SPDX-License-Identifier: AGPL-3.0-or-later
// Atmosphere — an ATAK plugin
// Copyright (C) 2026 Andreas Johansson (TAKWERX)
//
// This program is free software: you can redistribute it and/or modify it under
// the terms of the GNU Affero General Public License as published by the Free
// Software Foundation, either version 3 of the License, or (at your option) any
// later version, with the additional permission in LICENSE-EXCEPTION.md.
//
// This program is distributed in the hope that it will be useful, but WITHOUT ANY
// WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
// PARTICULAR PURPOSE. See the GNU Affero General Public License for more details.
package com.atakmap.android.atmosphere.rain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Where to write the temperature on the map. The picture is bands of one color
 * each; a legend in the pane says what the colors mean, but a crew reads the map,
 * not the pane (operator, 2026-11-12: "it should be on the map"). So each patch
 * of one band in view gets its band written on it, in the band's own color, and
 * the legend is the map.
 *
 * <p>Pure arithmetic over the grid, no Android types. The view is cut into a
 * lattice of cells a label wide; each cell takes the band at its middle; cells of
 * one band that touch are one patch. A patch is labelled at its most interior
 * cell, so the text sits in the body of the patch and away from the screen's
 * edge, and a patch big enough to hold several labels gets them spaced out, so a
 * single band across the whole view is still named wherever the eye lands. The
 * largest patch of every band in view is always labelled; smaller patches of a
 * band already named are labelled only when they are big enough to carry it,
 * so slivers do not become clutter.
 */
public final class BandLabels {

    /** Cells between labels in one patch, Chebyshev. */
    static final int SPACING = 5;
    /** The smallest patch that gets a label when its band is already named elsewhere. */
    static final int MIN_CELLS = 4;
    private static final int NONE = Integer.MIN_VALUE;

    public static final class Label {
        public final double lat, lon;
        /** Lower edge of the band, in the unit shown. */
        public final int floor;

        Label(double lat, double lon, int floor) {
            this.lat = lat;
            this.lon = lon;
            this.floor = floor;
        }
    }

    private BandLabels() {
    }

    /** "65–70 °F": the band, its upper edge exclusive, in the unit shown. */
    public static String text(int floor, boolean fahrenheit) {
        return String.format(Locale.US, "%d\u2013%d %s", floor, floor + NomadsTemperature.BAND_DEGREES,
                fahrenheit ? "\u00b0F" : "\u00b0C");
    }

    /**
     * @param cols,rows the lattice over the view, each cell about a label wide
     */
    public static List<Label> place(RainGrid grid, double west, double south, double east,
            double north, int cols, int rows, boolean fahrenheit) {
        final List<Label> out = new ArrayList<>();
        if (grid == null || cols < 1 || rows < 1 || east <= west || north <= south)
            return out;
        final double dLon = (east - west) / cols, dLat = (north - south) / rows;
        final int n = cols * rows;
        final int[] band = new int[n];
        for (int j = 0; j < rows; j++) {
            final double lat = north - (j + 0.5) * dLat;
            for (int i = 0; i < cols; i++) {
                final float floor = NomadsTemperature.bandFloor(
                        grid.sample(lat, west + (i + 0.5) * dLon), fahrenheit);
                band[j * cols + i] = Float.isNaN(floor) ? NONE : Math.round(floor);
            }
        }

        // Patches: 4-connected cells of one band.
        final int[] patch = new int[n];
        java.util.Arrays.fill(patch, -1);
        final List<List<Integer>> patches = new ArrayList<>();
        final ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int start = 0; start < n; start++) {
            if (band[start] == NONE || patch[start] >= 0)
                continue;
            final int id = patches.size();
            final List<Integer> cells = new ArrayList<>();
            patch[start] = id;
            queue.add(start);
            while (!queue.isEmpty()) {
                final int c = queue.poll();
                cells.add(c);
                final int x = c % cols, y = c / cols;
                final int[] around = { x > 0 ? c - 1 : -1, x < cols - 1 ? c + 1 : -1,
                        y > 0 ? c - cols : -1, y < rows - 1 ? c + cols : -1 };
                for (int a : around) {
                    if (a >= 0 && patch[a] < 0 && band[a] == band[c]) {
                        patch[a] = id;
                        queue.add(a);
                    }
                }
            }
            patches.add(cells);
        }

        // Interior distance: how many cells to the nearest cell of another band or
        // the edge of the view. Multi-source breadth-first from the outside in.
        final int[] depth = new int[n];
        for (int c = 0; c < n; c++) {
            if (band[c] == NONE)
                continue;
            final int x = c % cols, y = c / cols;
            final boolean edge = x == 0 || y == 0 || x == cols - 1 || y == rows - 1
                    || band[c - 1] != band[c] || band[c + 1] != band[c]
                    || band[c - cols] != band[c] || band[c + cols] != band[c];
            if (edge) {
                depth[c] = 1;
                queue.add(c);
            }
        }
        while (!queue.isEmpty()) {
            final int c = queue.poll();
            final int x = c % cols, y = c / cols;
            final int[] around = { x > 0 ? c - 1 : -1, x < cols - 1 ? c + 1 : -1,
                    y > 0 ? c - cols : -1, y < rows - 1 ? c + cols : -1 };
            for (int a : around) {
                if (a >= 0 && band[a] == band[c] && depth[a] == 0) {
                    depth[a] = depth[c] + 1;
                    queue.add(a);
                }
            }
        }

        // Largest patch first, so the first patch of each band is the one that is
        // always labelled.
        final List<Integer> order = new ArrayList<>();
        for (int i = 0; i < patches.size(); i++)
            order.add(i);
        Collections.sort(order, new Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                return patches.get(b).size() - patches.get(a).size();
            }
        });
        final java.util.Set<Integer> named = new java.util.HashSet<>();
        for (int id : order) {
            final List<Integer> cells = patches.get(id);
            final int b = band[cells.get(0)];
            if (cells.size() < MIN_CELLS && !named.add(b))
                continue;
            named.add(b);
            // Deepest first; ties toward the patch's middle.
            double sx = 0, sy = 0;
            for (int c : cells) {
                sx += c % cols;
                sy += c / cols;
            }
            final double cx = sx / cells.size(), cy = sy / cells.size();
            Collections.sort(cells, new Comparator<Integer>() {
                @Override
                public int compare(Integer p, Integer q) {
                    if (depth[p] != depth[q])
                        return depth[q] - depth[p];
                    return Double.compare(Math.hypot(p % cols - cx, p / cols - cy),
                            Math.hypot(q % cols - cx, q / cols - cy));
                }
            });
            final List<Integer> picked = new ArrayList<>();
            for (int c : cells) {
                boolean clear = true;
                for (int p : picked) {
                    if (Math.max(Math.abs(c % cols - p % cols), Math.abs(c / cols - p / cols)) < SPACING) {
                        clear = false;
                        break;
                    }
                }
                if (!clear)
                    continue;
                picked.add(c);
                out.add(new Label(north - (c / cols + 0.5) * dLat, west + (c % cols + 0.5) * dLon, b));
            }
        }
        return out;
    }
}

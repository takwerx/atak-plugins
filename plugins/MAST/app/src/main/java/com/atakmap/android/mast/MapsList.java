package com.atakmap.android.mast;

import android.content.Context;
import android.graphics.Color;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.atakmap.android.hierarchy.HierarchyListFilter;
import com.atakmap.android.hierarchy.HierarchyListItem;
import com.atakmap.android.hierarchy.action.GoTo;
import com.atakmap.android.hierarchy.action.Visibility;
import com.atakmap.android.hierarchy.filters.FOVFilter;
import com.atakmap.android.hierarchy.items.AbstractHierarchyListItem2;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.mast.plugin.R;
import com.atakmap.android.overlay.MapOverlay;
import com.atakmap.android.overlay.MapOverlay2;
import com.atakmap.coremap.log.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * MAST's own list of the image overlays (GRGs, the school floor plans): every row is
 * one of Overlay Manager's own rows for the Image Overlay list, so the eye toggle
 * hides and shows exactly as it does there and Go zooms the same way, but the operator
 * never leaves the pane (operator, 2026-09-19: "is there a way to have like our own
 * list of these? there is no back to the plugin function now").
 *
 * <p>In-view mode applies ATAK's own field-of-view filter; All mode lists every plan
 * with a filter box for finding a building by name. The list model is Overlay
 * Manager's ({@code MapOverlay.getListModel}), refreshed asynchronously; rows are
 * rebuilt when it reports back through the adapter.
 */
public class MapsList {

    private static final String TAG = "MAST.Maps";
    private static final String IMAGE_OVERLAY = "Image Overlay";

    public interface Host {
        void onBack();
    }

    private final MapView mapView;
    private final Context plugin;
    private final Context host;
    private final View root;
    private final Button backBtn;
    private final Button scopeBtn;
    private final EditText search;
    private final TextView empty;
    private final LinearLayout rows;
    private final Host owner;
    private boolean inView = true;
    private HierarchyListItem model;
    private String filterText = "";

    /** Overlay Manager's list model reports its refresh here; the rows are rebuilt then. */
    private final BaseAdapter adapter = new BaseAdapter() {
        @Override
        public int getCount() {
            return 0;
        }

        @Override
        public Object getItem(int position) {
            return null;
        }

        @Override
        public long getItemId(int position) {
            return 0;
        }

        @Override
        public View getView(int position, View convertView, android.view.ViewGroup parent) {
            return null;
        }

        @Override
        public void notifyDataSetChanged() {
            super.notifyDataSetChanged();
            mapView.post(new Runnable() {
                @Override
                public void run() {
                    rebuild();
                }
            });
        }
    };

    public MapsList(MapView mapView, Context pluginContext, View root, Host owner) {
        this.mapView = mapView;
        this.plugin = pluginContext;
        this.host = mapView.getContext();
        this.root = root;
        this.owner = owner;
        backBtn = root.findViewById(R.id.mast_maps_back);
        scopeBtn = root.findViewById(R.id.mast_maps_scope);
        search = root.findViewById(R.id.mast_maps_search);
        empty = root.findViewById(R.id.mast_maps_empty);
        rows = root.findViewById(R.id.mast_maps_rows);
        backBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                owner.onBack();
            }
        });
        scopeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                inView = !inView;
                refresh();
            }
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                filterText = s.toString().trim().toLowerCase();
                rebuild();
            }
        });
    }

    /** Show the list: in-view only, or everything with the name box. */
    public void open(boolean inViewOnly, boolean withSearch) {
        inView = inViewOnly;
        search.setVisibility(withSearch ? View.VISIBLE : View.GONE);
        if (!withSearch) {
            search.setText("");
            filterText = "";
        }
        refresh();
        if (withSearch)
            search.requestFocus();
    }

    public void close() {
        rows.removeAllViews();
        model = null;
    }

    private void refresh() {
        scopeBtn.setText(inView ? R.string.maps_in_view : R.string.maps_all);
        MapOverlay overlay = mapView.getMapOverlayManager().getOverlay(IMAGE_OVERLAY);
        if (overlay == null) {
            model = null;
            rows.removeAllViews();
            empty.setText(R.string.maps_no_overlay);
            empty.setVisibility(View.VISIBLE);
            return;
        }
        HierarchyListFilter filter = inView ? new FOVFilter(mapView)
                : new HierarchyListFilter(new HierarchyListItem.SortAlphabet());
        long actions = com.atakmap.android.hierarchy.action.Actions.ACTION_VISIBILITY
                | com.atakmap.android.hierarchy.action.Actions.ACTION_GOTO;
        if (overlay instanceof MapOverlay2)
            model = ((MapOverlay2) overlay).getListModel(adapter, actions, filter);
        else
            model = overlay.getListModel(adapter, actions, filter.sort);
        // The model refreshes on its own thread and calls the adapter back; a model
        // that is already current shows whatever it has right away.
        rebuild();
    }

    private void rebuild() {
        rows.removeAllViews();
        if (model == null)
            return;
        List<HierarchyListItem> items = new ArrayList<HierarchyListItem>();
        if (model instanceof AbstractHierarchyListItem2)
            items.addAll(((AbstractHierarchyListItem2) model).getChildren());
        else
            for (int i = 0; i < model.getChildCount(); i++)
                items.add(model.getChildAt(i));
        final float dp = plugin.getResources().getDisplayMetrics().density;
        int shown = 0;
        for (final HierarchyListItem item : items) {
            String title = item.getTitle();
            if (title == null)
                continue;
            if (filterText.length() > 0 && !title.toLowerCase().contains(filterText))
                continue;
            shown++;
            LinearLayout row = new LinearLayout(plugin);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setBaselineAligned(false);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = (int) (6 * dp);
            rows.addView(row, rlp);

            final Button eye = new Button(plugin, null, 0, R.style.TakwerxButton);
            LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                    (int) (92 * dp), LinearLayout.LayoutParams.WRAP_CONTENT);
            elp.setMarginEnd((int) (6 * dp));
            eye.setLayoutParams(elp);
            final Visibility vis = item instanceof Visibility ? (Visibility) item : null;
            paintEye(eye, vis);
            eye.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (vis == null)
                        return;
                    vis.setVisible(!vis.isVisible());
                    paintEye(eye, vis);
                }
            });
            row.addView(eye);

            TextView name = new TextView(plugin);
            name.setText(displayName(title));
            name.setTextColor(Color.WHITE);
            name.setTextSize(15);
            name.setMaxLines(2);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            nlp.setMarginEnd((int) (6 * dp));
            name.setLayoutParams(nlp);
            row.addView(name);

            Button go = new Button(plugin, null, 0, R.style.TakwerxButton);
            LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                    (int) (64 * dp), LinearLayout.LayoutParams.WRAP_CONTENT);
            go.setLayoutParams(glp);
            go.setText(R.string.maps_go);
            go.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (item instanceof GoTo) {
                        if (vis != null && !vis.isVisible()) {
                            vis.setVisible(true);
                            paintEye(eye, vis);
                        }
                        ((GoTo) item).goTo(false);
                    }
                }
            });
            row.addView(go);
        }
        Log.d(TAG, "maps list: " + items.size() + " items, " + shown + " shown, inView=" + inView
                + (filterText.length() > 0 ? " filter='" + filterText + "'" : ""));
        if (shown == 0) {
            empty.setText(inView ? R.string.maps_none_in_view : R.string.maps_none);
            empty.setVisibility(View.VISIBLE);
        } else {
            empty.setVisibility(View.GONE);
        }
    }

    /** Toggles look like Traffic's: ON in green text, OFF in red, on a plain button. */
    private void paintEye(Button eye, Visibility vis) {
        boolean on = vis != null && vis.isVisible();
        eye.setText(on ? R.string.maps_shown : R.string.maps_hidden);
        eye.setTextColor(on ? Color.rgb(0x66, 0xdd, 0x66) : Color.rgb(0xee, 0x66, 0x66));
        eye.setEnabled(vis != null);
    }

    /** "Auburndale F1.tif" reads as "Auburndale F1". */
    private static String displayName(String title) {
        int dot = title.lastIndexOf('.');
        if (dot > 0 && title.length() - dot <= 5)
            return title.substring(0, dot);
        return title;
    }

    private void toast(String s) {
        Toast.makeText(host, s, Toast.LENGTH_SHORT).show();
    }
}

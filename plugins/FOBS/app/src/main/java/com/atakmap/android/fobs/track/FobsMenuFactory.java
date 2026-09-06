package com.atakmap.android.fobs.track;

import android.content.Context;

import com.atakmap.android.maps.MapDataRef;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.MultiPolyline;
import com.atakmap.android.maps.assets.MapAssets;
import com.atakmap.android.menu.MapMenuButtonWidget;
import com.atakmap.android.menu.MapMenuFactory;
import com.atakmap.android.menu.MapMenuReceiver;
import com.atakmap.android.menu.MapMenuWidget;
import com.atakmap.android.menu.MenuMapAdapter;
import com.atakmap.android.menu.MenuResourceFactory;
import com.atakmap.android.menu.PluginMenuParser;
import com.atakmap.android.track.maps.TrackPolyline;
import com.atakmap.android.widgets.MapWidget;
import com.atakmap.android.widgets.WidgetIcon;
import com.atakmap.coremap.log.Log;

import java.io.IOException;

import gov.tak.api.widgets.IMapMenuButtonWidget;

/**
 * Adds FOBS buttons to radial menus: a boot print on a Track History line (import it
 * as a FOBS shape) and scissors on a FOBS track (cut it). Track History's export list
 * is a fixed array inside ATAK and cannot take a plugin entry; the radial menu can.
 *
 * <p>Every other item gets ATAK's default menu untouched: the factory returns null
 * for anything that is not a track line, which tells ATAK to carry on as usual.
 */
public class FobsMenuFactory implements MapMenuFactory {

    private static final String TAG = "FOBS.MenuFactory";

    private final Context plugin;
    private final MenuResourceFactory defaults;
    private final ImportTrack importTrack;

    public FobsMenuFactory(MapView mapView, Context pluginContext, ImportTrack importTrack) {
        this.plugin = pluginContext;
        this.importTrack = importTrack;
        Context host = mapView.getContext();
        MapAssets assets = new MapAssets(host);
        MenuMapAdapter adapter = new MenuMapAdapter();
        try {
            adapter.loadMenuFilters(assets, "filters/menu_filters.xml");
        } catch (IOException e) {
            Log.w(TAG, "menu filters not loaded", e);
        }
        defaults = new MenuResourceFactory(mapView, mapView.getMapData(), assets, adapter);
    }

    @Override
    public MapMenuWidget create(MapItem item) {
        if (item instanceof TrackPolyline)
            return withButtons(item, icon("icons/fobs_radial.png"),
                    new IMapMenuButtonWidget.OnButtonClickHandler() {
                        @Override
                        public boolean isSupported(Object o) {
                            return o instanceof TrackPolyline;
                        }

                        @Override
                        public void performAction(Object o) {
                            MapMenuReceiver.getInstance().hideMenu();
                            if (o instanceof TrackPolyline)
                                importTrack.importVisible((TrackPolyline) o);
                        }
                    }, icon("icons/fobs_cut_radial.png"), CUT);
        // Scissors on every line and closed shape: FOBS tracks and areas, freeform
        // lines and polygons, routes, strokes, rectangles, circles.
        if (CutTrackTool.cuttable(item) && !(item instanceof MultiPolyline))
            return withButtons(item, icon("icons/fobs_cut_radial.png"), CUT, null, null);
        return null;
    }

    /** Starts Cut a track locked to the item the menu was opened on. */
    private static final IMapMenuButtonWidget.OnButtonClickHandler CUT =
            new IMapMenuButtonWidget.OnButtonClickHandler() {
                @Override
                public boolean isSupported(Object o) {
                    return o instanceof MapItem;
                }

                @Override
                public void performAction(Object o) {
                    MapMenuReceiver.getInstance().hideMenu();
                    if (!(o instanceof MapItem))
                        return;
                    android.os.Bundle b = new android.os.Bundle();
                    b.putString(CutTrackTool.EXTRA_UID, ((MapItem) o).getUID());
                    com.atakmap.android.toolbar.ToolManagerBroadcastReceiver
                            .getInstance().startTool(CutTrackTool.ID, b);
                }
            };

    /** ATAK's default menu for the item, plus one or two buttons sized like the others. */
    private MapMenuWidget withButtons(MapItem item, WidgetIcon icon,
            IMapMenuButtonWidget.OnButtonClickHandler handler, WidgetIcon icon2,
            IMapMenuButtonWidget.OnButtonClickHandler handler2) {
        MapMenuWidget menu = defaults.create(item);
        if (menu == null)
            return null;

        // Copy geometry from a button already on the ring. The layout normalizes the
        // slice angles itself, but each button keeps its own radius, width and
        // background; a button without them sits off the ring (XCover, 2026-09-05).
        MapMenuButtonWidget template = null;
        for (MapWidget child : menu.getChildWidgets()) {
            if (child instanceof MapMenuButtonWidget) {
                template = (MapMenuButtonWidget) child;
                break;
            }
        }
        addButton(menu, icon, handler, template);
        if (icon2 != null && handler2 != null)
            addButton(menu, icon2, handler2, template);
        return menu;
    }

    private void addButton(MapMenuWidget menu, WidgetIcon icon,
            IMapMenuButtonWidget.OnButtonClickHandler handler, MapMenuButtonWidget template) {
        MapMenuButtonWidget button = new MapMenuButtonWidget(MapView.getMapView().getContext());
        button.setIcon(icon);
        button.setOnButtonClickHandler(handler);
        if (template != null) {
            button.setOrientation(template.getOrientationAngle(), template.getOrientationRadius());
            button.setButtonSize(template.getButtonSpan(), template.getButtonWidth());
            button.setLayoutWeight(template.getLayoutWeight());
            if (template.getWidgetBackground() != null)
                button.setWidgetBackground(template.getWidgetBackground());
        }
        menu.addWidget(button);
    }

    private WidgetIcon icon(String asset) {
        String uri = PluginMenuParser.getItem(plugin, asset);
        if (uri.length() == 0)
            uri = "asset:///icons/details.png";
        return new WidgetIcon.Builder()
                .setImageRef(0, MapDataRef.parseUri(uri))
                // ATAK's own radial icons are 32 px; the boot print reads small at
                // that size, so it gets 48 (operator, 2026-09-05).
                .setAnchor(24, 24)
                .setSize(48, 48)
                .build();
    }
}

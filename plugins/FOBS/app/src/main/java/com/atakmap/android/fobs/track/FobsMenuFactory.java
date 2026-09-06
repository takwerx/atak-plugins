package com.atakmap.android.fobs.track;

import android.content.Context;

import com.atakmap.android.maps.MapDataRef;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
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
 * Adds a FOBS button to the radial menu of a Track History line, so a track the
 * operator has turned on in ATAK's own Track History becomes a FOBS shape from the
 * map: tap the line, tap the boot print. Track History's export list is a fixed
 * array inside ATAK and cannot take a plugin entry; the radial menu can.
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
        if (!(item instanceof TrackPolyline))
            return null;
        MapMenuWidget menu = defaults.create(item);
        if (menu == null)
            return null;

        // Same size as the buttons already on the menu.
        float span = 0, width = 0;
        int count = 0;
        for (MapWidget child : menu.getChildWidgets()) {
            if (child instanceof MapMenuButtonWidget) {
                MapMenuButtonWidget b = (MapMenuButtonWidget) child;
                span += b.getButtonSpan();
                width += b.getButtonWidth();
                count++;
            }
        }
        MapMenuButtonWidget button = new MapMenuButtonWidget(item.getGroup() == null
                ? plugin : MapView.getMapView().getContext());
        button.setIcon(icon());
        button.setOnButtonClickHandler(new IMapMenuButtonWidget.OnButtonClickHandler() {
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
        });
        if (count > 0) {
            button.setLayoutWeight(span / count);
            button.setButtonSize(span / count, width / count);
        }
        menu.addWidget(button);
        return menu;
    }

    private WidgetIcon icon() {
        String uri = PluginMenuParser.getItem(plugin, "icons/fobs_radial.png");
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

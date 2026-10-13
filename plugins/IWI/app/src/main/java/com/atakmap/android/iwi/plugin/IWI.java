
package com.atakmap.android.iwi.plugin;

import android.content.Context;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.atakmap.android.cot.detail.CotDetailManager;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;

import com.atak.plugins.impl.PluginContextProvider;
import com.atak.plugins.impl.PluginLayoutInflater;

import gov.tak.api.plugin.IPlugin;
import gov.tak.api.plugin.IServiceController;
import gov.tak.api.ui.IHostUIService;
import gov.tak.api.ui.Pane;
import gov.tak.api.ui.PaneBuilder;
import gov.tak.api.ui.ToolbarItem;
import gov.tak.api.ui.ToolbarItemAdapter;
import gov.tak.platform.marshal.MarshalManager;

public class IWI implements IPlugin {

    IServiceController serviceController;
    Context pluginContext;
    IHostUIService uiService;
    ToolbarItem toolbarItem;
    Pane templatePane;
    IwiDetailHandler iwiDetailHandler;

    public IWI(IServiceController serviceController) {
        this.serviceController = serviceController;
        final PluginContextProvider ctxProvider = serviceController
                .getService(PluginContextProvider.class);
        if (ctxProvider != null) {
            pluginContext = ctxProvider.getPluginContext();
            pluginContext.setTheme(R.style.ATAKPluginTheme);
        }

        // obtain the UI service
        uiService = serviceController.getService(IHostUIService.class);

        // initialize the toolbar button for the plugin

        // create the button and set the identifier to be well known
        // if you fail to do this, the toolbar configuration will never
        // be able to find it again after the user moves the icon.
        toolbarItem = new ToolbarItem.Builder(
                pluginContext.getString(R.string.app_name),
                MarshalManager.marshal(
                        pluginContext.getResources().getDrawable(R.drawable.ic_toolbar),
                        android.graphics.drawable.Drawable.class,
                        gov.tak.api.commons.graphics.Bitmap.class))
                .setListener(new ToolbarItemAdapter() {
                    @Override
                    public void onClick(ToolbarItem item) {
                        showPane();
                    }
                }).setIdentifier(pluginContext.getPackageName())
                .build();
    }

    @Override
    public void onStart() {
        // Anything that must outlive a tap lives here, not in a Tool. The
        // detail handler is what makes _iwi_ typed rather than opaque on a
        // phone that has the plugin.
        if (iwiDetailHandler == null)
            iwiDetailHandler = new IwiDetailHandler();
        CotDetailManager.getInstance().registerHandler(iwiDetailHandler);

        if (uiService == null)
            return;

        uiService.addToolbarItem(toolbarItem);
    }

    @Override
    public void onStop() {
        if (iwiDetailHandler != null)
            CotDetailManager.getInstance().unregisterHandler(iwiDetailHandler);

        // the plugin is stopping, remove the button from the toolbar
        if (uiService == null)
            return;

        uiService.removeToolbarItem(toolbarItem);
    }

    private void showPane() {
        // instantiate the plugin view if necessary
        if(templatePane == null) {
            // Remember to use the PluginLayoutInflator if you are actually inflating a custom view
            // In this case, using it is not necessary - but I am putting it here to remind
            // developers to look at this Inflator

            final View v = PluginLayoutInflater.inflate(pluginContext,
                    R.layout.main_layout, null);
            wireSpike(v);

            templatePane = new PaneBuilder(v)
                    // relative location is set to default; pane will switch location dependent on
                    // current orientation of device screen
                    .setMetaValue(Pane.RELATIVE_LOCATION, Pane.Location.Default)
                    // pane will take up 50% of screen width in landscape mode
                    .setMetaValue(Pane.PREFERRED_WIDTH_RATIO, 0.5D)
                    // pane will take up 50% of screen height in portrait mode
                    .setMetaValue(Pane.PREFERRED_HEIGHT_RATIO, 0.5D)
                    .build();
        }

        // if the plugin pane is not visible, show it!
        if(!uiService.isPaneVisible(templatePane)) {
            uiService.showPane(templatePane, null);
        }
    }

    /**
     * Round-trip confirmation wiring (PLAN-IWI-v0.1). Temporary: this pane
     * becomes the Report and Manage faces.
     */
    private void wireSpike(View v) {
        final TextView result = v.findViewById(R.id.spike_result);

        v.findViewById(R.id.btn_inject).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View button) {
                        MapView mv = MapView.getMapView();
                        String uid = RoundTrip.inject(mv);
                        result.setText(uid != null
                                ? "Injected. Open its radial, edit the med line, save, then Dump."
                                : "Inject failed — see logcat tag " + RoundTrip.TAG);
                    }
                });

        v.findViewById(R.id.btn_dump).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View button) {
                        MapView mv = MapView.getMapView();
                        boolean ok = RoundTrip.dump(mv);
                        result.setText(ok
                                ? "Dumped to logcat, tag " + RoundTrip.TAG
                                : "No marker found — inject first.");
                    }
                });
    }
}

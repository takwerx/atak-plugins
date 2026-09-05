package com.atakmap.android.fobs.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.fobs.plugin.R;
import com.atakmap.android.fobs.track.FobsShapes;
import com.atakmap.android.fobs.track.GpsTrackTool;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.toolbar.ToolManagerBroadcastReceiver;

/**
 * The toolbar button's one popup: six verbs as ATAK-style dark buttons, icon over
 * label, in a compact dialog. Not a full-screen list, and not ATAK's TileButtonDialog
 * either: the operator wants every takwerx plugin on the same black TakwerxButton.
 *
 * <p>Every dialog here is built on the MapView's context; the plugin context cannot
 * own a window and takes ATAK down with it. The view is inflated with the plugin
 * context so it finds the plugin's own styles and drawables.
 */
public class Chooser implements View.OnClickListener {

    private final MapView mapView;
    private final Context plugin;
    private final Context host;
    private AlertDialog dialog;

    public Chooser(MapView mapView, Context pluginContext) {
        this.mapView = mapView;
        this.plugin = pluginContext;
        this.host = mapView.getContext();
    }

    public void show() {
        View v = PluginLayoutInflater.inflate(plugin, R.layout.chooser, null);
        int[] ids = { R.id.start_gps, R.id.draw_track, R.id.select_track, R.id.import_track,
                R.id.cut_track, R.id.join_tracks };
        for (int id : ids)
            v.findViewById(id).setOnClickListener(this);
        // No title icon: the template's placeholder glyph is the Android robot and it
        // took a third of the dialog's height on the XCover in landscape.
        dialog = new AlertDialog.Builder(host)
                .setTitle(plugin.getString(R.string.app_name))
                .setView(v)
                .setNegativeButton(plugin.getString(R.string.cancel), null)
                .show();
    }

    @Override
    public void onClick(View v) {
        if (dialog != null) {
            dialog.dismiss();
            dialog = null;
        }
        if (v.getId() == R.id.start_gps)
            nameThenStartGps();
        else
            Toast.makeText(host, plugin.getString(R.string.not_built_yet),
                    Toast.LENGTH_SHORT).show();
    }

    /**
     * Name first, then record. The name is on the track from the first fix, so a
     * teammate watching the mesh sees "Engine 3 - north road" grow, not "Track 1".
     */
    private void nameThenStartGps() {
        String pattern = plugin.getString(R.string.track_default_name);
        String callsign = mapView.getDeviceCallsign();
        int n = FobsShapes.nextTrackNumber(mapView, pattern, callsign);
        final EditText name = new EditText(host);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        name.setSingleLine(true);
        name.setText(String.format(pattern, callsign, n));
        name.setSelection(name.getText().length());

        new AlertDialog.Builder(host)
                .setTitle(plugin.getString(R.string.name_track))
                .setView(name)
                .setPositiveButton(plugin.getString(R.string.start),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                Bundle b = new Bundle();
                                b.putString(GpsTrackTool.EXTRA_TITLE,
                                        name.getText().toString().trim());
                                ToolManagerBroadcastReceiver.getInstance()
                                        .startTool(GpsTrackTool.ID, b);
                            }
                        })
                .setNegativeButton(plugin.getString(R.string.cancel), null)
                .show();
    }
}

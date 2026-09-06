package com.atakmap.android.fobs.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.fobs.feed.FeedPublisher;
import com.atakmap.android.fobs.plugin.R;
import com.atakmap.android.fobs.track.CutTrackTool;
import com.atakmap.android.fobs.track.DrawTrackTool;
import com.atakmap.android.fobs.track.FobsShapes;
import com.atakmap.android.fobs.track.FreehandTrack;
import com.atakmap.android.fobs.track.GpsTrackTool;
import com.atakmap.android.fobs.track.ImportTrack;
import com.atakmap.android.fobs.track.JoinTracksTool;
import com.atakmap.android.fobs.track.LassoJoin;
import com.atakmap.android.fobs.track.SelectTrack;
import com.atakmap.android.fobs.track.StylePrefs;
import com.atakmap.android.gui.ColorPalette;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.toolbar.ToolManagerBroadcastReceiver;

import gov.tak.api.ui.IHostUIService;
import gov.tak.api.ui.Pane;
import gov.tak.api.ui.PaneBuilder;

/**
 * The FOBS side pane: six verbs as ATAK-style dark tiles in a 3 x 2 grid, and the
 * Default Style rows (color, line style, thickness) every new track starts with. An
 * ATAK Pane like CamDepot's, half the screen in landscape, so the tiles have room.
 *
 * <p>Every dialog here is built on the MapView's context; the plugin context cannot
 * own a window and takes ATAK down with it. Views are inflated with the plugin
 * context so they find the plugin's own styles and drawables.
 */
public class FobsPane implements View.OnClickListener {

    private final MapView mapView;
    private final Context plugin;
    private final Context host;
    private final IHostUIService ui;
    private final StylePrefs style;
    private SelectTrack selectTrack;
    private FreehandTrack freehand;
    private ImportTrack importTrack;
    private final View view;
    private final Pane pane;
    private final Button colorBtn;
    private final Button lineBtn;
    private final Button thicknessBtn;

    public FobsPane(MapView mapView, Context pluginContext, IHostUIService ui) {
        this.mapView = mapView;
        this.plugin = pluginContext;
        this.host = mapView.getContext();
        this.ui = ui;
        this.style = new StylePrefs(mapView);

        view = PluginLayoutInflater.inflate(plugin, R.layout.fobs_pane, null);
        int[] ids = { R.id.start_gps, R.id.draw_track, R.id.select_track, R.id.import_track,
                R.id.cut_track, R.id.join_tracks, R.id.style_color, R.id.style_line,
                R.id.style_thickness };
        for (int id : ids)
            view.findViewById(id).setOnClickListener(this);
        colorBtn = view.findViewById(R.id.style_color);
        lineBtn = view.findViewById(R.id.style_line);
        thicknessBtn = view.findViewById(R.id.style_thickness);

        pane = new PaneBuilder(view)
                .setMetaValue(Pane.RELATIVE_LOCATION, Pane.Location.Default)
                .setMetaValue(Pane.PREFERRED_WIDTH_RATIO, 0.5D)
                .setMetaValue(Pane.PREFERRED_HEIGHT_RATIO, 0.5D)
                .build();
    }

    public void setSelectTrack(SelectTrack selectTrack) {
        this.selectTrack = selectTrack;
    }

    public void setFreehand(FreehandTrack freehand) {
        this.freehand = freehand;
    }

    public void setImportTrack(ImportTrack importTrack) {
        this.importTrack = importTrack;
    }

    private LassoJoin lassoJoin;

    public void setLassoJoin(LassoJoin lassoJoin) {
        this.lassoJoin = lassoJoin;
    }

    /** Tap ends, or lasso several tracks and let FOBS chain them. */
    private void askJoinHow() {
        new AlertDialog.Builder(host)
                .setTitle(plugin.getString(R.string.join_how))
                .setPositiveButton(plugin.getString(R.string.join_tap),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                ToolManagerBroadcastReceiver.getInstance()
                                        .startTool(JoinTracksTool.ID, new Bundle());
                            }
                        })
                .setNegativeButton(plugin.getString(R.string.join_lasso),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                if (lassoJoin != null)
                                    lassoJoin.begin();
                            }
                        })
                .setNeutralButton(plugin.getString(R.string.cancel), null)
                .show();
    }

    /** Drop points (tap vertices) or Freehand (ATAK's telestration in FOBS colors). */
    private void askDrawHow() {
        new AlertDialog.Builder(host)
                .setTitle(plugin.getString(R.string.draw_how))
                .setPositiveButton(plugin.getString(R.string.draw_points),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                nameThenStart(DrawTrackTool.ID);
                            }
                        })
                .setNegativeButton(plugin.getString(R.string.draw_freehand),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                askName(new OnName() {
                                    @Override
                                    public void go(String name) {
                                        if (freehand != null)
                                            freehand.begin(name);
                                    }
                                });
                            }
                        })
                .setNeutralButton(plugin.getString(R.string.cancel), null)
                .show();
    }

    /** Toolbar button: open if closed, close if open. */
    public void toggle() {
        if (ui.isPaneVisible(pane)) {
            ui.closePane(pane);
            return;
        }
        refreshStyle();
        ui.showPane(pane, null);
    }

    public void close() {
        if (ui.isPaneVisible(pane))
            ui.closePane(pane);
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.style_color) {
            pickColor();
        } else if (id == R.id.style_line) {
            pickLine();
        } else if (id == R.id.style_thickness) {
            pickThickness();
        } else if (id == R.id.start_gps) {
            close();
            askName(new OnName() {
                @Override
                public void go(final String name) {
                    FeedPublisher feed = FeedPublisher.get();
                    if (feed == null) {
                        startGps(name, null);
                        return;
                    }
                    feed.ask(new FeedPublisher.OnFeed() {
                        @Override
                        public void go(FeedPublisher.Feed f) {
                            startGps(name, f);
                        }
                    });
                }
            });
        } else if (id == R.id.draw_track) {
            close();
            askDrawHow();
        } else if (id == R.id.select_track) {
            close();
            if (selectTrack != null)
                selectTrack.begin();
        } else if (id == R.id.import_track) {
            close();
            if (importTrack != null)
                importTrack.begin();
        } else if (id == R.id.cut_track) {
            close();
            ToolManagerBroadcastReceiver.getInstance().startTool(CutTrackTool.ID, new Bundle());
        } else if (id == R.id.join_tracks) {
            close();
            askJoinHow();
        } else {
            Toast.makeText(host, plugin.getString(R.string.not_built_yet),
                    Toast.LENGTH_SHORT).show();
        }
    }

    // ---- Default Style -----------------------------------------------------------

    private void refreshStyle() {
        GradientDrawable swatch = new GradientDrawable();
        swatch.setShape(GradientDrawable.RECTANGLE);
        swatch.setColor(style.color());
        swatch.setStroke(dp(1), 0xFFFFFFFF);
        swatch.setCornerRadius(dp(3));
        swatch.setSize(dp(22), dp(22));
        colorBtn.setCompoundDrawablesWithIntrinsicBounds(swatch, null, null, null);
        colorBtn.setText(plugin.getString(R.string.style_color));
        lineBtn.setText(lineNames()[StylePrefs.styleIndex(style.lineStyle())]);
        thicknessBtn.setText(thicknessNames()[StylePrefs.weightIndex(style.strokeWeight())]);
    }

    private String[] lineNames() {
        // No "Outlined": ATAK draws it with a dark border around the color, and the
        // operator called the result "black and green ... looks like crap" (2026-09-05).
        return new String[] {
                plugin.getString(R.string.line_solid), plugin.getString(R.string.line_dashed),
                plugin.getString(R.string.line_dotted)
        };
    }

    private String[] thicknessNames() {
        return new String[] {
                plugin.getString(R.string.thick_thin), plugin.getString(R.string.thick_medium),
                plugin.getString(R.string.thick_thick)
        };
    }

    /** ATAK's own palette, so the swatches are the ones the user knows from drawings. */
    private void pickColor() {
        final ColorPalette palette = new ColorPalette(host);
        palette.setShowAlpha(false);
        palette.setShowFill(false);
        palette.setColor(style.color());
        final AlertDialog d = new AlertDialog.Builder(host)
                .setTitle(plugin.getString(R.string.style_color))
                .setView(palette)
                .setNegativeButton(plugin.getString(R.string.cancel), null)
                .create();
        palette.setOnColorSelectedListener(new ColorPalette.OnColorSelectedListener() {
            @Override
            public void onColorSelected(int color, String label) {
                style.setColor(color);
                refreshStyle();
                d.dismiss();
            }
        });
        d.show();
    }

    private void pickLine() {
        new AlertDialog.Builder(host)
                .setTitle(plugin.getString(R.string.style_line))
                .setSingleChoiceItems(lineNames(), StylePrefs.styleIndex(style.lineStyle()),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                style.setLineStyle(StylePrefs.styleFromIndex(which));
                                refreshStyle();
                                d.dismiss();
                            }
                        })
                .setNegativeButton(plugin.getString(R.string.cancel), null)
                .show();
    }

    private void pickThickness() {
        new AlertDialog.Builder(host)
                .setTitle(plugin.getString(R.string.style_thickness))
                .setSingleChoiceItems(thicknessNames(),
                        StylePrefs.weightIndex(style.strokeWeight()),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                style.setStrokeWeight(StylePrefs.weightFromIndex(which));
                                refreshStyle();
                                d.dismiss();
                            }
                        })
                .setNegativeButton(plugin.getString(R.string.cancel), null)
                .show();
    }

    private int dp(int v) {
        return (int) (v * host.getResources().getDisplayMetrics().density + 0.5f);
    }

    // ---- starting a tool ---------------------------------------------------------

    interface OnName {
        void go(String name);
    }

    private void startGps(String name, FeedPublisher.Feed feed) {
        Bundle b = new Bundle();
        b.putString(GpsTrackTool.EXTRA_TITLE, name);
        if (feed != null) {
            b.putString(GpsTrackTool.EXTRA_FEED, feed.name);
            b.putString(GpsTrackTool.EXTRA_FEED_SERVER, feed.server);
        }
        ToolManagerBroadcastReceiver.getInstance().startTool(GpsTrackTool.ID, b);
    }

    /**
     * Name first, then record. The name is on the track from the first fix, so a
     * teammate watching the mesh sees "Engine 3 - north road" grow, not "Track 1".
     */
    private void nameThenStart(final String toolId) {
        askName(new OnName() {
            @Override
            public void go(String name) {
                Bundle b = new Bundle();
                b.putString(GpsTrackTool.EXTRA_TITLE, name);
                ToolManagerBroadcastReceiver.getInstance().startTool(toolId, b);
            }
        });
    }

    /** "<callsign> - Track N", editable, then hand the name on. */
    private void askName(final OnName then) {
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
                                then.go(name.getText().toString().trim());
                            }
                        })
                .setNegativeButton(plugin.getString(R.string.cancel), null)
                .show();
    }
}

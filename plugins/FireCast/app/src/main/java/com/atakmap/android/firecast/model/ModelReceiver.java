package com.atakmap.android.firecast.model;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.dropdown.DropDown.OnStateListener;
import com.atakmap.android.dropdown.DropDownReceiver;
import com.atakmap.android.firecast.Units;
import com.atakmap.android.firecast.plugin.R;
import com.atakmap.android.maps.MapView;

import java.util.Locale;

/**
 * The fire model page: what the run says, what went into it, and what it assumes.
 * Opened from the pane's Fire model button; Back returns to the pane.
 */
public class ModelReceiver extends DropDownReceiver implements OnStateListener, FireModel.Listener {

    public static final String ACTION = "com.atakmap.android.firecast.MODEL";

    private final Context pluginContext;
    private final FireModel model;
    private final Runnable reopenPane;
    private final View view;
    private final LinearLayout body;
    /** The run whose details the page shows; the newest unless one was tapped. */
    private FireModel.Run shown;

    public ModelReceiver(MapView mapView, Context pluginContext, FireModel model, Runnable reopenPane) {
        super(mapView);
        this.pluginContext = pluginContext;
        this.model = model;
        this.reopenPane = reopenPane;
        this.view = PluginLayoutInflater.inflate(pluginContext, R.layout.model_panel, null);
        this.body = view.findViewById(R.id.model_body);
        view.findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeDropDown();
                reopenPane.run();
            }
        });
        view.findViewById(R.id.btn_place).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // The page gives way to the map so the tap lands on it.
                closeDropDown();
                model.pickStart();
            }
        });
        view.findViewById(R.id.btn_run).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (model.perimeter() != null ? !model.runFromPerimeter() : !model.runFromStart())
                    return;
                render();
            }
        });
        view.findViewById(R.id.btn_perimeter).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (model.perimeter() != null) {
                    model.clearPerimeter();
                    render();
                    return;
                }
                closeDropDown();
                model.drawPerimeter();
            }
        });
        view.findViewById(R.id.btn_horizon).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String[] labels = { "1 hour, at 20, 40 and 60 minutes (15 m cells, 5 mi reach)",
                        "3 hours, every half hour (30 m cells, 12 mi reach)" };
                new android.app.AlertDialog.Builder(getMapView().getContext())
                        .setTitle("How far ahead")
                        .setSingleChoiceItems(labels, model.horizonMinutes() >= 180 ? 1 : 0, new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dialog, int which) {
                                dialog.dismiss();
                                model.setHorizonMinutes(which == 1 ? 180 : 60);
                                render();
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        });
        view.findViewById(R.id.btn_started).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final int[] mins = { 0, 10, 20, 30, 45, 60, 90, 120, 180 };
                final String[] labels = new String[mins.length];
                int current = 0;
                for (int i = 0; i < mins.length; i++) {
                    labels[i] = mins[i] == 0 ? "Not known" : mins[i] + " min ago";
                    if (mins[i] == model.startedMinutesAgo())
                        current = i;
                }
                new android.app.AlertDialog.Builder(getMapView().getContext())
                        .setTitle("When did it start?")
                        .setSingleChoiceItems(labels, current, new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dialog, int which) {
                                dialog.dismiss();
                                model.setStartedMinutesAgo(mins[which]);
                                render();
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        });
        view.findViewById(R.id.btn_station).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final FireModel.Run r = model.last();
                final java.util.List<Weather.Reading> list = r == null ? new java.util.ArrayList<Weather.Reading>() : r.stations;
                final String[] labels = new String[1 + list.size()];
                labels[0] = "Closest at about the start's height (automatic)";
                int current = 0;
                for (int i = 0; i < list.size(); i++) {
                    final Weather.Reading s = list.get(i);
                    labels[1 + i] = s.station + ("HRRR".equals(s.source) ? "" : " (" + s.source + ")")
                            + ("NWS forecast".equals(s.source) || "HRRR".equals(s.source) ? "" : " · " + Units.format(s.distanceM))
                            + (s.elevationFt > 0 && r != null && !Double.isNaN(r.startElevFt)
                                    ? String.format(Locale.US, " · %+,.0f ft", s.elevationFt - r.startElevFt) : "")
                            + String.format(Locale.US, " · %.0f mph from %s", s.windMph, Units.compass(s.windFromDeg))
                            + (Double.isNaN(s.gustMph) || s.gustMph <= s.windMph ? "" : String.format(Locale.US, " gust %.0f", s.gustMph))
                            + (Double.isNaN(s.relativeHumidity) ? "" : String.format(Locale.US, " · RH %.0f%%", s.relativeHumidity));
                    if (s.wxId.equals(model.station()))
                        current = 1 + i;
                }
                if (list.isEmpty())
                    labels[0] = "Run once to list the stations near the start";
                new android.app.AlertDialog.Builder(getMapView().getContext())
                        .setTitle("Weather station")
                        .setSingleChoiceItems(labels, current, new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dialog, int which) {
                                dialog.dismiss();
                                model.setStation(which == 0 ? "" : list.get(which - 1).wxId);
                                render();
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        });
        view.findViewById(R.id.btn_conditions).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String[] keys = { FireModel.COND_OBSERVED, FireModel.COND_GUST, FireModel.COND_DRY, FireModel.COND_DRY_WINDY };
                final String[] labels = { "As observed at the station picked",
                        "As observed, but at the gust",
                        "Dry day: 1 h 4%, 10 h 5%, 100 h 7%, wind as observed",
                        "Dry and 20 mph wind" };
                int current = 0;
                for (int i = 0; i < keys.length; i++)
                    if (keys[i].equals(model.conditions()))
                        current = i;
                new android.app.AlertDialog.Builder(getMapView().getContext())
                        .setTitle("Conditions")
                        .setSingleChoiceItems(labels, current, new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dialog, int which) {
                                dialog.dismiss();
                                model.setConditions(keys[which]);
                                render();
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        });
        view.findViewById(R.id.btn_clear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new android.app.AlertDialog.Builder(getMapView().getContext())
                        .setTitle("Clear everything?")
                        .setMessage("Every run comes off the map and the start marker with it. One run at a time is Delete on its row.")
                        .setPositiveButton("Clear all", new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dialog, int which) {
                                model.clearAll();
                                render();
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        });
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        render();
        showDropDown(view, HALF_WIDTH, FULL_HEIGHT, FULL_WIDTH, HALF_HEIGHT, this);
    }

    private FireModel.Run lastSeen;

    @Override
    public void onModelChanged() {
        // A run that just finished is the one to look at.
        final FireModel.Run newest = model.last();
        if (newest != null && newest != lastSeen) {
            lastSeen = newest;
            shown = newest;
        }
        if (isVisible())
            render();
    }

    /** Main thread. */
    public void render() {
        body.removeAllViews();
        final java.util.List<FireModel.Run> runs = model.runs();
        if (shown == null || !runs.contains(shown))
            shown = model.last();
        final FireModel.Run r = shown;
        final com.atakmap.coremap.maps.coords.GeoPoint start = model.startPoint();
        ((Button) view.findViewById(R.id.btn_run)).setEnabled(!model.isRunning() && (start != null || model.perimeter() != null));
        ((Button) view.findViewById(R.id.btn_perimeter)).setText(model.perimeter() != null ? "Perimeter:\ndrawn, tap to drop" : "Draw perimeter");
        ((Button) view.findViewById(R.id.btn_horizon)).setText(model.horizonMinutes() >= 180 ? "Horizon:\n3 hours" : "Horizon:\n1 hour");
        final int ago = model.startedMinutesAgo();
        ((Button) view.findViewById(R.id.btn_started)).setText(ago == 0 ? "Started:\nnot known" : "Started:\n" + ago + " min ago");
        ((Button) view.findViewById(R.id.btn_conditions)).setText("Conditions:\n" + FireModel.conditionsLabel(model.conditions()));
        String stationLabel = "Station:\nautomatic";
        if (!model.station().isEmpty()) {
            stationLabel = "Station:\n" + model.station();
            if (r != null)
                for (Weather.Reading s : r.stations)
                    if (s.wxId.equals(model.station()))
                        stationLabel = "Station:\n" + s.station;
        }
        ((Button) view.findViewById(R.id.btn_station)).setText(stationLabel);
        if (model.isRunning()) {
            section("Running", model.status());
            return;
        }
        if (start == null && model.perimeter() == null) {
            section("Fire model", "Tap Place start, then tap the map where the fire starts. Or Draw perimeter and trace the fire's "
                    + "edge as you find it; say when it started and the run is scaled to what it has already done. Move the marker if it "
                    + "needs to be exact (drag it, or Fine Adjust on its menu), pick the conditions, then Run model. "
                    + "Where the fire is at 20, 40 and 60 minutes is drawn on the map, with everything the run assumed.");
            return;
        }
        if (!runs.isEmpty()) {
            body.addView(headingView("Runs on the map"));
            for (final FireModel.Run x : runs) {
                final View row = PluginLayoutInflater.inflate(pluginContext, R.layout.model_run_row, null);
                final Button name = row.findViewById(R.id.run_name);
                name.setText((x == r ? "\u25B8 " : "") + x.label());
                name.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        // The row goes there and opens its details, like every takwerx list.
                        shown = x;
                        if (!x.visible)
                            model.setVisible(x, true);
                        model.goTo(x);
                        render();
                    }
                });
                final Button toggle = row.findViewById(R.id.run_toggle);
                toggle.setText(x.visible ? "ON" : "OFF");
                toggle.setTextColor(x.visible ? 0xFF3DDC61 : 0xFFFF5B52);
                toggle.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        model.setVisible(x, !x.visible);
                        render();
                    }
                });
                row.findViewById(R.id.run_delete).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        model.clear(x);
                        render();
                    }
                });
                body.addView(row);
            }
        }
        if (r == null) {
            section("Start placed", "Drag the marker or use Fine Adjust on its menu if it needs to be exact, "
                    + "then tap Run model.");
            return;
        }
        if (r.problem != null) {
            section("No run", r.problem);
            inputs(r);
            return;
        }
        final Behave.Result o = r.result;
        body.addView(timeSlider(r));
        if (r.ground != null)
            body.addView(stepTable(r));
        final StringBuilder goes = new StringBuilder();
        if (r.ground != null) {
            for (int i = 0; i < r.steps.length; i++) {
                final int min = r.steps[i];
                goes.append(clock(r.ranAt + min * 60000L)).append(" (").append(min).append(" min): ").append(acres(r.acres(min)))
                        .append(", farthest ").append(Units.format(r.headM(min)))
                        .append(" to the ").append(Units.compass(r.headDeg(min)));
                if (r.structures != null) {
                    goes.append("; ").append(r.structures[i]).append(r.structures[i] == 1 ? " structure" : " structures");
                    if (r.homes[i] > 0)
                        goes.append(" (").append(r.homes[i]).append(r.homes[i] == 1 ? " home)" : " homes)");
                    if (r.people[i] >= 1)
                        goes.append(", about ").append(Math.round(r.people[i])).append(" people");
                }
                goes.append(".\n");
            }
            if (r.ground.maxSpotM > 0)
                goes.append("Embers carry up to ").append(Units.format(r.ground.maxSpotM)).append(" downwind (Albini)")
                        .append(r.ground.spotCrossings == 0 ? ".\n" : "; the fire crossed a road or a block that way "
                                + r.ground.spotCrossings + (r.ground.spotCrossings == 1 ? " time.\n" : " times.\n"));
            if (r.startCanopy != null) {
                final int last = r.steps[r.steps.length - 1];
                final double[] crown = r.ground.crownAcres(last);
                if (crown[0] >= 0.05 || crown[1] >= 0.05)
                    goes.append("Crown fire by ").append(clock(r.ranAt + last * 60000L)).append(": ")
                            .append(acres(crown[0])).append(" crowning through the trees, ")
                            .append(acres(crown[1])).append(" torching. Flames that size are past any line at the head.\n");
                else
                    goes.append("No crown fire: where there are trees, the fire stays on the ground under these conditions.\n");
            }
            if (r.calibrationNote != null)
                goes.append(r.calibrationNote).append('\n');
            if (r.perimeter != null)
                goes.append("Run from the drawn perimeter: everything inside it was burning at the start.\n");
            if (r.ground.startOffsetM > 0 && r.perimeter == null)
                goes.append("The start itself is on ground LANDFIRE marks as not burnable (a road, a roof, bare ground), so the run began "
                        + "from the nearest fuel, ").append(Units.format(r.ground.startOffsetM)).append(" to the ")
                        .append(Units.compass(r.ground.startOffsetDeg)).append(".\n");
            if (r.structureNote != null)
                goes.append(r.structureNote).append('\n');
            else if (r.structures != null)
                goes.append("Structures and people from FEMA's national building inventory; a building counts when the fire reaches the ground beside it.\n");
            goes.append(String.format(Locale.US, "Across the ground: %d fuel%s crossed in %d cells of 15 m. Neighborhoods burn house to house "
                    + "at the rate measured in nine North American conflagrations; roads slow it; commercial blocks, water and bare ground stop it. "
                    + "At the start the head would run toward %s at %s.",
                    r.ground.fuelsCrossed, r.ground.fuelsCrossed == 1 ? "" : "s", r.ground.cellsSeen,
                    Units.compass(o.headingDeg), rate(o.rosFtPerMin)));
        } else {
            goes.append("Head runs toward ").append(Units.compass(o.headingDeg))
                    .append(String.format(Locale.US, " (%.0f°)", o.headingDeg))
                    .append(" at ").append(rate(o.rosFtPerMin)).append(".\n");
            for (int min : r.steps)
                goes.append(min).append(" min: ").append(acres(r.acres(min)))
                        .append(", head ").append(Units.format(r.headM(min)))
                        .append(" from the start, ").append(Units.format(o.aFtPerMin * min * 2 * 0.3048d)).append(" wide.\n");
            goes.append("Backing ").append(rate(o.backingFtPerMin)).append(", flanks ").append(rate(o.flankingFtPerMin)).append('.');
            if (r.groundNote != null)
                goes.append('\n').append(r.groundNote);
        }
        section("Where it goes", goes.toString());

        final String flame = String.format(Locale.US, "Flame length %s. Fireline intensity %.0f BTU/ft/s.",
                Units.format(o.flameLengthFt * 0.3048d), o.firelineIntensity)
                + (o.flameLengthFt > 11 ? " Past what a hand crew can hold at the head."
                        : o.flameLengthFt > 8 ? " Too hot for a hand line at the head; equipment or retardant."
                        : o.flameLengthFt > 4 ? " Hand line at the head is marginal." : " Hand line can hold the head.");
        section("How hard it burns", flame);

        inputs(r);

        final boolean byHour = r.hourly != null && r.hourly.length > 1;
        section("What this does not mean", r.ground != null
                ? "The fuel and slope are the real ground, cell by cell, but the wind is "
                        + (r.fieldWind && r.windField != null ? "a 3 km field, too coarse to know a canyon"
                                : byHour ? "one reading over the whole ground, changing by the hour with the forecast"
                                : "one reading for the whole run and the whole hour")
                        + (byHour ? ", and the fuel moisture steps by the hour, not within it" : "")
                        + ". Spotting is the longest ember flight, straight downwind, across walls "
                        + "narrower than it; "
                        + (r.startCanopy != null ? "crown fire is the trees in LANDFIRE's canopy grid, cell by cell, not "
                                + "the fire's own wind; " : "no crown fire (the canopy grids were not reached); ")
                        + "no suppression. House-to-house spread is an "
                        + "average over past conflagrations, not this street. Run again as the wind changes."
                : "One fuel, one slope and one wind for the whole run, read at the start point. Real fire crosses "
                        + "fuel changes, terrain and a wind that shifts; this is the direction and the order of "
                        + "distance, not a perimeter to plan on. No spotting, no crown fire, no barriers. "
                        + "Run again as the wind changes.");
    }

    private void inputs(FireModel.Run r) {
        final StringBuilder in = new StringBuilder();
        if (r.site != null) {
            in.append("Fuel: ").append(r.fuelName == null || r.fuelName.isEmpty() ? "code " + r.site.fuelModel : r.fuelName).append(" (LANDFIRE 2024).\n");
            in.append(String.format(Locale.US, "Slope %.0f°, facing %s (%.0f°), canopy %.0f%%.\n",
                    r.site.slopeDeg, Units.compass(r.site.aspectDeg), r.site.aspectDeg, r.site.coverPct));
        }
        if (r.result != null || r.windNote != null) {
            in.append(String.format(Locale.US, "Wind %.0f mph from %s (%.0f°) at %s",
                    r.windMph, Units.compass(r.windFromDeg), r.windFromDeg, r.tenMeterWind ? "10 m" : "20 ft"));
            if (r.windNote != null)
                in.append(": ").append(r.windNote);
            in.append(".\n");
            if (r.weather != null) {
                final String h = FireModel.heightNote(r, r.weather);
                if (!h.isEmpty())
                    in.append("That station is").append(h).append("; its wind and humidity may not be the fire's. "
                            + "Pick another under Station if one sits nearer the fire's height.\n");
                if (!Double.isNaN(r.weather.relativeHumidity))
                    in.append(String.format(Locale.US, "RH %.0f%%", r.weather.relativeHumidity));
                if (!Double.isNaN(r.weather.airTempF))
                    in.append(String.format(Locale.US, ", %.0f°F", r.weather.airTempF));
                if (r.weather.observedAt > 0)
                    in.append(", read ").append(ago(r.weather.observedAt));
                in.append(".\n");
            }
            in.append(String.format(Locale.US, "Fuel moisture 1 h %.0f%%, 10 h %.0f%%, 100 h %.0f%%, live herb %.0f%%, live woody %.0f%%.",
                    r.m1, r.m10, r.m100, r.mHerb, r.mWoody));
            if (r.moistureNote != null)
                in.append(' ').append(r.moistureNote);
            if (r.foliarNote != null)
                in.append(' ').append(r.foliarNote);
            if (r.startCanopy == null)
                in.append("\nCanopy grids not reached; this run is surface fire only.");
            else if (r.startCanopy[0] <= 0)
                in.append("\nNo trees over the start (LANDFIRE 2024).");
            else
                in.append(String.format(Locale.US, "\nTrees over the start: %.0f%% cover, %.0f ft tall, crowns from %.0f ft, "
                        + "%.2f kg/m\u00B3 of crown fuel (LANDFIRE 2024).", r.startCanopy[0], r.startCanopy[1],
                        r.startCanopy[2], r.startCanopy[3]));
            if (r.hourly != null && r.hourly.length > 1) {
                in.append("\nHour by hour:");
                for (FireModel.Hour h : r.hourly)
                    in.append(String.format(Locale.US, "\n%s  %.0f mph from %s, 1 h %.0f%%, 10 h %.0f%%",
                            clock(h.at), h.windMph, Units.compass(h.windFromDeg), h.m1, h.m10));
                if (r.hourlyNote != null)
                    in.append('\n').append(r.hourlyNote);
            }
        }
        if (r.windFieldNote != null)
            in.append('\n').append(r.windFieldNote);
        section("What went in", in.toString());
        section("When", FireModel.stamp(r.ranAt) + " run" + (r.by == null ? ""
                : ", received " + FireModel.stamp(r.receivedAt) + ". The file says it was run by " + r.by
                        + "; ATAK does not confirm who sent it. Drawn here from the file's inputs with this phone's own "
                        + "engine, ground and building count."));
        final View row = PluginLayoutInflater.inflate(pluginContext, R.layout.model_action_row, null);
        final Button act = row.findViewById(R.id.action);
        act.setEnabled(!model.isRunning());
        act.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                model.rerun(r);
                render();
            }
        });
        body.addView(row);
        // The run as one file, through ATAK's own send dialog: a contact, a feed, another app.
        final View sendRow = PluginLayoutInflater.inflate(pluginContext, R.layout.model_action_row, null);
        final Button send = sendRow.findViewById(R.id.action);
        send.setText("Send this run");
        send.setEnabled(r.result != null && r.problem == null);
        send.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                model.send(r);
            }
        });
        body.addView(sendRow);
    }

    private static String clock(long t) {
        return new java.text.SimpleDateFormat("HH:mm", Locale.US).format(new java.util.Date(t));
    }

    private static String acres(double a) {
        if (a < 0.1)
            return "under a tenth of an acre";
        if (a < 10)
            return String.format(Locale.US, "%.1f acres", a);
        return String.format(Locale.US, "%,.0f acres", a);
    }

    /**
     * "Where it is by": a slider from the run to its last step in five-minute notches.
     * The fire's edge at that moment is drawn on the map in white as it moves, and the
     * line above it says the clock, acres, reach and buildings. A plain ScrollView
     * page, no pager, so the bar keeps sideways drags and the page keeps vertical ones.
     */
    private View timeSlider(final FireModel.Run r) {
        final LinearLayout wrap = new LinearLayout(view.getContext());
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(headingView("Where it is by"));
        final TextView label = bodyView(timeText(r, r.atMinutes));
        wrap.addView(label);
        final int last = r.steps[r.steps.length - 1];
        final android.widget.SeekBar bar = new android.widget.SeekBar(view.getContext());
        bar.setMax(last / 5);
        bar.setProgress(Math.max(0, Math.min(last, r.atMinutes)) / 5);
        bar.setPadding(dp(18), dp(16), dp(18), dp(16));
        bar.setMinimumHeight(dp(52));
        final android.content.res.ColorStateList white = android.content.res.ColorStateList.valueOf(Color.WHITE);
        bar.setThumbTintList(white);
        bar.setProgressTintList(white);
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        bar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser)
                    return;
                label.setText(timeText(r, progress * 5));
                model.showAt(r, progress * 5, false);
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar sb) {
                model.showAt(r, sb.getProgress() * 5, true);
            }
        });
        wrap.addView(bar);
        // The bar's two ends, so the range reads without touching it.
        final LinearLayout ends = new LinearLayout(view.getContext());
        ends.setOrientation(LinearLayout.HORIZONTAL);
        final TextView from = bodyView("Run, " + clock(r.ranAt));
        final TextView to = bodyView(clock(r.ranAt + last * 60000L));
        from.setTextSize(13f);
        to.setTextSize(13f);
        from.setTextColor(0xFFB0B0B0);
        to.setTextColor(0xFFB0B0B0);
        from.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        to.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        to.setGravity(Gravity.END);
        ends.addView(from);
        ends.addView(to);
        wrap.addView(ends);
        return wrap;
    }

    /** "By 14:35, in 35 min: 42 acres, farthest 0.6 mi to the NE; 3 buildings (2 homes)." */
    private static String timeText(FireModel.Run r, int minutes) {
        final int last = r.steps[r.steps.length - 1];
        if (minutes <= 0)
            return "Slide to see where the fire is at any time up to " + clock(r.ranAt + last * 60000L)
                    + ". Its edge is drawn on the map in white.";
        final StringBuilder sb = new StringBuilder("By ").append(clock(r.ranAt + minutes * 60000L))
                .append(", in ").append(minutes < 60 ? minutes + " min"
                        : minutes % 60 == 0 ? (minutes / 60) + " h" : (minutes / 60) + " h " + (minutes % 60) + " min")
                .append(": ").append(acres(r.acres(minutes))).append(", farthest ").append(Units.format(r.headM(minutes)))
                .append(" to the ").append(Units.compass(r.headDeg(minutes)));
        if (r.ground != null && r.startCanopy != null) {
            final double[] crown = r.ground.crownAcres(minutes);
            if (crown[0] >= 0.05)
                sb.append("; ").append(acres(crown[0])).append(" crowning");
        }
        final double[] b = r.reachedBy(minutes);
        if (b != null) {
            final int n = (int) b[0], h = (int) b[1];
            sb.append("; ").append(n).append(n == 1 ? " building" : " buildings");
            if (h > 0)
                sb.append(" (").append(h).append(h == 1 ? " home)" : " homes)");
            if (b[2] >= 1)
                sb.append(", about ").append(Math.round(b[2])).append(" people");
        }
        return sb.append('.').toString();
    }

    /** The card: a row per step with the clock, acres, structures and people, its color at the left. */
    private View stepTable(FireModel.Run r) {
        final android.widget.TableLayout t = new android.widget.TableLayout(view.getContext());
        t.setStretchAllColumns(false);
        t.setColumnStretchable(1, true);
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        t.setLayoutParams(lp);
        t.addView(tableRow(0, "", "ETA", "Acres", r.structures != null ? "Bldgs" : "", r.structures != null ? "People" : "", true));
        for (int i = 0; i < r.steps.length; i++) {
            final int min = r.steps[i];
            t.addView(tableRow(0xFF000000 | FireModel.stepColor(i, r.steps.length),
                    "", clock(r.ranAt + min * 60000L) + "  (" + min + " min)",
                    acresShort(r.acres(min)),
                    r.structures == null ? "" : String.valueOf(r.structures[i]),
                    r.people == null ? "" : (r.people[i] < 1 ? "0" : String.valueOf(Math.round(r.people[i]))), false));
        }
        if (!r.critical.isEmpty()) {
            final StringBuilder sb = new StringBuilder();
            final double[] from = { r.lat, r.lon };
            for (Object[] c : r.critical)
                sb.append(c[0]).append(", ").append(Units.rangeBearing(from, (Double) c[1], (Double) c[2]))
                        .append(" of the start, by ").append(clock(r.ranAt + r.steps[(Integer) c[3]] * 60000L)).append('\n');
            final LinearLayout wrap = new LinearLayout(view.getContext());
            wrap.setOrientation(LinearLayout.VERTICAL);
            wrap.addView(t);
            wrap.addView(headingView("Places to call first"));
            wrap.addView(bodyView(sb.toString().trim()));
            return wrap;
        }
        return t;
    }

    private android.widget.TableRow tableRow(int color, String swatch, String eta, String acres, String bldgs, String people, boolean head) {
        final android.widget.TableRow row = new android.widget.TableRow(view.getContext());
        final View sw = new View(view.getContext());
        final android.widget.TableRow.LayoutParams swp = new android.widget.TableRow.LayoutParams(dp(14), dp(14));
        swp.rightMargin = dp(8);
        swp.gravity = Gravity.CENTER_VERTICAL;
        sw.setLayoutParams(swp);
        if (color != 0)
            sw.setBackgroundColor(color);
        row.addView(sw);
        for (String s : new String[] { eta, acres, bldgs, people }) {
            final TextView c = new TextView(view.getContext());
            c.setText(s);
            c.setTextSize(head ? 12f : 15f);
            c.setTextColor(head ? 0xFF00E5FF : Color.WHITE);
            if (head)
                c.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            c.setPadding(0, dp(3), dp(14), dp(3));
            row.addView(c);
        }
        return row;
    }

    private static String acresShort(double a) {
        return a < 10 ? String.format(Locale.US, "%.1f", a) : String.format(Locale.US, "%,.0f", a);
    }

    private static String rate(double ftPerMin) {
        return String.format(Locale.US, "%.1f ft/min (%.1f ch/h)", ftPerMin, ftPerMin * 60d / 66d);
    }

    private static String ago(long t) {
        final long mins = (System.currentTimeMillis() - t) / 60000L;
        if (mins < 1)
            return "just now";
        if (mins < 60)
            return mins + " min ago";
        return (mins / 60) + " h ago";
    }

    // ---- the same page furniture as the detail page ---------------------------------

    private void section(String heading, String text) {
        body.addView(headingView(heading));
        body.addView(bodyView(text));
    }

    private TextView headingView(String text) {
        final TextView t = new TextView(view.getContext());
        t.setText(text);
        t.setAllCaps(true);
        t.setTextSize(14f);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setTextColor(0xFF00E5FF);
        t.setGravity(Gravity.START);
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        lp.bottomMargin = dp(2);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView bodyView(String text) {
        final TextView t = new TextView(view.getContext());
        t.setText(text);
        t.setTextSize(16f);
        t.setTextColor(Color.WHITE);
        t.setLineSpacing(dp(4), 1f);
        t.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return t;
    }

    private int dp(int v) {
        return Math.round(v * view.getResources().getDisplayMetrics().density);
    }

    @Override
    public void onDropDownSelectionRemoved() {
    }

    @Override
    public void onDropDownVisible(boolean v) {
    }

    @Override
    public void onDropDownSizeChanged(double width, double height) {
    }

    @Override
    public void onDropDownClose() {
    }

    @Override
    protected void disposeImpl() {
    }
}

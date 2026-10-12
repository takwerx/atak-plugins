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
                goes.append("Embers carry up to ").append(Units.format(r.ground.maxSpotM))
                        .append(" downwind (Albini); the fire crossed a road or a block that way ")
                        .append(r.ground.spotCrossings).append(r.ground.spotCrossings == 1 ? " time.\n" : " times.\n");
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

        section("What this does not mean", r.ground != null
                ? "The fuel and slope are the real ground, cell by cell, but the wind is one reading for the whole "
                        + "run and the whole hour. Spotting is the longest ember flight, straight downwind, across walls "
                        + "narrower than it; no crown fire, no suppression. House-to-house spread is an "
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
        }
        if (r.windFieldNote != null)
            in.append('\n').append(r.windFieldNote);
        section("What went in", in.toString());
        section("When", FireModel.stamp(r.ranAt) + " run");
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

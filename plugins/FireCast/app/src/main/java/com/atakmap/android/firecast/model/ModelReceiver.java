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
import com.atakmap.coremap.maps.coords.GeoPoint;

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

    // NASA's satellite perimeters (FEDS) as a list that follows the map: each fire's box
    // and numbers only; an outline is fetched and drawn only for the one picked.
    private boolean fedsOpen;
    private java.util.List<Feds.Fire> fedsFires;
    private String fedsStatus;
    private boolean fedsLoading;
    /** The box the last list answered, so a pan inside it does not ask again. */
    private double[] fedsBox;
    private final android.os.Handler fedsMain = new android.os.Handler(android.os.Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService fedsWorker = java.util.concurrent.Executors.newSingleThreadExecutor();
    /** County and state per fire, looked up once for the rows shown; "" when the Census has none. Main thread. */
    private final java.util.Map<String, String> fedsPlaces = new java.util.HashMap<>();
    private final java.util.Set<String> fedsAsked = new java.util.HashSet<>();
    private final java.util.concurrent.ExecutorService placeWorker = java.util.concurrent.Executors.newFixedThreadPool(3);
    /** The rows named, so a wide view does not send hundreds of lookups. */
    private static final int FEDS_ROWS = 40;
    /** GL thread: post only (a View or a map item touched here is a native crash). */
    private final com.atakmap.map.AtakMapView.OnMapMovedListener fedsMoved = new com.atakmap.map.AtakMapView.OnMapMovedListener() {
        @Override
        public void onMapMoved(com.atakmap.map.AtakMapView v, boolean animate) {
            fedsMain.removeCallbacks(fedsSettled);
            fedsMain.postDelayed(fedsSettled, 600);
        }
    };
    private final Runnable fedsSettled = new Runnable() {
        @Override
        public void run() {
            if (!fedsOpen || fedsLoading || !isVisible())
                return;
            final double[] now = viewBox();
            final double[] was = fedsBox;
            // Inside the box already answered, and not much smaller than it: nothing new.
            if (now == null || (was != null && now[0] >= was[0] && now[1] >= was[1] && now[2] <= was[2] && now[3] <= was[3]
                    && (now[3] - now[1]) > (was[3] - was[1]) * 0.4))
                return;
            loadFeds();
        }
    };
    private static final String RUNS_FOLD = "Runs on the map";
    private static final String TIMELINE_FOLD = "Timeline Slider", PREF_TIMELINE_OPEN = "model.timelineOpen";
    private static final String SETTINGS_FOLD = "Start and conditions", PREF_SETTINGS_OPEN = "model.settingsOpen";
    private static final String PREF_RUNS_ALL = "model.runsAll", PREF_RUNS_OPEN = "model.runsOpen";
    /** The runs in view at the last redraw, so a pan redraws only when that changes. Main thread. */
    private java.util.Set<String> listedInView = new java.util.HashSet<>();
    /** GL thread: post only. The runs list follows the map while it shows In view. */
    private final com.atakmap.map.AtakMapView.OnMapMovedListener runsMoved = new com.atakmap.map.AtakMapView.OnMapMovedListener() {
        @Override
        public void onMapMoved(com.atakmap.map.AtakMapView v, boolean animate) {
            fedsMain.removeCallbacks(runsSettled);
            fedsMain.postDelayed(runsSettled, 600);
        }
    };
    private final Runnable runsSettled = new Runnable() {
        @Override
        public void run() {
            if (!isVisible() || runsAll() || !OPEN_FOLDS.contains(RUNS_FOLD))
                return;
            if (!inViewIds(model.runs()).equals(listedInView))
                render();
        }
    };
    private final LinearLayout body;
    /** The run whose details the page shows; the newest unless one was tapped. */
    private FireModel.Run shown;

    private android.content.SharedPreferences prefs() {
        return getMapView().getContext().getSharedPreferences("firecast", Context.MODE_PRIVATE);
    }

    private boolean runsAll() {
        return prefs().getBoolean(PREF_RUNS_ALL, false);
    }

    /** A run is in view when its start or any point of its perimeter is in the map beside the pane. */
    private boolean runInView(FireModel.Run x) {
        final double[] b = viewBox();
        if (b == null)
            return true;
        if (x.lat >= b[0] && x.lat <= b[2] && x.lon >= b[1] && x.lon <= b[3])
            return true;
        if (x.perimeter != null)
            for (double[] p : x.perimeter)
                if (p[0] >= b[0] && p[0] <= b[2] && p[1] >= b[1] && p[1] <= b[3])
                    return true;
        return false;
    }

    private java.util.Set<String> inViewIds(java.util.List<FireModel.Run> runs) {
        final java.util.Set<String> ids = new java.util.HashSet<>();
        for (FireModel.Run x : runs)
            if (runInView(x))
                ids.add(x.runId);
        return ids;
    }

    public ModelReceiver(MapView mapView, Context pluginContext, FireModel model, Runnable reopenPane) {
        super(mapView);
        this.pluginContext = pluginContext;
        this.model = model;
        this.reopenPane = reopenPane;
        this.view = PluginLayoutInflater.inflate(pluginContext, R.layout.model_panel, null);
        com.atakmap.android.firecast.DetectionFeed.coveredBy(view);
        this.body = view.findViewById(R.id.model_body);
        if (prefs().getBoolean(PREF_TIMELINE_OPEN, true))
            OPEN_FOLDS.add(TIMELINE_FOLD);
        if (prefs().getBoolean(PREF_SETTINGS_OPEN, true))
            OPEN_FOLDS.add(SETTINGS_FOLD);
        final TextView settingsPill = view.findViewById(R.id.settings_pill);
        stylePill(settingsPill);
        settingsPill.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!OPEN_FOLDS.remove(SETTINGS_FOLD))
                    OPEN_FOLDS.add(SETTINGS_FOLD);
                prefs().edit().putBoolean(PREF_SETTINGS_OPEN, OPEN_FOLDS.contains(SETTINGS_FOLD)).apply();
                paintSettings();
            }
        });
        if (prefs().getBoolean(PREF_RUNS_OPEN, true))
            OPEN_FOLDS.add(RUNS_FOLD);
        try {
            mapView.addOnMapMovedListener(runsMoved);
        } catch (Exception ignored) {
        }
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
                chooseStart();
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
        // Rerun sits beside Run model: the run the page shows, again under today's settings.
        view.findViewById(R.id.btn_rerun).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (shown == null)
                    return;
                model.rerun(shown);
                render();
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
                chooseIgnition();
            }
        });
        view.findViewById(R.id.btn_station).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseWeather();
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
        // From a FEDS perimeter's detail panel: start from that outline.
        final String fedsId = intent == null ? null : intent.getStringExtra("fedsId");
        final double[] box = intent == null ? null : intent.getDoubleArrayExtra("fedsBox");
        if (fedsId != null && box != null && box.length == 4) {
            closeFeds();
            model.satellitePerimeter(new Feds.Fire(fedsId, Double.NaN, intent.getDoubleExtra("fedsDays", Double.NaN),
                    intent.getLongExtra("fedsAt", 0), box[0], box[1], box[2], box[3]));
        }
        // From a "FireCast run from ..." notice: open that run and fly to it.
        final String runId = intent == null ? null : intent.getStringExtra(FireModel.EXTRA_RUN);
        final FireModel.Run asked = runId != null && runId.matches("[0-9a-f-]{36}") ? model.find(runId) : null;
        if (asked != null) {
            shown = asked;
            if (!asked.visible)
                model.setVisible(asked, true);
            model.goTo(asked);
        }
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

    private void openFeds() {
        fedsOpen = true;
        fedsBox = null;
        fedsFires = null;
        try {
            getMapView().addOnMapMovedListener(fedsMoved);
        } catch (Exception ignored) {
        }
        loadFeds();
        render();
    }

    private void closeFeds() {
        fedsOpen = false;
        fedsMain.removeCallbacks(fedsSettled);
        try {
            getMapView().removeOnMapMovedListener(fedsMoved);
        } catch (Exception ignored) {
        }
    }

    /** What is on screen as south, west, north, east; null when the map cannot say or it crosses 180. */
    private double[] viewBox() {
        return com.atakmap.android.firecast.DetectionFeed.visibleBounds(getMapView());
    }

    /** Main thread: asks NASA for the fires in view, the list redrawn when it answers. */
    private void loadFeds() {
        final double[] box = viewBox();
        if (box == null) {
            fedsStatus = "The map cannot say what is in view; pan a little and it asks again.";
            render();
            return;
        }
        fedsLoading = true;
        fedsStatus = "Looking for NASA's satellite perimeters in view...";
        fedsWorker.execute(new Runnable() {
            @Override
            public void run() {
                java.util.List<Feds.Fire> found = null;
                String said;
                try {
                    found = Feds.inBox(box[0], box[1], box[2], box[3]);
                    said = null;
                } catch (Exception e) {
                    com.atakmap.coremap.log.Log.w("FireCast", "FEDS list: " + e);
                    said = "Could not reach NASA's satellite perimeters (no network, or the service did not answer).";
                }
                final java.util.List<Feds.Fire> fires = found;
                final String problem = said;
                fedsMain.post(new Runnable() {
                    @Override
                    public void run() {
                        fedsLoading = false;
                        if (!fedsOpen)
                            return;
                        if (fires != null) {
                            fedsFires = fires;
                            fedsBox = box;
                            fedsStatus = null;
                        } else {
                            fedsStatus = problem;
                        }
                        if (isVisible())
                            render();
                    }
                });
            }
        });
    }

    /** The list: biggest first, nothing drawn until one is picked. */
    private void renderFeds() {
        body.addView(headingView("NASA satellite perimeters in view"));
        final java.util.List<Feds.Fire> fires = fedsFires;
        String text = fedsStatus;
        if (text == null && fires != null)
            text = fires.isEmpty()
                    ? "No fire in view has a NASA outline from the last 3 days. Pan or zoom out to a fire; the list follows the map."
                    : fires.size() + (fires.size() == 1 ? " fire" : " fires") + " in view with a NASA outline from the last 3 days, "
                            + "biggest first. Tap one to start from it; only that one is drawn. The list follows the map."
                            + (fires.size() >= 200 ? " Zoom in to see them all." : "");
        if (text != null)
            body.addView(bodyView(text));
        if (fires != null) {
            final GeoPoint c = getMapView().getCenterPoint().get();
            final int shown = Math.min(fires.size(), FEDS_ROWS);
            askPlaces(fires, shown);
            for (int i = 0; i < shown; i++) {
                final Feds.Fire f = fires.get(i);
                final View row = PluginLayoutInflater.inflate(pluginContext, R.layout.model_run_row, null);
                row.findViewById(R.id.run_toggle).setVisibility(View.GONE);
                row.findViewById(R.id.run_delete).setVisibility(View.GONE);
                final Button name = row.findViewById(R.id.run_name);
                name.setSingleLine(false);
                name.setMaxLines(3);
                name.setText(fedsRow(f, c, fedsPlaces.get(fireKey(f))));
                name.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        closeFeds();
                        model.satellitePerimeter(f);
                        render();
                    }
                });
                body.addView(row);
            }
        }
        final View closeRow = PluginLayoutInflater.inflate(pluginContext, R.layout.model_run_row, null);
        closeRow.findViewById(R.id.run_toggle).setVisibility(View.GONE);
        closeRow.findViewById(R.id.run_delete).setVisibility(View.GONE);
        final Button cancel = closeRow.findViewById(R.id.run_name);
        cancel.setGravity(Gravity.CENTER);
        cancel.setText("Close the list");
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeFeds();
                render();
            }
        });
        body.addView(closeRow);
    }

    /** The fire's number, the part of its id that stays the same from one 12-hour step to the next. */
    private static String fireKey(Feds.Fire f) {
        final int a = f.id.indexOf('|'), b = f.id.lastIndexOf('|');
        return a >= 0 && b > a ? f.id.substring(a + 1, b) : f.id;
    }

    /** Looks up the county and state of each shown row not yet asked; the list redraws as they come. */
    private void askPlaces(java.util.List<Feds.Fire> fires, int shown) {
        for (int i = 0; i < shown; i++) {
            final Feds.Fire f = fires.get(i);
            final String key = fireKey(f);
            if (!fedsAsked.add(key))
                continue;
            placeWorker.execute(new Runnable() {
                @Override
                public void run() {
                    String name;
                    try {
                        name = Feds.place(f.lat(), f.lon());
                    } catch (Exception e) {
                        name = null;
                    }
                    final String got = name;
                    fedsMain.post(new Runnable() {
                        @Override
                        public void run() {
                            if (got == null) {
                                fedsAsked.remove(key);
                                return;
                            }
                            fedsPlaces.put(key, got);
                            fedsMain.removeCallbacks(placesRedraw);
                            fedsMain.postDelayed(placesRedraw, 300);
                        }
                    });
                }
            });
        }
    }

    private final Runnable placesRedraw = new Runnable() {
        @Override
        public void run() {
            if (fedsOpen && isVisible())
                render();
        }
    };

    /** "Rio Arriba County, NM / 1,250 acres, burning 9 days, outline 12 h old / 140 mi NW of the middle of the map". */
    private static String fedsRow(Feds.Fire f, GeoPoint from, String place) {
        final StringBuilder b = new StringBuilder();
        if (place != null && !place.isEmpty())
            b.append(place).append('\n');
        b.append(Double.isNaN(f.areaKm2) ? "size not known" : String.format(Locale.US, "%,.0f acres", f.areaKm2 * 247.105d));
        if (!Double.isNaN(f.burningDays))
            b.append(f.burningDays <= 0 ? ", first seen in this outline" : f.burningDays < 1 ? ", burning half a day"
                    : ", burning " + Math.round(f.burningDays) + (Math.round(f.burningDays) == 1 ? " day" : " days"));
        final long ageMin = Math.max(0, (System.currentTimeMillis() - f.atMs) / 60000L);
        b.append(", outline ").append(ageMin < 120 ? ageMin + " min" : (ageMin / 60) + " h").append(" old");
        if (from != null) {
            final GeoPoint at = new GeoPoint(f.lat(), f.lon());
            b.append("\n").append(Units.format(from.distanceTo(at))).append(' ')
                    .append(Units.compass(from.bearingTo(at))).append(" of the middle of the map");
        }
        return b.toString();
    }

    /**
     * The run's weather: a station (automatic or picked), the operator's own observation
     * typed in, or a Kestrel Wx station heard on the map (operator, 2026-10-31).
     */
    private void chooseWeather() {
        final Observation ob = model.observation();
        final java.util.List<String> items = new java.util.ArrayList<>();
        items.add("A weather station (automatic, or pick one)");
        items.add("My observation: type it in");
        items.add("From a Kestrel station on the map");
        if (ob != null)
            items.add("Stop using my observation");
        new android.app.AlertDialog.Builder(getMapView().getContext())
                .setTitle(ob != null && ob.usable(System.currentTimeMillis()) ? "Weather: " + ob.summary() : "Weather for the run")
                .setItems(items.toArray(new String[0]), new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        if (which == 0) {
                            model.setObservation(null);
                            chooseStation();
                        } else if (which == 1) {
                            enterObservation(ob, null);
                        } else if (which == 2) {
                            chooseKestrel();
                        } else {
                            model.setObservation(null);
                            render();
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Lists the stations around the start, fetching them first when the start is new. */
    private void chooseStation() {
        final GeoPoint p = model.startPoint();
        if (p == null) {
            new android.app.AlertDialog.Builder(getMapView().getContext())
                    .setTitle("Weather station")
                    .setMessage("Place the start first (Start from), then the stations around it are listed here.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        final android.app.AlertDialog wait = new android.app.AlertDialog.Builder(getMapView().getContext())
                .setTitle("Weather station")
                .setMessage("Finding the stations around the start...")
                .setNegativeButton("Cancel", null)
                .show();
        model.fetchNearby(p.getLatitude(), p.getLongitude(), new Runnable() {
            @Override
            public void run() {
                if (!wait.isShowing())
                    return;
                wait.dismiss();
                showStations();
            }
        });
    }

    private void showStations() {
                final java.util.List<Weather.Reading> found = model.nearby();
                final double[] at = model.nearbyAt();
                final double startFt = at == null ? Double.NaN : at[2];
                final java.util.List<Weather.Reading> list = new java.util.ArrayList<>();
                if (found != null)
                    list.addAll(found);
                java.util.Collections.sort(list, new java.util.Comparator<Weather.Reading>() {
                    @Override
                    public int compare(Weather.Reading a, Weather.Reading b) {
                        return Double.compare(a.distanceM, b.distanceM);
                    }
                });
                final String[] labels = new String[3 + list.size()];
                final String[] ids = new String[labels.length];
                labels[0] = "Closest at about the start's height (automatic)";
                ids[0] = "";
                labels[1] = "HRRR 3 km wind field (wind only; humidity from the forecast)";
                ids[1] = "HRRR";
                labels[2] = "NWS forecast for this spot";
                ids[2] = "FORECAST";
                for (int i = 0; i < list.size(); i++) {
                    final Weather.Reading s = list.get(i);
                    labels[3 + i] = s.station + " (" + s.source + ")"
                            + " \u00B7 " + Units.format(s.distanceM)
                            + (s.elevationFt > 0 && !Double.isNaN(startFt)
                                    ? String.format(Locale.US, " \u00B7 %+,.0f ft", s.elevationFt - startFt) : "")
                            + String.format(Locale.US, " \u00B7 %.0f mph from %s", s.windMph, Units.compass(s.windFromDeg))
                            + (Double.isNaN(s.gustMph) || s.gustMph <= s.windMph ? "" : String.format(Locale.US, " gust %.0f", s.gustMph))
                            + (Double.isNaN(s.relativeHumidity) ? "" : String.format(Locale.US, " \u00B7 RH %.0f%%", s.relativeHumidity))
                            + (s.observedAt > 0 ? " \u00B7 " + clock(s.observedAt) : "");
                    ids[3 + i] = s.wxId;
                }
                int current = 0;
                for (int i = 0; i < ids.length; i++)
                    if (ids[i].equals(model.station()))
                        current = i;
                final int[] picked = { current };
                new android.app.AlertDialog.Builder(getMapView().getContext())
                        .setTitle(list.isEmpty() ? "Weather station (none answered near the start)" : "Weather station")
                        .setSingleChoiceItems(labels, current, new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dialog, int which) {
                                picked[0] = which;
                                dialog.dismiss();
                                model.setStation(ids[which]);
                                render();
                            }
                        })
                        // OK keeps what is ticked: tapping the ticked row did not read as a way out.
                        .setPositiveButton("OK", new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dialog, int which) {
                                model.setStation(ids[picked[0]]);
                                render();
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
    }

    /** Kestrel Wx stations heard on this phone, newest first; one picked fills the observation form. */
    private void chooseKestrel() {
        final java.util.List<KestrelStations.Station> all = KestrelStations.stations();
        final GeoPoint here = model.startPoint() != null ? model.startPoint() : getMapView().getCenterPoint().get();
        if (all.isEmpty()) {
            new android.app.AlertDialog.Builder(getMapView().getContext())
                    .setTitle("No Kestrel stations")
                    .setMessage("No Kestrel Wx station has been heard on this device since FireCast started. A station "
                            + "appears when the Kestrel Wx plugin on a phone with a meter shares its reading to the team.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        final String[] labels = new String[all.size()];
        final long now = System.currentTimeMillis();
        for (int i = 0; i < labels.length; i++) {
            final KestrelStations.Station k = all.get(i);
            final long ago = Math.max(0, (now - k.at) / 60000L);
            labels[i] = k.name + String.format(Locale.US, " · %.0f mph from %s", k.windMph, Units.compass(k.windFromDeg))
                    + (Double.isNaN(k.tempF) ? "" : String.format(Locale.US, " · %.0f°F", k.tempF))
                    + (Double.isNaN(k.rh) ? "" : String.format(Locale.US, " · %.0f%% RH", k.rh))
                    + " · " + (ago < 120 ? ago + " min" : (ago / 60) + " h") + " ago"
                    + (here == null ? "" : " · " + Units.rangeBearing(new double[] { here.getLatitude(), here.getLongitude() }, k.lat, k.lon));
        }
        new android.app.AlertDialog.Builder(getMapView().getContext())
                .setTitle("Kestrel stations")
                .setItems(labels, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        final KestrelStations.Station k = all.get(which);
                        // A station named "Kestrel 5500" is not "Kestrel Kestrel 5500".
                        final String src = k.name != null && k.name.trim().toLowerCase(java.util.Locale.US).startsWith("kestrel")
                                ? k.name.trim() : "Kestrel " + k.name;
                        enterObservation(Observation.checked(k.windMph, k.windFromDeg, Double.NaN, k.tempF, k.rh, Double.NaN,
                                k.at, true, src), src);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * The observation form: wind, direction, gust, temperature, humidity, 1 h fuel
     * moisture, eye level or 20 ft. Filled from {@code start} when given (the last one,
     * or a Kestrel reading), every field editable. MapView context, plain EditTexts.
     */
    private void enterObservation(final Observation start, final String source) {
        final android.content.Context c = getMapView().getContext();
        final LinearLayout form = new LinearLayout(c);
        form.setOrientation(LinearLayout.VERTICAL);
        final int pad = dp(16);
        form.setPadding(pad, dp(8), pad, 0);
        final android.widget.EditText wind = field(form, "Wind, mph", start == null ? "" : fmt(start.windMph), false);
        final android.widget.EditText from = field(form, "Wind from: degrees or N, NE, SW ...",
                start == null ? "" : fmt(start.windFromDeg), true);
        final android.widget.EditText gust = field(form, "Gust, mph (optional)", start == null ? "" : fmt(start.gustMph), false);
        final android.widget.EditText temp = field(form, "Temperature, °F", start == null ? "" : fmt(start.tempF), false);
        final android.widget.EditText rh = field(form, "Relative humidity, %", start == null ? "" : fmt(start.rh), false);
        final android.widget.EditText fm = field(form, "1 h fuel moisture, % (optional; else from temp and RH)",
                start == null ? "" : fmt(start.fineFuelPct), false);
        final android.widget.CheckBox eye = new android.widget.CheckBox(c);
        eye.setText("Wind read at eye level (a hand-held meter)");
        eye.setChecked(start == null || start.eyeLevel);
        form.addView(eye);
        final android.widget.ScrollView scroll = new android.widget.ScrollView(c);
        scroll.addView(form);
        new android.app.AlertDialog.Builder(c)
                .setTitle(source != null ? "Observation from " + source : "My observation")
                .setView(scroll)
                .setPositiveButton("Use it", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        final Observation ob = Observation.checked(num(wind), direction(from.getText().toString()), num(gust),
                                num(temp), num(rh), num(fm), start != null && source != null ? start.at : System.currentTimeMillis(),
                                eye.isChecked(), source != null ? source : "your observation");
                        if (ob == null) {
                            new android.app.AlertDialog.Builder(c).setTitle("Not used")
                                    .setMessage("A wind speed and a direction are needed, and every number has to be one a "
                                            + "real reading can be (wind 0-150 mph, temperature -40 to 130°F, humidity 1-100%, "
                                            + "fuel moisture 1-60%).")
                                    .setPositiveButton("OK", null).show();
                            return;
                        }
                        model.setObservation(ob);
                        render();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private android.widget.EditText field(LinearLayout form, String hint, String value, boolean text) {
        final TextView label = new TextView(form.getContext());
        label.setText(hint);
        label.setTextColor(0xFFB0B0B0);
        form.addView(label);
        final android.widget.EditText e = new android.widget.EditText(form.getContext());
        e.setSingleLine(true);
        e.setInputType(text ? android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                : android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setText(value);
        form.addView(e);
        return e;
    }

    private static String fmt(double v) {
        return Double.isNaN(v) ? "" : String.format(Locale.US, "%.0f", v);
    }

    private static double num(android.widget.EditText e) {
        final String s = e.getText().toString().trim();
        if (s.isEmpty() || s.length() > 12)
            return Double.NaN;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException x) {
            return Double.NaN;
        }
    }

    /** "225", "SW", "s" -> degrees; NaN when neither. */
    static double direction(String s) {
        final String t = s == null ? "" : s.trim().toUpperCase(Locale.US);
        if (t.isEmpty() || t.length() > 8)
            return Double.NaN;
        final String[] names = { "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW" };
        for (int i = 0; i < names.length; i++)
            if (names[i].equals(t))
                return i * 22.5;
        try {
            final double d = Double.parseDouble(t.replace("°", ""));
            return d >= 0 && d <= 360 ? d % 360 : Double.NaN;
        } catch (NumberFormatException x) {
            return Double.NaN;
        }
    }

    /** "Ignition:\n23:40 (35 min ago)", or not known, or set but too old to use. */
    private String ignitionLabel() {
        final long at = model.ignitionAt(), now = System.currentTimeMillis();
        if (at <= 0)
            return "Ignition:\nnot known";
        if (model.usableIgnition(now) == 0)
            return "Ignition:\n" + clock(at) + " (not used)";
        final long ago = Math.max(0, (now - at) / 60000L);
        return "Ignition:\n" + clock(at) + " (" + (ago < 120 ? ago + " min" : (ago / 60) + " h " + (ago % 60) + " min") + " ago)";
    }

    /**
     * When the fire ignited: a clock time, so "5 minutes before dispatch" stays the same
     * moment however long the crew takes to get there (operator, 2026-10-31).
     */
    private void chooseIgnition() {
        final String[] items = { "Not known", "Now", "5 min ago", "15 min ago", "30 min ago", "1 hour ago", "2 hours ago",
                "Set the time...", "Minutes before dispatch..." };
        final int[] mins = { -1, 0, 5, 15, 30, 60, 120 };
        new android.app.AlertDialog.Builder(getMapView().getContext())
                .setTitle("When did it ignite?")
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        final long now = System.currentTimeMillis();
                        if (which < mins.length) {
                            model.setIgnitionAt(mins[which] < 0 ? 0 : now - mins[which] * 60000L);
                            render();
                        } else if (which == 7) {
                            pickClock("Ignition time", new ClockPicked() {
                                @Override
                                public void at(long t) {
                                    model.setIgnitionAt(t);
                                    render();
                                }
                            });
                        } else {
                            pickClock("Dispatch time", new ClockPicked() {
                                @Override
                                public void at(final long dispatch) {
                                    final String[] before = { "At dispatch", "5 min before", "10 min before", "15 min before",
                                            "30 min before", "1 hour before" };
                                    final int[] m = { 0, 5, 10, 15, 30, 60 };
                                    new android.app.AlertDialog.Builder(getMapView().getContext())
                                            .setTitle("Ignition, before dispatch at " + clock(dispatch))
                                            .setItems(before, new android.content.DialogInterface.OnClickListener() {
                                                @Override
                                                public void onClick(android.content.DialogInterface d, int i) {
                                                    model.setIgnitionAt(dispatch - m[i] * 60000L);
                                                    render();
                                                }
                                            })
                                            .setNegativeButton("Cancel", null)
                                            .show();
                                }
                            });
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private interface ClockPicked {
        void at(long epochMs);
    }

    /** A 24-hour time picker on the MapView context; a time later than now is taken as yesterday's. */
    private void pickClock(String title, final ClockPicked done) {
        final java.util.Calendar c = java.util.Calendar.getInstance();
        final android.app.TimePickerDialog d = new android.app.TimePickerDialog(getMapView().getContext(),
                new android.app.TimePickerDialog.OnTimeSetListener() {
                    @Override
                    public void onTimeSet(android.widget.TimePicker v, int hour, int minute) {
                        final java.util.Calendar t = java.util.Calendar.getInstance();
                        t.set(java.util.Calendar.HOUR_OF_DAY, hour);
                        t.set(java.util.Calendar.MINUTE, minute);
                        t.set(java.util.Calendar.SECOND, 0);
                        t.set(java.util.Calendar.MILLISECOND, 0);
                        if (t.getTimeInMillis() > System.currentTimeMillis() + 60000L)
                            t.add(java.util.Calendar.DAY_OF_MONTH, -1);
                        done.at(t.getTimeInMillis());
                    }
                }, c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE), true);
        d.setTitle(title);
        d.show();
    }

    /** Start the run from: a point, a perimeter drawn, or a shape already on the map (operator's names, 2026-11-02). */
    private void chooseStart() {
        final GeoPoint start = model.startPoint();
        final java.util.List<String> items = new java.util.ArrayList<>();
        // Three ways in, in the order a crew meets them; anything already on the map is a
        // shape to tap, whatever brought it there (operator, 2026-11-02).
        items.add("A point");
        items.add("A perimeter you draw");
        items.add("A shape already on the map");
        new android.app.AlertDialog.Builder(getMapView().getContext())
                .setTitle("Start the Run From")
                .setItems(items.toArray(new String[0]), new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        switch (which) {
                            case 0:
                                // The page gives way to the map so the tap lands on it.
                                closeFeds();
                                model.clearPerimeter();
                                closeDropDown();
                                model.pickStart();
                                break;
                            case 1:
                                closeFeds();
                                closeDropDown();
                                model.drawPerimeter();
                                break;
                            default:
                                closeFeds();
                                closeDropDown();
                                model.pickShape();
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
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
        view.findViewById(R.id.btn_rerun).setEnabled(!model.isRunning() && r != null);
        // Shown again below only for a run that has a result to size or send.
        view.findViewById(R.id.run_actions).setVisibility(View.GONE);
        view.findViewById(R.id.btn_size).setVisibility(View.GONE);
        ((Button) view.findViewById(R.id.btn_place)).setText(model.perimeter() != null
                ? ("nasa".equals(model.perimeterKind()) ? "Start from: NASA perimeter"
                        : "shape".equals(model.perimeterKind()) ? "Start from: map shape" : "Start from: drawn perimeter")
                : start != null ? "Start from: a point" : "Start from: choose");
        ((Button) view.findViewById(R.id.btn_horizon)).setText(model.horizonMinutes() >= 180 ? "Horizon:\n3 hours" : "Horizon:\n1 hour");
        ((Button) view.findViewById(R.id.btn_started)).setText(ignitionLabel());
        ((Button) view.findViewById(R.id.btn_conditions)).setText("Conditions:\n" + FireModel.conditionsLabel(model.conditions()));
        final Observation obs = model.observation();
        String stationLabel = "Weather:\nautomatic";
        if (!model.station().isEmpty()) {
            stationLabel = "Weather:\n" + model.station();
            if (r != null)
                for (Weather.Reading s : r.stations)
                    if (s.wxId.equals(model.station()))
                        stationLabel = "Weather:\n" + s.station;
            final java.util.List<Weather.Reading> near = model.nearby();
            if (near != null)
                for (Weather.Reading s : near)
                    if (s.wxId.equals(model.station()))
                        stationLabel = "Weather:\n" + s.station;
            if ("HRRR".equals(model.station()))
                stationLabel = "Weather:\nHRRR wind";
            else if ("FORECAST".equals(model.station()))
                stationLabel = "Weather:\nNWS forecast";
        }
        if (obs != null)
            stationLabel = obs.usable(System.currentTimeMillis())
                    ? "Weather:\n" + (obs.source.startsWith("Kestrel") ? obs.source : "my obs " + clock(obs.at))
                    : "Weather:\nobs too old";
        ((Button) view.findViewById(R.id.btn_station)).setText(stationLabel);
        paintSettings();
        if (model.isRunning()) {
            section("Running", model.status());
            return;
        }
        if (fedsOpen)
            renderFeds();
        if (start == null && model.perimeter() == null) {
            section("Fire model", "Tap Start from and choose: a point you tap on the map, a perimeter you draw, "
                    + "or a shape already on the map; with a perimeter, say when it started and the run is scaled to what it has already done. Move the marker if it "
                    + "needs to be exact (drag it, or Fine Adjust on its menu), pick the conditions, then Run model. "
                    + "Where the fire is at 20, 40 and 60 minutes is drawn on the map, with everything the run assumed.");
            return;
        }
        if (!runs.isEmpty()) {
            // Behind a fold the operator can shut, and inside it only the runs in the map
            // beside the pane unless All is picked: seven rows of last night's runs
            // pushed the page down a screen (operator, 2026-11-02).
            final boolean allRuns = runsAll();
            final java.util.List<FireModel.Run> listed = new java.util.ArrayList<>();
            int inView = 0;
            for (FireModel.Run x : runs) {
                final boolean in = runInView(x);
                if (in)
                    inView++;
                if (allRuns || in || x == r)
                    listed.add(x);
            }
            listedInView = inViewIds(runs);
            beginFold(RUNS_FOLD);
            foldSummary = runs.size() + (runs.size() == 1 ? " run, " : " runs, ") + inView + " in view";
            final View filterRow = PluginLayoutInflater.inflate(pluginContext, R.layout.model_action_row, null);
            final Button filter = filterRow.findViewById(R.id.action);
            filter.setText(allRuns ? "Show: All runs (" + runs.size() + ")"
                    : "Show: In view (" + inView + " of " + runs.size() + ")");
            filter.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    prefs().edit().putBoolean(PREF_RUNS_ALL, !runsAll()).apply();
                    render();
                }
            });
            foldContent.addView(filterRow);
            foldContent.addView(opacitySlider());
            if (listed.isEmpty())
                foldContent.addView(bodyView("None of the " + runs.size() + " runs is in the map beside the pane. "
                        + "Pan to one, or tap Show for all of them."));
            for (final FireModel.Run x : listed) {
                final View row = PluginLayoutInflater.inflate(pluginContext, R.layout.model_run_row, null);
                final Button name = row.findViewById(R.id.run_name);
                name.setText((x == r ? "\u25B8 " : "") + x.label());
                // The run the page is showing reads cyan, like the plugin's own marks (operator, 2026-11-02).
                if (x == r)
                    name.setTextColor(0xFF00E5FF);
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
                foldContent.addView(row);
            }
            endFold();
        }
        if (r == null) {
            section("Start placed", model.perimeter() != null
                    ? "Tap Run model. Everything inside the outline starts burning."
                    : "Drag the marker or use Fine Adjust on its menu if it needs to be exact, then tap Run model.");
            return;
        }
        if (r.problem != null) {
            section("No run", r.problem);
            inputs(r);
            return;
        }
        final Behave.Result o = r.result;
        // The slider and its table fold together, open until the operator shuts them.
        beginFold(TIMELINE_FOLD);
        final int lastStep = r.steps[r.steps.length - 1];
        foldSummary = timeText(r, r.atMinutes > 0 ? r.atMinutes : lastStep);
        foldContent.addView(timeSlider(r));
        if (r.ground != null)
            foldContent.addView(stepTable(r));
        endFold();
        final StringBuilder goes = new StringBuilder();
        if (r.originAt > 0)
            goes.append("Ignition set to ").append(clock(r.originAt)).append(", ").append(r.nowMinutes())
                    .append(" min before this run: each ring is where the fire should be by its time, the ")
                    .append(clock(r.ranAt)).append(" one now.\n");
        if (r.ground != null) {
            for (int i = 0; i < r.steps.length; i++) {
                final int min = r.steps[i];
                goes.append(clock(r.t0() + min * 60000L)).append(" (").append(min).append(" min): ").append(acres(r.acres(min)))
                        .append(", farthest ").append(Units.format(r.headM(min)))
                        .append(" to the ").append(Units.compass(r.headDeg(min)));
                if (r.structures != null) {
                    goes.append("; ").append(r.structures[i]).append(r.structures[i] == 1 ? " structure" : " structures");
                    if (r.homes[i] > 0)
                        goes.append(" (").append(r.homes[i]).append(r.homes[i] == 1 ? " home)" : " homes)");
                    if (r.people[i] >= 1)
                        goes.append(", up to ").append(Math.round(r.people[i])).append(" people");
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
                    goes.append("Crown fire by ").append(clock(r.t0() + last * 60000L)).append(": ")
                            .append(acres(crown[0])).append(" crowning through the trees, ")
                            .append(acres(crown[1])).append(" torching. Flames that size are past any line at the head.\n");
                else
                    goes.append("No crown fire: where there are trees, the fire stays on the ground under these conditions.\n");
            }
            if (r.calibrationNote != null)
                goes.append(r.calibrationNote).append('\n');
            if (r.perimeter != null && "shape".equals(r.perimeterKind))
                goes.append("Run from a shape on the map: everything inside it was burning at the start. ")
                        .append(r.perimeterNote == null ? "" : r.perimeterNote).append('\n');
            else if (r.perimeter != null && r.perimeterNote != null)
                goes.append("Run from the satellite perimeter: everything inside it was burning at the start. ")
                        .append(r.perimeterNote).append('\n');
            else if (r.perimeter != null)
                goes.append("Run from the drawn perimeter: everything inside it was burning at the start.\n");
            if (r.ground.startOffsetM > 0 && r.perimeter == null)
                goes.append("The start itself is on ground that does not carry fire here (a road, a roof, bare ground, or fuel too "
                        + "wet or sparse), so the run began "
                        + "from the nearest fuel, ").append(Units.format(r.ground.startOffsetM)).append(" to the ")
                        .append(Units.compass(r.ground.startOffsetDeg)).append(".\n");
            final int end = r.steps[r.steps.length - 1];
            final double grew = r.ground.acres(end) - r.ground.acres(0);
            if (r.perimeter != null && grew < 0.5)
                goes.append(String.format(Locale.US, "No spread past the perimeter by %s: %sthe fine dead fuel is %.0f%% water",
                        clock(r.t0() + end * 60000L), r.weather == null || Double.isNaN(r.weather.relativeHumidity) ? ""
                                : String.format(Locale.US, "at %.0f%% humidity ", r.weather.relativeHumidity),
                        r.m1)).append(r.m1 >= 20 ? ", too wet to carry fire. " : " and the fire is too slow to cross a cell. ")
                        .append("Conditions: Dry day shows what it would do once it dries out.\n");
            else if (r.acres(end) < 0.5)
                goes.append("A slow fire: the fuel here carries fire at ").append(rate(o.rosFtPerMin))
                        .append(" at this wind, so it covers ").append(acres(r.acres(end))).append(" by ")
                        .append(clock(r.t0() + end * 60000L)).append(". A stronger wind or a drier day moves it much "
                        + "faster; Conditions: Dry and 20 mph wind shows how much.\n");
            if (r.structureNote != null)
                goes.append(r.structureNote).append('\n');
            else if (r.structures != null)
                goes.append("Structures and people from FEMA's national building inventory, people as FEMA's upper estimate; a building counts when the fire reaches the ground beside it.\n");
            goes.append(String.format(Locale.US, "Across the ground: %d fuel%s crossed in %d cells of %.0f m. Neighborhoods burn house to house "
                    + "at the rate measured in nine North American conflagrations; roads slow it; commercial blocks, water and bare ground stop it. "
                    + "At the start the head would run toward %s at %s.",
                    r.ground.fuelsCrossed, r.ground.fuelsCrossed == 1 ? "" : "s", r.ground.cellsSeen, r.cellM,
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
        String sizeText = null;
        if (r.sizeAcres != null) {
            // PyreCast's own control and words, so the two read the same: one size at a
            // time, Median unless another is picked, and everything on the page is that run.
            final String name = com.atakmap.android.firecast.PyrecastForecasts.pctName(r.sizePct);
            sizeText = ("Showing the " + name + " of " + r.sizeRuns
                    + " runs of this fire, with the wind's speed and direction and the dead fuel moisture nudged"
                    + " the way a forecast misses. The rings, the slider, the acres and the buildings on this page"
                    + " are that run.");
            final Button pick = view.findViewById(R.id.btn_size);
            pick.setVisibility(View.VISIBLE);
            pick.setText("Predicted Fire Size: " + name);
            pick.setEnabled(!model.isRunning());
            pick.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    final int[] pcts = { 90, 70, 50, 30, 10 };
                    final CharSequence[] items = new CharSequence[pcts.length];
                    int at = 2;
                    for (int i = 0; i < pcts.length; i++) {
                        int k = 0;
                        for (int j = 0; j < FireModel.SIZE_PCT.length; j++)
                            if (FireModel.SIZE_PCT[j] == pcts[i])
                                k = j;
                        final String w = com.atakmap.android.firecast.PyrecastForecasts.pctName(pcts[i]);
                        final android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder(w);
                        b.setSpan(new android.text.style.ForegroundColorSpan(0xFF00E5FF), 0, w.length(),
                                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        b.append(" (").append(String.valueOf(pcts[i])).append("th percentile)  ·  ")
                                .append(acres(r.sizeAcres[k])).append(" by ")
                                .append(String.valueOf(r.steps[r.steps.length - 1])).append(" min");
                        items[i] = b;
                        if (pcts[i] == r.sizePct)
                            at = i;
                    }
                    new android.app.AlertDialog.Builder(getMapView().getContext())
                            .setTitle("Predicted Fire Size")
                            .setSingleChoiceItems(items, at, new android.content.DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(android.content.DialogInterface d, int which) {
                                    d.dismiss();
                                    model.setSize(r, pcts[which]);
                                }
                            })
                            .setNegativeButton("Close", null)
                            .show();
                }
            });
        }
        beginFold("Where it goes");
        section("Where it goes", goes.toString());
        endFold();
        if (sizeText != null) {
            beginFold("About Predicted Fire Size");
            section("About Predicted Fire Size", sizeText);
            endFold();
        }

        final String flame = String.format(Locale.US, "Flame length %s. Fireline intensity %.0f BTU/ft/s.",
                Units.format(o.flameLengthFt * 0.3048d), o.firelineIntensity)
                + (o.flameLengthFt > 11 ? " Past what a hand crew can hold at the head."
                        : o.flameLengthFt > 8 ? " Too hot for a hand line at the head; equipment or retardant."
                        : o.flameLengthFt > 4 ? " Hand line at the head is marginal." : " Hand line can hold the head.");
        beginFold("Burn behavior");
        section("How hard it burns", flame);

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
        endFold();
        inputs(r);
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
                    r.windMph, Units.compass(r.windFromDeg), r.windFromDeg, r.eyeLevelWind ? "eye level" : r.tenMeterWind ? "10 m" : "20 ft"));
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
        beginFold("What went in");
        section("What went in", in.toString());
        section("When", FireModel.stamp(r.ranAt) + " run" + (r.by == null ? ""
                : ", received " + FireModel.stamp(r.receivedAt) + ". The file says it was run by " + r.by
                        + "; ATAK does not confirm who sent it. Drawn here from the file's inputs with this phone's own "
                        + "engine, ground and building count."));
        endFold();
        // The run as one file, through ATAK's own send dialog: a contact, a feed, another app.
        view.findViewById(R.id.run_actions).setVisibility(View.VISIBLE);
        final Button send = view.findViewById(R.id.btn_send);
        send.setEnabled(r.result != null && r.problem == null);
        send.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                model.send(r);
            }
        });
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
     * "Timeline Slider": a slider from the run to its last step in five-minute notches.
     * The fire's edge at that moment is drawn on the map in cyan as it moves, and the
     * line above it says the clock, acres, reach and buildings. A plain ScrollView
     * page, no pager, so the bar keeps sideways drags and the page keeps vertical ones.
     */
    private View timeSlider(final FireModel.Run r) {
        final LinearLayout wrap = new LinearLayout(view.getContext());
        wrap.setOrientation(LinearLayout.VERTICAL);
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
        final TextView from = bodyView(r.originAt > 0 ? "Ignition, " + clock(r.originAt) : "Run, " + clock(r.ranAt));
        final TextView to = bodyView(clock(r.t0() + last * 60000L));
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

    /** How solid the runs draw over the map, 10-100 %; applied when the thumb is let go. */
    private View opacitySlider() {
        final LinearLayout wrap = new LinearLayout(view.getContext());
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(0, dp(6), 0, 0);
        final TextView label = bodyView("Opacity: " + model.opacity() + "%");
        wrap.addView(label);
        final android.widget.SeekBar bar = new android.widget.SeekBar(view.getContext());
        bar.setMax(9);
        bar.setProgress(model.opacity() / 10 - 1);
        bar.setPadding(dp(18), dp(12), dp(18), dp(12));
        bar.setMinimumHeight(dp(48));
        final android.content.res.ColorStateList white = android.content.res.ColorStateList.valueOf(Color.WHITE);
        bar.setThumbTintList(white);
        bar.setProgressTintList(white);
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        bar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar sb, int progress, boolean fromUser) {
                label.setText("Opacity: " + (progress + 1) * 10 + "%");
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar sb) {
                model.setOpacity((sb.getProgress() + 1) * 10);
            }
        });
        wrap.addView(bar);
        return wrap;
    }

    /** "By 14:35, in 35 min: 42 acres, farthest 0.6 mi to the NE; 3 buildings (2 homes)." */
    private static String timeText(FireModel.Run r, int minutes) {
        final int last = r.steps[r.steps.length - 1];
        if (minutes <= 0)
            return "Slide to see where the fire is at any time up to " + clock(r.t0() + last * 60000L)
                    + ". Its edge is drawn on the map in cyan.";
        final StringBuilder sb = new StringBuilder("By ").append(clock(r.t0() + minutes * 60000L))
                .append(r.originAt > 0 ? ", " : ", in ").append(minutes < 60 ? minutes + " min"
                        : minutes % 60 == 0 ? (minutes / 60) + " h" : (minutes / 60) + " h " + (minutes % 60) + " min")
                .append(r.originAt > 0 ? " after ignition" : "")
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
                sb.append(", up to ").append(Math.round(b[2])).append(" people");
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
        t.addView(tableRow(0, "", "ETA", "Acres", r.structures != null ? "Bldgs" : "", r.structures != null ? "People, up to" : "", true));
        for (int i = 0; i < r.steps.length; i++) {
            final int min = r.steps[i];
            t.addView(tableRow(0xFF000000 | FireModel.stepColor(i, r.steps.length),
                    "", clock(r.t0() + min * 60000L) + (r.originAt > 0 && min == r.nowMinutes() ? "  now" : "  (" + min + " min)"),
                    acresShort(r.acres(min)),
                    r.structures == null ? "" : String.valueOf(r.structures[i]),
                    r.people == null ? "" : (r.people[i] < 1 ? "0" : String.valueOf(Math.round(r.people[i]))), false));
        }
        if (!r.critical.isEmpty()) {
            final StringBuilder sb = new StringBuilder();
            final double[] from = { r.lat, r.lon };
            for (Object[] c : r.critical)
                sb.append(c[0]).append(", ").append(Units.rangeBearing(from, (Double) c[1], (Double) c[2]))
                        .append(" of the start, by ").append(clock(r.t0() + r.steps[(Integer) c[3]] * 60000L)).append('\n');
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
        if (foldContent == null) {
            body.addView(headingView(heading));
            body.addView(bodyView(text));
            return;
        }
        // Inside a fold the pill is the heading; a second section keeps its own small one.
        if (!heading.equals(foldTitle)) {
            final TextView h = headingView(heading);
            h.setTextSize(12f);
            foldContent.addView(h);
        }
        if (text.trim().isEmpty())
            return;
        foldContent.addView(bodyView(text));
        if (foldSummary == null) {
            final String first = text.split("\\n")[0];
            foldSummary = first.length() > 70 ? first.substring(0, 67) + "..." : first;
        }
    }

    /** Sections held open or shut, kept while ATAK runs so a redraw does not close what was opened. */
    private static final java.util.Set<String> OPEN_FOLDS = new java.util.HashSet<>();
    private LinearLayout foldContent;
    private String foldTitle, foldSummary;
    private TextView foldPill;

    /** The sections that follow go behind one pill, shut until it is tapped. */
    private void beginFold(String title) {
        foldTitle = title;
        foldSummary = null;
        foldPill = new TextView(view.getContext());
        foldContent = new LinearLayout(view.getContext());
        foldContent.setOrientation(LinearLayout.VERTICAL);
        foldContent.setPadding(dp(12), dp(4), dp(12), dp(8));
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        foldPill.setLayoutParams(lp);
        stylePill(foldPill);
        body.addView(foldPill);
        body.addView(foldContent);
    }

    private void stylePill(TextView pill) {
        pill.setMinHeight(dp(44));
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(14), dp(6), dp(14), dp(6));
        final android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xFF1E2430);
        bg.setCornerRadius(dp(22));
        bg.setStroke(dp(1), 0xFF3A4458);
        pill.setBackground(bg);
    }

    /** The Start and conditions bar: what the next run will use, in one line when shut. */
    private void paintSettings() {
        final TextView note = view.findViewById(R.id.start_note);
        String start = model.startNote();
        // A refused shape stays in orange until a start is placed.
        final boolean refused = start == null && model.startRefused() != null
                && model.perimeter() == null && model.startPoint() == null;
        if (refused)
            start = model.startRefused();
        note.setText(start == null ? "" : start);
        note.setTextColor(refused ? 0xFFFF9800 : 0xFFFFFFFF);
        note.setVisibility(start == null || fedsOpen ? View.GONE : View.VISIBLE);
        final String from = ((Button) view.findViewById(R.id.btn_place)).getText().toString()
                .replace("Start from: ", "");
        paintFold((TextView) view.findViewById(R.id.settings_pill), (LinearLayout) view.findViewById(R.id.settings_box),
                SETTINGS_FOLD, from + " \u00B7 " + FireModel.conditionsLabel(model.conditions()) + " \u00B7 "
                        + (model.horizonMinutes() >= 180 ? "3 hours" : "1 hour"));
    }

    private void endFold() {
        if (foldContent == null)
            return;
        final LinearLayout content = foldContent;
        final TextView pill = foldPill;
        final String title = foldTitle, summary = foldSummary;
        foldContent = null;
        paintFold(pill, content, title, summary);
        pill.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!OPEN_FOLDS.remove(title))
                    OPEN_FOLDS.add(title);
                if (RUNS_FOLD.equals(title))
                    prefs().edit().putBoolean(PREF_RUNS_OPEN, OPEN_FOLDS.contains(title)).apply();
                else if (TIMELINE_FOLD.equals(title))
                    prefs().edit().putBoolean(PREF_TIMELINE_OPEN, OPEN_FOLDS.contains(title)).apply();
                paintFold(pill, content, title, summary);
            }
        });
    }

    private void paintFold(TextView pill, LinearLayout content, String title, String summary) {
        final boolean open = OPEN_FOLDS.contains(title);
        final android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        sb.append(title.toUpperCase(Locale.US));
        sb.setSpan(new android.text.style.ForegroundColorSpan(0xFF00E5FF), 0, sb.length(), 0);
        sb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, sb.length(), 0);
        sb.setSpan(new android.text.style.AbsoluteSizeSpan(14, true), 0, sb.length(), 0);
        if (!open && summary != null && !summary.isEmpty()) {
            final int at = sb.length();
            sb.append("\n").append(summary);
            sb.setSpan(new android.text.style.ForegroundColorSpan(0xFFA0A8B8), at, sb.length(), 0);
            sb.setSpan(new android.text.style.AbsoluteSizeSpan(13, true), at, sb.length(), 0);
        }
        pill.setText(sb);
        pill.setCompoundDrawablesWithIntrinsicBounds(0, 0, open ? android.R.drawable.arrow_up_float
                : android.R.drawable.arrow_down_float, 0);
        content.setVisibility(open ? View.VISIBLE : View.GONE);
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
        if (!fedsOpen)
            return;
        try {
            if (v)
                getMapView().addOnMapMovedListener(fedsMoved);
            else {
                getMapView().removeOnMapMovedListener(fedsMoved);
                fedsMain.removeCallbacks(fedsSettled);
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onDropDownSizeChanged(double width, double height) {
    }

    @Override
    public void onDropDownClose() {
    }

    @Override
    protected void disposeImpl() {
        closeFeds();
        fedsMain.removeCallbacks(runsSettled);
        try {
            getMapView().removeOnMapMovedListener(runsMoved);
        } catch (Exception ignored) {
        }
        fedsWorker.shutdownNow();
        placeWorker.shutdownNow();
    }
}

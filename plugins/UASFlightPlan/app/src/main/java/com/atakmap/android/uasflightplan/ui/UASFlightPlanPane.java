package com.atakmap.android.uasflightplan.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.android.uasflightplan.data.Units;
import com.atakmap.android.uasflightplan.map.IslandOverlay;
import com.atakmap.android.uasflightplan.map.LaunchPoint;
import com.atakmap.android.uasflightplan.map.ObstacleOverlay;
import com.atakmap.android.uasflightplan.obstacles.Obstacle;
import com.atakmap.android.uasflightplan.obstacles.ObstacleManager;
import com.atakmap.android.uasflightplan.plugin.R;
import com.atakmap.android.uasflightplan.terrain.TerrainGrid;
import com.atakmap.coremap.maps.coords.GeoPoint;

import java.util.ArrayList;
import java.util.Locale;

/**
 * The pane, in the takwerx shape: a pinned status line, a main row of the three
 * things used in the field (Islands on the map, Settings, Launch point), the two
 * numbers the pilot came for under it, and a list under that. Everything set once
 * and left (the ceiling, the circle, the map key) is on the Settings page, which
 * takes the list's place while it is open.
 *
 * <p>The pane drives {@link IslandOverlay} but never owns it. Closing the pane, or
 * having ATAK close it, leaves the overlay exactly where it was. Reopening reads the
 * current state back out of the overlay and the preferences rather than from a
 * field that went away with the view.
 */
public final class UASFlightPlanPane implements IslandOverlay.Listener,
        LaunchPoint.Callback, ObstacleManager.Listener {

    /** Proposed ceiling, feet above the launch ground, until the pilot sets one. */
    static final double DEFAULT_ABOVE_LAUNCH_FT = 1000d;
    /** One statute mile. */
    static final double DEFAULT_RADIUS_M = 1609.344d;

    private static final String PREF_CEILING_FT = "uasflightplan.ceilingFt";
    private static final String PREF_RADIUS_M = "uasflightplan.radiusM";
    private static final String PREF_FOLD = "uasflightplan.fold.";

    private final View root;
    private final Context pluginContext;
    private final Context host;
    private final MapView mapView;
    private final IslandOverlay overlay;
    private final ObstacleManager obstacles;
    private final LaunchPoint launch;
    private final SharedPreferences prefs;
    private final ObstacleAdapter adapter;
    private final TextView listHeading;
    private final Fold obstaclesFold;
    private final Fold tallerFold;
    private final Fold typesFold;
    private final LinearLayout tallerTiles;
    private final LinearLayout typeTiles;
    private final View detailsPage;
    private Obstacle showing;

    private final TextView status;
    private final ListView list;
    private final View settingsPage;
    private final Button btnIslands;
    private final Button btnLaunch;
    private final TextView ground;
    private final TextView ceilingLine;
    private final Fold ceilingFold;
    private final Fold circleFold;
    private final Fold keyFold;
    private final LinearLayout ceilingSteps;
    private final Button btnCeilingAbove;
    private final LinearLayout circleTiles;
    private final LinearLayout keyBody;

    /** A sample or a paint is in flight. */
    private boolean working;
    private String lastFail;
    /** Said once, after the plugin picked the ceiling for the pilot. */
    private String proposedNote;

    /** One Settings row: a head naming the setting and its value, an arrow, a body. */
    private final class Fold {
        final Button head;
        final ImageButton chevron;
        final View body;
        final String pref;
        boolean open;

        Fold(View page, int headId, int chevronId, int bodyId, String pref) {
            this(page, headId, chevronId, bodyId, pref, true);
        }

        /** @param headOpens false for a row whose head is a switch: only the arrow opens it */
        Fold(View page, int headId, int chevronId, int bodyId, String pref, boolean headOpens) {
            head = page.findViewById(headId);
            chevron = page.findViewById(chevronId);
            body = page.findViewById(bodyId);
            this.pref = pref;
            open = prefs.getBoolean(pref, false);
            final View.OnClickListener flip = new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    open = !open;
                    prefs.edit().putBoolean(Fold.this.pref, open).apply();
                    show();
                }
            };
            if (headOpens)
                head.setOnClickListener(flip);
            chevron.setOnClickListener(flip);
            show();
        }

        void label(String name, String value) {
            head.setText(value == null ? name : name + ": " + value);
        }

        void show() {
            chevron.setRotation(open ? 180f : 0f);
            body.setVisibility(open ? View.VISIBLE : View.GONE);
        }
    }

    public UASFlightPlanPane(View root, Context pluginContext, MapView mapView,
            IslandOverlay overlay, ObstacleManager obstacles) {
        this.root = root;
        this.pluginContext = pluginContext;
        this.host = mapView.getContext();
        this.mapView = mapView;
        this.overlay = overlay;
        this.obstacles = obstacles;
        this.launch = new LaunchPoint(mapView, pluginContext, this);
        this.prefs = PreferenceManager.getDefaultSharedPreferences(host);

        status = root.findViewById(R.id.status);
        list = root.findViewById(R.id.list);
        // The controls are the list's header, so the whole pane is one scroller.
        final View header = PluginLayoutInflater.inflate(pluginContext,
                R.layout.controls_header, null);
        list.addHeaderView(header, null, false);
        adapter = new ObstacleAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                final int i = position - list.getHeaderViewsCount();
                if (i >= 0 && i < adapter.getCount())
                    obstacles.panTo(adapter.getItem(i));
            }
        });
        listHeading = header.findViewById(R.id.list_heading);

        btnIslands = header.findViewById(R.id.btn_islands);
        btnLaunch = header.findViewById(R.id.btn_launch);
        ground = header.findViewById(R.id.ground);
        ceilingLine = header.findViewById(R.id.ceiling);

        // Everything set once and left lives on the Settings page, which takes the
        // list's place while it is open.
        final View settings = PluginLayoutInflater.inflate(pluginContext,
                R.layout.settings_controls, null);
        ((ViewGroup) root.findViewById(R.id.settings_container)).addView(settings);
        settingsPage = root.findViewById(R.id.settings_page);
        header.findViewById(R.id.btn_settings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSettings(true);
            }
        });
        root.findViewById(R.id.btn_settings_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSettings(false);
            }
        });
        detailsPage = root.findViewById(R.id.details_page);
        root.findViewById(R.id.btn_details_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMain();
            }
        });
        root.findViewById(R.id.btn_details_zoom).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (showing != null)
                    obstacles.panTo(showing);
            }
        });

        obstaclesFold = new Fold(settings, R.id.fold_obstacles_head, R.id.fold_obstacles_chev,
                R.id.fold_obstacles_body, PREF_FOLD + "obstacles", false);
        obstaclesFold.head.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                obstacles.setEnabled(!obstacles.isEnabled());
            }
        });
        tallerFold = new Fold(settings, R.id.fold_taller_head, R.id.fold_taller_chev,
                R.id.fold_taller_body, PREF_FOLD + "taller");
        typesFold = new Fold(settings, R.id.fold_types_head, R.id.fold_types_chev,
                R.id.fold_types_body, PREF_FOLD + "types");
        tallerTiles = settings.findViewById(R.id.taller_tiles);
        typeTiles = settings.findViewById(R.id.type_tiles);
        settings.findViewById(R.id.btn_types_all_on).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                obstacles.setAllGroups(true);
            }
        });
        settings.findViewById(R.id.btn_types_all_off).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                obstacles.setAllGroups(false);
            }
        });
        ceilingFold = new Fold(settings, R.id.fold_ceiling_head, R.id.fold_ceiling_chev,
                R.id.fold_ceiling_body, PREF_FOLD + "ceiling");
        circleFold = new Fold(settings, R.id.fold_circle_head, R.id.fold_circle_chev,
                R.id.fold_circle_body, PREF_FOLD + "circle");
        keyFold = new Fold(settings, R.id.fold_key_head, R.id.fold_key_chev,
                R.id.fold_key_body, PREF_FOLD + "key");
        ceilingSteps = settings.findViewById(R.id.ceiling_steps);
        btnCeilingAbove = settings.findViewById(R.id.btn_ceiling_above);
        circleTiles = settings.findViewById(R.id.circle_tiles);
        keyBody = settings.findViewById(R.id.fold_key_body);

        wire(settings);
        buildCeilingSteps();
        buildKey();

        // The overlay keeps the ceiling it paints with; hand it the saved one so a
        // launch point placed before Settings is ever opened paints at once.
        final Double c = ceilingM();
        if (c != null)
            overlay.setCeiling(c);

        final Double cft = ceilingFt();
        if (cft != null)
            obstacles.setCeiling(cft);
        overlay.setListener(this);
        obstacles.setListener(this);
        syncAll();
    }

    /** One row per obstacle, nearest first: the kind and height, then what else is known. */
    private final class ObstacleAdapter extends ArrayAdapter<Obstacle> {
        ObstacleAdapter() {
            super(host, 0, new ArrayList<Obstacle>());
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final View row = convertView != null ? convertView
                    : PluginLayoutInflater.inflate(pluginContext, R.layout.obstacle_row, null);
            final Obstacle o = getItem(position);
            final double ceilingFt = obstacles.getCeilingMslFt();
            final boolean above = !Double.isNaN(ceilingFt) && o.aboveCeiling(ceilingFt);
            row.findViewById(R.id.bar).setBackgroundColor(
                    above ? ObstacleOverlay.ABOVE_ARGB : ObstacleOverlay.BELOW_ARGB);
            ((TextView) row.findViewById(R.id.kind)).setText(String.format(Locale.US,
                    "%s, %s AGL", o.kind(), Units.height(Units.feetToMeters(o.aglFt))));
            final StringBuilder d = new StringBuilder();
            if (!Double.isNaN(o.amslFt))
                d.append(pluginContext.getString(R.string.top_at,
                        Units.altitudeMsl(Units.feetToMeters(o.amslFt))));
            if (above)
                d.append(d.length() > 0 ? ", " : "")
                        .append(pluginContext.getString(R.string.row_above_ceiling));
            d.append(d.length() > 0 ? ", " : "").append(o.lightingWords());
            d.append(", ").append(pluginContext.getString(
                    o.isVerified() ? R.string.row_verified : R.string.row_unverified));
            if (!o.city.isEmpty())
                d.append(", ").append(o.city);
            ((TextView) row.findViewById(R.id.detail)).setText(d.toString());
            ((TextView) row.findViewById(R.id.distance)).setText(
                    o.distanceAndPoint(Units.distance(o.distanceM)));
            // A row holding a Button swallows the list's own item click, so the row
            // and the button each get their own: the row goes there, Details opens it.
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    obstacles.panTo(o);
                }
            });
            row.findViewById(R.id.btn_row_details).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showDetails(o);
                }
            });
            return row;
        }
    }

    /**
     * One obstacle's page: the one way in, from the list's Details button and from a
     * tap on the map alike. Every field in words, nothing a pilot has to decode.
     */
    public void showDetails(Obstacle o) {
        if (o == null)
            return;
        showing = o;
        ((TextView) root.findViewById(R.id.details_title)).setText(String.format(Locale.US,
                "%s, %s AGL", o.kind(), Units.height(Units.feetToMeters(o.aglFt))));
        ((TextView) root.findViewById(R.id.details_subtitle)).setText(
                pluginContext.getString(R.string.details_source));
        final StringBuilder sb = new StringBuilder();
        sb.append(pluginContext.getString(R.string.d_height,
                Units.height(Units.feetToMeters(o.aglFt)))).append('\n');
        if (!Double.isNaN(o.amslFt))
            sb.append(pluginContext.getString(R.string.d_top,
                    Units.altitudeMsl(Units.feetToMeters(o.amslFt)))).append('\n');
        final Double cft = ceilingFt();
        if (cft == null || Double.isNaN(o.amslFt)) {
            sb.append(pluginContext.getString(R.string.d_no_ceiling)).append('\n');
        } else {
            final double gapM = Units.feetToMeters(Math.abs(cft - o.amslFt));
            sb.append(pluginContext.getString(
                    o.aboveCeiling(cft) ? R.string.d_above : R.string.d_under,
                    Units.altitudeMsl(Units.feetToMeters(cft)), Units.height(gapM)))
                    .append('\n');
        }
        sb.append('\n');
        sb.append(pluginContext.getString(R.string.d_lighting, o.lightingWords())).append('\n');
        sb.append(pluginContext.getString(R.string.d_verified, pluginContext.getString(
                o.isVerified() ? R.string.yes : R.string.no))).append('\n');
        if (o.quantity > 1)
            sb.append(pluginContext.getString(R.string.d_quantity, o.quantity)).append('\n');
        if (!o.city.isEmpty())
            sb.append(pluginContext.getString(R.string.d_city,
                    o.city + (o.state.isEmpty() ? "" : ", " + o.state))).append('\n');
        sb.append(pluginContext.getString(obstacles.measuringFromSelf()
                ? R.string.d_distance_you : R.string.d_distance, Units.distance(o.distanceM)))
                .append('\n');
        if (!Double.isNaN(o.bearingDeg))
            sb.append(pluginContext.getString(R.string.d_bearing, o.compassPoint(),
                    Math.round(o.bearingDeg))).append('\n');
        if (!o.oas.isEmpty())
            sb.append(pluginContext.getString(R.string.d_faa, o.oas)).append('\n');
        ((TextView) root.findViewById(R.id.details_fields)).setText(sb.toString().trim());
        settingsPage.setVisibility(View.GONE);
        list.setVisibility(View.GONE);
        detailsPage.setVisibility(View.VISIBLE);
    }

    /** The main screen back: list visible, every other page gone. */
    private void showMain() {
        detailsPage.setVisibility(View.GONE);
        settingsPage.setVisibility(View.GONE);
        list.setVisibility(View.VISIBLE);
    }

    /* ----- wiring ----- */

    private void wire(View settings) {
        btnIslands.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                overlay.setVisible(!overlay.isVisible());
                syncAll();
            }
        });

        btnLaunch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (launch.isPicking()) {
                    launch.cancelPick();
                    syncAll();
                } else {
                    launchDialog();
                }
            }
        });

        settings.findViewById(R.id.btn_ceiling_type).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                typeCeiling();
            }
        });

        btnCeilingAbove.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final double g = overlay.getGroundMslM();
                if (Double.isNaN(g))
                    return;
                setCeilingFt(proposedCeilingFt(g));
            }
        });
    }

    /**
     * The launch point picker: a small popup of the plugin's own buttons, never a
     * Spinner, built on the host context so it can show at all.
     */
    private void launchDialog() {
        final float dp = pluginContext.getResources().getDisplayMetrics().density;
        final LinearLayout box = new LinearLayout(host);
        box.setOrientation(LinearLayout.VERTICAL);
        final int pad = (int) (12 * dp);
        box.setPadding(pad, pad, pad, 0);

        final Button tap = tile(pluginContext.getString(R.string.tap_map), dp);
        final Button here = tile(pluginContext.getString(R.string.use_position), dp);
        final Button clear = tile(pluginContext.getString(R.string.clear_launch), dp);
        box.addView(tap);
        box.addView(here);
        box.addView(clear);
        final GeoPoint self = selfPosition();
        if (self == null) {
            here.setEnabled(false);
            here.setAlpha(0.5f);
        }
        if (launch.getPoint() == null) {
            clear.setEnabled(false);
            clear.setAlpha(0.5f);
        }

        // Strings, never ids: a dialog on the host context resolves an id in
        // ATAK's own resources and shows some other plugin's or ATAK's text.
        final AlertDialog dlg = new AlertDialog.Builder(host)
                .setTitle(pluginContext.getString(R.string.launch_point))
                .setView(box)
                .setNegativeButton(pluginContext.getString(R.string.cancel), null)
                .create();
        tap.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dlg.dismiss();
                launch.startPick();
                syncAll();
            }
        });
        here.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dlg.dismiss();
                onPicked(self);
            }
        });
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dlg.dismiss();
                clearAll();
            }
        });
        dlg.show();
    }

    private Button tile(String text, float dp) {
        final Button b = new Button(pluginContext, null, 0, R.style.TakwerxButton);
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (6 * dp);
        b.setLayoutParams(lp);
        b.setText(text);
        return b;
    }

    private void clearAll() {
        if (launch.isPicking())
            launch.cancelPick();
        launch.clear();
        lastFail = null;
        proposedNote = null;
        working = false;
        overlay.clear();
        obstacles.clear();
    }

    /* ----- the ceiling ----- */

    /** The ceiling in feet MSL, or null before one is set. */
    private Double ceilingFt() {
        final float v = prefs.getFloat(PREF_CEILING_FT, Float.NaN);
        return Float.isNaN(v) ? null : (double) v;
    }

    private Double ceilingM() {
        final Double ft = ceilingFt();
        return ft == null ? null : Units.feetToMeters(ft);
    }

    private void setCeilingFt(double ft) {
        prefs.edit().putFloat(PREF_CEILING_FT, (float) ft).apply();
        proposedNote = null;
        overlay.setCeiling(Units.feetToMeters(ft));
        obstacles.setCeiling(ft);
        syncAll();
    }

    /** The launch ground plus the default, rounded up to the next hundred feet. */
    private static double proposedCeilingFt(double groundMslM) {
        final double groundFt = Units.metersToFeet(groundMslM);
        return Math.ceil((groundFt + DEFAULT_ABOVE_LAUNCH_FT) / 100d) * 100d;
    }

    /**
     * Step tiles in the unit ATAK shows altitudes in: hundreds and five hundreds of
     * feet, or thirties and hundred-fifties of meters.
     */
    private void buildCeilingSteps() {
        ceilingSteps.removeAllViews();
        final boolean feet = Units.altitudeInFeet();
        final double[] steps = feet
                ? new double[] { -500d, -100d, 100d, 500d }
                : new double[] { -150d, -30d, 30d, 150d };
        final float dp = pluginContext.getResources().getDisplayMetrics().density;
        for (final double step : steps) {
            final Button b = new Button(pluginContext, null, 0, R.style.TakwerxButton);
            b.setText(String.format(Locale.US, "%+,.0f", step));
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = (int) (4 * dp);
            b.setLayoutParams(lp);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    final Double c = ceilingFt();
                    if (c == null)
                        return;
                    final double stepFt = feet ? step : Units.metersToFeet(step);
                    setCeilingFt(Math.max(0d, c + stepFt));
                }
            });
            ceilingSteps.addView(b);
        }
    }

    private void typeCeiling() {
        final EditText input = new EditText(host);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setSingleLine(true);
        final Double c = ceilingFt();
        if (c != null)
            input.setText(String.format(Locale.US, "%.0f",
                    Units.toAltitudeUnit(Units.feetToMeters(c))));
        new AlertDialog.Builder(host)
                .setTitle(pluginContext.getString(R.string.ceiling_prompt, Units.altitudeUnit()))
                .setView(input)
                .setNegativeButton(pluginContext.getString(R.string.cancel), null)
                .setPositiveButton(pluginContext.getString(R.string.ok),
                        new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        try {
                            final double v = Double.parseDouble(input.getText().toString().trim());
                            final double ft = Units.altitudeInFeet() ? v : Units.metersToFeet(v);
                            setCeilingFt(Math.max(0d, ft));
                        } catch (NumberFormatException e) {
                            // Nothing typed; the ceiling stays as it was.
                        }
                    }
                })
                .show();
        input.requestFocus();
    }

    /* ----- the circle ----- */

    private double radiusM() {
        return prefs.getFloat(PREF_RADIUS_M, (float) DEFAULT_RADIUS_M);
    }

    /**
     * Presets in the operator's large unit, the chosen one in green. Rebuilt on
     * every sync because the unit can change in ATAK's settings while the pane is
     * open.
     */
    private void buildCircleTiles() {
        circleTiles.removeAllViews();
        final double current = radiusM();
        final float dp = pluginContext.getResources().getDisplayMetrics().density;
        for (final double m : Units.circlePresetsM()) {
            final Button b = new Button(pluginContext, null, 0, R.style.TakwerxButton);
            b.setText(Units.largeUnit(m));
            b.setTextColor(Math.abs(m - current) < 1d
                    ? pluginContext.getResources().getColor(R.color.state_on)
                    : 0xFFFFFFFF);
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = (int) (4 * dp);
            b.setLayoutParams(lp);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    prefs.edit().putFloat(PREF_RADIUS_M, (float) m).apply();
                    final GeoPoint p = launch.getPoint();
                    if (p != null) {
                        working = true;
                        lastFail = null;
                        overlay.computeFor(p, m);
                        obstacles.load(p, m);
                    }
                    syncAll();
                }
            });
            circleTiles.addView(b);
        }
    }

    /* ----- the obstacle filters ----- */

    /** The height floor presets, the chosen one in green. */
    private void buildTallerTiles() {
        tallerTiles.removeAllViews();
        final double current = obstacles.minAglFt();
        final float dp = pluginContext.getResources().getDisplayMetrics().density;
        for (final double ft : ObstacleManager.MIN_AGL_PRESETS_FT) {
            final Button b = new Button(pluginContext, null, 0, R.style.TakwerxButton);
            b.setText(ft <= 0d ? pluginContext.getString(R.string.taller_any)
                    : Units.height(Units.feetToMeters(ft)));
            b.setTextColor(Math.abs(ft - current) < 0.5d
                    ? pluginContext.getResources().getColor(R.color.state_on)
                    : 0xFFFFFFFF);
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = (int) (4 * dp);
            b.setLayoutParams(lp);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    obstacles.setMinAglFt(ft);
                }
            });
            tallerTiles.addView(b);
        }
    }

    /**
     * One tile per kind, two across: the name and the count the FAA sent on one
     * line, ON or OFF in its color on the next.
     */
    private void buildTypeTiles() {
        typeTiles.removeAllViews();
        final float dp = pluginContext.getResources().getDisplayMetrics().density;
        LinearLayout row = null;
        for (int i = 0; i < Obstacle.GROUPS.length; i++) {
            final String g = Obstacle.GROUPS[i];
            if (i % 2 == 0) {
                row = new LinearLayout(host);
                row.setOrientation(LinearLayout.HORIZONTAL);
                final LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                rl.topMargin = (int) (4 * dp);
                typeTiles.addView(row, rl);
            }
            final boolean on = obstacles.isGroupOn(g);
            final int n = obstacles.countInGroup(g);
            final Button b = new Button(pluginContext, null, 0, R.style.TakwerxButton);
            b.setSingleLine(false);
            b.setMaxLines(2);
            b.setText(Obstacle.groupName(g) + (n > 0 ? " (" + n + ")" : "")
                    + "\n" + (on ? "ON" : "OFF"));
            b.setTextColor(pluginContext.getResources().getColor(
                    on ? R.color.state_on : R.color.state_off));
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = (int) (4 * dp);
            b.setLayoutParams(lp);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    obstacles.setGroupOn(g, !obstacles.isGroupOn(g));
                }
            });
            row.addView(b);
        }
        // An odd last row gets a spacer so its one tile is not full width.
        if (row != null && row.getChildCount() == 1) {
            final View spacer = new View(host);
            row.addView(spacer, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
    }

    /* ----- the map key ----- */

    /** Built from the overlay's own colors, so the key cannot drift from the map. */
    private void buildKey() {
        keyBody.removeAllViews();
        keyRow(IslandOverlay.ISLAND_ARGB, R.string.key_island);
        keyRow(IslandOverlay.WATER_ARGB, R.string.key_water);
        keyRow(IslandOverlay.EDGE_ARGB, R.string.key_edge);
        keyRow(ObstacleOverlay.ABOVE_ARGB, R.string.key_obstacle_above);
        keyRow(ObstacleOverlay.BELOW_ARGB, R.string.key_obstacle_below);
    }

    private void keyRow(int argb, int textId) {
        final float dp = pluginContext.getResources().getDisplayMetrics().density;
        final LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        final View swatch = new View(host);
        // Opaque: the operator is matching hue, not transparency.
        swatch.setBackgroundColor(0xFF000000 | (argb & 0x00FFFFFF));
        final LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(
                (int) (18 * dp), (int) (18 * dp));
        sl.rightMargin = (int) (10 * dp);
        sl.topMargin = (int) (4 * dp);
        sl.bottomMargin = (int) (4 * dp);
        row.addView(swatch, sl);
        final TextView t = new TextView(host);
        // A view built on the host context reads ids from ATAK's resources.
        t.setText(pluginContext.getString(textId));
        t.setTextSize(13);
        t.setTextColor(0xFFFFFFFF);
        row.addView(t, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        keyBody.addView(row);
    }

    /** A launch point handed in from outside the pane: placed as if tapped. */
    public void placeLaunchPoint(GeoPoint point) {
        if (launch.isPicking())
            launch.cancelPick();
        onPicked(point);
        mapView.getMapController().panTo(point, true);
    }

    /* ----- LaunchPoint.Callback ----- */

    @Override
    public void onPicked(GeoPoint point) {
        launch.place(point);
        working = true;
        lastFail = null;
        overlay.computeFor(point, radiusM());
        obstacles.load(point, radiusM());
        syncAll();
    }

    @Override
    public void onPickCancelled() {
        syncAll();
    }

    /* ----- IslandOverlay.Listener ----- */

    @Override
    public void onSampled(TerrainGrid grid, double groundMslM) {
        if (ceilingFt() == null) {
            // No ceiling yet: propose one from the ground and say so once. The
            // overlay paints as soon as it is set.
            final double ft = proposedCeilingFt(groundMslM);
            prefs.edit().putFloat(PREF_CEILING_FT, (float) ft).apply();
            proposedNote = pluginContext.getString(R.string.ceiling_proposed,
                    Units.height(Units.feetToMeters(DEFAULT_ABOVE_LAUNCH_FT)));
            overlay.setCeiling(Units.feetToMeters(ft));
            obstacles.setCeiling(ft);
        }
        syncAll();
    }

    /* ----- ObstacleManager.Listener ----- */

    @Override
    public void onObstaclesChanged() {
        syncAll();
    }

    @Override
    public void onPainted(TerrainGrid grid, double ceilingMslM, int islandCells) {
        working = false;
        syncAll();
    }

    @Override
    public void onFailed(String reason) {
        working = false;
        lastFail = reason;
        syncAll();
    }

    @Override
    public void onCleared() {
        working = false;
        syncAll();
    }

    /* ----- reading the state into the controls ----- */

    /** Re-reads everything the pane shows. Main thread. */
    private void syncAll() {
        status.setText(statusLine());

        final TerrainGrid grid = overlay.getGrid();
        final double g = overlay.getGroundMslM();
        ground.setText(grid == null || Double.isNaN(g)
                ? pluginContext.getString(R.string.ground_none)
                : pluginContext.getString(R.string.ground_here, Units.altitudeMsl(g)));

        final Double c = ceilingM();
        if (grid != null && c != null && !Double.isNaN(g)) {
            ceilingLine.setText(pluginContext.getString(R.string.ceiling_line,
                    Units.altitudeMsl(c), Units.height(c - g)));
            ceilingLine.setVisibility(View.VISIBLE);
        } else {
            ceilingLine.setVisibility(View.GONE);
        }

        syncIslandsSwitch();
        btnLaunch.setText(launch.isPicking() ? R.string.cancel : R.string.launch_point);

        // The list and its heading: the obstacles the manager holds, nearest first.
        adapter.clear();
        adapter.addAll(obstacles.getObstacles());
        adapter.notifyDataSetChanged();
        if (adapter.getCount() > 0) {
            listHeading.setText(pluginContext.getString(obstacles.measuringFromSelf()
                    ? R.string.heading_obstacles_from_you
                    : R.string.heading_obstacles_from_launch, adapter.getCount()));
            listHeading.setVisibility(View.VISIBLE);
        } else {
            listHeading.setVisibility(View.GONE);
        }
        final String thing = pluginContext.getString(R.string.app_obstacles);
        final boolean on = obstacles.isEnabled();
        obstaclesFold.head.setText(thing + (on ? " ON" : " OFF"));
        obstaclesFold.head.setTextColor(pluginContext.getResources().getColor(
                on ? R.color.state_on : R.color.state_off));
        final double floor = obstacles.minAglFt();
        tallerFold.label(pluginContext.getString(R.string.taller_than),
                floor <= 0d ? pluginContext.getString(R.string.taller_any)
                        : Units.height(Units.feetToMeters(floor)));
        typesFold.label(pluginContext.getString(R.string.types),
                pluginContext.getString(R.string.types_value, obstacles.groupsOn(),
                        Obstacle.GROUPS.length));
        buildTallerTiles();
        buildTypeTiles();

        ceilingFold.label(pluginContext.getString(R.string.ceiling),
                c == null ? null : Units.altitudeMsl(c));
        circleFold.label(pluginContext.getString(R.string.circle), Units.largeUnit(radiusM()));
        btnCeilingAbove.setText(pluginContext.getString(R.string.ceiling_above_launch,
                Units.height(Units.feetToMeters(DEFAULT_ABOVE_LAUNCH_FT))));
        final boolean haveGround = !Double.isNaN(g);
        btnCeilingAbove.setEnabled(haveGround);
        btnCeilingAbove.setAlpha(haveGround ? 1f : 0.5f);
        buildCircleTiles();
    }

    /**
     * ON in green, OFF in red, on a plain dark button: the same switch every takwerx
     * plugin uses. The face never changes color; only the word does.
     */
    private void syncIslandsSwitch() {
        final String thing = pluginContext.getString(R.string.app_islands);
        if (working) {
            btnIslands.setText(R.string.loading);
            btnIslands.setTextColor(pluginContext.getResources().getColor(R.color.working));
            btnIslands.setEnabled(false);
            btnIslands.setAlpha(1f);
            return;
        }
        final boolean has = overlay.getLayer().hasField();
        final boolean on = overlay.isVisible();
        btnIslands.setText(thing + (on ? " ON" : " OFF"));
        btnIslands.setTextColor(pluginContext.getResources().getColor(
                on ? R.color.state_on : R.color.state_off));
        btnIslands.setEnabled(has);
        btnIslands.setAlpha(has ? 1f : 0.5f);
    }

    /**
     * Says what is happening and what is not shown. Every reason something is
     * missing gets its own words, and each empty state says what to do about it.
     */
    private String statusLine() {
        if (launch.isPicking())
            return pluginContext.getString(R.string.status_picking);
        if (working)
            return pluginContext.getString(R.string.status_reading, Units.largeUnit(radiusM()));
        if (lastFail != null)
            return lastFail;
        final TerrainGrid grid = overlay.getGrid();
        if (grid == null)
            return pluginContext.getString(R.string.status_idle);

        final StringBuilder sb = new StringBuilder();
        final Double c = overlay.getCeilingMslM();
        if (c != null) {
            final int known = Math.max(1, grid.knownCells());
            final int islands = grid.islandCells(c);
            if (islands == 0)
                sb.append(pluginContext.getString(R.string.all_under_water));
            else
                sb.append(String.format(Locale.US,
                        pluginContext.getString(R.string.islands_share),
                        Math.max(1d, 100d * islands / known)));
            final double hi = grid.highestMsl();
            if (!Double.isNaN(hi))
                sb.append(' ').append(pluginContext.getString(R.string.highest_ground,
                        Units.altitudeMsl(hi)));
        }
        sb.append('\n').append(pluginContext.getString(R.string.terrain_detail,
                Units.largeUnit(grid.radiusM), Units.height(grid.cellMeters)));
        if (grid.unknownCells > 0)
            sb.append('\n').append(String.format(Locale.US,
                    pluginContext.getString(R.string.missing_share),
                    Math.max(1d, 100d * grid.unknownCells / Math.max(1, grid.circleCells))));
        if (!overlay.isVisible())
            sb.append('\n').append(pluginContext.getString(R.string.status_map_off));
        final String ob = obstacles.statusLine(launch.getPoint() != null);
        if (ob != null)
            sb.append('\n').append(ob);
        if (proposedNote != null)
            sb.append('\n').append(proposedNote);
        return sb.toString();
    }

    /** The settings page in place of the list, or the list back. */
    private void showSettings(boolean open) {
        detailsPage.setVisibility(View.GONE);
        settingsPage.setVisibility(open ? View.VISIBLE : View.GONE);
        list.setVisibility(open ? View.GONE : View.VISIBLE);
    }

    private GeoPoint selfPosition() {
        final Marker self = mapView.getSelfMarker();
        final GeoPoint p = self == null ? null : self.getPoint();
        if (p == null || !p.isValid()
                || (p.getLatitude() == 0 && p.getLongitude() == 0))
            return null;
        return new GeoPoint(p.getLatitude(), p.getLongitude());
    }

    /* ----- lifecycle ----- */

    /**
     * The phone moves without the map moving, so the distances are re-measured
     * every few seconds while the pane is up.
     */
    private static final long RESORT_MS = 5000L;
    private boolean shown;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!shown)
                return;
            if (!obstacles.getObstacles().isEmpty()) {
                obstacles.resort();
                syncAll();
            }
            root.postDelayed(this, RESORT_MS);
        }
    };

    /** The pane was shown. It opens on the main screen every time. */
    public void onPaneShown() {
        showMain();
        syncAll();
        shown = true;
        root.removeCallbacks(tick);
        root.postDelayed(tick, RESORT_MS);
    }

    /**
     * The pane was closed by the user. Cancels a pick in progress and leaves
     * everything else alone: the overlay must survive it, a half-finished pick
     * must not, because {@link LaunchPoint} holds ATAK's map event listeners while
     * it waits for the tap.
     */
    public void onPaneClosed() {
        shown = false;
        root.removeCallbacks(tick);
        if (launch.isPicking()) {
            launch.cancelPick();
            syncAll();
        }
    }

    /** Called when the plugin itself is stopping. */
    public void onClosed() {
        onPaneClosed();
        launch.dispose();
        overlay.setListener(null);
        obstacles.setListener(null);
    }

    public View getRoot() {
        return root;
    }
}

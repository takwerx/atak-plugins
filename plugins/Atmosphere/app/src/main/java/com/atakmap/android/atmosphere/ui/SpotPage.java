package com.atakmap.android.atmosphere.ui;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.atakmap.android.atmosphere.compat.MapCompat;
import com.atakmap.android.atmosphere.data.Spot;
import com.atakmap.android.atmosphere.net.EgressPolicy;
import com.atakmap.android.atmosphere.net.Http;
import com.atakmap.android.atmosphere.plugin.R;
import com.atakmap.android.atmosphere.units.UnitSystem;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoBounds;
import com.atakmap.coremap.maps.coords.GeoPoint;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The spot forecasts page: every open NWS spot request, narrowed on the phone, and the
 * forecast NWS issued for the one tapped. See {@link Spot} for where each piece comes
 * from and why the filtering cannot be done by the server.
 *
 * <p>Its own class rather than more of the pane, which is long enough already; the
 * pane builds it, hands it the page slot and tells it when it is looked at.
 *
 * <p>Nothing is fetched until the operator allows it, and then only when the page is
 * looked at and the list is more than a few minutes old. The operator's position never
 * leaves the phone: the whole country's list comes down and "near me" is worked out
 * here.
 */
public final class SpotPage {

    private static final String TAG = "AtmosphereSpot";
    /**
     * "spotforecasts", not "spot": the first build asked to be allowed to reach
     * spot.weather.gov, and the list now comes from another server. Being allowed is
     * per server by name, so the operator is asked again rather than having the old
     * yes carried over to a host it did not name.
     */
    public static final String LAYER_ID = "spotforecasts";

    private static final String PREF_FILTER = "weather.spot.filter";
    private static final String PREF_STATE = "weather.spot.state";
    private static final String PREF_REGION = "weather.spot.region";
    private static final String PREF_RADIUS = "weather.spot.radius";

    /** A list older than this is fetched again when the page is looked at. */
    private static final long STALE_MS = 5 * 60 * 1000L;
    /** Rows drawn at most; the status line says when more matched. */
    private static final int MAX_ROWS = 50;
    /** The distance presets, in the large unit of the operator's system. */
    private static final int[] RADII = { 25, 50, 100, 250 };

    private enum Filter { ALL, NEAR, MAP, STATE, REGION }

    /** What the page needs from the pane around it. */
    public interface Host {
        /** The point the pane is reading, for a request. */
        GeoPoint point();

        UnitSystem units();
    }

    private final Context pluginContext;
    private final MapView mapView;
    private final EgressPolicy egress;
    private final Host host;
    private final View root;
    private final View gate, browse, detail;
    private final Button all, near, onMap, state, region;
    private final TextView status;
    private final LinearLayout list;
    private final TextView detailTitle, detailFacts, detailText;
    private final Button openBrowser;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private List<Spot.Request> requests = new ArrayList<>();
    /** The state outlines, read from the plugin's assets once, on the worker. */
    private com.atakmap.android.atmosphere.data.States states;
    private long fetchedAt;
    private boolean inFlight;
    /** Bumped per list fetch and per forecast shown, separately, so neither cancels the other. */
    private int listGeneration, detailGeneration;
    private Filter filter = Filter.ALL;
    private String stateCode = "";
    private String regionCode = "";
    private int radiusIndex = 1;
    /** The request whose details are showing, or null for the list. */
    private Spot.Request showing;

    public SpotPage(Context pluginContext, MapView mapView, EgressPolicy egress, Host host) {
        this.pluginContext = pluginContext;
        this.mapView = mapView;
        this.egress = egress;
        this.host = host;
        root = LayoutInflater.from(pluginContext).inflate(R.layout.page_spot, null);
        gate = root.findViewById(R.id.spot_gate);
        browse = root.findViewById(R.id.spot_browse);
        detail = root.findViewById(R.id.spot_detail);
        all = root.findViewById(R.id.spot_all);
        near = root.findViewById(R.id.spot_near);
        onMap = root.findViewById(R.id.spot_map);
        state = root.findViewById(R.id.spot_state);
        region = root.findViewById(R.id.spot_region);
        status = root.findViewById(R.id.spot_status);
        list = root.findViewById(R.id.spot_list);
        detailTitle = root.findViewById(R.id.spot_detail_title);
        detailFacts = root.findViewById(R.id.spot_detail_facts);
        detailText = root.findViewById(R.id.spot_detail_text);
        openBrowser = root.findViewById(R.id.spot_open);

        final SharedPreferences p = MapCompat.prefs();
        if (p != null) {
            try {
                filter = Filter.valueOf(p.getString(PREF_FILTER, Filter.ALL.name()));
            } catch (IllegalArgumentException e) {
                filter = Filter.ALL;
            }
            stateCode = p.getString(PREF_STATE, "");
            regionCode = p.getString(PREF_REGION, "");
            radiusIndex = Math.max(0, Math.min(RADII.length - 1, p.getInt(PREF_RADIUS, 1)));
        }
        wire();
        showGateOrList();
    }

    public View view() {
        return root;
    }

    /** The page came into view, or the pane opened on it. */
    public void onShown() {
        showGateOrList();
        if (!egress.isLayerEnabled(LAYER_ID))
            return;
        if (System.currentTimeMillis() - fetchedAt > STALE_MS)
            fetch();
        else if (showing == null)
            render();
    }

    public void dispose() {
        listGeneration++;
        detailGeneration++;
        worker.shutdownNow();
    }

    private void showGateOrList() {
        final boolean allowed = egress.isLayerEnabled(LAYER_ID);
        gate.setVisibility(allowed ? View.GONE : View.VISIBLE);
        browse.setVisibility(allowed && showing == null ? View.VISIBLE : View.GONE);
        detail.setVisibility(allowed && showing != null ? View.VISIBLE : View.GONE);
    }

    // ---- wiring ------------------------------------------------------------------------

    private void wire() {
        root.findViewById(R.id.spot_load).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                askToAllow();
            }
        });
        all.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setFilter(Filter.ALL);
            }
        });
        near.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickRadius();
            }
        });
        onMap.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Tapping it again reads the map again: the box is the map as it is
                // at the tap, not a live frame.
                setFilter(Filter.MAP);
            }
        });
        state.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickState();
            }
        });
        region.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickRegion();
            }
        });
        root.findViewById(R.id.spot_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showing = null;
                showGateOrList();
                render();
            }
        });
        root.findViewById(R.id.spot_goto).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (showing != null)
                    goTo(showing);
            }
        });
        root.findViewById(R.id.spot_request).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showRequestDialog();
            }
        });
    }

    private void setFilter(Filter f) {
        filter = f;
        final SharedPreferences p = MapCompat.prefs();
        if (p != null)
            p.edit().putString(PREF_FILTER, f.name()).putString(PREF_STATE, stateCode)
                    .putString(PREF_REGION, regionCode).putInt(PREF_RADIUS, radiusIndex)
                    .apply();
        render();
    }

    // ---- the list -------------------------------------------------------------------------

    private void fetch() {
        if (inFlight)
            return;
        inFlight = true;
        final int mine = ++listGeneration;
        if (requests.isEmpty())
            status.setText("Getting spot forecasts…");
        final Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/json");
        // 717 KB as it stands, 79 KB gzipped.
        headers.put("Accept-Encoding", "gzip");
        Http.get(Spot.LIST_URL, egress.userAgent(), headers, new Http.Callback() {
            @Override
            public void onSuccess(final String body) {
                if (mine != listGeneration) {
                    inFlight = false;
                    return;
                }
                // Parsing the country is a few hundred milliseconds of JSON; not on
                // the thread the pane answers on.
                worker.execute(new Runnable() {
                    @Override
                    public void run() {
                        List<Spot.Request> parsed = null;
                        try {
                            if (states == null)
                                states = loadStates();
                            parsed = Spot.parse(body, states);
                        } catch (Exception e) {
                            Log.w(TAG, "spot list unreadable", e);
                        }
                        final List<Spot.Request> got = parsed;
                        mapView.post(new Runnable() {
                            @Override
                            public void run() {
                                inFlight = false;
                                if (mine != listGeneration)
                                    return;
                                if (got == null) {
                                    status.setText("The spot list could not be read");
                                    return;
                                }
                                requests = got;
                                fetchedAt = System.currentTimeMillis();
                                Log.d(TAG, "spot list: " + got.size() + " open requests");
                                if (showing == null)
                                    render();
                            }
                        });
                    }
                });
            }

            @Override
            public void onFailure(String error) {
                inFlight = false;
                if (mine != listGeneration)
                    return;
                Log.w(TAG, "spot list failed: " + error);
                status.setText(requests.isEmpty() ? "Could not reach the Weather Service"
                        : "Could not check for newer requests; this list is from "
                                + clock(fetchedAt));
            }
        });
    }

    /** assets/us_states.json; null (every request "at sea") if it will not read. Worker only. */
    private com.atakmap.android.atmosphere.data.States loadStates() {
        try (java.io.InputStream in = pluginContext.getAssets().open("us_states.json")) {
            final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            final byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0)
                out.write(buf, 0, n);
            return com.atakmap.android.atmosphere.data.States.parse(out.toString("UTF-8"));
        } catch (Exception e) {
            Log.w(TAG, "state outlines unreadable", e);
            return null;
        }
    }

    /** Apply the filter, label the buttons with what each would show, draw the rows. */
    private void render() {
        paintFilters();
        list.removeAllViews();
        if (requests.isEmpty())
            return;
        final GeoPoint self = MapCompat.selfPoint();
        List<Spot.Request> shown;
        String what;
        switch (filter) {
            case NEAR:
                if (self == null) {
                    status.setText("No position for you yet");
                    return;
                }
                shown = Spot.near(requests, self.getLatitude(), self.getLongitude(),
                        radiusMeters());
                what = "within " + radiusLabel(radiusIndex) + " of you";
                break;
            case MAP: {
                final double[] box = viewBox();
                if (box == null) {
                    shown = new ArrayList<>(requests);
                    Spot.sortNewest(shown);
                    what = "in the country (zoom in to use the map)";
                } else {
                    shown = Spot.inBox(requests, box[0], box[1], box[2], box[3]);
                    what = "on the map";
                }
                break;
            }
            case STATE:
                shown = Spot.inState(requests, stateCode);
                what = "in " + Spot.stateName(stateCode);
                break;
            case REGION:
                shown = Spot.inRegion(requests, regionCode);
                what = "in the " + Spot.regionName(regionCode) + " region";
                break;
            default:
                shown = new ArrayList<>(requests);
                Spot.sortNewest(shown);
                what = "in the country";
                break;
        }
        // Say what is not being shown: a trimmed list reads as the whole picture.
        final String order = filter == Filter.NEAR || filter == Filter.MAP
                ? "nearest" : "newest";
        if (shown.isEmpty())
            status.setText("No open spot requests " + what);
        else if (shown.size() > MAX_ROWS)
            status.setText("Showing the " + order + " " + MAX_ROWS + " of " + shown.size()
                    + " " + what + ". Narrow it down to see the rest.");
        else
            status.setText(shown.size() + (shown.size() == 1 ? " request " : " requests ")
                    + what + ", list from " + clock(fetchedAt));
        for (int i = 0; i < shown.size() && i < MAX_ROWS; i++)
            list.addView(row(shown.get(i), self));
    }

    /** Each filter says what it would show before it is tapped. */
    private void paintFilters() {
        final GeoPoint self = MapCompat.selfPoint();
        all.setText(pluginContext.getString(R.string.spot_all) + count(requests.size()));
        near.setText(self == null || requests.isEmpty()
                ? pluginContext.getString(R.string.spot_near)
                : radiusLabel(radiusIndex) + count(Spot.near(requests, self.getLatitude(),
                        self.getLongitude(), radiusMeters()).size()));
        final double[] box = viewBox();
        onMap.setText(pluginContext.getString(R.string.spot_map) + (box == null ? ""
                : count(Spot.inBox(requests, box[0], box[1], box[2], box[3]).size())));
        state.setText(stateCode.isEmpty() ? pluginContext.getString(R.string.spot_state)
                : Spot.stateName(stateCode) + count(Spot.inState(requests, stateCode).size()));
        region.setText(regionCode.isEmpty() ? pluginContext.getString(R.string.spot_region)
                : Spot.regionName(regionCode) + count(Spot.inRegion(requests, regionCode).size()));
        green(all, filter == Filter.ALL);
        green(near, filter == Filter.NEAR);
        green(onMap, filter == Filter.MAP);
        green(state, filter == Filter.STATE);
        green(region, filter == Filter.REGION);
    }

    private String count(int n) {
        return requests.isEmpty() ? "" : " (" + n + ")";
    }

    private void green(Button b, boolean on) {
        b.setTextColor(on ? pluginContext.getResources().getColor(R.color.state_on) : Color.WHITE);
    }

    /** One request as a row: what it is, who asked, where it stands. */
    private View row(final Spot.Request r, GeoPoint self) {
        final LinearLayout row = new LinearLayout(pluginContext);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackgroundResource(R.drawable.btn_gray);
        row.setPadding(dp(10), dp(6), dp(10), dp(8));
        row.setClickable(true);
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        row.setLayoutParams(lp);

        final TextView title = new TextView(pluginContext);
        title.setText(r.project.isEmpty() ? "(no name)" : r.project);
        title.setTextColor(Color.WHITE);
        title.setTextSize(15);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(title);

        final TextView who = new TextView(pluginContext);
        who.setText(r.kind);
        who.setTextColor(0xFFD0D0D0);
        who.setTextSize(13);
        row.addView(who);

        final TextView where = new TextView(pluginContext);
        final String when = r.filledAt <= 0 ? r.status()
                : r.status() + " " + clock(r.filledAt);
        where.setText(join(" · ", when, r.officeName,
                self == null ? "" : distanceLabel(Spot.distanceMeters(self.getLatitude(),
                        self.getLongitude(), r.lat, r.lon))));
        where.setTextColor(r.filledAt <= 0 ? 0xFFFFC040 : 0xFFA0A0A0);
        where.setTextSize(12);
        row.addView(where);

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDetail(r);
            }
        });
        return row;
    }

    // ---- one request ------------------------------------------------------------------

    private void showDetail(final Spot.Request r) {
        showing = r;
        showGateOrList();
        detailTitle.setText(r.project);
        detailFacts.setText(facts(r));
        openBrowser.setVisibility(View.GONE);
        scrollToTop();
        final long filledAt = r.filledAt;
        if (filledAt <= 0) {
            detailText.setText("NWS has not issued a forecast for this request yet.");
            return;
        }
        detailText.setText("Getting the forecast…");
        final int mine = ++detailGeneration;
        Http.get(Spot.fwsListUrl(r.office), egress.userAgent(), null, new Http.Callback() {
            @Override
            public void onSuccess(String listJson) {
                if (mine != detailGeneration || showing != r)
                    return;
                final List<String> ids;
                try {
                    ids = Spot.candidates(listJson, filledAt);
                } catch (Exception e) {
                    Log.w(TAG, "FWS list unreadable", e);
                    notOnline(r, 0);
                    return;
                }
                if (ids.isEmpty()) {
                    notOnline(r, Spot.oldestListed(listJson));
                    return;
                }
                fetchProduct(r, ids, 0, mine);
            }

            @Override
            public void onFailure(String error) {
                if (mine != detailGeneration || showing != r)
                    return;
                detailText.setText("Could not reach the Weather Service: " + error);
            }
        });
    }

    /** Try each product issued near the fill time until one names this project. */
    private void fetchProduct(final Spot.Request r, final List<String> ids, final int i,
            final int mine) {
        if (i >= ids.size()) {
            notOnline(r, 0);
            return;
        }
        Http.get(ids.get(i), egress.userAgent(), null, new Http.Callback() {
            @Override
            public void onSuccess(String body) {
                if (mine != detailGeneration || showing != r)
                    return;
                final String text = Spot.productText(body);
                if (Spot.isFor(text, r.project)) {
                    detailText.setText(text.trim());
                    return;
                }
                fetchProduct(r, ids, i + 1, mine);
            }

            @Override
            public void onFailure(String error) {
                if (mine != detailGeneration || showing != r)
                    return;
                fetchProduct(r, ids, i + 1, mine);
            }
        });
    }

    /**
     * The forecast exists but api.weather.gov no longer lists it, which is what a
     * forecast older than about a week looks like. The program's own page still has
     * it, so offer that rather than a dead end.
     */
    private void notOnline(Spot.Request r, long oldest) {
        detailText.setText(oldest > 0 && r.filledAt < oldest
                ? "This forecast was issued " + clock(r.filledAt) + ". The Weather Service"
                        + " keeps about a week of them in its feed and this one is older."
                : "The forecast issued " + clock(r.filledAt) + " was not found in the"
                        + " Weather Service's feed.");
        // The program's own page still has it; it cannot be linked to by this
        // request, so the Monitor's front page, where the request is listed.
        openBrowser.setVisibility(View.VISIBLE);
        openBrowser.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openUrl(Spot.MONITOR_URL);
            }
        });
    }

    /**
     * What was asked, in the order a crew reads it. The public service carries the
     * kind, the office, the point and the times; who asked, the site and the fuels
     * are in the forecast text itself, under this.
     */
    private String facts(Spot.Request r) {
        final List<String> lines = new ArrayList<>();
        lines.add(r.kind);
        lines.add(join(", ", r.officeName.isEmpty() ? "" : r.officeName + " office",
                Spot.stateName(r.state)));
        if (r.filledAt > 0)
            lines.add("Issued " + clock(r.filledAt) + (r.status().equals("Forecast issued")
                    ? "" : "; " + r.status().toLowerCase(Locale.US)));
        else
            lines.add(r.status());
        if (r.requestedAt > 0)
            lines.add("Requested " + clock(r.requestedAt));
        if (r.deliverAt > 0)
            lines.add("Wanted by " + clock(r.deliverAt));
        final StringBuilder b = new StringBuilder();
        for (String l : lines) {
            if (l == null || l.isEmpty())
                continue;
            if (b.length() > 0)
                b.append('\n');
            b.append(l);
        }
        return b.toString();
    }

    private void goTo(Spot.Request r) {
        try {
            mapView.getMapController().panZoomTo(new GeoPoint(r.lat, r.lon),
                    mapView.mapResolutionAsMapScale(20d), true);
        } catch (LinkageError | RuntimeException e) {
            Log.w(TAG, "panZoomTo failed; plain pan", e);
            mapView.getMapController().panTo(new GeoPoint(r.lat, r.lon), true);
        }
    }

    // ---- pickers: a tile grid of TakwerxButtons, never a full-screen list ----------------

    private void pickRadius() {
        final String[] labels = new String[RADII.length];
        for (int i = 0; i < RADII.length; i++)
            labels[i] = radiusLabel(i);
        tiles(R.string.spot_pick_distance, labels, radiusIndex, new Picked() {
            @Override
            public void picked(int which) {
                radiusIndex = which;
                setFilter(Filter.NEAR);
            }
        });
    }

    private void pickState() {
        final List<String> codes = new ArrayList<>(Spot.countByState(requests).keySet());
        if (codes.isEmpty())
            return;
        final String[] labels = new String[codes.size()];
        for (int i = 0; i < codes.size(); i++)
            labels[i] = Spot.stateName(codes.get(i));
        tiles(R.string.spot_pick_state, labels, codes.indexOf(stateCode), new Picked() {
            @Override
            public void picked(int which) {
                stateCode = codes.get(which);
                setFilter(Filter.STATE);
            }
        });
    }

    private void pickRegion() {
        final List<String> codes = new ArrayList<>(Spot.countByRegion(requests).keySet());
        if (codes.isEmpty())
            return;
        final String[] labels = new String[codes.size()];
        for (int i = 0; i < codes.size(); i++)
            labels[i] = Spot.regionName(codes.get(i));
        tiles(R.string.spot_pick_region, labels, codes.indexOf(regionCode), new Picked() {
            @Override
            public void picked(int which) {
                regionCode = codes.get(which);
                setFilter(Filter.REGION);
            }
        });
    }

    private interface Picked {
        void picked(int which);
    }

    /**
     * A compact dialog of TakwerxButton tiles, three across, the current choice green.
     * On the MapView's context: a dialog on the plugin's context kills ATAK.
     */
    private void tiles(int titleRes, String[] labels, int current, final Picked picked) {
        final Context ctx = MapCompat.atakContext();
        if (ctx == null)
            return;
        final LinearLayout grid = new LinearLayout(pluginContext);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(dp(8), dp(4), dp(4), dp(4));
        final ScrollView scroll = new ScrollView(pluginContext);
        scroll.addView(grid);
        final AlertDialog dialog = new AlertDialog.Builder(ctx)
                .setTitle(pluginContext.getString(titleRes))
                .setView(scroll)
                .setNegativeButton(pluginContext.getString(R.string.close), null)
                .create();
        LinearLayout row = null;
        for (int i = 0; i < labels.length; i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(pluginContext);
                row.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(row);
            }
            final Button b = (Button) LayoutInflater.from(pluginContext)
                    .inflate(R.layout.trend_chip, row, false);
            b.setText(labels[i]);
            b.setTextSize(14);
            green(b, i == current);
            final int which = i;
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dialog.dismiss();
                    picked.picked(which);
                }
            });
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = dp(4);
            lp.topMargin = dp(4);
            b.setLayoutParams(lp);
            row.addView(b);
        }
        // Keep a short last row's tiles the width of the others.
        if (row != null)
            for (int i = labels.length % 3; i > 0 && i < 3; i++) {
                final View filler = new View(pluginContext);
                row.addView(filler, new LinearLayout.LayoutParams(0, 1, 1f));
            }
        dialog.show();
    }

    // ---- requesting ----------------------------------------------------------------------

    /**
     * The program takes requests on its own form only: its API wants a key that is not
     * ours, and the form reads nothing from a link. So hand over the point to paste and
     * open the form, which is honest about what the plugin can do.
     */
    private void showRequestDialog() {
        final Context ctx = MapCompat.atakContext();
        if (ctx == null)
            return;
        final GeoPoint p = host.point();
        if (p == null) {
            Toast.makeText(ctx, pluginContext.getString(R.string.no_position),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        final String position = String.format(Locale.US, "%.5f, %.5f", p.getLatitude(),
                p.getLongitude());
        new AlertDialog.Builder(ctx)
                .setTitle(pluginContext.getString(R.string.spot_request_title))
                .setMessage(pluginContext.getString(R.string.spot_request_text,
                        "Latitude, longitude: " + position))
                .setPositiveButton(pluginContext.getString(R.string.spot_open_form),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                copy(ctx, position);
                                openUrl(Spot.NEW_REQUEST_URL);
                            }
                        })
                .setNeutralButton(pluginContext.getString(R.string.spot_copy),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                copy(ctx, position);
                            }
                        })
                .setNegativeButton(pluginContext.getString(R.string.close), null)
                .show();
    }

    private void copy(Context ctx, String text) {
        final ClipboardManager cm = (ClipboardManager) ctx.getSystemService(
                Context.CLIPBOARD_SERVICE);
        if (cm == null)
            return;
        cm.setPrimaryClip(ClipData.newPlainText("Spot forecast point", text));
        Toast.makeText(ctx, "Position copied: " + text, Toast.LENGTH_SHORT).show();
    }

    private void openUrl(String url) {
        final Context ctx = MapCompat.atakContext();
        if (ctx == null)
            return;
        try {
            final Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (RuntimeException e) {
            Log.w(TAG, "no browser for " + url, e);
            Toast.makeText(ctx, "No browser on this phone to open " + url,
                    Toast.LENGTH_LONG).show();
        }
    }

    private void askToAllow() {
        final Context ctx = MapCompat.atakContext();
        if (ctx == null)
            return;
        new AlertDialog.Builder(ctx)
                .setTitle(pluginContext.getString(R.string.spot_allow_title))
                .setMessage(pluginContext.getString(R.string.spot_allow_text))
                .setPositiveButton(pluginContext.getString(R.string.allow),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                egress.setLayerEnabled(LAYER_ID, true);
                                onShown();
                            }
                        })
                .setNegativeButton(pluginContext.getString(R.string.close), null)
                .show();
    }

    // ---- units, time, helpers ----------------------------------------------------------

    /** The map's box as north, west, south, east; null on the globe or with no map. */
    private double[] viewBox() {
        final GeoBounds b = mapView.getBounds();
        if (b == null || Double.isNaN(b.getNorth()) || Double.isNaN(b.getSouth())
                || Double.isNaN(b.getEast()) || Double.isNaN(b.getWest())
                || b.getEast() <= b.getWest() || b.getEast() - b.getWest() >= 355)
            return null;
        return new double[] { b.getNorth(), b.getWest(), b.getSouth(), b.getEast() };
    }

    /** Distances in the large unit of the operator's own system, pinned per the rule. */
    private double unitMeters() {
        switch (host.units()) {
            case METRIC: return 1000d;
            case AVIATION: return 1852d;
            default: return 1609.344;
        }
    }

    private String unitName() {
        switch (host.units()) {
            case METRIC: return "km";
            case AVIATION: return "nm";
            default: return "mi";
        }
    }

    private double radiusMeters() {
        return RADII[radiusIndex] * unitMeters();
    }

    private String radiusLabel(int i) {
        return RADII[i] + " " + unitName();
    }

    private String distanceLabel(double meters) {
        final double v = meters / unitMeters();
        return (v < 10 ? String.format(Locale.US, "%.1f", v) : String.valueOf(Math.round(v)))
                + " " + unitName();
    }

    /** "8:13 pm" today, "Tue 8:13 pm" otherwise, in the phone's zone. */
    private static String clock(long when) {
        if (when <= 0)
            return "at an unknown time";
        final boolean today = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date(when))
                .equals(new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date()));
        return new SimpleDateFormat(today ? "h:mm a" : "EEE h:mm a", Locale.US)
                .format(new Date(when)).replace("AM", "am").replace("PM", "pm");
    }

    private static String join(String sep, String... parts) {
        final StringBuilder b = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isEmpty())
                continue;
            if (b.length() > 0)
                b.append(sep);
            b.append(p);
        }
        return b.toString();
    }

    private void scrollToTop() {
        if (root instanceof ScrollView)
            ((ScrollView) root).smoothScrollTo(0, 0);
    }

    private int dp(int v) {
        return Math.round(v * pluginContext.getResources().getDisplayMetrics().density);
    }
}

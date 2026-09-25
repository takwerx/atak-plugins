package com.atakmap.android.atmosphere.ui;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PointF;
import android.graphics.Typeface;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.atakmap.android.atmosphere.compat.MapCompat;
import com.atakmap.android.atmosphere.data.Favorites;
import com.atakmap.android.atmosphere.data.Spot;
import com.atakmap.android.atmosphere.net.EgressPolicy;
import com.atakmap.android.atmosphere.net.Http;
import com.atakmap.android.atmosphere.plugin.R;
import com.atakmap.android.atmosphere.units.UnitSystem;
import com.atakmap.android.maps.MapEvent;
import com.atakmap.android.maps.MapEventDispatcher;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.user.geocode.GeocodeManager;
import com.atakmap.android.user.geocode.GeocodingUtil;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoBounds;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.coremap.maps.coords.GeoPointMetaData;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
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

        /** What the pane calls that point: "My position", "Map center"... */
        String pointLabel();

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
    private final android.widget.EditText search;
    private final TextView searchCount;
    /** What has been typed, lowercased. Applied on top of whichever scope is picked. */
    private String query = "";
    private final LinearLayout list;
    private final TextView detailTitle, detailFacts, detailText;
    private final Button openBrowser;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private List<Spot.Request> requests = new ArrayList<>();
    /** A request tapped on the map before the list had been read. */
    private String pendingId;

    /**
     * Whether only recent requests are shown, shared with the map layer.
     *
     * <p>It has to be shared. The list had no filter at all while the layer had one,
     * so an incident could be in the list and not on the map, and "Go to" flew the
     * operator to an empty patch of ground -- which is what happened with the Wheeler
     * Incident (2026-09-25). Read from the layer's own preference every time rather
     * than cached, so the two can never drift apart.
     */
    private static boolean recentOnly() {
        final android.content.SharedPreferences p = MapCompat.prefs();
        return p == null || p.getBoolean("weather.layer.spot.recentonly", true);
    }

    /** The requests the page is willing to show, under the shared age filter. */
    private List<Spot.Request> visible() {
        if (!recentOnly())
            return requests;
        final long now = System.currentTimeMillis();
        final List<Spot.Request> out = new ArrayList<>();
        for (Spot.Request r : requests)
            if (com.atakmap.android.atmosphere.overlay.SpotOverlay.isRecent(r, now))
                out.add(r);
        return out;
    }
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
        search = root.findViewById(R.id.spot_search);
        searchCount = root.findViewById(R.id.spot_search_count);
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence c, int a, int b, int d) {
            }

            @Override
            public void onTextChanged(CharSequence c, int a, int b, int d) {
            }

            @Override
            public void afterTextChanged(android.text.Editable e) {
                final String next = e == null ? ""
                        : e.toString().trim().toLowerCase(Locale.US);
                if (next.equals(query))
                    return;
                query = next;
                // Typing narrows the list that is already there; it never refetches.
                if (showing == null)
                    render();
            }
        });
        // Keep the list visible while typing into the box that filters it.
        //
        // ATAK's window pans by default, so the soft keyboard slides over the pane and
        // the operator is typing a filter at a list they can no longer see (operator,
        // 2026-09-25: "can we make it so when your typing we can still see the tab?").
        // Resizing instead shortens the pane above the keyboard, so the rows stay on
        // screen and stay scrollable. It is the host activity's setting, so it is put
        // back the moment the field loses focus rather than left changed for all of
        // ATAK.
        search.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                resizeForKeyboard(hasFocus);
                if (hasFocus)
                    bringSearchToTop();
            }
        });
        root.findViewById(R.id.spot_search_clear).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        search.setText("");
                    }
                });
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

    /**
     * The shared status filter changed on the layers page; redraw what is listed.
     * Nothing is refetched -- it is the same requests, a different slice of them.
     */
    public void onFilterChanged() {
        if (showing == null)
            render();
    }

    /** The page came into view, or the pane opened on it. */
    /**
     * Open one request's forecast by its id, for a tap on the map layer.
     *
     * <p>The list may not have been read yet -- the layer can be on with this page
     * never opened -- so a miss asks for the list once and tries again rather than
     * showing nothing.
     */
    public void showById(final String spotId) {
        if (spotId == null || spotId.isEmpty())
            return;
        final Spot.Request found = byId(spotId);
        if (found != null) {
            showDetail(found);
            return;
        }
        status.setText("Getting spot forecasts\u2026");
        pendingId = spotId;
        fetch();
    }

    private Spot.Request byId(String spotId) {
        for (Spot.Request r : requests)
            if (spotId.equals(r.id))
                return r;
        return null;
    }

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
        resizeForKeyboard(false);
        listGeneration++;
        detailGeneration++;
        disarmRequestPick();
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
                                // A map tap that arrived before the list did.
                                if (pendingId != null) {
                                    final Spot.Request waiting = byId(pendingId);
                                    pendingId = null;
                                    if (waiting != null) {
                                        showDetail(waiting);
                                        return;
                                    }
                                }
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
    /**
     * Put the search box at the top of the pane while it is focused.
     *
     * <p>Resizing for the keyboard leaves the pane short, and the icon row and the
     * area buttons above the box fill what is left, so the rows being filtered end up
     * below the fold -- visible pane, invisible list. Scrolling the box to the top
     * hands the remaining height to the list, which is the half worth seeing while
     * typing.
     */
    private void bringSearchToTop() {
        final View row = root.findViewById(R.id.spot_search_row);
        if (!(root instanceof ScrollView) || row == null)
            return;
        final ScrollView scroller = (ScrollView) root;
        // After the resize has happened, not before it.
        scroller.postDelayed(new Runnable() {
            @Override
            public void run() {
                scroller.smoothScrollTo(0, row.getTop());
            }
        }, 250L);
    }

    /** ATAK's own soft-input mode, so it can be handed back unchanged. */
    private int softInputWas = -1;

    private void resizeForKeyboard(boolean on) {
        try {
            final android.content.Context ctx = mapView.getContext();
            if (!(ctx instanceof android.app.Activity))
                return;
            final android.view.Window w = ((android.app.Activity) ctx).getWindow();
            if (w == null)
                return;
            if (on) {
                if (softInputWas == -1)
                    softInputWas = w.getAttributes().softInputMode;
                w.setSoftInputMode(android.view.WindowManager.LayoutParams
                        .SOFT_INPUT_ADJUST_RESIZE);
            } else if (softInputWas != -1) {
                w.setSoftInputMode(softInputWas);
                softInputWas = -1;
            }
        } catch (Exception e) {
            // Not worth failing a search over; the keyboard just covers the pane.
            Log.w(TAG, "soft input mode", e);
        }
    }

    /**
     * Whether a request answers to what was typed. The incident name is what a crew
     * knows, so that is matched first; the office and state are matched too because
     * "LOX" and "CA" are the other things somebody types into a box like this.
     */
    private static boolean matches(Spot.Request r, String q) {
        if (contains(r.project, q))
            return true;
        if (contains(r.kind, q))
            return true;
        if (contains(r.office, q) || contains(r.officeName, q))
            return true;
        return contains(r.state, q);
    }

    private static boolean contains(String field, String q) {
        return field != null && field.toLowerCase(Locale.US).contains(q);
    }

    private void render() {
        paintFilters();
        list.removeAllViews();
        if (requests.isEmpty())
            return;
        final GeoPoint self = MapCompat.selfPoint();
        // The same list the map layer draws from, so the two can never disagree about
        // whether an incident exists.
        final List<Spot.Request> pool = visible();
        List<Spot.Request> shown;
        String what;
        switch (filter) {
            case NEAR:
                if (self == null) {
                    status.setText("No position for you yet");
                    return;
                }
                shown = Spot.near(pool, self.getLatitude(), self.getLongitude(),
                        radiusMeters());
                what = "within " + radiusLabel(radiusIndex) + " of you";
                break;
            case MAP: {
                final double[] box = viewBox();
                if (box == null) {
                    shown = new ArrayList<>(pool);
                    Spot.sortNewest(shown);
                    what = "in the country (zoom in to use the map)";
                } else {
                    shown = Spot.inBox(pool, box[0], box[1], box[2], box[3]);
                    what = "on the map";
                }
                break;
            }
            case STATE:
                shown = Spot.inState(pool, stateCode);
                what = "in " + Spot.stateName(stateCode);
                break;
            case REGION:
                shown = Spot.inRegion(pool, regionCode);
                what = "in the " + Spot.regionName(regionCode) + " region";
                break;
            default:
                shown = new ArrayList<>(pool);
                Spot.sortNewest(shown);
                what = "in the country";
                break;
        }
        // The name search narrows whatever the scope chose, rather than replacing it:
        // "HATCHERY within 250 miles" is a question somebody asks; "HATCHERY, and also
        // forget where you were looking" is not.
        if (!query.isEmpty()) {
            final List<Spot.Request> hits = new ArrayList<>();
            for (Spot.Request r : shown)
                if (matches(r, query))
                    hits.add(r);
            shown = hits;
            what = what + " matching \u201c" + query + "\u201d";
        }

        // One row per incident, the newest. A fire gets a fresh spot forecast every
        // operational period, so 44 of the 346 projects running on 2026-09-25 had
        // more than one request open and "Widemouth 2" had seven. The operator wants
        // the current one: "i dont care about past forecasts, why cant i get the
        // latest?" The older ones are counted on the row rather than silently
        // dropped.
        final Map<String, Integer> perIncident = new LinkedHashMap<>();
        shown = com.atakmap.android.atmosphere.overlay.SpotOverlay
                .newestPerIncident(shown, perIncident);

        // Beside the box, so a search that is working says so while the keyboard is
        // still covering the rows.
        searchCount.setText(query.isEmpty() ? ""
                : (shown.isEmpty() ? "none" : String.valueOf(shown.size())));

        // Say what is not being shown: a trimmed list reads as the whole picture.
        final String order = filter == Filter.NEAR || filter == Filter.MAP
                ? "nearest" : "newest";
        if (shown.isEmpty())
            status.setText("No open spot requests " + what);
        else if (shown.size() > MAX_ROWS)
            status.setText("Showing the " + order + " " + MAX_ROWS + " of " + shown.size()
                    + " incidents " + what + ". Narrow it down to see the rest.");
        else
            // Incidents, not requests: the rows have been collapsed to the newest
            // forecast each, and the scope buttons above still count requests, so
            // saying "requests" here would read as a number that did not add up.
            status.setText(shown.size() + (shown.size() == 1 ? " incident " : " incidents ")
                    + what + ", newest forecast each, list from " + clock(fetchedAt));
        for (int i = 0; i < shown.size() && i < MAX_ROWS; i++) {
            final Spot.Request r = shown.get(i);
            final Integer n = perIncident.get(com.atakmap.android.atmosphere.overlay.SpotOverlay
                    .incidentKey(r));
            list.addView(row(r, self, n == null ? 1 : n));
        }
    }

    /** Each filter says what it would show before it is tapped. */
    private void paintFilters() {
        final GeoPoint self = MapCompat.selfPoint();
        // Counted off the same list the rows come from, so a button never promises
        // more than the list beneath it will show.
        final List<Spot.Request> can = visible();
        all.setText(pluginContext.getString(R.string.spot_all) + count(can.size()));
        near.setText(self == null || can.isEmpty()
                ? pluginContext.getString(R.string.spot_near)
                : radiusLabel(radiusIndex) + count(Spot.near(can, self.getLatitude(),
                        self.getLongitude(), radiusMeters()).size()));
        final double[] box = viewBox();
        onMap.setText(pluginContext.getString(R.string.spot_map) + (box == null ? ""
                : count(Spot.inBox(can, box[0], box[1], box[2], box[3]).size())));
        state.setText(stateCode.isEmpty() ? pluginContext.getString(R.string.spot_state)
                : Spot.stateName(stateCode) + count(Spot.inState(can, stateCode).size()));
        region.setText(regionCode.isEmpty() ? pluginContext.getString(R.string.spot_region)
                : Spot.regionName(regionCode) + count(Spot.inRegion(can, regionCode).size()));
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
        return row(r, self, 1);
    }

    private View row(final Spot.Request r, GeoPoint self, int forecasts) {
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
        who.setText(forecasts > 1 ? r.kind + " \u00b7 " + forecasts + " forecasts, newest"
                : r.kind);
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

    private void tiles(int titleRes, String[] labels, int current, final Picked picked) {
        tiles(pluginContext.getString(titleRes), labels, current, 3, picked);
    }

    /**
     * A compact dialog of TakwerxButton tiles, the current choice green. Three across
     * for short names; one across, two lines allowed, for anything longer (an address).
     * On the MapView's context: a dialog on the plugin's context kills ATAK.
     */
    private void tiles(String title, String[] labels, int current, final int columns,
            final Picked picked) {
        final Context ctx = MapCompat.atakContext();
        if (ctx == null)
            return;
        final LinearLayout grid = new LinearLayout(pluginContext);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(dp(8), dp(4), dp(4), dp(4));
        final ScrollView scroll = new ScrollView(pluginContext);
        scroll.addView(grid);
        final AlertDialog dialog = new AlertDialog.Builder(ctx)
                .setTitle(title)
                .setView(scroll)
                .setNegativeButton(pluginContext.getString(R.string.close), null)
                .create();
        LinearLayout row = null;
        for (int i = 0; i < labels.length; i++) {
            if (i % columns == 0) {
                row = new LinearLayout(pluginContext);
                row.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(row);
            }
            final Button b = (Button) LayoutInflater.from(pluginContext)
                    .inflate(R.layout.trend_chip, row, false);
            b.setText(labels[i]);
            b.setTextSize(14);
            if (columns == 1) {
                b.setSingleLine(false);
                b.setMaxLines(2);
            }
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
            for (int i = labels.length % columns; i > 0 && i < columns; i++) {
                final View filler = new View(pluginContext);
                row.addView(filler, new LinearLayout.LayoutParams(0, 1, 1f));
            }
        dialog.show();
    }

    // ---- requesting ----------------------------------------------------------------------

    /**
     * The point a request is for, and what to call it. Starts as the pane's own point
     * each time the dialog is opened from the page; a source picked inside the dialog
     * replaces it until the dialog is closed.
     */
    private GeoPoint requestPoint;
    private String requestFrom;
    /** A map tap armed for the request point; the map's own listeners are pushed. */
    private MapEventDispatcher.MapEventDispatchListener requestPick;

    /**
     * The program takes requests on its own form only: its API wants a key that is not
     * ours, and the form reads nothing from a link. So the dialog settles WHERE -- your
     * position, the map center, a tap on the map, a favorite or an address -- and hands
     * that over to paste, then opens the form (operator, 2026-09-24: "can i tap on the
     * map ... put in an address ... give all the options").
     */
    private void showRequestDialog() {
        requestPoint = host.point();
        requestFrom = host.pointLabel();
        showRequestDialogFor();
    }

    private void showRequestDialogFor() {
        final Context ctx = MapCompat.atakContext();
        if (ctx == null)
            return;
        final LinearLayout body = new LinearLayout(pluginContext);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(8), dp(12), 0);
        final TextView intro = new TextView(pluginContext);
        intro.setText(R.string.spot_request_text);
        intro.setTextColor(Color.WHITE);
        intro.setTextSize(15);
        body.addView(intro);
        body.addView(heading(R.string.spot_point_heading));
        final TextView where = new TextView(pluginContext);
        where.setTextColor(Color.WHITE);
        where.setTextSize(17);
        where.setText(requestPoint == null ? pluginContext.getString(R.string.no_position)
                : requestFrom + "\n" + position(requestPoint));
        body.addView(where);
        body.addView(heading(R.string.spot_use_heading));

        final ScrollView scroll = new ScrollView(pluginContext);
        scroll.addView(body);
        final AlertDialog.Builder b = new AlertDialog.Builder(ctx)
                .setTitle(pluginContext.getString(R.string.spot_request_title))
                .setView(scroll)
                .setNegativeButton(pluginContext.getString(R.string.close), null);
        if (requestPoint != null) {
            final String pos = position(requestPoint);
            b.setPositiveButton(pluginContext.getString(R.string.spot_open_form),
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int which) {
                            copy(ctx, pos);
                            openUrl(Spot.NEW_REQUEST_URL);
                        }
                    });
            b.setNeutralButton(pluginContext.getString(R.string.spot_copy),
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int which) {
                            copy(ctx, pos);
                        }
                    });
        }
        final AlertDialog dialog = b.create();

        // Five sources, three across then two: each sets the point and redraws.
        final int[] names = { R.string.spot_my_position, R.string.spot_map_center,
                R.string.spot_pick_on_map, R.string.spot_favorite, R.string.spot_address };
        LinearLayout row = null;
        for (int i = 0; i < names.length; i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(pluginContext);
                row.setOrientation(LinearLayout.HORIZONTAL);
                body.addView(row);
            }
            final Button tile = (Button) LayoutInflater.from(pluginContext)
                    .inflate(R.layout.trend_chip, row, false);
            tile.setText(names[i]);
            tile.setTextSize(14);
            final int which = i;
            tile.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dialog.dismiss();
                    useSource(which);
                }
            });
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = dp(4);
            lp.topMargin = dp(4);
            tile.setLayoutParams(lp);
            row.addView(tile);
        }
        if (row != null)
            row.addView(new View(pluginContext), new LinearLayout.LayoutParams(0, 1, 1f));
        dialog.show();
    }

    /** 0 my position, 1 map center, 2 a tap on the map, 3 a favorite, 4 an address. */
    private void useSource(int which) {
        switch (which) {
            case 0:
                setRequestPoint(MapCompat.selfPoint(),
                        pluginContext.getString(R.string.spot_my_position));
                break;
            case 1:
                setRequestPoint(MapCompat.mapCenter(),
                        pluginContext.getString(R.string.spot_map_center));
                break;
            case 2:
                pickRequestPoint();
                break;
            case 3:
                pickFavorite();
                break;
            default:
                askAddress();
                break;
        }
    }

    private void setRequestPoint(GeoPoint p, String from) {
        if (p != null && p.isValid()) {
            requestPoint = p;
            requestFrom = from;
        } else {
            toast(pluginContext.getString(R.string.no_position));
        }
        showRequestDialogFor();
    }

    /** The dialog steps aside, the next map tap is the point, and it comes back. */
    private void pickRequestPoint() {
        final MapEventDispatcher d = mapView.getMapEventDispatcher();
        if (requestPick != null)
            return;
        d.pushListeners();
        d.clearListeners(MapEvent.MAP_CLICK);
        d.clearListeners(MapEvent.ITEM_CLICK);
        requestPick = new MapEventDispatcher.MapEventDispatchListener() {
            @Override
            public void onMapEvent(MapEvent event) {
                final PointF pf = event.getPointF();
                GeoPoint p = null;
                if (pf != null) {
                    final GeoPointMetaData gp = mapView.inverseWithElevation(pf.x, pf.y);
                    p = gp == null ? null : gp.get();
                }
                disarmRequestPick();
                setRequestPoint(p, pluginContext.getString(R.string.spot_picked));
            }
        };
        d.addMapEventListener(MapEvent.MAP_CLICK, requestPick);
        d.addMapEventListener(MapEvent.ITEM_CLICK, requestPick);
        toast(pluginContext.getString(R.string.spot_tap_map));
    }

    private void disarmRequestPick() {
        if (requestPick == null)
            return;
        requestPick = null;
        mapView.getMapEventDispatcher().popListeners();
    }

    private void pickFavorite() {
        final Context ctx = MapCompat.atakContext();
        final List<Favorites.Place> all = ctx == null ? new ArrayList<Favorites.Place>()
                : new Favorites(ctx).all();
        if (all.isEmpty()) {
            toast(pluginContext.getString(R.string.favorites_none));
            showRequestDialogFor();
            return;
        }
        final String[] labels = new String[all.size()];
        for (int i = 0; i < labels.length; i++)
            labels[i] = all.get(i).name;
        tiles(pluginContext.getString(R.string.spot_pick_favorite), labels, -1, 3,
                new Picked() {
                    @Override
                    public void picked(int which) {
                        final Favorites.Place f = all.get(which);
                        setRequestPoint(new GeoPoint(f.latitude, f.longitude),
                                "\u2605 " + f.name);
                    }
                });
    }

    /**
     * An address, looked up by ATAK's own address search: the geocoder the operator
     * picked in ATAK's settings (Android's by default), through {@code GeocodingUtil},
     * the same path ATAK's Go To uses. The dialog names that geocoder, because the
     * typed address is sent to it; the plugin sends nothing of its own.
     */
    private void askAddress() {
        final Context ctx = MapCompat.atakContext();
        if (ctx == null)
            return;
        final GeocodeManager.Geocoder coder = GeocodeManager.getInstance(ctx)
                .getSelectedGeocoder();
        if (coder == null) {
            toast(pluginContext.getString(R.string.spot_no_geocoder));
            showRequestDialogFor();
            return;
        }
        final EditText input = new EditText(ctx);
        input.setSingleLine(true);
        input.setHint(pluginContext.getString(R.string.spot_address_hint));
        new AlertDialog.Builder(ctx)
                .setTitle(pluginContext.getString(R.string.spot_address_title))
                .setMessage(pluginContext.getString(R.string.spot_address_text, coder.getTitle()))
                .setView(input)
                .setPositiveButton(pluginContext.getString(R.string.spot_search),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                final String text = input.getText().toString().trim();
                                if (text.isEmpty()) {
                                    showRequestDialogFor();
                                    return;
                                }
                                lookUp(coder, text);
                            }
                        })
                .setNegativeButton(pluginContext.getString(R.string.back),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                showRequestDialogFor();
                            }
                        })
                .show();
    }

    private void lookUp(GeocodeManager.Geocoder coder, final String text) {
        toast(pluginContext.getString(R.string.spot_searching, text));
        // The search is biased to the map on screen; on the globe, the world.
        final double[] box = viewBox();
        final GeoBounds bounds = box == null ? new GeoBounds(90, -180, -90, 180)
                : new GeoBounds(box[0], box[1], box[2], box[3]);
        final int mine = ++detailGeneration;
        GeocodingUtil.lookup(coder, bounds, text, 6, new GeocodingUtil.ResultListener() {
            @Override
            public void onResult(GeocodeManager.Geocoder c, String original, GeoPoint point,
                    final List<android.util.Pair<String, GeoPoint>> found,
                    final GeocodeManager.GeocoderException error) {
                // Answers on the geocoder's own thread; everything below is UI.
                mapView.post(new Runnable() {
                    @Override
                    public void run() {
                        if (mine != detailGeneration)
                            return;
                        if (error != null)
                            Log.w(TAG, "address search failed", error);
                        showAddresses(text, found);
                    }
                });
            }
        });
    }

    private void showAddresses(String text,
            final List<android.util.Pair<String, GeoPoint>> found) {
        final List<android.util.Pair<String, GeoPoint>> usable = new ArrayList<>();
        if (found != null)
            for (android.util.Pair<String, GeoPoint> f : found)
                if (f != null && f.second != null && f.second.isValid())
                    usable.add(f);
        if (usable.isEmpty()) {
            toast(pluginContext.getString(R.string.spot_address_none, text));
            showRequestDialogFor();
            return;
        }
        if (usable.size() == 1) {
            setRequestPoint(usable.get(0).second, addressLabel(usable.get(0).first, text));
            return;
        }
        final String[] labels = new String[usable.size()];
        for (int i = 0; i < labels.length; i++)
            labels[i] = addressLabel(usable.get(i).first, text);
        tiles(pluginContext.getString(R.string.spot_pick_address), labels, -1, 1,
                new Picked() {
                    @Override
                    public void picked(int which) {
                        setRequestPoint(usable.get(which).second, labels[which]);
                    }
                });
    }

    private static String addressLabel(String found, String typed) {
        return found == null || found.trim().isEmpty() ? typed : found.trim();
    }

    private static String position(GeoPoint p) {
        return String.format(Locale.US, "%.5f, %.5f", p.getLatitude(), p.getLongitude());
    }

    private TextView heading(int res) {
        final TextView h = new TextView(pluginContext);
        h.setText(res);
        h.setTextSize(10);
        h.setAllCaps(true);
        h.setAlpha(0.6f);
        h.setPadding(0, dp(10), 0, dp(2));
        return h;
    }

    private void toast(String text) {
        final Context ctx = MapCompat.atakContext();
        if (ctx != null)
            Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show();
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
    /**
     * When, with the date on it unless it was today.
     *
     * <p>A weekday alone is ambiguous by the end of the week: an incident with
     * forecasts on Tuesday, Wednesday and Friday read as "Tue", "Wed" and a bare time,
     * and there was no way to tell Tuesday of this week from any other (operator,
     * 2026-09-25: "its friday and i see a tuesday, a wed and a today ... it needs the
     * date and day in there"). Today says so in words rather than by omission.
     */
    private static String clock(long when) {
        if (when <= 0)
            return "at an unknown time";
        final SimpleDateFormat key = new SimpleDateFormat("yyyyMMdd", Locale.US);
        final String time = new SimpleDateFormat("h:mm a", Locale.US)
                .format(new Date(when)).replace("AM", "am").replace("PM", "pm");
        final String on = key.format(new Date(when));
        if (on.equals(key.format(new Date())))
            return "today " + time;
        if (on.equals(key.format(new Date(System.currentTimeMillis() - 86_400_000L))))
            return "yesterday " + time;
        return new SimpleDateFormat("EEE MMM d", Locale.US).format(new Date(when))
                + ", " + time;
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

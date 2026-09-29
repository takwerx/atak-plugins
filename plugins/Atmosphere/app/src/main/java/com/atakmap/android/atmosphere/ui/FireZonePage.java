package com.atakmap.android.atmosphere.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.atakmap.android.atmosphere.compat.MapCompat;
import com.atakmap.android.atmosphere.data.FireZones;
import com.atakmap.android.atmosphere.data.Fwf;
import com.atakmap.android.atmosphere.data.ZoneFavorites;
import com.atakmap.android.atmosphere.net.EgressPolicy;
import com.atakmap.android.atmosphere.net.Http;
import com.atakmap.android.atmosphere.plugin.R;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * The fire zones page: the NWS fire weather planning forecast for one fire weather
 * zone, shown as the office wrote it, the way a spot forecast is. The zone the pane
 * is reading sits on top, then a search by number or name, then the starred zones.
 * See {@link Fwf} for the product and {@link FireZones} for the zones.
 *
 * <p>Nothing is fetched until the operator allows it, and then only while the page
 * is looked at. Finding the zone a point is in sends the half-degree cell the point
 * falls in, never the point; the zone is picked out on the phone.
 */
public final class FireZonePage {
    private static final String TAG = "AtmosphereZones";
    /** Allowed once, by name, for both servers the page talks to. */
    public static final String LAYER_ID = "firezones";
    private static final String PREF_DISCUSSION = "weather.zones.discussion.open";

    /** How long after the last keystroke the search goes out. */
    private static final long SEARCH_SETTLE_MS = 600L;
    /** Issuances read, newest first, looking for one that covers the zone. */
    private static final int ISSUANCES = 4;
    /** Older than this and the forecast says so in words. */
    private static final long OLD_MS = 36 * 3_600_000L;
    /** Cells kept in memory, so moving the point about does not ask again. */
    private static final int CELLS_KEPT = 8;

    /** What the page needs from the pane around it. */
    public interface Host {
        /** The point the pane is reading, or null. */
        GeoPoint point();

        /** What the pane calls that point: "My position", "Map center"... */
        String pointLabel();
    }

    /** A zone as a row and a detail: from the cell, a search, or the stars. */
    private static final class Pick {
        final String id, name, cwa;
        /** West, south, east, north; null until the outline has been read. */
        double[] bbox;

        Pick(String id, String name, String cwa, double[] bbox) {
            this.id = id;
            this.name = name == null ? "" : name;
            this.cwa = cwa == null ? "" : cwa;
            this.bbox = bbox;
        }

        static Pick of(FireZones.Zone z) {
            return new Pick(z.id, z.name, z.cwa, z.bbox());
        }

        String ugc() {
            return FireZones.ugc(id);
        }
    }

    private final Context pluginContext;
    private final MapView mapView;
    private final EgressPolicy egress;
    private final Host host;
    private final ZoneFavorites favorites;
    private final View root;
    private final View gate, browse, detail;
    private final TextView hereStatus, foundStatus, searchCount, starredNone;
    private final LinearLayout hereList, foundList, starredList;
    private final EditText search;
    private final Button star, discussionToggle;
    private final View discussionScroll;
    private final TextView detailTitle, detailFacts, detailText, discussionText;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    /** Zones per cell key, most recent last. */
    private final LinkedHashMap<String, List<FireZones.Zone>> cells =
            new LinkedHashMap<String, List<FireZones.Zone>>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<FireZones.Zone>> e) {
                    return size() > CELLS_KEPT;
                }
            };
    private String cellInFlight;
    private int hereGeneration, searchGeneration, detailGeneration;
    private String query = "";
    private Pick showing;
    private boolean discussionOpen;
    private boolean pointDirty = true;

    private final Runnable searchSettled = new Runnable() {
        @Override
        public void run() {
            runSearch();
        }
    };

    public FireZonePage(Context pluginContext, MapView mapView, EgressPolicy egress, Host host) {
        this.pluginContext = pluginContext;
        this.mapView = mapView;
        this.egress = egress;
        this.host = host;
        this.favorites = new ZoneFavorites(mapView.getContext());
        root = LayoutInflater.from(pluginContext).inflate(R.layout.page_firezones, null);
        gate = root.findViewById(R.id.zones_gate);
        browse = root.findViewById(R.id.zones_browse);
        detail = root.findViewById(R.id.zones_detail);
        hereStatus = root.findViewById(R.id.zones_here_status);
        hereList = root.findViewById(R.id.zones_here_list);
        search = root.findViewById(R.id.zones_search);
        searchCount = root.findViewById(R.id.zones_search_count);
        foundStatus = root.findViewById(R.id.zones_found_status);
        foundList = root.findViewById(R.id.zones_found_list);
        starredNone = root.findViewById(R.id.zones_starred_none);
        starredList = root.findViewById(R.id.zones_starred_list);
        star = root.findViewById(R.id.zones_star);
        detailTitle = root.findViewById(R.id.zones_detail_title);
        detailFacts = root.findViewById(R.id.zones_detail_facts);
        detailText = root.findViewById(R.id.zones_detail_text);
        discussionToggle = root.findViewById(R.id.zones_discussion_toggle);
        discussionScroll = root.findViewById(R.id.zones_discussion_scroll);
        discussionText = root.findViewById(R.id.zones_discussion_text);

        final SharedPreferences p = MapCompat.prefs();
        discussionOpen = p != null && p.getBoolean(PREF_DISCUSSION, false);
        wire();
        showGateOrList();
        renderStarred();
    }

    public View view() {
        return root;
    }

    /** The page came into view, or the pane opened on it. */
    public void onShown() {
        showGateOrList();
        if (!egress.isLayerEnabled(LAYER_ID))
            return;
        if (pointDirty)
            locate();
        renderStarred();
    }

    /**
     * The pane is reading another point. Looked up now when the page is in view,
     * otherwise the next time it is.
     */
    public void pointChanged(boolean pageShowing) {
        pointDirty = true;
        if (pageShowing && egress.isLayerEnabled(LAYER_ID))
            locate();
    }

    public void dispose() {
        mapView.removeCallbacks(searchSettled);
        resizeForKeyboard(false);
        hereGeneration++;
        searchGeneration++;
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
        root.findViewById(R.id.zones_load).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                askToAllow();
            }
        });
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence c, int a, int b, int d) {
            }

            @Override
            public void onTextChanged(CharSequence c, int a, int b, int d) {
            }

            @Override
            public void afterTextChanged(android.text.Editable e) {
                final String next = e == null ? "" : e.toString().trim();
                if (next.equals(query))
                    return;
                query = next;
                mapView.removeCallbacks(searchSettled);
                if (query.isEmpty()) {
                    searchGeneration++;
                    foundList.removeAllViews();
                    searchCount.setText("");
                    foundStatus.setText(R.string.zones_search_help);
                    return;
                }
                mapView.postDelayed(searchSettled, SEARCH_SETTLE_MS);
            }
        });
        search.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId != EditorInfo.IME_ACTION_SEARCH)
                    return false;
                mapView.removeCallbacks(searchSettled);
                runSearch();
                return true;
            }
        });
        // Resize rather than pan while typing, so the results stay in view above the
        // keyboard; the host activity's own mode is handed back when focus leaves.
        search.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                resizeForKeyboard(hasFocus);
                if (hasFocus)
                    bringSearchToTop();
            }
        });
        root.findViewById(R.id.zones_search_clear).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        search.setText("");
                    }
                });
        root.findViewById(R.id.zones_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showing = null;
                detailGeneration++;
                showGateOrList();
                renderStarred();
            }
        });
        root.findViewById(R.id.zones_goto).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (showing != null)
                    goTo(showing);
            }
        });
        star.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (showing == null)
                    return;
                favorites.toggle(showing.id, showing.name, showing.cwa);
                paintStar();
            }
        });
        discussionToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                discussionOpen = !discussionOpen;
                final SharedPreferences p = MapCompat.prefs();
                if (p != null)
                    p.edit().putBoolean(PREF_DISCUSSION, discussionOpen).apply();
                paintDiscussion();
            }
        });
    }

    // ---- the zone the pane is reading --------------------------------------------------

    private void locate() {
        final GeoPoint p = host.point();
        if (p == null) {
            hereList.removeAllViews();
            hereStatus.setText("No point yet: " + host.pointLabel());
            return;
        }
        pointDirty = false;
        final double lat = p.getLatitude(), lon = p.getLongitude();
        final String key = FireZones.cellKey(lat, lon);
        final List<FireZones.Zone> known = cells.get(key);
        if (known != null) {
            showHere(FireZones.at(lat, lon, known), lat, lon);
            return;
        }
        if (key.equals(cellInFlight))
            return;
        cellInFlight = key;
        final int mine = ++hereGeneration;
        hereStatus.setText("Finding the zone for " + host.pointLabel() + "…");
        Http.get(FireZones.cellUrl(lat, lon), egress.userAgent(), null, new Http.Callback() {
            @Override
            public void onSuccess(final String body) {
                offMain(new Runnable() {
                    @Override
                    public void run() {
                        final List<FireZones.Zone> parsed = FireZones.parse(body);
                        mapView.post(new Runnable() {
                            @Override
                            public void run() {
                                if (key.equals(cellInFlight))
                                    cellInFlight = null;
                                if (!parsed.isEmpty())
                                    cells.put(key, parsed);
                                if (mine != hereGeneration)
                                    return;
                                // The point may have moved on within the cell while
                                // this was out; ask the pane again.
                                final GeoPoint now = host.point();
                                if (now == null)
                                    return;
                                showHere(FireZones.at(now.getLatitude(), now.getLongitude(),
                                        parsed), now.getLatitude(), now.getLongitude());
                            }
                        });
                    }
                });
            }

            @Override
            public void onFailure(String error) {
                if (key.equals(cellInFlight))
                    cellInFlight = null;
                if (mine != hereGeneration)
                    return;
                pointDirty = true;
                hereList.removeAllViews();
                hereStatus.setText("Could not reach the Weather Service: " + error);
            }
        });
    }

    private void showHere(FireZones.Zone z, double lat, double lon) {
        hereList.removeAllViews();
        if (z == null) {
            // Offshore, or outside the country: there is no fire zone to show.
            hereStatus.setText(host.pointLabel() + " is not in a fire weather zone.");
            return;
        }
        hereStatus.setText(host.pointLabel());
        hereList.addView(row(Pick.of(z)));
    }

    // ---- search -------------------------------------------------------------------------

    private void runSearch() {
        final String q = query;
        final String url = FireZones.searchUrl(q);
        foundList.removeAllViews();
        if (url == null) {
            searchCount.setText("");
            foundStatus.setText(R.string.zones_search_help);
            return;
        }
        final int mine = ++searchGeneration;
        foundStatus.setText("Looking up “" + q + "”…");
        Http.get(url, egress.userAgent(), null, new Http.Callback() {
            @Override
            public void onSuccess(final String body) {
                offMain(new Runnable() {
                    @Override
                    public void run() {
                        final List<FireZones.Zone> parsed = FireZones.parse(body);
                        mapView.post(new Runnable() {
                            @Override
                            public void run() {
                                if (mine != searchGeneration)
                                    return;
                                showFound(q, parsed);
                            }
                        });
                    }
                });
            }

            @Override
            public void onFailure(String error) {
                if (mine != searchGeneration)
                    return;
                searchCount.setText("");
                foundStatus.setText("Could not reach the Weather Service: " + error);
            }
        });
    }

    private void showFound(String q, List<FireZones.Zone> zones) {
        foundList.removeAllViews();
        searchCount.setText(zones.isEmpty() ? "none" : String.valueOf(zones.size()));
        if (zones.isEmpty()) {
            foundStatus.setText("No fire weather zone matches “" + q + "”.");
            return;
        }
        // The service stops at 50; say so rather than let 50 read as all of them.
        foundStatus.setText(zones.size() >= 50
                ? "The first 50 matches. Type more of the name to narrow it."
                : "");
        for (FireZones.Zone z : zones)
            foundList.addView(row(Pick.of(z)));
    }

    // ---- starred ------------------------------------------------------------------------

    private void renderStarred() {
        starredList.removeAllViews();
        final List<ZoneFavorites.Starred> all = favorites.all();
        starredNone.setVisibility(all.isEmpty() ? View.VISIBLE : View.GONE);
        for (ZoneFavorites.Starred s : all)
            starredList.addView(row(new Pick(s.id, s.name, s.cwa, null)));
    }

    // ---- rows ---------------------------------------------------------------------------

    private View row(final Pick z) {
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
        title.setText((favorites.contains(z.id) ? "★ " : "") + z.ugc());
        title.setTextColor(Color.WHITE);
        title.setTextSize(15);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(title);

        final TextView name = new TextView(pluginContext);
        name.setText(z.cwa.isEmpty() ? z.name : z.name + " · " + z.cwa + " office");
        name.setTextColor(0xFFD0D0D0);
        name.setTextSize(13);
        row.addView(name);

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDetail(z);
            }
        });
        return row;
    }

    // ---- one zone -----------------------------------------------------------------------

    /** Open a zone's forecast from outside the page, e.g. a tap on the map layer. */
    public void showZone(String id, String name, String cwa) {
        final String n = FireZones.normalize(id);
        if (n == null)
            return;
        showDetail(new Pick(n, name, cwa, null));
    }

    private void showDetail(final Pick z) {
        showing = z;
        showGateOrList();
        scrollToTop();
        detailTitle.setText(z.ugc() + "  " + z.name);
        detailFacts.setText(z.cwa.isEmpty() ? "" : z.cwa + " office");
        detailText.setText("Getting the forecast…");
        discussionText.setText("");
        paintStar();
        paintDiscussion();
        if (z.cwa.isEmpty()) {
            detailText.setText("The Weather Service did not say which office forecasts "
                    + z.ugc() + ".");
            return;
        }
        final int mine = ++detailGeneration;
        Http.get(Fwf.listUrl(z.cwa), egress.userAgent(), null, new Http.Callback() {
            @Override
            public void onSuccess(String body) {
                if (mine != detailGeneration)
                    return;
                final List<Fwf.Issuance> issued = Fwf.parseList(body, ISSUANCES);
                if (issued.isEmpty()) {
                    detailText.setText("The " + z.cwa + " office has no fire weather planning"
                            + " forecast in the Weather Service's feed right now. Some"
                            + " offices only issue one in fire season.");
                    return;
                }
                fetchIssuance(z, issued, 0, mine);
            }

            @Override
            public void onFailure(String error) {
                if (mine != detailGeneration)
                    return;
                detailText.setText("Could not reach the Weather Service: " + error);
            }
        });
    }

    /** Read issuances newest first until one has a section for the zone. */
    private void fetchIssuance(final Pick z, final List<Fwf.Issuance> issued, final int i,
            final int mine) {
        if (i >= issued.size()) {
            detailText.setText("The newest forecasts from the " + z.cwa + " office do not"
                    + " include " + z.ugc() + ".");
            return;
        }
        final Fwf.Issuance it = issued.get(i);
        Http.get(Fwf.productUrl(it.id), egress.userAgent(), null, new Http.Callback() {
            @Override
            public void onSuccess(String body) {
                if (mine != detailGeneration)
                    return;
                final String text = Fwf.text(body);
                final String section = Fwf.section(text, z.ugc());
                if (section == null) {
                    fetchIssuance(z, issued, i + 1, mine);
                    return;
                }
                showForecast(z, text, section, it.issuedAt);
            }

            @Override
            public void onFailure(String error) {
                if (mine != detailGeneration)
                    return;
                if (i + 1 < issued.size())
                    fetchIssuance(z, issued, i + 1, mine);
                else
                    detailText.setText("Could not reach the Weather Service: " + error);
            }
        });
    }

    private void showForecast(Pick z, String text, String section, long issuedAt) {
        final String office = Fwf.office(text);
        final StringBuilder facts = new StringBuilder();
        facts.append(office.isEmpty() ? z.cwa + " office" : office + " office");
        if (issuedAt > 0) {
            facts.append("\nIssued ").append(clock(issuedAt));
            final long age = System.currentTimeMillis() - issuedAt;
            if (age > OLD_MS)
                facts.append("\nThis is the newest one the office has published, and it is ")
                        .append(age / 86_400_000L < 2 ? "more than a day"
                                : (age / 86_400_000L) + " days")
                        .append(" old.");
        }
        detailFacts.setText(facts.toString());
        detailFacts.setTextColor(issuedAt > 0 && System.currentTimeMillis() - issuedAt > OLD_MS
                ? 0xFFFFC040 : Color.WHITE);
        detailText.setText(section);
        discussionText.setText(Fwf.discussion(text));
        paintDiscussion();
    }

    private void paintStar() {
        final boolean on = showing != null && favorites.contains(showing.id);
        star.setText(on ? R.string.zones_star_on : R.string.zones_star_off);
        star.setTextColor(on ? pluginContext.getResources().getColor(R.color.state_on)
                : Color.WHITE);
    }

    private void paintDiscussion() {
        final boolean has = discussionText.getText() != null
                && discussionText.getText().length() > 0;
        discussionToggle.setVisibility(has ? View.VISIBLE : View.GONE);
        discussionToggle.setText(discussionOpen ? R.string.zones_hide_discussion
                : R.string.zones_show_discussion);
        discussionScroll.setVisibility(has && discussionOpen ? View.VISIBLE : View.GONE);
    }

    /** Fly to the zone, reading its outline first when a starred row has none yet. */
    private void goTo(final Pick z) {
        if (z.bbox != null) {
            fly(z.bbox);
            return;
        }
        Http.get(FireZones.byIdUrl(z.id), egress.userAgent(), null, new Http.Callback() {
            @Override
            public void onSuccess(String body) {
                final List<FireZones.Zone> got = FireZones.parse(body);
                if (got.isEmpty())
                    return;
                z.bbox = got.get(0).bbox();
                if (showing == z)
                    fly(z.bbox);
            }

            @Override
            public void onFailure(String error) {
                Log.w(TAG, "zone outline: " + error);
            }
        });
    }

    private void fly(double[] b) {
        if (b == null || b[0] > b[2] || b[1] > b[3])
            return;
        final GeoPoint c = new GeoPoint((b[1] + b[3]) / 2, (b[0] + b[2]) / 2);
        try {
            final double spanM = Math.max((b[3] - b[1]) * 111_000d,
                    (b[2] - b[0]) * 111_000d * Math.cos(Math.toRadians(c.getLatitude())));
            // A zone fills about two thirds of the width.
            final double res = spanM * 1.5 / Math.max(1, mapView.getWidth());
            mapView.getMapController().panZoomTo(c, mapView.mapResolutionAsMapScale(res),
                    true);
        } catch (LinkageError | RuntimeException e) {
            Log.w(TAG, "zoom to zone failed; plain pan", e);
            mapView.getMapController().panTo(c, true);
        }
    }

    private void askToAllow() {
        final Context ctx = MapCompat.atakContext();
        if (ctx == null)
            return;
        new AlertDialog.Builder(ctx)
                .setTitle(pluginContext.getString(R.string.zones_allow_title))
                .setMessage(pluginContext.getString(R.string.zones_allow_text))
                .setPositiveButton(pluginContext.getString(R.string.allow),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                egress.setLayerEnabled(LAYER_ID, true);
                                pointDirty = true;
                                onShown();
                            }
                        })
                .setNegativeButton(pluginContext.getString(R.string.close), null)
                .show();
    }

    // ---- helpers --------------------------------------------------------------------------

    private void bringSearchToTop() {
        final View row = root.findViewById(R.id.zones_search_row);
        if (!(root instanceof ScrollView) || row == null)
            return;
        final ScrollView scroller = (ScrollView) root;
        scroller.postDelayed(new Runnable() {
            @Override
            public void run() {
                scroller.smoothScrollTo(0, ((View) row.getParent()).getTop() + row.getTop());
            }
        }, 250L);
    }

    /** ATAK's own soft-input mode, so it can be handed back unchanged. */
    private int softInputWas = -1;

    private void resizeForKeyboard(boolean on) {
        try {
            final Context ctx = mapView.getContext();
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
            Log.w(TAG, "soft input mode", e);
        }
    }

    /** "today 3:26 pm", "yesterday 3:26 pm", or the day and date. */
    private static String clock(long when) {
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

    /** On the worker; nothing once the page is gone (the pool is shut on unload). */
    private void offMain(Runnable r) {
        try {
            worker.execute(r);
        } catch (RejectedExecutionException e) {
            Log.d(TAG, "page closed, answer dropped");
        }
    }

    private void scrollToTop() {
        if (root instanceof ScrollView)
            ((ScrollView) root).smoothScrollTo(0, 0);
    }

    private int dp(int v) {
        return Math.round(v * pluginContext.getResources().getDisplayMetrics().density);
    }
}

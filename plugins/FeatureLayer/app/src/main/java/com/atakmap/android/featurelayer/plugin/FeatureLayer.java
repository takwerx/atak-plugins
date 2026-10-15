package com.atakmap.android.featurelayer.plugin;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.atak.plugins.impl.PluginContextProvider;
import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.featurelayer.ArcGisAuth;
import com.atakmap.android.featurelayer.LayerManager;
import com.atakmap.android.featurelayer.LayerSpec;
import com.atakmap.android.featurelayer.LoadedLayer;
import com.atakmap.android.featurelayer.Units;
import com.atakmap.android.featurelayer.Sources;
import com.atakmap.coremap.log.Log;

import java.util.List;
import java.util.concurrent.Callable;

import gov.tak.api.plugin.IPlugin;
import gov.tak.api.plugin.IServiceController;
import gov.tak.api.ui.IHostUIService;
import gov.tak.api.ui.Pane;
import gov.tak.api.ui.PaneBuilder;
import gov.tak.api.ui.ToolbarItem;
import gov.tak.api.ui.ToolbarItemAdapter;
import gov.tak.platform.marshal.MarshalManager;

/**
 * ArcGIS feature layers on the ATAK map: NIFS fires, SARCOP incidents, and whatever
 * comes next. The manager lives for the plugin's life; the pane is its controls.
 */
public class FeatureLayer implements IPlugin {

    private static final String TAG = "FeatureLayer";

    IServiceController serviceController;
    Context pluginContext;
    IHostUIService uiService;
    ToolbarItem toolbarItem;
    Pane pane;
    View paneView;
    LayerManager manager;
    MapView mapView;

    public FeatureLayer(IServiceController serviceController) {
        this.serviceController = serviceController;
        final PluginContextProvider ctxProvider = serviceController
                .getService(PluginContextProvider.class);
        if (ctxProvider != null) {
            pluginContext = ctxProvider.getPluginContext();
            pluginContext.setTheme(R.style.ATAKPluginTheme);
        }
        uiService = serviceController.getService(IHostUIService.class);
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

    private static final String PREFS_KEY = "featurelayerPreference";

    /** The Tool Preferences entry the manual is reached through; a build without the class costs the manual, not the plugin. */
    private void registerPreferences() {
        try {
            com.atakmap.app.preferences.ToolsPreferenceFragment.register(
                    new com.atakmap.app.preferences.ToolsPreferenceFragment.ToolPreference(
                            pluginContext.getString(R.string.app_name),
                            pluginContext.getString(R.string.prefs_summary),
                            PREFS_KEY,
                            pluginContext.getResources().getDrawable(R.drawable.ic_toolbar),
                            new FeatureLayerPreferenceFragment(pluginContext)));
        } catch (LinkageError | RuntimeException notThisBuild) {
            Log.w(TAG, "could not register preferences: " + notThisBuild);
        }
    }

    private void unregisterPreferences() {
        try {
            com.atakmap.app.preferences.ToolsPreferenceFragment.unregister(PREFS_KEY);
        } catch (LinkageError | RuntimeException notThisBuild) {
            Log.w(TAG, "could not unregister preferences: " + notThisBuild);
        }
    }

    @Override
    public void onStart() {
        if (uiService != null)
            uiService.addToolbarItem(toolbarItem);
        registerPreferences();
        mapView = MapView.getMapView();
        if (mapView != null && manager == null) {
            manager = new LayerManager(mapView, pluginContext, BuildConfig.ARCGIS_CLIENT_ID);
            manager.start();
        }
        followMap(true);
        registerShow();
    }

    /**
     * Opens the pane: a system broadcast, so a test over adb can open it without driving
     * the Tools list. Debug builds only: a system receiver is exported to every app on
     * the phone, and a release build has no use for one.
     */
    public static final String SHOW_ACTION = "com.atakmap.android.featurelayer.SHOW";
    private android.content.BroadcastReceiver showReceiver;

    private void registerShow() {
        if (showReceiver != null || !BuildConfig.DEBUG)
            return;
        showReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context context, android.content.Intent intent) {
                if (SHOW_ACTION.equals(intent.getAction()))
                    showPane();
            }
        };
        try {
            final com.atakmap.android.ipc.AtakBroadcast.DocumentedIntentFilter f =
                    new com.atakmap.android.ipc.AtakBroadcast.DocumentedIntentFilter();
            f.addAction(SHOW_ACTION);
            com.atakmap.android.ipc.AtakBroadcast.getInstance().registerSystemReceiver(showReceiver, f);
        } catch (Exception e) {
            Log.w(TAG, "show receiver", e);
            showReceiver = null;
        }
    }

    private void unregisterShow() {
        if (showReceiver == null)
            return;
        try {
            com.atakmap.android.ipc.AtakBroadcast.getInstance().unregisterSystemReceiver(showReceiver);
        } catch (Exception ignored) {
        }
        showReceiver = null;
    }

    @Override
    public void onStop() {
        // Close our pane: ATAK keeps a plugin's pane on screen across a reload, and a
        // pane whose buttons point at a stopped instance does nothing when tapped.
        if (pane != null && uiService != null) {
            try {
                if (uiService.isPaneVisible(pane))
                    uiService.closePane(pane);
            } catch (Exception ignored) {
            }
            pane = null;
            paneView = null;
        }
        followMap(false);
        if (manager != null) {
            manager.stop();
            manager = null;
        }
        if (uiService != null)
            uiService.removeToolbarItem(toolbarItem);
        unregisterPreferences();
        unregisterShow();
    }

    private void toast(String s) {
        Log.d(TAG, "toast: " + s);
        Toast.makeText(mapView.getContext(), s, Toast.LENGTH_LONG).show();
    }

    // ---- orgs ---------------------------------------------------------------------

    /** A place to get layers from: a portal to sign into, and what "find" means there. */
    private static class Org {
        final String id, title, portal, findLabel, hint;
        final boolean custom;

        Org(String id, String title, String portal, String findLabel, String hint) {
            this(id, title, portal, findLabel, hint, false);
        }

        Org(String id, String title, String portal, String findLabel, String hint, boolean custom) {
            this.id = id;
            this.title = title;
            this.portal = portal;
            this.findLabel = findLabel;
            this.hint = hint;
            this.custom = custom;
        }
    }

    private static final String ADD_ORG = "Add your own org\u2026";

    private static final Org[] BUILT_IN = {
            new Org("nifc", "NIFC", Sources.NIFC_PORTAL, "Find fire", "Search"),
            new Org("new-starts", "New Fire Starts", null, "Add new starts",
                    "Wildfires and prescribed fires reported in the last 24 hours, public"),
            new Org("ongoing", "Ongoing Fires", null, "Add ongoing fires",
                    "Fires found more than a day ago and not yet contained, public"),
            new Org("fire-history", "Fire History", null, "Add fire history",
                    "Where fires have burned since 1900, by years since and by decade, public"),
            new Org("boundaries", "Boundaries", null, "Add",
                    "County, city and tribal land boundaries by state, public"),
            new Org("sarcop-live", "SARCOP Live", Sources.NAPSG_PORTAL, "Find incident", "Search"),
            new Org("sarcop-training", "SARCOP Training", null, "Find incident", "Search"),
            new Org("ca-air-intel", "CA Air Intel", null, "Add perimeters", "Statewide fire perimeters, public"),
            new Org("nifs-archive", "NIFS Archive (demo)", null, "Load Dragon Bravo", "Last year's Dragon Bravo, public"),
    };

    private Org org;

    private android.content.SharedPreferences uiPrefs() {
        return mapView.getContext().getSharedPreferences("featurelayer.ui", Context.MODE_PRIVATE);
    }

    /** Built-in orgs plus the user's own, from prefs. */
    private List<Org> orgs() {
        final List<Org> out = new java.util.ArrayList<>(java.util.Arrays.asList(BUILT_IN));
        try {
            final org.json.JSONArray arr = new org.json.JSONArray(uiPrefs().getString("customOrgs", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                // Saved as {portal, name}; older entries were the bare portal string.
                final Object e = arr.get(i);
                final String portal = e instanceof org.json.JSONObject ? ((org.json.JSONObject) e).optString("portal") : String.valueOf(e);
                final String name = e instanceof org.json.JSONObject ? ((org.json.JSONObject) e).optString("name", null) : null;
                out.add(new Org("custom:" + portal, name != null && !name.isEmpty() ? name : LayerManager.hostOf(portal), portal,
                        "Find layer", "Search", true));
            }
        } catch (Exception e) {
            Log.w(TAG, "custom orgs unreadable", e);
        }
        return out;
    }

    private Org orgById(String id) {
        for (Org o : orgs())
            if (o.id.equals(id))
                return o;
        return null;
    }

    private static String portalOf(Object entry) {
        return entry instanceof org.json.JSONObject ? ((org.json.JSONObject) entry).optString("portal") : String.valueOf(entry);
    }

    private void addCustomOrg(String portal) {
        try {
            final org.json.JSONArray arr = new org.json.JSONArray(uiPrefs().getString("customOrgs", "[]"));
            for (int i = 0; i < arr.length(); i++)
                if (portalOf(arr.get(i)).equalsIgnoreCase(portal))
                    return;
            arr.put(new org.json.JSONObject().put("portal", portal));
            uiPrefs().edit().putString("customOrgs", arr.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "custom org save failed", e);
        }
        resolveOrgName(portal);
    }

    /** Drops an org the operator added (a fat-fingered address, a place they no longer work with). */
    private void forgetCustomOrg(final Org o) {
        if (o == null || !o.custom)
            return;
        new AlertDialog.Builder(mapView.getContext())
                .setTitle("Forget " + o.title + "?")
                .setMessage("It leaves the source list and its sign-in is dropped. Layers already added from it stay.")
                .setPositiveButton("Forget", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        try {
                            final org.json.JSONArray arr = new org.json.JSONArray(uiPrefs().getString("customOrgs", "[]"));
                            final org.json.JSONArray out = new org.json.JSONArray();
                            for (int i = 0; i < arr.length(); i++)
                                if (!portalOf(arr.get(i)).equalsIgnoreCase(o.portal))
                                    out.put(arr.get(i));
                            uiPrefs().edit().putString("customOrgs", out.toString()).remove("org").apply();
                        } catch (Exception e) {
                            Log.w(TAG, "forget org failed", e);
                        }
                        try {
                            if (manager != null)
                                manager.auth(o.portal).signOut();
                        } catch (Exception ignored) {
                        }
                        org = null;
                        refreshOrgUi();
                        toast(o.title + " forgotten");
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Remembers the org's name for a portal; the picker and every row use it from then on. */
    private void saveOrgName(String portal, String name) {
        try {
            final org.json.JSONArray arr = new org.json.JSONArray(uiPrefs().getString("customOrgs", "[]"));
            final org.json.JSONArray out = new org.json.JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                final String pt = portalOf(arr.get(i));
                final org.json.JSONObject o = new org.json.JSONObject().put("portal", pt);
                final String old = arr.get(i) instanceof org.json.JSONObject ? ((org.json.JSONObject) arr.get(i)).optString("name", null) : null;
                o.put("name", pt.equalsIgnoreCase(portal) ? name : old);
                out.put(o);
            }
            uiPrefs().edit().putString("customOrgs", out.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "custom org name save failed", e);
        }
        if (org != null && org.portal != null && org.portal.equalsIgnoreCase(portal))
            org = orgById(org.id);
        refreshOrgUi();
    }

    /** Asks the portal what the org is called; with a token when signed in, so a private org answers too. */
    private void resolveOrgName(final String portal) {
        if (manager == null)
            return;
        manager.search(new Callable<String>() {
            @Override
            public String call() {
                final ArcGisAuth a = manager.auth(portal);
                return com.atakmap.android.featurelayer.Esri.portalName(portal, a.isSignedIn() ? a.getValidToken() : null);
            }
        }, new LayerManager.SearchCallback<String>() {
            @Override
            public void onResult(String name, String error) {
                if (name != null && !name.isEmpty())
                    saveOrgName(portal, name);
            }
        });
    }

    /** "myagency.maps.arcgis.com" or a full https URL, to a portal base with no trailing slash. */
    private static String normalizePortal(String text) {
        String t = text.trim();
        if (t.isEmpty())
            return null;
        if (t.startsWith("http://"))
            t = "https://" + t.substring("http://".length()); // a token never travels in the clear
        if (!t.startsWith("https://"))
            t = "https://" + t;
        t = t.replaceFirst("/+$", "");
        // Strip anything past the host: users paste item or home pages.
        final int slash = t.indexOf('/', "https://".length());
        if (slash > 0)
            t = t.substring(0, slash);
        return t;
    }

    private void askCustomOrg() {
        final Context ctx = mapView.getContext();
        final EditText input = new EditText(ctx);
        input.setHint("myagency.maps.arcgis.com");
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);
        final AlertDialog dialog = new AlertDialog.Builder(ctx)
                .setTitle("Your org's ArcGIS address")
                .setMessage("The address you sign into ArcGIS Online with, or your agency's ArcGIS Enterprise portal.")
                .setView(input)
                .setPositiveButton("Add", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        final String portal = normalizePortal(input.getText().toString());
                        if (portal == null) {
                            toast("No address entered");
                            return;
                        }
                        addCustomOrg(portal);
                        org = orgById("custom:" + portal);
                        uiPrefs().edit().putString("org", org.id).apply();
                        refreshOrgUi();
                    }
                })
                .setNegativeButton("Cancel", null)
                .create();
        dialog.show();
        final android.view.Window w = dialog.getWindow();
        if (w != null)
            w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
        input.requestFocus();
    }

    // ---- pane ---------------------------------------------------------------------

    private void showPane() {
        if (pane == null) {
            paneView = PluginLayoutInflater.inflate(pluginContext, R.layout.main_layout, null);
            // Starting to scroll means done typing. A text box at the top of the scroller
            // that still holds focus pulls it back up to itself on every redraw, and the
            // Find list redraws as the layers refresh: scrolled down a list of fires, it
            // kept jumping back to the box (operator, 2026-10-05).
            paneView.findViewById(R.id.pane_scroll).setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, android.view.MotionEvent e) {
                    if (e.getActionMasked() == android.view.MotionEvent.ACTION_MOVE)
                        doneTyping();
                    return false;
                }
            });
            final Button orgButton = paneView.findViewById(R.id.btn_org);
            final Button signin = paneView.findViewById(R.id.btn_signin);
            final Button find = paneView.findViewById(R.id.btn_find);
            final EditText search = paneView.findViewById(R.id.search_text);
            org = orgById(uiPrefs().getString("org", ""));
            orgButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    pickOrg();
                }
            });
            signin.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (org == null || org.portal == null || manager == null)
                        return;
                    final ArcGisAuth auth = manager.auth(org.portal);
                    if (auth.isSignedIn()) {
                        auth.signOut();
                        refreshOrgUi();
                        toast(org.title + ": signed out");
                        return;
                    }
                    // The sign-in page is a drop-down of its own, and opening it closes
                    // this pane; nothing brought the pane back, so every sign-in ended on
                    // the bare map (operator, 2026-10-05: "after i log into nifc the pane
                    // closes i have to reopen plugin"). It comes back when the sign-in
                    // ends either way but cancelled: a cancel may be another tool taking
                    // the side of the screen, and reopening here would fight it.
                    auth.signIn(new ArcGisAuth.Callback() {
                        @Override
                        public void onSignedIn(String username) {
                            showPane();
                            refreshOrgUi();
                            toast(org.title + ": signed in as " + username);
                            if (org.custom && org.title.equals(LayerManager.hostOf(org.portal)))
                                resolveOrgName(org.portal); // a private org only names itself to a member
                            manager.refreshAll();
                        }

                        @Override
                        public void onFailed(String reason) {
                            if (!"cancelled".equals(reason))
                                showPane();
                            toast(org.title + ": sign-in failed: " + reason);
                        }
                    });
                }
            });
            find.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onFind(find, search.getText().toString().trim());
                }
            });
            // The main screen's row: Add Layer opens the sources, Find searches every
            // loaded layer whatever it came from (operator, 2026-10-05: the source row
            // made a feature search something only NIFC had).
            paneView.findViewById(R.id.btn_add_layer).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showAddPanel();
                }
            });
            paneView.findViewById(R.id.btn_find_all).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (manager == null || manager.snapshot().isEmpty()) {
                        toast("Nothing loaded yet: Add Layer first");
                        return;
                    }
                    findFeature("", null);
                }
            });
            // Clears the source search box, beside its Find.
            paneView.findViewById(R.id.btn_clear_search).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    search.setText("");
                }
            });
            // The keyboard's search key does the same, and closes the keyboard.
            search.setOnEditorActionListener(new TextView.OnEditorActionListener() {
                @Override
                public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
                    if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                            || (event != null && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER
                                    && event.getAction() == android.view.KeyEvent.ACTION_DOWN)) {
                        final android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                                mapView.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                        if (imm != null)
                            imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
                        onFind(find, search.getText().toString().trim());
                        return true;
                    }
                    return false;
                }
            });
            if (manager != null)
                manager.setListener(new LayerManager.Listener() {
                    @Override
                    public void onChanged() {
                        renderRows();
                        // The Features panel of a live layer follows its fetches too, in
                        // place: new vehicle types appear and counts move as the map pans.
                        // Only while it is the panel on screen: with Find open on top of it
                        // this re-showed the type list under the results every minute, and
                        // the results "reverted" (2026-09-18). Find refreshes its own rows.
                        final LoadedLayer showing = featuresFor;
                        final View search = paneView == null ? null : paneView.findViewById(R.id.search_panel);
                        final boolean finding = search != null && search.getVisibility() == View.VISIBLE;
                        final View details = paneView == null ? null : paneView.findViewById(R.id.details_panel);
                        final boolean reading = details != null && details.getVisibility() == View.VISIBLE;
                        // Details up: leave them alone. The in-place Features redraw put its
                        // panel back over the details on every refresh tick (S22, 2026-09-19).
                        if (showing != null && !showing.refreshing && !showing.busy && !reading) {
                            if (finding)
                                refreshResultsInPlace();
                            else
                                pickSets(showing, true);
                        } else if (showing == null && finding && !reading && scope == null && !scanning(null)) {
                            // Find over every layer, opened from the main screen: its rows
                            // follow the fetches once they have all landed, not per tick.
                            refreshResultsInPlace();
                        }
                    }
                });
            refreshOrgUi();
            renderRows();
            pane = new PaneBuilder(paneView)
                    .setMetaValue(Pane.RELATIVE_LOCATION, Pane.Location.Default)
                    .setMetaValue(Pane.PREFERRED_WIDTH_RATIO, 0.5D)
                    .setMetaValue(Pane.PREFERRED_HEIGHT_RATIO, 0.5D)
                    .build();
        }
        if (!uiService.isPaneVisible(pane))
            uiService.showPane(pane, null);
    }

    /** MapView context, never plugin context: a dialog on the plugin context kills ATAK. */
    private void pickOrg() {
        final List<Org> all = orgs();
        final String[] titles = new String[all.size() + 1];
        int checked = -1;
        for (int i = 0; i < all.size(); i++) {
            titles[i] = all.get(i).title;
            if (org != null && org.id.equals(all.get(i).id))
                checked = i;
        }
        titles[all.size()] = ADD_ORG;
        new AlertDialog.Builder(mapView.getContext())
                .setTitle("Pick a source")
                .setSingleChoiceItems(titles, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        d.dismiss();
                        if (which == all.size()) {
                            askCustomOrg();
                            return;
                        }
                        org = all.get(which);
                        uiPrefs().edit().putString("org", org.id).apply();
                        refreshOrgUi();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * The main pane's Feature button: a search is always inside one layer, so with one
     * loaded it opens there, and with more it asks which first.
     */
    /** The Add Layer page: the sources, sign-in and fire search, with Back to the list. */
    private void showAddPanel() {
        if (paneView == null)
            return;
        paneView.findViewById(R.id.main_panel).setVisibility(View.GONE);
        paneView.findViewById(R.id.add_panel).setVisibility(View.VISIBLE);
        ((TextView) paneView.findViewById(R.id.features_title)).setText("Add a layer");
        paneView.findViewById(R.id.features_header).setVisibility(View.VISIBLE);
        paneView.findViewById(R.id.btn_features_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                backToMain();
            }
        });
        refreshOrgUi();
        scrollPaneToTop();
    }

    /** The main screen again, from the Add page or after a layer was added there. */
    private void backToMain() {
        if (paneView == null)
            return;
        // The search box on the Add page may hold focus; hidden with it, focus lands on
        // a list row and the scroller follows it down.
        final View focused = paneView.findFocus();
        if (focused != null)
            focused.clearFocus();
        paneView.findViewById(R.id.add_panel).setVisibility(View.GONE);
        paneView.findViewById(R.id.features_header).setVisibility(View.GONE);
        paneView.findViewById(R.id.main_panel).setVisibility(View.VISIBLE);
        renderRows();
        scrollPaneToTop();
    }

    /** Adds a layer and goes back to the list, where its row now is: the Add page has done its job. */
    private void addLayer(com.atakmap.android.featurelayer.LayerSpec spec) {
        if (manager == null)
            return;
        manager.add(spec);
        backToMain();
    }

    // ---- the search pane: every loaded feature, filtered and sorted, with Back ----------

    private static final int RESULT_CAP = 200;
    /**
     * The typed name, its trailing year dropped ("Ranch 2007" is Ranch), as a pattern that
     * matches only at the start of a word; null when nothing is typed.
     */
    static java.util.regex.Pattern wordStart(String text) {
        if (text == null)
            return null;
        final String name = text.trim().replaceAll("\\s*\\b(1[89]\\d\\d|20\\d\\d)$", "").trim();
        if (name.isEmpty())
            return null;
        return java.util.regex.Pattern.compile("(^|[^A-Za-z0-9])" + java.util.regex.Pattern.quote(name),
                java.util.regex.Pattern.CASE_INSENSITIVE);
    }

    static boolean startsWord(java.util.regex.Pattern word, String title) {
        return title != null && word.matcher(title).find();
    }

    private static final String[] SORT_LABELS = { "Nearest", "Newest", "Oldest", "Name" };
    private String filterType;      // null = all
    private LoadedLayer scope;      // opened from a layer's Features: only that layer, Back returns there
    private boolean fromMapCenter;  // distances from where the map is now, instead of from the device
    private int sortMode;           // index into SORT_LABELS
    private List<Object[]> shownHits;  // the rows on screen now, in their drawn order; null on the type list

    // ---- the list follows what it is measured from -----------------------------------

    /**
     * "From: Map center" is where the map is now and "From: Me" is where the device is;
     * neither stands still. Until 0.6 a distance was worked out once, when the list was
     * drawn, so panning the map left every row reading its distance from wherever the map
     * used to be -- worse than no distance, because it still looks right.
     *
     * <p>Neither callback arrives on the main thread: {@code onMapMoved} runs on the GL
     * render thread, where touching a View is a native SIGSEGV with no Java stack trace.
     * Each one posts onto the main looper and coalesces, so a drag redraws once when it
     * settles rather than on every frame.
     */
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private com.atakmap.coremap.maps.coords.GeoPoint lastFrom;
    private static final double FOLLOW_M = 10; // a move shorter than this changes no row

    private final com.atakmap.map.AtakMapView.OnMapMovedListener mapWatch =
            new com.atakmap.map.AtakMapView.OnMapMovedListener() {
                @Override
                public void onMapMoved(com.atakmap.map.AtakMapView v, boolean animate) {
                    handler.removeCallbacks(followTick);
                    handler.postDelayed(followTick, 300);
                }
            };

    private final com.atakmap.android.maps.PointMapItem.OnPointChangedListener selfWatch =
            new com.atakmap.android.maps.PointMapItem.OnPointChangedListener() {
                @Override
                public void onPointChanged(com.atakmap.android.maps.PointMapItem item) {
                    handler.removeCallbacks(followTick);
                    handler.postDelayed(followTick, 300);
                }
            };

    private final Runnable followTick = new Runnable() {
        @Override
        public void run() {
            refreshDistances();
        }
    };

    private com.atakmap.android.maps.PointMapItem watched;

    /**
     * ATAK replaces the self marker when the device identity changes, so the listener goes
     * on whatever marker is there now and is re-attached when the search pane opens,
     * rather than being held for the plugin's life.
     */
    private void followMap(boolean on) {
        try {
            if (watched != null) {
                watched.removeOnPointChangedListener(selfWatch);
                watched = null;
            }
            if (mapView == null)
                return;
            handler.removeCallbacks(followTick);
            mapView.removeOnMapMovedListener(mapWatch);
            if (!on)
                return;
            mapView.addOnMapMovedListener(mapWatch);
            final com.atakmap.android.maps.Marker self = mapView.getSelfMarker();
            if (self != null) {
                self.addOnPointChangedListener(selfWatch);
                watched = self;
            }
        } catch (LinkageError | RuntimeException e) {
            Log.w(TAG, "could not follow the map; distances will not update as it moves", e);
        }
    }

    /** Where distances are measured from right now: the device, or the map's center. */
    private com.atakmap.coremap.maps.coords.GeoPoint measureFrom() {
        final com.atakmap.coremap.maps.coords.GeoPoint me = fromMapCenter ? null : selfPoint();
        return me != null ? me : mapCenter();
    }

    /**
     * Redraws the distances after a move. Sorting by distance reorders the list, so that
     * one redraws the list; every other sort keeps its order and only the numbers change,
     * which leaves the operator's scroll position where they put it.
     */
    private void refreshDistances() {
        if (paneView == null || manager == null || shownHits == null)
            return;
        final View searchPanel = paneView.findViewById(R.id.search_panel);
        if (searchPanel == null || searchPanel.getVisibility() != View.VISIBLE)
            return;
        final com.atakmap.coremap.maps.coords.GeoPoint from = measureFrom();
        if (from == null)
            return;
        if (lastFrom != null
                && com.atakmap.coremap.maps.coords.GeoCalculations.distanceTo(from, lastFrom) < FOLLOW_M)
            return;
        if (sortMode == 0) {
            renderResults();
            return;
        }
        lastFrom = from;
        final LinearLayout container = paneView.findViewById(R.id.results_container);
        final int n = Math.min(shownHits.size(), container.getChildCount());
        for (int i = 0; i < n; i++) {
            final LoadedLayer.Hit h = (LoadedLayer.Hit) shownHits.get(i)[1];
            ((TextView) container.getChildAt(i).findViewById(R.id.result_dist))
                    .setText(com.atakmap.android.featurelayer.Units.format(
                            com.atakmap.coremap.maps.coords.GeoCalculations.distanceTo(from,
                                    new com.atakmap.coremap.maps.coords.GeoPoint(h.lat, h.lon))));
        }
    }

    // ---- zoom gate helpers (Cam Depot's scale-bar language) --------------------------

    /** Presets in the operator's big unit, as the scale bar would read them. */
    private static final double[] GATE_BIG = { 0.25, 1, 5, 15, 50 };

    private static String gateName(double big) {
        final String num = big == Math.floor(big) ? String.format(java.util.Locale.US, "%.0f", big)
                : String.format(java.util.Locale.US, "%.2f", big);
        return num + " " + com.atakmap.android.featurelayer.Units.bigLabel() + " or closer";
    }

    /** Pixels the scale bar spans, so a quoted threshold matches its text. */
    private double scaleBarPixels() {
        final double res = mapView.getMapResolution();
        if (res <= 0)
            return 200;
        final double m = com.atakmap.android.featurelayer.ScaleBar.meters(mapView);
        return m > 0 ? m / res : 200;
    }

    // A level is printed and picked through one nominal bar length, not the live scale
    // bar: the same 40 m/px read "5 mi" on the XCover and "9.38 mi" on the S22
    // (2026-09-19), because the bar's pixel length differs per phone and zoom.
    private String labelLevel(LoadedLayer l) {
        if (l.spec.labelGsd == Double.MAX_VALUE)
            return "Always";
        return com.atakmap.android.featurelayer.ScaleBar.describe(l.spec.labelGsd * com.atakmap.android.featurelayer.ScaleBar.FALLBACK_BAR_PIXELS) + " or closer";
    }

    private String gateLabel(LoadedLayer l) {
        if (l.spec.gateGsd == Double.MAX_VALUE)
            return "Always";
        return com.atakmap.android.featurelayer.ScaleBar.describe(l.spec.gateGsd * com.atakmap.android.featurelayer.ScaleBar.FALLBACK_BAR_PIXELS) + " or closer";
    }

    /** Keyboard away and no text box focused, so the scroller stays where the operator puts it. */
    private void doneTyping() {
        if (paneView == null)
            return;
        final View f = paneView.findFocus();
        if (!(f instanceof EditText))
            return;
        final android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                mapView.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null)
            imm.hideSoftInputFromWindow(f.getWindowToken(), 0);
        f.clearFocus();
    }

    /** Opens the search pane with the text inside one layer: a fire, an incident, a source. */
    private void findFeature(String text, LoadedLayer only) {
        if (manager == null || paneView == null)
            return;
        scope = only; // null: every loaded layer, from the main screen's Find
        filterType = null;
        // The sort and the measuring point come back the way they were left.
        sortMode = uiPrefs().getInt("sortMode", 0);
        fromMapCenter = uiPrefs().getBoolean("fromMapCenter", false);
        final View mainPanel = paneView.findViewById(R.id.main_panel);
        final View featuresPanel = paneView.findViewById(R.id.features_panel);
        final View searchPanel = paneView.findViewById(R.id.search_panel);
        final View header = paneView.findViewById(R.id.features_header);
        ((TextView) paneView.findViewById(R.id.features_title)).setText(only == null ? "Find features" : "Find in " + only.spec.title);
        final EditText box = paneView.findViewById(R.id.result_search_text);
        box.setText(text);
        final View.OnClickListener doFind = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doneTyping();
                renderResults();
            }
        };
        paneView.findViewById(R.id.btn_result_find).setOnClickListener(doFind);
        // Clear: empty box, no type picked, back to the list of types.
        paneView.findViewById(R.id.btn_result_clear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                box.setText("");
                filterType = null;
                renderResults();
            }
        });
        box.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                    doneTyping();
                    renderResults();
                    return true;
                }
                return false;
            }
        });
        paneView.findViewById(R.id.btn_filter_type).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickType();
            }
        });
        // "Map center" means where the map is now, re-read on every render, as in Cam Depot.
        paneView.findViewById(R.id.btn_from).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                fromMapCenter = !fromMapCenter;
                uiPrefs().edit().putBoolean("fromMapCenter", fromMapCenter).apply();
                renderResults();
            }
        });
        paneView.findViewById(R.id.btn_sort).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(mapView.getContext()).setTitle("Sort by")
                        .setSingleChoiceItems(SORT_LABELS, sortMode, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                sortMode = which;
                                uiPrefs().edit().putInt("sortMode", sortMode).apply();
                                d.dismiss();
                                renderResults();
                            }
                        }).setNegativeButton("Cancel", null).show();
            }
        });
        paneView.findViewById(R.id.btn_features_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                searchPanel.setVisibility(View.GONE);
                paneView.findViewById(R.id.details_panel).setVisibility(View.GONE);
                paneView.findViewById(R.id.features_header).setVisibility(View.VISIBLE);
                header.setVisibility(View.GONE);
                if (scope != null) {
                    pickSets(scope); // back to the layer's Features, where this came from
                    return;
                }
                mainPanel.setVisibility(View.VISIBLE);
                renderRows();
            }
        });
        mainPanel.setVisibility(View.GONE);
        featuresPanel.setVisibility(View.GONE);
        header.setVisibility(View.VISIBLE);
        searchPanel.setVisibility(View.VISIBLE);
        followMap(true);   // onto the self marker that is there now
        renderResults();
    }

    /** Where the device is, or null without a fix. */
    private com.atakmap.coremap.maps.coords.GeoPoint selfPoint() {
        try {
            final com.atakmap.android.maps.Marker self = mapView.getSelfMarker();
            final com.atakmap.coremap.maps.coords.GeoPoint p = self == null ? null : self.getPoint();
            if (p == null || !p.isValid() || (p.getLatitude() == 0 && p.getLongitude() == 0))
                return null;
            return p;
        } catch (Exception e) {
            return null;
        }
    }

    /** The matches for the box's text across every loaded layer, before the type filter. */
    private List<Object[]> currentHits() {
        final String text = ((EditText) paneView.findViewById(R.id.result_search_text)).getText().toString().trim();
        final List<Object[]> hits = new java.util.ArrayList<>();
        for (LoadedLayer l : manager.snapshot()) {
            if (scope != null && l != scope)
                continue;
            // "What is in view" means the view now, not the box the last fetch covered:
            // zooming in stays inside that box, so nothing refetches, and the list kept
            // every vehicle from the wider view (2026-09-18, "the list is not updating").
            com.atakmap.coremap.maps.coords.GeoBounds view = null;
            if ("view".equals(l.spec.scopeKind)) {
                try {
                    view = mapView.getBounds();
                } catch (RuntimeException ignored) {
                }
            }
            for (LoadedLayer.Hit h : l.find(text, 5000)) {
                if (view != null && (h.lat < view.getSouth() || h.lat > view.getNorth()
                        || h.lon < view.getWest() || h.lon > view.getEast()))
                    continue;
                hits.add(new Object[] { l, h });
            }
        }
        return hits;
    }

    /**
     * Where a row that only mentions the text says it: the field and a few words around
     * the match, "Comments: ...Primarily replace rails on fence...".
     */
    private static String mentionOf(LoadedLayer.Hit h, String text) {
        final String v = h.matchedText == null ? "" : h.matchedText.replace('\n', ' ').trim();
        final int at = v.toLowerCase(java.util.Locale.US).indexOf(text.toLowerCase(java.util.Locale.US));
        final int from = Math.max(0, at - 15), to = Math.min(v.length(), Math.max(at, 0) + text.length() + 30);
        final String cut = (from > 0 ? "\u2026" : "") + v.substring(from, to) + (to < v.length() ? "\u2026" : "");
        return h.matchedIn + ": \"" + cut + "\"";
    }

    /** Where the map is looking right now. */
    private com.atakmap.coremap.maps.coords.GeoPoint mapCenter() {
        try {
            return mapView.getPoint().get();
        } catch (Exception e) {
            return null;
        }
    }

    private void pickType() {
        final java.util.Map<String, Integer> counts = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        int total = 0;
        for (Object[] o : currentHits()) {
            final String t = ((LoadedLayer.Hit) o[1]).type;
            counts.put(t, counts.containsKey(t) ? counts.get(t) + 1 : 1);
            total++;
        }
        final List<String> types = new java.util.ArrayList<>(counts.keySet());
        final String[] labels = new String[types.size() + 1];
        labels[0] = "All types (" + total + ")";
        int checked = 0;
        for (int i = 0; i < types.size(); i++) {
            labels[i + 1] = types.get(i) + " (" + counts.get(types.get(i)) + ")";
            if (types.get(i).equalsIgnoreCase(filterType))
                checked = i + 1;
        }
        new AlertDialog.Builder(mapView.getContext()).setTitle("Show only")
                .setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        filterType = which == 0 ? null : types.get(which - 1);
                        d.dismiss();
                        renderResults();
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private void renderResults() {
        if (paneView == null || manager == null)
            return;
        final LinearLayout container = paneView.findViewById(R.id.results_container);
        final TextView status = paneView.findViewById(R.id.search_status);
        final Button typeBtn = paneView.findViewById(R.id.btn_filter_type);
        final Button fromBtn = paneView.findViewById(R.id.btn_from);
        final Button sortBtn = paneView.findViewById(R.id.btn_sort);
        final List<Object[]> all = currentHits();
        typeBtn.setText(filterType == null ? "All types" : filterType);
        fromBtn.setText(fromMapCenter ? "From: Map center" : "From: Me");
        sortBtn.setText(SORT_LABELS[sortMode]);
        final String text = ((EditText) paneView.findViewById(R.id.result_search_text)).getText().toString().trim();
        if (text.isEmpty() && filterType == null) {
            // Nothing typed and nothing picked: the kinds of things that are loaded, with
            // counts, so "what is out there" is one tap and a type is one more.
            final java.util.Map<String, Integer> counts = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (Object[] o : all) {
                final String t = ((LoadedLayer.Hit) o[1]).type;
                counts.put(t, counts.containsKey(t) ? counts.get(t) + 1 : 1);
            }
            // The picker is the catalog of what the layer carries, not what happens to be
            // in view: a DART layer's types are its sets, every kind ever seen, so
            // "Command" can be picked with none in view and the list fills as the map
            // pans (operator, 2026-09-18: "your current view should not limit that").
            // The count beside each is what is in view now.
            if (scope != null && com.atakmap.android.featurelayer.DartStyles.handles(scope.spec))
                for (LoadedLayer.SetInfo si : scope.types())
                    if (!counts.containsKey(si.name))
                        counts.put(si.name, 0);
            status.setText(withLegend(counts.isEmpty() ? emptyOrScanning(null, scope) + scopeNote(scope)
                    : counts.size() + " types, " + all.size() + " in view" + scopeNote(scope)
                            + (scanning(scope) ? " \u00b7 updating\u2026" : "") + " \u00b7 tap a type, or type a name", scope));
            shownHits = null;   // the type list carries counts, not distances
            container.removeAllViews();
            for (final java.util.Map.Entry<String, Integer> e : counts.entrySet()) {
                final View row = PluginLayoutInflater.inflate(pluginContext, R.layout.result_row, null);
                row.findViewById(R.id.result_go).setVisibility(View.GONE); // a type row lists, it does not go
                ((TextView) row.findViewById(R.id.result_title)).setText(e.getKey());
                ((TextView) row.findViewById(R.id.result_sub)).setText("");
                ((TextView) row.findViewById(R.id.result_goto)).setText("tap to list");
                ((TextView) row.findViewById(R.id.result_dist)).setText(e.getValue() + " in view");
                row.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        filterType = e.getKey();
                        renderResults();
                    }
                });
                container.addView(row);
            }
            return;
        }
        final List<Object[]> hits = new java.util.ArrayList<>();
        // A typed name in a scoped layer: the feed's own answer, anywhere, once it is in;
        // the cached view's matches until then.
        final List<LoadedLayer.Hit> fromFeed = text.isEmpty() ? null : feedHitsFor(text, scope);
        // Find from the main screen with a name typed: Fire History answers from all of
        // its history, not only what is loaded in view (operator, 2026-10-06: "can i do
        // a search for a feature?"). Its view's matches stand until the answer is in.
        final LoadedLayer history = scope == null && !text.isEmpty() ? historyLayer() : null;
        final List<LoadedLayer.Hit> fromHistory = history == null ? null : feedHitsFor(text, history);
        if (fromFeed != null) {
            for (LoadedLayer.Hit h : fromFeed)
                if (filterType == null || filterType.equalsIgnoreCase(h.type))
                    hits.add(new Object[] { scope, h });
        } else {
            for (Object[] o : all) {
                if (fromHistory != null && o[0] == history)
                    continue; // the history's own answer replaces its in-view matches
                if (filterType == null || filterType.equalsIgnoreCase(((LoadedLayer.Hit) o[1]).type))
                    hits.add(o);
            }
            if (fromHistory != null)
                for (LoadedLayer.Hit h : fromHistory)
                    if (filterType == null || filterType.equalsIgnoreCase(h.type))
                        hits.add(new Object[] { history, h });
        }
        // From the device, or from the map's center; without a fix, the map center stands in.
        final com.atakmap.coremap.maps.coords.GeoPoint me = fromMapCenter ? null : selfPoint();
        final boolean noFix = !fromMapCenter && me == null;
        final com.atakmap.coremap.maps.coords.GeoPoint from = me != null ? me : mapCenter();
        lastFrom = from;
        final java.util.Map<Object[], Double> dist = new java.util.HashMap<>();
        if (from != null)
            for (Object[] o : hits) {
                final LoadedLayer.Hit h = (LoadedLayer.Hit) o[1];
                dist.put(o, com.atakmap.coremap.maps.coords.GeoCalculations.distanceTo(from,
                        new com.atakmap.coremap.maps.coords.GeoPoint(h.lat, h.lon)));
            }
        final int mode = sortMode == 0 && from == null ? 3 : sortMode;
        final java.util.regex.Pattern word = wordStart(text);
        java.util.Collections.sort(hits, new java.util.Comparator<Object[]>() {
            @Override
            public int compare(Object[] a, Object[] b) {
                final LoadedLayer.Hit x = (LoadedLayer.Hit) a[1], y = (LoadedLayer.Hit) b[1];
                // What is named for the text first, then what only mentions it, each in
                // the chosen order: "Prima" listed a DOME repair point whose comment says
                // "Primarily" above the PRIMA fire, because it was nearer (2026-10-05).
                final int byName = Boolean.compare(x.matchedIn != null, y.matchedIn != null);
                if (byName != 0)
                    return byName;
                // Among names, those with a word starting with the text first: "Ranch 2007"
                // listed Rocky Branch and Maple Branch above Ranch Fire (2007), because
                // "Branch" holds "ranch" and they were newer (2026-10-07).
                if (word != null) {
                    final int byWord = Boolean.compare(!startsWord(word, x.title), !startsWord(word, y.title));
                    if (byWord != 0)
                        return byWord;
                }
                switch (mode) {
                    case 0:
                        return Double.compare(dist.get(a), dist.get(b));
                    case 1: // newest first; undated last
                        return Long.compare(y.time == 0 ? Long.MIN_VALUE : y.time, x.time == 0 ? Long.MIN_VALUE : x.time);
                    case 2: // oldest first; undated last
                        return Long.compare(x.time == 0 ? Long.MAX_VALUE : x.time, y.time == 0 ? Long.MAX_VALUE : y.time);
                    default:
                        return String.CASE_INSENSITIVE_ORDER.compare(x.title == null ? "" : x.title, y.title == null ? "" : y.title);
                }
            }
        });
        final StringBuilder st = new StringBuilder();
        if (fromFeed != null) {
            st.append(hits.isEmpty() ? "Nothing on the feed matches \"" + text + "\""
                    : hits.size() + (hits.size() == 1 ? " match" : " matches") + " on the feed, anywhere");
            if (feedError != null)
                st.append(" \u00b7 ").append(feedError);
        } else if (!text.isEmpty() && scope != null && scope.hasScopeControl() && feedAsking) {
            st.append(hits.isEmpty() ? "" : hits.size() + " in view \u00b7 ").append("asking the feed for \"").append(text).append("\"\u2026");
        } else if (hits.isEmpty())
            st.append(emptyOrScanning(text, scope));
        else {
            int mention = 0;
            for (Object[] o : hits)
                if (((LoadedLayer.Hit) o[1]).matchedIn != null)
                    mention++;
            final int named = hits.size() - mention;
            if (text.isEmpty() || mention == 0)
                st.append(hits.size()).append(hits.size() == 1 ? " feature" : " features");
            else
                st.append(named).append(" named \"").append(text).append("\", ").append(mention)
                        .append(mention == 1 ? " more mentions it" : " more mention it");
            st.append(scanning(scope) ? " \u00b7 updating\u2026" : "");
        }
        if (hits.size() > RESULT_CAP)
            st.append(", showing ").append(SORT_LABELS[mode].toLowerCase(java.util.Locale.US)).append(" ").append(RESULT_CAP);
        if (noFix && from != null)
            st.append(" \u00b7 no GPS fix, measured from the map center");
        else if (from == null)
            st.append(" \u00b7 nowhere to measure from, sorted by name");
        if (fromFeed == null)
            st.append(scopeNote(scope));
        status.setText(withLegend(st.toString(), scope));
        container.removeAllViews();
        shownHits = new java.util.ArrayList<>();
        final java.text.SimpleDateFormat when = new java.text.SimpleDateFormat("MMM d HH:mm", java.util.Locale.US);
        int shown = 0;
        for (final Object[] o : hits) {
            if (shown++ >= RESULT_CAP)
                break;
            shownHits.add(o);
            final LoadedLayer l = (LoadedLayer) o[0];
            final LoadedLayer.Hit h = (LoadedLayer.Hit) o[1];
            final View row = PluginLayoutInflater.inflate(pluginContext, R.layout.result_row, null);
            // An inReach S.O.S. (EGP's rule: the callsign ends "-Alert") says so in the list too.
            ((TextView) row.findViewById(R.id.result_title)).setText(
                    com.atakmap.android.featurelayer.DartStyles.sosCallsign(h.title) ? "S.O.S. " + h.title : h.title);
            final StringBuilder sub = new StringBuilder();
            if (h.type != null && !h.type.equalsIgnoreCase(h.title))
                sub.append(h.type);
            if (scope == null)
                sub.append(sub.length() > 0 ? " \u00b7 " : "").append(h.layer);
            if (h.matchedIn != null)
                sub.append(sub.length() > 0 ? " \u00b7 " : "").append(mentionOf(h, text));
            // A DART row says how old its report is, in the bucket's color -- the same
            // green / yellow / red as the ring on its marker. Other layers keep the date.
            final boolean dart = com.atakmap.android.featurelayer.DartStyles.handles(l.spec);
            String agoText = null;
            int agoColor = 0;
            if (h.time > 0 && dart) {
                final int bucket = com.atakmap.android.featurelayer.DartStyles.ageBucket(h.time,
                        // A hit does not carry its data source, so a person gets the Field
                        // Maps cut-offs here; the marker's ring, built from the row, knows
                        // an inReach when it sees one. Same numbers today.
                        System.currentTimeMillis(), com.atakmap.android.featurelayer.DartStyles.ageCutoffs(
                                l.spec.id.contains("personnel"), false));
                agoColor = com.atakmap.android.featurelayer.DartStyles.ageColor(bucket);
                final long min = Math.max(0, (System.currentTimeMillis() - h.time) / 60000L);
                agoText = min < 1 ? "just now" : min < 60 ? min + " min ago"
                        : min < 1440 ? (min / 60) + " h " + (min % 60) + " min ago" : (min / 1440) + " d ago";
            }
            // A burn's title carries its year; the time behind it is only for sorting, and
            // for the older records it is a made-up 1 July.
            if (h.time > 0 && !dart && !com.atakmap.android.featurelayer.FireHistoryStyles.handles(l.spec))
                sub.append(sub.length() > 0 ? " \u00b7 " : "").append(when.format(new java.util.Date(h.time)));
            final TextView subView = row.findViewById(R.id.result_sub);
            if (agoText != null) {
                final String head = sub.length() > 0 ? sub + " \u00b7 " : "";
                final android.text.SpannableString sp = new android.text.SpannableString(head + agoText);
                if (agoColor != 0)
                    sp.setSpan(new android.text.style.ForegroundColorSpan(agoColor), head.length(), sp.length(),
                            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                subView.setText(sp);
            } else {
                subView.setText(sub.toString());
            }
            ((TextView) row.findViewById(R.id.result_dist)).setText(dist.containsKey(o)
                    ? com.atakmap.android.featurelayer.Units.format(dist.get(o)) : "");
            // Cam Depot's shape: the row opens the details, the button goes there.
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showDetails(l, h);
                }
            });
            row.findViewById(R.id.result_go).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    l.zoomTo(h);
                }
            });
            container.addView(row);
        }
    }

    /** A hit's attributes in the pane, as the radial's Details shows them; Back returns to the list. */
    private void showDetails(final LoadedLayer l, final LoadedLayer.Hit h) {
        if (paneView == null)
            return;
        final View searchPanel = paneView.findViewById(R.id.search_panel);
        final View panel = paneView.findViewById(R.id.details_panel);
        final String[] title = { h.title };
        final String body = com.atakmap.android.featurelayer.FeatureDetailsReceiver.render(h.attrs, title);
        ((TextView) paneView.findViewById(R.id.pane_details_title)).setText(
                com.atakmap.android.featurelayer.DartStyles.sosCallsign(title[0]) ? "S.O.S. " + title[0] : title[0]);
        ((TextView) paneView.findViewById(R.id.pane_details_subtitle)).setText(l.displayName());
        ((TextView) paneView.findViewById(R.id.pane_details_attributes)).setText(body);
        paneView.findViewById(R.id.btn_details_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                panel.setVisibility(View.GONE);
                paneView.findViewById(R.id.features_header).setVisibility(View.VISIBLE);
                searchPanel.setVisibility(View.VISIBLE);
            }
        });
        paneView.findViewById(R.id.btn_details_goto).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                l.zoomTo(h);
            }
        });
        com.atakmap.android.featurelayer.FeatureDetailsReceiver.bindInciWeb(
                paneView.findViewById(R.id.btn_details_inciweb), h.attrs, mapView);
        com.atakmap.android.featurelayer.FeatureDetailsReceiver.bindCalFire(
                paneView.findViewById(R.id.btn_details_calfire), h.attrs, mapView);
        com.atakmap.android.featurelayer.FeatureDetailsReceiver.bindOnly(
                paneView.findViewById(R.id.btn_details_only), paneView.findViewById(R.id.btn_details_only_show),
                l, h.attrs, manager);
        searchPanel.setVisibility(View.GONE);
        paneView.findViewById(R.id.features_header).setVisibility(View.GONE); // one Back, the details' own
        panel.setVisibility(View.VISIBLE);
        scrollPaneToTop();
    }

    private void refreshOrgUi() {
        if (paneView == null)
            return;
        final Button orgButton = paneView.findViewById(R.id.btn_org);
        final Button signin = paneView.findViewById(R.id.btn_signin);
        final Button forget = paneView.findViewById(R.id.btn_forget);
        forget.setVisibility(org != null && org.custom ? View.VISIBLE : View.GONE);
        forget.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                forgetCustomOrg(org);
            }
        });
        final Button find = paneView.findViewById(R.id.btn_find);
        final EditText search = paneView.findViewById(R.id.search_text);
        final View searchRow = paneView.findViewById(R.id.search_row);
        // DART is not a source of its own: NIFC is the source, and DART is a choice
        // inside it that appears once the operator is signed in. It shares NIFC's
        // portal, so there is never a second sign-in.
        final View dartRow = paneView.findViewById(R.id.dart_row);
        final Button dart = paneView.findViewById(R.id.btn_dart);
        dartRow.setVisibility(View.GONE);
        final View boundariesPanel = paneView.findViewById(R.id.boundaries_panel);
        boundariesPanel.setVisibility(View.GONE);
        dart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickDart();
            }
        });
        // FireGuard rides the same NIFC sign-in and the same row: one tap adds it.
        paneView.findViewById(R.id.btn_fireguard).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (manager != null)
                    addLayer(Sources.fireGuard());
            }
        });
        if (org == null) {
            orgButton.setText("Pick a source");
            signin.setVisibility(View.GONE);
            searchRow.setVisibility(View.GONE);
            return;
        }
        if ("nifc".equals(org.id) && manager != null && manager.auth(Sources.NIFC_PORTAL).isSignedIn())
            dartRow.setVisibility(View.VISIBLE);
        orgButton.setText(org.title);
        if ("boundaries".equals(org.id)) {
            // Its own page of rows under the source button; nothing to search or sign into.
            searchRow.setVisibility(View.GONE);
            signin.setVisibility(View.GONE);
            boundariesPanel.setVisibility(View.VISIBLE);
            bindBoundaries();
            return;
        }
        searchRow.setVisibility(View.VISIBLE);
        find.setText(org.findLabel);
        search.setHint(org.hint);
        search.setVisibility("nifs-archive".equals(org.id) || "ca-air-intel".equals(org.id)
                || "new-starts".equals(org.id) || "ongoing".equals(org.id)
                || "fire-history".equals(org.id) ? View.GONE : View.VISIBLE);
        // A source that is one fixed layer has nothing to search: its button adds the
        // layer, and once the layer is in the list below the button goes away (the
        // operator read "Add perimeters" over an added layer as a second thing to add).
        final String fixedLayer = "ca-air-intel".equals(org.id) ? "ca-air-intel"
                : "nifs-archive".equals(org.id) ? "nifs-archive:Dragon Bravo"
                : "new-starts".equals(org.id) ? Sources.NEW_STARTS_ID
                : "ongoing".equals(org.id) ? Sources.ONGOING_ID
                : "fire-history".equals(org.id) ? Sources.FIRE_HISTORY_ID : null;
        if (fixedLayer != null && manager != null) {
            boolean loaded = false;
            for (LoadedLayer l : manager.snapshot())
                if (fixedLayer.equals(l.spec.id))
                    loaded = true;
            searchRow.setVisibility(loaded ? View.GONE : View.VISIBLE);
        }
        if (org.portal == null || manager == null) {
            signin.setVisibility(View.GONE);
            return;
        }
        final ArcGisAuth auth = manager.auth(org.portal);
        signin.setVisibility(View.VISIBLE);
        if (!auth.isConfigured()) {
            signin.setText("No client ID");
            signin.setEnabled(false);
            return;
        }
        signin.setEnabled(true);
        signin.setText(auth.isSignedIn() ? "Sign out (" + auth.getUsername() + ")" : "Sign in");
    }

    private void onFind(final Button find, final String text) {
        if (org == null || manager == null)
            return;
        Log.d(TAG, org.id + " find: \"" + text + "\"");
        switch (org.id) {
            case "nifs-archive":
                addLayer(Sources.nifsArchiveDemo());
                return;
            case "ca-air-intel":
                addLayer(Sources.caAirIntel());
                return;
            case "new-starts":
                addLayer(Sources.newStarts());
                return;
            case "ongoing":
                addLayer(Sources.ongoingFires());
                return;
            case "fire-history":
                addLayer(Sources.fireHistory());
                return;
            case "sarcop-live":
                toast("SARCOP Live is not wired up yet; SARCOP Training is");
                return;
            default:
                break;
        }
        find.setEnabled(false);
        if (org.custom) {
            final String portal = org.portal;
            manager.search(new Callable<List<com.atakmap.android.featurelayer.Esri.Item>>() {
                @Override
                public List<com.atakmap.android.featurelayer.Esri.Item> call() throws Exception {
                    final String token = manager.auth(portal).getValidToken();
                    if (token == null)
                        throw new IllegalStateException("sign in to " + org.title + " first");
                    return com.atakmap.android.featurelayer.Esri.searchItems(portal, token, text);
                }
            }, new LayerManager.SearchCallback<List<com.atakmap.android.featurelayer.Esri.Item>>() {
                @Override
                public void onResult(List<com.atakmap.android.featurelayer.Esri.Item> items, String error) {
                    find.setEnabled(true);
                    if (error != null)
                        toast("Search failed: " + error);
                    else if (items == null || items.isEmpty())
                        toast("No feature layer named like \"" + text + "\" in " + org.title);
                    else
                        pickItem(portal, items);
                }
            });
            return;
        }
        if ("nifc".equals(org.id)) {
            manager.search(new Callable<List<Sources.Fire>>() {
                @Override
                public List<Sources.Fire> call() throws Exception {
                    return Sources.searchFires(text);
                }
            }, new LayerManager.SearchCallback<List<Sources.Fire>>() {
                @Override
                public void onResult(List<Sources.Fire> fires, String error) {
                    find.setEnabled(true);
                    if (error != null)
                        toast("Search failed: " + error);
                    else if (fires == null || fires.isEmpty())
                        toast(text.isEmpty() ? "No fires listed" : "No fire this year matches \"" + text + "\"");
                    else
                        pickFire(fires);
                }
            });
        } else {
            manager.search(new Callable<List<Sources.SarcopEvent>>() {
                @Override
                public List<Sources.SarcopEvent> call() throws Exception {
                    return Sources.searchSarcop(text);
                }
            }, new LayerManager.SearchCallback<List<Sources.SarcopEvent>>() {
                @Override
                public void onResult(List<Sources.SarcopEvent> events, String error) {
                    find.setEnabled(true);
                    if (error != null)
                        toast("Search failed: " + error);
                    else if (events == null || events.isEmpty())
                        toast(text.isEmpty() ? "No training incidents listed" : "No SARCOP Training incident matches \"" + text + "\"");
                    else
                        pickSarcop(events);
                }
            });
        }
    }

    // ---- Boundaries: kind, states, which, add -------------------------------------------

    /** The kinds to add, in Boundaries.KINDS order; every kind until the operator unticks one. */
    private final java.util.LinkedHashSet<String> bKinds = new java.util.LinkedHashSet<>(java.util.Arrays.asList(com.atakmap.android.featurelayer.Boundaries.KINDS));
    private final List<String> bStates = new java.util.ArrayList<>();          // FIPS codes, in the order picked
    private List<com.atakmap.android.featurelayer.Boundaries.Named> bPicks;     // null = every boundary in the states
    /** The state under the map center, asked of the Census once per page opening; null until it answers. */
    private com.atakmap.android.featurelayer.Boundaries.State bCenterState;
    private boolean bCenterAsked;

    private static String joinStates(List<String> fips, boolean abbr) {
        final StringBuilder sb = new StringBuilder();
        for (String f : fips) {
            final com.atakmap.android.featurelayer.Boundaries.State st = com.atakmap.android.featurelayer.Boundaries.state(f);
            sb.append(sb.length() > 0 ? ", " : "").append(st == null ? f : abbr ? st.abbr : st.name);
        }
        return sb.toString();
    }

    /** The three rows and the Add button say what is picked; the state under the map is the default. */
    private void bindBoundaries() {
        if (paneView == null)
            return;
        final Button kind = paneView.findViewById(R.id.btn_bnd_kind);
        final Button state = paneView.findViewById(R.id.btn_bnd_state);
        final Button which = paneView.findViewById(R.id.btn_bnd_which);
        final Button add = paneView.findViewById(R.id.btn_bnd_add);
        if (!bCenterAsked && manager != null) {
            // Which state the map is over, from the Census (the nearest state center is a
            // guess that fails on a border); the list puts it first and the row starts on it.
            bCenterAsked = true;
            final com.atakmap.coremap.maps.coords.GeoPoint c = mapCenter();
            if (c != null) {
                manager.search(new Callable<com.atakmap.android.featurelayer.Boundaries.State>() {
                    @Override
                    public com.atakmap.android.featurelayer.Boundaries.State call() {
                        return com.atakmap.android.featurelayer.Boundaries.stateAt(c.getLatitude(), c.getLongitude());
                    }
                }, new LayerManager.SearchCallback<com.atakmap.android.featurelayer.Boundaries.State>() {
                    @Override
                    public void onResult(com.atakmap.android.featurelayer.Boundaries.State st, String error) {
                        if (st == null) {
                            bCenterAsked = false; // offline, or at sea: asked again next time the page is shown
                            return;
                        }
                        bCenterState = st;
                        if (bStates.isEmpty()) {
                            bStates.add(st.fips);
                            bindBoundaries();
                        }
                    }
                });
            }
        }
        if (bStates.isEmpty() && bCenterState != null)
            bStates.add(bCenterState.fips);
        kind.setText(bKinds.isEmpty() ? "Kinds: pick one" : "Kinds: " + kindsTitle());
        state.setText(bStates.isEmpty() ? "State: pick one" : "State: " + joinStates(bStates, bStates.size() > 2));
        which.setText(bPicks == null ? "Which: All"
                : "Which: " + bPicks.size() + (bPicks.size() == 1 ? " picked" : " picked"));
        final String[] names = pickNames();
        add.setText(bStates.isEmpty() || bKinds.isEmpty() ? "Add"
                : names != null ? "Add " + joinNames(names) + " (" + joinStates(bStates, true) + ")"
                : "Add " + com.atakmap.android.featurelayer.Boundaries.groupTitle(bStates.toArray(new String[0])) + ": " + kindsTitle());
        add.setEnabled(!bStates.isEmpty() && !bKinds.isEmpty() && (bPicks == null || !bPicks.isEmpty()));
        kind.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickBoundaryKind();
            }
        });
        state.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickBoundaryStates();
            }
        });
        which.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickBoundaryWhich();
            }
        });
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addBoundaries();
            }
        });
    }

    /** "Counties, Cities, Tribal Lands", in the standard order, for the row and the Add button. */
    /** The kinds on offer for the picked states, in the standard order. */
    private String[] kindsOffered() {
        return com.atakmap.android.featurelayer.Boundaries.kindsFor(bStates.toArray(new String[0]));
    }

    private String kindsTitle() {
        final StringBuilder sb = new StringBuilder();
        for (String k : kindsOffered())
            if (bKinds.contains(k))
                sb.append(sb.length() > 0 ? ", " : "").append(com.atakmap.android.featurelayer.Boundaries.kindTitle(k));
        return sb.toString();
    }

    private static String joinNames(String[] names) {
        final StringBuilder sb = new StringBuilder();
        final int shown = Math.min(3, names.length);
        for (int i = 0; i < shown; i++)
            sb.append(i > 0 ? ", " : "").append(names[i]);
        if (names.length > shown)
            sb.append(" +").append(names.length - shown);
        return sb.toString();
    }

    private String[] pickNames() {
        if (bPicks == null || bPicks.isEmpty())
            return null;
        final String[] out = new String[bPicks.size()];
        for (int i = 0; i < out.length; i++)
            out[i] = bPicks.get(i).shortName; // "Elko, Humboldt Counties (NV)", not "Elko County, Humboldt County Counties"
        return out;
    }

    private void pickBoundaryKind() {
        final String[] kinds = kindsOffered();
        final CharSequence[] labels = new CharSequence[kinds.length];
        final boolean[] on = new boolean[kinds.length];
        for (int i = 0; i < kinds.length; i++) {
            final String t = com.atakmap.android.featurelayer.Boundaries.kindTitle(kinds[i]);
            final String n = com.atakmap.android.featurelayer.Boundaries.kindNote(kinds[i]);
            final android.text.SpannableString two = new android.text.SpannableString(t + "\n" + n);
            two.setSpan(new android.text.style.RelativeSizeSpan(0.78f), t.length() + 1, two.length(), 0);
            two.setSpan(new android.text.style.ForegroundColorSpan(0xFFBBBBBB), t.length() + 1, two.length(), 0);
            labels[i] = two;
            on[i] = bKinds.contains(kinds[i]);
        }
        final java.util.LinkedHashSet<String> picked = new java.util.LinkedHashSet<>(bKinds);
        new AlertDialog.Builder(mapView.getContext()).setTitle("Kinds")
                .setMultiChoiceItems(labels, on, new DialogInterface.OnMultiChoiceClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which, boolean isChecked) {
                        if (isChecked)
                            picked.add(kinds[which]);
                        else
                            picked.remove(kinds[which]);
                    }
                })
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (!picked.equals(bKinds))
                            bPicks = null; // names belong to the kinds they were picked in
                        bKinds.clear();
                        bKinds.addAll(picked);
                        bindBoundaries();
                    }
                })
                .setNegativeButton("Cancel", null).show();
    }

    /** Up to five states, the one under the map first; a sixth tap says so and stays off. */
    private void pickBoundaryStates() {
        final com.atakmap.coremap.maps.coords.GeoPoint c = mapCenter();
        final List<com.atakmap.android.featurelayer.Boundaries.State> all = com.atakmap.android.featurelayer.Boundaries
                .statesNear(c == null ? Double.NaN : c.getLatitude(), c == null ? Double.NaN : c.getLongitude());
        if (bCenterState != null) {
            all.remove(bCenterState);
            all.add(0, bCenterState);
        }
        final String[] labels = new String[all.size()];
        final boolean[] on = new boolean[all.size()];
        for (int i = 0; i < all.size(); i++) {
            labels[i] = all.get(i).name;
            on[i] = bStates.contains(all.get(i).fips);
        }
        final List<String> picked = new java.util.ArrayList<>(bStates);
        new AlertDialog.Builder(mapView.getContext()).setTitle("States, up to " + com.atakmap.android.featurelayer.Boundaries.MAX_STATES)
                .setMultiChoiceItems(labels, on, new DialogInterface.OnMultiChoiceClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which, boolean isChecked) {
                        final String fips = all.get(which).fips;
                        if (isChecked) {
                            if (picked.size() >= com.atakmap.android.featurelayer.Boundaries.MAX_STATES) {
                                ((AlertDialog) d).getListView().setItemChecked(which, false);
                                toast("Up to " + com.atakmap.android.featurelayer.Boundaries.MAX_STATES
                                        + " states in one layer; add another layer for more");
                                return;
                            }
                            if (!picked.contains(fips))
                                picked.add(fips);
                        } else {
                            picked.remove(fips);
                        }
                    }
                })
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (!picked.equals(bStates))
                            bPicks = null; // names belong to the states they were picked in
                        bStates.clear();
                        bStates.addAll(picked);
                        bindBoundaries();
                    }
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void pickBoundaryWhich() {
        if (bStates.isEmpty()) {
            toast("Pick a state first");
            return;
        }
        if (bKinds.isEmpty()) {
            toast("Pick a kind first");
            return;
        }
        final String[] labels = { "All", "Pick names\u2026" };
        new AlertDialog.Builder(mapView.getContext()).setTitle("Which boundaries")
                .setSingleChoiceItems(labels, bPicks == null ? 0 : 1, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        d.dismiss();
                        if (which == 0) {
                            bPicks = null;
                            bindBoundaries();
                        } else {
                            fetchBoundaryNames();
                        }
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    /** The names list, no shapes, nearest the map center first; then the checklist. */
    private void fetchBoundaryNames() {
        if (manager == null)
            return;
        final List<String> kl = new java.util.ArrayList<>();
        for (String k : kindsOffered())
            if (bKinds.contains(k) && !com.atakmap.android.featurelayer.Boundaries.mosaic(k))
                kl.add(k);
        if (kl.isEmpty()) {
            toast("Names are picked for counties, cities, communities and tribal lands; the land layers show what is in view");
            return;
        }
        final String[] kinds = kl.toArray(new String[0]);
        final String[] states = bStates.toArray(new String[0]);
        final com.atakmap.coremap.maps.coords.GeoPoint c = mapCenter();
        final Button which = paneView.findViewById(R.id.btn_bnd_which);
        which.setText("Which: loading names\u2026");
        which.setEnabled(false);
        manager.search(new Callable<List<com.atakmap.android.featurelayer.Boundaries.Named>>() {
            @Override
            public List<com.atakmap.android.featurelayer.Boundaries.Named> call() throws Exception {
                return com.atakmap.android.featurelayer.Boundaries.names(kinds, states,
                        c == null ? Double.NaN : c.getLatitude(), c == null ? Double.NaN : c.getLongitude());
            }
        }, new LayerManager.SearchCallback<List<com.atakmap.android.featurelayer.Boundaries.Named>>() {
            @Override
            public void onResult(List<com.atakmap.android.featurelayer.Boundaries.Named> names, String error) {
                which.setEnabled(true);
                bindBoundaries();
                if (error != null)
                    toast("Could not load the names: no network?");
                else if (names == null || names.isEmpty())
                    toast("No boundaries listed there");
                else
                    pickBoundaryNames(names);
            }
        });
    }

    /** A checklist with a filter box on top; a tick keeps a name, OK keeps the ticks. */
    private void pickBoundaryNames(final List<com.atakmap.android.featurelayer.Boundaries.Named> names) {
        final Context ctx = mapView.getContext();
        final java.util.Set<String> picked = new java.util.LinkedHashSet<>();
        if (bPicks != null)
            for (com.atakmap.android.featurelayer.Boundaries.Named n : bPicks)
                picked.add(n.geoid);
        final boolean oneState = bStates.size() == 1;
        boolean oneTypeTmp = true;
        for (com.atakmap.android.featurelayer.Boundaries.Named n : names)
            if (!n.type.equals(names.get(0).type))
                oneTypeTmp = false;
        final boolean oneType = oneTypeTmp;
        final LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        final int pad = (int) (8 * ctx.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, 0);
        final EditText filter = new EditText(ctx);
        filter.setHint("Type to narrow the list");
        filter.setSingleLine(true);
        filter.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        filter.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN);
        box.addView(filter, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        final android.widget.ListView list = new android.widget.ListView(ctx);
        list.setChoiceMode(android.widget.ListView.CHOICE_MODE_MULTIPLE);
        box.addView(list, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                (int) (mapView.getHeight() * 0.55)));
        final List<com.atakmap.android.featurelayer.Boundaries.Named> shown = new java.util.ArrayList<>();
        final Runnable fill = new Runnable() {
            @Override
            public void run() {
                final String needle = filter.getText().toString().trim().toLowerCase(java.util.Locale.US);
                shown.clear();
                final List<String> labels = new java.util.ArrayList<>();
                for (com.atakmap.android.featurelayer.Boundaries.Named n : names) {
                    if (!needle.isEmpty() && !n.name.toLowerCase(java.util.Locale.US).contains(needle))
                        continue;
                    shown.add(n);
                    labels.add(n.label(oneState, oneType)
                            + (n.distanceM == Double.MAX_VALUE ? "" : "  \u00b7  " + Units.formatBig(n.distanceM)));
                }
                list.setAdapter(new android.widget.ArrayAdapter<String>(ctx,
                        android.R.layout.simple_list_item_multiple_choice, labels));
                for (int i = 0; i < shown.size(); i++)
                    list.setItemChecked(i, picked.contains(shown.get(i).geoid));
            }
        };
        fill.run();
        filter.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(android.text.Editable e) {
                fill.run();
            }
        });
        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View v, int pos, long id) {
                if (pos < 0 || pos >= shown.size())
                    return;
                final String g = shown.get(pos).geoid;
                if (list.isItemChecked(pos))
                    picked.add(g);
                else
                    picked.remove(g);
            }
        });
        final String what = bKinds.size() == 1 ? kindsTitle().toLowerCase(java.util.Locale.US) : "boundaries";
        new AlertDialog.Builder(ctx).setTitle("Pick " + what + " (" + names.size() + ")")
                .setView(box)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        final List<com.atakmap.android.featurelayer.Boundaries.Named> out = new java.util.ArrayList<>();
                        for (com.atakmap.android.featurelayer.Boundaries.Named n : names)
                            if (picked.contains(n.geoid))
                                out.add(n);
                        bPicks = out.isEmpty() ? null : out;
                        bindBoundaries();
                    }
                })
                .setNegativeButton("Cancel", null).show();
    }

    /** One layer per kind: every boundary of it in the states, or the names picked in it. */
    private void addBoundaries() {
        if (manager == null || bStates.isEmpty() || bKinds.isEmpty())
            return;
        final String[] states = bStates.toArray(new String[0]);
        LayerSpec last = null;
        for (String kind : kindsOffered()) {
            if (!bKinds.contains(kind))
                continue;
            String[] geoids = null, names = null;
            if (bPicks != null && !bPicks.isEmpty() && !com.atakmap.android.featurelayer.Boundaries.mosaic(kind)) {
                final List<String> g = new java.util.ArrayList<>(), n = new java.util.ArrayList<>();
                for (com.atakmap.android.featurelayer.Boundaries.Named p : bPicks)
                    if (kind.equals(p.kind)) {
                        g.add(p.geoid);
                        n.add(p.shortName);
                    }
                if (g.isEmpty())
                    continue; // nothing of this kind was picked
                geoids = g.toArray(new String[0]);
                names = n.toArray(new String[0]);
            }
            last = com.atakmap.android.featurelayer.Boundaries.layer(kind, states, geoids, names);
            if (com.atakmap.android.featurelayer.Boundaries.mosaic(kind)) {
                // The square it loads once: around where the map is now.
                final com.atakmap.coremap.maps.coords.GeoPoint c = mapCenter();
                if (c != null)
                    last.scopeBox = com.atakmap.android.featurelayer.Boundaries.boxAround(c.getLatitude(), c.getLongitude());
            }
            manager.add(last);
        }
        bPicks = null;
        if (last != null)
            backToMain();
    }

    private void renderRows() {
        if (paneView == null || manager == null)
            return;
        refreshOrgUi(); // the source row hides its add button once its layer is listed
        final LinearLayout container = paneView.findViewById(R.id.layers_container);
        final TextView empty = paneView.findViewById(R.id.layers_empty);
        container.removeAllViews();
        final List<LoadedLayer> layers = manager.snapshot();
        empty.setVisibility(layers.isEmpty() ? View.VISIBLE : View.GONE);
        // One button for every layer at once, named for what it will do (Cam Depot's rule):
        // "All ON" while anything is off, "All OFF" once everything is on.
        final Button all = paneView.findViewById(R.id.btn_all);
        boolean anyOff = false;
        for (LoadedLayer l : layers)
            if (!l.isVisible())
                anyOff = true;
        final boolean turnOn = anyOff;
        all.setVisibility(layers.size() > 1 ? View.VISIBLE : View.GONE);
        all.setText(turnOn ? "All ON" : "All OFF");
        all.setTextColor(turnOn ? Color.parseColor("#4CAF50") : Color.parseColor("#F44336"));
        all.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                for (LoadedLayer l : manager.snapshot())
                    if (l.isVisible() != turnOn)
                        manager.setVisible(l, turnOn);
            }
        });
        // Boundaries fold under their state: one row per state, the kinds inside it
        // (operator, 2026-10-12: "keeping the side pane menus compact then expandable").
        final java.util.Map<String, List<LoadedLayer>> groups = new java.util.LinkedHashMap<>();
        final List<Object> order = new java.util.ArrayList<>(); // a LoadedLayer, or a group key
        for (final LoadedLayer l : layers) {
            if (com.atakmap.android.featurelayer.Boundaries.handles(l.spec)) {
                final String key = com.atakmap.android.featurelayer.Boundaries.groupKey(l.spec);
                List<LoadedLayer> g = groups.get(key);
                if (g == null) {
                    g = new java.util.ArrayList<>();
                    groups.put(key, g);
                    order.add(key);
                }
                g.add(l);
            } else {
                order.add(l);
            }
        }
        for (Object o : order) {
            if (o instanceof LoadedLayer)
                container.addView(buildRow((LoadedLayer) o, ((LoadedLayer) o).displayName()));
            else
                container.addView(groupRow((String) o, groups.get((String) o)));
        }
    }

    /**
     * "Color: Purple" on a Boundaries layer's row, with a swatch; a tap opens the colors
     * as a grid of dark tiles. Gone on every other layer.
     */
    private void bindColor(View row, final LoadedLayer l) {
        final Button b = row.findViewById(R.id.row_color);
        if (b == null)
            return;
        // A land mosaic draws in its publisher's colors, one per class: no color to pick.
        if (!com.atakmap.android.featurelayer.Boundaries.handles(l.spec)
                || com.atakmap.android.featurelayer.Boundaries.mosaic(l.spec.kind)) {
            b.setVisibility(View.GONE);
            return;
        }
        final int rgb = com.atakmap.android.featurelayer.Boundaries.hue(l.spec);
        b.setVisibility(View.VISIBLE);
        b.setText("Color: " + com.atakmap.android.featurelayer.Boundaries.colorName(rgb));
        b.setCompoundDrawablesWithIntrinsicBounds(swatch(rgb), null, null, null);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickColor(l);
            }
        });
    }

    /** A rounded color square for a button, edged in grey so black and white show. */
    private android.graphics.drawable.Drawable swatch(int rgb) {
        final float dp = paneView.getResources().getDisplayMetrics().density;
        final android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(0xFF000000 | rgb);
        d.setStroke(Math.max(1, Math.round(dp)), 0xFF999999);
        d.setCornerRadius(3 * dp);
        final int side = Math.round(22 * dp);
        d.setBounds(0, 0, side, side);
        d.setSize(side, side);
        return d;
    }

    /** The colors as two columns of dark tiles, each with its swatch; the layer's own marked. */
    private void pickColor(final LoadedLayer l) {
        final android.content.Context ctx = mapView.getContext();
        final float dp = paneView.getResources().getDisplayMetrics().density;
        final int now = com.atakmap.android.featurelayer.Boundaries.hue(l.spec);
        final LinearLayout grid = new LinearLayout(ctx);
        grid.setOrientation(LinearLayout.VERTICAL);
        final int pad = Math.round(8 * dp);
        grid.setPadding(pad, pad, pad, pad);
        final AlertDialog[] dialog = new AlertDialog[1];
        final Object[][] colors = com.atakmap.android.featurelayer.Boundaries.COLORS;
        LinearLayout line = null;
        for (int i = 0; i < colors.length; i++) {
            if (i % 2 == 0) {
                line = new LinearLayout(ctx);
                line.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(line);
            }
            final String name = (String) colors[i][0];
            final int rgb = (Integer) colors[i][1];
            final Button t = (Button) PluginLayoutInflater.inflate(pluginContext, R.layout.color_tile, null);
            t.setText(((rgb & 0x00FFFFFF) == (now & 0x00FFFFFF) ? "\u2713 " : "") + name);
            t.setCompoundDrawablesWithIntrinsicBounds(swatch(rgb), null, null, null);
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (dialog[0] != null)
                        dialog[0].dismiss();
                    if ((rgb & 0x00FFFFFF) != (now & 0x00FFFFFF))
                        manager.setColor(l, rgb);
                }
            });
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(Math.round(3 * dp), Math.round(3 * dp), Math.round(3 * dp), Math.round(3 * dp));
            line.addView(t, lp);
        }
        dialog[0] = new AlertDialog.Builder(ctx).setTitle(l.displayName() + ": color").setView(grid)
                .setNegativeButton("Cancel", null).show();
    }

    /** The prefix a where line starts with, so several can share one. */
    private static String wherePrefix(String w) {
        return w.startsWith("Map center is in ") ? "Map center is in " : w.startsWith("You are in ") ? "You are in " : "";
    }

    /** One state's row: the head switches every kind in it, the arrow opens the kind rows. */
    private View groupRow(final String key, final List<LoadedLayer> kinds) {
        final View row = PluginLayoutInflater.inflate(pluginContext, R.layout.boundary_group_row, null);
        final Button toggle = row.findViewById(R.id.group_toggle);
        final ImageButton expand = row.findViewById(R.id.group_expand);
        final LinearLayout body = row.findViewById(R.id.group_body);
        final TextView where = row.findViewById(R.id.group_where);
        final String title = com.atakmap.android.featurelayer.Boundaries.groupTitle(kinds.get(0).spec.states) + " Boundaries";
        boolean anyOn = false, loading = false;
        final StringBuilder wl = new StringBuilder();
        boolean elsewhere = false;
        for (LoadedLayer l : kinds) {
            anyOn |= l.isVisible();
            loading |= l.refreshing || l.busy;
            if (l.isVisible() && !manager.loadedHere(l))
                elsewhere = true;
            final String w = l.whereLine;
            if (w != null && l.isVisible() && !l.refreshing && !l.busy) {
                if (wl.length() == 0)
                    wl.append(w);
                else
                    wl.append(" \u00b7 ").append(w.substring(wherePrefix(w).length()));
            }
        }
        if (loading) {
            toggle.setText(title + " Loading\u2026");
            toggle.setTextColor(Color.parseColor("#FFC107"));
            toggle.setEnabled(false);
        } else {
            toggle.setText(title + (anyOn ? " ON" : " OFF"));
            toggle.setTextColor(anyOn ? Color.parseColor("#4CAF50") : Color.parseColor("#F44336"));
            toggle.setEnabled(true);
        }
        final boolean turnOn = !anyOn;
        // Off remembers which kinds were on, and on brings back those, not every kind
        // (operator, 2026-10-13: "if i have ... county and community on, then i hit calif
        // boundary off and back on it should be last state not everything back on").
        final String onKey = "group.on." + key;
        toggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!turnOn) {
                    final StringBuilder was = new StringBuilder();
                    for (LoadedLayer l : kinds)
                        if (l.isVisible())
                            was.append(was.length() > 0 ? "\n" : "").append(l.spec.id);
                    uiPrefs().edit().putString(onKey, was.toString()).apply();
                    for (LoadedLayer l : kinds)
                        if (l.isVisible())
                            manager.setVisible(l, false);
                    return;
                }
                final java.util.Set<String> was = new java.util.HashSet<>(
                        java.util.Arrays.asList(uiPrefs().getString(onKey, "").split("\n")));
                boolean any = false;
                for (LoadedLayer l : kinds)
                    any |= was.contains(l.spec.id);
                for (LoadedLayer l : kinds)
                    if (!any || was.contains(l.spec.id))
                        manager.setVisible(l, true);
            }
        });
        // A land layer holds the square it was loaded for; when the map has left it,
        // the row says so instead of looking broken.
        if (elsewhere)
            wl.append(wl.length() > 0 ? " \u00b7 " : "").append("Land layers loaded elsewhere: Refresh loads them here");
        if (wl.length() > 0) {
            where.setText(wl);
            where.setVisibility(View.VISIBLE);
        } else {
            where.setVisibility(View.GONE);
        }
        final String foldKey = "fold.group." + key;
        final boolean open = uiPrefs().getBoolean(foldKey, false);
        expand.setRotation(open ? 180f : 0f);
        body.setVisibility(open ? View.VISIBLE : View.GONE);
        expand.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final boolean nowOpen = body.getVisibility() != View.VISIBLE;
                uiPrefs().edit().putBoolean(foldKey, nowOpen).apply();
                expand.setRotation(nowOpen ? 180f : 0f);
                body.setVisibility(nowOpen ? View.VISIBLE : View.GONE);
            }
        });
        for (LoadedLayer l : kinds)
            body.addView(buildRow(l, com.atakmap.android.featurelayer.Boundaries.shortTitle(l.spec)));
        return row;
    }

    /** One layer's row: its switch, its status line and, under the arrow, its controls. */
    private View buildRow(final LoadedLayer l, final String name) {
        final View row = PluginLayoutInflater.inflate(pluginContext, R.layout.layer_row, null);
        final TextView status = row.findViewById(R.id.row_status);
        status.setText(statusLine(l));
        // "You are in Elko County, NV", from the layer itself, while it is on.
        final TextView where = row.findViewById(R.id.row_where);
        final String whereLine = l.whereLine;
        if (whereLine != null && l.isVisible() && !l.refreshing && !l.busy) {
            where.setText(whereLine);
            where.setVisibility(View.VISIBLE);
        } else {
            where.setVisibility(View.GONE);
        }
        final Button toggle = row.findViewById(R.id.row_toggle);
        if (l.refreshing || l.busy) {
            toggle.setText(name + " Loading\u2026");
            toggle.setTextColor(Color.parseColor("#FFC107"));
            toggle.setEnabled(false);
        } else {
            toggle.setText(l.myFiresShown() && l.isVisible()
                    ? l.spec.title + ": My Fires (" + l.myFiresCount() + ")"
                    : name + (l.isVisible() ? " ON" : " OFF"));
            toggle.setTextColor(l.isVisible() ? Color.parseColor("#4CAF50") : Color.parseColor("#F44336"));
            toggle.setEnabled(true);
        }
        // One line per layer, its controls under the arrow (Atmosphere's layers page,
        // operator 2026-10-05). Closed until opened, and remembered per layer: the
        // list is rebuilt on every refresh, so the state cannot live in the views.
        final ImageButton expand = row.findViewById(R.id.row_expand);
        final View body = row.findViewById(R.id.row_body);
        final String foldKey = foldPref(l);
        final boolean open = uiPrefs().getBoolean(foldKey, false);
        expand.setRotation(open ? 180f : 0f);
        body.setVisibility(open ? View.VISIBLE : View.GONE);
        status.setVisibility(open ? View.VISIBLE : View.GONE);
        expand.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final boolean nowOpen = body.getVisibility() != View.VISIBLE;
                uiPrefs().edit().putBoolean(foldKey, nowOpen).apply();
                expand.setRotation(nowOpen ? 180f : 0f);
                body.setVisibility(nowOpen ? View.VISIBLE : View.GONE);
                status.setVisibility(nowOpen ? View.VISIBLE : View.GONE);
            }
        });
        toggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                manager.setVisible(l, !l.isVisible());
            }
        });
        row.findViewById(R.id.row_sets).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickSets(l);
            }
        });
        // Go to frames one incident; a feed spread over a state or the country has no
        // one place to go, and Features takes the row.
        bindColor(row, l);
        row.findViewById(R.id.row_goto).setVisibility(l.isWideFeed() ? View.GONE : View.VISIBLE);
        row.findViewById(R.id.row_goto).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                l.panTo();
            }
        });
        final Button auto = row.findViewById(R.id.row_auto);
        auto.setText(autoLabel(l.spec.refreshMinutes));
        auto.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final int[] steps = { 0, 1, 5, 15, 30 };
                int i = 0;
                for (int k = 0; k < steps.length; k++)
                    if (steps[k] == l.spec.refreshMinutes)
                        i = k;
                final int next = steps[(i + 1) % steps.length];
                auto.setText(autoLabel(next));
                manager.setRefreshMinutes(l, next);
            }
        });
        row.findViewById(R.id.row_refresh).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                manager.refreshHere(l);
            }
        });
        row.findViewById(R.id.row_remove).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                uiPrefs().edit().remove(foldKey).apply();
                manager.remove(l);
            }
        });
        bindKey((LinearLayout) row.findViewById(R.id.row_key), l);
        bindMyFires(row, l);
        final View scopeBlock = row.findViewById(R.id.row_scope);
        if (l.hasScopeControl()) {
            scopeBlock.setVisibility(View.VISIBLE);
            bindScope(row, l);
        } else {
            scopeBlock.setVisibility(View.GONE);
        }
        return row;
    }

    /** After a panel swap; posted so it runs once the new panel has been laid out. */
    private void scrollPaneToTop() {
        if (paneView == null)
            return;
        final android.widget.ScrollView sv = paneView.findViewById(R.id.pane_scroll);
        if (sv == null)
            return;
        sv.post(new Runnable() {
            @Override
            public void run() {
                sv.scrollTo(0, 0);
            }
        });
    }

    /**
     * Re-run the current find after its layer fetched, keeping the scroll where it was:
     * a vehicle's last-reported time moves, a vehicle arrives or leaves, the list does
     * not jump and does not revert to the type list.
     */
    private void refreshResultsInPlace() {
        if (paneView == null)
            return;
        final android.widget.ScrollView sv = paneView.findViewById(R.id.pane_scroll);
        final int keepY = sv == null ? 0 : sv.getScrollY();
        renderResults();
        if (sv != null)
            sv.post(new Runnable() {
                @Override
                public void run() {
                    sv.scrollTo(0, keepY);
                }
            });
    }

    /** The radius a layer gets when the operator asks for a point without naming one. */
    private static final int DEFAULT_SCOPE_BIG = 25;
    /** Radius choices, in the operator's own big unit. 0 is "what is in view". */
    private static final int[] SCOPE_PRESETS = { 0, 2, 5, 10, 25, 50 };

    private static String scopePresetLabel(LoadedLayer l, int r) {
        return r == 0 ? zeroLabel(l) : r + " " + Units.bigLabel();
    }

    /**
     * What the radius control's zero means for a layer. Most scoped layers hold too much
     * to fetch nationally, so zero is what is in view. New Fire Starts is a hundred or so
     * starts across the country: zero is everywhere, and Find reaches a fire wherever the
     * map is (operator, 2026-10-05, looking for FORK in Shasta with the map in Texas).
     */
    private static String zeroKind(LoadedLayer l) {
        return com.atakmap.android.featurelayer.NewStartsStyles.handles(l.spec) ? "all" : "view";
    }

    private static String zeroLabel(LoadedLayer l) {
        return "all".equals(zeroKind(l)) ? "Everywhere" : "What is in view";
    }

    /**
     * Cam Depot's radius control, on a layer that has a scope: the label says the state,
     * the slider is the radius (0 = what is in view), the From button names the point it
     * measures from and rotates it, Use this extent takes the radius from the map, and
     * Presets is the list. Every change fetches.
     */
    private void bindScope(final View row, final LoadedLayer l) {
        final TextView label = row.findViewById(R.id.row_scope_label);
        final android.widget.SeekBar seek = row.findViewById(R.id.row_scope_seek);
        final Button from = row.findViewById(R.id.row_scope_from);
        final boolean center = "center".equals(l.spec.scopeKind);
        final boolean inView = "view".equals(l.spec.scopeKind) || "all".equals(l.spec.scopeKind);
        final String fromName = center ? "Map Center" : "My Location";
        final int big = inView ? 0
                : (int) Math.max(0, Math.min(seek.getMax(), Math.round(l.spec.scopeRadiusM / Units.bigToMeters(1))));
        label.setText(l.scopeLabel());
        seek.setProgress(big);
        from.setText("Measuring from: " + fromName);
        seek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar sb, int p, boolean fromUser) {
                if (fromUser)
                    label.setText(p == 0 ? zeroLabel(l)
                            : "Within " + p + " " + Units.bigLabel() + " of " + fromName);
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar sb) {
                applyScope(l, sb.getProgress(), center ? "center" : "me");
            }
        });
        from.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Rotates between the two points. From "what is in view" it also needs a
                // radius, or the button would change nothing anyone could see.
                final int p = seek.getProgress() == 0 ? DEFAULT_SCOPE_BIG : seek.getProgress();
                applyScope(l, p, center ? "me" : "center");
            }
        });
        row.findViewById(R.id.row_scope_extent).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // "What I am looking at", as a radius: center to corner, so the whole
                // visible rectangle is inside the circle.
                final com.atakmap.coremap.maps.coords.GeoBounds b = mapView.getBounds();
                final com.atakmap.coremap.maps.coords.GeoPoint c = mapView.getPoint().get();
                if (b == null || c == null) {
                    toast("The map has no extent yet");
                    return;
                }
                final double m = c.distanceTo(new com.atakmap.coremap.maps.coords.GeoPoint(b.getNorth(), b.getEast()));
                final double bigD = m / Units.bigToMeters(1);
                if (bigD > seek.getMax())
                    toast(String.format(java.util.Locale.US, "That view is wider than %d %s, radius set to the maximum",
                            seek.getMax(), Units.bigLabel()));
                applyScope(l, (int) Math.max(1, Math.min(seek.getMax(), Math.round(bigD))), "center");
            }
        });
        row.findViewById(R.id.row_scope_preset).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String[] items = new String[SCOPE_PRESETS.length];
                int checked = -1;
                for (int i = 0; i < SCOPE_PRESETS.length; i++) {
                    items[i] = scopePresetLabel(l, SCOPE_PRESETS[i]);
                    if (SCOPE_PRESETS[i] == (inView ? 0 : big))
                        checked = i;
                }
                // MapView context, never the plugin context: a dialog on the plugin
                // context is a BadTokenException and ATAK dies.
                new android.app.AlertDialog.Builder(mapView.getContext())
                        .setTitle("Show " + l.spec.title.toLowerCase(java.util.Locale.US) + " within")
                        .setSingleChoiceItems(items, checked, new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int w) {
                                d.dismiss();
                                applyScope(l, SCOPE_PRESETS[w], center ? "center" : "me");
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        });
    }

    private void applyScope(LoadedLayer l, int big, String kind) {
        if (big <= 0)
            manager.setScope(l, zeroKind(l), 0);
        else
            manager.setScope(l, kind, Units.bigToMeters(big));
    }

    /**
     * Feature types of one layer, shown in the pane in place of the layer list so the map
     * stays visible: an ON/OFF button per type, a fill button for area types, Back on top.
     */
    /** The layer whose Features panel is showing, or null; re-rendered in place on change. */
    private LoadedLayer featuresFor;

    private void pickSets(final LoadedLayer l) {
        if (paneView == null || manager == null)
            return; // the pane is gone: a late notification after stop
        pickSets(l, false);
    }

    /**
     * @param inPlace true when the panel is already showing this layer and a fetch just
     *        changed it: the rows are rebuilt where they are, without clearing the find
     *        box or jumping to the top. A DART list that did not change as the map panned
     *        read as "panning does nothing" (2026-09-18).
     */
    /** What the Features panel last drew, so a fetch that changed nothing does not redraw it. */
    private String featuresSig;

    private static String featuresSignature(LoadedLayer l, List<LoadedLayer.SetInfo> sets) {
        final StringBuilder sb = new StringBuilder(l.spec.id).append('|').append(l.count);
        for (LoadedLayer.SetInfo si : sets)
            sb.append('|').append(si.id).append(':').append(si.name).append(':').append(si.visible);
        return sb.toString();
    }

    private void pickSets(final LoadedLayer l, final boolean inPlace) {
        if (paneView == null || manager == null)
            return; // the pane is gone: a late notification after stop
        final List<LoadedLayer.SetInfo> sets = l.types();
        // In place: redraw only when the types or counts moved, and keep the scroll
        // where it was. Rebuilding the rows empties the list for a frame and the
        // ScrollView snapped to the top on every pan (2026-09-18).
        final String sig = featuresSignature(l, sets);
        final android.widget.ScrollView sv = paneView == null ? null
                : (android.widget.ScrollView) paneView.findViewById(R.id.pane_scroll);
        final int keepY = inPlace && sv != null ? sv.getScrollY() : 0;
        if (inPlace && sig.equals(featuresSig))
            return;
        featuresSig = sig;
        if (sets.isEmpty()) {
            toast("Nothing loaded in this layer yet");
            return;
        }
        final View mainPanel = paneView.findViewById(R.id.main_panel);
        final View featuresPanel = paneView.findViewById(R.id.features_panel);
        final View featuresHeader = paneView.findViewById(R.id.features_header);
        final LinearLayout container = paneView.findViewById(R.id.features_container);
        ((TextView) paneView.findViewById(R.id.features_title)).setText(l.spec.title);
        final EditText layerSearch = paneView.findViewById(R.id.layer_search_text);
        if (!inPlace)
            layerSearch.setText("");
        final View.OnClickListener doFind = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                findFeature(layerSearch.getText().toString().trim(), l);
            }
        };
        paneView.findViewById(R.id.btn_layer_find).setOnClickListener(doFind);
        layerSearch.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                    doFind.onClick(v);
                    return true;
                }
                return false;
            }
        });
        container.removeAllViews();
        if (l.spec.timeField != null && l.spec.minAgeHours <= 0) {
            // How far back: the where clause changes, so this one refetches. Not on
            // Ongoing Fires, which is everything older than a day by definition.
            final View row = PluginLayoutInflater.inflate(pluginContext, R.layout.feature_row, null);
            ((TextView) row.findViewById(R.id.feature_name)).setText("Time window");
            final Button win = row.findViewById(R.id.feature_fill);
            win.setText(l.spec.windowLabel());
            row.findViewById(R.id.feature_toggle).setVisibility(View.GONE);
            // New Fire Starts holds 24 hours and nothing older, so it offers the
            // hours inside that; every other windowed layer reaches back days.
            final boolean starts = com.atakmap.android.featurelayer.NewStartsStyles.handles(l.spec);
            final int[] hours = starts ? new int[] { 1, 3, 6, 12, 24 } : new int[] { 6, 12, 24, 72, 168, 720, 0 };
            final String[] labels = starts
                    ? new String[] { "Last hour", "Last 3 hours", "Last 6 hours", "Last 12 hours", "Last 24 hours" }
                    : new String[] { "Last 6 hours", "Last 12 hours", "Last 24 hours", "Last 3 days", "Last 7 days", "Last 30 days", "All time" };
            win.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    int checked = starts ? hours.length - 1 : 3;
                    for (int i = 0; i < hours.length; i++)
                        if (hours[i] == l.spec.sinceHours)
                            checked = i;
                    new AlertDialog.Builder(mapView.getContext()).setTitle(starts ? "Fires reported in" : "Perimeters from")
                            .setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface d, int which) {
                                    d.dismiss();
                                    win.setText(labels[which]);
                                    manager.setSinceHours(l, hours[which]);
                                }
                            }).setNegativeButton("Cancel", null).show();
                }
            });
            container.addView(row);
        }
        {
            // Zoom gate, Cam Depot's way: set it by example ("Use this zoom") or pick a
            // scale-bar reading; nothing in the layer draws further out than that.
            final View row = PluginLayoutInflater.inflate(pluginContext, R.layout.feature_row, null);
            ((TextView) row.findViewById(R.id.feature_name)).setText("Zoom gate");
            final Button useThis = row.findViewById(R.id.feature_toggle);
            useThis.setText("Use this zoom");
            useThis.setTextColor(Color.WHITE);
            final Button preset = row.findViewById(R.id.feature_fill);
            preset.setText(gateLabel(l));
            useThis.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    manager.setGate(l, mapView.getMapResolution());
                    preset.setText(gateLabel(l));
                }
            });
            preset.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    final String[] labels = new String[GATE_BIG.length + 1];
                    for (int i = 0; i < GATE_BIG.length; i++)
                        labels[i] = gateName(GATE_BIG[i]);
                    labels[GATE_BIG.length] = "Always";
                    new AlertDialog.Builder(mapView.getContext()).setTitle("Draw when the scale bar reads")
                            .setItems(labels, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface d, int which) {
                                    final double gsd = which == GATE_BIG.length ? Double.MAX_VALUE
                                            : com.atakmap.android.featurelayer.Units.bigToMeters(GATE_BIG[which]) / com.atakmap.android.featurelayer.ScaleBar.FALLBACK_BAR_PIXELS;
                                    manager.setGate(l, gsd);
                                    preset.setText(gateLabel(l));
                                }
                            }).setNegativeButton("Cancel", null).show();
                }
            });
            container.addView(row);
        }
        {
            final View row = PluginLayoutInflater.inflate(pluginContext, R.layout.feature_row, null);
            ((TextView) row.findViewById(R.id.feature_name)).setText("Labels");
            final Button toggle = row.findViewById(R.id.feature_toggle);
            final boolean[] on = { l.spec.labels };
            styleToggle(toggle, on[0]);
            toggle.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    on[0] = !on[0];
                    styleToggle(toggle, on[0]);
                    manager.setLabels(l, on[0]);
                }
            });
            row.findViewById(R.id.feature_fill).setVisibility(View.INVISIBLE);
            container.addView(row);
        }
        {
            // Label zoom, the same shape as the zoom gate: set it by example ("Use this
            // zoom") or pick a scale-bar reading. Names from there in, symbols alone
            // further out; 5 mi unless changed. "Always" is a choice, not the default.
            final View row = PluginLayoutInflater.inflate(pluginContext, R.layout.feature_row, null);
            ((TextView) row.findViewById(R.id.feature_name)).setText("Label zoom");
            final Button useThis = row.findViewById(R.id.feature_toggle);
            useThis.setText("Use this zoom");
            useThis.setTextColor(Color.WHITE);
            final Button level = row.findViewById(R.id.feature_fill);
            level.setText(labelLevel(l));
            useThis.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    manager.setLabelLevel(l, mapView.getMapResolution());
                    level.setText(labelLevel(l));
                }
            });
            level.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    final String[] labels = new String[GATE_BIG.length + 1];
                    for (int i = 0; i < GATE_BIG.length; i++)
                        labels[i] = gateName(GATE_BIG[i]);
                    labels[GATE_BIG.length] = "Always";
                    new AlertDialog.Builder(mapView.getContext()).setTitle("Label when the scale bar reads")
                            .setItems(labels, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface d, int which) {
                                    final double gsd = which == GATE_BIG.length ? Double.MAX_VALUE
                                            : com.atakmap.android.featurelayer.Units.bigToMeters(GATE_BIG[which]) / com.atakmap.android.featurelayer.ScaleBar.FALLBACK_BAR_PIXELS;
                                    manager.setLabelLevel(l, gsd);
                                    level.setText(labelLevel(l));
                                }
                            }).setNegativeButton("Cancel", null).show();
                }
            });
            container.addView(row);
        }
        if (com.atakmap.android.featurelayer.DartStyles.handles(l.spec)) {
            // What the ring colors mean, where the layer is set up; the list says it too.
            final View row = PluginLayoutInflater.inflate(pluginContext, R.layout.feature_row, null);
            final android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder("Reported ");
            b.append(ageLegend(l.spec.id.contains("personnel")));
            ((TextView) row.findViewById(R.id.feature_name)).setText(b);
            row.findViewById(R.id.feature_toggle).setVisibility(View.GONE);
            row.findViewById(R.id.feature_fill).setVisibility(View.GONE);
            container.addView(row);
        }
        if (l.spec.profile == LayerSpec.Profile.NWCG) {
            // NWCG's secondary symbology: off by default, as on NIFC's own incident map,
            // because its magenta "In Use" reads as a planned line.
            final View row = PluginLayoutInflater.inflate(pluginContext, R.layout.feature_row, null);
            ((TextView) row.findViewById(R.id.feature_name)).setText("Repair status halos");
            final Button toggle = row.findViewById(R.id.feature_toggle);
            final boolean[] on = { l.spec.repairStatus };
            styleToggle(toggle, on[0]);
            toggle.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    on[0] = !on[0];
                    styleToggle(toggle, on[0]);
                    manager.setRepairStatus(l, on[0]);
                }
            });
            row.findViewById(R.id.feature_fill).setVisibility(View.INVISIBLE);
            container.addView(row);
        }
        for (final LoadedLayer.SetInfo si : sets) {
            final View row = PluginLayoutInflater.inflate(pluginContext, R.layout.feature_row, null);
            final String note = l.spec.setNotes.get(si.name);
            if (note == null) {
                ((TextView) row.findViewById(R.id.feature_name)).setText(si.name);
            } else {
                // The name, then what it is in smaller gray type underneath.
                final android.text.SpannableString two = new android.text.SpannableString(si.name + "\n" + note);
                two.setSpan(new android.text.style.RelativeSizeSpan(0.78f), si.name.length() + 1, two.length(), 0);
                two.setSpan(new android.text.style.ForegroundColorSpan(0xFFBBBBBB), si.name.length() + 1, two.length(), 0);
                ((TextView) row.findViewById(R.id.feature_name)).setText(two);
            }
            final Button toggle = row.findViewById(R.id.feature_toggle);
            final boolean[] on = { si.visible };
            styleToggle(toggle, on[0]);
            toggle.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    on[0] = !on[0];
                    styleToggle(toggle, on[0]);
                    manager.setSetOn(l, si.name, on[0]);
                }
            });
            final Button fill = row.findViewById(R.id.feature_fill);
            if ("polygon".equals(l.spec.setKind.get(si.name))) {
                fill.setText(fillLabel(l.spec.fillFor(si.name)));
                fill.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        final int cur = l.spec.fillFor(si.name);
                        final int next = cur == 0x40 ? 0x80 : cur == 0x80 ? 0 : 0x40;
                        fill.setText(fillLabel(next));
                        manager.setSetFill(l, si.name, next);
                    }
                });
            } else {
                fill.setVisibility(View.INVISIBLE);
            }
            container.addView(row);
        }
        paneView.findViewById(R.id.btn_features_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                featuresFor = null;
                featuresSig = null;
                featuresPanel.setVisibility(View.GONE);
                featuresHeader.setVisibility(View.GONE);
                mainPanel.setVisibility(View.VISIBLE);
                renderRows();
                scrollPaneToTop();
            }
        });
        featuresFor = l;
        mainPanel.setVisibility(View.GONE);
        featuresHeader.setVisibility(View.VISIBLE);
        featuresPanel.setVisibility(View.VISIBLE);
        // The panels swap inside one ScrollView, which keeps its offset: from a layer row
        // halfway down the list, Features opened halfway down the feature list, with the
        // find box scrolled off the top (2026-09-18). Start every panel at its top --
        // unless this is a redraw in place, which goes back to where the operator was.
        if (!inPlace) {
            scrollPaneToTop();
        } else if (sv != null) {
            sv.post(new Runnable() {
                @Override
                public void run() {
                    sv.scrollTo(0, keepY);
                }
            });
        }
    }

    private static String autoLabel(int minutes) {
        return minutes <= 0 ? "Auto: off" : "Auto: " + minutes + " min";
    }

    private static void styleToggle(Button b, boolean on) {
        b.setText(on ? "ON" : "OFF");
        b.setTextColor(on ? Color.parseColor("#4CAF50") : Color.parseColor("#F44336"));
    }

    private static String fillLabel(int alpha) {
        return alpha == 0 ? "Outline" : alpha >= 0x80 ? "Fill 50%" : "Fill 25%";
    }

    /**
     * The report-age colors spelled out: the ring on a DART marker and the age in its
     * row. Asked for on 2026-09-18 ("could we display what the schema is somewhere"):
     * under the layer's controls, and over its list.
     */
    private static CharSequence ageLegend(boolean personnel) {
        final int[] cut = com.atakmap.android.featurelayer.DartStyles.ageCutoffs(personnel, false);
        final android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder();
        legendDot(b, com.atakmap.android.featurelayer.DartStyles.ageColor(0), "under " + cut[0] + " min");
        legendDot(b, com.atakmap.android.featurelayer.DartStyles.ageColor(1), "under " + cut[1] + " min");
        legendDot(b, com.atakmap.android.featurelayer.DartStyles.ageColor(2), "older");
        legendDot(b, 0xFFBBBBBB, "no time");
        return b;
    }

    private static void legendDot(android.text.SpannableStringBuilder b, int color, String what) {
        if (b.length() > 0)
            b.append("   ");
        final int at = b.length();
        b.append("\u25cf ");
        b.setSpan(new android.text.style.ForegroundColorSpan(color), at, at + 1,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        b.append(what);
    }

    /**
     * How wide the search is, on the line the results sit under: there is no search of
     * everything, a scoped layer searches its own area, and people have to be told
     * (operator, 2026-09-18: "people need to know there is no overall search, it's by
     * extent set on the map").
     */
    private String scopeNote(LoadedLayer only) {
        if (only != null) {
            if (!only.hasScopeControl())
                return "";
            final String label = only.scopeLabel();
            return " \u00b7 " + Character.toLowerCase(label.charAt(0)) + label.substring(1);
        }
        if (manager != null)
            for (LoadedLayer l : manager.snapshot())
                if (l.hasScopeControl() && !l.holdsEverything())
                    return " \u00b7 scoped layers search only their own area";
        return "";
    }

    // ---- a typed name asks the feed, not only the cached view ----------------------
    private String feedText;                       // the text the feed was last asked for
    private List<LoadedLayer.Hit> feedHits;        // its answer, or null while it is being asked
    private long feedAt;                           // when it answered, so a stale answer is asked again
    private String feedError;
    private boolean feedAsking;

    /**
     * The feed's answer for the typed text in a scoped layer, asking for it if it has not
     * been asked (or the answer is older than two minutes). Null while the answer is on
     * its way, so the cached matches show meanwhile.
     */
    private List<LoadedLayer.Hit> feedHitsFor(final String text, final LoadedLayer only) {
        if (only == null || !only.hasScopeControl() || only.holdsEverything() || text == null || text.isEmpty())
            return null;
        final boolean fresh = text.equals(feedText) && feedHits != null && System.currentTimeMillis() - feedAt < 120_000;
        if (fresh)
            return feedHits;
        if (feedAsking && text.equals(feedText))
            return null;
        feedText = text;
        feedHits = null;
        feedError = null;
        feedAsking = true;
        manager.searchFeed(only, text, new LayerManager.SearchCallback<List<LoadedLayer.Hit>>() {
            @Override
            public void onResult(List<LoadedLayer.Hit> result, String error) {
                feedAsking = false;
                if (!text.equals(feedText))
                    return; // the box moved on
                feedHits = result == null ? new java.util.ArrayList<LoadedLayer.Hit>() : result;
                feedError = error;
                feedAt = System.currentTimeMillis();
                if (paneView != null && paneView.findViewById(R.id.search_panel).getVisibility() == View.VISIBLE)
                    refreshResultsInPlace();
            }
        });
        return null;
    }

    /** The loaded Fire History layer, or null. */
    private LoadedLayer historyLayer() {
        if (manager != null)
            for (LoadedLayer l : manager.snapshot())
                if (com.atakmap.android.featurelayer.FireHistoryStyles.handles(l.spec))
                    return l;
        return null;
    }

    /** Whether the layers behind the list are fetching right now: a pan just asked for a new area. */
    private boolean scanning(LoadedLayer only) {
        if (only != null)
            return only.refreshing || only.busy || only.pendingMove;
        if (manager != null)
            for (LoadedLayer l : manager.snapshot())
                if (l.refreshing || l.busy || l.pendingMove)
                    return true;
        return false;
    }

    /**
     * What an empty or in-progress list says, so a pan to a new area reads as "looking"
     * and then "nothing here", never as a list that quietly stayed empty (operator,
     * 2026-09-18: "loading features or scanning area or something so you know").
     */
    private String emptyOrScanning(String text, LoadedLayer only) {
        if (scanning(only))
            return "Scanning this area\u2026";
        if (text != null && !text.isEmpty())
            return "Nothing matches \"" + text + "\" in this view";
        return filterType != null ? "No " + filterType + " in this view" : "No features in this view";
    }

    /** A status line with the legend under it when the list is one DART layer's. */
    private static CharSequence withLegend(CharSequence line, LoadedLayer only) {
        if (only == null || !com.atakmap.android.featurelayer.DartStyles.handles(only.spec))
            return line;
        final android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder(line);
        b.append("\nReported ").append(ageLegend(only.spec.id.contains("personnel")));
        return b;
    }

    /**
     * The map key under a fire layer's arrow: each type's marker beside its name, from
     * the images the map draws with. Other layers have none, and the block stays gone.
     */
    private void bindKey(LinearLayout key, LoadedLayer l) {
        if (key == null)
            return;
        if (com.atakmap.android.featurelayer.FireHistoryStyles.handles(l.spec)) {
            bindHistoryKey(key, l);
            return;
        }
        if (com.atakmap.android.featurelayer.Boundaries.handles(l.spec)
                && com.atakmap.android.featurelayer.Boundaries.mosaic(l.spec.kind)) {
            bindMosaicKey(key, l);
            return;
        }
        if (!com.atakmap.android.featurelayer.NewStartsStyles.handles(l.spec)) {
            key.setVisibility(View.GONE);
            return;
        }
        while (key.getChildCount() > 1)
            key.removeViewAt(1); // keep the heading
        final float dp = paneView.getResources().getDisplayMetrics().density;
        for (String[] k : com.atakmap.android.featurelayer.NewStartsStyles.KEY) {
            final java.io.File f = l.keyIcon(k[0]);
            final android.graphics.Bitmap bmp = f == null ? null
                    : android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath());
            final LinearLayout line = new LinearLayout(key.getContext());
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(android.view.Gravity.CENTER_VERTICAL);
            line.setPadding(0, Math.round(3 * dp), 0, Math.round(3 * dp));
            final android.widget.ImageView icon = new android.widget.ImageView(key.getContext());
            if (bmp != null)
                icon.setImageBitmap(bmp);
            final int side = Math.round(32 * dp);
            line.addView(icon, new LinearLayout.LayoutParams(side, side));
            final TextView name = new TextView(key.getContext());
            name.setText(k[1]);
            name.setTextColor(Color.WHITE);
            name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
            name.setPadding(Math.round(10 * dp), 0, 0, 0);
            line.addView(name);
            key.addView(line);
        }
        key.setVisibility(View.VISIBLE);
    }

    /**
     * A land layer's key: each class the view holds as a swatch in the color it is drawn
     * in, read from the features themselves, so the key and the map cannot disagree
     * (operator, 2026-10-14: the Features list named the classes without colors).
     */
    private void bindMosaicKey(LinearLayout key, LoadedLayer l) {
        while (key.getChildCount() > 1)
            key.removeViewAt(1);
        final java.util.LinkedHashMap<String, Integer> hues = l.classHues();
        if (hues.isEmpty()) {
            key.setVisibility(View.GONE);
            return;
        }
        final float dp = paneView.getResources().getDisplayMetrics().density;
        for (java.util.Map.Entry<String, Integer> k : hues.entrySet()) {
            final LinearLayout line = new LinearLayout(key.getContext());
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(android.view.Gravity.CENTER_VERTICAL);
            line.setPadding(0, Math.round(2 * dp), 0, Math.round(2 * dp));
            final View swatch = new View(key.getContext());
            final android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
            d.setColor(0xFF000000 | k.getValue());
            d.setStroke(Math.max(1, Math.round(dp)), 0xFF999999);
            d.setCornerRadius(3 * dp);
            swatch.setBackground(d);
            final int side = Math.round(22 * dp);
            line.addView(swatch, new LinearLayout.LayoutParams(side, side));
            final TextView name = new TextView(key.getContext());
            name.setText(k.getKey() + (l.spec.isOn(k.getKey()) ? "" : "  (off)"));
            name.setTextColor(l.spec.isOn(k.getKey()) ? Color.WHITE : 0xFF999999);
            name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
            name.setPadding(Math.round(10 * dp), 0, 0, 0);
            line.addView(name);
            key.addView(line);
        }
        key.setVisibility(View.VISIBLE);
    }

    /**
     * Fire History's key: each band and decade as a swatch in EGP's fill, from the same
     * table the map is drawn with.
     */
    private void bindHistoryKey(LinearLayout key, LoadedLayer l) {
        while (key.getChildCount() > 1)
            key.removeViewAt(1);
        final float dp = paneView.getResources().getDisplayMetrics().density;
        final List<Object[]> rows = new java.util.ArrayList<>(java.util.Arrays.asList(com.atakmap.android.featurelayer.FireHistoryStyles.KEY));
        if (l.myFiresCount() > 0)
            rows.add(new Object[] { "My Fires", null }); // the white edge, no fill of its own
        for (Object[] k : rows) {
            final LinearLayout line = new LinearLayout(key.getContext());
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(android.view.Gravity.CENTER_VERTICAL);
            line.setPadding(0, Math.round(2 * dp), 0, Math.round(2 * dp));
            final View swatch = new View(key.getContext());
            final android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
            if (k[1] == null) {
                d.setColor(0x00000000);
                d.setStroke(Math.max(2, Math.round(2.5f * dp)), 0xFFFFFFFF);
            } else {
                d.setColor(0xFF000000 | (Integer) k[1]);
                d.setStroke(Math.max(1, Math.round(dp)), 0xFF999999);
            }
            d.setCornerRadius(3 * dp);
            swatch.setBackground(d);
            final int side = Math.round(22 * dp);
            line.addView(swatch, new LinearLayout.LayoutParams(side, side));
            final TextView name = new TextView(key.getContext());
            name.setText((String) k[0]);
            name.setTextColor(Color.WHITE);
            name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
            name.setPadding(Math.round(10 * dp), 0, 0, 0);
            line.addView(name);
            key.addView(line);
        }
        key.setVisibility(View.VISIBLE);
    }

    /**
     * My Fires on a Fire History row, once the list has a fire: Only My Fires ON (green)
     * or OFF (red) with the count, and Clear My Fires behind a confirmation, since the
     * list is the operator's own work for an incident.
     */
    private void bindMyFires(View row, final LoadedLayer l) {
        final View block = row.findViewById(R.id.row_myfires);
        final int n = l.myFiresCount();
        if (!com.atakmap.android.featurelayer.FireHistoryStyles.handles(l.spec) || n == 0) {
            block.setVisibility(View.GONE);
            return;
        }
        block.setVisibility(View.VISIBLE);
        final Button only = row.findViewById(R.id.row_myfires_only);
        final boolean on = l.myFiresShown();
        only.setText("Only My Fires " + (on ? "ON" : "OFF") + " (" + n + ")");
        only.setTextColor(on ? Color.parseColor("#4CAF50") : Color.parseColor("#F44336"));
        only.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                manager.myFires(l, on ? com.atakmap.android.featurelayer.LayerManager.MY_ONLY_OFF
                        : com.atakmap.android.featurelayer.LayerManager.MY_ONLY_ON, null);
            }
        });
        row.findViewById(R.id.row_myfires_clear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(mapView.getContext())
                        .setTitle("Clear My Fires")
                        .setMessage("Take " + (n == 1 ? "the fire" : n == 2 ? "both fires" : "all " + n + " fires")
                                + " out of My Fires and show every fire again?")
                        .setPositiveButton("Clear", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                manager.myFires(l, com.atakmap.android.featurelayer.LayerManager.MY_CLEAR, null);
                            }
                        }).setNegativeButton("Cancel", null).show();
            }
        });
    }

    /** Where a layer row's open/closed state is kept, by layer id. */
    private static String foldPref(LoadedLayer l) {
        return "fold." + l.spec.id;
    }

    private static String statusLine(LoadedLayer l) {
        final StringBuilder sb = new StringBuilder();
        if (l.refreshing)
            return "Loading features\u2026 " + l.progress + " so far";
        if (l.busy)
            return "Loading features\u2026";
        sb.append(l.count).append(l.count == 1 ? " feature" : " features");
        if (l.lastRefresh > 0)
            sb.append(" · refreshed ").append(age(l.lastRefresh)).append(" ago");
        else
            sb.append(" · never refreshed");
        if (l.spec.refreshMinutes > 0)
            sb.append(" · auto ").append(l.spec.refreshMinutes).append(" min");
        if (!l.refreshing && l.stale)
            sb.append(com.atakmap.android.featurelayer.LoadedLayer.NO_NETWORK.equals(l.status) ? " · " : " · STALE: ")
                    .append(l.status);
        else if (!l.refreshing && l.status.startsWith("partial"))
            sb.append(" · ").append(l.status);
        if (l.capped)
            sb.append(" \u00b7 ").append(l.spec.maxFeatures).append(" shown, more exist: zoom in or shrink the radius");
        if (l.unnamedHidden > 0)
            sb.append(" \u00b7 ").append(l.unnamedHidden).append(l.unnamedHidden == 1 ? " fire" : " fires")
                    .append(" with no name, over 1 h, left out");
        return sb.toString();
    }

    private static String age(long since) {
        final long s = Math.max(0, (System.currentTimeMillis() - since) / 1000);
        if (s < 60)
            return s + " s";
        if (s < 3600)
            return (s / 60) + " min";
        if (s < 86400)
            return (s / 3600) + " h";
        return (s / 86400) + " d";
    }

    /**
     * What to draw from DART. Two services, so two layers: each is turned off or removed
     * on its own, which is the toggle. Vehicles is the denser half by a factor of ten
     * (3,981 nationally against 387 people), so it is added first and personnel draws
     * over the top of it.
     */
    private void pickDart() {
        if (manager == null)
            return;
        final String[] labels = { "Personnel and vehicles", "Personnel only", "Vehicles only" };
        new AlertDialog.Builder(mapView.getContext())
                .setTitle("Add DART")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (which != 1)
                            addLayer(Sources.dartVehicles());
                        if (which != 2)
                            addLayer(Sources.dartPersonnel());
                        d.dismiss();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void pickFire(final List<Sources.Fire> fires) {
        final String[] labels = new String[fires.size()];
        for (int i = 0; i < fires.size(); i++)
            labels[i] = fires.get(i).label();
        new AlertDialog.Builder(mapView.getContext())
                .setTitle("Which fire?")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        final Sources.Fire fire = fires.get(which);
                        if (!manager.auth(Sources.NIFC_PORTAL).isSignedIn())
                            toast("Sign in to NIFC to load " + fire.name);
                        addLayer(Sources.nifsLive(fire));
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void pickItem(final String portal, final List<com.atakmap.android.featurelayer.Esri.Item> items) {
        final String[] labels = new String[items.size()];
        for (int i = 0; i < items.size(); i++) {
            // Owner and server, so a look-alike from elsewhere reads as one.
            final String host = items.get(i).url == null ? "?" : com.atakmap.android.featurelayer.Esri.hostOf(items.get(i).url);
            labels[i] = items.get(i).title + " (" + items.get(i).owner + ") \u00b7 " + host;
        }
        new AlertDialog.Builder(mapView.getContext())
                .setTitle("Which layer?")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        final com.atakmap.android.featurelayer.Esri.Item item = items.get(which);
                        manager.search(new Callable<LayerSpec>() {
                            @Override
                            public LayerSpec call() throws Exception {
                                final String base = item.url.replaceAll("/+$", "");
                                // The org's token goes only to the org's own servers. A result hosted
                                // elsewhere is read anonymously, and kept anonymous for good.
                                final boolean ours = com.atakmap.android.featurelayer.Esri.trustsServer(portal, base);
                                final String token = ours ? manager.auth(portal).getValidToken() : null;
                                final int[] ids;
                                try {
                                    ids = com.atakmap.android.featurelayer.Esri.serviceLayerIds(base, token);
                                } catch (Exception e) {
                                    if (!ours)
                                        throw new IllegalStateException("not on " + org.title + "'s servers and not public");
                                    throw e;
                                }
                                if (ids.length == 0)
                                    throw new IllegalStateException("that service has no layers");
                                final LayerSpec spec = Sources.customService(portal, org.title, item, ids);
                                if (!ours)
                                    spec.portal = null;
                                return spec;
                            }
                        }, new LayerManager.SearchCallback<LayerSpec>() {
                            @Override
                            public void onResult(LayerSpec spec, String error) {
                                if (error != null)
                                    toast("Could not read " + item.title + ": " + error);
                                else
                                    addLayer(spec);
                            }
                        });
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void pickSarcop(final List<Sources.SarcopEvent> events) {
        final String[] labels = new String[events.size()];
        for (int i = 0; i < events.size(); i++)
            labels[i] = events.get(i).label();
        new AlertDialog.Builder(mapView.getContext())
                .setTitle("Which SARCOP incident?")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        addLayer(Sources.sarcopTraining(events.get(which)));
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}

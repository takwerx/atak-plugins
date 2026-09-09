package com.atakmap.android.featurelayer.plugin;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
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
        if (manager != null) {
            manager.stop();
            manager = null;
        }
        if (uiService != null)
            uiService.removeToolbarItem(toolbarItem);
        unregisterPreferences();
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
                    auth.signIn(new ArcGisAuth.Callback() {
                        @Override
                        public void onSignedIn(String username) {
                            refreshOrgUi();
                            toast(org.title + ": signed in as " + username);
                            if (org.custom && org.title.equals(LayerManager.hostOf(org.portal)))
                                resolveOrgName(org.portal); // a private org only names itself to a member
                            manager.refreshAll();
                        }

                        @Override
                        public void onFailed(String reason) {
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
            paneView.findViewById(R.id.btn_find_feature).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    findFeature(search.getText().toString().trim());
                }
            });
            // Clears the source search box, beside All ON/OFF where the operator asked for it.
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
    private void findFeature(final String text) {
        if (manager == null)
            return;
        final List<LoadedLayer> layers = manager.snapshot();
        if (layers.isEmpty()) {
            toast("Nothing loaded yet");
            return;
        }
        if (layers.size() == 1) {
            findFeature(text, layers.get(0));
            return;
        }
        final String[] labels = new String[layers.size()];
        for (int i = 0; i < layers.size(); i++)
            labels[i] = layers.get(i).spec.title;
        new AlertDialog.Builder(mapView.getContext()).setTitle("Search in")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        findFeature(text, layers.get(which));
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    // ---- the search pane: every loaded feature, filtered and sorted, with Back ----------

    private static final int RESULT_CAP = 200;
    private static final String[] SORT_LABELS = { "Nearest", "Newest", "Oldest", "Name" };
    private String filterType;      // null = all
    private LoadedLayer scope;      // opened from a layer's Features: only that layer, Back returns there
    private boolean fromMapCenter;  // distances from where the map is now, instead of from the device
    private int sortMode;           // index into SORT_LABELS

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

    private String gateLabel(LoadedLayer l) {
        if (l.spec.gateGsd == Double.MAX_VALUE)
            return "Always";
        return com.atakmap.android.featurelayer.ScaleBar.describe(l.spec.gateGsd * scaleBarPixels()) + " or closer";
    }

    /** Opens the search pane with the text inside one layer: a fire, an incident, a source. */
    private void findFeature(String text, LoadedLayer only) {
        if (manager == null || paneView == null || only == null)
            return;
        scope = only;
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
            for (LoadedLayer.Hit h : l.find(text, 5000))
                hits.add(new Object[] { l, h });
        }
        return hits;
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
            status.setText(counts.size() + " types, " + all.size() + " features \u00b7 tap a type, or type a name");
            container.removeAllViews();
            for (final java.util.Map.Entry<String, Integer> e : counts.entrySet()) {
                final View row = PluginLayoutInflater.inflate(pluginContext, R.layout.result_row, null);
                ((TextView) row.findViewById(R.id.result_title)).setText(e.getKey());
                ((TextView) row.findViewById(R.id.result_sub)).setText("");
                ((TextView) row.findViewById(R.id.result_goto)).setText("tap to list");
                ((TextView) row.findViewById(R.id.result_dist)).setText(String.valueOf(e.getValue()));
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
        for (Object[] o : all)
            if (filterType == null || filterType.equalsIgnoreCase(((LoadedLayer.Hit) o[1]).type))
                hits.add(o);
        // From the device, or from the map's center; without a fix, the map center stands in.
        final com.atakmap.coremap.maps.coords.GeoPoint me = fromMapCenter ? null : selfPoint();
        final boolean noFix = !fromMapCenter && me == null;
        final com.atakmap.coremap.maps.coords.GeoPoint from = me != null ? me : mapCenter();
        final java.util.Map<Object[], Double> dist = new java.util.HashMap<>();
        if (from != null)
            for (Object[] o : hits) {
                final LoadedLayer.Hit h = (LoadedLayer.Hit) o[1];
                dist.put(o, com.atakmap.coremap.maps.coords.GeoCalculations.distanceTo(from,
                        new com.atakmap.coremap.maps.coords.GeoPoint(h.lat, h.lon)));
            }
        final int mode = sortMode == 0 && from == null ? 3 : sortMode;
        java.util.Collections.sort(hits, new java.util.Comparator<Object[]>() {
            @Override
            public int compare(Object[] a, Object[] b) {
                final LoadedLayer.Hit x = (LoadedLayer.Hit) a[1], y = (LoadedLayer.Hit) b[1];
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
        st.append(hits.size()).append(hits.size() == 1 ? " feature" : " features");
        if (hits.size() > RESULT_CAP)
            st.append(", showing ").append(SORT_LABELS[mode].toLowerCase(java.util.Locale.US)).append(" ").append(RESULT_CAP);
        if (noFix && from != null)
            st.append(" \u00b7 no GPS fix, measured from the map center");
        else if (from == null)
            st.append(" \u00b7 nowhere to measure from, sorted by name");
        status.setText(st.toString());
        container.removeAllViews();
        final java.text.SimpleDateFormat when = new java.text.SimpleDateFormat("MMM d HH:mm", java.util.Locale.US);
        int shown = 0;
        for (final Object[] o : hits) {
            if (shown++ >= RESULT_CAP)
                break;
            final LoadedLayer l = (LoadedLayer) o[0];
            final LoadedLayer.Hit h = (LoadedLayer.Hit) o[1];
            final View row = PluginLayoutInflater.inflate(pluginContext, R.layout.result_row, null);
            ((TextView) row.findViewById(R.id.result_title)).setText(h.title);
            final StringBuilder sub = new StringBuilder();
            if (h.type != null && !h.type.equalsIgnoreCase(h.title))
                sub.append(h.type);
            if (scope == null)
                sub.append(sub.length() > 0 ? " \u00b7 " : "").append(h.layer);
            if (h.time > 0)
                sub.append(sub.length() > 0 ? " \u00b7 " : "").append(when.format(new java.util.Date(h.time)));
            ((TextView) row.findViewById(R.id.result_sub)).setText(sub.toString());
            ((TextView) row.findViewById(R.id.result_dist)).setText(dist.containsKey(o)
                    ? com.atakmap.android.featurelayer.Units.format(dist.get(o)) : "");
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    l.zoomTo(h);
                }
            });
            container.addView(row);
        }
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
        if (org == null) {
            orgButton.setText("Pick a source");
            signin.setVisibility(View.GONE);
            searchRow.setVisibility(View.GONE);
            return;
        }
        orgButton.setText(org.title);
        searchRow.setVisibility(View.VISIBLE);
        find.setText(org.findLabel);
        search.setHint(org.hint);
        search.setVisibility("nifs-archive".equals(org.id) || "ca-air-intel".equals(org.id) ? View.GONE : View.VISIBLE);
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
                manager.add(Sources.nifsArchiveDemo());
                return;
            case "ca-air-intel":
                manager.add(Sources.caAirIntel());
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

    private void renderRows() {
        if (paneView == null || manager == null)
            return;
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
        for (final LoadedLayer l : layers) {
            final View row = PluginLayoutInflater.inflate(pluginContext, R.layout.layer_row, null);
            ((TextView) row.findViewById(R.id.row_title)).setText(l.displayName());
            ((TextView) row.findViewById(R.id.row_status)).setText(statusLine(l));
            final Button toggle = row.findViewById(R.id.row_toggle);
            if (l.refreshing || l.busy) {
                toggle.setText("Loading\u2026");
                toggle.setTextColor(Color.parseColor("#FFC107"));
                toggle.setEnabled(false);
            } else {
                toggle.setText(l.isVisible() ? "ON" : "OFF");
                toggle.setTextColor(l.isVisible() ? Color.parseColor("#4CAF50") : Color.parseColor("#F44336"));
                toggle.setEnabled(true);
            }
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
                    manager.refresh(l);
                }
            });
            row.findViewById(R.id.row_remove).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    manager.remove(l);
                }
            });
            container.addView(row);
        }
    }

    /**
     * Feature types of one layer, shown in the pane in place of the layer list so the map
     * stays visible: an ON/OFF button per type, a fill button for area types, Back on top.
     */
    private void pickSets(final LoadedLayer l) {
        final List<LoadedLayer.SetInfo> sets = l.types();
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
        if (l.spec.timeField != null) {
            // How far back: the where clause changes, so this one refetches.
            final View row = PluginLayoutInflater.inflate(pluginContext, R.layout.feature_row, null);
            ((TextView) row.findViewById(R.id.feature_name)).setText("Time window");
            final Button win = row.findViewById(R.id.feature_fill);
            win.setText(l.spec.windowLabel());
            row.findViewById(R.id.feature_toggle).setVisibility(View.GONE);
            final int[] hours = { 6, 12, 24, 72, 168, 720, 0 };
            final String[] labels = { "Last 6 hours", "Last 12 hours", "Last 24 hours", "Last 3 days", "Last 7 days", "Last 30 days", "All time" };
            win.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    int checked = 3;
                    for (int i = 0; i < hours.length; i++)
                        if (hours[i] == l.spec.sinceHours)
                            checked = i;
                    new AlertDialog.Builder(mapView.getContext()).setTitle("Perimeters from")
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
                                            : com.atakmap.android.featurelayer.Units.bigToMeters(GATE_BIG[which]) / scaleBarPixels();
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
                // The name, then what it is in smaller grey type underneath.
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
                featuresPanel.setVisibility(View.GONE);
                featuresHeader.setVisibility(View.GONE);
                mainPanel.setVisibility(View.VISIBLE);
                renderRows();
            }
        });
        mainPanel.setVisibility(View.GONE);
        featuresHeader.setVisibility(View.VISIBLE);
        featuresPanel.setVisibility(View.VISIBLE);
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

    private static String statusLine(LoadedLayer l) {
        final StringBuilder sb = new StringBuilder();
        if (l.refreshing)
            return "Loading features\u2026 " + l.progress + " so far";
        if (l.busy)
            return "Loading features\u2026";
        sb.append(l.count).append(" features");
        if (l.lastRefresh > 0)
            sb.append(" · refreshed ").append(age(l.lastRefresh)).append(" ago");
        else
            sb.append(" · never refreshed");
        if (l.spec.refreshMinutes > 0)
            sb.append(" · auto ").append(l.spec.refreshMinutes).append(" min");
        if (!l.refreshing && l.stale)
            sb.append(" · STALE: ").append(l.status);
        else if (!l.refreshing && l.status.startsWith("partial"))
            sb.append(" · ").append(l.status);
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
                        manager.add(Sources.nifsLive(fire));
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
                                    manager.add(spec);
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
                        manager.add(Sources.sarcopTraining(events.get(which)));
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}

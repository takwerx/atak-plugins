---
name: plugin-ui
description: The takwerx pane standard every ATAK plugin follows - main screen (map switch | Settings | Notify, list right under), a Settings page of drop-down rows, ON/OFF switches that show state, the zoom gate on ATAK's live scale bar, Area and Where filters, a list that follows the map and shares one rule with it, the map key and the status line that says what is not shown. The baseline a NEW plugin starts from: load it when writing a new plugin's PLAN and building its pane. Existing plugins keep what they have unless the operator asks to change them.
---

# The takwerx pane

Every takwerx plugin's pane has the same shape and the same controls, so an
operator moving between plugins already knows where everything is. This was
worked out one plugin at a time across Feature Layer, Atmosphere, IPAWS, Evac
Zone and Traffic in September 2026; this file is where it is written down, so
that the next plugin starts here instead of rediscovering it.

**A baseline for new plugins, not a retrofit.** A new plugin starts from this
shape without the operator having to describe it: propose it in the PLAN and let
them change it. Existing plugins keep what they have. Do not bring one in line
with this file unless the operator asks for that change in that plugin. Where this
file is wrong, change it on a tooling branch (CLAUDE.md, "Working in parallel")
the same day, and say why.

Who it is for decides most of it: firefighters and law enforcement on a phone in
a vehicle, often with gloves, often at night. They should see the thing they came
for right away, read the state of every switch without guessing, and never have
to decode words that describe our data plumbing.

---

## 1. The shape

```
Main screen                               Settings page (same pane)
+---------------------------------------+ +---------------------------------------+
| Updated 3 min ago. Zoom in to see     | | [ Back ]  Settings                    |
| alerts on the map.                    | +---------------------------------------+
+---------------------------------------+ | [ Zoom gate: 5 mi or closer     ][v] |
| [Alerts ON] [ Settings ] [Notify ON] | | [ Area: What is in view         ][v] |
| ALERTS                                | | [ Where: California, 3 counties ][v] |
| Red Flag Warning         12 mi        | |   ...body of an open row...           |
| Wind Advisory            30 mi        | | [ Types: 7 of 10                ][v] |
| ...                                   | | [ Map key                       ][v] |
+---------------------------------------+ | [ Updates: every 5 min          ][v] |
                                          | [ Notifications: Extreme, Severe][v] |
                                          +---------------------------------------+
```

IPAWS is the reference for the whole shape (`IpawsPane`, `controls_header.xml`,
`main_layout.xml`, `settings_controls.xml`).

### Main screen: three buttons, then the list

- **A status line, pinned** in the pane root above everything, 13sp. It is how
  old the picture is and what is not being shown (section 6). It sits outside the
  list so it never scrolls away.
- **One row of up to three equal-weight `TakwerxButton`s** (`width=0dp`,
  `weight=1`, 6dp between them), in this order:
  1. the map switch, `<Thing> ON` / `<Thing> OFF`
  2. `Settings`
  3. the one other control used in the field: `Notify ON/OFF` if the plugin
     notifies, otherwise `Refresh` or nothing
- **The list, straight underneath.** The row (and a small-caps heading naming the
  list) is the `ListView`'s header, `list.addHeaderView(header, null, false)`, so
  the whole screen is one scroller. Offset click positions by
  `getHeaderViewsCount()`.
- **Nothing else goes on the main screen.** Anything that is set once and left
  belongs on Settings. The operator's complaint that produced this layout was
  having to scroll through three screens of controls to reach the list.
- **The pane opens on the main screen every time.** Reset the Settings page when
  the pane is shown; a reused pane otherwise reopens wherever it was left.
- A plugin with no list yet (Traffic) still puts the main row first and the status
  under it.

### Settings page: a column of drop-down rows

- **The same pane, by visibility swap**: list `GONE`, settings page `VISIBLE`, and
  back. Not a dialog (Atmosphere's gear dialog is the older way) and not a second
  pane.
- **A pinned top row**: `Back` (a `TakwerxButton`, `minWidth=90dp`) and the title
  `Settings` (17sp, bold). Under it a `ScrollView` with the focus fix:
  `android:focusableInTouchMode="true"` and
  `android:descendantFocusability="beforeDescendants"`, or the scroller jumps to
  a button that is rebuilt.
- **Every setting is a drop-down row** (next section). No loose buttons between
  them (IPAWS's plain Severity and interval buttons are not the pattern).
- **Order**: what is drawn and where first (Zoom gate, Area, Where, Types), then
  Map key, then how often it updates, then Notifications last.
- **Rows start closed.** The row head carries the value, so a closed page reads as
  a summary of every setting; opening one is for changing it.

### The drop-down row

- **Head**: a `TakwerxButton`, `weight=1`, reading `Name: value`:
  `Zoom gate: 5 mi or closer`, `Area: Within 25 mi of My Location`,
  `Where: California, 3 counties`, `Types: 7 of 10`,
  `Notifications: Extreme, Severe`. A row with no value (the map key) is just its
  name.
- **Arrow**: a 44dp `ImageButton`, style `TakwerxIconButton`, `@drawable/ic_expand`,
  rotated 180 degrees when open.
- **Body**: a vertical `LinearLayout`, `gone` until opened. Its controls are
  `TakwerxButton`s and small-caps headings.
- The head and the arrow both open and close it. Open/closed is remembered per
  row in prefs, `<plugin>.fold.<row>`, default closed.
- **A row whose head is a switch** (Atmosphere's layers page: `Wind ON` with its
  controls under the arrow): the head toggles the layer, the arrow opens the
  controls, and **the arrow stays available while the layer is OFF**, so it can be
  set up before it is turned on.

```xml
<LinearLayout android:id="@+id/fold_gate_row" android:orientation="horizontal"
    android:layout_width="match_parent" android:layout_height="wrap_content"
    android:gravity="center_vertical" android:layout_marginTop="6dp">
    <Button android:id="@+id/fold_gate_head" style="@style/TakwerxButton"
        android:layout_width="0dp" android:layout_weight="1"
        android:layout_height="wrap_content" />
    <ImageButton android:id="@+id/fold_gate_chev" style="@style/TakwerxIconButton"
        android:layout_width="44dp" android:layout_weight="0"
        android:layout_marginRight="0dp"
        android:contentDescription="@string/show_settings"
        android:src="@drawable/ic_expand" />
</LinearLayout>
<LinearLayout android:id="@+id/fold_gate_body" android:orientation="vertical"
    android:layout_width="match_parent" android:layout_height="wrap_content"
    android:paddingTop="4dp" android:visibility="gone">
    <!-- the row's controls -->
</LinearLayout>
```

```java
/** One Settings row: a head naming the setting and its value, an arrow, a body. */
private final class Fold {
    final Button head; final ImageButton chevron; final View body;
    final String pref; boolean open;

    Fold(View page, int headId, int chevronId, int bodyId, String pref) {
        head = page.findViewById(headId);
        chevron = page.findViewById(chevronId);
        body = page.findViewById(bodyId);
        this.pref = pref;
        open = prefs.getBoolean(pref, false);
        final View.OnClickListener flip = new View.OnClickListener() {
            @Override public void onClick(View v) {
                open = !open;
                prefs.edit().putBoolean(Fold.this.pref, open).apply();
                show();
            }
        };
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
```

Anonymous classes, not lambdas: lambdas break under release proguard (CLAUDE.md).
`ic_expand` is a white down-chevron PNG; copy it from Traffic or IPAWS.

---

## 2. Switches

- **The label says what IS, never what a tap will do**: `Alerts ON` / `Alerts OFF`.
  Feature Layer's `All ON` (meaning "tap to turn everything on") was read as the
  state while the map was off.
- **Color is the text only**: ON `#3ddc61` (`state_on`), OFF `#ff5b52`
  (`state_off`), from `colors.xml`, on a plain `TakwerxButton` face. Not white for
  OFF, not a colored face, not a green border.
- **One wording**: `<Thing> ON`, no colon. `Notify ON`, not `Notify: ON`.
- **While it is working**: `Loading...` in `#FFC107`, disabled.
- **Several things with one switch each** (Atmosphere's layers, Traffic's incident
  types): a tile per thing, the name on one line and ON/OFF on the next in its
  color. **Everything at once** is two plain buttons, `All on` | `All off`, never
  one button whose label flips.
- **One-of-N choices** (a height, a radius, a unit) are a row of `TakwerxButton`
  presets, up to four across, weighted equally, the chosen one's text green and
  the rest white (Atmosphere `choiceTile`). A short ladder of values is never a
  slider; a slider on a `ViewPager` page is never allowed (it fights the pager).
- **The map switch hides the map, nothing else.** The data, the list, polling and
  notifications carry on, and the switch must act at once: hide the layer, set the
  feature sets invisible off the main thread, then show the layer again, or its
  labels stay on an empty map (IPAWS `AlertOverlay.setVisible`). Never make a
  switch empty the store and wait for a rebuild.

```java
static void setState(Resources res, Button b, String thing, boolean on) {
    b.setText(thing + (on ? " ON" : " OFF"));
    b.setTextColor(res.getColor(on ? R.color.state_on : R.color.state_off));
}
```

---

## 3. Where and how far: zoom gate, Area, Where

Three separate rows on Settings, each answering one question. Keep them apart;
the operator reads them as different things.

### Zoom gate: `Zoom gate: 5 mi or closer`

- **Stored as the distance ATAK's scale bar reads**, in meters
  (`<plugin>.gateBarM`, -1 for Always), and **compared against the live bar**,
  `ScaleBar.meters(mapView)` below. What the button says is what the bar in the
  corner shows, so the operator has one scale to read, not two.
- **Not** meters per pixel (the bar's width in pixels changes with zoom, so the
  quoted value drifts), **not** a nominal 200-pixel conversion, and **not** a
  `FeatureSet` minimum resolution: the renderer compares that against its own
  draw resolution truncated to a tile level, and the map goes empty at a zoom the
  pane says is inside the gate, with no "zoom in" anywhere.
- **The plugin enforces it** on settled map moves: past `gate * 1.02`, hide the
  layer and say so in the status line.
- **Body**: `[Use this zoom]` (stores the bar's current reading) and
  `[5 mi or closer]` opening presets 0.25, 1, 5, 15, 50 in the operator's large
  unit plus `Always`, titled "Draw when the scale bar reads", current value
  checked. Under them one line: `Scale bar now 10 mi - hidden`.
- The gate hides the **map** only. The list still follows Area, and the status
  line says the map is gated.
- A second gate for **labels** (`Labels: 1 mi or closer`) works the same way.
- Traffic and IPAWS do it this way. Evac Zone, Atmosphere and Feature Layer store
  meters per pixel; do not copy their gate.

```java
/** Reads the same number as ATAK's scale bar. Best-effort lookup, cached, with an
 *  arithmetic fallback: the widget tree is internal and could move. */
public final class ScaleBar {
    private static final double FALLBACK_BAR_PIXELS = 200;
    private static ScaleWidget cached;
    private static boolean lookupFailed;

    private static ScaleWidget widget(MapView mv) {
        if (cached != null || lookupFailed || mv == null) return cached;
        try {
            final Object root = mv.getComponentExtra("rootLayoutWidget");
            if (root instanceof AbstractParentWidget)
                cached = find((AbstractParentWidget) root, 0);
        } catch (LinkageError | RuntimeException e) {
            Log.w(TAG, "scale widget lookup failed; using arithmetic instead", e);
        }
        if (cached == null) lookupFailed = true;   // do not re-walk every frame
        return cached;
    }

    private static ScaleWidget find(AbstractParentWidget p, int depth) {
        if (depth > 6) return null;
        for (int i = 0; i < p.getChildCount(); i++) {
            final MapWidget w = p.getChildAt(i);
            if (w instanceof ScaleWidget) return (ScaleWidget) w;
            if (w instanceof AbstractParentWidget) {
                final ScaleWidget f = find((AbstractParentWidget) w, depth + 1);
                if (f != null) return f;
            }
        }
        return null;
    }

    /** The distance the bar spans, in meters: what a gate is compared against. */
    public static double meters(MapView mv) {
        final ScaleWidget w = widget(mv);
        if (w != null) {
            try { final double s = w.getScale(); if (s > 0) return s; }
            catch (RuntimeException e) { /* fall through */ }
        }
        return (mv == null ? 1 : mv.getMapResolution()) * FALLBACK_BAR_PIXELS;
    }

    /** A bar distance the way ATAK writes it, in the operator's units. */
    public static String describe(double barMeters) {
        return SpanUtilities.formatType(Units.type(), barMeters, Span.METER);
    }
}
```

IPAWS `ui/ScaleBar.java` is the full version (it also reads the bar's own text).

### Area: `Area: What is in view`

- Three choices: `Everything`, `What is in view`, `Within 25 mi of My Location`
  (or `of Map Center`).
- **Body**: presets (single choice, current checked), `[Use this extent]` (radius
  from the map center to the north-east corner, and switches to Map Center), and
  `[Measuring from: My Location]`. Presets pin the large unit. A radius slider is
  tolerated in a plain ScrollView; presets are better.
- **No GPS fix**: measure from the map center and say so in the row head,
  `... - no GPS fix, measuring from the map center`. A `GeoPoint` at 0,0 is not a
  fix, whatever `isValid()` says.
- **What is in view clips to the view.** Items off screen are not in view, even if
  their state is (Traffic draws whole states today).
- The globe view has no bounds (`getBounds()` is NaN, or crosses the date line):
  treat it as Everything or refuse with words ("The map has no extent yet"). Too
  wide a view for a radius: clamp and say so ("That view is wider than 50 mi,
  radius set to the maximum").

### Where: `Where: California, 3 counties`

Only for data organized by named places.

- A multi-choice picker applied on OK, a neutral `Clear` button, and `All counties`
  as row 0, exclusive with the rest.
- **Counts on each place** (`Monterey  (12)`) counted from the same collection the
  map draws, and none on a picker of plain choices (which state).
- Two-step pickers (state, then its counties) skip the first step when there is
  only one choice.
- It narrows the map, the list and the map key together.
- **It filters; it never gates.** The list follows the map, and a place pick
  narrows it. A list held hostage to a county pick was the failure that settled
  this (Evac Zone, 2026-09-08).

### Following the map

`onMapMoved` runs on ATAK's GL thread. Touching a View or a map item there is a
native crash with no Java stack. Post, coalesce, and do the work on settle:

```java
private final AtakMapView.OnMapMovedListener moved = new AtakMapView.OnMapMovedListener() {
    @Override public void onMapMoved(AtakMapView v, boolean animate) {
        main.removeCallbacks(settled);       // GL thread: touch nothing else
        main.postDelayed(settled, 400);
    }
};
private final Runnable settled = new Runnable() {
    @Override public void run() { applyGate(); followScope(); }
};
```

- `followScope()` re-filters on the worker, and **fetches again only when the scope
  really moved**: a radius whose point moved more than `max(250 m, 20% of r)`; a
  view that left the last fetched box (padded 20% per side) or shrank below half
  of it.
- Rate-limit fetches, and **retry a move the limit dropped** when the limit ends,
  or the list is stale until the next pan (Feature Layer's `pendingMove`).
- A timer calls `followScope()` too: My Location moves without the map moving.
- Register the listener in the plugin's long-lived manager at `onStart`, remove it
  and the pending callback at `onStop`.

---

## 4. The list

- **The list and the map follow one rule.** The filter, the grouping and the
  counts live on the thing that owns the data, and one pass produces the list, the
  map and the map key together (IPAWS `AlertManager.rebuild`: `kept`, `drawn`,
  `onMap`). The pane never filters; it shows what it is handed. Predicates the
  pane does need are `public static` on the owner, and settings are read from
  prefs each time, never copied per surface. When the two disagree the operator
  taps "Go to" and finds nothing there.
- **Sort nearest first** from the Area's point, with the distance in the row, and
  re-sort after moves. Sort worst-first instead when severity is what the operator
  acts on (IPAWS).
- **A row**: bold name and distance on the first line; status in its map color and
  the place on the second. A tap on the row goes there on the map; a `Details`
  button opens the details. A row holding a Button needs its own
  `setOnClickListener`, or `setItemsCanFocus(true)` swallows the row click.
- **Cap it and say so**: `Nearest 100 of 342 on screen - narrow the search`.
- **Search** (when there is one): `imeOptions="actionSearch|flagNoExtractUi"`, a
  `Clear` button, and a search looks past the screen.
- Group rows show their count when it is more than 1.

---

## 5. The map key

- A `Map key` row on Settings (under the list instead when it is read every time),
  built in code from **the same color function the map draws with**, so the two
  cannot drift.
- Only what is on the map now, in priority order; the row hides when the map is
  empty. 111 event types is not a legend.
- Colors come from the agency that publishes them (NWS's `weather.gov/help-map`
  for weather alerts), never from CAP severity, which puts a Watch above a Warning.

---

## 6. The status line: say what is not shown

The main screen's pinned line names **every** reason something is missing, each in
its own words, and each empty state says what to do about it:

| Reason | Words |
|---|---|
| Map switched off | `Map off.` |
| Past the zoom gate | `Zoom in to see alerts on the map. Shown at 5 mi or closer.` |
| View too wide to fetch | `Zoom in to load: the view is 400 mi across, the most is 311 mi.` |
| List capped | `Nearest 100 of 342 on screen - narrow the search.` |
| No GPS fix | `No GPS fix, measuring from the map center.` |
| Age | `Updated 3 min ago.` |
| Server down | `Server out of reach since 14:02. Showing what it last sent.` |
| Nothing here | `No zones on screen. Pan to them, or turn off On screen only.` |

Two different reasons never share one message: "zoom in" for the gate and "zoom
in" for too many states read as the same thing and are not.

---

## 7. Notifications

Only when the plugin alerts on something.

- `Notify ON/OFF` on the main row; a `Notifications: Extreme, Severe` row on
  Settings for what, where and whether updates count, with
  `[Send a test notification]` going through the real path.
- Its own channel at `IMPORTANCE_HIGH` with vibration: ATAK's `NotificationUtil`
  channel is silent. One notification id, so the newest replaces the last. A tap
  opens the item's details through ATAK's launch intent with an `internalIntent`
  extra.
- The first pass after start, and a change of Where, **record** what is there
  instead of announcing it all.

---

## 8. Words on controls

- **Field operators, not data engineers.** No source, feed, provider or model names
  on a control ("HRRR" becomes `2 mi detail`), no "extent", "cached", "layer
  source", Zulu time or decimals. Provenance goes in details and the manual.
- **Times** are local and relative: `3 min ago`, `Now, Tue 12 pm`,
  `In 6 h, Tue 6 pm`.
- **Distances follow ATAK**: read `rab_rng_units_pref` (a small `Units` helper;
  `Span.ENGLISH = 0`, `METRIC = 1`) and format with `SpanUtilities.formatType`.
  A preset list pins the large unit, or 0.25 mi appears as 1320 ft.
- **Counts on filters** (`Closures (12)`), not on plain choices.
- American spelling. ASCII in the manual (tak.gov's typst drops em dashes).
- When renaming a word, sweep every `status(`, every `setText(` with a literal, and
  all of `strings.xml`, not just the line that was reported.

---

## 9. Controls and dialogs

The base rules from CLAUDE.md's "Plugin UI standard", with the styles in full.

```xml
<style name="TakwerxButton" parent="@style/darkButton">
    <item name="android:paddingLeft">12dp</item>
    <item name="android:paddingRight">12dp</item>
    <item name="android:paddingTop">6dp</item>
    <item name="android:paddingBottom">8dp</item>
    <item name="android:minHeight">44dp</item>
    <item name="android:minWidth">0dp</item>
    <item name="android:textSize">15sp</item>
    <item name="android:singleLine">true</item>
    <item name="android:ellipsize">end</item>
</style>
<style name="TakwerxIconButton">
    <item name="android:layout_width">0dp</item>
    <item name="android:layout_weight">1</item>
    <item name="android:layout_height">44dp</item>
    <item name="android:layout_marginLeft">2dp</item>
    <item name="android:layout_marginRight">2dp</item>
    <item name="android:background">@drawable/btn_gray</item>
    <item name="android:scaleType">centerInside</item>
    <item name="android:padding">9dp</item>
</style>
<style name="TakwerxSectionHeading">
    <item name="android:textSize">10sp</item>
    <item name="android:textAllCaps">true</item>
    <item name="android:textColor">@color/white</item>
    <item name="android:alpha">0.6</item>
    <item name="android:paddingTop">10dp</item>
    <item name="android:paddingBottom">3dp</item>
</style>
```

```xml
<color name="state_on">#3ddc61</color>
<color name="state_off">#ff5b52</color>
```

- `style="@style/TakwerxButton"` on every button, with no per-button `textSize`.
- **Never a Spinner.** Every dialog and toast uses `mapView.getContext()`, never
  the plugin context, or ATAK dies.
- **Single choice** is `setSingleChoiceItems` with the current value checked,
  scrolled to the top (below). **Multi choice** applies on OK, with a neutral
  `Clear` / `All`.
- A picker of things with icons is a tile grid of `TakwerxButton`s (FOBS
  `chooser.xml`, Atmosphere `SpotPage.tiles`), not a full-screen list.
- A `ListView` never goes inside a `ScrollView`; controls above a list go in its
  header view.
- A number table under a new graph stays, closed by default, behind
  `Show hourly table` / `Hide hourly table`, remembered in prefs.
- **Half width is the operating state.** If a plugin needs full width (charts,
  setup), it uses a `DropDownReceiver` with a `Wide` button and narrows itself
  when the result is on the map; the stable `Pane` cannot resize.

```java
private static void fromTop(AlertDialog d) {
    final ListView lv = d.getListView();
    if (lv == null) return;
    lv.post(new Runnable() { @Override public void run() { lv.setSelection(0); } });
}
```

---

## 10. The map layer under it

The pane controls a map layer; these are the rules that layer has to keep for the
switches and gates above to behave. Full machinery:
`../atak-plugins-notes/docs/NOTES-feature-layer-rendering-pattern.md`.

- **Someone else's GIS data is a feature layer**, never `Marker` or
  `DrawingShape`: `FeatureSetDatabase2` + `FeatureLayer3(title, store, true)` on
  `VECTOR_OVERLAYS` + `FeatureDataStoreMapOverlay` through `addOverlay` (check
  the boolean it returns). One feature set per kind, so Overlay Manager lists each.
  ATAK saves drawings as the operator's own and brings them back at every start.
- **Writes are one batched rewrite on a worker** under one modify lock. Never a
  loop of inserts on main; an HTTP callback is on main.
- **Never `dispose()` the store**: the renderer's worker aborts the whole ATAK
  process. Empty the file on attach, or keep track of every set written.
- A colored point is **one composed PNG** as an `IconPointStyle` plus an empty
  `LabelPointStyle`; a second point style in a composite is ignored. Bump a style
  version whenever the look changes, because styles are stored with the features.
- Flatten nested geometry collections before inserting them.
- A tap: `FeatureDataStoreDeepMapItemQuery` that fetches attributes by id (the
  hit-test drops them), dedupes hits, and sets the radial menu.

---

## 11. Lifetime

- A manager created in `onStart` owns the data, the map listener, the timer and
  the worker, and lives as long as the plugin. Never inside a `Tool`; ATAK ends
  tools whenever something else opens.
- Nothing slow on the main thread at start or stop: no directory sweeps, no store
  work, no network. ATAK loads and unloads plugins on main.
- `onStop` removes the map listener and any pending callback, and closes the panes.

---

## 12. Before installing a new plugin's pane

```bash
cd plugins/<Name>/app/src/main
grep -rn "<Spinner\|<Button" res/layout | grep -v TakwerxButton   # must print nothing
# each hit below needs a look: a gate compared here is on the wrong scale
grep -rn "getMapResolution\|minResolution\|FALLBACK_BAR_PIXELS" java | grep -iv scalebar
grep -rn '"All ON"\|: ON"\|: OFF"' java res                        # action labels, colons
```

Then look at the screen and ask:

1. Is the list right under the main row, with nothing else on the main screen?
2. Does every Settings row read `Name: value`, closed?
3. Does every switch show its state in green or red text?
4. Is the zoom gate the scale bar's own number, and does the status line say when
   it is hiding things?
5. Do the list, the map and the map key show the same things?
6. Could a firefighter read every word on it without asking what it means?

---

## Reference implementations

| Piece | Best example |
|---|---|
| Whole shape: main row, list, Settings swap | IPAWS `IpawsPane`, `controls_header.xml`, `main_layout.xml` |
| Drop-down row | Traffic `IncidentSettings.Fold`; IPAWS `IpawsPane.Fold` |
| Switch row with its controls under an arrow | Atmosphere `page_layers.xml`, `updateLayerControls()` |
| Switch colors | Traffic `colors.xml`, `setState()` |
| Choice tiles | Atmosphere `choiceTile()`; Traffic `incident_type_tile.xml` |
| Scale bar and zoom gate | IPAWS `ScaleBar`, `AlertManager.applyVisibility`; Traffic `IncidentFeed.applyGate` |
| Area and following the map | IPAWS `AlertManager.followScope`; Traffic `IncidentFeed` |
| Where, with counts | Evac Zone `pickCounties()` |
| One rule for list and map | IPAWS `AlertManager.rebuild`; Atmosphere `SpotOverlay.newestPerIncident` |
| List following the map, nearest first | Evac Zone `renderZones()` |
| Map key | IPAWS `refreshKey()` / `legendLine()` |
| Status line | IPAWS `AlertManager.statusLine()`; Traffic `incidentsLine()` |
| Notifications | IPAWS `postNotification()`, `announce()` |
| Feature layer | IPAWS `AlertOverlay`; Atmosphere `AtmosphereFeatures` |
| Wide pane | IAP `IapDropDown`; Atmosphere `AtmosphereDropDown` |

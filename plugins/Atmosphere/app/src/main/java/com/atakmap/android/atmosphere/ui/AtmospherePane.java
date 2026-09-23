package com.atakmap.android.atmosphere.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PointF;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.atakmap.android.atmosphere.astro.Astro;
import com.atakmap.android.atmosphere.compat.MapCompat;
import com.atakmap.android.atmosphere.data.Favorites;
import com.atakmap.android.atmosphere.data.ParamSelection;
import com.atakmap.android.atmosphere.data.WeatherClient;
import com.atakmap.android.atmosphere.model.Reading;
import com.atakmap.android.atmosphere.model.SeriesEntry;
import com.atakmap.android.atmosphere.model.Snapshot;
import com.atakmap.android.atmosphere.net.EgressPolicy;
import com.atakmap.android.atmosphere.overlay.RadarOverlay;
import com.atakmap.android.atmosphere.overlay.WindScaleView;
import com.atakmap.android.atmosphere.overlay.WindOverlay;
import com.atakmap.android.atmosphere.wind.NomadsWind;
import com.atakmap.android.atmosphere.plugin.R;
import com.atakmap.android.atmosphere.source.SourceRegistry;
import com.atakmap.android.atmosphere.source.WxParam;
import com.atakmap.android.atmosphere.source.WxSourceDef;
import com.atakmap.android.atmosphere.units.Quantity;
import com.atakmap.android.atmosphere.units.UnitSystem;
import com.atakmap.android.atmosphere.units.Units;
import com.atakmap.android.maps.MapEvent;
import com.atakmap.android.maps.MapEventDispatcher;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.coremap.maps.coords.GeoPointMetaData;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * The plugin pane: pick a source, pick a place, read the weather.
 *
 * <p>Everything shown here comes from a source definition — the labels, the units, which
 * variables exist. Nothing in this class knows the name of a weather provider.
 *
 * <p>Anonymous listeners rather than lambdas: this code ships in release builds, where
 * the SDK documents lambdas breaking under proguard.
 */
public final class AtmospherePane {

    private static final String PREF_SOURCE = "weather.source.selected";
    private static final String PREF_UNITS = "weather.units";
    /** Which point the readout is for: a {@link PointMode} name. */
    private static final String PREF_MODE = "weather.position.mode";
    /** The picked point as "lat,lon", kept so a picked point survives a reopen. */
    private static final String PREF_PICKED = "weather.position.picked";
    /** The name of the favorite being read, when one is; absent for map center or self. */
    private static final String PREF_FAVORITE = "weather.position.favorite";
    private static final String PREF_TREND = "weather.trend.kind";
    private static final String PREF_HOURS_TABLE = "weather.hours.table";

    private final View root;
    private final Context pluginContext;
    private final SourceRegistry registry;
    private final EgressPolicy egress;
    private final WeatherClient client;

    private final ImageButton modeSelf;
    private final ImageButton modeCenter;
    private final ImageButton modePick;
    private final ImageButton favoritesButton;
    private final Button unitsButton;
    private final ImageButton wideButton;
    private final ImageButton pageButton;
    private final ViewPager pager;
    private final LinearLayout pageDots;
    private final View[] pages;
    private final ImageButton refreshButton;
    private final ImageButton settingsButton;
    private final TextView positionText;
    private final TextView statusText;
    private final TextView currentHeading;
    private final LinearLayout currentContainer;
    private final TextView seriesHeading;
    private final HorizontalScrollView trendScroll;
    private final LinearLayout trendHost;
    private final LinearLayout trendChips;
    private final TrendStripView trend;
    private Kind trendKind = Kind.TEMP;
    private final Button hoursTableButton;
    private final View hoursTable;
    private final LinearLayout hoursLegend;
    private final LinearLayout hoursContainer;
    private boolean hoursTableOpen;
    private final TextView daysHeading;
    private final View daysStrip;
    private final LinearLayout daysLegend;
    private final LinearLayout daysContainer;
    private final TextView attributionText;
    private final Button radarToggle;
    private final View scrubber;
    private final TextView scrubberLabel;
    private final LinearLayout scrubberDays;
    private final LinearLayout scrubberHours;
    /** The times the picker offers, and which of them is on the map. */
    private final List<Long> whenTimes = new ArrayList<>();
    private int whenIndex = -1;
    /** The index that means "right now": hour 0 for wind, the last frame for radar. */
    private int whenLive = -1;
    /** Start of the day whose hours are laid out, so a repaint need not rebuild. */
    private long whenDay = Long.MIN_VALUE;
    private RadarOverlay radar;
    private List<String> radarFrames = new ArrayList<>();
    private final Button windToggle;
    private final View windScaleHost;
    private final WindScaleView windScale;
    private final LinearLayout windUnitRow;
    private final View windLevelBlock;
    private final TextView windLevelLabel;
    private final LinearLayout windLevelRows;
    private WindOverlay wind;
    private int windHours;

    private final List<WxSourceDef> sources;

    private WxSourceDef selected;
    private UnitSystem units;
    /** Where the readout is for. */
    public enum PointMode {
        SELF, CENTER, PICKED, FAVORITE;

        static PointMode fromName(String n) {
            if (n != null)
                for (PointMode m : values())
                    if (m.name().equals(n)) return m;
            return CENTER;
        }
    }

    private PointMode mode = PointMode.CENTER;
    /** The point picked on the map, when the mode is PICKED. */
    private GeoPoint pickedPoint;
    /** The saved place being read, when the mode is FAVORITE. */
    private Favorites.Place favorite;
    private final Favorites favorites;
    /** True between the Pick tap and the map tap; the map listeners are pushed. */
    private boolean pickArmed;
    private MapEventDispatcher.MapEventDispatchListener pickListener;
    /** The drop-down hosting this pane, for the wide toggle; set after construction. */
    private AtmosphereDropDown host;
    private Snapshot snapshot;

    public AtmospherePane(View root, Context pluginContext, SourceRegistry registry,
            EgressPolicy egress, WeatherClient client) {

        this.root = root;
        this.pluginContext = pluginContext;
        this.registry = registry;
        this.egress = egress;
        this.client = client;
        this.sources = registry.sources();

        // The pages are their own layouts, inflated here and handed to the pager;
        // every id below is looked up across the root and the pages.
        final LayoutInflater inflater = LayoutInflater.from(pluginContext);
        pages = new View[] {
                inflater.inflate(R.layout.page_forecast, null),
                inflater.inflate(R.layout.page_layers, null)
        };
        pager = root.findViewById(R.id.pager);
        pageDots = root.findViewById(R.id.page_dots);
        pageButton = root.findViewById(R.id.page_button);
        wirePager();

        modeSelf = find(R.id.mode_self);
        modeCenter = find(R.id.mode_center);
        modePick = find(R.id.mode_pick);
        favoritesButton = find(R.id.favorites_button);
        unitsButton = find(R.id.units_button);
        wideButton = find(R.id.wide_button);
        refreshButton = find(R.id.refresh_button);
        settingsButton = find(R.id.settings_button);
        positionText = find(R.id.position_text);
        statusText = find(R.id.status_text);
        currentHeading = find(R.id.current_heading);
        currentContainer = find(R.id.current_container);
        seriesHeading = find(R.id.series_heading);
        trendScroll = find(R.id.trend_scroll);
        trendHost = find(R.id.trend_host);
        trendChips = find(R.id.trend_chips);
        trend = new TrendStripView(pluginContext);
        trendHost.addView(trend);
        hoursTableButton = find(R.id.hours_table_button);
        hoursTable = find(R.id.hours_table);
        hoursLegend = find(R.id.hours_legend);
        hoursContainer = find(R.id.hours_container);
        daysHeading = find(R.id.days_heading);
        daysStrip = find(R.id.days_strip);
        daysLegend = find(R.id.days_legend);
        daysContainer = find(R.id.days_container);
        attributionText = find(R.id.attribution_text);
        radarToggle = find(R.id.radar_toggle);
        windToggle = find(R.id.wind_toggle);
        windScaleHost = find(R.id.wind_scale_host);
        windScale = new WindScaleView(pluginContext);
        ((LinearLayout) windScaleHost).addView(windScale);
        windUnitRow = new LinearLayout(pluginContext);
        windUnitRow.setOrientation(LinearLayout.HORIZONTAL);
        ((LinearLayout) windScaleHost).addView(windUnitRow);
        buildWindUnitRow();
        windLevelBlock = find(R.id.wind_level_block);
        windLevelLabel = find(R.id.wind_level_label);
        windLevelRows = find(R.id.wind_level_rows);
        buildWindLevelRows();
        scrubber = find(R.id.scrubber);
        scrubberLabel = find(R.id.scrubber_label);
        scrubberDays = find(R.id.scrubber_days);
        scrubberHours = find(R.id.scrubber_hours);
        wireLayers();

        final SharedPreferences prefs = MapCompat.prefs();
        units = UnitSystem.fromName(prefs == null ? null
                : prefs.getString(PREF_UNITS, null), UnitSystem.METRIC);
        favorites = new Favorites(MapCompat.atakContext());
        favorite = favorites.byName(prefs == null ? null : prefs.getString(PREF_FAVORITE, null));
        pickedPoint = parsePoint(prefs == null ? null : prefs.getString(PREF_PICKED, null));
        mode = PointMode.fromName(prefs == null ? null : prefs.getString(PREF_MODE, null));
        // A mode whose point is gone (favorite removed, nothing picked) falls back.
        if ((mode == PointMode.FAVORITE && favorite == null)
                || (mode == PointMode.PICKED && pickedPoint == null))
            mode = PointMode.CENTER;
        trendKind = Kind.fromName(prefs == null ? null : prefs.getString(PREF_TREND, null));
        hoursTableOpen = prefs != null && prefs.getBoolean(PREF_HOURS_TABLE, false);
        updateHoursTableButton();
        hoursTableButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hoursTableOpen = !hoursTableOpen;
                final SharedPreferences p = MapCompat.prefs();
                if (p != null)
                    p.edit().putBoolean(PREF_HOURS_TABLE, hoursTableOpen).apply();
                updateHoursTableButton();
                render();
            }
        });

        loadSelectedSource(prefs);
        wireButtons();
        updateUnitsButton();
        updateModeIcons();
        showSourceProblemsIfAny();
    }

    /** Called every time the pane is shown, so a stale pane never lingers. */
    public void onShown() {
        refresh(false);
        // The frame list ages while the pane is closed; a stale one is re-read.
        if (radar != null && radar.isOn())
            radar.refreshFrames(false);
        updateLayerControls();
    }

    /** A view by id, in the root or on any page. */
    private <T extends View> T find(int id) {
        T v = root.findViewById(id);
        if (v != null)
            return v;
        for (View page : pages) {
            v = page.findViewById(id);
            if (v != null)
                return v;
        }
        throw new IllegalStateException("no view with id " + id);
    }

    /**
     * Pages in ATAK's own ViewPager (the SDK helloworld TabViewDropDown pattern):
     * the forecast first, the layers a swipe or the arrow away. Plain views, not
     * fragments: a fragment needs the host activity's FragmentManager and the
     * plugin class loader, which is a release-proguard risk for nothing.
     */
    private void wirePager() {
        pager.setAdapter(new PagerAdapter() {
            @Override
            public int getCount() {
                return pages.length;
            }

            @Override
            public boolean isViewFromObject(View view, Object object) {
                return view == object;
            }

            @Override
            public Object instantiateItem(ViewGroup container, int position) {
                container.addView(pages[position]);
                return pages[position];
            }

            @Override
            public void destroyItem(ViewGroup container, int position, Object object) {
                container.removeView((View) object);
            }
        });
        pager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                updatePageDots(position);
            }
        });
        for (int i = 0; i < pages.length; i++) {
            final View dot = new View(pluginContext);
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(9), dp(9));
            lp.setMargins(dp(4), 0, dp(4), 0);
            dot.setLayoutParams(lp);
            final android.graphics.drawable.GradientDrawable bg =
                    new android.graphics.drawable.GradientDrawable();
            bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            bg.setColor(Color.WHITE);
            dot.setBackground(bg);
            final int page = i;
            dot.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    pager.setCurrentItem(page, true);
                }
            });
            pageDots.addView(dot);
        }
        updatePageDots(0);
        pageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pager.setCurrentItem((pager.getCurrentItem() + 1) % pages.length, true);
            }
        });
    }

    private void updatePageDots(int current) {
        for (int i = 0; i < pageDots.getChildCount(); i++)
            pageDots.getChildAt(i).setAlpha(i == current ? 1f : 0.35f);
        // The arrow points at the page it will go to: right until the last page,
        // then mirrored, since from there it goes back to the first.
        pageButton.setScaleX(current == pages.length - 1 ? -1f : 1f);
    }

    /** The drop-down hosting this pane; the wide toggle needs it. */
    public void setHost(AtmosphereDropDown host) {
        this.host = host;
    }

    /** The radar overlay, owned by the plugin; the pane drives and reads it. */
    public void setRadar(RadarOverlay overlay) {
        radar = overlay;
        if (radar == null)
            return;
        radar.setListener(new RadarOverlay.Listener() {
            @Override
            public void onFrames(List<String> times, int shown) {
                radarFrames = times;
                if (radar.isOn())
                    setWhenTimes(radarTimes(), shown, Math.max(0, times.size() - 1));
                updateLayerControls();
            }

            @Override
            public void onFrameShown(int index, String time) {
                if (!radar.isOn())
                    return;
                showWhen(index);
                scrubberLabel.setText(frameLabel(index, time));
            }

            @Override
            public void onStatus(String status) {
                if (!radar.isOn())
                    return;
                if (!status.isEmpty())
                    scrubberLabel.setText(status);
                else if (radar != null) {
                    final int i = radar.frameIndex();
                    scrubberLabel.setText(frameLabel(i,
                            i < 0 || i >= radarFrames.size() ? null : radarFrames.get(i)));
                }
            }
        });
        updateLayerControls();
    }

    /** The wind overlay, owned by the plugin; shares the scrubber with the radar. */
    public void setWind(WindOverlay overlay) {
        wind = overlay;
        if (wind == null)
            return;
        wind.setListener(new WindOverlay.Listener() {
            @Override
            public void onFrames(List<String> labels, int shown) {
                windHours = Math.max(0, labels.size() - 1);
                if (wind.isOn())
                    setWhenTimes(windTimes(), shown, 0);
                updateLayerControls();
            }

            @Override
            public void onFrameShown(int index, long validTime) {
                if (!wind.isOn())
                    return;
                showWhen(index);
                scrubberLabel.setText(windLabel(index, validTime));
                // The model can change with the height, so the level line follows.
                updateWindLevel();
            }

            @Override
            public void onStatus(String status) {
                if (!wind.isOn())
                    return;
                if (!status.isEmpty())
                    scrubberLabel.setText(status);
                else
                    scrubberLabel.setText(
                            windLabel(wind.hourIndex(), wind.validTime(wind.hourIndex())));
            }
        });
        updateLayerControls();
    }

    /**
     * When the wind on screen is for, led by how far that is from now, which is what
     * somebody scrubbing the bar is actually asking. "+0 h" is a modeller's way of
     * counting and the model's name is not something a crew can act on.
     */
    private String windLabel(int hour, long validTime) {
        if (validTime <= 0)
            return "No forecast yet";
        // Counted in steps off the bar rather than off the clock. Step 0 is the hour
        // that contains now, so at 5:46 it is the 5 pm forecast: measuring that
        // against the clock reads "44 min ago", which is stale weather to anyone
        // looking at it, and it is the wind blowing right now.
        final String when = hour <= 0 ? "Now"
                : "In " + hour + " h";
        return when + ", " + clock(validTime);
    }

    /** "now", "in 6 h", "2 h ago" \u2014 a forecast hour in the operator's terms. */
    private static String relativeTime(long when) {
        final long minutes = Math.round((when - System.currentTimeMillis()) / 60000.0);
        if (Math.abs(minutes) <= 30)
            return "now";
        final long size = Math.abs(minutes);
        final String gap = size < 60 ? size + " min" : Math.round(size / 60.0) + " h";
        return minutes > 0 ? "in " + gap : gap + " ago";
    }

    /** "Tue 6 pm": the day and hour, with a lower-case meridiem. */
    private static String clock(long when) {
        return new SimpleDateFormat("EEE h a", Locale.US).format(new Date(when))
                .replace("AM", "am").replace("PM", "pm");
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ---- layers ------------------------------------------------------------------

    private void wireLayers() {
        windToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (wind == null)
                    return;
                if (wind.isOn()) {
                    wind.setOn(false);
                    updateLayerControls();
                } else if (egress.isLayerEnabled(WindOverlay.LAYER_ID)) {
                    turnWindOn();
                } else {
                    askToAllowWind();
                }
            }
        });
        radarToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (radar == null)
                    return;
                if (radar.isOn()) {
                    radar.setOn(false);
                    updateLayerControls();
                } else if (egress.isLayerEnabled(RadarOverlay.LAYER_ID)) {
                    turnRadarOn();
                } else {
                    askToAllowRadar();
                }
            }
        });
    }

    // ---- when: live, then a day, then an hour ----------------------------------------

    /** The wind's forecast hours as wall-clock times, index for index. */
    private List<Long> windTimes() {
        final List<Long> out = new ArrayList<>();
        if (wind != null)
            for (int i = 0; i <= windHours; i++)
                out.add(wind.validTime(i));
        return out;
    }

    /** The radar's frame stamps as wall-clock times, index for index. */
    private List<Long> radarTimes() {
        final List<Long> out = new ArrayList<>();
        for (String t : radarFrames)
            out.add(com.atakmap.android.atmosphere.data.IsoTime.parse(t));
        return out;
    }

    /**
     * Hand the picker a fresh set of times.
     *
     * @param live the index that means now: hour 0 for a forecast that runs forward,
     *             the last frame for a radar loop that runs up to the present.
     */
    private void setWhenTimes(List<Long> times, int shown, int live) {
        whenTimes.clear();
        whenTimes.addAll(times);
        whenLive = live;
        whenIndex = shown >= 0 && shown < whenTimes.size() ? shown : live;
        whenDay = Long.MIN_VALUE;
        buildWhenPicker();
    }

    /** The map moved to another time; follow it without rebuilding unless the day changed. */
    private void showWhen(int index) {
        if (index < 0 || index >= whenTimes.size())
            return;
        whenIndex = index;
        if (startOfDay(whenTimes.get(index)) != whenDay)
            buildWhenPicker();
        else
            paintWhenPicker();
    }

    /**
     * Live, the days, and the hours of the day being shown, as buttons.
     *
     * <p>This was a SeekBar and a SeekBar was wrong here twice over: on a pager page
     * it will not drag sideways, and once it is made to, a finger meaning to scroll
     * the page drags the value instead. Buttons also say what the choices are without
     * being touched, which is the whole of "pick a day and an hour".
     */
    private void buildWhenPicker() {
        scrubberDays.removeAllViews();
        scrubberHours.removeAllViews();
        if (whenTimes.isEmpty())
            return;
        if (whenIndex < 0 || whenIndex >= whenTimes.size())
            whenIndex = Math.max(0, Math.min(whenLive, whenTimes.size() - 1));
        whenDay = startOfDay(whenTimes.get(whenIndex));

        // Live, then one button per day the times cover. A single day is not worth
        // a button of its own: the hours below it are already that day.
        scrubberDays.addView(whenButton("Live", null, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickWhen(whenLive);
            }
        }, whenIndex == whenLive));
        final List<Long> days = new ArrayList<>();
        for (Long t : whenTimes) {
            final long d = startOfDay(t);
            if (!days.contains(d))
                days.add(d);
        }
        if (days.size() > 1)
            for (final Long day : days)
                scrubberDays.addView(whenButton(dayLabel(day), day, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        // A day is picked by going to its first hour, so green always
                        // means "this is what is on the map" and never "this is open".
                        for (int i = 0; i < whenTimes.size(); i++)
                            if (startOfDay(whenTimes.get(i)) == day) {
                                pickWhen(i);
                                return;
                            }
                    }
                }, day == whenDay));

        LinearLayout row = null;
        int inRow = 0;
        for (int i = 0; i < whenTimes.size(); i++) {
            if (startOfDay(whenTimes.get(i)) != whenDay)
                continue;
            if (inRow % 4 == 0) {
                row = new LinearLayout(pluginContext);
                row.setOrientation(LinearLayout.HORIZONTAL);
                scrubberHours.addView(row, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
            }
            final int index = i;
            final Button b = whenButton(hourLabel(whenTimes.get(i)), index,
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            pickWhen(index);
                        }
                    }, i == whenIndex);
            row.addView(b);
            inRow++;
        }
        // Pad the last row so four across stay four across.
        if (row != null)
            for (int i = inRow % 4; i > 0 && i < 4; i++) {
                final View filler = new View(pluginContext);
                final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, 1, 1f);
                lp.rightMargin = dp(4);
                row.addView(filler, lp);
            }
    }

    /** Repaint which button is green without rebuilding the rows. */
    private void paintWhenPicker() {
        paintGreen(scrubberDays);
        for (int i = 0; i < scrubberHours.getChildCount(); i++) {
            final View row = scrubberHours.getChildAt(i);
            if (row instanceof LinearLayout)
                paintGreen((LinearLayout) row);
        }
    }

    private void paintGreen(LinearLayout host) {
        final long day = whenIndex >= 0 && whenIndex < whenTimes.size()
                ? startOfDay(whenTimes.get(whenIndex)) : Long.MIN_VALUE;
        for (int i = 0; i < host.getChildCount(); i++) {
            final View v = host.getChildAt(i);
            if (!(v instanceof Button))
                continue;
            final Object tag = v.getTag(R.id.scrubber);
            final boolean on;
            if (tag instanceof Integer)
                on = (Integer) tag == whenIndex;
            else if (tag instanceof Long)
                on = ((Long) tag).longValue() == day;
            else
                on = whenIndex == whenLive;
            ((Button) v).setTextColor(on
                    ? pluginContext.getResources().getColor(R.color.state_on) : Color.WHITE);
        }
    }

    /** One cell of the picker: a quarter-width TakwerxButton, green when it is on. */
    private Button whenButton(String text, Object tag, View.OnClickListener onClick,
            boolean on) {
        final Button b = (Button) LayoutInflater.from(pluginContext)
                .inflate(R.layout.trend_chip, scrubberHours, false);
        b.setText(text);
        // Keyed so a repaint can tell an hour (Integer) from a day (Long) from Live.
        b.setTag(R.id.scrubber, tag);
        b.setTextSize(13);
        b.setOnClickListener(onClick);
        b.setTextColor(on
                ? pluginContext.getResources().getColor(R.color.state_on) : Color.WHITE);
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = dp(4);
        lp.topMargin = dp(4);
        b.setLayoutParams(lp);
        return b;
    }

    /** Send a pick to whichever layer owns the strip. */
    private void pickWhen(int index) {
        if (index < 0 || index >= whenTimes.size())
            return;
        // The line answers the tap, not the fetch. A grid takes a moment to arrive and
        // the label is only written when it does, so the heading sat on the old time
        // while the buttons had already moved, which reads as a control that did not
        // take (XCover, 2026-09-22).
        if (wind != null && wind.isOn()) {
            wind.setHourIndex(index);
            scrubberLabel.setText(windLabel(index, whenTimes.get(index)));
        } else if (radar != null && radar.isOn()) {
            radar.setFrameIndex(index);
            scrubberLabel.setText(frameLabel(index,
                    index < radarFrames.size() ? radarFrames.get(index) : null));
        }
        showWhen(index);
    }

    /** "Today", "Tomorrow", else the weekday. */
    private static String dayLabel(long day) {
        final long from = Math.round(
                (day - startOfDay(System.currentTimeMillis())) / 86_400_000.0);
        if (from == 0)
            return "Today";
        if (from == 1)
            return "Tomorrow";
        return new SimpleDateFormat("EEE", Locale.US).format(new Date(day));
    }

    /** "6 pm" on the hour, "5:12 pm" off it, because radar frames are not hourly. */
    private static String hourLabel(long when) {
        final Calendar c = Calendar.getInstance();
        c.setTimeInMillis(when);
        final String pattern = c.get(Calendar.MINUTE) == 0 ? "h a" : "h:mm a";
        return new SimpleDateFormat(pattern, Locale.US).format(new Date(when))
                .replace("AM", "am").replace("PM", "pm");
    }

    private static long startOfDay(long when) {
        final Calendar c = Calendar.getInstance();
        c.setTimeInMillis(when);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** The egress gate: the host, by name, once. */
    private void askToAllowRadar() {
        final Context ctx = MapCompat.atakContext();
        if (ctx == null)
            return;
        new AlertDialog.Builder(ctx)
                .setTitle(pluginContext.getString(R.string.radar_allow_title))
                .setMessage(pluginContext.getString(R.string.radar_allow_text))
                .setPositiveButton(pluginContext.getString(R.string.allow),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                egress.setLayerEnabled(RadarOverlay.LAYER_ID, true);
                                turnRadarOn();
                            }
                        })
                .setNegativeButton(pluginContext.getString(R.string.close), null)
                .show();
    }

    /** One time-enabled layer at a time (WxReport's rule): the scrubber is one strip. */
    private void turnRadarOn() {
        if (wind != null && wind.isOn())
            wind.setOn(false);
        if (radar != null) {
            radar.setOn(true);
            setWhenTimes(radarTimes(), radar.frameIndex(),
                    Math.max(0, radarFrames.size() - 1));
        }
        updateLayerControls();
    }

    private void turnWindOn() {
        if (radar != null && radar.isOn())
            radar.setOn(false);
        if (wind != null) {
            wind.setOn(true);
            setWhenTimes(windTimes(), wind.hourIndex(), 0);
        }
        updateLayerControls();
    }

    private void askToAllowWind() {
        final Context ctx = MapCompat.atakContext();
        if (ctx == null)
            return;
        new AlertDialog.Builder(ctx)
                .setTitle(pluginContext.getString(R.string.wind_allow_title))
                .setMessage(pluginContext.getString(R.string.wind_allow_text))
                .setPositiveButton(pluginContext.getString(R.string.allow),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                egress.setLayerEnabled(WindOverlay.LAYER_ID, true);
                                turnWindOn();
                            }
                        })
                .setNegativeButton(pluginContext.getString(R.string.close), null)
                .show();
    }

    private void updateLayerControls() {
        final boolean radarOn = radar != null && radar.isOn();
        final boolean windOn = wind != null && wind.isOn();
        radarToggle.setText(radarOn ? R.string.radar_on : R.string.radar_off);
        radarToggle.setTextColor(pluginContext.getResources().getColor(
                radarOn ? R.color.state_on : R.color.state_off));
        windToggle.setText(windOn ? R.string.wind_on : R.string.wind_off);
        windToggle.setTextColor(pluginContext.getResources().getColor(
                windOn ? R.color.state_on : R.color.state_off));
        // The legend explains what is on the map, so it appears with the layer.
        windScaleHost.setVisibility(windOn ? View.VISIBLE : View.GONE);
        windLevelBlock.setVisibility(windOn ? View.VISIBLE : View.GONE);
        if (windOn) {
            updateWindScale();
            // The row is built in the constructor, before the stored unit has been
            // read, so the first paint of it happens here.
            updateWindUnitRow();
            updateWindLevel();
        }
        scrubber.setVisibility(radarOn || windOn ? View.VISIBLE : View.GONE);
    }

    /**
     * Wind speed by the unit a crew names it in, picked where they are looking at it
     * (operator, 2026-09-22: "on wind can we have kt and mph as option on the scale
     * on the layers"). Each button sets the whole unit system rather than a private
     * wind unit, so there is one answer to "what units am I in" and the icon row's
     * button never disagrees: knots is the aviation system, miles per hour imperial,
     * kilometers per hour metric. The third is there because the system has three; a
     * row where the live setting matched no button would be worse than a spare.
     */
    private void buildWindUnitRow() {
        final UnitSystem[] order = { UnitSystem.IMPERIAL, UnitSystem.AVIATION, UnitSystem.METRIC };
        for (final UnitSystem system : order) {
            final Button b = (Button) LayoutInflater.from(pluginContext)
                    .inflate(R.layout.trend_chip, windUnitRow, false);
            b.setText(Units.displayUnit(Quantity.SPEED, system));
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    applyUnits(system);
                }
            });
            b.setTag(system);
            windUnitRow.addView(b);
        }
        updateWindUnitRow();
    }

    private void updateWindUnitRow() {
        for (int i = 0; i < windUnitRow.getChildCount(); i++) {
            final View child = windUnitRow.getChildAt(i);
            if (!(child instanceof Button))
                continue;
            ((Button) child).setTextColor(child.getTag() == units
                    ? pluginContext.getResources().getColor(R.color.state_on) : Color.WHITE);
        }
    }

    /** Every route to a unit change goes through here, so nothing gets left behind. */
    private void applyUnits(UnitSystem system) {
        units = system;
        final SharedPreferences p = MapCompat.prefs();
        if (p != null)
            p.edit().putString(PREF_UNITS, units.name()).apply();
        updateUnitsButton();
        updateWindScale();
        updateWindUnitRow();
        relabelWindLevelRows();
        updateWindLevel();
        // A unit change is a display change: re-render, never re-fetch.
        render();
    }

    /**
     * Paint the height that is being drawn, and say how fine the wind is there.
     * Ten meters up to the jet, a ladder rather than a scale because that is what
     * the models carry: two heights above the ground and the standard pressure
     * surfaces above them.
     */
    private void updateWindLevel() {
        if (wind == null)
            return;
        final int picked = wind.levelIndex();
        for (int i = 0; i < windLevelRows.getChildCount(); i++) {
            final View row = windLevelRows.getChildAt(i);
            if (!(row instanceof LinearLayout))
                continue;
            final LinearLayout cells = (LinearLayout) row;
            for (int j = 0; j < cells.getChildCount(); j++) {
                final View cell = cells.getChildAt(j);
                if (!(cell instanceof Button) || !(cell.getTag() instanceof Integer))
                    continue;
                ((Button) cell).setTextColor((Integer) cell.getTag() == picked
                        ? pluginContext.getResources().getColor(R.color.state_on)
                        : Color.WHITE);
            }
        }
        windLevelLabel.setText(wind.levelLabel(units == UnitSystem.METRIC,
                units == UnitSystem.AVIATION));
    }

    /**
     * One button per height the models carry (operator, 2026-09-22: "lets just do
     * some presets on the wind height"). It began as a slider and the slider was the
     * wrong control here: it lives on a page of a ViewPager inside a scroller, so a
     * finger meaning to scroll the page moved the wind to the jet stream instead, and
     * eight discrete levels were never a scale to begin with. Buttons say what the
     * choices are without being touched, which a slider cannot.
     *
     * <p>Four to a row, weighted so the two rows line up, and the labels are the
     * heights themselves rather than the pressure surfaces they come from.
     */
    private void buildWindLevelRows() {
        final NomadsWind.Level[] all = NomadsWind.Level.values();
        final int perRow = 4;
        LinearLayout row = null;
        for (int i = 0; i < all.length; i++) {
            if (i % perRow == 0) {
                row = new LinearLayout(pluginContext);
                row.setOrientation(LinearLayout.HORIZONTAL);
                windLevelRows.addView(row, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
            }
            final int index = i;
            final Button b = (Button) LayoutInflater.from(pluginContext)
                    .inflate(R.layout.trend_chip, row, false);
            b.setText(all[i].shortLabel(units == UnitSystem.METRIC));
            b.setTextSize(13);
            b.setTag(index);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (wind == null)
                        return;
                    wind.setLevelIndex(index);
                    updateWindLevel();
                }
            });
            // Even columns: each cell takes a quarter of the row whatever its text.
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = dp(4);
            lp.topMargin = dp(4);
            b.setLayoutParams(lp);
            row.addView(b);
        }
    }

    /** The preset labels carry a unit, so a unit change relabels them. */
    private void relabelWindLevelRows() {
        final NomadsWind.Level[] all = NomadsWind.Level.values();
        for (int i = 0; i < windLevelRows.getChildCount(); i++) {
            final View r = windLevelRows.getChildAt(i);
            if (!(r instanceof LinearLayout))
                continue;
            final LinearLayout cells = (LinearLayout) r;
            for (int j = 0; j < cells.getChildCount(); j++) {
                final View cell = cells.getChildAt(j);
                if (!(cell instanceof Button) || !(cell.getTag() instanceof Integer))
                    continue;
                final int index = (Integer) cell.getTag();
                if (index >= 0 && index < all.length)
                    ((Button) cell).setText(all[index].shortLabel(units == UnitSystem.METRIC));
            }
        }
    }

    /**
     * A distance a crew can picture, for the position-rounding choices. Decimal places
     * are a way of storing a number, not a thing anybody can stand in.
     */
    private String roughly(int meters) {
        if (units == UnitSystem.METRIC)
            return meters >= 1000 ? Math.round(meters / 1000.0) + " km" : meters + " m";
        final double feet = meters / 0.3048;
        if (feet >= 5280)
            return Math.round(feet / 5280) + " mi";
        if (feet >= 900)
            return (Math.round(feet / 528) / 10.0) + " mi";
        return Math.round(feet / 3) + " yd";
    }

    /** The legend's numbers follow the operator's unit, like every other speed. */
    private void updateWindScale() {
        final float[] breaks = new float[WindScaleView.bandCount() - 1];
        for (int i = 0; i < breaks.length; i++)
            breaks[i] = (float) Units.toDisplay(Quantity.SPEED, WindScaleView.bandEdgeMs(i), units);
        windScale.setScale(breaks, Units.displayUnit(Quantity.SPEED, units));
    }

    /** "Latest, 4:40 pm" or "18 min ago, 4:22 pm", in the phone's zone. */
    private String frameLabel(int index, String time) {
        if (time == null || index < 0)
            return "No frame yet";
        final long t = com.atakmap.android.atmosphere.data.IsoTime.parse(time);
        if (t <= 0)
            return time;
        final String when = new SimpleDateFormat("h:mm a", Locale.US).format(new Date(t))
                .replace("AM", "am").replace("PM", "pm");
        final boolean latest = index == radarFrames.size() - 1;
        return (latest ? "Latest" : capitalize(relativeTime(t))) + ", " + when;
    }

    /** The pane left the screen: an armed pick must not keep the map's tap listeners. */
    public void onClosed() {
        disarmPick();
        updateModeIcons();
    }

    private void loadSelectedSource(SharedPreferences prefs) {
        final String storedId = prefs == null ? null : prefs.getString(PREF_SOURCE, null);
        int index = 0;
        for (int i = 0; i < sources.size(); i++) {
            if (sources.get(i).id.equals(storedId)) {
                index = i;
                break;
            }
        }
        if (!sources.isEmpty())
            selected = sources.get(index);
    }

    /**
     * The source picker: a single-choice dialog on ATAK's own context. Never a
     * Spinner: its dropdown is a Dialog built from the context that inflated the
     * view, and on the plugin context that is a BadTokenException that kills ATAK
     * (plugin UI standard, CLAUDE.md).
     */
    private void showSourceDialog() {
        if (sources.isEmpty())
            return;
        final Context ctx = MapCompat.atakContext();
        if (ctx == null)
            return;
        final String[] names = sourceNames();
        final int checked = selected == null ? -1 : sources.indexOf(selected);
        new AlertDialog.Builder(ctx)
                .setTitle(pluginContext.getString(R.string.source_title))
                .setSingleChoiceItems(names, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        d.dismiss();
                        if (which < 0 || which >= sources.size())
                            return;
                        selected = sources.get(which);
                        final SharedPreferences p = MapCompat.prefs();
                        if (p != null)
                            p.edit().putString(PREF_SOURCE, selected.id).apply();
                        snapshot = null;
                        refresh(false);
                    }
                })
                .setNegativeButton(pluginContext.getString(R.string.close), null)
                .show();
    }

    /** The gear: everything that is not the readout itself. */
    private void showSettingsDialog() {
        final Context ctx = MapCompat.atakContext();
        if (ctx == null)
            return;
        final String[] items = {
                pluginContext.getString(R.string.source_title) + ": "
                        + (selected == null ? pluginContext.getString(R.string.no_sources)
                                : sourceName(selected)),
                pluginContext.getString(R.string.sources_title),
                pluginContext.getString(R.string.variables_title),
                pluginContext.getString(R.string.privacy_title),
        };
        new AlertDialog.Builder(ctx)
                .setTitle(pluginContext.getString(R.string.settings_title))
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        switch (which) {
                            case 0: showSourceDialog(); break;
                            case 1: showSourcesDialog(); break;
                            case 2: showVariablesDialog(); break;
                            default: showPrivacyDialog(); break;
                        }
                    }
                })
                .setNegativeButton(pluginContext.getString(R.string.close), null)
                .show();
    }

    private String[] sourceNames() {
        final String[] names = new String[sources.size()];
        for (int i = 0; i < sources.size(); i++)
            names[i] = sourceName(sources.get(i));
        return names;
    }

    /** External (operator-dropped) definitions are marked, as the Sources dialog marks them. */
    private static String sourceName(WxSourceDef def) {
        return def.origin == WxSourceDef.Origin.EXTERNAL
                ? def.displayName + " *" : def.displayName;
    }

    private void wireButtons() {
        refreshButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refresh(true);
            }
        });

        modeSelf.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setMode(PointMode.SELF);
            }
        });

        modeCenter.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setMode(PointMode.CENTER);
            }
        });

        modePick.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                armPick();
            }
        });

        favoritesButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showFavoritesDialog();
            }
        });

        unitsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final UnitSystem[] all = UnitSystem.values();
                applyUnits(all[(units.ordinal() + 1) % all.length]);
            }
        });

        wideButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (host != null)
                    host.toggleWide();
            }
        });

        settingsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSettingsDialog();
            }
        });
    }

    // ---- the point ---------------------------------------------------------------

    private void setMode(PointMode m) {
        disarmPick();
        mode = m;
        if (m != PointMode.FAVORITE)
            favorite = null;
        final SharedPreferences p = MapCompat.prefs();
        if (p != null) {
            final SharedPreferences.Editor e = p.edit().putString(PREF_MODE, m.name());
            if (favorite == null)
                e.remove(PREF_FAVORITE);
            else
                e.putString(PREF_FAVORITE, favorite.name);
            if (pickedPoint == null)
                e.remove(PREF_PICKED);
            else
                e.putString(PREF_PICKED, pickedPoint.getLatitude() + "," + pickedPoint.getLongitude());
            e.apply();
        }
        updateModeIcons();
        snapshot = null;
        refresh(false);
    }

    private void setFavorite(Favorites.Place place) {
        favorite = place;
        setMode(place == null ? PointMode.CENTER : PointMode.FAVORITE);
    }

    /** The icon for the point in use is green; an armed pick is green until the tap. */
    private void updateModeIcons() {
        final int on = pluginContext.getResources().getColor(R.color.state_on);
        modeSelf.setColorFilter(mode == PointMode.SELF ? on : Color.WHITE);
        modeCenter.setColorFilter(mode == PointMode.CENTER ? on : Color.WHITE);
        modePick.setColorFilter(pickArmed || mode == PointMode.PICKED ? on : Color.WHITE);
        favoritesButton.setColorFilter(mode == PointMode.FAVORITE ? on : Color.WHITE);
    }

    /**
     * The row is too narrow for "Imperial", so the button wears the unit that tells
     * the three systems apart: Celsius for metric, Fahrenheit for imperial, knots
     * for aviation, which is imperial in every way but wind, distance and pressure.
     * It read "\u00b0C" in aviation until 2026-09-22, which is the one thing it must
     * never do: say Celsius while the readout is in Fahrenheit.
     */
    private void updateUnitsButton() {
        final String face;
        switch (units) {
            case METRIC:
                face = "\u00b0C";
                break;
            case AVIATION:
                face = "kt";
                break;
            default:
                face = "\u00b0F";
                break;
        }
        unitsButton.setText(face);
        unitsButton.setContentDescription(units.label());
    }

    private GeoPoint point() {
        switch (mode) {
            case SELF: return MapCompat.selfPoint();
            case PICKED: return pickedPoint;
            case FAVORITE: return favorite == null ? null
                    : new GeoPoint(favorite.latitude, favorite.longitude);
            default: return MapCompat.mapCenter();
        }
    }

    /** What the position line calls the point. */
    private String modeLabel() {
        switch (mode) {
            case SELF: return pluginContext.getString(R.string.self_position);
            case PICKED: return pluginContext.getString(R.string.picked_point);
            case FAVORITE: return "\u2605 " + (favorite == null ? "" : favorite.name);
            default: return pluginContext.getString(R.string.map_center);
        }
    }

    private static GeoPoint parsePoint(String s) {
        if (s == null)
            return null;
        final String[] parts = s.split(",");
        if (parts.length != 2)
            return null;
        try {
            final GeoPoint p = new GeoPoint(Double.parseDouble(parts[0].trim()),
                    Double.parseDouble(parts[1].trim()));
            return p.isValid() ? p : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Pick a point: the next map tap is the point (WxReport's Map Lock). The map's
     * click listeners are pushed and ours put in front, and popped again on the tap,
     * on a second Pick tap, or when the pane closes, so an armed pick never outlives
     * the pane and never eats a tap meant for ATAK.
     */
    private void armPick() {
        if (pickArmed) {
            disarmPick();
            updateModeIcons();
            statusText.setText("");
            return;
        }
        final MapView mv = MapView.getMapView();
        if (mv == null)
            return;
        pickArmed = true;
        updateModeIcons();
        statusText.setTextColor(pluginContext.getResources().getColor(R.color.state_on));
        statusText.setText(pluginContext.getString(R.string.pick_prompt));
        final MapEventDispatcher d = mv.getMapEventDispatcher();
        d.pushListeners();
        d.clearListeners(MapEvent.MAP_CLICK);
        d.clearListeners(MapEvent.ITEM_CLICK);
        pickListener = new MapEventDispatcher.MapEventDispatchListener() {
            @Override
            public void onMapEvent(MapEvent event) {
                final PointF pf = event.getPointF();
                GeoPoint p = null;
                if (pf != null) {
                    final GeoPointMetaData gp = mv.inverseWithElevation(pf.x, pf.y);
                    p = gp == null ? null : gp.get();
                }
                disarmPick();
                if (p == null || !p.isValid()) {
                    updateModeIcons();
                    return;
                }
                pickedPoint = p;
                setMode(PointMode.PICKED);
            }
        };
        d.addMapEventListener(MapEvent.MAP_CLICK, pickListener);
        d.addMapEventListener(MapEvent.ITEM_CLICK, pickListener);
    }

    private void disarmPick() {
        if (!pickArmed)
            return;
        pickArmed = false;
        pickListener = null;
        final MapView mv = MapView.getMapView();
        if (mv != null)
            mv.getMapEventDispatcher().popListeners();
    }

    private void refresh(boolean force) {
        if (selected == null) {
            statusText.setText(R.string.no_sources);
            return;
        }

        final GeoPoint p = point();
        if (p == null) {
            positionText.setText(R.string.no_position);
            statusText.setText(mode == PointMode.SELF
                    ? "No self position yet (no GPS fix). Tap Refresh once ATAK has one."
                    : mode == PointMode.PICKED ? "No point picked yet" : "No map center yet");
            return;
        }

        // The point being read, and nothing about how it is rounded on the way
        // out; that belongs in Position sent, not on a line read every time.
        positionText.setText(modeLabel() + " \u2014 " + egress.latitude(p) + ", "
                + egress.longitude(p));
        statusText.setTextColor(Color.parseColor("#dfb228"));
        statusText.setText("Getting the forecast\u2026");

        client.fetch(selected, p, force, new WeatherClient.Listener() {
            @Override
            public void onSnapshot(Snapshot result, boolean fromCache) {
                snapshot = result;
                final long age = result.ageMillis(System.currentTimeMillis());
                statusText.setTextColor(Color.parseColor("#dfb228"));
                // The place and how old it is. Which service answered, and whether
                // the bytes came off disk, are not things a crew can act on.
                statusText.setText((result.place == null ? "" : result.place + " \u2014 ")
                        + Snapshot.describeAge(age));
                render();
            }

            @Override
            public void onError(String message, Snapshot stale) {
                statusText.setTextColor(Color.parseColor("#ff8a65"));
                if (stale != null) {
                    snapshot = stale;
                    final long age = stale.ageMillis(System.currentTimeMillis());
                    statusText.setText(message + " — showing the last one, "
                            + Snapshot.describeAge(age));
                    render();
                } else {
                    statusText.setText(message);
                    currentContainer.removeAllViews();
                    trendChips.removeAllViews();
                }
            }
        });
    }

    private void updateHoursTableButton() {
        hoursTableButton.setText(hoursTableOpen ? R.string.hide_hours_table
                : R.string.show_hours_table);
    }

    private void render() {
        currentContainer.removeAllViews();
        trendChips.removeAllViews();
        hoursLegend.removeAllViews();
        hoursContainer.removeAllViews();
        daysLegend.removeAllViews();
        daysContainer.removeAllViews();

        if (snapshot == null)
            return;

        final Set<String> wanted = ParamSelection.selectedKeys(selected);

        List<Reading> now = new ArrayList<>();
        for (Reading r : snapshot.current) {
            if (wanted.contains(r.key))
                now.add(r);
        }

        // A source with no "current" block (NWS is one) still has a first forecast step,
        // which is the closest thing to now it can offer. Say so rather than showing
        // an empty heading.
        boolean fromSeries = false;
        if (now.isEmpty() && !snapshot.series.isEmpty()) {
            for (Reading r : snapshot.series.get(0).readings) {
                if (wanted.contains(r.key))
                    now.add(r);
            }
            fromSeries = true;
        }

        // A service with no "current" block gives its first forecast hour instead. That
        // is a fact about the feed, not about the weather, so the heading stays "Now".
        currentHeading.setText(pluginContext.getString(R.string.heading_now));

        renderNow(now);
        renderSunMoon();

        renderTrend(wanted);
        renderHours(wanted);
        renderDays(wanted);
        attributionText.setText(snapshot.attribution == null ? "" : snapshot.attribution);
    }

    private static final int TILE_COLUMNS = 3;
    /** Roboto Medium: the readout face. Not monospace, which spaced the digits like a terminal. */
    private static final Typeface VALUE_FACE = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    /**
     * The "now" block as tiles: the number big with its label directly under it, three
     * across. A label-left, value-right row put the two at opposite edges, and at 95 %
     * width they were half a screen apart.
     */
    private void renderNow(List<Reading> now) {
        LinearLayout row = null;
        int inRow = TILE_COLUMNS;
        for (Reading r : now) {
            if (inRow == TILE_COLUMNS) {
                row = new LinearLayout(pluginContext);
                row.setOrientation(LinearLayout.HORIZONTAL);
                currentContainer.addView(row);
                inRow = 0;
            }
            if (kind(r) == Kind.SKY)
                row.addView(skyTile(r));
            else
                row.addView(tile(tileLabel(r), r.format(units)));
            inRow++;
        }
        // Pad a short last row so its tiles keep the width of the others.
        while (row != null && inRow < TILE_COLUMNS) {
            final View filler = new View(pluginContext);
            filler.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
            row.addView(filler);
            inRow++;
        }
    }

    /** Wind direction is where the wind comes from; the label says so. */
    private static String tileLabel(Reading r) {
        switch (kind(r)) {
            case TEMP: return "Temperature";
            case DEW: return "Dew point";
            case FEELS: return "Feels like";
            case RH: return "Humidity";
            case WIND: return "Wind";
            case GUST: return "Gusts";
            case DIR: return "Wind from";
            case POP: return "Precip chance";
            case PRECIP: return "Precip";
            case SKY: return "Sky cover";
            default: return r.label;
        }
    }

    /** Sky cover as the station-plot okta circle, the percentage in the label under it. */
    private View skyTile(Reading r) {
        final LinearLayout t = new LinearLayout(pluginContext);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        t.setPadding(dp(4), dp(6), dp(4), dp(6));
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        final SkyCoverView glyph = new SkyCoverView(pluginContext);
        glyph.setLayoutParams(new LinearLayout.LayoutParams(dp(34), dp(34)));
        glyph.setPercent(r.valid() ? r.value : Double.NaN);
        final TextView l = new TextView(pluginContext);
        l.setText(r.valid() ? "Sky cover " + r.format(units) : "Sky cover");
        l.setTextSize(11);
        l.setAlpha(0.6f);
        l.setGravity(Gravity.CENTER);
        l.setSingleLine(true);
        t.addView(glyph);
        t.addView(l);
        return t;
    }

    private View tile(String label, String value) {
        final LinearLayout t = new LinearLayout(pluginContext);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        t.setPadding(dp(4), dp(6), dp(4), dp(6));
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        final TextView v = new TextView(pluginContext);
        v.setText(value);
        v.setTextSize(26);
        v.setTypeface(VALUE_FACE);
        v.setTextColor(Color.WHITE);
        v.setGravity(Gravity.CENTER);
        v.setSingleLine(true);
        final TextView l = new TextView(pluginContext);
        l.setText(label);
        l.setTextSize(11);
        l.setAlpha(0.6f);
        l.setGravity(Gravity.CENTER);
        l.setSingleLine(true);
        t.addView(v);
        t.addView(l);
        return t;
    }

    /** Hours are shown as columns; more than this is the days strip's job. */
    private static final int HOURS_SHOWN = 48;
    private static final int COLUMN_DP = 92;
    private static final int HEADER_DP = 36;
    private static final int ROW_DP = 24;

    /**
     * One value across the coming hours, the way the operator reads it in a consumer
     * weather app: a column per hour, the number on a line whose height follows it, a
     * sun or moon with cloud for the sky, sunrise and sunset as their own columns. The
     * chip row under it picks the value; direction rides on the wind as an arrow and
     * sky cover is in every column, so neither is a chip.
     */
    private void renderTrend(Set<String> wanted) {
        final List<Reading> order = keyOrder(wanted);
        final List<Reading> chips = new ArrayList<>();
        for (Reading r : order) {
            final Kind k = kind(r);
            if (k != Kind.DIR && k != Kind.SKY && k != Kind.OTHER)
                chips.add(r);
        }
        final boolean any = !chips.isEmpty() && !snapshot.series.isEmpty();
        seriesHeading.setVisibility(any ? View.VISIBLE : View.GONE);
        trendScroll.setVisibility(any ? View.VISIBLE : View.GONE);
        trendChips.setVisibility(any ? View.VISIBLE : View.GONE);
        if (!any)
            return;
        // The chosen kind must be one this source has, or fall back to the first.
        Reading chosen = null;
        for (Reading r : chips)
            if (kind(r) == trendKind) chosen = r;
        if (chosen == null) {
            chosen = chips.get(0);
            trendKind = kind(chosen);
        }
        for (final Reading r : chips) {
            final Button chip = (Button) LayoutInflater.from(pluginContext)
                    .inflate(R.layout.trend_chip, trendChips, false);
            chip.setText(chipLabel(r));
            chip.setTextColor(kind(r) == trendKind
                    ? pluginContext.getResources().getColor(R.color.state_on) : Color.WHITE);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    trendKind = kind(r);
                    final SharedPreferences p = MapCompat.prefs();
                    if (p != null)
                        p.edit().putString(PREF_TREND, trendKind.name()).apply();
                    render();
                }
            });
            trendChips.addView(chip);
        }
        trend.setColumns(trendColumns(chosen, order));
        trendScroll.scrollTo(0, 0);
    }

    /**
     * The hours as a table: every shown value per hour, the legend pinned on the left,
     * behind a toggle. The trend strip is the default read and the table is there when
     * a number for a given hour is wanted; the operator asked to keep both. Closed by
     * default, the choice kept in prefs, and only built while open, since 48 columns
     * of TextViews are not free.
     */
    private void renderHours(Set<String> wanted) {
        final List<Reading> order = keyOrder(wanted);
        final boolean any = !order.isEmpty() && !snapshot.series.isEmpty();
        hoursTableButton.setVisibility(any ? View.VISIBLE : View.GONE);
        hoursTable.setVisibility(any && hoursTableOpen ? View.VISIBLE : View.GONE);
        if (!any || !hoursTableOpen)
            return;
        final List<String> labels = new ArrayList<>();
        for (Reading r : order)
            labels.add(shortLabel(r));
        hoursLegend.addView(column("", labels, true));
        final SimpleDateFormat day = new SimpleDateFormat("EEE", Locale.US);
        final SimpleDateFormat hour = new SimpleDateFormat("ha", Locale.US);
        int shown = 0;
        for (SeriesEntry entry : snapshot.series) {
            if (shown++ >= HOURS_SHOWN)
                break;
            final List<String> values = new ArrayList<>();
            for (Reading k : order) {
                final Reading r = entry.reading(k.key);
                values.add(r == null ? "\u2014" : r.format(units));
            }
            final String header;
            if (entry.timeMillis > 0) {
                final Date d = new Date(entry.timeMillis);
                header = day.format(d) + "\n" + hour.format(d).toLowerCase(Locale.US);
            } else {
                header = String.valueOf(entry.timeRaw);
            }
            hoursContainer.addView(column(header, values, false));
        }
    }

    /** The chip text carries the unit, so the columns can print bare numbers. */
    private String chipLabel(Reading r) {
        switch (kind(r)) {
            case TEMP: return "Temperature";
            case DEW: return "Dew point";
            case FEELS: return "Feels like";
            case RH: return "Humidity";
            case WIND: return "Wind " + Units.displayUnit(Quantity.SPEED, units);
            case GUST: return "Gusts " + Units.displayUnit(Quantity.SPEED, units);
            case POP: return "Precip chance";
            case PRECIP: return "Precip " + Units.displayUnit(Quantity.PRECIPITATION, units);
            default: return r.label;
        }
    }

    /** A column's number: compact, the unit implied by the chip. */
    private String columnText(Reading r) {
        if (r == null || !r.valid())
            return "\u2014";
        switch (r.quantity) {
            case TEMPERATURE:
                return Math.round(Units.toDisplay(r.quantity, r.value, units)) + "\u00b0";
            case PERCENT:
                return Math.round(r.value) + "%";
            case SPEED:
                return String.valueOf(Math.round(Units.toDisplay(r.quantity, r.value, units)));
            default:
                return Units.format(r.quantity, r.value, units)
                        .replace(" " + Units.displayUnit(r.quantity, units), "");
        }
    }

    private List<TrendStripView.Column> trendColumns(Reading chosen, List<Reading> order) {
        final List<TrendStripView.Column> cols = new ArrayList<>();
        String dirKey = null, skyKey = null;
        for (Reading r : order) {
            if (kind(r) == Kind.DIR) dirKey = r.key;
            if (kind(r) == Kind.SKY) skyKey = r.key;
        }
        final long now = System.currentTimeMillis();
        final SimpleDateFormat hourFmt = new SimpleDateFormat("ha", Locale.US);
        final SimpleDateFormat dayFmt = new SimpleDateFormat("EEE", Locale.US);
        final SimpleDateFormat dayKey = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        final SimpleDateFormat clock = new SimpleDateFormat("h:mm", Locale.US);
        final Map<String, long[]> sunByDay = new LinkedHashMap<>();
        String lastDay = null;
        int shown = 0;
        long prevTime = 0;
        boolean nowMarked = false;
        for (SeriesEntry e : snapshot.series) {
            if (shown++ >= HOURS_SHOWN)
                break;
            if (e.timeMillis <= 0)
                continue;
            final Date d = new Date(e.timeMillis);
            final String dk = dayKey.format(d);
            long[] sun = sunByDay.get(dk);
            if (sun == null && !sunByDay.containsKey(dk)) {
                sun = Astro.sunRiseSet(e.timeMillis, TimeZone.getDefault(),
                        snapshot.latitude, snapshot.longitude);
                sunByDay.put(dk, sun);
            }
            // a sunrise or sunset between the previous hour and this one gets its own column
            if (sun != null && prevTime > 0) {
                for (int i = 0; i < 2; i++) {
                    if (sun[i] > prevTime && sun[i] <= e.timeMillis) {
                        final TrendStripView.Column sc = new TrendStripView.Column();
                        sc.sunEvent = true;
                        sc.sunrise = i == 0;
                        sc.header = clock.format(new Date(sun[i]));
                        sc.sunLabel = i == 0 ? "Sunrise" : "Sunset";
                        cols.add(sc);
                    }
                }
            }
            final TrendStripView.Column c = new TrendStripView.Column();
            final boolean isNow = !nowMarked && e.timeMillis <= now + 3_600_000L
                    && e.timeMillis > now - 3_600_000L;
            if (isNow) {
                c.header = "Now";
                c.now = true;
                nowMarked = true;
            } else if (lastDay != null && !dk.equals(lastDay)) {
                c.header = dayFmt.format(d) + "\n" + hourFmt.format(d).toLowerCase(Locale.US);
            } else {
                c.header = hourFmt.format(d).toLowerCase(Locale.US);
            }
            lastDay = dk;
            final Reading v = e.reading(chosen.key);
            c.value = v == null || !v.valid() ? Double.NaN
                    : Units.toDisplay(v.quantity, v.value, units);
            c.text = columnText(v);
            if ((kind(chosen) == Kind.WIND || kind(chosen) == Kind.GUST) && dirKey != null) {
                final Reading dir = e.reading(dirKey);
                if (dir != null && dir.valid())
                    c.arrowDeg = (dir.value + 180) % 360;   // from -> toward
            }
            if (skyKey != null) {
                final Reading sky = e.reading(skyKey);
                c.oktas = sky == null || !sky.valid() ? -1 : SkyCoverView.oktas(sky.value);
            }
            c.night = sun == null ? false : (e.timeMillis < sun[0] || e.timeMillis >= sun[1]);
            cols.add(c);
            prevTime = e.timeMillis;
        }
        return cols;
    }

    /** Sunrise, sunset and the moon for the point, computed on the device. */
    private void renderSunMoon() {
        if (snapshot == null)
            return;
        final long now = System.currentTimeMillis();
        final long[] sun = Astro.sunRiseSet(now, TimeZone.getDefault(), snapshot.latitude,
                snapshot.longitude);
        final SimpleDateFormat clock = new SimpleDateFormat("h:mm a", Locale.US);
        final LinearLayout row = new LinearLayout(pluginContext);
        row.setOrientation(LinearLayout.HORIZONTAL);
        if (sun != null) {
            row.addView(tile("Sunrise", clock.format(new Date(sun[0])).toLowerCase(Locale.US)));
            row.addView(tile("Sunset", clock.format(new Date(sun[1])).toLowerCase(Locale.US)));
        } else {
            row.addView(tile("Sun", "no rise or set today"));
            final View filler = new View(pluginContext);
            filler.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
            row.addView(filler);
        }
        final int lit = (int) Math.round(Astro.moonIllumination(now) * 100);
        row.addView(tile(Astro.moonPhaseName(now), lit + "% lit"));
        currentContainer.addView(row);
    }

    /** The wanted readings in the order the source lists them, taken from the first hour that has them. */
    private List<Reading> keyOrder(Set<String> wanted) {
        final List<Reading> order = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        for (SeriesEntry entry : snapshot.series) {
            for (Reading r : entry.readings) {
                if (wanted.contains(r.key) && seen.add(r.key))
                    order.add(r);
            }
            if (!order.isEmpty())
                break;
        }
        return order;
    }

    /**
     * The hours folded into days: the numbers a shift is planned on. High and low
     * temperature, the lowest RH, the strongest wind and gust, the highest chance of
     * precipitation and the total. Only shown when the forecast covers more than one day.
     */
    private void renderDays(Set<String> wanted) {
        final Map<String, DayAgg> days = new LinkedHashMap<>();
        final SimpleDateFormat dayKey = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        final SimpleDateFormat dayHead = new SimpleDateFormat("EEE\nM/d", Locale.US);
        for (SeriesEntry e : snapshot.series) {
            if (e.timeMillis <= 0)
                continue;
            final Date d = new Date(e.timeMillis);
            final String k = dayKey.format(d);
            DayAgg agg = days.get(k);
            if (agg == null) {
                agg = new DayAgg(dayHead.format(d));
                days.put(k, agg);
            }
            for (Reading r : e.readings) {
                if (r.valid() && wanted.contains(r.key))
                    agg.take(r);
            }
        }
        final boolean any = days.size() >= 2;
        daysHeading.setVisibility(any ? View.VISIBLE : View.GONE);
        daysStrip.setVisibility(any ? View.VISIBLE : View.GONE);
        if (!any)
            return;

        // Rows exist only when some day has the value, so a source without gusts has no gust row.
        final String[] rowLabels = {"Hi", "Lo", "Min RH", "Max wind", "Max gust", "Precip %", "Precip"};
        final boolean[] rowUsed = new boolean[rowLabels.length];
        for (DayAgg a : days.values()) {
            for (int i = 0; i < rowLabels.length; i++)
                rowUsed[i] |= !Double.isNaN(a.row(i));
        }
        final List<String> labels = new ArrayList<>();
        for (int i = 0; i < rowLabels.length; i++)
            if (rowUsed[i]) labels.add(rowLabels[i]);
        daysLegend.addView(column("", labels, true));
        for (DayAgg a : days.values()) {
            final List<String> values = new ArrayList<>();
            for (int i = 0; i < rowLabels.length; i++) {
                if (!rowUsed[i])
                    continue;
                final double v = a.row(i);
                values.add(Double.isNaN(v) ? "\u2014" : Units.format(a.quantity(i), v, units));
            }
            daysContainer.addView(column(a.header, values, false));
        }
    }

    /** What a reading is, from its quantity and its name, so days can be aggregated. */
    private enum Kind {
        TEMP, DEW, FEELS, RH, WIND, GUST, DIR, POP, PRECIP, SKY, OTHER;

        static Kind fromName(String n) {
            if (n == null) return TEMP;
            for (Kind k : values())
                if (k.name().equals(n)) return k;
            return TEMP;
        }
    }

    private static Kind kind(Reading r) {
        final String k = (r.key + " " + r.label).toLowerCase(Locale.US);
        switch (r.quantity) {
            case TEMPERATURE:
                if (k.contains("dew")) return Kind.DEW;
                if (k.contains("feel") || k.contains("apparent")) return Kind.FEELS;
                return Kind.TEMP;
            case PERCENT:
                if (k.contains("humid")) return Kind.RH;
                if (k.contains("precip")) return Kind.POP;
                if (k.contains("cloud") || k.contains("sky")) return Kind.SKY;
                return Kind.OTHER;
            case SPEED:
                return k.contains("gust") ? Kind.GUST : Kind.WIND;
            case ANGLE:
                return Kind.DIR;
            case PRECIPITATION:
                return Kind.PRECIP;
            default:
                return Kind.OTHER;
        }
    }

    /** Legend text that fits a column. */
    private static String shortLabel(Reading r) {
        switch (kind(r)) {
            case TEMP: return "Temp";
            case DEW: return "Dew pt";
            case FEELS: return "Feels";
            case RH: return "RH";
            case WIND: return "Wind";
            case GUST: return "Gust";
            case DIR: return "Dir";
            case POP: return "Precip %";
            case PRECIP: return "Precip";
            case SKY: return "Sky";
            default: return r.label.length() > 9 ? r.label.substring(0, 9) : r.label;
        }
    }

    /** One day's extremes, NaN until a reading arrives. */
    private static final class DayAgg {
        final String header;
        double tHi = Double.NaN, tLo = Double.NaN, rhLo = Double.NaN, windHi = Double.NaN,
                gustHi = Double.NaN, popHi = Double.NaN, precipSum = Double.NaN;

        DayAgg(String header) {
            this.header = header;
        }

        void take(Reading r) {
            switch (kind(r)) {
                case TEMP:
                    tHi = Double.isNaN(tHi) ? r.value : Math.max(tHi, r.value);
                    tLo = Double.isNaN(tLo) ? r.value : Math.min(tLo, r.value);
                    break;
                case RH: rhLo = Double.isNaN(rhLo) ? r.value : Math.min(rhLo, r.value); break;
                case WIND: windHi = Double.isNaN(windHi) ? r.value : Math.max(windHi, r.value); break;
                case GUST: gustHi = Double.isNaN(gustHi) ? r.value : Math.max(gustHi, r.value); break;
                case POP: popHi = Double.isNaN(popHi) ? r.value : Math.max(popHi, r.value); break;
                case PRECIP: precipSum = Double.isNaN(precipSum) ? r.value : precipSum + r.value; break;
                default: break;
            }
        }

        double row(int i) {
            switch (i) {
                case 0: return tHi;
                case 1: return tLo;
                case 2: return rhLo;
                case 3: return windHi;
                case 4: return gustHi;
                case 5: return popHi;
                default: return precipSum;
            }
        }

        Quantity quantity(int i) {
            switch (i) {
                case 0: case 1: return Quantity.TEMPERATURE;
                case 2: case 5: return Quantity.PERCENT;
                case 3: case 4: return Quantity.SPEED;
                default: return Quantity.PRECIPITATION;
            }
        }
    }

    /**
     * A column of the strip: a two-line header and one line per row, every line a fixed
     * height so the legend column and the value columns line up.
     */
    private View column(String header, List<String> values, boolean legend) {
        final LinearLayout col = new LinearLayout(pluginContext);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(
                legend ? LinearLayout.LayoutParams.WRAP_CONTENT : dp(COLUMN_DP),
                LinearLayout.LayoutParams.WRAP_CONTENT));
        col.setPadding(dp(4), 0, dp(4), 0);
        final TextView h = new TextView(pluginContext);
        h.setText(header);
        h.setTextSize(12);
        h.setMaxLines(2);
        h.setHeight(dp(HEADER_DP));
        h.setGravity((legend ? Gravity.START : Gravity.CENTER_HORIZONTAL) | Gravity.BOTTOM);
        h.setAlpha(0.8f);
        col.addView(h);
        for (String v : values) {
            final TextView t = new TextView(pluginContext);
            t.setText(v);
            t.setTextSize(15);
            t.setSingleLine(true);
            t.setHeight(dp(ROW_DP));
            t.setGravity((legend ? Gravity.START : Gravity.CENTER_HORIZONTAL) | Gravity.CENTER_VERTICAL);
            if (legend)
                t.setAlpha(0.7f);
            else
                t.setTypeface(VALUE_FACE);
            col.addView(t);
        }
        return col;
    }

    private int dp(int v) {
        return Math.round(v * pluginContext.getResources().getDisplayMetrics().density);
    }

    // ---- dialogs -----------------------------------------------------------------

    private void showSourcesDialog() {
        final Context ctx = MapCompat.atakContext();
        if (ctx == null || sources.isEmpty())
            return;

        final String[] labels = new String[sources.size()];
        final boolean[] enabled = new boolean[sources.size()];
        for (int i = 0; i < sources.size(); i++) {
            final WxSourceDef def = sources.get(i);
            final StringBuilder sb = new StringBuilder(def.displayName);
            sb.append("\n").append(hosts(def));
            if (def.origin == WxSourceDef.Origin.EXTERNAL)
                sb.append("\nfrom ").append(def.originFile);
            if (def.requiresApiKey)
                sb.append("\nnot available in this build");
            labels[i] = sb.toString();
            enabled[i] = egress.isEnabled(def);
        }

        new AlertDialog.Builder(ctx)
                .setTitle(pluginContext.getString(R.string.sources_title))
                .setMultiChoiceItems(labels, enabled,
                        new DialogInterface.OnMultiChoiceClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which,
                                    boolean isChecked) {
                                egress.setEnabled(sources.get(which), isChecked);
                            }
                        })
                .setPositiveButton(pluginContext.getString(R.string.close), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        refresh(false);
                    }
                })
                .show();
    }

    private void showVariablesDialog() {
        final Context ctx = MapCompat.atakContext();
        if (ctx == null || selected == null)
            return;

        final List<WxParam> params = selected.params;
        final String[] labels = new String[params.size()];
        final boolean[] checked = new boolean[params.size()];
        final Set<String> keys = new HashSet<>(ParamSelection.selectedKeys(selected));
        for (int i = 0; i < params.size(); i++) {
            labels[i] = params.get(i).label;
            checked[i] = keys.contains(params.get(i).key);
        }

        new AlertDialog.Builder(ctx)
                .setTitle(pluginContext.getString(R.string.variables_title))
                .setMultiChoiceItems(labels, checked,
                        new DialogInterface.OnMultiChoiceClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which,
                                    boolean isChecked) {
                                if (isChecked)
                                    keys.add(params.get(which).key);
                                else
                                    keys.remove(params.get(which).key);
                            }
                        })
                .setPositiveButton(pluginContext.getString(R.string.close), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        ParamSelection.setSelected(selected, keys);
                        // The variable list is part of the request, so this needs a
                        // fetch, not just a re-render.
                        refresh(true);
                    }
                })
                .show();
    }

    // ---- favorites -----------------------------------------------------------------

    private void showFavoritesDialog() {
        final Context ctx = MapCompat.atakContext();
        if (ctx == null)
            return;
        final List<Favorites.Place> all = new ArrayList<>(favorites.all());
        final AlertDialog.Builder b = new AlertDialog.Builder(ctx)
                .setTitle(pluginContext.getString(R.string.favorites_title));
        if (all.isEmpty()) {
            b.setMessage(pluginContext.getString(R.string.favorites_none));
        } else {
            final String[] names = new String[all.size()];
            int checked = -1;
            for (int i = 0; i < all.size(); i++) {
                names[i] = all.get(i).name;
                if (favorite != null && favorite.name.equals(names[i]))
                    checked = i;
            }
            b.setSingleChoiceItems(names, checked, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    dialog.dismiss();
                    setFavorite(all.get(which));
                }
            });
            b.setNeutralButton(pluginContext.getString(R.string.favorites_remove), new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    showRemoveFavoritesDialog();
                }
            });
        }
        b.setPositiveButton(pluginContext.getString(R.string.favorites_add), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                showAddFavoriteDialog();
            }
        });
        b.setNegativeButton(pluginContext.getString(R.string.close), null);
        b.show();
    }

    /**
     * Save the point being read now under a name, suggested from the provider's
     * nearest city when the last snapshot is for this point, else the coordinates.
     * Saving does not switch to it: an operator starring their own position wants
     * to keep following it, not freeze it.
     */
    private void showAddFavoriteDialog() {
        final Context ctx = MapCompat.atakContext();
        if (ctx == null)
            return;
        final GeoPoint p = point();
        if (p == null) {
            Toast.makeText(ctx, pluginContext.getString(R.string.no_position), Toast.LENGTH_SHORT).show();
            return;
        }
        String suggested = egress.latitude(p) + ", " + egress.longitude(p);
        if (snapshot != null && snapshot.place != null
                && Math.abs(snapshot.latitude - p.getLatitude()) < 0.01
                && Math.abs(snapshot.longitude - p.getLongitude()) < 0.01)
            suggested = snapshot.place;

        final EditText input = new EditText(ctx);
        input.setSingleLine(true);
        input.setText(suggested);
        input.setSelectAllOnFocus(true);
        final double lat = p.getLatitude(), lon = p.getLongitude();
        new AlertDialog.Builder(ctx)
                .setTitle(pluginContext.getString(R.string.favorite_name_title))
                .setView(input)
                .setPositiveButton(pluginContext.getString(R.string.save), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        final Favorites.Place saved = favorites.add(
                                input.getText().toString(), lat, lon);
                        if (saved != null)
                            Toast.makeText(ctx, "Saved \u2605 " + saved.name,
                                    Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(pluginContext.getString(R.string.close), null)
                .show();
    }

    private void showRemoveFavoritesDialog() {
        final Context ctx = MapCompat.atakContext();
        final List<Favorites.Place> all = new ArrayList<>(favorites.all());
        if (ctx == null || all.isEmpty())
            return;
        final String[] names = new String[all.size()];
        for (int i = 0; i < all.size(); i++)
            names[i] = all.get(i).name;
        final boolean[] checked = new boolean[all.size()];
        new AlertDialog.Builder(ctx)
                .setTitle(pluginContext.getString(R.string.favorites_remove_title))
                .setMultiChoiceItems(names, checked,
                        new DialogInterface.OnMultiChoiceClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which,
                                    boolean isChecked) {
                                checked[which] = isChecked;
                            }
                        })
                .setPositiveButton(pluginContext.getString(R.string.remove), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        boolean activeGone = false;
                        for (int i = 0; i < names.length; i++) {
                            if (!checked[i])
                                continue;
                            favorites.remove(names[i]);
                            if (favorite != null && favorite.name.equals(names[i]))
                                activeGone = true;
                        }
                        // The place being read is gone: back to the map center.
                        if (activeGone)
                            setMode(PointMode.CENTER);
                    }
                })
                .setNegativeButton(pluginContext.getString(R.string.close), null)
                .show();
    }

    private void showPrivacyDialog() {
        final Context ctx = MapCompat.atakContext();
        if (ctx == null)
            return;

        final int[] choices = {0, 1, 2, 3, 4};
        final String[] labels = new String[choices.length];
        for (int i = 0; i < choices.length; i++) {
            labels[i] = "Rounded to about " + roughly(
                    EgressPolicy.approximateMeters(choices[i]));
        }

        int current = 0;
        for (int i = 0; i < choices.length; i++) {
            if (choices[i] == egress.positionDecimals()) {
                current = i;
                break;
            }
        }

        new AlertDialog.Builder(ctx)
                .setTitle(pluginContext.getString(R.string.privacy_title))
                .setMessage("Asking for a forecast means saying roughly where you "
                        + "are. This sets how exact that is. Nothing else about you "
                        + "is sent.")
                .setSingleChoiceItems(labels, current,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                egress.setPositionDecimals(choices[which]);
                            }
                        })
                .setPositiveButton(pluginContext.getString(R.string.close), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        refresh(false);
                    }
                })
                .show();
    }

    /**
     * A source file that did not load is shown once, at start. Upstream plugins that
     * only log this leave the operator staring at a source that never appears.
     */
    private void showSourceProblemsIfAny() {
        final List<String> problems = registry.problems();
        if (problems.isEmpty())
            return;

        final Context ctx = MapCompat.atakContext();
        if (ctx == null)
            return;

        final StringBuilder sb = new StringBuilder();
        for (String p : problems)
            sb.append("• ").append(p).append("\n");

        new AlertDialog.Builder(ctx)
                .setTitle(pluginContext.getString(R.string.source_problems))
                .setMessage(sb.toString().trim())
                .setPositiveButton(pluginContext.getString(R.string.close), null)
                .show();
    }

    private static String hosts(WxSourceDef def) {
        final List<String> h = def.hosts();
        if (h.isEmpty())
            return "no host";
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < h.size(); i++) {
            if (i > 0)
                sb.append(", ");
            sb.append(h.get(i));
        }
        return sb.toString();
    }
}

package com.atakmap.android.atmosphere.data;

import java.util.HashMap;
import java.util.Map;

/**
 * NWS's own hazard table: every event the Weather Service draws on its maps, with the
 * priority it draws them in and the color it draws them with. Taken whole from
 * weather.gov/help-map on 2026-09-24 (111 events, priorities 1 to 111 with no gaps),
 * so a Red Flag Warning here is the deep pink a crew sees on weather.gov, and where two
 * overlap the one NWS ranks higher is drawn on top.
 *
 * <p>An event the table does not know draws gray at the bottom rather than guessing.
 */
public final class NwsHazards {

    /** Drawn under everything the table ranks. */
    public static final int UNRANKED = 1000;
    public static final int UNKNOWN_COLOR = 0xFFB0B0B0;

    private static final Map<String, int[]> TABLE = new HashMap<>();

    private static void put(String event, int priority, int color) {
        TABLE.put(event, new int[] { priority, color });
    }

    static {
        put("Tsunami Warning", 1, 0xFFFD6347);
        put("Tornado Warning", 2, 0xFFFF0000);
        put("Extreme Wind Warning", 3, 0xFFFF8C00);
        put("Severe Thunderstorm Warning", 4, 0xFFFFA500);
        put("Flash Flood Warning", 5, 0xFF8B0000);
        put("Flash Flood Statement", 6, 0xFF8B0000);
        put("Severe Weather Statement", 7, 0xFF00FFFF);
        put("Shelter In Place Warning", 8, 0xFFFA8072);
        put("Evacuation Immediate", 9, 0xFF7FFF00);
        put("Civil Danger Warning", 10, 0xFFFFB6C1);
        put("Nuclear Power Plant Warning", 11, 0xFF4B0082);
        put("Radiological Hazard Warning", 12, 0xFF4B0082);
        put("Hazardous Materials Warning", 13, 0xFF4B0082);
        put("Fire Warning", 14, 0xFFA0522D);
        put("Civil Emergency Message", 15, 0xFFFFB6C1);
        put("Law Enforcement Warning", 16, 0xFFC0C0C0);
        put("Storm Surge Warning", 17, 0xFFB524F7);
        put("Hurricane Force Wind Warning", 18, 0xFFCD5C5C);
        put("Hurricane Warning", 19, 0xFFDC143C);
        put("Typhoon Warning", 20, 0xFFDC143C);
        put("Special Marine Warning", 21, 0xFFFFA500);
        put("Blizzard Warning", 22, 0xFFFF4500);
        put("Snow Squall Warning", 23, 0xFFC71585);
        put("Ice Storm Warning", 24, 0xFF8B008B);
        put("Heavy Freezing Spray Warning", 25, 0xFF00BFFF);
        put("Winter Storm Warning", 26, 0xFFFF69B4);
        put("Lake Effect Snow Warning", 27, 0xFF008B8B);
        put("Dust Storm Warning", 28, 0xFFFFE4C4);
        put("Blowing Dust Warning", 29, 0xFFFFE4C4);
        put("High Wind Warning", 30, 0xFFDAA520);
        put("Tropical Storm Warning", 31, 0xFFB22222);
        put("Storm Warning", 32, 0xFF9400D3);
        put("Tsunami Advisory", 33, 0xFFD2691E);
        put("Tsunami Watch", 34, 0xFFFF00FF);
        put("Avalanche Warning", 35, 0xFF1E90FF);
        put("Earthquake Warning", 36, 0xFF8B4513);
        put("Volcano Warning", 37, 0xFF2F4F4F);
        put("Ashfall Warning", 38, 0xFFA9A9A9);
        put("Flood Warning", 39, 0xFF00FF00);
        put("Coastal Flood Warning", 40, 0xFF228B22);
        put("Lakeshore Flood Warning", 41, 0xFF228B22);
        put("Ashfall Advisory", 42, 0xFF696969);
        put("High Surf Warning", 43, 0xFF228B22);
        put("Extreme Heat Warning", 44, 0xFFC71585);
        put("Tornado Watch", 45, 0xFFFFFF00);
        put("Severe Thunderstorm Watch", 46, 0xFFDB7093);
        put("Flash Flood Watch", 47, 0xFF2E8B57);
        put("Gale Warning", 48, 0xFFDDA0DD);
        put("Flood Statement", 49, 0xFF00FF00);
        put("Extreme Cold Warning", 50, 0xFF0000FF);
        put("Freeze Warning", 51, 0xFF483D8B);
        put("Red Flag Warning", 52, 0xFFFF1493);
        put("Storm Surge Watch", 53, 0xFFDB7FF7);
        put("Hurricane Watch", 54, 0xFFFF00FF);
        put("Hurricane Force Wind Watch", 55, 0xFF9932CC);
        put("Typhoon Watch", 56, 0xFFFF00FF);
        put("Tropical Storm Watch", 57, 0xFFF08080);
        put("Storm Watch", 58, 0xFFFFE4B5);
        put("Tropical Cyclone Local Statement", 59, 0xFFFFE4B5);
        put("Winter Weather Advisory", 60, 0xFF7B68EE);
        put("Avalanche Advisory", 61, 0xFFCD853F);
        put("Cold Weather Advisory", 62, 0xFFAFEEEE);
        put("Heat Advisory", 63, 0xFFFF7F50);
        put("Flood Advisory", 64, 0xFF00FF7F);
        put("Coastal Flood Advisory", 65, 0xFF7CFC00);
        put("Lakeshore Flood Advisory", 66, 0xFF7CFC00);
        put("High Surf Advisory", 67, 0xFFBA55D3);
        put("Dense Fog Advisory", 68, 0xFF708090);
        put("Dense Smoke Advisory", 69, 0xFFF0E68C);
        put("Small Craft Advisory", 70, 0xFFD8BFD8);
        put("Brisk Wind Advisory", 71, 0xFFD8BFD8);
        put("Hazardous Seas Warning", 72, 0xFFD8BFD8);
        put("Dust Advisory", 73, 0xFFBDB76B);
        put("Blowing Dust Advisory", 74, 0xFFBDB76B);
        put("Lake Wind Advisory", 75, 0xFFD2B48C);
        put("Wind Advisory", 76, 0xFFD2B48C);
        put("Frost Advisory", 77, 0xFF6495ED);
        put("Freezing Fog Advisory", 78, 0xFF008080);
        put("Freezing Spray Advisory", 79, 0xFF00BFFF);
        put("Low Water Advisory", 80, 0xFFA52A2A);
        put("Local Area Emergency", 81, 0xFFC0C0C0);
        put("Winter Storm Watch", 82, 0xFF4682B4);
        put("Rip Current Statement", 83, 0xFF40E0D0);
        put("Beach Hazards Statement", 84, 0xFF40E0D0);
        put("Gale Watch", 85, 0xFFFFC0CB);
        put("Avalanche Watch", 86, 0xFFF4A460);
        put("Hazardous Seas Watch", 87, 0xFF483D8B);
        put("Heavy Freezing Spray Watch", 88, 0xFFBC8F8F);
        put("Flood Watch", 89, 0xFF2E8B57);
        put("Coastal Flood Watch", 90, 0xFF66CDAA);
        put("Lakeshore Flood Watch", 91, 0xFF66CDAA);
        put("High Wind Watch", 92, 0xFFB8860B);
        put("Extreme Heat Watch", 93, 0xFF800000);
        put("Extreme Cold Watch", 94, 0xFF5F9EA0);
        put("Freeze Watch", 95, 0xFF00FFFF);
        put("Fire Weather Watch", 96, 0xFFFFDEAD);
        put("Extreme Fire Danger", 97, 0xFFE9967A);
        put("911 Telephone Outage", 98, 0xFFC0C0C0);
        put("Coastal Flood Statement", 99, 0xFF6B8E23);
        put("Lakeshore Flood Statement", 100, 0xFF6B8E23);
        put("Special Weather Statement", 101, 0xFFFFE4B5);
        put("Marine Weather Statement", 102, 0xFFFFDAB9);
        put("Air Quality Alert", 103, 0xFF808080);
        put("Air Stagnation Advisory", 104, 0xFF808080);
        put("Hazardous Weather Outlook", 105, 0xFFEEE8AA);
        put("Hydrologic Outlook", 106, 0xFF90EE90);
        put("Short Term Forecast", 107, 0xFF98FB98);
        put("Administrative Message", 108, 0xFFC0C0C0);
        put("Test", 109, 0xFFF0FFFF);
        put("Child Abduction Emergency", 110, 0xFFFFFFFF);
        put("Blue Alert", 111, 0xFFFFFFFF);
    }

    private NwsHazards() {
    }

    /** 1 is Tsunami Warning, the most urgent; larger is less. */
    public static int priority(String event) {
        final int[] v = TABLE.get(event);
        return v == null ? UNRANKED : v[0];
    }

    /** NWS's map color for the event, opaque. */
    public static int color(String event) {
        final int[] v = TABLE.get(event);
        return v == null ? UNKNOWN_COLOR : v[1];
    }

    public static boolean known(String event) {
        return TABLE.containsKey(event);
    }

    public static int size() {
        return TABLE.size();
    }
}

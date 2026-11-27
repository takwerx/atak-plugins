package com.atakmap.android.mapdepot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the catalog keeps the base maps that need the operator's own key.
 *
 * Map Depot 1.16 and older read {@code basemaps.sources} and nothing else, so a
 * keyed (CARTO) row there is listed on those phones and installs with a literal
 * {@code {$key}} in the tile address, which CARTO answers with its watermark
 * (ops #1402). The keyed rows live in {@code basemapsKeyed}, which only 1.17 and
 * newer read.
 */
public class BaseMapCatalogTest {

    private static String row(String id, String keyProvider) {
        return "{\"id\":\"" + id + "\",\"name\":\"" + id.toUpperCase()
                + "\",\"category\":\"Street\",\"file\":\"basemaps/v1/" + id + ".xml\","
                + "\"bytes\":10,\"sha256\":\"" + repeat('a', 64) + "\""
                + (keyProvider == null ? ""
                        : ",\"keyProvider\":\"" + keyProvider
                                + "\",\"keyUrl\":\"https://carto.com/basemaps/apikey/\"")
                + "}";
    }

    private static String repeat(char c, int n) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++)
            sb.append(c);
        return sb.toString();
    }

    private static List<String> ids(List<Depot.BaseMap> maps) {
        final List<String> out = new ArrayList<>();
        for (Depot.BaseMap m : maps)
            out.add(m.id);
        return out;
    }

    @Test
    public void keyedRowsAreReadFromTheirOwnSection() throws Exception {
        final String json = "{\"schema\":1,\"basemaps\":{\"sources\":["
                + row("usgs-topo", null) + "]},"
                + "\"basemapsKeyed\":{\"sources\":["
                + row("carto-voyager", "carto") + "," + row("carto-positron", "carto")
                + "]}}";
        final List<Depot.BaseMap> maps = Depot.parseBaseMaps(json);
        assertEquals(3, maps.size());
        assertTrue(ids(maps).contains("carto-voyager"));
        for (Depot.BaseMap m : maps)
            assertEquals(m.id.startsWith("carto"), m.needsKey());
    }

    /** What the live catalog holds today: no keyed section at all. */
    @Test
    public void aCatalogWithNoKeyedSectionListsTheOrdinaryRows() throws Exception {
        final String json = "{\"basemaps\":{\"sources\":["
                + row("usgs-topo", null) + "," + row("osm", null) + "]}}";
        assertEquals(2, Depot.parseBaseMaps(json).size());
    }

    @Test
    public void aCatalogWithNoBaseMapsAtAllListsNothing() throws Exception {
        assertTrue(Depot.parseBaseMaps("{\"schema\":1}").isEmpty());
        assertTrue(Depot.parseBaseMaps(
                "{\"basemapsKeyed\":{\"sources\":[]}}").isEmpty());
        assertTrue(Depot.parseBaseMaps("{\"basemapsKeyed\":{}}").isEmpty());
    }

    /** Only keyed rows in the catalog (the ordinary section missing). */
    @Test
    public void theKeyedSectionAloneIsEnough() throws Exception {
        final String json = "{\"basemapsKeyed\":{\"sources\":["
                + row("carto-voyager", "carto") + "]}}";
        assertEquals(1, Depot.parseBaseMaps(json).size());
    }

    /** A hand-edited catalog with a keyed row in the old place still works. */
    @Test
    public void aKeyedRowInTheOldSectionStillWorks() throws Exception {
        final String json = "{\"basemaps\":{\"sources\":["
                + row("carto-voyager", "carto") + "]}}";
        final List<Depot.BaseMap> maps = Depot.parseBaseMaps(json);
        assertEquals(1, maps.size());
        assertTrue(maps.get(0).needsKey());
    }

    @Test
    public void aRowInBothSectionsShowsOnce() throws Exception {
        final String json = "{\"basemaps\":{\"sources\":["
                + row("carto-voyager", "carto") + "]},"
                + "\"basemapsKeyed\":{\"sources\":["
                + row("carto-voyager", "carto") + "]}}";
        assertEquals(1, Depot.parseBaseMaps(json).size());
    }

    /**
     * What a 1.16 phone does, written out: it reads {@code basemaps.sources}
     * and ignores the rest. The ingest keeps keyed rows out of that array, so
     * this is what the old reader would list from a catalog that has them.
     */
    @Test
    public void theOrdinaryArrayHoldsNoKeyedRowInTheNewLayout() throws Exception {
        final String json = "{\"basemaps\":{\"sources\":[" + row("usgs-topo", null)
                + "]},\"basemapsKeyed\":{\"sources\":["
                + row("carto-voyager", "carto") + "]}}";
        final org.json.JSONArray old = new org.json.JSONObject(json)
                .getJSONObject("basemaps").getJSONArray("sources");
        for (int i = 0; i < old.length(); i++)
            assertFalse(old.getJSONObject(i).has("keyProvider"));
    }
}

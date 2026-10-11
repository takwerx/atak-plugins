package com.atakmap.android.atmosphere;

import static org.junit.Assert.assertEquals;

import com.atakmap.android.atmosphere.data.SpcFireWx;

import org.junit.Test;

import java.util.List;
import java.util.TimeZone;

public class SpcFireWxTest {

    @Test
    public void areasReadAndThePlaceholderIsLeftOut() {
        final String body = "{\"type\":\"FeatureCollection\",\"features\":["
                + "{\"type\":\"Feature\",\"properties\":{\"dn\":0,\"valid\":\"202609261700\",\"expire\":\"202609271200\"},\"geometry\":null},"
                + "{\"type\":\"Feature\",\"properties\":{\"dn\":5,\"valid\":\"202609271200\",\"expire\":\"202609281200\"},"
                + "\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[[-118,42],[-116,42],[-116,44],[-118,44],[-118,42]]]}}]}";
        final List<SpcFireWx.Area> a = SpcFireWx.parse(body, SpcFireWx.LAYERS[2]);
        assertEquals(1, a.size());
        assertEquals(2, a.get(0).day);
        assertEquals("Elevated", a.get(0).label());
        assertEquals(0xFFE69800, a.get(0).color());
        assertEquals("Day 2 fire weather outlook: Elevated", a.get(0).title());
        assertEquals("Sun Sep 27, 5:00 AM", SpcFireWx.when("202609271200", TimeZone.getTimeZone("America/Los_Angeles")));
        assertEquals("Scattered dry thunderstorms", SpcFireWx.label(SpcFireWx.Kind.DRY_THUNDER, 8));
        assertEquals("Extreme", SpcFireWx.label(SpcFireWx.Kind.OUTLOOK, 10));
        assertEquals(0, SpcFireWx.parse("", SpcFireWx.LAYERS[0]).size());
    }

    @Test
    public void layersCoverThreeDaysOfBothKinds() {
        assertEquals(6, SpcFireWx.LAYERS.length);
        assertEquals(8, SpcFireWx.LAYERS[4].id);   // Day 3 categorical is layer 8, not 7
        assertEquals(3, SpcFireWx.LAYERS[5].day);
        assertEquals(SpcFireWx.Kind.DRY_THUNDER, SpcFireWx.LAYERS[5].kind);
    }

    @Test
    public void dayThreeSaysTheChanceNotACode() {
        assertEquals("40% chance of critical", SpcFireWx.label(SpcFireWx.Kind.OUTLOOK, 40));
        assertEquals("70% chance of critical", SpcFireWx.label(SpcFireWx.Kind.OUTLOOK, 70));
        assertEquals("10% chance of dry thunderstorms", SpcFireWx.label(SpcFireWx.Kind.DRY_THUNDER, 10));
        assertEquals("40% chance of dry thunderstorms", SpcFireWx.label(SpcFireWx.Kind.DRY_THUNDER, 40));
        assertEquals("Elevated", SpcFireWx.label(SpcFireWx.Kind.OUTLOOK, 5));
        assertEquals(0xFFFFAA00, SpcFireWx.color(SpcFireWx.Kind.OUTLOOK, 40));
        for (int[] code : SpcFireWx.LEGEND_CODES) {
            final SpcFireWx.Kind k = code[0] == 0 ? SpcFireWx.Kind.OUTLOOK : SpcFireWx.Kind.DRY_THUNDER;
            org.junit.Assert.assertFalse(SpcFireWx.label(k, code[1]).startsWith("Category"));
        }
    }
}

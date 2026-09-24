package com.atakmap.android.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.atmosphere.data.AirNow;
import com.atakmap.android.atmosphere.data.AirNow.Category;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Calendar;
import java.util.TimeZone;

/**
 * EPA AirNow's latest AQI contours against a real response: the whole country,
 * 2026-09-24 about 05Z, simplified to 0.05 degree for size. 91 polygons, bands 1 to 4
 * present, an Unhealthy area over the Inland Empire cut out of the Sensitive Groups
 * band around it.
 */
public class AirNowTest {

    private static AirNow.Contours contours() throws Exception {
        return AirNow.parse(new String(Files.readAllBytes(
                new File("src/test/resources/airnow_contours.geojson").toPath()),
                StandardCharsets.UTF_8));
    }

    @Test
    public void readsEveryBandOfTheCountry() throws Exception {
        final AirNow.Contours c = contours();
        assertEquals(91, c.contours.size());
        assertFalse(c.truncated);
        int good = 0, unhealthy = 0;
        for (AirNow.Contour k : c.contours) {
            if (k.category == Category.GOOD) good++;
            if (k.category == Category.UNHEALTHY) unhealthy++;
        }
        assertEquals(47, good);
        assertEquals(2, unhealthy);
    }

    @Test
    public void aPointIsInOneBandBecauseTheHolesAreKept() throws Exception {
        final AirNow.Contours c = contours();
        // Riverside sits in the Unhealthy polygon, which is a HOLE in the Sensitive
        // Groups polygon around it. Without the holes it would be in both.
        int holding = 0;
        for (AirNow.Contour k : c.contours)
            if (k.contains(34.00, -117.40))
                holding++;
        assertEquals(1, holding);
        assertEquals(Category.UNHEALTHY, c.at(34.00, -117.40));
        assertEquals(Category.SENSITIVE, c.at(35.4, -118.8));    // near Bakersfield
        assertEquals(Category.MODERATE, c.at(33.70, -117.70));   // Orange County
        assertEquals(Category.GOOD, c.at(39.74, -104.99));       // Denver
        assertNull(c.at(30, -40));                               // mid-Atlantic
    }

    @Test
    public void theStampIsEasternTimeWrittenAsUtc() throws Exception {
        final AirNow.Contours c = contours();
        assertEquals(1790208000L, c.unixtime);                   // "2026-09-24 00:00Z"
        final Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        utc.clear();
        utc.set(2026, Calendar.SEPTEMBER, 24, 5, 10, 0);
        final long now = utc.getTimeInMillis();
        utc.set(2026, Calendar.SEPTEMBER, 24, 4, 0, 0);
        // 00:00 on the Eastern clock in daylight time is 04:00Z, which is the hour
        // EPA's monitor layer carried at the same moment.
        assertEquals(utc.getTimeInMillis(), AirNow.observedAt(c.unixtime, now));
    }

    @Test
    public void aTrueUtcStampIsNotPushedIntoTheFuture() {
        final Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        utc.clear();
        utc.set(2026, Calendar.SEPTEMBER, 24, 4, 0, 0);
        final long stampMs = utc.getTimeInMillis();
        // Read as Eastern it would be 08:00Z, three hours ahead of this clock.
        utc.set(2026, Calendar.SEPTEMBER, 24, 5, 0, 0);
        assertEquals(stampMs, AirNow.observedAt(stampMs / 1000, utc.getTimeInMillis()));
        assertEquals(0, AirNow.observedAt(0, utc.getTimeInMillis()));
    }

    @Test
    public void theStampQueryIsReadForItsNewestValue() {
        assertEquals(1790208000L, AirNow.parseStamp(
                "{\"features\":[{\"attributes\":{\"Unixtime\":1790204400}},"
                        + "{\"attributes\":{\"Unixtime\":1790208000}}]}"));
        assertEquals(0, AirNow.parseStamp("not json"));
    }

    @Test
    public void theCategoriesAreEpasSix() {
        assertEquals(Category.GOOD, Category.of(1));
        assertEquals(Category.HAZARDOUS, Category.of(6));
        assertNull(Category.of(0));
        assertNull(Category.of(7));
        assertEquals("101 to 150", Category.SENSITIVE.range);
        assertEquals(0xFFFF7E00, Category.SENSITIVE.color);
        assertTrue(Category.VERY_UNHEALTHY.meaning.startsWith("Health alert"));
    }

    @Test
    public void theRequestsAreEpasAndCarryNoPosition() {
        assertTrue(AirNow.contoursUrl().startsWith("https://services.arcgis.com/cJ9YHowT8TU7DUyn/"));
        assertTrue(AirNow.contoursUrl().contains("where=1%3D1"));
        assertFalse(AirNow.contoursUrl().contains("geometry="));
        assertTrue(AirNow.stampUrl().contains("returnGeometry=false"));
    }
}

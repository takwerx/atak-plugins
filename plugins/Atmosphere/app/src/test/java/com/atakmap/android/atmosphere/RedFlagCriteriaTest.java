package com.atakmap.android.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.atmosphere.data.RedFlag;
import com.atakmap.android.atmosphere.data.RedFlagCriteria;

import org.junit.Test;

/** The California table, read the way the appendix is written. */
public class RedFlagCriteriaTest {

    @Test
    public void rangesExpandAndZonesArePadded() {
        assertNotNull(RedFlagCriteria.forZoneId("CA340"));
        assertNotNull(RedFlagCriteria.forZoneId("CA353"));
        assertNotNull(RedFlagCriteria.forZoneId("CA006".replace("006", "548")));
        assertNull(RedFlagCriteria.forZoneId("CA999"));
        assertNull(RedFlagCriteria.forZoneId(null));
    }

    @Test
    public void losAngelesHasTwoTiers() {
        final RedFlag.Criteria lox = RedFlagCriteria.forZoneId("CA548");
        assertEquals(2, lox.legs.length);
        // The drier tier at the lower wind: RH 9, sustained 16 -- Red Flag.
        assertEquals(RedFlag.CRITICAL, RedFlag.state(9, 16, Double.NaN, lox));
        // RH 14 needs the higher wind: sustained 20 is only one side of it.
        assertEquals(RedFlag.NEAR, RedFlag.state(14, 20, Double.NaN, lox));
        // ... and gusts to 36 complete it.
        assertEquals(RedFlag.CRITICAL, RedFlag.state(14, 20, 36, lox));
        assertEquals(RedFlag.BELOW, RedFlag.state(40, 5, 8, lox));
        assertTrue(lox.source.contains("Los Angeles"));
    }

    @Test
    public void renoHasNoSustainedFloor() {
        final RedFlag.Criteria rev = RedFlagCriteria.forZoneId("CA272");
        // Sustained 40 with no gust reported is not Red Flag in a gust-only zone...
        assertEquals(RedFlag.NEAR, RedFlag.state(12, 40, Double.NaN, rev));
        // ... and a 30 mph gust is.
        assertEquals(RedFlag.CRITICAL, RedFlag.state(19, 12, 30, rev));
        assertEquals("Humidity 20% or less with gusts 30 mph or more", rev.describe());
    }

    @Test
    public void theCommonPairReadsTheStrongestWind() {
        // The 2-argument rule, as the layer used it before there were zones.
        assertEquals(RedFlag.CRITICAL, RedFlag.state(15, 25));
        assertEquals(RedFlag.NEAR, RedFlag.state(15, 24));
        assertEquals("Humidity 15% or less with wind 25 mph or more or gusts 25 mph or more",
                RedFlag.Criteria.NATIONAL.describe());
    }

    @Test
    public void theNorthernMatrixIsReadRowByRow() {
        final RedFlag.Criteria mtr = RedFlagCriteria.forZoneId("CA516");
        assertEquals(4, mtr.legs.length);
        // Row 1: 40% humidity needs 30 mph sustained.
        assertEquals(RedFlag.NEAR, RedFlag.state(40, 25, 45, mtr));
        assertEquals(RedFlag.CRITICAL, RedFlag.state(40, 30, Double.NaN, mtr));
        // Row 3: 15% humidity at 12 mph.
        assertEquals(RedFlag.CRITICAL, RedFlag.state(15, 12, Double.NaN, mtr));
        assertEquals(RedFlag.NEAR, RedFlag.state(15, 11, Double.NaN, mtr));
        // Row 4: 7% at 6 mph. Gusts count for nothing here.
        assertEquals(RedFlag.CRITICAL, RedFlag.state(7, 6, Double.NaN, mtr));
        assertEquals(RedFlag.NEAR, RedFlag.state(7, 3, 50, mtr));
        // Above every row.
        assertEquals(RedFlag.NEAR, RedFlag.state(50, 35, Double.NaN, mtr));
        // The two zones the appendix skips.
        assertNull(RedFlagCriteria.forZoneId("CA515"));
    }

    @Test
    public void greatBasinReadsTheMapsColors() {
        // Boise's forest zones are purple (gusts 25), its BLM zones green (gusts 30).
        assertEquals(RedFlag.CRITICAL, RedFlag.state(15, 5, 25, RedFlagCriteria.forZoneId("ID401")));
        assertEquals(RedFlag.NEAR, RedFlag.state(15, 5, 25, RedFlagCriteria.forZoneId("ID400")));
        assertEquals(RedFlag.CRITICAL, RedFlag.state(15, 5, 30, RedFlagCriteria.forZoneId("ID400")));
        // Riverton takes sustained or gusts at 25.
        assertEquals(RedFlag.CRITICAL, RedFlag.state(12, 25, Double.NaN, RedFlagCriteria.forZoneId("WY415")));
        // Las Vegas: sustained 20 or gusts 35.
        assertEquals(RedFlag.CRITICAL, RedFlag.state(10, 20, Double.NaN, RedFlagCriteria.forZoneId("NV462")));
        assertEquals(RedFlag.NEAR, RedFlag.state(10, 19, 34, RedFlagCriteria.forZoneId("NV462")));
    }

    @Test
    public void anOfficeRuleAppliesToItsStatesOnly() {
        // Portland's rule reaches an Oregon zone with no entry of its own...
        final RedFlag.Criteria pqr = RedFlagCriteria.forZone("OR606", "PQR");
        assertNotNull(pqr);
        assertEquals(RedFlag.CRITICAL, RedFlag.state(25, 10, Double.NaN, pqr));    // day leg
        assertEquals(RedFlag.CRITICAL, RedFlag.state(33, 5, 25, pqr));             // night leg
        assertEquals(RedFlag.NEAR, RedFlag.state(33, 5, 22, pqr));
        // ... but a zone's own entry wins over it ...
        assertTrue(RedFlagCriteria.forZone("OR604", "PQR").source.contains("valley"));
        // ... and an office that spans two plans keeps them apart: Boise's Oregon
        // zones use the Northwest matrix, its Idaho zones the Great Basin map.
        assertTrue(RedFlagCriteria.forZone("OR636", "BOI").source.contains("matrix"));
        assertTrue(RedFlagCriteria.forZone("ID401", "BOI").source.contains("Great Basin"));
        // Midland's New Mexico zones follow a plan that is not loaded.
        assertNull(RedFlagCriteria.forZone("NM117", "MAF"));
        assertNull(RedFlagCriteria.forZone("CO205", "GJT"));
    }

    @Test
    public void lessThanIsOneBelow() {
        // Medford 616: "Min RH < 15%": 14 qualifies, 15 does not.
        final RedFlag.Criteria mfr = RedFlagCriteria.forZoneId("OR616");
        assertEquals(RedFlag.CRITICAL, RedFlag.state(14, 10, Double.NaN, mfr));
        assertEquals(RedFlag.NEAR, RedFlag.state(15, 10, Double.NaN, mfr));
        // Spokane basin: "exceeding 15 mph" with RH "less than 15%".
        final RedFlag.Criteria otx = RedFlagCriteria.forZoneId("WA706");
        assertEquals(RedFlag.CRITICAL, RedFlag.state(14, 16, Double.NaN, otx));
        assertEquals(RedFlag.NEAR, RedFlag.state(14, 15, Double.NaN, otx));
    }

    @Test
    public void sanDiegoIsTheSupersetForTheSharedDesertZones() {
        assertTrue(RedFlagCriteria.forZoneId("CA261").source.contains("San Diego"));
        assertTrue(RedFlagCriteria.forZoneId("CA281").source.contains("Reno"));
    }
}

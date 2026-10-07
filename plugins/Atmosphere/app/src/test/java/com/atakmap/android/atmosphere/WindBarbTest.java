
package com.atakmap.android.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.atmosphere.data.WindBarb;

import org.junit.Test;

public class WindBarbTest {

    private static void barb(double knots, int flags, int fulls, int halves) {
        final WindBarb.Feathers f = WindBarb.of(knots);
        assertEquals(knots + " kt: triangles", flags, f.flags);
        assertEquals(knots + " kt: full feathers", fulls, f.fulls);
        assertEquals(knots + " kt: half feathers", halves, f.halves);
    }

    @Test
    public void theTextbookBarbs() {
        barb(5, 0, 0, 1);
        barb(10, 0, 1, 0);
        barb(15, 0, 1, 1);
        barb(20, 0, 2, 0);
        barb(25, 0, 2, 1);
        barb(45, 0, 4, 1);
        barb(50, 1, 0, 0);
        barb(65, 1, 1, 1);
        barb(100, 2, 0, 0);
        barb(105, 2, 0, 1);
    }

    @Test
    public void aBarbReadsBackTheSpeedItWasDrawnFor() {
        // The whole risk with this symbol is that it is legible, confident and wrong.
        for (int kt = 5; kt <= 150; kt += 5)
            assertEquals(kt, WindBarb.of(kt).knots());
    }

    @Test
    public void theSpeedIsRoundedToTheNearestFive() {
        assertEquals(10, WindBarb.of(12).knots());
        assertEquals(15, WindBarb.of(13).knots());
        assertEquals(15, WindBarb.of(17.4).knots());
        assertEquals(20, WindBarb.of(17.6).knots());
    }

    @Test
    public void calmIsTheSymbolAloneAndNeverAStaff() {
        assertTrue(WindBarb.isCalm(0));
        assertTrue(WindBarb.isCalm(1));
        assertTrue(WindBarb.isCalm(2.4));
        // A station that reported no wind at all is calm, not zero-with-a-barb.
        assertTrue(WindBarb.isCalm(Double.NaN));
        assertFalse(WindBarb.isCalm(3));
        barb(0, 0, 0, 0);
        barb(Double.NaN, 0, 0, 0);
    }

    @Test
    public void aRawsGustInMilesPerHourBecomesTheRightBarb() {
        // 35 mph, which is a Red Flag wind in most places, is 30 knots: three full
        // feathers and nothing else.
        barb(35 / 1.15078, 0, 3, 0);
        // 60 mph is 52 knots, which rounds to 50: one triangle.
        barb(60 / 1.15078, 1, 0, 0);
    }
}

package com.atakmap.android.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.atmosphere.data.Favorites;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/** The saved-places list survives a round trip through its preference string. */
public class FavoritesTest {

    @Test
    public void roundTrip() {
        final List<Favorites.Place> in = Arrays.asList(
                new Favorites.Place("Corona, CA", 33.835, -117.573),
                new Favorites.Place("ICP", 34.1, -118.2));
        final List<Favorites.Place> out = Favorites.decode(Favorites.encode(in));
        assertEquals(2, out.size());
        assertEquals("Corona, CA", out.get(0).name);
        assertEquals(33.835, out.get(0).latitude, 1e-9);
        assertEquals(-117.573, out.get(0).longitude, 1e-9);
        assertEquals("ICP", out.get(1).name);
    }

    @Test
    public void damagedEntriesAreSkippedNotFatal() {
        final List<Favorites.Place> out = Favorites.decode(
                "[{\"name\":\"ok\",\"lat\":1,\"lon\":2},{\"name\":\"\",\"lat\":1,\"lon\":2},"
                + "{\"name\":\"no point\"},7]");
        assertEquals(1, out.size());
        assertEquals("ok", out.get(0).name);
        assertTrue(Favorites.decode(null).isEmpty());
        assertNull(Favorites.decode("not json"));
    }
}

package com.atakmap.android.mapdepot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * The key rules for base maps whose tile server wants the operator's own API
 * key (CARTO): what counts as a key, and how it goes into a source's XML. The
 * rest of the feature (prompt, preferences, Add) needs ATAK's MapView and is
 * checked on the emulator.
 */
public class BaseMapKeyTest {

    private static final String TEMPLATE = "<customMapSource>"
            + "<url>https://{$serverpart}.basemaps.cartocdn.com/light_all/"
            + "{$z}/{$x}/{$y}.png?key={$key}</url></customMapSource>";

    @Test
    public void realisticKeysAreAccepted() {
        assertTrue(BaseMapInstaller.isKeyShape("abcd1234"));
        assertTrue(BaseMapInstaller.isKeyShape("Ab9._~-Zz0123"));
        assertTrue(BaseMapInstaller.isKeyShape(repeat('k', 256)));
    }

    @Test
    public void tooShortOrTooLongIsRefused() {
        assertFalse(BaseMapInstaller.isKeyShape("abcdefg"));
        assertFalse(BaseMapInstaller.isKeyShape(repeat('k', 257)));
        assertFalse(BaseMapInstaller.isKeyShape(""));
        assertFalse(BaseMapInstaller.isKeyShape(null));
    }

    /** The key lands inside a URL inside XML, so none of these may get in. */
    @Test
    public void charactersThatBreakAUrlOrXmlAreRefused() {
        for (String bad : new String[] { "abcd1234&x=1", "abcd1234<", "abcd1234\"",
                "abcd 1234", "abcd1234\n", "abcd1234#", "abcd1234%20", "abcd/1234",
                "abcd1234{$z}" }) {
            assertFalse("accepted: " + bad, BaseMapInstaller.isKeyShape(bad));
        }
    }

    @Test
    public void slotIsFilledWithTheKey() {
        final String xml = BaseMapInstaller.fillKey(TEMPLATE, "abcd1234");
        assertTrue(xml.contains("light_all/{$z}/{$x}/{$y}.png?key=abcd1234<"));
        assertFalse(xml.contains(BaseMapInstaller.KEY_SLOT));
    }

    @Test
    public void everySlotIsFilled() {
        final String twice = "<a>{$key}</a><b>{$key}</b>";
        assertEquals("<a>abcd1234</a><b>abcd1234</b>",
                BaseMapInstaller.fillKey(twice, "abcd1234"));
    }

    /** A keyed entry with no slot must not install a URL with no key in it. */
    @Test
    public void templateWithoutASlotIsRefused() {
        assertNull(BaseMapInstaller.fillKey(
                "<url>https://x.example/{$z}/{$x}/{$y}.png</url>", "abcd1234"));
    }

    /** Every XML under contrib/carto carries the slot, or the key never lands. */
    @Test
    public void everyContributedCartoSourceHasTheSlot() throws Exception {
        final File dir = new File("contrib/carto");
        // gradle runs unit tests from the app module
        final File d = dir.isDirectory() ? dir : new File("../contrib/carto");
        final File[] xmls = d.listFiles((x, n) -> n.endsWith(".xml"));
        assertEquals(9, xmls.length);
        for (File f : xmls) {
            final String xml = new String(Files.readAllBytes(f.toPath()),
                    StandardCharsets.UTF_8);
            assertTrue(f.getName(), xml.contains("?key=" + BaseMapInstaller.KEY_SLOT));
            assertTrue(f.getName(),
                    BaseMapInstaller.fillKey(xml, "abcd1234").contains("?key=abcd1234<"));
        }
    }

    private static String repeat(char c, int n) {
        final StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++)
            b.append(c);
        return b.toString();
    }
}

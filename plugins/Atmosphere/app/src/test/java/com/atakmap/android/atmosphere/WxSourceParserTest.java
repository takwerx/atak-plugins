package com.atakmap.android.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.atmosphere.source.WxSourceDef;
import com.atakmap.android.atmosphere.source.WxSourceParser;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * The parser is the plugin's contract with whoever writes a source file. Its job is to
 * either load a definition or say exactly what is wrong with it — a file that is quietly
 * ignored is the failure mode this design most has to avoid.
 */
public class WxSourceParserTest {

    private static final File ASSETS = new File("src/main/assets/wx_sources");

    private static String read(String name) throws Exception {
        return new String(Files.readAllBytes(new File(ASSETS, name).toPath()),
                StandardCharsets.UTF_8);
    }

    /** Every definition we ship must parse with no errors and no warnings. */
    @Test
    public void bundledSourcesAreClean() throws Exception {
        final File[] files = ASSETS.listFiles();
        assertNotNull("bundled sources not found at " + ASSETS.getAbsolutePath(), files);
        assertTrue("expected bundled source definitions", files.length > 0);

        for (File f : files) {
            if (!f.getName().endsWith(".json"))
                continue;
            final WxSourceParser.Result r = WxSourceParser.parse(read(f.getName()),
                    WxSourceDef.Origin.BUNDLED, f.getName());
            assertEquals(f.getName() + " errors: " + r.errors, 0, r.errors.size());
            assertEquals(f.getName() + " warnings: " + r.warnings, 0, r.warnings.size());
            assertTrue(r.ok());
        }
    }

    @Test
    public void openMeteoDefinitionShape() throws Exception {
        final WxSourceParser.Result r = WxSourceParser.parse(read("open-meteo.json"),
                WxSourceDef.Origin.BUNDLED, "open-meteo.json");
        assertTrue(r.ok());
        assertEquals(WxSourceDef.Layout.COLUMNS, r.def.layout);
        assertTrue(r.def.hasCurrent());
        assertFalse(r.def.hasResolveStep());
        assertEquals("[api.open-meteo.com]", r.def.hosts().toString());
        assertNotNull(r.def.param("wind_gusts_10m"));
    }

    @Test
    public void nwsDefinitionShape() throws Exception {
        final WxSourceParser.Result r = WxSourceParser.parse(read("nws.json"),
                WxSourceDef.Origin.BUNDLED, "nws.json");
        assertTrue(r.ok());
        assertEquals(WxSourceDef.Layout.GRID, r.def.layout);
        assertEquals("properties", r.def.recordsPath);
        assertNotNull(r.def.param("twentyFootWindSpeed"));
        assertTrue(r.def.param("twentyFootWindSpeed").defaultOn);
        assertFalse(r.def.param("windSpeed").defaultOn);
        assertTrue(r.def.hasResolveStep());
        // A two-step source has no "current" block; the first forecast step stands in.
        assertFalse(r.def.hasCurrent());
        assertEquals("application/geo+json", r.def.headers.get("Accept"));
    }

    @Test
    public void plaintextUrlIsRefused() {
        final WxSourceParser.Result r = parseWith("\"requestUrl\": \"http://example.com/{lat}/{lon}\"");
        assertFalse(r.ok());
        assertTrue(r.errors.toString().contains("must be https"));
    }

    @Test
    public void unknownPlaceholderIsAnError() {
        final WxSourceParser.Result r = parseWith(
                "\"requestUrl\": \"https://example.com/?p={lat}&q={lon}&z={zoom}\"");
        assertFalse(r.ok());
        assertTrue(r.errors.toString().contains("{zoom}"));
    }

    @Test
    public void wrongSchemaVersionIsRefusedWithTheVersionWeRead() {
        final String json = "{\"schemaVersion\": 99, \"sourceId\": \"x\","
                + "\"displayName\": \"X\"}";
        final WxSourceParser.Result r = WxSourceParser.parse(json,
                WxSourceDef.Origin.EXTERNAL, "x.json");
        assertFalse(r.ok());
        assertTrue(r.errors.toString().contains("version 1"));
    }

    @Test
    public void parameterWithoutAPathIsRefused() {
        final String json = "{\"schemaVersion\": 1, \"sourceId\": \"x\","
                + "\"displayName\": \"X\","
                + "\"requestUrl\": \"https://example.com/?a={lat}&b={lon}\","
                + "\"parameters\": [{\"key\": \"t\", \"label\": \"T\"}]}";
        final WxSourceParser.Result r = WxSourceParser.parse(json,
                WxSourceDef.Origin.EXTERNAL, "x.json");
        assertFalse(r.ok());
        assertTrue(r.errors.toString().contains("currentPath"));
    }

    @Test
    public void badJsonNamesTheFile() {
        final WxSourceParser.Result r = WxSourceParser.parse("{ nope",
                WxSourceDef.Origin.EXTERNAL, "mine.json");
        assertFalse(r.ok());
        assertTrue(r.errors.get(0).startsWith("mine.json:"));
    }

    /**
     * The host is what the operator is shown before enabling a source and what the
     * consent is bound to; a URL the strict parser cannot name a host for is refused
     * rather than shown as "its provider" and sent anyway (security review, 2026-09-27).
     */
    @Test
    public void unreadableHostIsRefused() {
        assertFalse(parseWith("\"requestUrl\": \"https://collector.example/fore cast?lat={lat}&lon={lon}\"").ok());
        assertFalse(parseWith("\"requestUrl\": \"https://wx_api.example/f?lat={lat}&lon={lon}\"").ok());
        assertFalse(parseWith("\"requestUrl\": \"https:///f?lat={lat}&lon={lon}\"").ok());
        final WxSourceParser.Result r = parseWith("\"requestUrl\": \"https://a b.example/{lat}/{lon}\"");
        assertTrue(r.errors.toString(), r.errors.toString().contains("no readable host"));
        final WxSourceParser.Result ok = parseWith("\"requestUrl\": \"https://Api.Example.com/f?lat={lat}&lon={lon}\"");
        assertTrue(ok.errors.toString(), ok.ok());
        assertEquals("api.example.com", ok.def.hostsKey());
    }

    /** A URL a provider hands back is fetched only on a host the operator consented to. */
    @Test
    public void resolvedUrlMustBeOnAConsentedHost() {
        final WxSourceParser.Result r = parseWith(
                "\"resolveUrl\": \"https://api.example.gov/points/{lat},{lon}\", \"resolvePath\": \"properties.forecast\","
                + "\"requestUrl\": \"https://cdn.example.gov/f?lat={lat}&lon={lon}\"");
        assertTrue(r.errors.toString(), r.ok());
        assertEquals("api.example.gov,cdn.example.gov", r.def.hostsKey());
        assertTrue(r.def.trusts("https://api.example.gov/gridpoints/SGX/1,2/forecast"));
        assertTrue(r.def.trusts("https://CDN.example.gov/anything"));
        assertFalse(r.def.trusts("https://collector.attacker.example/x?lat=33.5"));
        assertFalse(r.def.trusts("http://api.example.gov/plain"));
        assertFalse(r.def.trusts("https://api.example.gov.attacker.example/"));
        // userinfo before the real host; written in pieces so the publish scrub
        // does not read the literal as an email address
        assertFalse(r.def.trusts("https://api.example.gov" + '@' + "attacker.example/"));
        assertFalse(r.def.trusts(""));
        assertFalse(r.def.trusts(null));
        assertEquals(null, WxSourceDef.hostOf("https://wx_api.example/"));
        assertEquals("api.example.gov", WxSourceDef.hostOf("https://api.example.gov/points/{lat},{lon}"));
    }

    private static WxSourceParser.Result parseWith(String urlField) {
        final String json = "{\"schemaVersion\": 1, \"sourceId\": \"x\","
                + "\"displayName\": \"X\"," + urlField + ","
                + "\"parameters\": [{\"key\": \"t\", \"label\": \"T\","
                + "\"currentPath\": \"t\"}]}";
        return WxSourceParser.parse(json, WxSourceDef.Origin.EXTERNAL, "x.json");
    }
}

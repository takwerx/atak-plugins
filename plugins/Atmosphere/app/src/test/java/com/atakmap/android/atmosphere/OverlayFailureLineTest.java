package com.atakmap.android.atmosphere;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * ops #1384: a layer that fails to fetch must show a sentence ({@code Http.line}), never the
 * bare fragment {@code Http.describe} returns ("could not connect") and never a raw host error.
 * Air Temperature (RainOverlay), Smoke, Waves and Radar once passed the failure straight to
 * their status line. The emulator showed it, so this keeps it from coming back.
 */
public class OverlayFailureLineTest {

    @Test
    public void noOverlayPrintsTheBareFailureAsItsStatusLine() throws Exception {
        final File dir = new File("src/main/java/com/atakmap/android/atmosphere/overlay");
        final File[] files = dir.listFiles((d, name) -> name.endsWith(".java"));
        assertTrue("overlay folder not found from " + new File(".").getAbsolutePath(),
                files != null && files.length > 20);
        final List<String> bad = new ArrayList<>();
        for (File f : files) {
            final List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                final String t = lines.get(i).trim();
                if (t.equals("status(error);"))
                    bad.add(f.getName() + ":" + (i + 1));
            }
        }
        assertTrue("these pass the failure text straight to the status line, use Http.line: "
                + bad, bad.isEmpty());
    }
}

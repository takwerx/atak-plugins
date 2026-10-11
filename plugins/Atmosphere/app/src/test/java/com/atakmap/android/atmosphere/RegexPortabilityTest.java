package com.atakmap.android.atmosphere;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Android compiles regular expressions with ICU, the JVM with OpenJDK's engine, and
 * they disagree on a bare closing brace outside a character class: OpenJDK takes it as
 * a literal, ICU throws {@code PatternSyntaxException}. A pattern in a static
 * initializer then kills the class, and with it ATAK, the first time it is touched on
 * a device, while every JVM test passes. This scans the main sources for regex
 * literals and fails on the construct the JVM would let through.
 */
public class RegexPortabilityTest {

    private static final Pattern REGEX_LITERAL = Pattern.compile(
            "(?:Pattern\\.compile|\\.matches|\\.replaceAll|\\.replaceFirst|\\.split)\\(\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    @Test
    public void noBareClosingBraceOutsideCharacterClass() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Paths.get("src/main/java"))) {
            for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                String src = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
                Matcher m = REGEX_LITERAL.matcher(src);
                while (m.find()) {
                    String regex = unescapeJava(m.group(1));
                    if (hasBareClosingBrace(regex)) {
                        offenders.add(f.getFileName() + ": " + m.group(1));
                    }
                }
            }
        }
        assertTrue("regex literals with a bare '}' outside [...] (ICU rejects them on Android): "
                + offenders, offenders.isEmpty());
    }

    /** True when a '}' appears unescaped and outside a character class. */
    static boolean hasBareClosingBrace(String regex) {
        boolean inClass = false;
        for (int i = 0; i < regex.length(); i++) {
            char c = regex.charAt(i);
            if (c == '\\') { i++; continue; }
            if (inClass) { if (c == ']') inClass = false; continue; }
            if (c == '[') { inClass = true; continue; }
            if (c == '}') {
                // a '}' closing a quantifier like {0,63} is fine
                int open = regex.lastIndexOf('{', i);
                if (open < 0 || !regex.substring(open + 1, i).matches("\\d+(,\\d*)?")) return true;
            }
        }
        return false;
    }

    /** Only the escapes a Java string literal needs for this purpose. */
    static String unescapeJava(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                if (n == '\\') out.append('\\');
                else if (n == '"') out.append('"');
                else if (n == 'n') out.append('\n');
                else if (n == 't') out.append('\t');
                else { out.append('\\').append(n); }
            } else out.append(c);
        }
        return out.toString();
    }

    @Test
    public void detectorKnowsTheDifference() {
        assertTrue(hasBareClosingBrace("\\{([^}]*)}"));           // the one that killed ATAK
        assertTrue(!hasBareClosingBrace("\\{([^}]*)\\}"));         // the fix
        assertTrue(!hasBareClosingBrace("[a-z0-9][a-z0-9-]{0,63}")); // quantifier brace
        assertTrue(!hasBareClosingBrace("[}]"));                   // inside a class
    }
}

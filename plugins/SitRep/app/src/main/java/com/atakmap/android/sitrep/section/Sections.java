package com.atakmap.android.sitrep.section;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Every section, in report order (PLAN section 4). */
public final class Sections {

    private Sections() {
    }

    private static final List<Section> ALL;

    static {
        final List<Section> s = new ArrayList<>();
        s.add(new Structures());
        ALL = Collections.unmodifiableList(s);
    }

    public static List<Section> all() {
        return ALL;
    }

    public static Section byKey(String key) {
        for (Section s : ALL)
            if (s.key.equals(key))
                return s;
        return null;
    }
}

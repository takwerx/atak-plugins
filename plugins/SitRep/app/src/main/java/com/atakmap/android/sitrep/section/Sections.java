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
        s.add(new Weather());
        s.add(new Alerts());
        s.add(new Fires());
        s.add(new Evacuations());
        s.add(new Roads());
        s.add(new Airspace());
        s.add(new Cameras());
        s.add(new Declarations());
        s.add(new Terrain());
        s.add(new Flood());
        s.add(new Risk());
        s.add(new People());
        s.add(new Places());
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

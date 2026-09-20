package com.atakmap.android.mast;

import com.atakmap.android.cot.detail.CotDetailHandler;
import com.atakmap.comms.CommsMapComponent.ImportResult;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.Marker;
import com.atakmap.android.mast.feed.FeedPublisher;
import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.coremap.cot.event.CotEvent;

/**
 * Carries MAST's marker metadata through CoT as a {@code <mast .../>} detail: which
 * tile dropped it, how many (victims, deceased), which floor, the typed text (a room
 * number) and the feed it is in. FOBS's {@code FobsDetailHandler} is the model.
 *
 * <p>ATAK persists markers as CoT and rebuilds them from CoT on restart, and other
 * devices only ever see the CoT. The same facts are also in the marker's title, so a
 * client without MAST still reads "Victim 2 x3 F2"; the detail is what lets MAST on the
 * receiving phone filter by floor and sum the counts on the board. Small and additive:
 * an ATAK without the plugin ignores it and still draws the marker.
 */
public class MastDetailHandler extends CotDetailHandler {

    public static final String DETAIL = "mast";

    public static final String META_KIND = "mast_kind";
    public static final String META_COUNT = "mast_count";
    public static final String META_FLOOR = "mast_floor";
    public static final String META_TEXT = "mast_text";

    private static final String[] ATTRS = { META_KIND, META_COUNT, META_FLOOR, META_TEXT,
            FeedPublisher.META_FEED };
    private static final String[] NAMES = { "kind", "count", "floor", "text", "feed" };

    public MastDetailHandler() {
        super(DETAIL);
    }

    /** Only markers carry the tag; on anything else a peer's detail is ignored, not stored. */
    @Override
    public boolean isSupported(MapItem item, CotEvent event, CotDetail detail) {
        return item instanceof Marker;
    }

    @Override
    public ImportResult toItemMetadata(MapItem item, CotEvent event, CotDetail detail) {
        for (int i = 0; i < ATTRS.length; i++) {
            String v = detail.getAttribute(NAMES[i]);
            if (v != null)
                item.setMetaString(ATTRS[i], v);
        }
        return ImportResult.SUCCESS;
    }

    @Override
    public boolean toCotDetail(MapItem item, CotEvent event, CotDetail root) {
        if (!item.hasMetaValue(META_KIND))
            return false;
        CotDetail d = new CotDetail(DETAIL);
        for (int i = 0; i < ATTRS.length; i++) {
            String v = item.getMetaString(ATTRS[i], null);
            if (v != null)
                d.setAttribute(NAMES[i], v);
        }
        root.addChild(d);
        return true;
    }
}

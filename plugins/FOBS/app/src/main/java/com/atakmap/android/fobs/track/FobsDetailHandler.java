package com.atakmap.android.fobs.track;

import com.atakmap.android.cot.detail.CotDetailHandler;
import com.atakmap.android.drawing.mapItems.DrawingShape;
import com.atakmap.android.maps.MapItem;
import com.atakmap.comms.CommsMapComponent.ImportResult;
import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.coremap.cot.event.CotEvent;

/**
 * Carries FOBS metadata through CoT as a {@code <fobs .../>} detail.
 *
 * <p>ATAK persists shapes as CoT and rebuilds them from CoT on restart, and other
 * devices only ever see the CoT. Without this, a track would come back after a restart
 * as an anonymous dashed drawing and could not be cut or joined, and a teammate's
 * track would not be recognized as one. The detail is small and additive; an ATAK
 * without the plugin ignores it and still draws the shape.
 */
public class FobsDetailHandler extends CotDetailHandler {

    public static final String DETAIL = "fobs";

    private static final String[] ATTRS = {
            FobsShapes.META_KIND, FobsShapes.META_SOURCE, FobsShapes.META_ALTSRC,
            FobsShapes.META_RAW, FobsShapes.META_DROPPED,
            com.atakmap.android.fobs.feed.FeedPublisher.META_FEED
    };

    private static final String[] NAMES = { "kind", "source", "altsrc", "raw", "dropped", "feed" };

    public FobsDetailHandler() {
        super(DETAIL);
    }

    /**
     * Only drawing shapes carry the tag. A peer can put a {@code <fobs>} detail on any
     * CoT it sends; on anything but a shape it is ignored rather than stored.
     */
    @Override
    public boolean isSupported(MapItem item, CotEvent event, CotDetail detail) {
        return item instanceof DrawingShape;
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
        if (!item.hasMetaValue(FobsShapes.META_KIND))
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

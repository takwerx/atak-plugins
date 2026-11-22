package com.atakmap.android.signaldf.model;

/**
 * Which of the KrakenSDR's interfaces a bearing came from.
 *
 * <p>This rides on every bearing because the three are not equivalent: the XML
 * is written in every mode but carries VFO 0 only and no spectrum, the CSV
 * carries every active VFO and the spectrum but is absent in Kerberos App mode,
 * and the WebSocket is only fed when the radio is in Kraken Pro Local. What the
 * plugin can offer the operator depends on which one answered, so the pane has
 * to be able to say which one did.
 */
public enum FeedSource {

    /** {@code http://<host>:8081/doa.xml} -- the DF Aggregator XML. */
    XML("doa.xml"),

    /** {@code http://<host>:8081/DOA_value.html} -- the Kraken App CSV. */
    CSV("Kraken App CSV"),

    /** {@code ws://<host>:8021} -- the node middleware's live frames. Not in 0.1. */
    WEBSOCKET("WebSocket");

    private final String label;

    FeedSource(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}

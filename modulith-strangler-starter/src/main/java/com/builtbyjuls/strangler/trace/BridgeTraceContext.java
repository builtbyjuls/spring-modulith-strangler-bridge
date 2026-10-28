package com.builtbyjuls.strangler.trace;

/**
 * Immutable carrier for W3C distributed trace context and correlation identifier.
 */
public record BridgeTraceContext(
        String traceparent,
        String tracestate,
        String baggage,
        String correlationId
) {

    public static final BridgeTraceContext EMPTY = new BridgeTraceContext(null, null, null, null);

    /**
     * Returns true when all trace context fields are null or blank.
     */
    public boolean isEmpty() {
        return isBlank(traceparent) && isBlank(tracestate) && isBlank(baggage) && isBlank(correlationId);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}

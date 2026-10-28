package com.builtbyjuls.strangler.trace;

import java.util.UUID;

import org.slf4j.MDC;

/**
 * Thread-local and SLF4J MDC holder for W3C trace context and correlation identifiers.
 */
public final class BridgeTraceContextHolder {

    public static final String TRACEPARENT_HEADER = "traceparent";
    public static final String TRACESTATE_HEADER = "tracestate";
    public static final String BAGGAGE_HEADER = "baggage";
    public static final String CORRELATION_ID_HEADER = "X-Modulith-Correlation-Id";

    public static final String MDC_TRACEPARENT = "traceparent";
    public static final String MDC_TRACESTATE = "tracestate";
    public static final String MDC_BAGGAGE = "baggage";
    public static final String MDC_CORRELATION_ID = "correlationId";

    private static final ThreadLocal<BridgeTraceContext> CONTEXT_HOLDER = new ThreadLocal<>();

    private BridgeTraceContextHolder() {
    }

    /**
     * Resolves the current trace context by checking ThreadLocal first, falling back to SLF4J MDC.
     */
    public static BridgeTraceContext current() {
        BridgeTraceContext threadContext = CONTEXT_HOLDER.get();
        String traceparent = resolveValue(threadContext != null ? threadContext.traceparent() : null, MDC.get(MDC_TRACEPARENT));
        String tracestate = resolveValue(threadContext != null ? threadContext.tracestate() : null, MDC.get(MDC_TRACESTATE));
        String baggage = resolveValue(threadContext != null ? threadContext.baggage() : null, MDC.get(MDC_BAGGAGE));
        String correlationId = resolveValue(threadContext != null ? threadContext.correlationId() : null, MDC.get(MDC_CORRELATION_ID));
        return new BridgeTraceContext(traceparent, tracestate, baggage, correlationId);
    }

    /**
     * Captures current trace context, generating a new UUID correlationId if none exists.
     */
    public static BridgeTraceContext captureForOutbound() {
        BridgeTraceContext currentContext = current();
        String correlationId = (currentContext.correlationId() != null && !currentContext.correlationId().isBlank())
                ? currentContext.correlationId()
                : UUID.randomUUID().toString();
        return new BridgeTraceContext(
                currentContext.traceparent(),
                currentContext.tracestate(),
                currentContext.baggage(),
                correlationId
        );
    }

    /**
     * Sets ThreadLocal context and binds non-blank fields into SLF4J MDC, returning an AutoCloseable Scope
     * that restores the previous ThreadLocal and MDC state on close.
     */
    public static Scope openScope(BridgeTraceContext context) {
        BridgeTraceContext previousContext = CONTEXT_HOLDER.get();
        String prevTraceparent = MDC.get(MDC_TRACEPARENT);
        String prevTracestate = MDC.get(MDC_TRACESTATE);
        String prevBaggage = MDC.get(MDC_BAGGAGE);
        String prevCorrelationId = MDC.get(MDC_CORRELATION_ID);

        if (context != null) {
            CONTEXT_HOLDER.set(context);
            putMdcIfNonBlank(MDC_TRACEPARENT, context.traceparent());
            putMdcIfNonBlank(MDC_TRACESTATE, context.tracestate());
            putMdcIfNonBlank(MDC_BAGGAGE, context.baggage());
            putMdcIfNonBlank(MDC_CORRELATION_ID, context.correlationId());
        }

        return () -> {
            if (previousContext != null) {
                CONTEXT_HOLDER.set(previousContext);
            } else {
                CONTEXT_HOLDER.remove();
            }
            restoreMdc(MDC_TRACEPARENT, prevTraceparent);
            restoreMdc(MDC_TRACESTATE, prevTracestate);
            restoreMdc(MDC_BAGGAGE, prevBaggage);
            restoreMdc(MDC_CORRELATION_ID, prevCorrelationId);
        };
    }

    /**
     * Sets the explicit ThreadLocal trace context and populates MDC for non-blank fields.
     */
    public static void set(BridgeTraceContext context) {
        if (context != null) {
            CONTEXT_HOLDER.set(context);
            putMdcIfNonBlank(MDC_TRACEPARENT, context.traceparent());
            putMdcIfNonBlank(MDC_TRACESTATE, context.tracestate());
            putMdcIfNonBlank(MDC_BAGGAGE, context.baggage());
            putMdcIfNonBlank(MDC_CORRELATION_ID, context.correlationId());
        } else {
            clear();
        }
    }

    /**
     * Clears ThreadLocal trace context and associated SLF4J MDC keys.
     */
    public static void clear() {
        CONTEXT_HOLDER.remove();
        MDC.remove(MDC_TRACEPARENT);
        MDC.remove(MDC_TRACESTATE);
        MDC.remove(MDC_BAGGAGE);
        MDC.remove(MDC_CORRELATION_ID);
    }

    private static String resolveValue(String explicitValue, String mdcValue) {
        if (explicitValue != null && !explicitValue.isBlank()) {
            return explicitValue;
        }
        if (mdcValue != null && !mdcValue.isBlank()) {
            return mdcValue;
        }
        return null;
    }

    private static void putMdcIfNonBlank(String key, String value) {
        if (value != null && !value.isBlank()) {
            MDC.put(key, value);
        }
    }

    private static void restoreMdc(String key, String previousValue) {
        if (previousValue != null) {
            MDC.put(key, previousValue);
        } else {
            MDC.remove(key);
        }
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();

        Scope NOOP = () -> {};
    }
}

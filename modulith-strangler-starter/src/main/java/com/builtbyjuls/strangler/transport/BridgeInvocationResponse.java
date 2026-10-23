package com.builtbyjuls.strangler.transport;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Result payload returned from an inbound bridge invocation.
 *
 * @param result        serialized JSON return value (null if void or exception)
 * @param durationMs    remote execution duration in milliseconds
 * @param moduleName    target module that processed the invocation
 * @param error         error message if invocation failed
 * @param businessError whether the failure is an application domain exception rather than transport
 * @param exceptionType fully qualified class name of the thrown application exception
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BridgeInvocationResponse(
        JsonNode result,
        long durationMs,
        String moduleName,
        String error,
        boolean businessError,
        String exceptionType
) {
    public BridgeInvocationResponse {
        if (result != null && result.isNull()) {
            result = null;
        }
    }

    public BridgeInvocationResponse(JsonNode result, long durationMs, String moduleName, String error) {
        this(result, durationMs, moduleName, error, false, null);
    }

    public static BridgeInvocationResponse success(JsonNode result, long durationMs, String moduleName) {
        return new BridgeInvocationResponse(result, durationMs, moduleName, null, false, null);
    }

    public static BridgeInvocationResponse error(String error, long durationMs, String moduleName) {
        String resolvedError = (error != null && !error.isBlank()) ? error : "Unknown execution error";
        return new BridgeInvocationResponse(null, durationMs, moduleName, resolvedError, false, null);
    }

    public static BridgeInvocationResponse businessError(String error, String exceptionType, long durationMs, String moduleName) {
        String resolvedError = (error != null && !error.isBlank()) ? error : "Unknown business error";
        return new BridgeInvocationResponse(null, durationMs, moduleName, resolvedError, true, exceptionType);
    }
}

package com.builtbyjuls.strangler.transport;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Result payload returned from an inbound bridge invocation.
 *
 * @param result     serialized JSON return value (null if void or exception)
 * @param durationMs remote execution duration in milliseconds
 * @param moduleName target module that processed the invocation
 * @param error      error message if invocation failed
 */
public record BridgeInvocationResponse(
        JsonNode result,
        long durationMs,
        String moduleName,
        String error
) {
    public BridgeInvocationResponse {
        if (result != null && result.isNull()) {
            result = null;
        }
    }

    public static BridgeInvocationResponse success(JsonNode result, long durationMs, String moduleName) {
        return new BridgeInvocationResponse(result, durationMs, moduleName, null);
    }

    public static BridgeInvocationResponse error(String error, long durationMs, String moduleName) {
        String resolvedError = (error != null && !error.isBlank()) ? error : "Unknown execution error";
        return new BridgeInvocationResponse(null, durationMs, moduleName, resolvedError);
    }
}

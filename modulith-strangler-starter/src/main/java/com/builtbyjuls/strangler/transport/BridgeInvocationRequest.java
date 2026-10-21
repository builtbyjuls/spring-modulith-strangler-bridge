package com.builtbyjuls.strangler.transport;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Encapsulates an RPC invocation across the Spring Modulith strangler bridge.
 *
 * @param moduleName     target module name (e.g. metering)
 * @param interfaceName  target module interface name (e.g. com.builtbyjuls.grid.metering.MeteringApi)
 * @param methodName     method to invoke
 * @param parameterTypes fully qualified parameter type names
 * @param arguments      serialized JSON arguments
 */
public record BridgeInvocationRequest(
        String moduleName,
        String interfaceName,
        String methodName,
        List<String> parameterTypes,
        List<JsonNode> arguments
) {
    public BridgeInvocationRequest {
        parameterTypes = parameterTypes != null ? List.copyOf(parameterTypes) : List.of();
        arguments = arguments != null ? List.copyOf(arguments) : List.of();
    }
}

package com.builtbyjuls.strangler.transport;

/**
 * Thrown when an RPC invocation across the strangler bridge fails at transport or remote execution level.
 */
public class BridgeRemoteInvocationException extends RuntimeException {

    public BridgeRemoteInvocationException(String message) {
        super(message);
    }

    public BridgeRemoteInvocationException(String message, Throwable cause) {
        super(message, cause);
    }
}

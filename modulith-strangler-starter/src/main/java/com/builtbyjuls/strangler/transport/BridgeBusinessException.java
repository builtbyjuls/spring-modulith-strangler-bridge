package com.builtbyjuls.strangler.transport;

/**
 * Thrown when a remote module invocation fails due to an application or domain exception
 * rather than a transport or network failure.
 */
public class BridgeBusinessException extends RuntimeException {

    private final String moduleName;
    private final String exceptionType;
    private final String remoteMessage;

    public BridgeBusinessException(String moduleName, String exceptionType, String remoteMessage) {
        super("Remote business exception on module '" + moduleName + "' [" + exceptionType + "]: " + remoteMessage);
        this.moduleName = moduleName;
        this.exceptionType = exceptionType;
        this.remoteMessage = remoteMessage;
    }

    public String getModuleName() {
        return moduleName;
    }

    public String getExceptionType() {
        return exceptionType;
    }

    public String getRemoteMessage() {
        return remoteMessage;
    }
}

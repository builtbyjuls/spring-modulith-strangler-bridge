package com.builtbyjuls.strangler.resilience;

/**
 * Exception thrown when a caller thread exceeds the call budget for a module boundary method,
 * indicating a synchronous chatty loop or N+1 RPC anti-pattern.
 */
public class ChattyModuleBoundaryException extends RuntimeException {

    private final String moduleName;
    private final String interfaceName;
    private final String methodName;
    private final int callCount;
    private final long windowMs;

    public ChattyModuleBoundaryException(
            String moduleName,
            String interfaceName,
            String methodName,
            int callCount,
            long windowMs
    ) {
        super(String.format(
                "Chatty module boundary detected: %d invocations of '%s.%s' (module '%s') within %dms window. "
                        + "Consider refactoring to a batch operation (e.g. Collection/List) or using Spring Modulith @Externalized events.",
                callCount, interfaceName, methodName, moduleName, windowMs
        ));
        this.moduleName = moduleName;
        this.interfaceName = interfaceName;
        this.methodName = methodName;
        this.callCount = callCount;
        this.windowMs = windowMs;
    }

    public String getModuleName() {
        return moduleName;
    }

    public String getInterfaceName() {
        return interfaceName;
    }

    public String getMethodName() {
        return methodName;
    }

    public int getCallCount() {
        return callCount;
    }

    public long getWindowMs() {
        return windowMs;
    }
}

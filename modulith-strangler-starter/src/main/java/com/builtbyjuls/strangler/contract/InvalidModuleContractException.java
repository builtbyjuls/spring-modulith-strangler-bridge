package com.builtbyjuls.strangler.contract;

/**
 * Thrown when a module boundary interface method uses a parameter or return type that is unsafe
 * for remote strangler bridge serialization (e.g. leaked JPA entities or non-serializable streams).
 */
public class InvalidModuleContractException extends RuntimeException {

    private final Class<?> interfaceType;
    private final String methodName;
    private final Class<?> offendingType;

    public InvalidModuleContractException(String message) {
        super(message);
        this.interfaceType = null;
        this.methodName = null;
        this.offendingType = null;
    }

    public InvalidModuleContractException(String message, Class<?> interfaceType, String methodName, Class<?> offendingType) {
        super(message);
        this.interfaceType = interfaceType;
        this.methodName = methodName;
        this.offendingType = offendingType;
    }

    public InvalidModuleContractException(String message, Throwable cause) {
        super(message, cause);
        this.interfaceType = null;
        this.methodName = null;
        this.offendingType = null;
    }

    public Class<?> getInterfaceType() {
        return interfaceType;
    }

    public String getMethodName() {
        return methodName;
    }

    public Class<?> getOffendingType() {
        return offendingType;
    }
}

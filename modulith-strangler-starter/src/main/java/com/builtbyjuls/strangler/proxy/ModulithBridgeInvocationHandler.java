package com.builtbyjuls.strangler.proxy;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.builtbyjuls.strangler.config.BridgeMode;
import com.builtbyjuls.strangler.config.ModulithBridgeProperties;
import com.builtbyjuls.strangler.transport.BridgeHttpClient;
import com.builtbyjuls.strangler.transport.BridgeInvocationRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Dynamic invocation handler that dispatches module API calls to LOCAL bean or REMOTE HTTP bridge.
 */
public class ModulithBridgeInvocationHandler implements InvocationHandler {

    private static final Logger log = LoggerFactory.getLogger(ModulithBridgeInvocationHandler.class);

    private final Object localTarget;
    private final String moduleName;
    private final Class<?> targetInterface;
    private final ModulithBridgeProperties properties;
    private final BridgeHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public ModulithBridgeInvocationHandler(
            Object localTarget,
            String moduleName,
            Class<?> targetInterface,
            ModulithBridgeProperties properties,
            BridgeHttpClient httpClient,
            ObjectMapper objectMapper
    ) {
        this.localTarget = localTarget;
        this.moduleName = Objects.requireNonNull(moduleName, "moduleName must not be null").toLowerCase();
        this.targetInterface = Objects.requireNonNull(targetInterface, "targetInterface must not be null");
        this.properties = properties != null ? properties : new ModulithBridgeProperties();
        this.httpClient = httpClient;
        this.objectMapper = objectMapper != null ? objectMapper : (httpClient != null ? httpClient.getObjectMapper() : new ObjectMapper());
    }

    public Object getLocalTarget() {
        return localTarget;
    }

    public String getModuleName() {
        return moduleName;
    }

    public Class<?> getTargetInterface() {
        return targetInterface;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return handleObjectMethod(proxy, method, args);
        }

        if (!targetInterface.isAssignableFrom(method.getDeclaringClass())) {
            return invokeLocal(method, args);
        }

        BridgeMode mode = resolveMode();

        if (isHostOnly() || mode == BridgeMode.LOCAL) {
            return invokeLocal(method, args);
        }

        if (mode == BridgeMode.REMOTE) {
            return invokeRemote(method, args);
        }

        // BridgeMode.SHADOW: in Stage 4, execute local target as authoritative
        return invokeLocal(method, args);
    }

    private BridgeMode resolveMode() {
        if (properties.getModules() != null) {
            ModulithBridgeProperties.ModuleRouteConfig cfg = properties.getModules().get(moduleName);
            if (cfg != null && cfg.getMode() != null) {
                return cfg.getMode();
            }
        }
        return BridgeMode.LOCAL;
    }

    private boolean isHostOnly() {
        String hostOnly = properties.getHostOnly();
        return hostOnly != null && hostOnly.trim().equalsIgnoreCase(moduleName);
    }

    private Object invokeLocal(Method method, Object[] args) throws Throwable {
        if (localTarget == null) {
            String methodName = method.getName();
            if ("close".equals(methodName) || "destroy".equals(methodName)) {
                return null;
            }
            throw new IllegalStateException("No local target bean available for module '" + moduleName
                    + "' (interface: " + targetInterface.getName() + ")");
        }
        try {
            return method.invoke(localTarget, args);
        } catch (InvocationTargetException ite) {
            throw ite.getCause() != null ? ite.getCause() : ite;
        }
    }

    private Object invokeRemote(Method method, Object[] args) throws Throwable {
        ModulithBridgeProperties.ModuleRouteConfig cfg = properties.getModules() != null
                ? properties.getModules().get(moduleName)
                : null;

        String url = cfg != null ? cfg.getUrl() : null;
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("Remote bridge URL is not configured for module '" + moduleName + "'");
        }

        if (httpClient == null) {
            throw new IllegalStateException("BridgeHttpClient is not available in ModulithBridgeInvocationHandler");
        }

        long timeoutMs = cfg != null ? cfg.getTimeoutMs() : 5000L;
        boolean fallbackToLocal = cfg == null || cfg.isFallbackToLocal();

        List<String> paramTypes = Arrays.stream(method.getParameterTypes())
                .map(Class::getName)
                .toList();

        List<JsonNode> jsonArgs = new ArrayList<>();
        if (args != null) {
            for (Object arg : args) {
                jsonArgs.add(objectMapper.valueToTree(arg));
            }
        }

        BridgeInvocationRequest request = new BridgeInvocationRequest(
                moduleName,
                targetInterface.getName(),
                method.getName(),
                paramTypes,
                jsonArgs
        );

        Type returnType = method.getGenericReturnType();

        try {
            return httpClient.invokeRemote(url, request, returnType, timeoutMs);
        } catch (Exception ex) {
            if (fallbackToLocal && localTarget != null) {
                log.warn("Remote call to module '{}' failed, falling back to local bean: {}",
                        moduleName, ex.getMessage());
                return invokeLocal(method, args);
            }
            throw ex;
        }
    }

    private Object handleObjectMethod(Object proxy, Method method, Object[] args) {
        String name = method.getName();
        return switch (name) {
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "ModulithBridgeProxy[" + moduleName + ":" + targetInterface.getSimpleName() + "]";
            default -> throw new UnsupportedOperationException("Unsupported Object method: " + name);
        };
    }
}

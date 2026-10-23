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
import com.builtbyjuls.strangler.parity.ShadowParityRegistry;
import com.builtbyjuls.strangler.parity.ShadowParityResult;
import com.builtbyjuls.strangler.parity.ShadowParityVerifier;
import com.builtbyjuls.strangler.transport.BridgeBusinessException;
import com.builtbyjuls.strangler.transport.BridgeHttpClient;
import com.builtbyjuls.strangler.transport.BridgeInvocationRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Dynamic invocation handler that dispatches module API calls to LOCAL bean, REMOTE HTTP bridge,
 * or parallel SHADOW mode on Java 21 Virtual Threads.
 */
public class ModulithBridgeInvocationHandler implements InvocationHandler {

    private static final Logger log = LoggerFactory.getLogger(ModulithBridgeInvocationHandler.class);

    private final Object localTarget;
    private final String moduleName;
    private final Class<?> targetInterface;
    private final ModulithBridgeProperties properties;
    private final BridgeHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ShadowParityVerifier verifier;
    private final ShadowParityRegistry registry;

    public ModulithBridgeInvocationHandler(
            Object localTarget,
            String moduleName,
            Class<?> targetInterface,
            ModulithBridgeProperties properties,
            BridgeHttpClient httpClient,
            ObjectMapper objectMapper
    ) {
        this(localTarget, moduleName, targetInterface, properties, httpClient, objectMapper, null, null);
    }

    public ModulithBridgeInvocationHandler(
            Object localTarget,
            String moduleName,
            Class<?> targetInterface,
            ModulithBridgeProperties properties,
            BridgeHttpClient httpClient,
            ObjectMapper objectMapper,
            ShadowParityVerifier verifier,
            ShadowParityRegistry registry
    ) {
        this.localTarget = localTarget;
        this.moduleName = Objects.requireNonNull(moduleName, "moduleName must not be null").toLowerCase();
        this.targetInterface = Objects.requireNonNull(targetInterface, "targetInterface must not be null");
        this.properties = properties != null ? properties : new ModulithBridgeProperties();
        this.httpClient = httpClient;
        this.objectMapper = objectMapper != null ? objectMapper : (httpClient != null ? httpClient.getObjectMapper() : new ObjectMapper());
        this.verifier = verifier != null ? verifier : new ShadowParityVerifier(this.objectMapper);
        this.registry = registry != null ? registry : new ShadowParityRegistry();
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

    public ShadowParityVerifier getVerifier() {
        return verifier;
    }

    public ShadowParityRegistry getRegistry() {
        return registry;
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

        if (mode == BridgeMode.SHADOW) {
            return invokeShadow(method, args);
        }

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

        BridgeInvocationRequest request = createRequest(method, args);
        Type returnType = method.getGenericReturnType();

        long startRemote = System.currentTimeMillis();
        try {
            return httpClient.invokeRemote(url, request, returnType, timeoutMs);
        } catch (BridgeBusinessException bbe) {
            throw bbe;
        } catch (Exception ex) {
            long remoteDurationMs = System.currentTimeMillis() - startRemote;
            if (fallbackToLocal && localTarget != null) {
                log.warn("Remote call to module '{}' failed, falling back to local bean: {}",
                        moduleName, ex.getMessage());
                long startLocal = System.currentTimeMillis();
                Object localResult = invokeLocal(method, args);
                long localDurationMs = System.currentTimeMillis() - startLocal;

                if (registry != null) {
                    String errorMsg = ex.getMessage() != null ? ex.getMessage() : ex.toString();
                    registry.record(new ShadowParityResult(
                            moduleName,
                            targetInterface.getName(),
                            method.getName(),
                            false,
                            localDurationMs,
                            remoteDurationMs,
                            "Remote call failed, fell back to local: " + errorMsg,
                            errorMsg,
                            true
                    ));
                }
                return localResult;
            }
            throw ex;
        }
    }

    private Object invokeShadow(Method method, Object[] args) throws Throwable {
        long startLocal = System.currentTimeMillis();
        Object localResult = invokeLocal(method, args);
        long localDurationMs = System.currentTimeMillis() - startLocal;

        ModulithBridgeProperties.ModuleRouteConfig cfg = properties.getModules() != null
                ? properties.getModules().get(moduleName)
                : null;
        double sampleRate = (cfg != null && cfg.getShadowSampleRate() != null)
                ? cfg.getShadowSampleRate()
                : properties.getShadowSampleRate();

        if (sampleRate <= 0.0 || (sampleRate < 1.0 && java.util.concurrent.ThreadLocalRandom.current().nextDouble() >= sampleRate)) {
            return localResult;
        }

        BridgeInvocationRequest request = createRequest(method, args);
        Type returnType = method.getGenericReturnType();

        Runnable shadowTask = () -> {
            long startRemote = System.currentTimeMillis();
            try {
                ModulithBridgeProperties.ModuleRouteConfig activeCfg = properties.getModules() != null
                        ? properties.getModules().get(moduleName)
                        : null;
                String url = activeCfg != null ? activeCfg.getUrl() : null;
                if (url == null || url.isBlank()) {
                    throw new IllegalStateException("Remote bridge URL is not configured for module '" + moduleName + "'");
                }
                if (httpClient == null) {
                    throw new IllegalStateException("BridgeHttpClient is not available in ModulithBridgeInvocationHandler");
                }
                long timeoutMs = activeCfg != null ? activeCfg.getTimeoutMs() : 5000L;
                Object remoteResult = httpClient.invokeRemote(url, request, returnType, timeoutMs, true);
                long remoteDurationMs = System.currentTimeMillis() - startRemote;

                ShadowParityVerifier.ParityOutcome outcome = verifier.verify(localResult, remoteResult);
                if (!outcome.matched()) {
                    log.warn("Shadow parity mismatch for module '{}', method '{}': {}",
                            moduleName, method.getName(), outcome.diffSummary());
                }

                if (registry != null) {
                    registry.record(new ShadowParityResult(
                            moduleName,
                            targetInterface.getName(),
                            method.getName(),
                            outcome.matched(),
                            localDurationMs,
                            remoteDurationMs,
                            outcome.diffSummary(),
                            null,
                            false
                    ));
                }
            } catch (Throwable t) {
                long remoteDurationMs = System.currentTimeMillis() - startRemote;
                String errorMsg = t.getMessage() != null ? t.getMessage() : t.toString();
                log.warn("Shadow remote call failed for module '{}', method '{}': {}",
                        moduleName, method.getName(), errorMsg);

                if (registry != null) {
                    registry.record(new ShadowParityResult(
                            moduleName,
                            targetInterface.getName(),
                            method.getName(),
                            false,
                            localDurationMs,
                            remoteDurationMs,
                            "Remote execution failed: " + errorMsg,
                            errorMsg,
                            true
                    ));
                }
            }
        };

        Thread virtualThread = Thread.ofVirtual()
                .name("modulith-shadow-" + moduleName + "-" + method.getName())
                .start(shadowTask);

        if (properties.isShadowAwait()) {
            try {
                virtualThread.join();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while awaiting shadow parity execution for module '{}'", moduleName);
            }
        }

        return localResult;
    }

    private BridgeInvocationRequest createRequest(Method method, Object[] args) {
        List<String> paramTypes = Arrays.stream(method.getParameterTypes())
                .map(Class::getName)
                .toList();

        List<JsonNode> jsonArgs = new ArrayList<>();
        if (args != null) {
            for (Object arg : args) {
                jsonArgs.add(objectMapper.valueToTree(arg));
            }
        }

        return new BridgeInvocationRequest(
                moduleName,
                targetInterface.getName(),
                method.getName(),
                paramTypes,
                jsonArgs
        );
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

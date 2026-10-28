package com.builtbyjuls.strangler.transport;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.persistence.EntityManager;

import com.builtbyjuls.strangler.config.ModulithBridgeProperties;
import com.builtbyjuls.strangler.proxy.ModulithBridgeInvocationHandler;
import com.builtbyjuls.strangler.slice.ModuleLayoutInspector;
import com.builtbyjuls.strangler.trace.BridgeTraceContext;
import com.builtbyjuls.strangler.trace.BridgeTraceContextHolder;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Inbound HTTP controller for Spring Modulith Strangler Bridge.
 * Receives remote RPC invocations, resolves the local module bean, deserializes
 * arguments against target method signatures, invokes the bean, and returns the response.
 */
@RestController
@RequestMapping("/internal/modulith-bridge")
public class ModulithBridgeInboundController {

    private static final Logger log = LoggerFactory.getLogger(ModulithBridgeInboundController.class);

    public static final String SECRET_HEADER = "X-Modulith-Bridge-Secret";
    public static final String SHADOW_HEADER = "X-Modulith-Shadow";

    private final ApplicationContext applicationContext;
    private final ModuleLayoutInspector inspector;
    private final ModulithBridgeProperties properties;
    private final ObjectMapper objectMapper;

    public ModulithBridgeInboundController(
            ApplicationContext applicationContext,
            ModuleLayoutInspector inspector,
            ModulithBridgeProperties properties,
            ObjectMapper objectMapper
    ) {
        this.applicationContext = Objects.requireNonNull(applicationContext, "applicationContext must not be null");
        this.inspector = inspector != null ? inspector : new ModuleLayoutInspector();
        this.properties = properties != null ? properties : new ModulithBridgeProperties();
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper().registerModule(new JavaTimeModule());
    }

    public ResponseEntity<String> invokeHttp(String rawRequestBody) {
        return invokeHttp(rawRequestBody, null, null);
    }

    public ResponseEntity<String> invokeHttp(String rawRequestBody, String providedSecret) {
        return invokeHttp(rawRequestBody, providedSecret, null);
    }

    public ResponseEntity<String> invokeHttp(String rawRequestBody, String providedSecret, String shadowHeader) {
        return invokeHttp(rawRequestBody, providedSecret, shadowHeader, null, null, null, null);
    }

    @PostMapping(
            value = "/invoke",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<String> invokeHttp(
            @RequestBody String rawRequestBody,
            @RequestHeader(value = SECRET_HEADER, required = false) String providedSecret,
            @RequestHeader(value = SHADOW_HEADER, required = false) String shadowHeader,
            @RequestHeader(value = BridgeTraceContextHolder.TRACEPARENT_HEADER, required = false) String traceparent,
            @RequestHeader(value = BridgeTraceContextHolder.TRACESTATE_HEADER, required = false) String tracestate,
            @RequestHeader(value = BridgeTraceContextHolder.BAGGAGE_HEADER, required = false) String baggage,
            @RequestHeader(value = BridgeTraceContextHolder.CORRELATION_ID_HEADER, required = false) String correlationId
    ) {
        if (StringUtils.hasText(properties.getSecretToken())) {
            byte[] expected = properties.getSecretToken().getBytes(StandardCharsets.UTF_8);
            byte[] actual = providedSecret != null ? providedSecret.getBytes(StandardCharsets.UTF_8) : new byte[0];
            if (providedSecret == null || !MessageDigest.isEqual(expected, actual)) {
                String err = "Unauthorized bridge request: invalid or missing " + SECRET_HEADER;
                log.warn(err);
                try {
                    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(objectMapper.writeValueAsString(BridgeInvocationResponse.error(err, 0, "unknown")));
                } catch (Exception ignored) {
                    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
                }
            }
        }

        BridgeInvocationRequest request;
        try {
            request = objectMapper.readValue(rawRequestBody, BridgeInvocationRequest.class);
        } catch (Exception ex) {
            String err = "Malformed request payload: " + ex.getMessage();
            log.error(err);
            try {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(objectMapper.writeValueAsString(BridgeInvocationResponse.error(err, 0, "unknown")));
            } catch (Exception ignored) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
            }
        }

        boolean shadowMode = "true".equalsIgnoreCase(shadowHeader);
        BridgeTraceContext inboundContext = properties.isPropagateTraceContext()
                ? new BridgeTraceContext(traceparent, tracestate, baggage, correlationId)
                : null;

        ResponseEntity<BridgeInvocationResponse> responseEntity;
        try (BridgeTraceContextHolder.Scope ignored = inboundContext != null
                ? BridgeTraceContextHolder.openScope(inboundContext)
                : BridgeTraceContextHolder.Scope.NOOP) {
            responseEntity = invoke(request, shadowMode);
        }

        try {
            String json = objectMapper.writeValueAsString(responseEntity.getBody());
            return ResponseEntity.status(responseEntity.getStatusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(json);
        } catch (Exception ex) {
            log.error("Failed to serialize BridgeInvocationResponse: {}", ex.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    public ResponseEntity<BridgeInvocationResponse> invoke(BridgeInvocationRequest request) {
        return invoke(request, false);
    }

    public ResponseEntity<BridgeInvocationResponse> invoke(BridgeInvocationRequest request, boolean shadowMode) {
        Objects.requireNonNull(request, "request must not be null");

        log.debug("Received inbound bridge invocation for module '{}', interface '{}', method '{}' (shadowMode: {})",
                request.moduleName(), request.interfaceName(), request.methodName(), shadowMode);

        Class<?> targetInterface = resolveInterface(request.interfaceName());
        if (targetInterface == null) {
            String err = "Cannot resolve interface: " + request.interfaceName();
            log.error(err);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(BridgeInvocationResponse.error(err, 0, request.moduleName()));
        }

        if (!targetInterface.isInterface() || !inspector.isModuleApi(targetInterface)) {
            String err = "Forbidden: target '" + request.interfaceName() + "' is not a whitelisted @NamedInterface or @ModulithBridgeTarget";
            log.warn(err);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(BridgeInvocationResponse.error(err, 0, request.moduleName()));
        }

        Object targetBean = resolveTargetBean(targetInterface, request.moduleName());
        if (targetBean == null) {
            String err = "No bean found implementing interface '" + targetInterface.getName()
                    + "' for module '" + request.moduleName() + "'";
            log.error(err);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(BridgeInvocationResponse.error(err, 0, request.moduleName()));
        }

        Object unwrappedTarget = unwrapBridgeProxy(targetBean);

        Method targetMethod = findMatchingMethod(targetInterface, request.methodName(), request.parameterTypes());
        if (targetMethod == null) {
            targetMethod = findMatchingMethod(unwrappedTarget.getClass(), request.methodName(), request.parameterTypes());
        }

        if (targetMethod == null) {
            String err = "Method '" + request.methodName() + "' with parameter types "
                    + request.parameterTypes() + " not found on interface " + targetInterface.getName();
            log.error(err);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(BridgeInvocationResponse.error(err, 0, request.moduleName()));
        }

        Object[] args;
        try {
            args = deserializeArguments(targetMethod, request.arguments());
        } catch (Exception ex) {
            String err = "Failed to deserialize arguments for method '" + targetMethod.getName() + "': " + ex.getMessage();
            log.error(err, ex);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(BridgeInvocationResponse.error(err, 0, request.moduleName()));
        }

        long start = System.currentTimeMillis();
        try {
            Object result;
            if (shadowMode && properties.isShadowRollbackWrites()) {
                result = invokeWithRollback(targetMethod, unwrappedTarget, args);
            } else {
                result = targetMethod.invoke(unwrappedTarget, args);
            }
            long durationMs = System.currentTimeMillis() - start;
            JsonNode resultNode = result != null ? objectMapper.valueToTree(result) : null;
            return ResponseEntity.ok(BridgeInvocationResponse.success(resultNode, durationMs, request.moduleName()));
        } catch (InvocationTargetException ite) {
            long durationMs = System.currentTimeMillis() - start;
            Throwable cause = ite.getCause() != null ? ite.getCause() : ite;
            String errorMsg = (cause.getMessage() != null && !cause.getMessage().isBlank())
                    ? cause.getMessage()
                    : cause.toString();
            log.error("Execution exception in method '{}' of module '{}': {}",
                    targetMethod.getName(), request.moduleName(), errorMsg);
            return ResponseEntity.ok(BridgeInvocationResponse.businessError(
                    errorMsg,
                    cause.getClass().getName(),
                    durationMs,
                    request.moduleName()
            ));
        } catch (Exception ex) {
            long durationMs = System.currentTimeMillis() - start;
            log.error("Invocation failure on module '{}': {}", request.moduleName(), ex.getMessage(), ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(BridgeInvocationResponse.error(ex.getMessage(), durationMs, request.moduleName()));
        }
    }

    private Object invokeWithRollback(Method targetMethod, Object unwrappedTarget, Object[] args) throws Exception {
        PlatformTransactionManager txManager = null;
        try {
            txManager = applicationContext.getBeanProvider(PlatformTransactionManager.class).getIfAvailable();
        } catch (Throwable t) {
            log.debug("PlatformTransactionManager lookup failed: {}", t.getMessage());
        }

        if (txManager == null) {
            return targetMethod.invoke(unwrappedTarget, args);
        }

        TransactionTemplate txTemplate = new TransactionTemplate(txManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        AtomicReference<InvocationTargetException> targetExceptionRef = new AtomicReference<>();
        AtomicReference<Exception> otherExceptionRef = new AtomicReference<>();

        Object result = txTemplate.execute(status -> {
            try {
                Object res = targetMethod.invoke(unwrappedTarget, args);
                flushEntityManagerIfPresent();
                return res;
            } catch (InvocationTargetException ite) {
                targetExceptionRef.set(ite);
                return null;
            } catch (Exception ex) {
                otherExceptionRef.set(ex);
                return null;
            } finally {
                status.setRollbackOnly();
            }
        });

        if (targetExceptionRef.get() != null) {
            throw targetExceptionRef.get();
        }
        if (otherExceptionRef.get() != null) {
            throw otherExceptionRef.get();
        }
        return result;
    }

    private void flushEntityManagerIfPresent() {
        try {
            EntityManager em = applicationContext.getBeanProvider(EntityManager.class).getIfAvailable();
            if (em != null) {
                try {
                    em.flush();
                } catch (Exception ex) {
                    if ("TransactionRequiredException".equals(ex.getClass().getSimpleName())) {
                        log.debug("No active transaction on EntityManager for flush: {}", ex.getMessage());
                    } else {
                        throw ex;
                    }
                }
            }
        } catch (NoClassDefFoundError | Exception ex) {
            if ("TransactionRequiredException".equals(ex.getClass().getSimpleName())) {
                log.debug("No active transaction on EntityManager for flush: {}", ex.getMessage());
            } else if (ex instanceof RuntimeException re) {
                throw re;
            }
        }
    }

    private Class<?> resolveInterface(String interfaceName) {
        if (!StringUtils.hasText(interfaceName)) {
            return null;
        }
        ClassLoader cl = applicationContext.getClassLoader() != null
                ? applicationContext.getClassLoader()
                : ClassUtils.getDefaultClassLoader();

        try {
            return ClassUtils.forName(interfaceName, cl);
        } catch (ClassNotFoundException ignored) {
        }

        // Search among known beans for an interface matching interfaceName simple name
        for (String beanName : applicationContext.getBeanDefinitionNames()) {
            try {
                Class<?> type = applicationContext.getType(beanName);
                if (type != null) {
                    for (Class<?> iface : ClassUtils.getAllInterfacesForClass(type)) {
                        if (iface.getName().equals(interfaceName) || iface.getSimpleName().equals(interfaceName)) {
                            return iface;
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private Object resolveTargetBean(Class<?> targetInterface, String moduleName) {
        Map<String, ?> beans = applicationContext.getBeansOfType(targetInterface);
        if (beans.isEmpty()) {
            return null;
        }
        if (beans.size() == 1) {
            return beans.values().iterator().next();
        }

        String basePackage = properties.getBasePackage();
        for (Object candidate : beans.values()) {
            Object unwrapped = unwrapBridgeProxy(candidate);
            Optional<String> mod = inspector.resolveModuleName(unwrapped.getClass(), basePackage);
            if (mod.isPresent() && mod.get().equalsIgnoreCase(moduleName)) {
                return candidate;
            }
        }

        return beans.values().iterator().next();
    }

    /**
     * Unwraps any ModulithBridgeInvocationHandler proxy to the raw local target bean to prevent recursion.
     */
    public Object unwrapBridgeProxy(Object bean) {
        Object current = bean;
        while (current != null && Proxy.isProxyClass(current.getClass())) {
            InvocationHandler handler = Proxy.getInvocationHandler(current);
            if (handler instanceof ModulithBridgeInvocationHandler bridgeHandler) {
                Object target = bridgeHandler.getLocalTarget();
                if (target != null && target != current) {
                    current = target;
                    continue;
                }
            }
            try {
                Method getLocalTarget = handler.getClass().getMethod("getLocalTarget");
                Object target = getLocalTarget.invoke(handler);
                if (target != null && target != current) {
                    current = target;
                    continue;
                }
            } catch (Exception ignored) {
            }
            break;
        }
        return current;
    }

    private Method findMatchingMethod(Class<?> targetClass, String methodName, List<String> paramTypeNames) {
        for (Method method : targetClass.getMethods()) {
            if (!method.getName().equals(methodName)) {
                continue;
            }
            Class<?>[] paramTypes = method.getParameterTypes();
            if (paramTypes.length != paramTypeNames.size()) {
                continue;
            }
            boolean matches = true;
            for (int i = 0; i < paramTypes.length; i++) {
                String expected = paramTypeNames.get(i);
                Class<?> actual = paramTypes[i];
                if (!actual.getName().equals(expected) && !actual.getSimpleName().equals(expected)) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return method;
            }
        }
        return null;
    }

    private Object[] deserializeArguments(Method method, List<JsonNode> jsonArgs) throws Exception {
        Type[] genericParamTypes = method.getGenericParameterTypes();
        Object[] args = new Object[genericParamTypes.length];
        for (int i = 0; i < genericParamTypes.length; i++) {
            JsonNode node = i < jsonArgs.size() ? jsonArgs.get(i) : null;
            if (node == null || node.isNull()) {
                args[i] = null;
            } else {
                JavaType javaType = objectMapper.getTypeFactory().constructType(genericParamTypes[i]);
                args[i] = objectMapper.readerFor(javaType).readValue(node);
            }
        }
        return args;
    }
}

package com.builtbyjuls.strangler.transport;

import java.lang.reflect.Type;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import com.builtbyjuls.strangler.config.ModulithBridgeProperties;
import com.builtbyjuls.strangler.trace.BridgeTraceContext;
import com.builtbyjuls.strangler.trace.BridgeTraceContextHolder;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Dispatches RPC method invocations over HTTP using Spring RestClient and Jackson.
 */
public class BridgeHttpClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BridgeHttpClient.class);
    private static final String INVOKE_PATH = "/internal/modulith-bridge/invoke";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final ExecutorService virtualThreadExecutor;
    private final String secretToken;
    private final boolean propagateTraceContext;

    public BridgeHttpClient() {
        this(RestClient.builder(), createDefaultObjectMapper(), (String) null, true);
    }

    public BridgeHttpClient(RestClient.Builder restClientBuilder, ObjectMapper objectMapper) {
        this(restClientBuilder, objectMapper, (String) null, true);
    }

    public BridgeHttpClient(RestClient.Builder restClientBuilder, ObjectMapper objectMapper, ModulithBridgeProperties properties) {
        this(restClientBuilder, objectMapper,
                properties != null ? properties.getSecretToken() : null,
                properties != null ? properties.isPropagateTraceContext() : true);
    }

    public BridgeHttpClient(RestClient.Builder restClientBuilder, ObjectMapper objectMapper, String secretToken) {
        this(restClientBuilder, objectMapper, secretToken, true);
    }

    public BridgeHttpClient(RestClient.Builder restClientBuilder, ObjectMapper objectMapper, String secretToken, boolean propagateTraceContext) {
        RestClient.Builder builder = restClientBuilder != null ? restClientBuilder : RestClient.builder();
        this.objectMapper = objectMapper != null ? objectMapper : createDefaultObjectMapper();
        this.secretToken = secretToken;
        this.propagateTraceContext = propagateTraceContext;
        this.restClient = builder.build();
        this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    public String getSecretToken() {
        return secretToken;
    }

    public boolean isPropagateTraceContext() {
        return propagateTraceContext;
    }

    public Object invokeRemote(String url, BridgeInvocationRequest request, Type returnType) {
        return invokeRemote(url, request, returnType, 5000L, false);
    }

    public Object invokeRemote(String url, BridgeInvocationRequest request, Type returnType, long timeoutMs) {
        return invokeRemote(url, request, returnType, timeoutMs, false);
    }

    public Object invokeRemote(String url, BridgeInvocationRequest request, Type returnType, long timeoutMs, boolean shadowMode) {
        Objects.requireNonNull(url, "url must not be null");
        Objects.requireNonNull(request, "request must not be null");

        String cleanUrl = url.trim();
        while (cleanUrl.endsWith("/")) {
            cleanUrl = cleanUrl.substring(0, cleanUrl.length() - 1);
        }
        String endpoint = cleanUrl + INVOKE_PATH;

        log.debug("Invoking remote bridge endpoint '{}' for module '{}', method '{}' (shadowMode: {})",
                endpoint, request.moduleName(), request.methodName(), shadowMode);

        BridgeInvocationResponse response;
        BridgeTraceContext traceContext = propagateTraceContext ? BridgeTraceContextHolder.captureForOutbound() : null;
        try {
            String requestJson = objectMapper.writeValueAsString(request);
            CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                try (BridgeTraceContextHolder.Scope ignored = traceContext != null
                        ? BridgeTraceContextHolder.openScope(traceContext)
                        : BridgeTraceContextHolder.Scope.NOOP) {
                    RestClient.RequestBodySpec requestSpec = restClient.post()
                            .uri(endpoint)
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON);
                    if (StringUtils.hasText(secretToken)) {
                        requestSpec.header(ModulithBridgeInboundController.SECRET_HEADER, secretToken);
                    }
                    if (shadowMode) {
                        requestSpec.header(ModulithBridgeInboundController.SHADOW_HEADER, "true");
                    }
                    if (traceContext != null) {
                        if (StringUtils.hasText(traceContext.traceparent())) {
                            requestSpec.header(BridgeTraceContextHolder.TRACEPARENT_HEADER, traceContext.traceparent());
                        }
                        if (StringUtils.hasText(traceContext.tracestate())) {
                            requestSpec.header(BridgeTraceContextHolder.TRACESTATE_HEADER, traceContext.tracestate());
                        }
                        if (StringUtils.hasText(traceContext.baggage())) {
                            requestSpec.header(BridgeTraceContextHolder.BAGGAGE_HEADER, traceContext.baggage());
                        }
                        if (StringUtils.hasText(traceContext.correlationId())) {
                            requestSpec.header(BridgeTraceContextHolder.CORRELATION_ID_HEADER, traceContext.correlationId());
                        }
                    }
                    return requestSpec.body(requestJson)
                            .retrieve()
                            .body(String.class);
                }
            }, virtualThreadExecutor);

            String responseJson;
            if (timeoutMs > 0) {
                try {
                    responseJson = future.get(timeoutMs, TimeUnit.MILLISECONDS);
                } catch (TimeoutException te) {
                    future.cancel(true);
                    throw new BridgeRemoteInvocationException(
                            "Remote bridge invocation timed out after " + timeoutMs + "ms for module '" + request.moduleName() + "'",
                            te
                    );
                } catch (ExecutionException ee) {
                    Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
                    if (cause instanceof RuntimeException re) {
                        throw re;
                    }
                    throw new BridgeRemoteInvocationException(
                            "Remote bridge invocation failed for module '" + request.moduleName() + "': " + cause.getMessage(),
                            cause
                    );
                }
            } else {
                responseJson = future.join();
            }

            if (responseJson == null) {
                throw new BridgeRemoteInvocationException("Received null response from bridge endpoint: " + endpoint);
            }
            response = objectMapper.readValue(responseJson, BridgeInvocationResponse.class);
        } catch (BridgeRemoteInvocationException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.error("HTTP transport error invoking remote module '{}' at '{}': {}",
                    request.moduleName(), endpoint, ex.getMessage());
            throw ex;
        } catch (Exception ex) {
            log.error("Serialization error invoking remote module '{}' at '{}': {}",
                    request.moduleName(), endpoint, ex.getMessage());
            throw new BridgeRemoteInvocationException("Failed to process invocation for module " + request.moduleName(), ex);
        }

        if (response.error() != null) {
            if (response.businessError()) {
                throw new BridgeBusinessException(
                        response.moduleName(),
                        response.exceptionType(),
                        response.error()
                );
            }
            throw new BridgeRemoteInvocationException(
                    "Remote bridge invocation failed on module '" + response.moduleName() + "': " + response.error()
            );
        }

        if (returnType == null || returnType == void.class || returnType == Void.class) {
            return null;
        }

        if (response.result() == null || response.result().isNull()) {
            return null;
        }

        try {
            JavaType javaType = objectMapper.getTypeFactory().constructType(returnType);
            return objectMapper.readerFor(javaType).readValue(response.result());
        } catch (Exception ex) {
            throw new BridgeRemoteInvocationException(
                    "Failed to deserialize response result into type " + returnType.getTypeName() + ": " + ex.getMessage(),
                    ex
            );
        }
    }

    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }

    @Override
    public void close() {
        virtualThreadExecutor.shutdown();
    }

    private static ObjectMapper createDefaultObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }
}

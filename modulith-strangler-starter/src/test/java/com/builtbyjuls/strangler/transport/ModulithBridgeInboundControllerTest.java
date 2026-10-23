package com.builtbyjuls.strangler.transport;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.modulith.NamedInterface;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import com.builtbyjuls.strangler.config.ModulithBridgeProperties;
import com.builtbyjuls.strangler.proxy.ModulithBridgeInvocationHandler;
import com.builtbyjuls.strangler.slice.ModuleLayoutInspector;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import static org.assertj.core.api.Assertions.assertThat;

class ModulithBridgeInboundControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final ModuleLayoutInspector inspector = new ModuleLayoutInspector();
    private ModulithBridgeProperties properties;
    private GenericApplicationContext context;
    private ModulithBridgeInboundController controller;

    public interface UnannotatedInternalApi {
        void doInternal();
    }

    public static class UnannotatedInternalServiceImpl implements UnannotatedInternalApi {
        @Override
        public void doInternal() {
        }
    }

    @NamedInterface("SampleMeteringApi")
    public interface SampleMeteringApi {
        List<String> getVolumesForDate(LocalDate date, int limit);
        void performAction(String actionCode);
        String failOperation();
        String failWithNullMessage();
    }

    public static class SampleMeteringServiceImpl implements SampleMeteringApi {
        int callCount = 0;

        @Override
        public List<String> getVolumesForDate(LocalDate date, int limit) {
            callCount++;
            return List.of("MTR_" + date + "_" + limit);
        }

        @Override
        public void performAction(String actionCode) {
            callCount++;
        }

        @Override
        public String failOperation() {
            throw new IllegalStateException("Simulated domain calculation error");
        }

        @Override
        public String failWithNullMessage() {
            throw new NullPointerException();
        }
    }

    @BeforeEach
    void setUp() {
        this.context = new GenericApplicationContext();
        this.properties = new ModulithBridgeProperties();
        this.properties.setBasePackage("com.builtbyjuls.grid");
    }

    @Test
    @DisplayName("Inbound controller invokes target bean with typed deserialized arguments and returns result")
    void shouldInvokeTargetBeanAndReturnResult() {
        SampleMeteringServiceImpl service = new SampleMeteringServiceImpl();
        context.registerBean("sampleMeteringService", SampleMeteringApi.class, () -> service);
        context.refresh();

        this.controller = new ModulithBridgeInboundController(context, inspector, properties, objectMapper);

        BridgeInvocationRequest request = new BridgeInvocationRequest(
                "metering",
                SampleMeteringApi.class.getName(),
                "getVolumesForDate",
                List.of("java.time.LocalDate", "int"),
                List.of(
                        objectMapper.valueToTree(LocalDate.of(2026, 10, 22)),
                        objectMapper.valueToTree(5)
                )
        );

        ResponseEntity<BridgeInvocationResponse> entity = controller.invoke(request);

        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.OK);
        BridgeInvocationResponse response = entity.getBody();
        assertThat(response).isNotNull();
        assertThat(response.error()).isNull();
        assertThat(response.moduleName()).isEqualTo("metering");
        assertThat(response.result().isArray()).isTrue();
        assertThat(response.result().get(0).asText()).isEqualTo("MTR_2026-10-22_5");
        assertThat(service.callCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Inbound controller handles void return type")
    void shouldHandleVoidReturnType() {
        SampleMeteringServiceImpl service = new SampleMeteringServiceImpl();
        context.registerBean("sampleMeteringService", SampleMeteringApi.class, () -> service);
        context.refresh();

        this.controller = new ModulithBridgeInboundController(context, inspector, properties, objectMapper);

        BridgeInvocationRequest request = new BridgeInvocationRequest(
                "metering",
                SampleMeteringApi.class.getName(),
                "performAction",
                List.of("java.lang.String"),
                List.of(objectMapper.valueToTree("SYNC_NOW"))
        );

        ResponseEntity<BridgeInvocationResponse> entity = controller.invoke(request);

        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.OK);
        BridgeInvocationResponse response = entity.getBody();
        assertThat(response).isNotNull();
        assertThat(response.error()).isNull();
        assertThat(response.result()).isNull();
        assertThat(service.callCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Inbound controller unwraps ModulithBridgeInvocationHandler proxy to prevent recursion")
    void shouldUnwrapBridgeInvocationHandlerProxy() {
        SampleMeteringServiceImpl rawTarget = new SampleMeteringServiceImpl();

        // Configure module in REMOTE mode so if not unwrapped it would attempt remote call and fail
        ModulithBridgeProperties.ModuleRouteConfig remoteCfg = new ModulithBridgeProperties.ModuleRouteConfig();
        remoteCfg.setMode(com.builtbyjuls.strangler.config.BridgeMode.REMOTE);
        remoteCfg.setUrl("http://invalid-url-that-would-fail:9999");
        remoteCfg.setFallbackToLocal(false);
        properties.getModules().put("metering", remoteCfg);

        ModulithBridgeInvocationHandler handler = new ModulithBridgeInvocationHandler(
                rawTarget,
                "metering",
                SampleMeteringApi.class,
                properties,
                null,
                objectMapper
        );

        SampleMeteringApi proxy = (SampleMeteringApi) Proxy.newProxyInstance(
                SampleMeteringApi.class.getClassLoader(),
                new Class<?>[]{SampleMeteringApi.class},
                handler
        );

        // Register proxy as the bean in context
        context.registerBean("sampleMeteringService", SampleMeteringApi.class, () -> proxy);
        context.refresh();

        this.controller = new ModulithBridgeInboundController(context, inspector, properties, objectMapper);

        BridgeInvocationRequest request = new BridgeInvocationRequest(
                "metering",
                SampleMeteringApi.class.getName(),
                "getVolumesForDate",
                List.of("java.time.LocalDate", "int"),
                List.of(
                        objectMapper.valueToTree(LocalDate.of(2026, 10, 22)),
                        objectMapper.valueToTree(10)
                )
        );

        ResponseEntity<BridgeInvocationResponse> entity = controller.invoke(request);

        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.OK);
        BridgeInvocationResponse response = entity.getBody();
        assertThat(response).isNotNull();
        assertThat(response.error()).isNull();
        assertThat(rawTarget.callCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Inbound controller returns businessError=true and exceptionType when target method throws exception")
    void shouldReturnErrorWhenMethodThrows() {
        SampleMeteringServiceImpl service = new SampleMeteringServiceImpl();
        context.registerBean("sampleMeteringService", SampleMeteringApi.class, () -> service);
        context.refresh();

        this.controller = new ModulithBridgeInboundController(context, inspector, properties, objectMapper);

        BridgeInvocationRequest request = new BridgeInvocationRequest(
                "metering",
                SampleMeteringApi.class.getName(),
                "failOperation",
                List.of(),
                List.of()
        );

        ResponseEntity<BridgeInvocationResponse> entity = controller.invoke(request);

        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.OK);
        BridgeInvocationResponse response = entity.getBody();
        assertThat(response).isNotNull();
        assertThat(response.error()).contains("Simulated domain calculation error");
        assertThat(response.businessError()).isTrue();
        assertThat(response.exceptionType()).isEqualTo("java.lang.IllegalStateException");
    }

    @Test
    @DisplayName("Inbound controller rejects non-@NamedInterface target interface with 403 Forbidden")
    void shouldReturnForbiddenWhenTargetIsNotNamedInterface() {
        UnannotatedInternalServiceImpl service = new UnannotatedInternalServiceImpl();
        context.registerBean("unannotatedService", UnannotatedInternalApi.class, () -> service);
        context.refresh();

        this.controller = new ModulithBridgeInboundController(context, inspector, properties, objectMapper);

        BridgeInvocationRequest request = new BridgeInvocationRequest(
                "metering",
                UnannotatedInternalApi.class.getName(),
                "doInternal",
                List.of(),
                List.of()
        );

        ResponseEntity<BridgeInvocationResponse> entity = controller.invoke(request);

        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(entity.getBody()).isNotNull();
        assertThat(entity.getBody().error())
                .isEqualTo("Forbidden: target '" + UnannotatedInternalApi.class.getName() + "' is not a whitelisted @NamedInterface or @ModulithBridgeTarget");
    }

    @Test
    @DisplayName("Inbound controller rejects concrete class target with 403 Forbidden")
    void shouldReturnForbiddenWhenTargetIsConcreteClass() {
        SampleMeteringServiceImpl service = new SampleMeteringServiceImpl();
        context.registerBean("sampleMeteringService", SampleMeteringServiceImpl.class, () -> service);
        context.refresh();

        this.controller = new ModulithBridgeInboundController(context, inspector, properties, objectMapper);

        BridgeInvocationRequest request = new BridgeInvocationRequest(
                "metering",
                SampleMeteringServiceImpl.class.getName(),
                "performAction",
                List.of("java.lang.String"),
                List.of(objectMapper.valueToTree("SYNC"))
        );

        ResponseEntity<BridgeInvocationResponse> entity = controller.invoke(request);

        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(entity.getBody()).isNotNull();
        assertThat(entity.getBody().error())
                .isEqualTo("Forbidden: target '" + SampleMeteringServiceImpl.class.getName() + "' is not a whitelisted @NamedInterface or @ModulithBridgeTarget");
    }

    @Test
    @DisplayName("Inbound controller rejects missing or invalid secret token with 401 Unauthorized")
    void shouldRejectMissingOrInvalidSecretTokenWithUnauthorized() throws Exception {
        properties.setSecretToken("test-bridge-secret-123");
        SampleMeteringServiceImpl service = new SampleMeteringServiceImpl();
        context.registerBean("sampleMeteringService", SampleMeteringApi.class, () -> service);
        context.refresh();

        this.controller = new ModulithBridgeInboundController(context, inspector, properties, objectMapper);

        BridgeInvocationRequest request = new BridgeInvocationRequest(
                "metering",
                SampleMeteringApi.class.getName(),
                "performAction",
                List.of("java.lang.String"),
                List.of(objectMapper.valueToTree("ACTION"))
        );
        String requestJson = objectMapper.writeValueAsString(request);

        // Missing secret header
        ResponseEntity<String> missingSecretEntity = controller.invokeHttp(requestJson);
        assertThat(missingSecretEntity.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(missingSecretEntity.getBody()).contains("Unauthorized bridge request: invalid or missing X-Modulith-Bridge-Secret");

        // Wrong secret header
        ResponseEntity<String> wrongSecretEntity = controller.invokeHttp(requestJson, "wrong-secret-token");
        assertThat(wrongSecretEntity.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(wrongSecretEntity.getBody()).contains("Unauthorized bridge request: invalid or missing X-Modulith-Bridge-Secret");

        // Valid secret header
        ResponseEntity<String> validSecretEntity = controller.invokeHttp(requestJson, "test-bridge-secret-123");
        assertThat(validSecretEntity.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(service.callCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Inbound controller formats exception class when cause getMessage is null")
    void shouldHandleExceptionWithNullMessage() {
        SampleMeteringServiceImpl service = new SampleMeteringServiceImpl();
        context.registerBean("sampleMeteringService", SampleMeteringApi.class, () -> service);
        context.refresh();

        this.controller = new ModulithBridgeInboundController(context, inspector, properties, objectMapper);

        BridgeInvocationRequest request = new BridgeInvocationRequest(
                "metering",
                SampleMeteringApi.class.getName(),
                "failWithNullMessage",
                List.of(),
                List.of()
        );

        ResponseEntity<BridgeInvocationResponse> entity = controller.invoke(request);

        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.OK);
        BridgeInvocationResponse response = entity.getBody();
        assertThat(response).isNotNull();
        assertThat(response.error()).isNotNull();
        assertThat(response.error()).contains("NullPointerException");
    }

    @Test
    @DisplayName("Inbound controller returns 400 when interface is not found")
    void shouldReturnBadRequestWhenInterfaceNotFound() {
        context.refresh();
        this.controller = new ModulithBridgeInboundController(context, inspector, properties, objectMapper);

        BridgeInvocationRequest request = new BridgeInvocationRequest(
                "metering",
                "com.nonexistent.UnknownApi",
                "someMethod",
                List.of(),
                List.of()
        );

        ResponseEntity<BridgeInvocationResponse> entity = controller.invoke(request);

        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(entity.getBody().error()).contains("Cannot resolve interface");
    }

    @Test
    @DisplayName("Inbound controller executes inside PROPAGATION_REQUIRES_NEW transaction and marks rollbackOnly when X-Modulith-Shadow is true")
    void shouldExecuteInRollbackOnlyTransactionWhenShadowHeaderPresent() throws Exception {
        AtomicBoolean requiresNewConfigured = new AtomicBoolean(false);
        AtomicBoolean rollbackOnlyMarked = new AtomicBoolean(false);

        PlatformTransactionManager txManager = new AbstractPlatformTransactionManager() {
            @Override
            protected Object doGetTransaction() {
                return new Object();
            }

            @Override
            protected void doBegin(Object transaction, TransactionDefinition definition) {
                if (definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW) {
                    requiresNewConfigured.set(true);
                }
            }

            @Override
            protected void doCommit(DefaultTransactionStatus status) {
            }

            @Override
            protected void doRollback(DefaultTransactionStatus status) {
                if (status.isRollbackOnly() || status.isLocalRollbackOnly()) {
                    rollbackOnlyMarked.set(true);
                }
            }
        };

        SampleMeteringServiceImpl service = new SampleMeteringServiceImpl();
        context.registerBean(PlatformTransactionManager.class, () -> txManager);
        context.registerBean("sampleMeteringService", SampleMeteringApi.class, () -> service);
        context.refresh();

        this.controller = new ModulithBridgeInboundController(context, inspector, properties, objectMapper);

        BridgeInvocationRequest request = new BridgeInvocationRequest(
                "metering",
                SampleMeteringApi.class.getName(),
                "getVolumesForDate",
                List.of("java.time.LocalDate", "int"),
                List.of(
                        objectMapper.valueToTree(LocalDate.of(2026, 10, 22)),
                        objectMapper.valueToTree(3)
                )
        );
        String requestJson = objectMapper.writeValueAsString(request);

        ResponseEntity<String> response = controller.invokeHttp(requestJson, null, "true");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("MTR_2026-10-22_3");
        assertThat(service.callCount).isEqualTo(1);
        assertThat(requiresNewConfigured.get()).isTrue();
        assertThat(rollbackOnlyMarked.get()).isTrue();
    }
}

package com.builtbyjuls.strangler.proxy;

import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.modulith.NamedInterface;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.builtbyjuls.strangler.config.BridgeMode;
import com.builtbyjuls.strangler.config.ModulithBridgeProperties;
import com.builtbyjuls.strangler.slice.ModuleLayoutInspector;
import com.builtbyjuls.strangler.slice.ModuleSliceRegistryPostProcessor;
import com.builtbyjuls.strangler.transport.BridgeHttpClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ModulithBridgeProxyTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final ModuleLayoutInspector inspector = new ModuleLayoutInspector();
    private ModulithBridgeProperties properties;
    private RestClient.Builder restClientBuilder;
    private MockRestServiceServer mockServer;
    private BridgeHttpClient httpClient;

    @NamedInterface("TestMeteringApi")
    public interface TestMeteringApi {
        List<String> getVolumes(LocalDate date);
    }

    public static class LocalMeteringService implements TestMeteringApi, AutoCloseable {
        int localCalls = 0;
        boolean closed = false;

        @Override
        public List<String> getVolumes(LocalDate date) {
            localCalls++;
            return List.of("LOCAL_VOLUME_" + date);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    @BeforeEach
    void setUp() {
        this.properties = new ModulithBridgeProperties();
        this.properties.setBasePackage("com.builtbyjuls.grid");
        this.restClientBuilder = RestClient.builder();
        this.mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();
        this.httpClient = new BridgeHttpClient(restClientBuilder, objectMapper);
    }

    @Test
    @DisplayName("LOCAL mode invokes local target directly without making HTTP requests")
    void shouldInvokeLocalTargetInLocalMode() {
        LocalMeteringService localService = new LocalMeteringService();
        ModulithBridgeInvocationHandler handler = new ModulithBridgeInvocationHandler(
                localService,
                "metering",
                TestMeteringApi.class,
                properties,
                httpClient,
                objectMapper
        );

        TestMeteringApi proxy = (TestMeteringApi) Proxy.newProxyInstance(
                TestMeteringApi.class.getClassLoader(),
                new Class<?>[]{TestMeteringApi.class},
                handler
        );

        List<String> result = proxy.getVolumes(LocalDate.of(2026, 10, 22));

        assertThat(result).containsExactly("LOCAL_VOLUME_2026-10-22");
        assertThat(localService.localCalls).isEqualTo(1);
        mockServer.verify(); // No HTTP requests made
    }

    @Test
    @DisplayName("REMOTE mode routes invocation over HTTP bridge")
    void shouldRouteToRemoteBridgeInRemoteMode() {
        ModulithBridgeProperties.ModuleRouteConfig route = new ModulithBridgeProperties.ModuleRouteConfig();
        route.setMode(BridgeMode.REMOTE);
        route.setUrl("http://remote-worker:8080");
        properties.getModules().put("metering", route);

        String remoteResponseJson = """
                {
                    "result": ["REMOTE_VOLUME_2026-10-22"],
                    "durationMs": 10,
                    "moduleName": "metering",
                    "error": null
                }
                """;

        mockServer.expect(requestTo("http://remote-worker:8080/internal/modulith-bridge/invoke"))
                .andRespond(withSuccess(remoteResponseJson, org.springframework.http.MediaType.APPLICATION_JSON));

        LocalMeteringService localService = new LocalMeteringService();
        ModulithBridgeInvocationHandler handler = new ModulithBridgeInvocationHandler(
                localService,
                "metering",
                TestMeteringApi.class,
                properties,
                httpClient,
                objectMapper
        );

        TestMeteringApi proxy = (TestMeteringApi) Proxy.newProxyInstance(
                TestMeteringApi.class.getClassLoader(),
                new Class<?>[]{TestMeteringApi.class},
                handler
        );

        List<String> result = proxy.getVolumes(LocalDate.of(2026, 10, 22));

        assertThat(result).containsExactly("REMOTE_VOLUME_2026-10-22");
        assertThat(localService.localCalls).isEqualTo(0);
        mockServer.verify();
    }

    @Test
    @DisplayName("REMOTE mode falls back to local bean when remote fails and fallbackToLocal is true")
    void shouldFallbackToLocalWhenRemoteFails() {
        ModulithBridgeProperties.ModuleRouteConfig route = new ModulithBridgeProperties.ModuleRouteConfig();
        route.setMode(BridgeMode.REMOTE);
        route.setUrl("http://remote-worker:8080");
        route.setFallbackToLocal(true);
        properties.getModules().put("metering", route);

        mockServer.expect(requestTo("http://remote-worker:8080/internal/modulith-bridge/invoke"))
                .andRespond(withServerError());

        LocalMeteringService localService = new LocalMeteringService();
        ModulithBridgeInvocationHandler handler = new ModulithBridgeInvocationHandler(
                localService,
                "metering",
                TestMeteringApi.class,
                properties,
                httpClient,
                objectMapper
        );

        TestMeteringApi proxy = (TestMeteringApi) Proxy.newProxyInstance(
                TestMeteringApi.class.getClassLoader(),
                new Class<?>[]{TestMeteringApi.class},
                handler
        );

        List<String> result = proxy.getVolumes(LocalDate.of(2026, 10, 22));

        assertThat(result).containsExactly("LOCAL_VOLUME_2026-10-22");
        assertThat(localService.localCalls).isEqualTo(1);
        mockServer.verify();
    }

    @Test
    @DisplayName("REMOTE mode throws exception when remote fails and fallbackToLocal is false")
    void shouldThrowWhenRemoteFailsAndNoFallback() {
        ModulithBridgeProperties.ModuleRouteConfig route = new ModulithBridgeProperties.ModuleRouteConfig();
        route.setMode(BridgeMode.REMOTE);
        route.setUrl("http://remote-worker:8080");
        route.setFallbackToLocal(false);
        properties.getModules().put("metering", route);

        mockServer.expect(requestTo("http://remote-worker:8080/internal/modulith-bridge/invoke"))
                .andRespond(withServerError());

        LocalMeteringService localService = new LocalMeteringService();
        ModulithBridgeInvocationHandler handler = new ModulithBridgeInvocationHandler(
                localService,
                "metering",
                TestMeteringApi.class,
                properties,
                httpClient,
                objectMapper
        );

        TestMeteringApi proxy = (TestMeteringApi) Proxy.newProxyInstance(
                TestMeteringApi.class.getClassLoader(),
                new Class<?>[]{TestMeteringApi.class},
                handler
        );

        LocalDate date = LocalDate.of(2026, 10, 22);
        assertThatThrownBy(() -> proxy.getVolumes(date))
                .isInstanceOf(Exception.class);
        assertThat(localService.localCalls).isEqualTo(0);
        mockServer.verify();
    }

    @Test
    @DisplayName("Host-only node executes local bean directly even if module route was configured REMOTE")
    void shouldExecuteLocalWhenHostOnlyMatches() {
        properties.setHostOnly("metering");
        ModulithBridgeProperties.ModuleRouteConfig route = new ModulithBridgeProperties.ModuleRouteConfig();
        route.setMode(BridgeMode.REMOTE);
        route.setUrl("http://some-url");
        properties.getModules().put("metering", route);

        LocalMeteringService localService = new LocalMeteringService();
        ModulithBridgeInvocationHandler handler = new ModulithBridgeInvocationHandler(
                localService,
                "metering",
                TestMeteringApi.class,
                properties,
                httpClient,
                objectMapper
        );

        TestMeteringApi proxy = (TestMeteringApi) Proxy.newProxyInstance(
                TestMeteringApi.class.getClassLoader(),
                new Class<?>[]{TestMeteringApi.class},
                handler
        );

        List<String> result = proxy.getVolumes(LocalDate.of(2026, 10, 22));

        assertThat(result).containsExactly("LOCAL_VOLUME_2026-10-22");
        assertThat(localService.localCalls).isEqualTo(1);
        mockServer.verify(); // No HTTP call
    }

    @Test
    @DisplayName("RemoteBridgeProxyFactoryBean produces proxy delegating to BridgeHttpClient")
    void shouldProduceRemoteProxyFromFactoryBean() {
        ModulithBridgeProperties.ModuleRouteConfig route = new ModulithBridgeProperties.ModuleRouteConfig();
        route.setMode(BridgeMode.REMOTE);
        route.setUrl("http://worker-service:8080");
        properties.getModules().put("metering", route);

        String remoteResponseJson = """
                {
                    "result": ["FACTORY_VOLUME_2026-10-22"],
                    "durationMs": 8,
                    "moduleName": "metering",
                    "error": null
                }
                """;

        mockServer.expect(requestTo("http://worker-service:8080/internal/modulith-bridge/invoke"))
                .andRespond(withSuccess(remoteResponseJson, org.springframework.http.MediaType.APPLICATION_JSON));

        AnnotationConfigApplicationContext testContext = new AnnotationConfigApplicationContext();
        testContext.registerBean(ModulithBridgeProperties.class, () -> properties);
        testContext.registerBean(BridgeHttpClient.class, () -> httpClient);
        testContext.registerBean(ObjectMapper.class, () -> objectMapper);
        testContext.refresh();

        RemoteBridgeProxyFactoryBean<TestMeteringApi> factoryBean =
                new RemoteBridgeProxyFactoryBean<>(TestMeteringApi.class, "metering");
        factoryBean.setBeanFactory(testContext.getBeanFactory());

        TestMeteringApi proxy = factoryBean.getObject();
        assertThat(proxy).isNotNull();

        List<String> volumes = proxy.getVolumes(LocalDate.of(2026, 10, 22));
        assertThat(volumes).containsExactly("FACTORY_VOLUME_2026-10-22");
        mockServer.verify();
        testContext.close();
    }

    @Test
    @DisplayName("ModuleSliceRegistryPostProcessor prunes internal service and registers RemoteBridgeProxyFactoryBean when fallbackToLocal is false")
    void shouldPruneInternalAndRegisterRemoteProxyWhenNoFallback() {
        AnnotationConfigApplicationContext testContext = new AnnotationConfigApplicationContext();

        // Configure properties: metering mode=REMOTE, fallbackToLocal=false
        ModulithBridgeProperties testProperties = new ModulithBridgeProperties();
        testProperties.setBasePackage("com.builtbyjuls.grid");
        ModulithBridgeProperties.ModuleRouteConfig route = new ModulithBridgeProperties.ModuleRouteConfig();
        route.setMode(BridgeMode.REMOTE);
        route.setUrl("http://worker:8080");
        route.setFallbackToLocal(false);
        testProperties.getModules().put("metering", route);

        ModuleSliceRegistryPostProcessor postProcessor = new ModuleSliceRegistryPostProcessor("com.builtbyjuls.grid", null);
        postProcessor.setProperties(testProperties);
        testContext.registerBean("moduleSliceRegistryPostProcessor", ModuleSliceRegistryPostProcessor.class, () -> postProcessor);
        testContext.registerBean("modulithBridgeProperties", ModulithBridgeProperties.class, () -> testProperties);
        testContext.registerBean("bridgeHttpClient", BridgeHttpClient.class, () -> httpClient);

        // Register a simulated internal service bean under com.builtbyjuls.grid.metering.internal
        testContext.register(PrunableMeteringConfig.class);

        testContext.refresh();

        // The internal service bean should be pruned
        assertThat(testContext.containsBean("meteringInternalService")).isFalse();
        // A remote-only proxy FactoryBean should be registered for MeteringApi
        assertThat(testContext.containsBean("meteringApi")).isTrue();
        com.builtbyjuls.grid.metering.MeteringApi meteringApi = testContext.getBean(com.builtbyjuls.grid.metering.MeteringApi.class);
        assertThat(meteringApi).isNotNull();

        testContext.close();
    }

    @Test
    @DisplayName("Non-target interface methods are executed locally and never routed over HTTP")
    void shouldExecuteNonTargetMethodsLocally() throws Exception {
        ModulithBridgeProperties.ModuleRouteConfig route = new ModulithBridgeProperties.ModuleRouteConfig();
        route.setMode(BridgeMode.REMOTE);
        route.setUrl("http://remote-worker:8080");
        properties.getModules().put("metering", route);

        LocalMeteringService localService = new LocalMeteringService();
        ModulithBridgeInvocationHandler handler = new ModulithBridgeInvocationHandler(
                localService,
                "metering",
                TestMeteringApi.class,
                properties,
                httpClient,
                objectMapper
        );

        AutoCloseable proxy = (AutoCloseable) Proxy.newProxyInstance(
                TestMeteringApi.class.getClassLoader(),
                new Class<?>[]{TestMeteringApi.class, AutoCloseable.class},
                handler
        );

        proxy.close();

        assertThat(localService.closed).isTrue();
        mockServer.verify(); // No HTTP requests dispatched
    }

    @Test
    @DisplayName("REMOTE mode falls back to local bean when remote call times out")
    void shouldFallbackToLocalWhenRemoteTimesOut() {
        ModulithBridgeProperties.ModuleRouteConfig route = new ModulithBridgeProperties.ModuleRouteConfig();
        route.setMode(BridgeMode.REMOTE);
        route.setUrl("http://remote-worker:8080");
        route.setTimeoutMs(50L);
        route.setFallbackToLocal(true);
        properties.getModules().put("metering", route);

        CountDownLatch latch = new CountDownLatch(1);
        mockServer.expect(requestTo("http://remote-worker:8080/internal/modulith-bridge/invoke"))
                .andRespond(clientRequest -> {
                    try {
                        latch.await(500, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException ignored) {
                    }
                    return new org.springframework.mock.http.client.MockClientHttpResponse(new byte[0], org.springframework.http.HttpStatus.OK);
                });

        LocalMeteringService localService = new LocalMeteringService();
        ModulithBridgeInvocationHandler handler = new ModulithBridgeInvocationHandler(
                localService,
                "metering",
                TestMeteringApi.class,
                properties,
                httpClient,
                objectMapper
        );

        TestMeteringApi proxy = (TestMeteringApi) Proxy.newProxyInstance(
                TestMeteringApi.class.getClassLoader(),
                new Class<?>[]{TestMeteringApi.class},
                handler
        );

        try {
            List<String> result = proxy.getVolumes(LocalDate.of(2026, 10, 22));
            assertThat(result).containsExactly("LOCAL_VOLUME_2026-10-22");
            assertThat(localService.localCalls).isEqualTo(1);
        } finally {
            latch.countDown();
        }
    }

    @Configuration
    static class PrunableMeteringConfig {
        @Bean
        public com.builtbyjuls.grid.metering.internal.MockMeteringAggregationService meteringInternalService() {
            return new com.builtbyjuls.grid.metering.internal.MockMeteringAggregationService();
        }
    }
}

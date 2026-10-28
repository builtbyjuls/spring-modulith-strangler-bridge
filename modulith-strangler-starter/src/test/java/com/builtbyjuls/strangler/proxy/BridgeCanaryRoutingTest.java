package com.builtbyjuls.strangler.proxy;

import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.modulith.NamedInterface;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.builtbyjuls.strangler.config.BridgeMode;
import com.builtbyjuls.strangler.config.ModulithBridgeProperties;
import com.builtbyjuls.strangler.parity.ShadowParityRegistry;
import com.builtbyjuls.strangler.parity.ShadowParityResult;
import com.builtbyjuls.strangler.parity.ShadowParityVerifier;
import com.builtbyjuls.strangler.resilience.BridgeCircuitBreaker;
import com.builtbyjuls.strangler.transport.BridgeHttpClient;
import com.builtbyjuls.strangler.transport.BridgeInvocationResponse;
import com.builtbyjuls.strangler.transport.ModulithBridgeAdminController;
import com.builtbyjuls.strangler.transport.ModulithBridgeAdminController.RouteUpdateRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class BridgeCanaryRoutingTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private ModulithBridgeProperties properties;
    private RestClient.Builder restClientBuilder;
    private MockRestServiceServer mockServer;
    private BridgeHttpClient httpClient;
    private ShadowParityRegistry registry;
    private BridgeCircuitBreaker circuitBreaker;

    @NamedInterface("CanaryMeteringApi")
    public interface CanaryMeteringApi {
        List<String> getVolumes(LocalDate date);
    }

    public static class LocalCanaryMeteringService implements CanaryMeteringApi {
        int callCount = 0;

        @Override
        public List<String> getVolumes(LocalDate date) {
            callCount++;
            return List.of("LOCAL_VOLUME_" + date);
        }
    }

    @BeforeEach
    void setUp() {
        this.properties = new ModulithBridgeProperties();
        this.properties.setBasePackage("com.builtbyjuls.grid");
        this.registry = new ShadowParityRegistry();
        this.circuitBreaker = new BridgeCircuitBreaker(properties);

        ModulithBridgeProperties.ModuleRouteConfig route = new ModulithBridgeProperties.ModuleRouteConfig();
        route.setMode(BridgeMode.CANARY);
        route.setUrl("http://remote-canary:8080");
        route.setTimeoutMs(1000L);
        route.setFallbackToLocal(true);
        this.properties.getModules().put("metering", route);

        this.restClientBuilder = RestClient.builder();
        this.mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();
        this.httpClient = new BridgeHttpClient(restClientBuilder, objectMapper, properties);
    }

    @Test
    @DisplayName("CANARY mode with remotePercentage = 0.0 routes 100% of calls to LOCAL with zero HTTP requests")
    void shouldRoute100PercentToLocalWhenRemotePercentageZero() {
        properties.getModules().get("metering").setRemotePercentage(0.0);
        LocalCanaryMeteringService localService = new LocalCanaryMeteringService();
        CanaryMeteringApi proxy = createProxy(localService);

        LocalDate date = LocalDate.of(2026, 3, 20);
        for (int i = 0; i < 20; i++) {
            List<String> result = proxy.getVolumes(date);
            assertThat(result).containsExactly("LOCAL_VOLUME_2026-03-20");
        }

        assertThat(localService.callCount).isEqualTo(20);
        mockServer.verify();
    }

    @Test
    @DisplayName("CANARY mode with remotePercentage = 100.0 routes 100% of calls to REMOTE")
    void shouldRoute100PercentToRemoteWhenRemotePercentageHundred() throws Exception {
        properties.getModules().get("metering").setRemotePercentage(100.0);
        LocalCanaryMeteringService localService = new LocalCanaryMeteringService();
        CanaryMeteringApi proxy = createProxy(localService);

        String remoteResponseJson = objectMapper.writeValueAsString(
                BridgeInvocationResponse.success(objectMapper.valueToTree(List.of("REMOTE_VOLUME_2026-03-20")), 5, "metering")
        );

        mockServer.expect(ExpectedCount.times(5), requestTo("http://remote-canary:8080/internal/modulith-bridge/invoke"))
                .andRespond(withSuccess(remoteResponseJson, MediaType.APPLICATION_JSON));

        LocalDate date = LocalDate.of(2026, 3, 20);
        for (int i = 0; i < 5; i++) {
            List<String> result = proxy.getVolumes(date);
            assertThat(result).containsExactly("REMOTE_VOLUME_2026-03-20");
        }

        assertThat(localService.callCount).isZero();
        mockServer.verify();
    }

    @Test
    @DisplayName("CANARY mode with remotePercentage = 50.0 splits traffic across LOCAL and REMOTE over 100 calls")
    void shouldSplitTrafficWhenRemotePercentageFifty() throws Exception {
        properties.getModules().get("metering").setRemotePercentage(50.0);
        LocalCanaryMeteringService localService = new LocalCanaryMeteringService();
        CanaryMeteringApi proxy = createProxy(localService);

        String remoteResponseJson = objectMapper.writeValueAsString(
                BridgeInvocationResponse.success(objectMapper.valueToTree(List.of("REMOTE_VOLUME_SPLIT")), 5, "metering")
        );

        mockServer.expect(ExpectedCount.between(1, 99), requestTo("http://remote-canary:8080/internal/modulith-bridge/invoke"))
                .andRespond(withSuccess(remoteResponseJson, MediaType.APPLICATION_JSON));

        LocalDate date = LocalDate.of(2026, 3, 20);
        int totalCalls = 100;
        int remoteCalls = 0;

        for (int i = 0; i < totalCalls; i++) {
            List<String> result = proxy.getVolumes(date);
            if (result.contains("REMOTE_VOLUME_SPLIT")) {
                remoteCalls++;
            }
        }

        assertThat(localService.callCount).isGreaterThan(0);
        assertThat(remoteCalls).isGreaterThan(0);
        assertThat(localService.callCount + remoteCalls).isEqualTo(totalCalls);
        // With n=100 and p=0.5, localService.callCount will comfortably lie between 20 and 80
        assertThat(localService.callCount).isBetween(20, 80);
    }

    @Test
    @DisplayName("CANARY mode falls back to LOCAL and trips BridgeCircuitBreaker on failure, fast-failing subsequent calls")
    void shouldFallbackToLocalAndTripCircuitBreakerOnRemoteFailure() {
        properties.getModules().get("metering").setRemotePercentage(100.0);
        properties.setCircuitFailureThreshold(3);
        properties.setCircuitResetTimeoutMs(30000L);
        LocalCanaryMeteringService localService = new LocalCanaryMeteringService();
        CanaryMeteringApi proxy = createProxy(localService);

        mockServer.expect(ExpectedCount.times(3), requestTo("http://remote-canary:8080/internal/modulith-bridge/invoke"))
                .andRespond(withServerError());

        LocalDate date = LocalDate.of(2026, 3, 20);

        // 3 consecutive failures: each falls back to local and records failure
        for (int i = 0; i < 3; i++) {
            List<String> result = proxy.getVolumes(date);
            assertThat(result).containsExactly("LOCAL_VOLUME_2026-03-20");
        }

        assertThat(localService.callCount).isEqualTo(3);
        assertThat(circuitBreaker.getState("metering")).isEqualTo(BridgeCircuitBreaker.State.OPEN);

        // 4th call: circuit breaker is OPEN, fast-fails to local in 0ms without hitting mockServer
        long start = System.currentTimeMillis();
        List<String> fastFailResult = proxy.getVolumes(date);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(fastFailResult).containsExactly("LOCAL_VOLUME_2026-03-20");
        assertThat(localService.callCount).isEqualTo(4);
        assertThat(elapsed).isLessThan(50L);

        mockServer.verify();

        List<ShadowParityResult> records = registry.getResults();
        assertThat(records).hasSize(4);
        assertThat(records.get(3).fallbackTriggered()).isTrue();
        assertThat(records.get(3).remoteError()).contains("Circuit breaker OPEN");
    }

    @Test
    @DisplayName("ModulithBridgeAdminController dynamically updates mode=CANARY and remotePercentage")
    void shouldDynamicallyUpdateCanaryPercentageViaAdminController() throws Exception {
        ModulithBridgeAdminController adminController = new ModulithBridgeAdminController(properties, objectMapper);
        LocalCanaryMeteringService localService = new LocalCanaryMeteringService();
        CanaryMeteringApi proxy = createProxy(localService);

        // Step 1: Admin switches route to CANARY with 100% remote
        RouteUpdateRequest toRemote = new RouteUpdateRequest(
                BridgeMode.CANARY,
                "http://remote-canary:8080",
                1000L,
                true,
                null,
                100.0
        );
        adminController.updateRoute("metering", toRemote);

        String remoteResponseJson = objectMapper.writeValueAsString(
                BridgeInvocationResponse.success(objectMapper.valueToTree(List.of("REMOTE_AFTER_ADMIN")), 5, "metering")
        );
        mockServer.expect(ExpectedCount.once(), requestTo("http://remote-canary:8080/internal/modulith-bridge/invoke"))
                .andRespond(withSuccess(remoteResponseJson, MediaType.APPLICATION_JSON));

        LocalDate date = LocalDate.of(2026, 3, 20);
        List<String> result1 = proxy.getVolumes(date);
        assertThat(result1).containsExactly("REMOTE_AFTER_ADMIN");
        assertThat(localService.callCount).isZero();
        mockServer.verify();

        // Step 2: Admin switches canary percentage to 0% (full local)
        RouteUpdateRequest toLocal = new RouteUpdateRequest(
                BridgeMode.CANARY,
                "http://remote-canary:8080",
                1000L,
                true,
                null,
                0.0
        );
        adminController.updateRoute("metering", toLocal);

        List<String> result2 = proxy.getVolumes(date);
        assertThat(result2).containsExactly("LOCAL_VOLUME_2026-03-20");
        assertThat(localService.callCount).isEqualTo(1);
    }

    private CanaryMeteringApi createProxy(LocalCanaryMeteringService localService) {
        ModulithBridgeInvocationHandler handler = new ModulithBridgeInvocationHandler(
                localService,
                "metering",
                CanaryMeteringApi.class,
                properties,
                httpClient,
                objectMapper,
                new ShadowParityVerifier(objectMapper),
                registry,
                null,
                circuitBreaker
        );

        return (CanaryMeteringApi) Proxy.newProxyInstance(
                CanaryMeteringApi.class.getClassLoader(),
                new Class<?>[]{CanaryMeteringApi.class},
                handler
        );
    }
}

package com.builtbyjuls.strangler.resilience;

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
import com.builtbyjuls.strangler.proxy.ModulithBridgeInvocationHandler;
import com.builtbyjuls.strangler.transport.BridgeBusinessException;
import com.builtbyjuls.strangler.transport.BridgeHttpClient;
import com.builtbyjuls.strangler.transport.BridgeInvocationResponse;
import com.builtbyjuls.strangler.transport.BridgeRemoteInvocationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class BridgeCircuitBreakerTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private ModulithBridgeProperties properties;
    private BridgeCircuitBreaker circuitBreaker;
    private RestClient.Builder restClientBuilder;
    private MockRestServiceServer mockServer;
    private BridgeHttpClient httpClient;
    private ShadowParityRegistry registry;

    @NamedInterface("TestMeteringApi")
    public interface TestMeteringApi {
        List<String> getVolumes(LocalDate date);
    }

    public static class LocalMeteringService implements TestMeteringApi {
        int localCalls = 0;

        @Override
        public List<String> getVolumes(LocalDate date) {
            localCalls++;
            return List.of("LOCAL_VOLUME_" + date);
        }
    }

    @BeforeEach
    void setUp() {
        this.properties = new ModulithBridgeProperties();
        this.properties.setCircuitFailureThreshold(3);
        this.properties.setCircuitResetTimeoutMs(150L);

        ModulithBridgeProperties.ModuleRouteConfig route = new ModulithBridgeProperties.ModuleRouteConfig();
        route.setMode(BridgeMode.REMOTE);
        route.setUrl("http://remote-metering:8080");
        route.setTimeoutMs(1000L);
        route.setFallbackToLocal(true);
        this.properties.getModules().put("metering", route);

        this.circuitBreaker = new BridgeCircuitBreaker(properties);
        this.restClientBuilder = RestClient.builder();
        this.mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();
        this.httpClient = new BridgeHttpClient(restClientBuilder, objectMapper);
        this.registry = new ShadowParityRegistry();
    }

    @Test
    @DisplayName("Circuit transitions from CLOSED to OPEN after consecutive transport failures reach threshold")
    void shouldTransitionClosedToOpenOnThresholdFailures() {
        assertThat(circuitBreaker.getState("metering")).isEqualTo(BridgeCircuitBreaker.State.CLOSED);
        assertThat(circuitBreaker.allowExecution("metering")).isTrue();

        circuitBreaker.recordFailure("metering");
        assertThat(circuitBreaker.getState("metering")).isEqualTo(BridgeCircuitBreaker.State.CLOSED);

        circuitBreaker.recordFailure("metering");
        assertThat(circuitBreaker.getState("metering")).isEqualTo(BridgeCircuitBreaker.State.CLOSED);

        // 3rd failure reaches threshold
        circuitBreaker.recordFailure("metering");
        assertThat(circuitBreaker.getState("metering")).isEqualTo(BridgeCircuitBreaker.State.OPEN);
        assertThat(circuitBreaker.allowExecution("metering")).isFalse();
    }

    @Test
    @DisplayName("Circuit allows a single probe and transitions to HALF_OPEN after reset timeout elapses")
    void shouldTransitionOpenToHalfOpenAfterResetTimeout() throws InterruptedException {
        circuitBreaker.recordFailure("metering");
        circuitBreaker.recordFailure("metering");
        circuitBreaker.recordFailure("metering");
        assertThat(circuitBreaker.getState("metering")).isEqualTo(BridgeCircuitBreaker.State.OPEN);

        // Before reset timeout elapses, execution is blocked
        assertThat(circuitBreaker.allowExecution("metering")).isFalse();

        // Wait for reset timeout (150ms) to elapse
        Thread.sleep(170);

        // First call allows probe and transitions state atomically to HALF_OPEN
        assertThat(circuitBreaker.allowExecution("metering")).isTrue();
        assertThat(circuitBreaker.getState("metering")).isEqualTo(BridgeCircuitBreaker.State.HALF_OPEN);

        // Concurrent call while probe is in flight is rejected
        assertThat(circuitBreaker.allowExecution("metering")).isFalse();
    }

    @Test
    @DisplayName("Probe success resets failures to 0 and transitions HALF_OPEN back to CLOSED")
    void shouldRecoverToClosedOnProbeSuccess() throws InterruptedException {
        circuitBreaker.recordFailure("metering");
        circuitBreaker.recordFailure("metering");
        circuitBreaker.recordFailure("metering");
        assertThat(circuitBreaker.getState("metering")).isEqualTo(BridgeCircuitBreaker.State.OPEN);

        Thread.sleep(170);

        // Probe allowed
        assertThat(circuitBreaker.allowExecution("metering")).isTrue();
        assertThat(circuitBreaker.getState("metering")).isEqualTo(BridgeCircuitBreaker.State.HALF_OPEN);

        // Probe succeeds
        circuitBreaker.recordSuccess("metering");
        assertThat(circuitBreaker.getState("metering")).isEqualTo(BridgeCircuitBreaker.State.CLOSED);
        assertThat(circuitBreaker.allowExecution("metering")).isTrue();
    }

    @Test
    @DisplayName("Probe failure in HALF_OPEN transitions immediately back to OPEN")
    void shouldReopenWhenProbeFails() throws InterruptedException {
        circuitBreaker.recordFailure("metering");
        circuitBreaker.recordFailure("metering");
        circuitBreaker.recordFailure("metering");
        assertThat(circuitBreaker.getState("metering")).isEqualTo(BridgeCircuitBreaker.State.OPEN);

        Thread.sleep(170);

        assertThat(circuitBreaker.allowExecution("metering")).isTrue();
        assertThat(circuitBreaker.getState("metering")).isEqualTo(BridgeCircuitBreaker.State.HALF_OPEN);

        // Probe fails
        circuitBreaker.recordFailure("metering");
        assertThat(circuitBreaker.getState("metering")).isEqualTo(BridgeCircuitBreaker.State.OPEN);
        assertThat(circuitBreaker.allowExecution("metering")).isFalse();
    }

    @Test
    @DisplayName("Fast-fail to LOCAL in OPEN state executes 0ms fallback with zero HTTP requests to remote worker")
    void shouldFastFailToLocalWithoutHttpRequestsWhenCircuitIsOpen() {
        LocalMeteringService localService = new LocalMeteringService();
        ModulithBridgeInvocationHandler handler = new ModulithBridgeInvocationHandler(
                localService,
                "metering",
                TestMeteringApi.class,
                properties,
                httpClient,
                objectMapper,
                null,
                registry,
                null,
                circuitBreaker
        );

        TestMeteringApi proxy = (TestMeteringApi) Proxy.newProxyInstance(
                TestMeteringApi.class.getClassLoader(),
                new Class<?>[]{TestMeteringApi.class},
                handler
        );

        // Expect 3 failed remote HTTP calls
        mockServer.expect(requestTo("http://remote-metering:8080/internal/modulith-bridge/invoke"))
                .andRespond(withServerError());
        mockServer.expect(requestTo("http://remote-metering:8080/internal/modulith-bridge/invoke"))
                .andRespond(withServerError());
        mockServer.expect(requestTo("http://remote-metering:8080/internal/modulith-bridge/invoke"))
                .andRespond(withServerError());

        // Invocations 1, 2, 3 fail over HTTP and fallback to local
        for (int i = 0; i < 3; i++) {
            List<String> result = proxy.getVolumes(LocalDate.of(2026, 10, 22));
            assertThat(result).containsExactly("LOCAL_VOLUME_2026-10-22");
        }
        assertThat(localService.localCalls).isEqualTo(3);
        mockServer.verify();

        // Circuit is now OPEN!
        assertThat(circuitBreaker.getState("metering")).isEqualTo(BridgeCircuitBreaker.State.OPEN);

        // 4th call: Fast-fails to local with ZERO HTTP expectations and ZERO requests dispatched!
        // No mockServer expectations defined! If it touched HTTP, mockServer would throw AssertionError!
        long start = System.currentTimeMillis();
        List<String> fastFailResult = proxy.getVolumes(LocalDate.of(2026, 10, 22));
        long duration = System.currentTimeMillis() - start;

        assertThat(fastFailResult).containsExactly("LOCAL_VOLUME_2026-10-22");
        assertThat(localService.localCalls).isEqualTo(4);
        assertThat(duration).isLessThan(100L); // Fast-fail in 0ms range, not blocked by timeout

        // Verify fallback record in registry
        List<ShadowParityResult> fallbacks = registry.getResults().stream()
                .filter(ShadowParityResult::fallbackTriggered)
                .toList();
        assertThat(fallbacks).hasSize(4);
        ShadowParityResult circuitFallback = fallbacks.get(3);
        assertThat(circuitFallback.diffSummary())
                .isEqualTo("Circuit breaker OPEN for module 'metering', fast-falling back to local");
        assertThat(circuitFallback.remoteDurationMs()).isEqualTo(0L);
    }

    @Test
    @DisplayName("Remote call throws BridgeRemoteInvocationException when circuit is OPEN and fallbackToLocal is false")
    void shouldThrowExceptionWhenCircuitOpenAndFallbackDisabled() {
        properties.getModules().get("metering").setFallbackToLocal(false);

        circuitBreaker.recordFailure("metering");
        circuitBreaker.recordFailure("metering");
        circuitBreaker.recordFailure("metering");
        assertThat(circuitBreaker.getState("metering")).isEqualTo(BridgeCircuitBreaker.State.OPEN);

        LocalMeteringService localService = new LocalMeteringService();
        ModulithBridgeInvocationHandler handler = new ModulithBridgeInvocationHandler(
                localService,
                "metering",
                TestMeteringApi.class,
                properties,
                httpClient,
                objectMapper,
                null,
                registry,
                null,
                circuitBreaker
        );

        TestMeteringApi proxy = (TestMeteringApi) Proxy.newProxyInstance(
                TestMeteringApi.class.getClassLoader(),
                new Class<?>[]{TestMeteringApi.class},
                handler
        );

        assertThatThrownBy(() -> proxy.getVolumes(LocalDate.of(2026, 10, 22)))
                .isInstanceOf(BridgeRemoteInvocationException.class)
                .hasMessageContaining("Circuit breaker is OPEN for module 'metering'");
    }

    @Test
    @DisplayName("BridgeBusinessException does not record failure and does not trip circuit breaker")
    void shouldNotTripCircuitOnBusinessException() throws Exception {
        LocalMeteringService localService = new LocalMeteringService();
        ModulithBridgeInvocationHandler handler = new ModulithBridgeInvocationHandler(
                localService,
                "metering",
                TestMeteringApi.class,
                properties,
                httpClient,
                objectMapper,
                null,
                registry,
                null,
                circuitBreaker
        );

        TestMeteringApi proxy = (TestMeteringApi) Proxy.newProxyInstance(
                TestMeteringApi.class.getClassLoader(),
                new Class<?>[]{TestMeteringApi.class},
                handler
        );

        BridgeInvocationResponse businessErrorResponse = BridgeInvocationResponse.businessError(
                "Trading date is out of range",
                "java.lang.IllegalArgumentException",
                5L,
                "metering"
        );
        String json = objectMapper.writeValueAsString(businessErrorResponse);

        // Expect 5 business exception responses (more than failure threshold 3)
        mockServer.expect(ExpectedCount.times(5), requestTo("http://remote-metering:8080/internal/modulith-bridge/invoke"))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> proxy.getVolumes(LocalDate.of(2026, 10, 22)))
                    .isInstanceOf(BridgeBusinessException.class)
                    .hasMessageContaining("Trading date is out of range");
        }

        mockServer.verify();

        // Circuit breaker must still be CLOSED!
        assertThat(circuitBreaker.getState("metering")).isEqualTo(BridgeCircuitBreaker.State.CLOSED);
        assertThat(circuitBreaker.allowExecution("metering")).isTrue();
    }

    @Test
    @DisplayName("Disabling circuit breaking (threshold <= 0) always permits execution")
    void shouldDisableCircuitBreakingWhenThresholdZeroOrNegative() {
        properties.setCircuitFailureThreshold(0);

        for (int i = 0; i < 10; i++) {
            circuitBreaker.recordFailure("metering");
        }

        assertThat(circuitBreaker.getState("metering")).isEqualTo(BridgeCircuitBreaker.State.CLOSED);
        assertThat(circuitBreaker.allowExecution("metering")).isTrue();
    }

    @Test
    @DisplayName("reset() resets all module circuits to CLOSED")
    void shouldResetAllCircuits() {
        circuitBreaker.recordFailure("metering");
        circuitBreaker.recordFailure("metering");
        circuitBreaker.recordFailure("metering");
        assertThat(circuitBreaker.getState("metering")).isEqualTo(BridgeCircuitBreaker.State.OPEN);

        circuitBreaker.reset();
        assertThat(circuitBreaker.getState("metering")).isEqualTo(BridgeCircuitBreaker.State.CLOSED);
        assertThat(circuitBreaker.allowExecution("metering")).isTrue();
    }
}

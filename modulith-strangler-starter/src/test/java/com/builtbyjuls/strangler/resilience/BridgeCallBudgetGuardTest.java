package com.builtbyjuls.strangler.resilience;

import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.NamedInterface;

import com.builtbyjuls.strangler.config.ModulithBridgeProperties;
import com.builtbyjuls.strangler.proxy.ModulithBridgeInvocationHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BridgeCallBudgetGuardTest {

    private ModulithBridgeProperties properties;
    private BridgeCallBudgetGuard guard;

    @NamedInterface("TestMeteringApi")
    public interface TestMeteringApi {
        List<String> getVolumes(LocalDate date);
    }

    public static class LocalMeteringService implements TestMeteringApi {
        int callCount = 0;

        @Override
        public List<String> getVolumes(LocalDate date) {
            callCount++;
            return List.of("VOLUME_" + date);
        }
    }

    @BeforeEach
    void setUp() {
        this.properties = new ModulithBridgeProperties();
        this.properties.setMaxCallsPerWindow(5);
        this.properties.setCallWindowMs(200L);
        this.properties.setFailOnChattyBoundary(false);
        this.guard = new BridgeCallBudgetGuard(properties);
    }

    @Test
    @DisplayName("Invocations within budget do not increment violations or throw exceptions")
    void shouldPermitCallsWithinBudget() {
        for (int i = 0; i < 5; i++) {
            guard.checkAndRecord("metering", TestMeteringApi.class.getName(), "getVolumes");
        }

        assertThat(guard.getViolationCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("Tight loop exceeding maxCallsPerWindow increments violation count when failOnChattyBoundary=false")
    void shouldRecordViolationWhenExceedingBudgetWithoutFailing() {
        for (int i = 0; i < 5; i++) {
            guard.checkAndRecord("metering", TestMeteringApi.class.getName(), "getVolumes");
        }
        assertThat(guard.getViolationCount()).isEqualTo(0);

        // 6th call exceeds budget (5)
        guard.checkAndRecord("metering", TestMeteringApi.class.getName(), "getVolumes");
        assertThat(guard.getViolationCount()).isEqualTo(1);

        // 7th call exceeds again
        guard.checkAndRecord("metering", TestMeteringApi.class.getName(), "getVolumes");
        assertThat(guard.getViolationCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("Tight loop exceeding maxCallsPerWindow throws ChattyModuleBoundaryException when failOnChattyBoundary=true")
    void shouldThrowExceptionWhenFailOnChattyBoundaryIsTrue() {
        properties.setFailOnChattyBoundary(true);

        for (int i = 0; i < 5; i++) {
            guard.checkAndRecord("metering", TestMeteringApi.class.getName(), "getVolumes");
        }

        assertThatThrownBy(() -> guard.checkAndRecord("metering", TestMeteringApi.class.getName(), "getVolumes"))
                .isInstanceOf(ChattyModuleBoundaryException.class)
                .satisfies(ex -> {
                    ChattyModuleBoundaryException chatty = (ChattyModuleBoundaryException) ex;
                    assertThat(chatty.getModuleName()).isEqualTo("metering");
                    assertThat(chatty.getInterfaceName()).isEqualTo(TestMeteringApi.class.getName());
                    assertThat(chatty.getMethodName()).isEqualTo("getVolumes");
                    assertThat(chatty.getCallCount()).isEqualTo(6);
                    assertThat(chatty.getWindowMs()).isEqualTo(200L);
                    assertThat(chatty.getMessage()).contains("Chatty module boundary detected: 6 invocations");
                    assertThat(chatty.getMessage()).contains("Consider refactoring to a batch operation");
                });

        assertThat(guard.getViolationCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Calls spaced beyond callWindowMs prune expired timestamps and do not violate budget")
    void shouldPruneExpiredTimestampsAfterWindowElapses() throws InterruptedException {
        for (int i = 0; i < 4; i++) {
            guard.checkAndRecord("metering", TestMeteringApi.class.getName(), "getVolumes");
        }

        // Wait for sliding window (200ms) to elapse
        Thread.sleep(220);

        // Subsequent calls should not exceed budget because previous 4 expired
        for (int i = 0; i < 4; i++) {
            guard.checkAndRecord("metering", TestMeteringApi.class.getName(), "getVolumes");
        }

        assertThat(guard.getViolationCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("Disabling maxCallsPerWindow (<= 0) permits unlimited calls without tracking")
    void shouldDisableLoopDetectionWhenMaxCallsPerWindowIsZeroOrNegative() {
        properties.setMaxCallsPerWindow(0);
        properties.setFailOnChattyBoundary(true);

        for (int i = 0; i < 20; i++) {
            guard.checkAndRecord("metering", TestMeteringApi.class.getName(), "getVolumes");
        }

        assertThat(guard.getViolationCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("resetCurrentThread clears thread local history while clearAll also resets violation count")
    void shouldResetThreadAndClearAll() {
        properties.setFailOnChattyBoundary(false);

        for (int i = 0; i < 6; i++) {
            guard.checkAndRecord("metering", TestMeteringApi.class.getName(), "getVolumes");
        }
        assertThat(guard.getViolationCount()).isEqualTo(1);

        guard.resetCurrentThread();

        // After thread reset, new calls start fresh
        for (int i = 0; i < 5; i++) {
            guard.checkAndRecord("metering", TestMeteringApi.class.getName(), "getVolumes");
        }
        // Still 1 from before
        assertThat(guard.getViolationCount()).isEqualTo(1);

        guard.clearAll();
        assertThat(guard.getViolationCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("Proxied @NamedInterface method throws ChattyModuleBoundaryException in LOCAL loop when failOnChattyBoundary=true")
    void shouldEnforceCallBudgetThroughDynamicProxy() {
        properties.setMaxCallsPerWindow(3);
        properties.setFailOnChattyBoundary(true);

        LocalMeteringService localService = new LocalMeteringService();
        ModulithBridgeInvocationHandler handler = new ModulithBridgeInvocationHandler(
                localService,
                "metering",
                TestMeteringApi.class,
                properties,
                null,
                null,
                null,
                null,
                guard,
                null
        );

        TestMeteringApi proxy = (TestMeteringApi) Proxy.newProxyInstance(
                TestMeteringApi.class.getClassLoader(),
                new Class<?>[]{TestMeteringApi.class},
                handler
        );

        // 3 calls succeed
        assertThatCode(() -> {
            for (int i = 0; i < 3; i++) {
                proxy.getVolumes(LocalDate.of(2026, 10, 22));
            }
        }).doesNotThrowAnyException();

        assertThat(localService.callCount).isEqualTo(3);

        // 4th call in tight loop fails
        assertThatThrownBy(() -> proxy.getVolumes(LocalDate.of(2026, 10, 22)))
                .isInstanceOf(ChattyModuleBoundaryException.class)
                .hasMessageContaining("Chatty module boundary detected");

        assertThat(localService.callCount).isEqualTo(3); // 4th call did not reach target
    }
}

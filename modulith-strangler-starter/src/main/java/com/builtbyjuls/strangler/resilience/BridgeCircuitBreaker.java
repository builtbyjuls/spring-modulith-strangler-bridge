package com.builtbyjuls.strangler.resilience;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.builtbyjuls.strangler.config.ModulithBridgeProperties;

/**
 * Fast-fail circuit breaker that tracks remote transport health per module
 * and routes calls directly to local fallback when the remote worker is unhealthy.
 */
public class BridgeCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(BridgeCircuitBreaker.class);

    /**
     * Circuit breaker operational state.
     */
    public enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    private final ModulithBridgeProperties properties;
    private final Map<String, ModuleCircuit> circuits = new ConcurrentHashMap<>();

    public BridgeCircuitBreaker() {
        this(new ModulithBridgeProperties());
    }

    public BridgeCircuitBreaker(ModulithBridgeProperties properties) {
        this.properties = properties != null ? properties : new ModulithBridgeProperties();
    }

    /**
     * Checks whether execution against the remote module is currently allowed.
     *
     * @param moduleName target module name
     * @return true if call is permitted (CLOSED or single HALF_OPEN probe), false if fast-failed
     */
    public boolean allowExecution(String moduleName) {
        if (properties.getCircuitFailureThreshold() <= 0) {
            return true;
        }

        String key = normalizeKey(moduleName);
        ModuleCircuit circuit = circuits.computeIfAbsent(key, k -> new ModuleCircuit());

        State current = circuit.state.get();
        if (current == State.CLOSED) {
            return true;
        }

        if (current == State.OPEN) {
            long now = System.currentTimeMillis();
            long openedAt = circuit.openedAtMs.get();
            if (now - openedAt >= properties.getCircuitResetTimeoutMs()) {
                if (circuit.state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                    log.info("Circuit breaker for module '{}' entered HALF_OPEN state; allowing probe request", key);
                    return true;
                }
            }
            return false;
        }

        // HALF_OPEN: concurrent calls rejected while probe is in flight
        return false;
    }

    /**
     * Records a successful remote invocation, resetting consecutive failures and closing the circuit.
     *
     * @param moduleName target module name
     */
    public void recordSuccess(String moduleName) {
        if (properties.getCircuitFailureThreshold() <= 0) {
            return;
        }

        String key = normalizeKey(moduleName);
        ModuleCircuit circuit = circuits.computeIfAbsent(key, k -> new ModuleCircuit());
        circuit.consecutiveFailures.set(0);
        State previous = circuit.state.getAndSet(State.CLOSED);
        if (previous != State.CLOSED) {
            log.info("Circuit breaker for module '{}' recovered to CLOSED state", key);
        }
    }

    /**
     * Records a failed remote transport invocation, incrementing consecutive failures and tripping to OPEN if threshold reached.
     *
     * @param moduleName target module name
     */
    public void recordFailure(String moduleName) {
        if (properties.getCircuitFailureThreshold() <= 0) {
            return;
        }

        String key = normalizeKey(moduleName);
        ModuleCircuit circuit = circuits.computeIfAbsent(key, k -> new ModuleCircuit());
        int failures = circuit.consecutiveFailures.incrementAndGet();
        int threshold = properties.getCircuitFailureThreshold();

        if (circuit.state.get() == State.HALF_OPEN || (threshold > 0 && failures >= threshold)) {
            circuit.openedAtMs.set(System.currentTimeMillis());
            circuit.state.set(State.OPEN);
            log.warn("Circuit breaker for module '{}' tripped OPEN (consecutive failures: {}, threshold: {})",
                    key, failures, threshold);
        }
    }

    /**
     * Returns the current operational state for the specified module circuit.
     *
     * @param moduleName target module name
     * @return current circuit state
     */
    public State getState(String moduleName) {
        if (properties.getCircuitFailureThreshold() <= 0) {
            return State.CLOSED;
        }

        String key = normalizeKey(moduleName);
        ModuleCircuit circuit = circuits.get(key);
        return circuit != null ? circuit.state.get() : State.CLOSED;
    }

    /**
     * Resets all module circuits to CLOSED state.
     */
    public void reset() {
        circuits.clear();
    }

    private String normalizeKey(String moduleName) {
        return moduleName != null ? moduleName.trim().toLowerCase() : "";
    }

    /**
     * Internal state holder for a module's circuit.
     */
    public static class ModuleCircuit {
        private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
        private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
        private final AtomicLong openedAtMs = new AtomicLong(0L);

        public State getState() {
            return state.get();
        }

        public int getConsecutiveFailures() {
            return consecutiveFailures.get();
        }

        public long getOpenedAtMs() {
            return openedAtMs.get();
        }
    }
}

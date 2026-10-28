package com.builtbyjuls.strangler.resilience;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.builtbyjuls.strangler.config.ModulithBridgeProperties;

/**
 * Detects tight loop invocations across module boundaries (N+1 chatty RPC anti-pattern)
 * within a sliding time window per thread.
 */
public class BridgeCallBudgetGuard {

    private static final Logger log = LoggerFactory.getLogger(BridgeCallBudgetGuard.class);

    private final ModulithBridgeProperties properties;
    private final ThreadLocal<Map<String, Deque<Long>>> historyThreadLocal = ThreadLocal.withInitial(HashMap::new);
    private final AtomicLong violationCount = new AtomicLong(0L);

    public BridgeCallBudgetGuard() {
        this(new ModulithBridgeProperties());
    }

    public BridgeCallBudgetGuard(ModulithBridgeProperties properties) {
        this.properties = properties != null ? properties : new ModulithBridgeProperties();
    }

    /**
     * Records an invocation of the specified module API method on the current thread and checks
     * whether the call frequency exceeds the sliding window budget.
     *
     * @param moduleName    target module name
     * @param interfaceName target interface name
     * @param methodName    target method name
     */
    public void checkAndRecord(String moduleName, String interfaceName, String methodName) {
        if (properties.getMaxCallsPerWindow() <= 0) {
            return;
        }

        long windowMs = properties.getCallWindowMs();
        long now = System.currentTimeMillis();
        long cutoff = now - windowMs;

        String key = (moduleName != null ? moduleName.toLowerCase() : "") + "#" + methodName;
        Map<String, Deque<Long>> map = historyThreadLocal.get();
        Deque<Long> deque = map.computeIfAbsent(key, k -> new ArrayDeque<>());

        while (!deque.isEmpty() && deque.peekFirst() <= cutoff) {
            deque.pollFirst();
        }

        deque.addLast(now);

        int currentCount = deque.size();
        if (currentCount > properties.getMaxCallsPerWindow()) {
            violationCount.incrementAndGet();
            if (properties.isFailOnChattyBoundary()) {
                throw new ChattyModuleBoundaryException(moduleName, interfaceName, methodName, currentCount, windowMs);
            }
            log.warn("Chatty module boundary detected: {} invocations of '{}.{}' (module '{}') within {}ms window. "
                    + "Consider refactoring to a batch operation (e.g. Collection/List) or using Spring Modulith @Externalized events.",
                    currentCount, interfaceName, methodName, moduleName, windowMs);
        }
    }

    /**
     * Returns the cumulative number of chatty boundary violations detected across all threads.
     *
     * @return violation count
     */
    public long getViolationCount() {
        return violationCount.get();
    }

    /**
     * Resets call history for the calling thread.
     */
    public void resetCurrentThread() {
        historyThreadLocal.remove();
    }

    /**
     * Clears all recorded violations and resets call history for the calling thread.
     */
    public void clearAll() {
        violationCount.set(0L);
        resetCurrentThread();
    }
}

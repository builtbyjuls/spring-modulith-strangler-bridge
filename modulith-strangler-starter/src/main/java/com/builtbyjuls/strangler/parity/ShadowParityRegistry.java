package com.builtbyjuls.strangler.parity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Thread-safe in-memory registry recording shadow parity verification outcomes
 * and remote-to-local fallback events.
 */
public class ShadowParityRegistry {

    private final ConcurrentLinkedQueue<ShadowParityResult> results = new ConcurrentLinkedQueue<>();

    /**
     * Records a parity verification outcome or fallback event.
     *
     * @param result the parity result to record
     */
    public void record(ShadowParityResult result) {
        Objects.requireNonNull(result, "result must not be null");
        results.add(result);
    }

    /**
     * Returns an unmodifiable snapshot list of all recorded parity results.
     */
    public List<ShadowParityResult> getResults() {
        return Collections.unmodifiableList(new ArrayList<>(results));
    }

    /**
     * Returns a list of all recorded mismatch results.
     */
    public List<ShadowParityResult> getMismatches() {
        return results.stream()
                .filter(r -> !r.matched())
                .toList();
    }

    /**
     * Returns the total count of events where fallback was triggered.
     */
    public long getFallbackCount() {
        return results.stream()
                .filter(ShadowParityResult::fallbackTriggered)
                .count();
    }

    /**
     * Returns the total count of matched parity results.
     */
    public long getMatchCount() {
        return results.stream()
                .filter(ShadowParityResult::matched)
                .count();
    }

    /**
     * Returns the total number of recorded parity events.
     */
    public long getTotalCount() {
        return results.size();
    }

    /**
     * Clears all recorded parity results.
     */
    public void clear() {
        results.clear();
    }
}

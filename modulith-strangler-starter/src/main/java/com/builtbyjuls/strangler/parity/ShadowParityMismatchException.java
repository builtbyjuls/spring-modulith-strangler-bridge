package com.builtbyjuls.strangler.parity;

import java.util.Collections;
import java.util.List;

/**
 * Thrown when shadow parity mismatches are detected and failOnMismatch is enabled
 * or when the parity gate is explicitly verified.
 */
public class ShadowParityMismatchException extends RuntimeException {

    private final long mismatchCount;
    private final List<ShadowParityResult> mismatches;

    public ShadowParityMismatchException(long mismatchCount, List<ShadowParityResult> mismatches) {
        super(buildMessage(mismatchCount, mismatches));
        this.mismatchCount = mismatchCount;
        this.mismatches = mismatches != null ? List.copyOf(mismatches) : Collections.emptyList();
    }

    public long getMismatchCount() {
        return mismatchCount;
    }

    public List<ShadowParityResult> getMismatches() {
        return mismatches;
    }

    private static String buildMessage(long mismatchCount, List<ShadowParityResult> mismatches) {
        StringBuilder sb = new StringBuilder();
        sb.append("Shadow parity gate failed with ").append(mismatchCount).append(" mismatch(es):");
        if (mismatches != null) {
            for (ShadowParityResult m : mismatches) {
                sb.append("\n - [").append(m.moduleName()).append("] ")
                        .append(m.interfaceName()).append(".").append(m.methodName())
                        .append(": ").append(m.diffSummary() != null ? m.diffSummary() : "diverged");
            }
        }
        return sb.toString();
    }
}

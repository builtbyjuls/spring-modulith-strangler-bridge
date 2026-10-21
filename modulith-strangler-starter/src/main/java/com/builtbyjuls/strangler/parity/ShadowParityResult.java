package com.builtbyjuls.strangler.parity;

/**
 * Audit record holding parity verification results between local and remote module execution.
 *
 * @param moduleName        name of the target module (e.g. metering)
 * @param interfaceName     fully qualified name of the target interface
 * @param methodName        name of the invoked method
 * @param matched           whether local and remote outcomes matched
 * @param localDurationMs   local execution duration in milliseconds
 * @param remoteDurationMs  remote execution duration in milliseconds
 * @param diffSummary       concise mismatch description or null when matched
 * @param remoteError       error message if remote call threw an exception or null
 * @param fallbackTriggered whether fallback to local occurred or shadow mode caught a remote error
 */
public record ShadowParityResult(
        String moduleName,
        String interfaceName,
        String methodName,
        boolean matched,
        long localDurationMs,
        long remoteDurationMs,
        String diffSummary,
        String remoteError,
        boolean fallbackTriggered
) {
}

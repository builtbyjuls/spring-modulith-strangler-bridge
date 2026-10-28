package com.builtbyjuls.strangler.config;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the Spring Modulith Strangler Bridge.
 */
@ConfigurationProperties(prefix = "modulith.bridge")
public class ModulithBridgeProperties {

    /**
     * Whether the strangler bridge and runtime slicer are enabled.
     */
    private boolean enabled = true;

    /**
     * Base package containing application modules (e.g. com.builtbyjuls.grid).
     */
    private String basePackage;

    /**
     * Optional module name to host exclusively in this JVM process.
     */
    private String hostOnly;

    /**
     * Optional pre-shared secret token for inbound bridge authentication.
     */
    private String secretToken;

    /**
     * In shadow mode, whether caller threads block awaiting remote execution.
     */
    private boolean shadowAwait = false;

    /**
     * Whether to automatically export structured strangler reports on shutdown.
     */
    private boolean exportReports = true;

    /**
     * Directory path where parity reports and container manifests are exported.
     */
    private String reportDir = "target/strangler-reports";

    /**
     * Whether to log a formatted ASCII summary banner to standard console output on export.
     */
    private boolean logConsoleSummary = true;

    /**
     * Whether report export fails with an exception when shadow parity mismatches are detected.
     */
    private boolean failOnMismatch = false;

    /**
     * Whether to validate module boundary interface contracts at startup.
     */
    private boolean validateContracts = true;

    /**
     * Whether to execute write invocations inside a rollback-only transaction in shadow mode.
     */
    private boolean shadowRollbackWrites = true;

    /**
     * Global sampling rate for shadow mode invocations (0.0 to 1.0).
     */
    private double shadowSampleRate = 1.0;

    /**
     * Maximum invocations permitted per thread within callWindowMs before triggering chatty loop detection.
     * Values <= 0 disable chatty loop detection.
     */
    private int maxCallsPerWindow = 10;

    /**
     * Sliding window duration in milliseconds for chatty RPC loop detection.
     */
    private long callWindowMs = 250L;

    /**
     * Whether exceeding maxCallsPerWindow throws ChattyModuleBoundaryException instead of logging a warning.
     */
    private boolean failOnChattyBoundary = false;

    /**
     * Number of consecutive transport failures before tripping the circuit breaker to OPEN.
     * Values <= 0 disable circuit breaking.
     */
    private int circuitFailureThreshold = 3;

    /**
     * Duration in milliseconds the circuit breaker remains OPEN before probing with HALF_OPEN.
     */
    private long circuitResetTimeoutMs = 30000L;

    /**
     * Per-module routing configurations.
     */
    private Map<String, ModuleRouteConfig> modules = new ConcurrentHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBasePackage() {
        return basePackage;
    }

    public void setBasePackage(String basePackage) {
        this.basePackage = basePackage;
    }

    public String getHostOnly() {
        return hostOnly;
    }

    public void setHostOnly(String hostOnly) {
        this.hostOnly = hostOnly;
    }

    public String getSecretToken() {
        return secretToken;
    }

    public void setSecretToken(String secretToken) {
        this.secretToken = secretToken;
    }

    public boolean isShadowAwait() {
        return shadowAwait;
    }

    public void setShadowAwait(boolean shadowAwait) {
        this.shadowAwait = shadowAwait;
    }

    public boolean isExportReports() {
        return exportReports;
    }

    public void setExportReports(boolean exportReports) {
        this.exportReports = exportReports;
    }

    public String getReportDir() {
        return reportDir;
    }

    public void setReportDir(String reportDir) {
        this.reportDir = reportDir;
    }

    public boolean isLogConsoleSummary() {
        return logConsoleSummary;
    }

    public void setLogConsoleSummary(boolean logConsoleSummary) {
        this.logConsoleSummary = logConsoleSummary;
    }

    public boolean isFailOnMismatch() {
        return failOnMismatch;
    }

    public void setFailOnMismatch(boolean failOnMismatch) {
        this.failOnMismatch = failOnMismatch;
    }

    public boolean isValidateContracts() {
        return validateContracts;
    }

    public void setValidateContracts(boolean validateContracts) {
        this.validateContracts = validateContracts;
    }

    public boolean isShadowRollbackWrites() {
        return shadowRollbackWrites;
    }

    public void setShadowRollbackWrites(boolean shadowRollbackWrites) {
        this.shadowRollbackWrites = shadowRollbackWrites;
    }

    public double getShadowSampleRate() {
        return shadowSampleRate;
    }

    public void setShadowSampleRate(double shadowSampleRate) {
        this.shadowSampleRate = shadowSampleRate;
    }

    public int getMaxCallsPerWindow() {
        return maxCallsPerWindow;
    }

    public void setMaxCallsPerWindow(int maxCallsPerWindow) {
        this.maxCallsPerWindow = maxCallsPerWindow;
    }

    public long getCallWindowMs() {
        return callWindowMs;
    }

    public void setCallWindowMs(long callWindowMs) {
        this.callWindowMs = callWindowMs;
    }

    public boolean isFailOnChattyBoundary() {
        return failOnChattyBoundary;
    }

    public void setFailOnChattyBoundary(boolean failOnChattyBoundary) {
        this.failOnChattyBoundary = failOnChattyBoundary;
    }

    public int getCircuitFailureThreshold() {
        return circuitFailureThreshold;
    }

    public void setCircuitFailureThreshold(int circuitFailureThreshold) {
        this.circuitFailureThreshold = circuitFailureThreshold;
    }

    public long getCircuitResetTimeoutMs() {
        return circuitResetTimeoutMs;
    }

    public void setCircuitResetTimeoutMs(long circuitResetTimeoutMs) {
        this.circuitResetTimeoutMs = circuitResetTimeoutMs;
    }

    public Map<String, ModuleRouteConfig> getModules() {
        return modules;
    }

    public void setModules(Map<String, ModuleRouteConfig> modules) {
        this.modules = (modules != null) ? new ConcurrentHashMap<>(modules) : new ConcurrentHashMap<>();
    }

    public static class ModuleRouteConfig {

        /**
         * Routing mode for this module (LOCAL, SHADOW, REMOTE).
         */
        private volatile BridgeMode mode = BridgeMode.LOCAL;

        /**
         * HTTP endpoint URL for remote calls.
         */
        private volatile String url;

        /**
         * Remote invocation timeout in milliseconds.
         */
        private volatile long timeoutMs = 5000L;

        /**
         * Whether to fallback to local execution if the remote invocation fails.
         */
        private volatile boolean fallbackToLocal = true;

        /**
         * Optional per-module override for shadow sample rate (0.0 to 1.0).
         */
        private volatile Double shadowSampleRate;

        public BridgeMode getMode() {
            return mode;
        }

        public void setMode(BridgeMode mode) {
            this.mode = mode;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public long getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public boolean isFallbackToLocal() {
            return fallbackToLocal;
        }

        public void setFallbackToLocal(boolean fallbackToLocal) {
            this.fallbackToLocal = fallbackToLocal;
        }

        public Double getShadowSampleRate() {
            return shadowSampleRate;
        }

        public void setShadowSampleRate(Double shadowSampleRate) {
            this.shadowSampleRate = shadowSampleRate;
        }
    }
}

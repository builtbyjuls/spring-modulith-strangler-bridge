package com.builtbyjuls.strangler.config;

import java.util.LinkedHashMap;
import java.util.Map;

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
     * Per-module routing configurations.
     */
    private Map<String, ModuleRouteConfig> modules = new LinkedHashMap<>();

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

    public Map<String, ModuleRouteConfig> getModules() {
        return modules;
    }

    public void setModules(Map<String, ModuleRouteConfig> modules) {
        this.modules = modules;
    }

    public static class ModuleRouteConfig {

        /**
         * Routing mode for this module (LOCAL, SHADOW, REMOTE).
         */
        private BridgeMode mode = BridgeMode.LOCAL;

        /**
         * HTTP endpoint URL for remote calls.
         */
        private String url;

        /**
         * Remote invocation timeout in milliseconds.
         */
        private long timeoutMs = 5000L;

        /**
         * Whether to fallback to local execution if the remote invocation fails.
         */
        private boolean fallbackToLocal = true;

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
    }
}

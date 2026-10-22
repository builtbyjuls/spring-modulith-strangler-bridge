package com.builtbyjuls.strangler.report;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;

import com.builtbyjuls.strangler.config.ModulithBridgeProperties;
import com.builtbyjuls.strangler.parity.ShadowParityRegistry;
import com.builtbyjuls.strangler.parity.ShadowParityResult;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Exports structured JSON reports, GitHub Step Summary Markdown tables, and container
 * deployment manifests documenting shadow parity verifications and routing topologies.
 */
public class StranglerReportExporter implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(StranglerReportExporter.class);

    private final ShadowParityRegistry parityRegistry;
    private final ModulithBridgeProperties properties;
    private final ObjectMapper objectMapper;
    private final DeploymentManifestGenerator manifestGenerator;
    private final AtomicBoolean shutdownExported = new AtomicBoolean(false);

    public StranglerReportExporter(
            ShadowParityRegistry parityRegistry,
            ModulithBridgeProperties properties,
            ObjectMapper objectMapper
    ) {
        this(parityRegistry, properties, objectMapper, new DeploymentManifestGenerator(properties));
    }

    public StranglerReportExporter(
            ShadowParityRegistry parityRegistry,
            ModulithBridgeProperties properties,
            ObjectMapper objectMapper,
            DeploymentManifestGenerator manifestGenerator
    ) {
        this.parityRegistry = parityRegistry != null ? parityRegistry : new ShadowParityRegistry();
        this.properties = properties != null ? properties : new ModulithBridgeProperties();
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.manifestGenerator = manifestGenerator != null ? manifestGenerator : new DeploymentManifestGenerator(this.properties);
    }

    /**
     * Exports parity reports and deployment manifests into the configured report directory.
     *
     * @return the destination directory path
     * @throws IOException if report export fails
     */
    public Path exportReports() throws IOException {
        String dir = properties.getReportDir() != null ? properties.getReportDir() : "target/strangler-reports";
        return exportReports(Path.of(dir));
    }

    /**
     * Exports parity reports and deployment manifests into the specified directory.
     *
     * @param targetDir directory to receive report artifacts
     * @return the destination directory path
     * @throws IOException if report export fails
     */
    public Path exportReports(Path targetDir) throws IOException {
        Files.createDirectories(targetDir);

        StranglerReport report = buildReport();

        Path reportJsonPath = targetDir.resolve("report.json");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(reportJsonPath.toFile(), report);

        Path summaryMdPath = targetDir.resolve("summary.md");
        String summaryMarkdown = buildSummaryMarkdown(report);
        Files.writeString(summaryMdPath, summaryMarkdown, StandardCharsets.UTF_8);

        if (manifestGenerator != null) {
            manifestGenerator.generateManifests(targetDir);
        }

        log.info("Exported strangler reports and manifests to '{}'", targetDir.toAbsolutePath());
        return targetDir;
    }

    @EventListener(ContextClosedEvent.class)
    public void onContextClosed(ContextClosedEvent event) {
        triggerShutdownExport();
    }

    @Override
    public void destroy() {
        triggerShutdownExport();
    }

    private void triggerShutdownExport() {
        if (properties.isExportReports() && parityRegistry.getTotalCount() > 0) {
            if (shutdownExported.compareAndSet(false, true)) {
                try {
                    exportReports();
                } catch (Exception e) {
                    log.error("Failed to automatically export strangler reports on shutdown", e);
                }
            }
        }
    }

    private StranglerReport buildReport() {
        long total = parityRegistry.getTotalCount();
        long matched = parityRegistry.getMatchCount();
        long fallbacks = parityRegistry.getFallbackCount();
        long mismatches = parityRegistry.getResults().stream()
                .filter(r -> !r.matched() && !r.fallbackTriggered())
                .count();

        double parityRate = total == 0 ? 100.0 : BigDecimal.valueOf((double) matched * 100.0 / total)
                .setScale(2, RoundingMode.HALF_UP)
                .doubleValue();

        Map<String, ModulithBridgeProperties.ModuleRouteConfig> moduleMap = properties.getModules() != null
                ? new LinkedHashMap<>(properties.getModules())
                : Collections.emptyMap();

        return new StranglerReport(
                Instant.now().toString(),
                properties.getHostOnly(),
                total,
                matched,
                mismatches,
                fallbacks,
                parityRate,
                moduleMap,
                parityRegistry.getResults()
        );
    }

    private String buildSummaryMarkdown(StranglerReport report) {
        StringBuilder sb = new StringBuilder();

        sb.append("# Modulith Strangler Bridge Parity Report\n\n");

        sb.append("## Overall Metrics\n\n");
        sb.append("| Metric | Value |\n");
        sb.append("|---|---|\n");
        sb.append("| Total Invocations | ").append(report.totalCount()).append(" |\n");
        sb.append("| Matched | ").append(report.matchCount()).append(" |\n");
        sb.append("| Mismatches | ").append(report.mismatchCount()).append(" |\n");
        sb.append("| Fallbacks | ").append(report.fallbackCount()).append(" |\n");
        sb.append("| Parity Rate | ").append(report.parityRatePercent()).append("% |\n\n");

        sb.append("## Module Routing Topology\n\n");
        sb.append("| Module | Mode | Target URL | Timeout (ms) | Fallback to Local |\n");
        sb.append("|---|---|---|---|---|\n");
        if (report.modules().isEmpty()) {
            sb.append("| (none) | LOCAL | - | - | - |\n");
        } else {
            for (Map.Entry<String, ModulithBridgeProperties.ModuleRouteConfig> entry : report.modules().entrySet()) {
                String module = entry.getKey();
                ModulithBridgeProperties.ModuleRouteConfig cfg = entry.getValue();
                String mode = cfg != null && cfg.getMode() != null ? cfg.getMode().name() : "LOCAL";
                String url = cfg != null && cfg.getUrl() != null && !cfg.getUrl().isBlank() ? cfg.getUrl() : "-";
                long timeout = cfg != null ? cfg.getTimeoutMs() : 5000L;
                boolean fallback = cfg == null || cfg.isFallbackToLocal();
                sb.append("| ").append(module)
                        .append(" | ").append(mode)
                        .append(" | ").append(url)
                        .append(" | ").append(timeout)
                        .append(" | ").append(fallback)
                        .append(" |\n");
            }
        }
        sb.append("\n");

        sb.append("## Shadow Parity & Fallback Results\n\n");
        sb.append("| Module | Interface | Method | Status | Local (ms) | Remote (ms) | Fallback | Details |\n");
        sb.append("|---|---|---|---|---|---|---|---|\n");
        if (report.results().isEmpty()) {
            sb.append("| - | - | - | - | - | - | - | - |\n");
        } else {
            for (ShadowParityResult r : report.results()) {
                String status = r.matched() ? "MATCH" : (r.fallbackTriggered() ? "FALLBACK" : "MISMATCH");
                String iface = simpleInterfaceName(r.interfaceName());
                String details = "-";
                if (r.diffSummary() != null && !r.diffSummary().isBlank()) {
                    details = r.diffSummary().replace('|', '/').replace("\n", " ").replace("\r", "");
                } else if (r.remoteError() != null && !r.remoteError().isBlank()) {
                    details = r.remoteError().replace('|', '/').replace("\n", " ").replace("\r", "");
                }

                sb.append("| ").append(r.moduleName())
                        .append(" | ").append(iface)
                        .append(" | ").append(r.methodName())
                        .append(" | ").append(status)
                        .append(" | ").append(r.localDurationMs())
                        .append(" | ").append(r.remoteDurationMs())
                        .append(" | ").append(r.fallbackTriggered())
                        .append(" | ").append(details)
                        .append(" |\n");
            }
        }

        return sb.toString();
    }

    private String simpleInterfaceName(String fqcn) {
        if (fqcn == null) {
            return "-";
        }
        int idx = fqcn.lastIndexOf('.');
        return idx >= 0 ? fqcn.substring(idx + 1) : fqcn;
    }

    public record StranglerReport(
            String generatedAt,
            String hostOnly,
            long totalCount,
            long matchCount,
            long mismatchCount,
            long fallbackCount,
            double parityRatePercent,
            Map<String, ModulithBridgeProperties.ModuleRouteConfig> modules,
            List<ShadowParityResult> results
    ) {
    }
}

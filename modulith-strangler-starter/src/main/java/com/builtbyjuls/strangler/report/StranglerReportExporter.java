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
import com.builtbyjuls.strangler.parity.ShadowParityMismatchException;
import com.builtbyjuls.strangler.parity.ShadowParityRegistry;
import com.builtbyjuls.strangler.parity.ShadowParityResult;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Exports CI-agnostic structured parity reports (JSON, JUnit XML, GitHub Step Summary
 * Markdown, and console ASCII summary) and container deployment manifests documenting
 * shadow parity verifications and routing topologies.
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

        Path junitXmlPath = targetDir.resolve("junit-parity.xml");
        String junitXml = buildJunitParityXml(report);
        Files.writeString(junitXmlPath, junitXml, StandardCharsets.UTF_8);

        if (manifestGenerator != null) {
            manifestGenerator.generateManifests(targetDir);
        }

        if (properties.isLogConsoleSummary()) {
            log.info("\n{}", buildConsoleSummary(report));
        }

        log.info("Exported strangler reports and manifests to '{}'", targetDir.toAbsolutePath());

        if (properties.isFailOnMismatch()) {
            verifyParityGate();
        }

        return targetDir;
    }

    /**
     * Inspects the shadow parity registry and throws an exception if any parity mismatches were detected.
     *
     * @throws ShadowParityMismatchException if one or more non-fallback mismatches occurred
     */
    public void verifyParityGate() {
        List<ShadowParityResult> mismatches = parityRegistry.getResults().stream()
                .filter(r -> !r.matched() && !r.fallbackTriggered())
                .toList();
        if (!mismatches.isEmpty()) {
            throw new ShadowParityMismatchException(mismatches.size(), mismatches);
        }
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

    public StranglerReport buildReport() {
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

    /**
     * Builds an ASCII summary banner of parity metrics and routing topologies.
     *
     * @param report the strangler parity report
     * @return formatted ASCII string
     */
    public String buildConsoleSummary(StranglerReport report) {
        StringBuilder sb = new StringBuilder();
        String line = "================================================================================";
        String div =  "--------------------------------------------------------------------------------";
        sb.append(line).append("\n");
        sb.append("                  SPRING MODULITH STRANGLER PARITY REPORT\n");
        sb.append(line).append("\n");
        String host = report.hostOnly() != null && !report.hostOnly().isBlank() ? report.hostOnly() : "(monolith)";
        sb.append("Active Slice : ").append(host).append("\n");
        sb.append("Parity Rate  : ").append(report.parityRatePercent()).append("%\n");
        sb.append("Total        : ").append(report.totalCount()).append("\n");
        sb.append("Matched      : ").append(report.matchCount()).append("\n");
        sb.append("Mismatches   : ").append(report.mismatchCount()).append("\n");
        sb.append("Fallbacks    : ").append(report.fallbackCount()).append("\n");

        sb.append(div).append("\n");
        sb.append("Module Routing Topology:\n");
        if (report.modules().isEmpty()) {
            sb.append("  (none configured)\n");
        } else {
            for (Map.Entry<String, ModulithBridgeProperties.ModuleRouteConfig> entry : report.modules().entrySet()) {
                String mod = entry.getKey();
                ModulithBridgeProperties.ModuleRouteConfig cfg = entry.getValue();
                String mode = cfg != null && cfg.getMode() != null ? cfg.getMode().name() : "LOCAL";
                String url = cfg != null && cfg.getUrl() != null ? cfg.getUrl() : "-";
                long timeout = cfg != null ? cfg.getTimeoutMs() : 5000L;
                boolean fallback = cfg == null || cfg.isFallbackToLocal();
                sb.append("  - ").append(mod).append(" : mode=").append(mode)
                        .append(", url=").append(url)
                        .append(", timeout=").append(timeout).append("ms")
                        .append(", fallback=").append(fallback).append("\n");
            }
        }

        sb.append(div).append("\n");
        sb.append("Shadow Invocations:\n");
        if (report.results().isEmpty()) {
            sb.append("  (no shadow invocations recorded)\n");
        } else {
            for (ShadowParityResult r : report.results()) {
                String status = r.matched() ? "MATCH" : (r.fallbackTriggered() ? "FALLBACK" : "MISMATCH");
                String iface = simpleInterfaceName(r.interfaceName());
                sb.append(String.format(
                        "  [%-8s] %s | %s.%s (local: %dms, remote: %dms)\n",
                        status,
                        r.moduleName(),
                        iface,
                        r.methodName(),
                        r.localDurationMs(),
                        r.remoteDurationMs()
                ));
                if (r.diffSummary() != null && !r.diffSummary().isBlank()) {
                    sb.append("             Diff: ").append(r.diffSummary().replace("\n", " ")).append("\n");
                }
                if (r.remoteError() != null && !r.remoteError().isBlank()) {
                    sb.append("             Error: ").append(r.remoteError().replace("\n", " ")).append("\n");
                }
            }
        }
        sb.append(line);
        return sb.toString();
    }

    /**
     * Builds standard JUnit XML output representing each shadow parity invocation as a testcase.
     *
     * @param report the strangler parity report
     * @return formatted JUnit XML string
     */
    public String buildJunitParityXml(StranglerReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");

        long totalTests = report.totalCount();
        long failures = report.mismatchCount();
        long totalRemoteDurationMs = 0L;
        for (ShadowParityResult r : report.results()) {
            totalRemoteDurationMs += r.remoteDurationMs();
        }
        String totalTime = formatSeconds(totalRemoteDurationMs);

        sb.append("<testsuite name=\"ModulithStranglerParity\"")
                .append(" tests=\"").append(totalTests).append("\"")
                .append(" failures=\"").append(failures).append("\"")
                .append(" errors=\"0\"")
                .append(" skipped=\"0\"")
                .append(" time=\"").append(totalTime).append("\">\n");

        int index = 1;
        for (ShadowParityResult r : report.results()) {
            String classname = escapeXmlAttribute(r.interfaceName() != null ? r.interfaceName() : r.moduleName());
            String testName = escapeXmlAttribute(r.moduleName() + "." + r.methodName() + "[#" + index + "]");
            String timeSeconds = formatSeconds(r.remoteDurationMs());

            sb.append("  <testcase classname=\"").append(classname).append("\"")
                    .append(" name=\"").append(testName).append("\"")
                    .append(" time=\"").append(timeSeconds).append("\"");

            boolean isMismatch = !r.matched() && !r.fallbackTriggered();
            if (isMismatch) {
                sb.append(">\n");
                String rawMsg = r.diffSummary() != null && !r.diffSummary().isBlank()
                        ? r.diffSummary()
                        : "Shadow parity mismatch";
                String messageAttr = escapeXmlAttribute(rawMsg);
                String bodyText = escapeXmlText(rawMsg);
                sb.append("    <failure message=\"").append(messageAttr)
                        .append("\" type=\"ShadowParityMismatch\">")
                        .append(bodyText)
                        .append("</failure>\n");
                sb.append("  </testcase>\n");
            } else {
                sb.append("/>\n");
            }
            index++;
        }

        sb.append("</testsuite>\n");
        return sb.toString();
    }

    private String escapeXmlAttribute(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private String escapeXmlText(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private String formatSeconds(long milliseconds) {
        return BigDecimal.valueOf(milliseconds)
                .divide(BigDecimal.valueOf(1000), 3, RoundingMode.HALF_UP)
                .toPlainString();
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

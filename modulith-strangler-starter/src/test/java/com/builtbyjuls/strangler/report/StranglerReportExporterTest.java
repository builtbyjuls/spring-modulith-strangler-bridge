package com.builtbyjuls.strangler.report;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.support.GenericApplicationContext;

import com.builtbyjuls.strangler.config.BridgeMode;
import com.builtbyjuls.strangler.config.ModulithBridgeProperties;
import com.builtbyjuls.strangler.parity.ShadowParityRegistry;
import com.builtbyjuls.strangler.parity.ShadowParityResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class StranglerReportExporterTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Exports structured report.json and summary.md matching parity registry data")
    void shouldExportStructuredJsonReportAndSummaryMarkdown() throws IOException {
        ShadowParityRegistry registry = new ShadowParityRegistry();
        registry.record(new ShadowParityResult(
                "metering",
                "com.builtbyjuls.grid.metering.MeteringApi",
                "aggregateDailyMeterVolumes",
                true,
                12L,
                15L,
                null,
                null,
                false
        ));
        registry.record(new ShadowParityResult(
                "metering",
                "com.builtbyjuls.grid.metering.MeteringApi",
                "aggregateDailyMeterVolumes",
                false,
                10L,
                14L,
                "$[0].totalActualMwh: local=100.0 vs remote=105.0",
                null,
                false
        ));
        registry.record(new ShadowParityResult(
                "metering",
                "com.builtbyjuls.grid.metering.MeteringApi",
                "aggregateDailyMeterVolumes",
                false,
                11L,
                5000L,
                "Remote call failed, fell back to local: Timeout",
                "TimeoutException",
                true
        ));

        ModulithBridgeProperties properties = new ModulithBridgeProperties();
        properties.setReportDir(tempDir.toString());
        ModulithBridgeProperties.ModuleRouteConfig route = new ModulithBridgeProperties.ModuleRouteConfig();
        route.setMode(BridgeMode.SHADOW);
        route.setUrl("http://localhost:8080");
        route.setTimeoutMs(5000L);
        route.setFallbackToLocal(true);
        properties.getModules().put("metering", route);

        StranglerReportExporter exporter = new StranglerReportExporter(registry, properties, objectMapper);
        Path exportedDir = exporter.exportReports(tempDir);

        assertThat(exportedDir).isEqualTo(tempDir);

        Path reportJson = tempDir.resolve("report.json");
        Path summaryMd = tempDir.resolve("summary.md");

        assertThat(Files.exists(reportJson)).isTrue();
        assertThat(Files.exists(summaryMd)).isTrue();

        JsonNode jsonNode = objectMapper.readTree(Files.readString(reportJson));
        assertThat(jsonNode.hasNonNull("generatedAt")).isTrue();
        assertThat(jsonNode.get("totalCount").asLong()).isEqualTo(3L);
        assertThat(jsonNode.get("matchCount").asLong()).isEqualTo(1L);
        assertThat(jsonNode.get("mismatchCount").asLong()).isEqualTo(1L);
        assertThat(jsonNode.get("fallbackCount").asLong()).isEqualTo(1L);
        assertThat(jsonNode.get("parityRatePercent").asDouble()).isEqualTo(33.33);
        assertThat(jsonNode.has("modules")).isTrue();
        assertThat(jsonNode.get("modules").has("metering")).isTrue();
        assertThat(jsonNode.get("modules").get("metering").get("mode").asText()).isEqualTo("SHADOW");
        assertThat(jsonNode.get("results").isArray()).isTrue();
        assertThat(jsonNode.get("results").size()).isEqualTo(3);

        String markdown = Files.readString(summaryMd);
        assertThat(markdown).contains("Modulith Strangler");
        assertThat(markdown).contains("Total Invocations");
        assertThat(markdown).contains("Matched");
        assertThat(markdown).contains("Mismatches");
        assertThat(markdown).contains("Fallbacks");
        assertThat(markdown).contains("Parity Rate");
        assertThat(markdown).contains("33.33%");
        assertThat(markdown).contains("Module Routing Topology");
        assertThat(markdown).contains("metering");
        assertThat(markdown).contains("SHADOW");
        assertThat(markdown).contains("http://localhost:8080");
        assertThat(markdown).contains("Shadow Parity & Fallback Results");
        assertThat(markdown).contains("MeteringApi");
        assertThat(markdown).contains("aggregateDailyMeterVolumes");
        assertThat(markdown).contains("MATCH");
        assertThat(markdown).contains("MISMATCH");
        assertThat(markdown).contains("FALLBACK");
    }

    @Test
    @DisplayName("Automatically exports on ContextClosedEvent when enabled and registry has events")
    void shouldExportOnContextClosedEventWhenEnabledAndEventsExist() {
        ShadowParityRegistry registry = new ShadowParityRegistry();
        registry.record(new ShadowParityResult(
                "metering",
                "com.builtbyjuls.grid.metering.MeteringApi",
                "aggregateDailyMeterVolumes",
                true,
                12L,
                15L,
                null,
                null,
                false
        ));

        ModulithBridgeProperties properties = new ModulithBridgeProperties();
        properties.setExportReports(true);
        properties.setReportDir(tempDir.toString());

        StranglerReportExporter exporter = new StranglerReportExporter(registry, properties, objectMapper);
        GenericApplicationContext ctx = new GenericApplicationContext();
        ctx.refresh();
        exporter.onContextClosed(new ContextClosedEvent(ctx));
        ctx.close();

        assertThat(Files.exists(tempDir.resolve("report.json"))).isTrue();
        assertThat(Files.exists(tempDir.resolve("summary.md"))).isTrue();
    }

    @Test
    @DisplayName("Does not export on shutdown when exportReports is disabled or registry is empty")
    void shouldNotExportWhenExportDisabledOrNoEvents() {
        ShadowParityRegistry registry = new ShadowParityRegistry();
        ModulithBridgeProperties properties = new ModulithBridgeProperties();
        properties.setExportReports(false);
        properties.setReportDir(tempDir.toString());

        StranglerReportExporter exporter = new StranglerReportExporter(registry, properties, objectMapper);
        GenericApplicationContext ctx = new GenericApplicationContext();
        ctx.refresh();
        exporter.onContextClosed(new ContextClosedEvent(ctx));
        ctx.close();

        assertThat(Files.exists(tempDir.resolve("report.json"))).isFalse();
        assertThat(Files.exists(tempDir.resolve("summary.md"))).isFalse();
    }
}

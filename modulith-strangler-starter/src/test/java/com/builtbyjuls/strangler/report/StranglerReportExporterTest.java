package com.builtbyjuls.strangler.report;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.support.GenericApplicationContext;
import org.w3c.dom.Document;

import com.builtbyjuls.strangler.config.BridgeMode;
import com.builtbyjuls.strangler.config.ModulithBridgeProperties;
import com.builtbyjuls.strangler.parity.ShadowParityMismatchException;
import com.builtbyjuls.strangler.parity.ShadowParityRegistry;
import com.builtbyjuls.strangler.parity.ShadowParityResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StranglerReportExporterTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Exports structured report.json and summary.md matching parity registry data")
    void shouldExportStructuredJsonReportAndSummaryMarkdown() throws Exception {
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
        Path junitXml = tempDir.resolve("junit-parity.xml");

        assertThat(Files.exists(reportJson)).isTrue();
        assertThat(Files.exists(summaryMd)).isTrue();
        assertThat(Files.exists(junitXml)).isTrue();

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

        String xmlContent = Files.readString(junitXml, StandardCharsets.UTF_8);
        assertThat(xmlContent).contains("<testsuite");
        assertThat(xmlContent).contains("name=\"ModulithStranglerParity\"");
        assertThat(xmlContent).contains("tests=\"3\"");
        assertThat(xmlContent).contains("failures=\"1\"");
        assertThat(xmlContent).contains("<testcase");
        assertThat(xmlContent).contains("classname=\"com.builtbyjuls.grid.metering.MeteringApi\"");
        assertThat(xmlContent).contains("name=\"metering.aggregateDailyMeterVolumes[#1]\"");
        assertThat(xmlContent).contains("name=\"metering.aggregateDailyMeterVolumes[#2]\"");
        assertThat(xmlContent).contains("name=\"metering.aggregateDailyMeterVolumes[#3]\"");
        assertThat(xmlContent).contains("<failure");
        assertThat(xmlContent).contains("type=\"ShadowParityMismatch\"");
        assertThat(xmlContent).contains("local=100.0 vs remote=105.0");

        Document doc = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(new ByteArrayInputStream(xmlContent.getBytes(StandardCharsets.UTF_8)));
        assertThat(doc.getDocumentElement().getNodeName()).isEqualTo("testsuite");
        assertThat(doc.getElementsByTagName("testcase").getLength()).isEqualTo(3);
        assertThat(doc.getElementsByTagName("failure").getLength()).isEqualTo(1);
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

    @Test
    @DisplayName("Escapes special XML characters in junit-parity.xml failure message and body")
    void shouldEscapeXmlSpecialCharactersInJunitReport() throws Exception {
        ShadowParityRegistry registry = new ShadowParityRegistry();
        registry.record(new ShadowParityResult(
                "metering",
                "com.builtbyjuls.grid.metering.MeteringApi",
                "aggregateDailyMeterVolumes",
                false,
                10L,
                14L,
                "$[0].tag: local=\"alpha & beta\" <mwh> vs remote='gamma & delta' > 100",
                null,
                false
        ));

        ModulithBridgeProperties properties = new ModulithBridgeProperties();
        properties.setReportDir(tempDir.toString());

        StranglerReportExporter exporter = new StranglerReportExporter(registry, properties, objectMapper);
        exporter.exportReports(tempDir);

        Path junitXml = tempDir.resolve("junit-parity.xml");
        assertThat(Files.exists(junitXml)).isTrue();

        String xmlContent = Files.readString(junitXml, StandardCharsets.UTF_8);
        assertThat(xmlContent).contains("&amp;");
        assertThat(xmlContent).contains("&lt;mwh&gt;");
        assertThat(xmlContent).contains("&quot;alpha");

        Document doc = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(new ByteArrayInputStream(xmlContent.getBytes(StandardCharsets.UTF_8)));
        assertThat(doc.getDocumentElement().getNodeName()).isEqualTo("testsuite");
        assertThat(doc.getElementsByTagName("failure").getLength()).isEqualTo(1);
    }

    @Test
    @DisplayName("Builds clean ASCII console summary with metrics and module topologies")
    void shouldBuildConsoleSummaryWithMetricsAndTopologies() {
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
                "local=100.0 vs remote=105.0",
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
                "Timeout",
                "TimeoutException",
                true
        ));

        ModulithBridgeProperties properties = new ModulithBridgeProperties();
        ModulithBridgeProperties.ModuleRouteConfig route = new ModulithBridgeProperties.ModuleRouteConfig();
        route.setMode(BridgeMode.SHADOW);
        route.setUrl("http://localhost:8080");
        properties.getModules().put("metering", route);

        StranglerReportExporter exporter = new StranglerReportExporter(registry, properties, objectMapper);
        StranglerReportExporter.StranglerReport report = exporter.buildReport();

        String summary = exporter.buildConsoleSummary(report);
        assertThat(summary).contains("Total");
        assertThat(summary).contains("Matched");
        assertThat(summary).contains("Mismatches");
        assertThat(summary).contains("Fallbacks");
        assertThat(summary).contains("Parity Rate");
        assertThat(summary).contains("metering");
        assertThat(summary).contains("SHADOW");
        assertThat(summary).contains("MATCH");
        assertThat(summary).contains("MISMATCH");
        assertThat(summary).contains("FALLBACK");
    }

    @Test
    @DisplayName("verifyParityGate throws ShadowParityMismatchException when mismatches exist")
    void shouldThrowExceptionWhenVerifyingParityGateWithMismatches() {
        ShadowParityRegistry registry = new ShadowParityRegistry();
        registry.record(new ShadowParityResult(
                "metering",
                "com.builtbyjuls.grid.metering.MeteringApi",
                "aggregateDailyMeterVolumes",
                false,
                10L,
                14L,
                "local=100 vs remote=105",
                null,
                false
        ));

        ModulithBridgeProperties properties = new ModulithBridgeProperties();
        StranglerReportExporter exporter = new StranglerReportExporter(registry, properties, objectMapper);

        assertThatThrownBy(exporter::verifyParityGate)
                .isInstanceOf(ShadowParityMismatchException.class)
                .hasMessageContaining("Shadow parity gate failed with 1 mismatch(es)")
                .satisfies(ex -> {
                    ShadowParityMismatchException mismatchEx = (ShadowParityMismatchException) ex;
                    assertThat(mismatchEx.getMismatchCount()).isEqualTo(1L);
                    assertThat(mismatchEx.getMismatches()).hasSize(1);
                });
    }

    @Test
    @DisplayName("verifyParityGate succeeds cleanly when only matches or fallbacks exist")
    void shouldPassParityGateWhenNoMismatchesExist() {
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
                11L,
                5000L,
                "Timeout",
                "TimeoutException",
                true
        ));

        ModulithBridgeProperties properties = new ModulithBridgeProperties();
        StranglerReportExporter exporter = new StranglerReportExporter(registry, properties, objectMapper);

        // Does not throw
        exporter.verifyParityGate();
    }

    @Test
    @DisplayName("exportReports throws ShadowParityMismatchException when failOnMismatch is true and mismatches exist")
    void shouldThrowOnExportReportsWhenFailOnMismatchIsTrueAndMismatchesExist() {
        ShadowParityRegistry registry = new ShadowParityRegistry();
        registry.record(new ShadowParityResult(
                "metering",
                "com.builtbyjuls.grid.metering.MeteringApi",
                "aggregateDailyMeterVolumes",
                false,
                10L,
                14L,
                "local=100 vs remote=105",
                null,
                false
        ));

        ModulithBridgeProperties properties = new ModulithBridgeProperties();
        properties.setFailOnMismatch(true);
        properties.setReportDir(tempDir.toString());

        StranglerReportExporter exporter = new StranglerReportExporter(registry, properties, objectMapper);

        assertThatThrownBy(() -> exporter.exportReports(tempDir))
                .isInstanceOf(ShadowParityMismatchException.class);

        // Files must still have been written prior to the exception
        assertThat(Files.exists(tempDir.resolve("report.json"))).isTrue();
        assertThat(Files.exists(tempDir.resolve("summary.md"))).isTrue();
        assertThat(Files.exists(tempDir.resolve("junit-parity.xml"))).isTrue();
    }
}

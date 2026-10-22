package com.builtbyjuls.grid;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.builtbyjuls.grid.fixture.GridDataSeeder;
import com.builtbyjuls.grid.metering.DailyMeterVolumeDto;
import com.builtbyjuls.grid.metering.MeteringApi;
import com.builtbyjuls.grid.settlement.DailySettlementPositionDto;
import com.builtbyjuls.grid.settlement.GridDailySettlementService;
import com.builtbyjuls.grid.settlement.SettlementOrchestrator;
import com.builtbyjuls.strangler.config.BridgeMode;
import com.builtbyjuls.strangler.config.ModulithBridgeProperties;
import com.builtbyjuls.strangler.parity.ShadowParityRegistry;
import com.builtbyjuls.strangler.parity.ShadowParityResult;
import com.builtbyjuls.strangler.parity.ShadowParityVerifier;
import com.builtbyjuls.strangler.proxy.ModulithBridgeInvocationHandler;
import com.builtbyjuls.strangler.report.DeploymentManifestGenerator;
import com.builtbyjuls.strangler.report.StranglerReportExporter;
import com.builtbyjuls.strangler.transport.BridgeHttpClient;
import com.builtbyjuls.strangler.transport.BridgeInvocationResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShadowParityAndFallbackIntegrationTest extends PostgreSqlTestContainerBase {

    private static ConfigurableApplicationContext workerContext;
    private static int workerPort;

    private static final LocalDate TRADING_DATE = LocalDate.of(2026, 3, 20);

    @Autowired
    private SettlementOrchestrator settlementOrchestrator;

    @Autowired
    private GridDailySettlementService settlementService;

    @Autowired
    private GridDataSeeder dataSeeder;

    @Autowired
    private ShadowParityRegistry parityRegistry;

    @Autowired
    private ModulithBridgeProperties bridgeProperties;

    @Autowired
    private MeteringApi meteringApi;

    @Autowired
    private StranglerReportExporter reportExporter;

    @Autowired
    private DeploymentManifestGenerator manifestGenerator;

    @DynamicPropertySource
    static void configureRemoteBridge(DynamicPropertyRegistry registry) {
        startWorkerNode();
        registry.add("modulith.bridge.modules.metering.mode", () -> "SHADOW");
        registry.add("modulith.bridge.modules.metering.url", () -> "http://localhost:" + workerPort);
        registry.add("modulith.bridge.shadow-await", () -> "true");
    }

    private static synchronized void startWorkerNode() {
        if (workerContext == null) {
            workerContext = new SpringApplicationBuilder(GridSettlementModulithApplication.class)
                    .web(WebApplicationType.SERVLET)
                    .properties(
                            "server.port=0",
                            "modulith.bridge.host-only=metering",
                            "spring.datasource.url=" + postgres.getJdbcUrl(),
                            "spring.datasource.username=" + postgres.getUsername(),
                            "spring.datasource.password=" + postgres.getPassword(),
                            "spring.threads.virtual.enabled=true"
                    )
                    .run();
            workerPort = ((WebServerApplicationContext) workerContext).getWebServer().getPort();
        }
    }

    @BeforeAll
    void seedTradingData() {
        dataSeeder.seedDeclarations(TRADING_DATE, GridDataSeeder.DEFAULT_CONTRACT_COUNT);
        dataSeeder.seedMeterReadings(TRADING_DATE, GridDataSeeder.DEFAULT_CONTRACT_COUNT);
    }

    @AfterAll
    static void stopWorkerNode() {
        if (workerContext != null) {
            workerContext.close();
            workerContext = null;
        }
    }

    @Test
    @DisplayName("Scenario C: Settle trading day in SHADOW mode with 100% parity across 5,760 intervals")
    void shouldSettleTradingDayInShadowModeWith100PercentParity() {
        parityRegistry.clear();

        List<DailySettlementPositionDto> positions = settlementOrchestrator.settleTradingDay(TRADING_DATE);

        assertThat(positions).hasSize(GridDataSeeder.DEFAULT_CONTRACT_COUNT);

        BigDecimal totalDeclared = BigDecimal.ZERO;
        BigDecimal totalActual = BigDecimal.ZERO;

        for (DailySettlementPositionDto position : positions) {
            assertThat(position.tradingDate()).isEqualTo(TRADING_DATE);
            assertThat(position.contractCode()).startsWith("BC_2026_");
            assertThat(position.sellerCode()).startsWith("GEN_");
            assertThat(position.buyerCode()).startsWith("UTIL_");
            assertThat(position.declaredMwh()).isGreaterThan(BigDecimal.ZERO);
            assertThat(position.actualMwh()).isGreaterThan(BigDecimal.ZERO);

            BigDecimal expectedImbalance = position.actualMwh().subtract(position.declaredMwh())
                    .setScale(6, RoundingMode.HALF_UP);
            assertThat(position.imbalanceMwh()).isEqualByComparingTo(expectedImbalance);
            assertThat(position.settlementAmount()).isNotNull();

            totalDeclared = totalDeclared.add(position.declaredMwh());
            totalActual = totalActual.add(position.actualMwh());
        }

        assertThat(totalDeclared).isGreaterThan(BigDecimal.ZERO);
        assertThat(totalActual).isGreaterThan(BigDecimal.ZERO);

        // Verify shadow parity results
        assertThat(parityRegistry.getTotalCount()).isGreaterThanOrEqualTo(1);
        assertThat(parityRegistry.getMatchCount()).isGreaterThanOrEqualTo(1);
        assertThat(parityRegistry.getMismatches()).isEmpty();
        assertThat(parityRegistry.getFallbackCount()).isEqualTo(0);

        ShadowParityResult result = parityRegistry.getResults().get(0);
        assertThat(result.moduleName()).isEqualTo("metering");
        assertThat(result.interfaceName()).isEqualTo(MeteringApi.class.getName());
        assertThat(result.methodName()).isEqualTo("aggregateDailyMeterVolumes");
        assertThat(result.matched()).isTrue();
        assertThat(result.diffSummary()).isNull();
        assertThat(result.remoteError()).isNull();
        assertThat(result.fallbackTriggered()).isFalse();
    }

    @Test
    @DisplayName("Detects parity mismatch when remote response drifts without altering authoritative local result")
    void shouldDetectParityMismatchWhenRemoteResponseDrifts() throws Exception {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

        // Construct drifted remote payload where element 0 totalActualMwh differs
        List<DailyMeterVolumeDto> baseVolumes = meteringApi.aggregateDailyMeterVolumes(TRADING_DATE);
        List<DailyMeterVolumeDto> driftedVolumes = new ArrayList<>(baseVolumes);
        DailyMeterVolumeDto originalFirst = driftedVolumes.get(0);
        driftedVolumes.set(0, new DailyMeterVolumeDto(
                originalFirst.meterCode(),
                originalFirst.readingDate(),
                originalFirst.intervalCount(),
                new BigDecimal("999.000000")
        ));

        String driftedResponseJson = mapper.writeValueAsString(new BridgeInvocationResponse(
                mapper.valueToTree(driftedVolumes),
                10L,
                "metering",
                null
        ));

        mockServer.expect(requestTo("http://drifted-worker:8080/internal/modulith-bridge/invoke"))
                .andRespond(withSuccess(driftedResponseJson, MediaType.APPLICATION_JSON));

        ModulithBridgeProperties driftProps = new ModulithBridgeProperties();
        driftProps.setShadowAwait(true);
        ModulithBridgeProperties.ModuleRouteConfig route = new ModulithBridgeProperties.ModuleRouteConfig();
        route.setMode(BridgeMode.SHADOW);
        route.setUrl("http://drifted-worker:8080");
        driftProps.getModules().put("metering", route);

        BridgeHttpClient driftClient = new BridgeHttpClient(restClientBuilder, mapper);
        ShadowParityRegistry driftRegistry = new ShadowParityRegistry();
        ShadowParityVerifier verifier = new ShadowParityVerifier(mapper);

        ModulithBridgeInvocationHandler handler = new ModulithBridgeInvocationHandler(
                meteringApi,
                "metering",
                MeteringApi.class,
                driftProps,
                driftClient,
                mapper,
                verifier,
                driftRegistry
        );

        MeteringApi driftProxy = (MeteringApi) Proxy.newProxyInstance(
                MeteringApi.class.getClassLoader(),
                new Class<?>[]{MeteringApi.class},
                handler
        );

        List<DailyMeterVolumeDto> volumes = driftProxy.aggregateDailyMeterVolumes(TRADING_DATE);

        // Authoritative local response returned intact
        assertThat(volumes).hasSize(GridDataSeeder.DEFAULT_CONTRACT_COUNT);
        mockServer.verify();

        // Mismatch correctly recorded
        assertThat(driftRegistry.getTotalCount()).isEqualTo(1);
        assertThat(driftRegistry.getMatchCount()).isEqualTo(0);
        assertThat(driftRegistry.getMismatches()).hasSize(1);

        ShadowParityResult mismatch = driftRegistry.getMismatches().get(0);
        assertThat(mismatch.matched()).isFalse();
        assertThat(mismatch.diffSummary()).contains("$[0].totalActualMwh: local=");
        assertThat(mismatch.diffSummary()).contains("vs remote=999");
        assertThat(mismatch.fallbackTriggered()).isFalse();
    }

    @Test
    @DisplayName("Resilient fallback to LOCAL when remote bridge is unreachable and fallback-to-local is true")
    void shouldFallbackToLocalWhenRemoteBridgeUnreachable() {
        parityRegistry.clear();

        ModulithBridgeProperties.ModuleRouteConfig config = bridgeProperties.getModules().get("metering");
        BridgeMode originalMode = config.getMode();
        String originalUrl = config.getUrl();
        boolean originalFallback = config.isFallbackToLocal();

        try {
            config.setMode(BridgeMode.REMOTE);
            config.setUrl("http://localhost:59999"); // unreachable port
            config.setFallbackToLocal(true);

            List<DailySettlementPositionDto> positions = settlementOrchestrator.settleTradingDay(TRADING_DATE);

            assertThat(positions).hasSize(GridDataSeeder.DEFAULT_CONTRACT_COUNT);
            assertThat(parityRegistry.getFallbackCount()).isGreaterThan(0);
        } finally {
            config.setMode(originalMode);
            config.setUrl(originalUrl);
            config.setFallbackToLocal(originalFallback);
        }
    }

    @Test
    @DisplayName("Exports structured strangler reports and container manifests to target/strangler-reports")
    void shouldExportStructuredReportsAndDeploymentManifests() throws IOException {
        settlementOrchestrator.settleTradingDay(TRADING_DATE);
        assertThat(parityRegistry.getTotalCount()).isGreaterThan(0);

        Path exportedDir = reportExporter.exportReports();
        assertThat(exportedDir).isNotNull();

        Path reportJson = exportedDir.resolve("report.json");
        Path summaryMd = exportedDir.resolve("summary.md");
        Path composeFile = exportedDir.resolve("docker-compose.strangler.yml");
        Path k8sFile = exportedDir.resolve("k8s-strangler.yaml");

        assertThat(Files.exists(reportJson)).isTrue();
        assertThat(Files.size(reportJson)).isGreaterThan(0);

        assertThat(Files.exists(summaryMd)).isTrue();
        assertThat(Files.size(summaryMd)).isGreaterThan(0);

        assertThat(Files.exists(composeFile)).isTrue();
        assertThat(Files.size(composeFile)).isGreaterThan(0);

        assertThat(Files.exists(k8sFile)).isTrue();
        assertThat(Files.size(k8sFile)).isGreaterThan(0);
    }
}

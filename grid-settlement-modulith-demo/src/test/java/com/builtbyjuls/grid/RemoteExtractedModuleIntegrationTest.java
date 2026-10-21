package com.builtbyjuls.grid;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.builtbyjuls.grid.fixture.GridDataSeeder;
import com.builtbyjuls.grid.metering.MeteringApi;
import com.builtbyjuls.grid.settlement.DailySettlementPositionDto;
import com.builtbyjuls.grid.settlement.GridDailySettlementService;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class RemoteExtractedModuleIntegrationTest extends PostgreSqlTestContainerBase {

    private static ConfigurableApplicationContext workerContext;
    private static int workerPort;

    @Autowired
    private GridDailySettlementService settlementService;

    @Autowired
    private GridDataSeeder dataSeeder;

    @DynamicPropertySource
    static void configureRemoteBridge(DynamicPropertyRegistry registry) {
        startWorkerNode();
        registry.add("modulith.bridge.modules.metering.mode", () -> "REMOTE");
        registry.add("modulith.bridge.modules.metering.url", () -> "http://localhost:" + workerPort);
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

    @AfterAll
    static void stopWorkerNode() {
        if (workerContext != null) {
            workerContext.close();
            workerContext = null;
        }
    }

    @Test
    @DisplayName("Worker node isolates metering module: contract and settlement beans pruned while MeteringApi and inbound controller active")
    void shouldVerifyWorkerNodeBeanIsolation() {
        // Assert worker node hosts metering and exposes inbound bridge controller
        assertThat(workerContext.getBean(MeteringApi.class)).isNotNull();
        assertThat(workerContext.containsBean("meteringAggregationService")).isTrue();
        assertThat(workerContext.containsBean("modulithBridgeInboundController")).isTrue();
        assertThat(workerContext.containsBean("meterReadingHeaderRepository")).isTrue();
        assertThat(workerContext.containsBean("meterIntervalReadingRepository")).isTrue();

        // Assert worker node has pruned contract and settlement beans
        assertThat(workerContext.containsBean("gridDailySettlementService")).isFalse();
        assertThat(workerContext.containsBean("contractModuleService")).isFalse();
        assertThat(workerContext.containsBean("contractDeclarationRepository")).isFalse();
        assertThat(workerContext.containsBean("bilateralContractRepository")).isFalse();
        assertThat(workerContext.containsBean("marketParticipantRepository")).isFalse();
        assertThat(workerContext.containsBean("intervalQuantityRepository")).isFalse();
    }

    @Test
    @DisplayName("Scenario B: Settle 20 bilateral contracts over HTTP bridge in REMOTE mode against extracted worker node")
    void shouldSettleTradingDayInRemoteWorkerMode() {
        LocalDate tradingDate = LocalDate.of(2026, 10, 23);
        dataSeeder.seedDeclarations(tradingDate, GridDataSeeder.DEFAULT_CONTRACT_COUNT);
        dataSeeder.seedMeterReadings(tradingDate, GridDataSeeder.DEFAULT_CONTRACT_COUNT);

        List<DailySettlementPositionDto> positions = settlementService.settleTradingDay(tradingDate);

        assertThat(positions).hasSize(GridDataSeeder.DEFAULT_CONTRACT_COUNT);

        BigDecimal totalDeclared = BigDecimal.ZERO;
        BigDecimal totalActual = BigDecimal.ZERO;

        for (DailySettlementPositionDto position : positions) {
            assertThat(position.tradingDate()).isEqualTo(tradingDate);
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
    }
}

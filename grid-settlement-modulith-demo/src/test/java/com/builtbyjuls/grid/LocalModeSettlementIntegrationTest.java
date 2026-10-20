package com.builtbyjuls.grid;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import com.builtbyjuls.grid.fixture.GridDataSeeder;
import com.builtbyjuls.grid.settlement.DailySettlementPositionDto;
import com.builtbyjuls.grid.settlement.GridDailySettlementService;

class LocalModeSettlementIntegrationTest {

    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    class MonolithScenarioATest extends PostgreSqlTestContainerBase {

        @Autowired
        private GridDailySettlementService settlementService;

        @Autowired
        private GridDataSeeder dataSeeder;

        @Autowired
        private ApplicationContext context;

        @Test
        @DisplayName("Scenario A: Settle 20 bilateral contracts across 5,760 intervals in LOCAL mode")
        void shouldSettleTradingDayInLocalMonolithMode() {
            LocalDate tradingDate = LocalDate.of(2026, 10, 22);
            dataSeeder.seedDeclarations(tradingDate, GridDataSeeder.DEFAULT_CONTRACT_COUNT);
            dataSeeder.seedMeterReadings(tradingDate, GridDataSeeder.DEFAULT_CONTRACT_COUNT);

            List<DailySettlementPositionDto> positions = settlementService.settleTradingDay(tradingDate);

            assertThat(positions).hasSize(GridDataSeeder.DEFAULT_CONTRACT_COUNT);

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
            }

            // In LOCAL monolith mode, all module services and repositories are present
            assertThat(context.containsBean("contractModuleService")).isTrue();
            assertThat(context.containsBean("meteringAggregationService")).isTrue();
            assertThat(context.containsBean("gridDailySettlementService")).isTrue();
            assertThat(context.containsBean("contractDeclarationRepository")).isTrue();
            assertThat(context.containsBean("meterReadingHeaderRepository")).isTrue();
        }
    }

    @Nested
    @SpringBootTest(properties = "modulith.bridge.host-only=metering")
    @ActiveProfiles("test")
    class HostOnlyMeteringSliceTest extends PostgreSqlTestContainerBase {

        @Autowired
        private ApplicationContext context;

        @Test
        @DisplayName("Runtime slice host-only=metering prunes contract and settlement services and repositories")
        void shouldPruneNonHostedModulesOnWorkerNode() {
            // Hosted metering module beans must be present
            assertThat(context.containsBean("meteringAggregationService")).isTrue();
            assertThat(context.containsBean("meterReadingHeaderRepository")).isTrue();
            assertThat(context.containsBean("meterIntervalReadingRepository")).isTrue();

            // Non-hosted contract and settlement beans must be pruned before singleton creation
            assertThat(context.containsBean("gridDailySettlementService")).isFalse();
            assertThat(context.containsBean("contractModuleService")).isFalse();
            assertThat(context.containsBean("contractDeclarationRepository")).isFalse();
            assertThat(context.containsBean("bilateralContractRepository")).isFalse();
            assertThat(context.containsBean("marketParticipantRepository")).isFalse();
            assertThat(context.containsBean("intervalQuantityRepository")).isFalse();
        }
    }
}

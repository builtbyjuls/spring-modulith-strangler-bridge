package com.builtbyjuls.grid.settlement;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Component;

/**
 * High-level settlement orchestrator coordinating wholesale energy imbalance settlement.
 */
@Component
public class SettlementOrchestrator {

    private final GridDailySettlementService settlementService;

    public SettlementOrchestrator(GridDailySettlementService settlementService) {
        this.settlementService = Objects.requireNonNull(settlementService, "settlementService must not be null");
    }

    /**
     * Settles energy imbalance across all confirmed bilateral contracts for a trading day
     * using the default imbalance tariff.
     *
     * @param tradingDate trading day to settle
     * @return daily settlement positions per contract
     */
    public List<DailySettlementPositionDto> settleTradingDay(LocalDate tradingDate) {
        return settlementService.settleTradingDay(tradingDate);
    }

    /**
     * Settles energy imbalance across all confirmed bilateral contracts for a trading day
     * using a specified imbalance tariff.
     *
     * @param tradingDate     trading day to settle
     * @param imbalanceTariff price per MWh of imbalance
     * @return daily settlement positions per contract
     */
    public List<DailySettlementPositionDto> settleTradingDay(LocalDate tradingDate, BigDecimal imbalanceTariff) {
        return settlementService.settleTradingDay(tradingDate, imbalanceTariff);
    }
}

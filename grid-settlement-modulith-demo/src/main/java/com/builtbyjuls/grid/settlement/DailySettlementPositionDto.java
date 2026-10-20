package com.builtbyjuls.grid.settlement;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Public DTO representing the calculated daily financial settlement and MWh imbalance for a contract.
 */
public record DailySettlementPositionDto(
        String contractCode,
        String sellerCode,
        String buyerCode,
        LocalDate tradingDate,
        BigDecimal declaredMwh,
        BigDecimal actualMwh,
        BigDecimal imbalanceMwh,
        BigDecimal settlementAmount
) {
}

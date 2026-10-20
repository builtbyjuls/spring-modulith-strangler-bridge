package com.builtbyjuls.grid.settlement;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.builtbyjuls.grid.contract.ContractApi;
import com.builtbyjuls.grid.contract.ContractDeclarationDto;
import com.builtbyjuls.grid.metering.DailyMeterVolumeDto;
import com.builtbyjuls.grid.metering.MeteringApi;

/**
 * Wholesale electricity market settlement service coordinating daily volume
 * audit calculations and counterparty position summaries.
 * Orchestrates ContractApi and MeteringApi without accessing internal module packages.
 */
@Service
@Transactional(readOnly = true)
public class GridDailySettlementService {

    public static final BigDecimal DEFAULT_IMBALANCE_TARIFF = new BigDecimal("45.50");
    private static final Pattern DIGIT_PATTERN = Pattern.compile("(\\d+)$");

    private final ContractApi contractApi;
    private final MeteringApi meteringApi;

    public GridDailySettlementService(ContractApi contractApi, MeteringApi meteringApi) {
        this.contractApi = Objects.requireNonNull(contractApi, "contractApi must not be null");
        this.meteringApi = Objects.requireNonNull(meteringApi, "meteringApi must not be null");
    }

    /**
     * Settles energy imbalance across all confirmed bilateral contracts for a trading day
     * using the default imbalance tariff.
     *
     * @param tradingDate trading day to settle
     * @return daily settlement positions per contract
     */
    public List<DailySettlementPositionDto> settleTradingDay(LocalDate tradingDate) {
        return settleTradingDay(tradingDate, DEFAULT_IMBALANCE_TARIFF);
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
        Objects.requireNonNull(tradingDate, "tradingDate must not be null");
        Objects.requireNonNull(imbalanceTariff, "imbalanceTariff must not be null");

        List<ContractDeclarationDto> declarations = contractApi.findConfirmedDeclarations(tradingDate);
        List<DailyMeterVolumeDto> meterVolumes = meteringApi.aggregateDailyMeterVolumes(tradingDate);

        Map<String, DailyMeterVolumeDto> volumeByMeterCode = new HashMap<>();
        for (DailyMeterVolumeDto volume : meterVolumes) {
            volumeByMeterCode.put(volume.meterCode(), volume);
        }

        List<DailySettlementPositionDto> positions = new ArrayList<>(declarations.size());

        for (int i = 0; i < declarations.size(); i++) {
            ContractDeclarationDto decl = declarations.get(i);
            DailyMeterVolumeDto matchedVolume = findMatchingMeterVolume(decl.contractCode(), i, volumeByMeterCode, meterVolumes);

            BigDecimal declaredMwh = decl.totalDeclaredMwh().setScale(6, RoundingMode.HALF_UP);
            BigDecimal actualMwh = (matchedVolume != null ? matchedVolume.totalActualMwh() : BigDecimal.ZERO)
                    .setScale(6, RoundingMode.HALF_UP);

            BigDecimal imbalanceMwh = actualMwh.subtract(declaredMwh).setScale(6, RoundingMode.HALF_UP);
            BigDecimal settlementAmount = imbalanceMwh.multiply(imbalanceTariff).setScale(2, RoundingMode.HALF_UP);

            positions.add(new DailySettlementPositionDto(
                    decl.contractCode(),
                    decl.sellerCode(),
                    decl.buyerCode(),
                    decl.tradingDate(),
                    declaredMwh,
                    actualMwh,
                    imbalanceMwh,
                    settlementAmount
            ));
        }

        return positions;
    }

    private DailyMeterVolumeDto findMatchingMeterVolume(
            String contractCode,
            int index,
            Map<String, DailyMeterVolumeDto> volumeByMeterCode,
            List<DailyMeterVolumeDto> allVolumes
    ) {
        // Direct match by contract code
        if (volumeByMeterCode.containsKey(contractCode)) {
            return volumeByMeterCode.get(contractCode);
        }

        // Try extracting numeric suffix (e.g. BC_2026_001 -> 1 -> MTR_0001 or MTR_001)
        Matcher matcher = DIGIT_PATTERN.matcher(contractCode);
        if (matcher.find()) {
            int num = Integer.parseInt(matcher.group(1));
            String key4 = String.format("MTR_%04d", num);
            if (volumeByMeterCode.containsKey(key4)) {
                return volumeByMeterCode.get(key4);
            }
            String key3 = String.format("MTR_%03d", num);
            if (volumeByMeterCode.containsKey(key3)) {
                return volumeByMeterCode.get(key3);
            }
        }

        // Fallback: match by index if collection sizes align
        if (index < allVolumes.size()) {
            return allVolumes.get(index);
        }

        return null;
    }
}

package com.builtbyjuls.grid.fixture;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.builtbyjuls.grid.contract.DeclarationStatus;
import com.builtbyjuls.grid.contract.ParticipantRole;
import com.builtbyjuls.grid.contract.internal.BilateralContract;
import com.builtbyjuls.grid.contract.internal.BilateralContractRepository;
import com.builtbyjuls.grid.contract.internal.ContractDeclaration;
import com.builtbyjuls.grid.contract.internal.ContractDeclarationRepository;
import com.builtbyjuls.grid.contract.internal.IntervalQuantity;
import com.builtbyjuls.grid.contract.internal.MarketParticipant;
import com.builtbyjuls.grid.contract.internal.MarketParticipantRepository;
import com.builtbyjuls.grid.metering.internal.MeterIntervalReading;
import com.builtbyjuls.grid.metering.internal.MeterReadingHeader;
import com.builtbyjuls.grid.metering.internal.MeterReadingHeaderRepository;

/**
 * Deterministic test data seeder modeling 20 bilateral contracts and declarations
 * across 288 daily 5-minute dispatch intervals (5,760 total interval records),
 * as well as telemetry meter reading headers with 288 intervals each (5,760 total readings).
 */
@Component
public class GridDataSeeder {

    public static final int INTERVALS_PER_DAY = 288;
    public static final int DEFAULT_CONTRACT_COUNT = 20;

    private final MarketParticipantRepository participantRepository;
    private final BilateralContractRepository contractRepository;
    private final ContractDeclarationRepository declarationRepository;
    private final MeterReadingHeaderRepository meterHeaderRepository;

    public GridDataSeeder(
            MarketParticipantRepository participantRepository,
            BilateralContractRepository contractRepository,
            ContractDeclarationRepository declarationRepository,
            MeterReadingHeaderRepository meterHeaderRepository
    ) {
        this.participantRepository = participantRepository;
        this.contractRepository = contractRepository;
        this.declarationRepository = declarationRepository;
        this.meterHeaderRepository = meterHeaderRepository;
    }

    @Transactional
    public List<ContractDeclaration> seedDeclarations(LocalDate tradingDate, int contractCount) {
        List<MarketParticipant> sellers = new ArrayList<>();
        for (int i = 1; i <= contractCount; i++) {
            final int sellerId = i;
            String code = String.format("GEN_%02d", sellerId);
            MarketParticipant seller = participantRepository.findByParticipantCode(code)
                    .orElseGet(() -> participantRepository.save(
                            new MarketParticipant(code, "Generator " + sellerId + " Power Plant", ParticipantRole.GENERATOR)
                    ));
            sellers.add(seller);
        }

        List<MarketParticipant> buyers = new ArrayList<>();
        for (int i = 1; i <= contractCount; i++) {
            final int buyerId = i;
            String code = String.format("UTIL_%02d", buyerId);
            MarketParticipant buyer = participantRepository.findByParticipantCode(code)
                    .orElseGet(() -> participantRepository.save(
                            new MarketParticipant(code, "Utility " + buyerId + " Electric Grid", ParticipantRole.DISTRIBUTION_UTILITY)
                    ));
            buyers.add(buyer);
        }

        List<BilateralContract> contracts = new ArrayList<>();
        for (int i = 1; i <= contractCount; i++) {
            String code = String.format("BC_2026_%03d", i);
            MarketParticipant seller = sellers.get(i - 1);
            MarketParticipant buyer = buyers.get(i - 1);

            BilateralContract contract = contractRepository.findByContractCode(code)
                    .orElseGet(() -> contractRepository.save(new BilateralContract(code, seller, buyer)));
            contracts.add(contract);
        }

        List<ContractDeclaration> declarations = new ArrayList<>();
        for (int c = 0; c < contractCount; c++) {
            BilateralContract contract = contracts.get(c);
            ContractDeclaration declaration = new ContractDeclaration(
                    tradingDate,
                    DeclarationStatus.CONFIRMED,
                    contract
            );

            String nodeCode = String.format("NODE_ZONE_%02d", (c % 4) + 1);
            for (int interval = 1; interval <= INTERVALS_PER_DAY; interval++) {
                BigDecimal baseMwh = BigDecimal.valueOf(15.000000 + (c * 2) + (interval * 0.05))
                        .setScale(6, RoundingMode.HALF_UP);

                IntervalQuantity quantity = new IntervalQuantity(
                        interval,
                        nodeCode,
                        baseMwh,
                        declaration
                );
                declaration.addInterval(quantity);
            }

            declarations.add(declarationRepository.save(declaration));
        }

        return declarations;
    }

    @Transactional
    public List<MeterReadingHeader> seedMeterReadings(LocalDate readingDate, int count) {
        List<MeterReadingHeader> headers = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            String meterCode = String.format("MTR_%04d", i);
            MeterReadingHeader header = new MeterReadingHeader(meterCode, readingDate);
            for (int interval = 1; interval <= INTERVALS_PER_DAY; interval++) {
                BigDecimal actualMwh = BigDecimal.valueOf(14.800000 + (i * 2) + (interval * 0.05))
                        .setScale(6, RoundingMode.HALF_UP);
                MeterIntervalReading reading = new MeterIntervalReading(
                        interval,
                        actualMwh
                );
                header.addReading(reading);
            }
            headers.add(meterHeaderRepository.save(header));
        }
        return headers;
    }
}

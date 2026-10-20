package com.builtbyjuls.grid.contract;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Public DTO representing a confirmed contract declaration and its aggregated interval volume.
 */
public record ContractDeclarationDto(
        Long declarationId,
        String contractCode,
        String sellerCode,
        String buyerCode,
        LocalDate tradingDate,
        DeclarationStatus status,
        int intervalCount,
        BigDecimal totalDeclaredMwh
) {

    public ContractDeclarationDto {
        totalDeclaredMwh = totalDeclaredMwh != null ? totalDeclaredMwh : BigDecimal.ZERO;
    }

    public ContractDeclarationDto(
            Long declarationId,
            String contractCode,
            String sellerCode,
            String buyerCode,
            LocalDate tradingDate,
            DeclarationStatus status,
            long intervalCount,
            BigDecimal totalDeclaredMwh
    ) {
        this(
                declarationId,
                contractCode,
                sellerCode,
                buyerCode,
                tradingDate,
                status,
                (int) intervalCount,
                totalDeclaredMwh
        );
    }
}

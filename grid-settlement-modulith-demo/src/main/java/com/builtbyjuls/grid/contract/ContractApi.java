package com.builtbyjuls.grid.contract;

import java.time.LocalDate;
import java.util.List;

import org.springframework.modulith.NamedInterface;

/**
 * Public Spring Modulith module API for bilateral contracts and declarations.
 */
@NamedInterface("ContractApi")
public interface ContractApi {

    /**
     * Retrieves confirmed bilateral contract declarations and aggregated volumes for a trading day.
     *
     * @param tradingDate trading day
     * @return confirmed contract declaration DTOs
     */
    List<ContractDeclarationDto> findConfirmedDeclarations(LocalDate tradingDate);
}

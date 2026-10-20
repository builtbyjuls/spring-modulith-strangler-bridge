package com.builtbyjuls.grid.contract.internal;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.builtbyjuls.grid.contract.ContractApi;
import com.builtbyjuls.grid.contract.ContractDeclarationDto;
import com.builtbyjuls.grid.contract.DeclarationStatus;

@Service
@Transactional(readOnly = true)
public class ContractModuleService implements ContractApi {

    private final ContractDeclarationRepository declarationRepository;

    public ContractModuleService(ContractDeclarationRepository declarationRepository) {
        this.declarationRepository = Objects.requireNonNull(declarationRepository, "declarationRepository must not be null");
    }

    @Override
    public List<ContractDeclarationDto> findConfirmedDeclarations(LocalDate tradingDate) {
        Objects.requireNonNull(tradingDate, "tradingDate must not be null");
        return declarationRepository.findConfirmedDeclarationDtos(tradingDate, DeclarationStatus.CONFIRMED);
    }
}

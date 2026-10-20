package com.builtbyjuls.grid.contract.internal;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.builtbyjuls.grid.contract.ContractDeclarationDto;
import com.builtbyjuls.grid.contract.DeclarationStatus;

@Repository
public interface ContractDeclarationRepository extends JpaRepository<ContractDeclaration, Long> {

    @Query("SELECT new com.builtbyjuls.grid.contract.ContractDeclarationDto(" +
           "d.id, c.contractCode, s.participantCode, b.participantCode, d.tradingDate, d.status, " +
           "COUNT(i.id), SUM(i.declaredMwh)) " +
           "FROM ContractDeclaration d " +
           "JOIN d.bilateralContract c " +
           "JOIN c.seller s " +
           "JOIN c.buyer b " +
           "LEFT JOIN d.intervals i " +
           "WHERE d.tradingDate = :tradingDate AND d.status = :status " +
           "GROUP BY d.id, c.contractCode, s.participantCode, b.participantCode, d.tradingDate, d.status " +
           "ORDER BY c.contractCode")
    List<ContractDeclarationDto> findConfirmedDeclarationDtos(
            @Param("tradingDate") LocalDate tradingDate,
            @Param("status") DeclarationStatus status
    );

    List<ContractDeclaration> findByTradingDate(LocalDate tradingDate);
}

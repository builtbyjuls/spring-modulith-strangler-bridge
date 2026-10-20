package com.builtbyjuls.grid.contract.internal;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MarketParticipantRepository extends JpaRepository<MarketParticipant, Long> {

    Optional<MarketParticipant> findByParticipantCode(String participantCode);
}

package com.builtbyjuls.grid.contract.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface IntervalQuantityRepository extends JpaRepository<IntervalQuantity, Long> {
}

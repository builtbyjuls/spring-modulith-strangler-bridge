package com.builtbyjuls.grid.metering.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MeterIntervalReadingRepository extends JpaRepository<MeterIntervalReading, Long> {
}

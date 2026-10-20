package com.builtbyjuls.grid.metering.internal;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.builtbyjuls.grid.metering.DailyMeterVolumeDto;

@Repository
public interface MeterReadingHeaderRepository extends JpaRepository<MeterReadingHeader, Long> {

    @Query("SELECT new com.builtbyjuls.grid.metering.DailyMeterVolumeDto(" +
           "h.meterCode, h.readingDate, COUNT(r.id), SUM(r.actualMwh)) " +
           "FROM MeterReadingHeader h " +
           "LEFT JOIN h.readings r " +
           "WHERE h.readingDate = :readingDate " +
           "GROUP BY h.id, h.meterCode, h.readingDate " +
           "ORDER BY h.meterCode")
    List<DailyMeterVolumeDto> findDailyVolumes(@Param("readingDate") LocalDate readingDate);

    List<MeterReadingHeader> findByReadingDate(LocalDate readingDate);
}

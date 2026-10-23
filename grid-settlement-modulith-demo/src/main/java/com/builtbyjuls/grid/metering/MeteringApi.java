package com.builtbyjuls.grid.metering;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.springframework.modulith.NamedInterface;

/**
 * Public Spring Modulith module API for meter reading telemetry aggregation.
 */
@NamedInterface("MeteringApi")
public interface MeteringApi {

    /**
     * Aggregates daily telemetry meter readings across all dispatch intervals for a given date.
     *
     * @param readingDate reading date
     * @return daily aggregated meter volume DTOs
     */
    List<DailyMeterVolumeDto> aggregateDailyMeterVolumes(LocalDate readingDate);

    /**
     * Records a manual meter reading adjustment and returns the recorded volume DTO.
     *
     * @param meterCode meter identification code
     * @param readingDate reading date
     * @param actualMwh actual MWh volume
     * @return recorded volume DTO
     */
    DailyMeterVolumeDto recordMeterReading(String meterCode, LocalDate readingDate, BigDecimal actualMwh);
}

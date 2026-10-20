package com.builtbyjuls.grid.metering;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Public DTO representing aggregated daily meter telemetry volume.
 */
public record DailyMeterVolumeDto(
        String meterCode,
        LocalDate readingDate,
        int intervalCount,
        BigDecimal totalActualMwh
) {

    public DailyMeterVolumeDto {
        totalActualMwh = totalActualMwh != null ? totalActualMwh : BigDecimal.ZERO;
    }

    public DailyMeterVolumeDto(
            String meterCode,
            LocalDate readingDate,
            long intervalCount,
            BigDecimal totalActualMwh
    ) {
        this(
                meterCode,
                readingDate,
                (int) intervalCount,
                totalActualMwh
        );
    }
}

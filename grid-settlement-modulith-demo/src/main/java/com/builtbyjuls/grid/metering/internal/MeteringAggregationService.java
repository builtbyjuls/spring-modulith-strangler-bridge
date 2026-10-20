package com.builtbyjuls.grid.metering.internal;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.builtbyjuls.grid.metering.DailyMeterVolumeDto;
import com.builtbyjuls.grid.metering.MeteringApi;

@Service
@Transactional(readOnly = true)
public class MeteringAggregationService implements MeteringApi {

    private final MeterReadingHeaderRepository meterReadingHeaderRepository;

    public MeteringAggregationService(MeterReadingHeaderRepository meterReadingHeaderRepository) {
        this.meterReadingHeaderRepository = Objects.requireNonNull(
                meterReadingHeaderRepository,
                "meterReadingHeaderRepository must not be null"
        );
    }

    @Override
    public List<DailyMeterVolumeDto> aggregateDailyMeterVolumes(LocalDate readingDate) {
        Objects.requireNonNull(readingDate, "readingDate must not be null");
        return meterReadingHeaderRepository.findDailyVolumes(readingDate);
    }
}

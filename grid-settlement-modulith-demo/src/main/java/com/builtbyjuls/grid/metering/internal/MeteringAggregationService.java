package com.builtbyjuls.grid.metering.internal;

import java.math.BigDecimal;
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

    @Override
    @Transactional
    public DailyMeterVolumeDto recordMeterReading(String meterCode, LocalDate readingDate, BigDecimal actualMwh) {
        Objects.requireNonNull(meterCode, "meterCode must not be null");
        Objects.requireNonNull(readingDate, "readingDate must not be null");
        Objects.requireNonNull(actualMwh, "actualMwh must not be null");

        MeterReadingHeader header = new MeterReadingHeader(meterCode, readingDate);
        MeterIntervalReading reading = new MeterIntervalReading(1, actualMwh, header);
        header.addReading(reading);
        meterReadingHeaderRepository.save(header);

        return new DailyMeterVolumeDto(meterCode, readingDate, 1, actualMwh);
    }
}

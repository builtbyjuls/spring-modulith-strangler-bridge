package com.builtbyjuls.grid.metering.internal;

import java.util.List;

import org.springframework.stereotype.Service;

import com.builtbyjuls.grid.metering.MeteringApi;

@Service
public class MockMeteringAggregationService implements MeteringApi {

    @Override
    public List<String> getReadings() {
        return List.of("reading-1", "reading-2");
    }
}

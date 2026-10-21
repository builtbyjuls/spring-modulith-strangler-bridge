package com.builtbyjuls.grid.metering;

import java.util.List;

import org.springframework.modulith.NamedInterface;

@NamedInterface("MeteringApi")
public interface MeteringApi {

    List<String> getReadings();
}

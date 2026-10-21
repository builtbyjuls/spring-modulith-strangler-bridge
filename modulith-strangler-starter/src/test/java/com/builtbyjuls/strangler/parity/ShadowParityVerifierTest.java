package com.builtbyjuls.strangler.parity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import static org.assertj.core.api.Assertions.assertThat;

class ShadowParityVerifierTest {

    private ShadowParityVerifier verifier;

    record SampleVolumeDto(String meterCode, LocalDate date, BigDecimal totalActualMwh) {
    }

    record NestedMetricDto(String metricName, List<SampleVolumeDto> volumes, Map<String, String> tags) {
    }

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        this.verifier = new ShadowParityVerifier(mapper);
    }

    @Test
    @DisplayName("Exact object and list match returns matched with null diff summary")
    void shouldMatchExactObjectsAndLists() {
        LocalDate date = LocalDate.of(2026, 10, 22);
        List<SampleVolumeDto> local = List.of(
                new SampleVolumeDto("MTR_001", date, new BigDecimal("144.500000")),
                new SampleVolumeDto("MTR_002", date, new BigDecimal("288.750000"))
        );
        List<SampleVolumeDto> remote = List.of(
                new SampleVolumeDto("MTR_001", date, new BigDecimal("144.500000")),
                new SampleVolumeDto("MTR_002", date, new BigDecimal("288.750000"))
        );

        ShadowParityVerifier.ParityOutcome outcome = verifier.verify(local, remote);

        assertThat(outcome.matched()).isTrue();
        assertThat(outcome.diffSummary()).isNull();
    }

    @Test
    @DisplayName("BigDecimal scale normalization treats 144.00 and 144.000000 as equal")
    void shouldNormalizeBigDecimalScale() {
        LocalDate date = LocalDate.of(2026, 10, 22);
        SampleVolumeDto local = new SampleVolumeDto("MTR_001", date, new BigDecimal("144.00"));
        SampleVolumeDto remote = new SampleVolumeDto("MTR_001", date, new BigDecimal("144.000000"));

        ShadowParityVerifier.ParityOutcome outcome = verifier.verify(local, remote);

        assertThat(outcome.matched()).isTrue();
        assertThat(outcome.diffSummary()).isNull();
    }

    @Test
    @DisplayName("Nested field or list element drift produces exact JSON path diff summary")
    void shouldDetectNestedFieldAndElementDrift() {
        LocalDate date = LocalDate.of(2026, 10, 22);
        List<SampleVolumeDto> local = List.of(
                new SampleVolumeDto("MTR_001", date, new BigDecimal("144.000000"))
        );
        List<SampleVolumeDto> remote = List.of(
                new SampleVolumeDto("MTR_001", date, new BigDecimal("999.000000"))
        );

        ShadowParityVerifier.ParityOutcome outcome = verifier.verify(local, remote);

        assertThat(outcome.matched()).isFalse();
        assertThat(outcome.diffSummary()).isNotNull();
        assertThat(outcome.diffSummary()).contains("$[0].totalActualMwh: local=144.000000 vs remote=999.000000");
    }

    @Test
    @DisplayName("Null handling matches null vs null and detects null vs non-null mismatches")
    void shouldHandleNullValuesCorrectly() {
        // null vs null matches
        ShadowParityVerifier.ParityOutcome bothNull = verifier.verify(null, null);
        assertThat(bothNull.matched()).isTrue();
        assertThat(bothNull.diffSummary()).isNull();

        // null vs non-null mismatches
        SampleVolumeDto localDto = new SampleVolumeDto("MTR_001", LocalDate.of(2026, 10, 22), new BigDecimal("100.00"));
        ShadowParityVerifier.ParityOutcome localNull = verifier.verify(null, localDto);
        assertThat(localNull.matched()).isFalse();
        assertThat(localNull.diffSummary()).contains("$: local=null vs remote=");

        // non-null vs null mismatches
        ShadowParityVerifier.ParityOutcome remoteNull = verifier.verify(localDto, null);
        assertThat(remoteNull.matched()).isFalse();
        assertThat(remoteNull.diffSummary()).contains("$: local=");
        assertThat(remoteNull.diffSummary()).contains("vs remote=null");
    }

    @Test
    @DisplayName("Array size mismatch is detected with clear description")
    void shouldDetectArraySizeMismatch() {
        List<String> local = List.of("A", "B");
        List<String> remote = List.of("A");

        ShadowParityVerifier.ParityOutcome outcome = verifier.verify(local, remote);

        assertThat(outcome.matched()).isFalse();
        assertThat(outcome.diffSummary()).contains("$: array size mismatch (local=2 vs remote=1)");
    }

    @Test
    @DisplayName("Deeply nested object field differences are detected with path tracking")
    void shouldTrackDeeplyNestedObjectPaths() {
        LocalDate date = LocalDate.of(2026, 10, 22);
        NestedMetricDto local = new NestedMetricDto(
                "GRID_LOAD",
                List.of(new SampleVolumeDto("MTR_001", date, new BigDecimal("10.50"))),
                Map.of("region", "NORTH", "env", "PROD")
        );
        NestedMetricDto remote = new NestedMetricDto(
                "GRID_LOAD",
                List.of(new SampleVolumeDto("MTR_001", date, new BigDecimal("10.50"))),
                Map.of("region", "SOUTH", "env", "PROD")
        );

        ShadowParityVerifier.ParityOutcome outcome = verifier.verify(local, remote);

        assertThat(outcome.matched()).isFalse();
        assertThat(outcome.diffSummary()).contains("$.tags.region: local=NORTH vs remote=SOUTH");
    }

    @Test
    @DisplayName("Convenience verify method builds full ShadowParityResult")
    void shouldBuildShadowParityResultFromVerification() {
        ShadowParityResult result = verifier.verify(
                "metering",
                "com.builtbyjuls.grid.metering.MeteringApi",
                "aggregateDailyMeterVolumes",
                "OK",
                "OK",
                12L,
                24L
        );

        assertThat(result.moduleName()).isEqualTo("metering");
        assertThat(result.interfaceName()).isEqualTo("com.builtbyjuls.grid.metering.MeteringApi");
        assertThat(result.methodName()).isEqualTo("aggregateDailyMeterVolumes");
        assertThat(result.matched()).isTrue();
        assertThat(result.localDurationMs()).isEqualTo(12L);
        assertThat(result.remoteDurationMs()).isEqualTo(24L);
        assertThat(result.diffSummary()).isNull();
        assertThat(result.remoteError()).isNull();
        assertThat(result.fallbackTriggered()).isFalse();
    }
}

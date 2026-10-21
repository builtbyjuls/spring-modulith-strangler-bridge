package com.builtbyjuls.strangler.transport;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import static org.assertj.core.api.Assertions.assertThat;

class BridgeInvocationModelTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    @DisplayName("BridgeInvocationRequest serializes and deserializes cleanly with Jackson")
    void shouldSerializeAndDeserializeBridgeInvocationRequest() throws Exception {
        JsonNode argNode = objectMapper.readTree("{\"readingDate\":\"2026-10-22\"}");
        BridgeInvocationRequest request = new BridgeInvocationRequest(
                "metering",
                "com.builtbyjuls.grid.metering.MeteringApi",
                "aggregateDailyMeterVolumes",
                List.of("java.time.LocalDate"),
                List.of(argNode)
        );

        String json = objectMapper.writeValueAsString(request);
        BridgeInvocationRequest deserialized = objectMapper.readValue(json, BridgeInvocationRequest.class);

        assertThat(deserialized.moduleName()).isEqualTo("metering");
        assertThat(deserialized.interfaceName()).isEqualTo("com.builtbyjuls.grid.metering.MeteringApi");
        assertThat(deserialized.methodName()).isEqualTo("aggregateDailyMeterVolumes");
        assertThat(deserialized.parameterTypes()).containsExactly("java.time.LocalDate");
        assertThat(deserialized.arguments()).hasSize(1);
        assertThat(deserialized.arguments().getFirst().get("readingDate").asText()).isEqualTo("2026-10-22");
    }

    @Test
    @DisplayName("BridgeInvocationResponse serializes and deserializes results and errors cleanly")
    void shouldSerializeAndDeserializeBridgeInvocationResponse() throws Exception {
        JsonNode resultNode = objectMapper.readTree("[{\"meterCode\":\"MTR_001\",\"totalActualMwh\":125.50}]");
        BridgeInvocationResponse successResponse = new BridgeInvocationResponse(
                resultNode,
                42L,
                "metering",
                null
        );

        String successJson = objectMapper.writeValueAsString(successResponse);
        BridgeInvocationResponse deserializedSuccess = objectMapper.readValue(successJson, BridgeInvocationResponse.class);

        assertThat(deserializedSuccess.result()).isNotNull();
        assertThat(deserializedSuccess.durationMs()).isEqualTo(42L);
        assertThat(deserializedSuccess.moduleName()).isEqualTo("metering");
        assertThat(deserializedSuccess.error()).isNull();

        BridgeInvocationResponse errorResponse = new BridgeInvocationResponse(
                null,
                15L,
                "metering",
                "Module timeout exceeded"
        );

        String errorJson = objectMapper.writeValueAsString(errorResponse);
        BridgeInvocationResponse deserializedError = objectMapper.readValue(errorJson, BridgeInvocationResponse.class);

        assertThat(deserializedError.result()).isNull();
        assertThat(deserializedError.error()).isEqualTo("Module timeout exceeded");
    }
}

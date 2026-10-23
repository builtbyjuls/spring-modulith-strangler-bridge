package com.builtbyjuls.strangler.transport;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class BridgeHttpClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private RestClient.Builder restClientBuilder;
    private MockRestServiceServer mockServer;
    private BridgeHttpClient httpClient;

    public record TestDto(String meterCode, LocalDate date, BigDecimal amount) {}

    @BeforeEach
    void setUp() {
        this.restClientBuilder = RestClient.builder();
        this.mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();
        this.httpClient = new BridgeHttpClient(restClientBuilder, objectMapper);
    }

    @Test
    @DisplayName("BridgeHttpClient sends POST to /internal/modulith-bridge/invoke and deserializes generic return type")
    void shouldInvokeRemoteAndDeserializeGenericReturnType() throws Exception {
        String responseJson = """
                {
                    "result": [
                        {
                            "meterCode": "MTR_001",
                            "date": "2026-10-22",
                            "amount": 125.750000
                        }
                    ],
                    "durationMs": 12,
                    "moduleName": "metering",
                    "error": null
                }
                """;

        mockServer.expect(requestTo("http://worker-node:8080/internal/modulith-bridge/invoke"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Content-Type", MediaType.APPLICATION_JSON_VALUE))
                .andExpect(jsonPath("$.moduleName").value("metering"))
                .andExpect(jsonPath("$.interfaceName").value("com.example.MeteringApi"))
                .andExpect(jsonPath("$.methodName").value("getVolumes"))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        BridgeInvocationRequest request = new BridgeInvocationRequest(
                "metering",
                "com.example.MeteringApi",
                "getVolumes",
                List.of("java.time.LocalDate"),
                List.of(objectMapper.valueToTree(LocalDate.of(2026, 10, 22)))
        );

        Type returnType = new ParameterizedTypeReference<List<TestDto>>() {}.getType();
        @SuppressWarnings("unchecked")
        List<TestDto> result = (List<TestDto>) httpClient.invokeRemote("http://worker-node:8080", request, returnType, 5000L);

        mockServer.verify();
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().meterCode()).isEqualTo("MTR_001");
        assertThat(result.getFirst().date()).isEqualTo(LocalDate.of(2026, 10, 22));
        assertThat(result.getFirst().amount()).isEqualByComparingTo(new BigDecimal("125.75"));
    }

    @Test
    @DisplayName("BridgeHttpClient throws BridgeRemoteInvocationException when response has error field")
    void shouldThrowWhenRemoteReturnsErrorField() {
        String responseJson = """
                {
                    "result": null,
                    "durationMs": 5,
                    "moduleName": "metering",
                    "error": "Database query timed out"
                }
                """;

        mockServer.expect(requestTo("http://worker-node:8080/internal/modulith-bridge/invoke"))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        BridgeInvocationRequest request = new BridgeInvocationRequest(
                "metering",
                "com.example.MeteringApi",
                "getVolumes",
                List.of(),
                List.of()
        );

        assertThatThrownBy(() -> httpClient.invokeRemote("http://worker-node:8080", request, String.class, 5000L))
                .isInstanceOf(BridgeRemoteInvocationException.class)
                .hasMessageContaining("Database query timed out");
    }

    @Test
    @DisplayName("BridgeHttpClient throws exception when HTTP call returns 500 server error")
    void shouldThrowWhenHttp500() {
        mockServer.expect(requestTo("http://worker-node:8080/internal/modulith-bridge/invoke"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        BridgeInvocationRequest request = new BridgeInvocationRequest(
                "metering",
                "com.example.MeteringApi",
                "getVolumes",
                List.of(),
                List.of()
        );

        assertThatThrownBy(() -> httpClient.invokeRemote("http://worker-node:8080", request, String.class, 5000L))
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("BridgeHttpClient throws BridgeRemoteInvocationException when invocation exceeds timeoutMs")
    void shouldThrowWhenRemoteTimesOut() {
        CountDownLatch latch = new CountDownLatch(1);
        mockServer.expect(requestTo("http://worker-node:8080/internal/modulith-bridge/invoke"))
                .andRespond(clientRequest -> {
                    try {
                        latch.await(500, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException ignored) {
                    }
                    return new org.springframework.mock.http.client.MockClientHttpResponse(new byte[0], HttpStatus.OK);
                });

        BridgeInvocationRequest request = new BridgeInvocationRequest(
                "metering",
                "com.example.MeteringApi",
                "getVolumes",
                List.of(),
                List.of()
        );

        try {
            assertThatThrownBy(() -> httpClient.invokeRemote("http://worker-node:8080", request, String.class, 50L))
                    .isInstanceOf(BridgeRemoteInvocationException.class)
                    .hasMessageContaining("timed out after 50ms");
        } finally {
            latch.countDown();
        }
    }

    @Test
    @DisplayName("BridgeHttpClient attaches X-Modulith-Bridge-Secret header when secretToken is configured")
    void shouldAttachSecretHeaderWhenConfigured() {
        BridgeHttpClient clientWithSecret = new BridgeHttpClient(restClientBuilder, objectMapper, "my-auth-secret-xyz");
        String responseJson = """
                {
                    "result": null,
                    "durationMs": 5,
                    "moduleName": "metering",
                    "error": null
                }
                """;

        mockServer.expect(requestTo("http://worker-node:8080/internal/modulith-bridge/invoke"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Modulith-Bridge-Secret", "my-auth-secret-xyz"))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        BridgeInvocationRequest request = new BridgeInvocationRequest(
                "metering",
                "com.example.MeteringApi",
                "sync",
                List.of(),
                List.of()
        );

        clientWithSecret.invokeRemote("http://worker-node:8080", request, void.class, 5000L);
        mockServer.verify();
    }

    @Test
    @DisplayName("BridgeHttpClient throws BridgeBusinessException when response has businessError=true")
    void shouldThrowBridgeBusinessExceptionWhenResponseIsBusinessError() {
        String responseJson = """
                {
                    "result": null,
                    "durationMs": 8,
                    "moduleName": "metering",
                    "error": "Contract interval reading mismatch",
                    "businessError": true,
                    "exceptionType": "java.lang.IllegalArgumentException"
                }
                """;

        mockServer.expect(requestTo("http://worker-node:8080/internal/modulith-bridge/invoke"))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        BridgeInvocationRequest request = new BridgeInvocationRequest(
                "metering",
                "com.example.MeteringApi",
                "getVolumes",
                List.of(),
                List.of()
        );

        assertThatThrownBy(() -> httpClient.invokeRemote("http://worker-node:8080", request, String.class, 5000L))
                .isInstanceOf(BridgeBusinessException.class)
                .satisfies(ex -> {
                    BridgeBusinessException bbe = (BridgeBusinessException) ex;
                    assertThat(bbe.getModuleName()).isEqualTo("metering");
                    assertThat(bbe.getExceptionType()).isEqualTo("java.lang.IllegalArgumentException");
                    assertThat(bbe.getRemoteMessage()).isEqualTo("Contract interval reading mismatch");
                });
    }

    @Test
    @DisplayName("BridgeHttpClient attaches X-Modulith-Shadow: true header when shadowMode is true")
    void shouldAttachShadowHeaderWhenShadowModeIsTrue() {
        String responseJson = """
                {
                    "result": null,
                    "durationMs": 6,
                    "moduleName": "metering",
                    "error": null
                }
                """;

        mockServer.expect(requestTo("http://worker-node:8080/internal/modulith-bridge/invoke"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Modulith-Shadow", "true"))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        BridgeInvocationRequest request = new BridgeInvocationRequest(
                "metering",
                "com.example.MeteringApi",
                "sync",
                List.of(),
                List.of()
        );

        httpClient.invokeRemote("http://worker-node:8080", request, void.class, 5000L, true);
        mockServer.verify();
    }

    @Test
    @DisplayName("BridgeHttpClient omits X-Modulith-Shadow header when shadowMode is false")
    void shouldOmitShadowHeaderWhenShadowModeIsFalse() {
        String responseJson = """
                {
                    "result": null,
                    "durationMs": 6,
                    "moduleName": "metering",
                    "error": null
                }
                """;

        mockServer.expect(requestTo("http://worker-node:8080/internal/modulith-bridge/invoke"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(headerDoesNotExist("X-Modulith-Shadow"))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        BridgeInvocationRequest request = new BridgeInvocationRequest(
                "metering",
                "com.example.MeteringApi",
                "sync",
                List.of(),
                List.of()
        );

        httpClient.invokeRemote("http://worker-node:8080", request, void.class, 5000L, false);
        mockServer.verify();
    }
}

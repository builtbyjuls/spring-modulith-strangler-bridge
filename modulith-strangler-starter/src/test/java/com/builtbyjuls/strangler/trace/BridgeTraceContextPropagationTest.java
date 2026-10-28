package com.builtbyjuls.strangler.trace;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.modulith.NamedInterface;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.builtbyjuls.strangler.config.ModulithBridgeProperties;
import com.builtbyjuls.strangler.slice.ModuleLayoutInspector;
import com.builtbyjuls.strangler.transport.BridgeHttpClient;
import com.builtbyjuls.strangler.transport.BridgeInvocationRequest;
import com.builtbyjuls.strangler.transport.BridgeInvocationResponse;
import com.builtbyjuls.strangler.transport.ModulithBridgeInboundController;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class BridgeTraceContextPropagationTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private ModulithBridgeProperties properties;
    private RestClient.Builder restClientBuilder;
    private MockRestServiceServer mockServer;
    private BridgeHttpClient httpClient;

    @NamedInterface("TracedMeteringApi")
    public interface TracedMeteringApi {
        String queryVolumes(String date);
    }

    @BeforeEach
    void setUp() {
        BridgeTraceContextHolder.clear();
        this.properties = new ModulithBridgeProperties();
        this.properties.setBasePackage("com.builtbyjuls.grid");
        this.restClientBuilder = RestClient.builder();
        this.mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();
        this.httpClient = new BridgeHttpClient(restClientBuilder, objectMapper, properties);
    }

    @AfterEach
    void tearDown() {
        BridgeTraceContextHolder.clear();
    }

    @Test
    @DisplayName("Caller SLF4J MDC is captured across virtual thread executor and sent as HTTP trace headers")
    void shouldCaptureMdcAcrossVirtualThreadsAndAttachHttpHeaders() throws Exception {
        MDC.put(BridgeTraceContextHolder.MDC_TRACEPARENT, "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        MDC.put(BridgeTraceContextHolder.MDC_TRACESTATE, "rojo=1,congo=2");
        MDC.put(BridgeTraceContextHolder.MDC_BAGGAGE, "userId=alice,env=prod");
        MDC.put(BridgeTraceContextHolder.MDC_CORRELATION_ID, "corr-xyz-789");

        String responseJson = objectMapper.writeValueAsString(
                BridgeInvocationResponse.success(objectMapper.valueToTree("OK"), 2, "metering")
        );

        mockServer.expect(requestTo("http://remote-worker:8080/internal/modulith-bridge/invoke"))
                .andExpect(header(BridgeTraceContextHolder.TRACEPARENT_HEADER, "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"))
                .andExpect(header(BridgeTraceContextHolder.TRACESTATE_HEADER, "rojo=1,congo=2"))
                .andExpect(header(BridgeTraceContextHolder.BAGGAGE_HEADER, "userId=alice,env=prod"))
                .andExpect(header(BridgeTraceContextHolder.CORRELATION_ID_HEADER, "corr-xyz-789"))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        BridgeInvocationRequest request = new BridgeInvocationRequest(
                "metering",
                TracedMeteringApi.class.getName(),
                "queryVolumes",
                List.of(String.class.getName()),
                List.of(objectMapper.valueToTree("2026-03-20"))
        );

        Object result = httpClient.invokeRemote("http://remote-worker:8080", request, String.class);
        assertThat(result).isEqualTo("OK");
        mockServer.verify();
    }

    @Test
    @DisplayName("BridgeHttpClient auto-generates X-Modulith-Correlation-Id when none is set in MDC or context")
    void shouldAutoGenerateCorrelationIdWhenNoneSet() throws Exception {
        assertThat(MDC.get(BridgeTraceContextHolder.MDC_CORRELATION_ID)).isNull();

        String responseJson = objectMapper.writeValueAsString(
                BridgeInvocationResponse.success(objectMapper.valueToTree("OK"), 2, "metering")
        );

        AtomicReference<String> capturedCorrelationId = new AtomicReference<>();

        mockServer.expect(requestTo("http://remote-worker:8080/internal/modulith-bridge/invoke"))
                .andExpect(req -> {
                    List<String> headers = req.getHeaders().get(BridgeTraceContextHolder.CORRELATION_ID_HEADER);
                    assertThat(headers).isNotEmpty();
                    String corrId = headers.get(0);
                    assertThat(corrId).isNotBlank();
                    // Validate UUID format
                    UUID.fromString(corrId);
                    capturedCorrelationId.set(corrId);
                })
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        BridgeInvocationRequest request = new BridgeInvocationRequest(
                "metering",
                TracedMeteringApi.class.getName(),
                "queryVolumes",
                List.of(String.class.getName()),
                List.of(objectMapper.valueToTree("2026-03-20"))
        );

        Object result = httpClient.invokeRemote("http://remote-worker:8080", request, String.class);
        assertThat(result).isEqualTo("OK");
        assertThat(capturedCorrelationId.get()).isNotNull();
        mockServer.verify();
    }

    @Test
    @DisplayName("ModulithBridgeInboundController binds inbound trace headers into MDC and cleans up after call")
    void shouldBindInboundHeadersIntoMdcAndClearAfterExecution() throws Exception {
        GenericApplicationContext context = new GenericApplicationContext();

        AtomicReference<String> beanSeenTraceparent = new AtomicReference<>();
        AtomicReference<String> beanSeenBaggage = new AtomicReference<>();
        AtomicReference<String> beanSeenCorrelationId = new AtomicReference<>();
        AtomicReference<BridgeTraceContext> beanSeenContext = new AtomicReference<>();

        TracedMeteringApi testService = date -> {
            beanSeenTraceparent.set(MDC.get(BridgeTraceContextHolder.MDC_TRACEPARENT));
            beanSeenBaggage.set(MDC.get(BridgeTraceContextHolder.MDC_BAGGAGE));
            beanSeenCorrelationId.set(MDC.get(BridgeTraceContextHolder.MDC_CORRELATION_ID));
            beanSeenContext.set(BridgeTraceContextHolder.current());
            return "RESULT_" + date;
        };

        context.registerBean("tracedMeteringService", TracedMeteringApi.class, () -> testService);
        context.refresh();

        ModuleLayoutInspector inspector = new ModuleLayoutInspector();
        ModulithBridgeInboundController controller = new ModulithBridgeInboundController(
                context,
                inspector,
                properties,
                objectMapper
        );

        BridgeInvocationRequest request = new BridgeInvocationRequest(
                "metering",
                TracedMeteringApi.class.getName(),
                "queryVolumes",
                List.of(String.class.getName()),
                List.of(objectMapper.valueToTree("2026-03-20"))
        );
        String rawJson = objectMapper.writeValueAsString(request);

        String inboundTraceparent = "00-testtrace1234567890123456789012-0011223344556677-01";
        String inboundTracestate = "sample=state";
        String inboundBaggage = "tenant=energy-corp";
        String inboundCorrelationId = "inbound-corr-555";

        ResponseEntity<String> response = controller.invokeHttp(
                rawJson,
                null,
                null,
                inboundTraceparent,
                inboundTracestate,
                inboundBaggage,
                inboundCorrelationId
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(beanSeenTraceparent.get()).isEqualTo(inboundTraceparent);
        assertThat(beanSeenBaggage.get()).isEqualTo(inboundBaggage);
        assertThat(beanSeenCorrelationId.get()).isEqualTo(inboundCorrelationId);

        assertThat(beanSeenContext.get()).isNotNull();
        assertThat(beanSeenContext.get().traceparent()).isEqualTo(inboundTraceparent);
        assertThat(beanSeenContext.get().tracestate()).isEqualTo(inboundTracestate);
        assertThat(beanSeenContext.get().baggage()).isEqualTo(inboundBaggage);
        assertThat(beanSeenContext.get().correlationId()).isEqualTo(inboundCorrelationId);

        // After invokeHttp finishes, MDC and ThreadLocal must be clean
        assertThat(MDC.get(BridgeTraceContextHolder.MDC_TRACEPARENT)).isNull();
        assertThat(MDC.get(BridgeTraceContextHolder.MDC_TRACESTATE)).isNull();
        assertThat(MDC.get(BridgeTraceContextHolder.MDC_BAGGAGE)).isNull();
        assertThat(MDC.get(BridgeTraceContextHolder.MDC_CORRELATION_ID)).isNull();
        assertThat(BridgeTraceContextHolder.current().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("BridgeTraceContextHolder openScope restores previous MDC and ThreadLocal values when closed")
    void shouldRestorePreviousMdcAndThreadLocalWhenScopeClosed() {
        MDC.put(BridgeTraceContextHolder.MDC_TRACEPARENT, "parent-trace");
        MDC.put(BridgeTraceContextHolder.MDC_CORRELATION_ID, "parent-corr");

        BridgeTraceContext childContext = new BridgeTraceContext("child-trace", "child-state", "child-bag", "child-corr");
        try (BridgeTraceContextHolder.Scope scope = BridgeTraceContextHolder.openScope(childContext)) {
            assertThat(MDC.get(BridgeTraceContextHolder.MDC_TRACEPARENT)).isEqualTo("child-trace");
            assertThat(MDC.get(BridgeTraceContextHolder.MDC_TRACESTATE)).isEqualTo("child-state");
            assertThat(MDC.get(BridgeTraceContextHolder.MDC_BAGGAGE)).isEqualTo("child-bag");
            assertThat(MDC.get(BridgeTraceContextHolder.MDC_CORRELATION_ID)).isEqualTo("child-corr");
            assertThat(BridgeTraceContextHolder.current().traceparent()).isEqualTo("child-trace");
        }

        // Restored to parent values
        assertThat(MDC.get(BridgeTraceContextHolder.MDC_TRACEPARENT)).isEqualTo("parent-trace");
        assertThat(MDC.get(BridgeTraceContextHolder.MDC_CORRELATION_ID)).isEqualTo("parent-corr");
        assertThat(MDC.get(BridgeTraceContextHolder.MDC_TRACESTATE)).isNull();
        assertThat(MDC.get(BridgeTraceContextHolder.MDC_BAGGAGE)).isNull();
    }
}

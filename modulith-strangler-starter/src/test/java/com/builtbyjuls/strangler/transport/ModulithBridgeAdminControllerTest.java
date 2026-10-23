package com.builtbyjuls.strangler.transport;

import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.modulith.NamedInterface;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.builtbyjuls.strangler.config.BridgeMode;
import com.builtbyjuls.strangler.config.ModulithBridgeProperties;
import com.builtbyjuls.strangler.proxy.ModulithBridgeInvocationHandler;
import com.builtbyjuls.strangler.transport.ModulithBridgeAdminController.RouteUpdateRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ModulithBridgeAdminControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private ModulithBridgeProperties properties;
    private ModulithBridgeAdminController controller;
    private RestClient.Builder restClientBuilder;
    private MockRestServiceServer mockServer;
    private BridgeHttpClient httpClient;

    @NamedInterface("AdminTestMeteringApi")
    public interface AdminTestMeteringApi {
        List<String> getVolumes(LocalDate date);
    }

    public static class AdminLocalMeteringService implements AdminTestMeteringApi {
        int callCount = 0;

        @Override
        public List<String> getVolumes(LocalDate date) {
            callCount++;
            return List.of("LOCAL_" + date);
        }
    }

    @BeforeEach
    void setUp() {
        this.properties = new ModulithBridgeProperties();
        this.properties.setBasePackage("com.builtbyjuls.grid");
        this.controller = new ModulithBridgeAdminController(properties, objectMapper);
        this.restClientBuilder = RestClient.builder();
        this.mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();
        this.httpClient = new BridgeHttpClient(restClientBuilder, objectMapper);
    }

    @Test
    @DisplayName("GET /internal/modulith-bridge/routes returns current module route map")
    void shouldReturnConfiguredRoutesOnGet() {
        ModulithBridgeProperties.ModuleRouteConfig meteringRoute = new ModulithBridgeProperties.ModuleRouteConfig();
        meteringRoute.setMode(BridgeMode.LOCAL);
        meteringRoute.setUrl("http://localhost:8081");
        properties.getModules().put("metering", meteringRoute);

        ResponseEntity<Map<String, ModulithBridgeProperties.ModuleRouteConfig>> entity = controller.getRoutes();

        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, ModulithBridgeProperties.ModuleRouteConfig> routes = entity.getBody();
        assertThat(routes).isNotNull();
        assertThat(routes).containsKey("metering");
        assertThat(routes.get("metering").getMode()).isEqualTo(BridgeMode.LOCAL);
        assertThat(routes.get("metering").getUrl()).isEqualTo("http://localhost:8081");
    }

    @Test
    @DisplayName("POST updates mode, url, timeout, fallback, and sample rate live without restarting proxy")
    void shouldUpdateRouteLiveInMemoryAndAffectProxyImmediately() {
        AdminLocalMeteringService localService = new AdminLocalMeteringService();
        ModulithBridgeInvocationHandler handler = new ModulithBridgeInvocationHandler(
                localService,
                "metering",
                AdminTestMeteringApi.class,
                properties,
                httpClient,
                objectMapper
        );

        AdminTestMeteringApi proxy = (AdminTestMeteringApi) Proxy.newProxyInstance(
                AdminTestMeteringApi.class.getClassLoader(),
                new Class<?>[]{AdminTestMeteringApi.class},
                handler
        );

        // Step 1: Initial invocation in LOCAL mode
        List<String> firstResult = proxy.getVolumes(LocalDate.of(2026, 10, 23));
        assertThat(firstResult).containsExactly("LOCAL_2026-10-23");
        assertThat(localService.callCount).isEqualTo(1);
        mockServer.verify();

        // Step 2: Live switch LOCAL -> REMOTE with custom url, timeout, fallback, and sample rate
        RouteUpdateRequest updateRequest = new RouteUpdateRequest(
                BridgeMode.REMOTE,
                "http://live-remote-worker:8080",
                2500L,
                false,
                0.75
        );

        ResponseEntity<ModulithBridgeProperties.ModuleRouteConfig> updateEntity =
                controller.updateRoute("metering", updateRequest);

        assertThat(updateEntity.getStatusCode()).isEqualTo(HttpStatus.OK);
        ModulithBridgeProperties.ModuleRouteConfig updatedConfig = updateEntity.getBody();
        assertThat(updatedConfig).isNotNull();
        assertThat(updatedConfig.getMode()).isEqualTo(BridgeMode.REMOTE);
        assertThat(updatedConfig.getUrl()).isEqualTo("http://live-remote-worker:8080");
        assertThat(updatedConfig.getTimeoutMs()).isEqualTo(2500L);
        assertThat(updatedConfig.isFallbackToLocal()).isFalse();
        assertThat(updatedConfig.getShadowSampleRate()).isEqualTo(0.75);

        // Step 3: Next invocation on the same proxy instance immediately routes to REMOTE over HTTP
        String remoteResponseJson = """
                {
                    "result": ["REMOTE_2026-10-23"],
                    "durationMs": 8,
                    "moduleName": "metering",
                    "error": null
                }
                """;

        mockServer.expect(requestTo("http://live-remote-worker:8080/internal/modulith-bridge/invoke"))
                .andRespond(withSuccess(remoteResponseJson, MediaType.APPLICATION_JSON));

        List<String> secondResult = proxy.getVolumes(LocalDate.of(2026, 10, 23));

        assertThat(secondResult).containsExactly("REMOTE_2026-10-23");
        // Local service was not called for the remote invocation
        assertThat(localService.callCount).isEqualTo(1);
        mockServer.verify();
    }

    @Test
    @DisplayName("Admin controller enforces X-Modulith-Bridge-Secret authentication when secretToken is configured")
    void shouldRejectMissingOrInvalidSecretTokenWithUnauthorized() {
        properties.setSecretToken("admin-secret-token-xyz");

        RouteUpdateRequest updateRequest = new RouteUpdateRequest(
                BridgeMode.SHADOW,
                "http://shadow-host:8080",
                3000L,
                true,
                1.0
        );

        // GET without secret
        ResponseEntity<Map<String, ModulithBridgeProperties.ModuleRouteConfig>> getMissing =
                controller.getRoutes(null);
        assertThat(getMissing.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // GET with invalid secret
        ResponseEntity<Map<String, ModulithBridgeProperties.ModuleRouteConfig>> getWrong =
                controller.getRoutes("wrong-secret");
        assertThat(getWrong.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // GET with valid secret
        ResponseEntity<Map<String, ModulithBridgeProperties.ModuleRouteConfig>> getValid =
                controller.getRoutes("admin-secret-token-xyz");
        assertThat(getValid.getStatusCode()).isEqualTo(HttpStatus.OK);

        // POST without secret
        ResponseEntity<ModulithBridgeProperties.ModuleRouteConfig> postMissing =
                controller.updateRoute("metering", updateRequest, null);
        assertThat(postMissing.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // POST with invalid secret
        ResponseEntity<ModulithBridgeProperties.ModuleRouteConfig> postWrong =
                controller.updateRoute("metering", updateRequest, "wrong-secret");
        assertThat(postWrong.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // POST with valid secret
        ResponseEntity<ModulithBridgeProperties.ModuleRouteConfig> postValid =
                controller.updateRoute("metering", updateRequest, "admin-secret-token-xyz");
        assertThat(postValid.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(postValid.getBody()).isNotNull();
        assertThat(postValid.getBody().getMode()).isEqualTo(BridgeMode.SHADOW);
    }

    @Test
    @DisplayName("Admin controller returns 400 Bad Request when moduleName is blank")
    void shouldReturnBadRequestWhenModuleNameIsBlank() {
        ResponseEntity<ModulithBridgeProperties.ModuleRouteConfig> response =
                controller.updateRoute("  ", new RouteUpdateRequest(BridgeMode.LOCAL, null, null, null, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}

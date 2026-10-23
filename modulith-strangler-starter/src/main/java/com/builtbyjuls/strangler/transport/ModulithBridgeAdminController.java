package com.builtbyjuls.strangler.transport;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.builtbyjuls.strangler.config.BridgeMode;
import com.builtbyjuls.strangler.config.ModulithBridgeProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Administrative controller for zero-restart live mode switching and route configuration updates.
 */
@RestController
@RequestMapping("/internal/modulith-bridge/routes")
public class ModulithBridgeAdminController {

    private static final Logger log = LoggerFactory.getLogger(ModulithBridgeAdminController.class);

    private final ModulithBridgeProperties properties;
    private final ObjectMapper objectMapper;

    public ModulithBridgeAdminController(ModulithBridgeProperties properties, ObjectMapper objectMapper) {
        this.properties = properties != null ? properties : new ModulithBridgeProperties();
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    public record RouteUpdateRequest(
            BridgeMode mode,
            String url,
            Long timeoutMs,
            Boolean fallbackToLocal,
            Double shadowSampleRate
    ) {}

    public ResponseEntity<Map<String, ModulithBridgeProperties.ModuleRouteConfig>> getRoutes() {
        return getRoutes(null);
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, ModulithBridgeProperties.ModuleRouteConfig>> getRoutes(
            @RequestHeader(value = ModulithBridgeInboundController.SECRET_HEADER, required = false) String providedSecret
    ) {
        if (!isAuthorized(providedSecret)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(properties.getModules());
    }

    public ResponseEntity<ModulithBridgeProperties.ModuleRouteConfig> updateRoute(
            String moduleName,
            RouteUpdateRequest updateRequest
    ) {
        return updateRoute(moduleName, updateRequest, null);
    }

    @PostMapping(
            value = "/{moduleName}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<ModulithBridgeProperties.ModuleRouteConfig> updateRoute(
            @PathVariable("moduleName") String moduleName,
            @RequestBody RouteUpdateRequest updateRequest,
            @RequestHeader(value = ModulithBridgeInboundController.SECRET_HEADER, required = false) String providedSecret
    ) {
        if (!isAuthorized(providedSecret)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        if (!StringUtils.hasText(moduleName)) {
            return ResponseEntity.badRequest().build();
        }

        String key = moduleName.trim().toLowerCase();
        ModulithBridgeProperties.ModuleRouteConfig config = properties.getModules()
                .computeIfAbsent(key, k -> new ModulithBridgeProperties.ModuleRouteConfig());

        synchronized (config) {
            if (updateRequest != null) {
                if (updateRequest.mode() != null) {
                    config.setMode(updateRequest.mode());
                }
                if (updateRequest.url() != null) {
                    config.setUrl(updateRequest.url());
                }
                if (updateRequest.timeoutMs() != null) {
                    config.setTimeoutMs(updateRequest.timeoutMs());
                }
                if (updateRequest.fallbackToLocal() != null) {
                    config.setFallbackToLocal(updateRequest.fallbackToLocal());
                }
                if (updateRequest.shadowSampleRate() != null) {
                    config.setShadowSampleRate(updateRequest.shadowSampleRate());
                }
            }
        }

        log.info("Updated route for module '{}': mode={}, url={}, timeoutMs={}, fallbackToLocal={}, shadowSampleRate={}",
                key, config.getMode(), config.getUrl(), config.getTimeoutMs(), config.isFallbackToLocal(), config.getShadowSampleRate());

        return ResponseEntity.ok(config);
    }

    public ModulithBridgeProperties getProperties() {
        return properties;
    }

    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }

    private boolean isAuthorized(String providedSecret) {
        if (!StringUtils.hasText(properties.getSecretToken())) {
            return true;
        }
        byte[] expected = properties.getSecretToken().getBytes(StandardCharsets.UTF_8);
        byte[] actual = providedSecret != null ? providedSecret.getBytes(StandardCharsets.UTF_8) : new byte[0];
        return providedSecret != null && MessageDigest.isEqual(expected, actual);
    }
}

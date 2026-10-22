package com.builtbyjuls.strangler.report;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.builtbyjuls.strangler.config.BridgeMode;
import com.builtbyjuls.strangler.config.ModulithBridgeProperties;

import static org.assertj.core.api.Assertions.assertThat;

class DeploymentManifestGeneratorTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Generates docker-compose.strangler.yml with postgres, worker services, and modulith-main")
    void shouldGenerateDockerComposeWithExtractedWorker() throws IOException {
        ModulithBridgeProperties properties = new ModulithBridgeProperties();
        ModulithBridgeProperties.ModuleRouteConfig route = new ModulithBridgeProperties.ModuleRouteConfig();
        route.setMode(BridgeMode.REMOTE);
        route.setUrl("http://metering-worker:8080");
        properties.getModules().put("metering", route);

        DeploymentManifestGenerator generator = new DeploymentManifestGenerator(properties);
        generator.generateManifests(tempDir);

        Path composeFile = tempDir.resolve("docker-compose.strangler.yml");
        assertThat(Files.exists(composeFile)).isTrue();

        String composeContent = Files.readString(composeFile);
        assertThat(composeContent).contains("postgres:16-alpine");
        assertThat(composeContent).contains("metering-worker");
        assertThat(composeContent).contains("--modulith.bridge.host-only=metering");
        assertThat(composeContent).contains("JAVA_TOOL_OPTIONS");
        assertThat(composeContent).contains("modulith-main");
        assertThat(composeContent).contains("http://metering-worker:8080");
    }

    @Test
    @DisplayName("Generates k8s-strangler.yaml with Deployment and Service for workers and modulith-main")
    void shouldGenerateKubernetesManifestWithWorkerAndMainServices() throws IOException {
        ModulithBridgeProperties properties = new ModulithBridgeProperties();
        ModulithBridgeProperties.ModuleRouteConfig route = new ModulithBridgeProperties.ModuleRouteConfig();
        route.setMode(BridgeMode.SHADOW);
        route.setUrl("http://metering-worker:8080");
        properties.getModules().put("metering", route);

        DeploymentManifestGenerator generator = new DeploymentManifestGenerator(properties);
        generator.generateManifests(tempDir);

        Path k8sFile = tempDir.resolve("k8s-strangler.yaml");
        assertThat(Files.exists(k8sFile)).isTrue();

        String k8sContent = Files.readString(k8sFile);
        assertThat(k8sContent).contains("kind: Deployment");
        assertThat(k8sContent).contains("kind: Service");
        assertThat(k8sContent).contains("metering-worker");
        assertThat(k8sContent).contains("--modulith.bridge.host-only=metering");
        assertThat(k8sContent).contains("replicas:");
        assertThat(k8sContent).contains("resources:");
        assertThat(k8sContent).contains("requests:");
        assertThat(k8sContent).contains("limits:");
        assertThat(k8sContent).contains("modulith-main");
    }
}

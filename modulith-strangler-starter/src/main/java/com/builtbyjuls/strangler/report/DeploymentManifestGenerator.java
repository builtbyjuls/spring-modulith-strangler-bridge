package com.builtbyjuls.strangler.report;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.builtbyjuls.strangler.config.BridgeMode;
import com.builtbyjuls.strangler.config.ModulithBridgeProperties;

/**
 * Generates single-JAR container and Kubernetes deployment manifests for extracted
 * microservice worker slices and the central monolith node.
 */
public class DeploymentManifestGenerator {

    private final ModulithBridgeProperties properties;

    public DeploymentManifestGenerator(ModulithBridgeProperties properties) {
        this.properties = properties != null ? properties : new ModulithBridgeProperties();
    }

    /**
     * Generates docker-compose.strangler.yml and k8s-strangler.yaml into the configured report directory.
     *
     * @return the destination directory path
     * @throws IOException if manifest generation fails
     */
    public Path generateManifests() throws IOException {
        String dir = properties.getReportDir() != null ? properties.getReportDir() : "target/strangler-reports";
        Path targetPath = Path.of(dir);
        generateManifests(targetPath);
        return targetPath;
    }

    /**
     * Generates docker-compose.strangler.yml and k8s-strangler.yaml into the specified directory.
     *
     * @param targetDir directory to receive generated manifests
     * @throws IOException if file writing fails
     */
    public void generateManifests(Path targetDir) throws IOException {
        Files.createDirectories(targetDir);

        Path composeFile = targetDir.resolve("docker-compose.strangler.yml");
        Path k8sFile = targetDir.resolve("k8s-strangler.yaml");

        Files.writeString(composeFile, generateDockerCompose(), StandardCharsets.UTF_8);
        Files.writeString(k8sFile, generateKubernetesManifest(), StandardCharsets.UTF_8);
    }

    /**
     * Generates a multi-service Docker Compose specification wiring extracted worker slices.
     */
    public String generateDockerCompose() {
        List<String> extractedModules = getExtractedModules();
        StringBuilder sb = new StringBuilder();

        sb.append("services:\n");
        sb.append("  postgres:\n");
        sb.append("    image: postgres:16-alpine\n");
        sb.append("    container_name: postgres\n");
        sb.append("    environment:\n");
        sb.append("      POSTGRES_DB: grid_settlement\n");
        sb.append("      POSTGRES_USER: postgres\n");
        sb.append("      POSTGRES_PASSWORD: postgres\n");
        sb.append("    ports:\n");
        sb.append("      - \"5432:5432\"\n");
        sb.append("    healthcheck:\n");
        sb.append("      test: [\"CMD-SHELL\", \"pg_isready -U postgres\"]\n");
        sb.append("      interval: 5s\n");
        sb.append("      timeout: 5s\n");
        sb.append("      retries: 5\n\n");

        int workerPortOffset = 8081;
        for (String module : extractedModules) {
            sb.append("  ").append(module).append("-worker:\n");
            sb.append("    image: modulith-app:latest\n");
            sb.append("    container_name: ").append(module).append("-worker\n");
            sb.append("    command: [\"--modulith.bridge.host-only=").append(module).append("\"]\n");
            sb.append("    environment:\n");
            sb.append("      JAVA_TOOL_OPTIONS: \"-XX:+UseZGC -XX:+ExitOnOutOfMemoryError\"\n");
            sb.append("      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/grid_settlement\n");
            sb.append("      SPRING_DATASOURCE_USERNAME: postgres\n");
            sb.append("      SPRING_DATASOURCE_PASSWORD: postgres\n");
            sb.append("      SPRING_THREADS_VIRTUAL_ENABLED: \"true\"\n");
            sb.append("    ports:\n");
            sb.append("      - \"").append(workerPortOffset++).append(":8080\"\n");
            sb.append("    depends_on:\n");
            sb.append("      postgres:\n");
            sb.append("        condition: service_healthy\n\n");
        }

        sb.append("  modulith-main:\n");
        sb.append("    image: modulith-app:latest\n");
        sb.append("    container_name: modulith-main\n");
        sb.append("    environment:\n");
        sb.append("      JAVA_TOOL_OPTIONS: \"-XX:+UseZGC -XX:+ExitOnOutOfMemoryError\"\n");
        sb.append("      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/grid_settlement\n");
        sb.append("      SPRING_DATASOURCE_USERNAME: postgres\n");
        sb.append("      SPRING_DATASOURCE_PASSWORD: postgres\n");
        sb.append("      SPRING_THREADS_VIRTUAL_ENABLED: \"true\"\n");

        if (properties.getModules() != null) {
            for (Map.Entry<String, ModulithBridgeProperties.ModuleRouteConfig> entry : properties.getModules().entrySet()) {
                String mod = entry.getKey();
                ModulithBridgeProperties.ModuleRouteConfig cfg = entry.getValue();
                String envPrefix = "      MODULITH_BRIDGE_MODULES_" + mod.toUpperCase(Locale.ROOT).replace('-', '_');
                sb.append(envPrefix).append("_MODE: ").append(cfg.getMode() != null ? cfg.getMode().name() : "LOCAL").append("\n");
                String workerUrl = "http://" + mod + "-worker:8080";
                sb.append(envPrefix).append("_URL: ").append(workerUrl).append("\n");
            }
        }

        sb.append("    ports:\n");
        sb.append("      - \"8080:8080\"\n");
        sb.append("    depends_on:\n");
        sb.append("      postgres:\n");
        sb.append("        condition: service_healthy\n");

        for (String module : extractedModules) {
            sb.append("      ").append(module).append("-worker:\n");
            sb.append("        condition: service_started\n");
        }

        return sb.toString();
    }

    /**
     * Generates Kubernetes Deployment and Service manifests for workers and the main node.
     */
    public String generateKubernetesManifest() {
        List<String> extractedModules = getExtractedModules();
        StringBuilder sb = new StringBuilder();

        for (String module : extractedModules) {
            sb.append("apiVersion: apps/v1\n");
            sb.append("kind: Deployment\n");
            sb.append("metadata:\n");
            sb.append("  name: ").append(module).append("-worker\n");
            sb.append("  labels:\n");
            sb.append("    app: ").append(module).append("-worker\n");
            sb.append("    tier: worker\n");
            sb.append("spec:\n");
            sb.append("  replicas: 3\n");
            sb.append("  selector:\n");
            sb.append("    matchLabels:\n");
            sb.append("      app: ").append(module).append("-worker\n");
            sb.append("  template:\n");
            sb.append("    metadata:\n");
            sb.append("      labels:\n");
            sb.append("        app: ").append(module).append("-worker\n");
            sb.append("        tier: worker\n");
            sb.append("    spec:\n");
            sb.append("      containers:\n");
            sb.append("        - name: ").append(module).append("-worker\n");
            sb.append("          image: modulith-app:latest\n");
            sb.append("          args: [\"--modulith.bridge.host-only=").append(module).append("\"]\n");
            sb.append("          ports:\n");
            sb.append("            - containerPort: 8080\n");
            sb.append("          resources:\n");
            sb.append("            requests:\n");
            sb.append("              cpu: 500m\n");
            sb.append("              memory: 512Mi\n");
            sb.append("            limits:\n");
            sb.append("              cpu: 1000m\n");
            sb.append("              memory: 1Gi\n");
            sb.append("---\n");
            sb.append("apiVersion: v1\n");
            sb.append("kind: Service\n");
            sb.append("metadata:\n");
            sb.append("  name: ").append(module).append("-worker\n");
            sb.append("  labels:\n");
            sb.append("    app: ").append(module).append("-worker\n");
            sb.append("spec:\n");
            sb.append("  type: ClusterIP\n");
            sb.append("  ports:\n");
            sb.append("    - port: 8080\n");
            sb.append("      targetPort: 8080\n");
            sb.append("  selector:\n");
            sb.append("    app: ").append(module).append("-worker\n");
            sb.append("---\n");
        }

        // modulith-main Deployment & Service
        sb.append("apiVersion: apps/v1\n");
        sb.append("kind: Deployment\n");
        sb.append("metadata:\n");
        sb.append("  name: modulith-main\n");
        sb.append("  labels:\n");
        sb.append("    app: modulith-main\n");
        sb.append("    tier: api\n");
        sb.append("spec:\n");
        sb.append("  replicas: 2\n");
        sb.append("  selector:\n");
        sb.append("    matchLabels:\n");
        sb.append("      app: modulith-main\n");
        sb.append("  template:\n");
        sb.append("    metadata:\n");
        sb.append("      labels:\n");
        sb.append("        app: modulith-main\n");
        sb.append("        tier: api\n");
        sb.append("    spec:\n");
        sb.append("      containers:\n");
        sb.append("        - name: modulith-main\n");
        sb.append("          image: modulith-app:latest\n");
        sb.append("          ports:\n");
        sb.append("            - containerPort: 8080\n");
        sb.append("          env:\n");

        if (properties.getModules() != null) {
            for (Map.Entry<String, ModulithBridgeProperties.ModuleRouteConfig> entry : properties.getModules().entrySet()) {
                String mod = entry.getKey();
                ModulithBridgeProperties.ModuleRouteConfig cfg = entry.getValue();
                String envPrefix = "MODULITH_BRIDGE_MODULES_" + mod.toUpperCase(Locale.ROOT).replace('-', '_');
                sb.append("            - name: ").append(envPrefix).append("_MODE\n");
                sb.append("              value: \"").append(cfg.getMode() != null ? cfg.getMode().name() : "LOCAL").append("\"\n");
                sb.append("            - name: ").append(envPrefix).append("_URL\n");
                sb.append("              value: \"http://").append(mod).append("-worker:8080\"\n");
            }
        }

        sb.append("          resources:\n");
        sb.append("            requests:\n");
        sb.append("              cpu: 1000m\n");
        sb.append("              memory: 1Gi\n");
        sb.append("            limits:\n");
        sb.append("              cpu: 2000m\n");
        sb.append("              memory: 2Gi\n");
        sb.append("---\n");
        sb.append("apiVersion: v1\n");
        sb.append("kind: Service\n");
        sb.append("metadata:\n");
        sb.append("  name: modulith-main\n");
        sb.append("  labels:\n");
        sb.append("    app: modulith-main\n");
        sb.append("spec:\n");
        sb.append("  type: ClusterIP\n");
        sb.append("  ports:\n");
        sb.append("    - port: 8080\n");
        sb.append("      targetPort: 8080\n");
        sb.append("  selector:\n");
        sb.append("    app: modulith-main\n");

        return sb.toString();
    }

    private List<String> getExtractedModules() {
        List<String> extracted = new ArrayList<>();
        if (properties.getModules() != null) {
            for (Map.Entry<String, ModulithBridgeProperties.ModuleRouteConfig> entry : properties.getModules().entrySet()) {
                BridgeMode mode = entry.getValue() != null ? entry.getValue().getMode() : null;
                if (mode == BridgeMode.SHADOW || mode == BridgeMode.REMOTE) {
                    extracted.add(entry.getKey());
                }
            }
        }
        return extracted;
    }
}

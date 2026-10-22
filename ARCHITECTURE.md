# Architecture & Technical Design

**Target Stack**: **Java 21**, **Spring Boot 4.1.x**, **Spring Modulith 2.1.1**, **PostgreSQL 16** (Testcontainers), **JUnit 5**, **Hibernate ORM 7.x**.

---

## 1. Overview & Design Goals

### 1.1 Problem

**Spring Modulith** enforces clean package boundaries inside a monolithic application (`@ApplicationModule`, `@NamedInterface`), and `@Externalized` forwards asynchronous domain events to message brokers. However, when a CPU-intensive or memory-heavy module must be extracted into an independent service under load, two critical gaps remain:

* **No Synchronous Local-to-Remote Bridge**: Extracting a module that exposes synchronous `@NamedInterface` APIs requires manually writing REST/gRPC controllers, client stubs (`@HttpExchange` or Feign), and DTO adapters across the codebase.
* **No Production Runtime Module Slicing**: Spring Modulith only supports slicing modules inside JUnit tests (`@ApplicationModuleTest`). Booting the same production JAR as a dedicated worker node still instantiates every other module's beans, repositories, and background tasks, wasting heap memory and database connections.

### 1.2 How It Works

This project provides a reusable Spring Boot starter (**`modulith-strangler-starter`**) and demonstrates it on a wholesale electricity settlement application (**`grid-settlement-modulith-demo`**).

1. **Slice**: When a JVM starts with `modulith.bridge.host-only=<module>`, a `BeanDefinitionRegistryPostProcessor` removes bean definitions belonging to non-hosted modules before singleton instantiation.
2. **Expose**: The extracted worker node automatically registers an inbound HTTP bridge endpoint (`/internal/modulith-bridge/invoke`) for its `@NamedInterface` beans.
3. **Proxy**: On the main monolith node, a `BeanPostProcessor` wraps `@NamedInterface` beans in a dynamic router controlled by `modulith.bridge.modules.<module>.mode`:
   * **`LOCAL`**: Direct in-JVM method call with zero serialization overhead.
   * **`REMOTE`**: Routes method calls over HTTP/JSON via Spring `RestClient` to the extracted worker node, with optional automatic fallback to `LOCAL`.
   * **`SHADOW`**: Executes `LOCAL` as the authoritative response while invoking `REMOTE` in parallel on a Java 21 Virtual Thread to verify calculation parity before cutover.

### 1.3 System Boundaries

* **Zero Domain Lock-In**: Domain modules program strictly to standard Java interfaces and Spring Modulith `@NamedInterface` boundaries without RPC-specific annotations.
* **Deterministic Numerical Parity**: Financial and energy telemetry comparisons normalize `BigDecimal` scale (`10.00` equals `10.000000`) so serialization formatting never triggers false parity mismatches.

### 1.4 Performance & Reliability Targets

* **`LOCAL` Mode Overhead**: Near-zero overhead (single enum branch check prior to direct Java method invocation).
* **Worker Node Isolation**: `host-only` worker nodes instantiate zero service or repository beans from non-hosted application modules.
* **Safe Strangler Fallback**: Remote network timeouts or HTTP 5xx errors in `SHADOW` mode (or `REMOTE` mode with `fallback-to-local=true`) never fail caller transactions.

---

## 2. Module Structure

```text
spring-modulith-strangler-bridge/
|-- pom.xml                                 # Parent BOM (Java 21, Spring Boot 4.1.x, Spring Modulith 2.1.1)
|-- Dockerfile                              # Multi-stage build for single-JAR runtime container
|-- docker-compose.yml                      # Reference stack: postgres, metering-worker, modulith-main
|-- k8s/manifests.yaml                      # Reference Kubernetes Deployment & Service manifests
|-- .github/workflows/ci.yml                # CI build, step summary publishing, and artifact upload
|-- modulith-strangler-starter/             # Reusable Strangler Bridge Starter Module
|   |-- src/main/java/.../annotation/       # @EnableModulithBridge, @ModulithBridgeTarget
|   |-- src/main/java/.../config/           # ModulithBridgeAutoConfiguration, ModulithBridgeProperties
|   |-- src/main/java/.../slice/            # ModuleLayoutInspector, ModuleSliceRegistryPostProcessor
|   |-- src/main/java/.../proxy/            # ModulithBridgeBeanPostProcessor, ModulithBridgeInvocationHandler
|   |-- src/main/java/.../transport/        # BridgeHttpClient, ModulithBridgeInboundController
|   |-- src/main/java/.../parity/           # ShadowParityVerifier, ShadowParityResult
|   \-- src/main/java/.../report/           # StranglerReportExporter, DeploymentManifestGenerator
\-- grid-settlement-modulith-demo/          # Wholesale Energy Settlement Modulith Demo App
    |-- src/main/java/.../contract/         # Module 1: Bilateral Contracts & Declarations (ContractApi)
    |-- src/main/java/.../metering/         # Module 2: 288-Interval Telemetry Aggregation (MeteringApi)
    |-- src/main/java/.../settlement/       # Module 3: Daily Imbalance & Financial Settlement
    \-- src/test/java/.../                  # Testcontainers Integration Tests (PostgreSQL 16)
```

---

## 3. Core Engine (`modulith-strangler-starter`)

### 3.1 Runtime Module Slicing (`ModuleSliceRegistryPostProcessor`)

To allow the exact same JAR artifact to run either as the full monolith or as a stripped single-module microservice:

* **Pre-Instantiation Pruning**: `ModuleSliceRegistryPostProcessor` implements `BeanDefinitionRegistryPostProcessor` and `EnvironmentAware`. It inspects `modulith.bridge.host-only` (e.g., `metering`) and `modulith.bridge.base-package` (e.g., `com.builtbyjuls.grid`).
* **Package Boundary Matching**: Every application module maps to `<basePackage>.<moduleName>..*`. When `host-only=metering` is configured, bean definitions whose bean class or factory method resides in another module sub-package (`contract`, `settlement`) are removed from the `BeanDefinitionRegistry` before Spring creates singletons.

### 3.2 Dynamic Proxy Routing (`ModulithBridgeBeanPostProcessor`)

* **Interface Detection**: Wraps Spring beans that implement a `@NamedInterface` or `@ModulithBridgeTarget` interface in a JDK Dynamic Proxy.
* **Mode Dispatch (`ModulithBridgeInvocationHandler`)**:
  * **`LOCAL`**: Invokes `method.invoke(localTarget, args)` directly.
  * **`REMOTE`**: Dispatches a `BridgeInvocationRequest` (containing `moduleName`, `interfaceName`, `methodName`, `parameterTypes`, and serialized JSON arguments) via `BridgeHttpClient`. If `fallbackToLocal` is enabled and the remote call throws a transport exception, the handler logs the failure and invokes `localTarget`.
  * **`SHADOW`**: Executes `localTarget` synchronously to return the authoritative result, dispatches the remote request on a Virtual Thread (or synchronously when `shadowAwait=true` in deterministic tests), and passes both outputs to `ShadowParityVerifier`.

### 3.3 Inbound Bridge Adapter (`ModulithBridgeInboundController`)

* **Reflective Method Resolution**: Exposes `POST /internal/modulith-bridge/invoke`. Resolves the target Spring bean by interface class and invokes the exact overloaded method matching `parameterTypes`.
* **Typed Argument Deserialization**: Deserializes each JSON argument node against the target method's generic `Type` (`Method.getGenericParameterTypes()`) using Jackson `ObjectMapper` so collections, records, `LocalDate`, and `BigDecimal` values reconstruct accurately.

### 3.4 Shadow Parity Verification (`ShadowParityVerifier`)

* **Scale-Normalized Comparison**: Converts both `LOCAL` and `REMOTE` return values into Jackson `JsonNode` trees and compares them recursively. Numeric nodes are compared via `BigDecimal.compareTo()` so scale differences (`1250.50` vs `1250.500000`) evaluate as equal.
* **Audit Recording**: Stores `ShadowParityResult` entries in a thread-safe registry (`matched`, `localDurationMs`, `remoteDurationMs`, `diffSummary`) for verification in integration tests.

### 3.5 CI/CD Parity Reporting (`StranglerReportExporter`)

* **Structured JSON (`report.json`)**: Emits `generatedAt` timestamp, active `hostOnly` slice, invocation totals, match and mismatch counts, fallback counts, calculated parity rate, configured module routing maps, and a complete audit log of `ShadowParityResult` records.
* **GitHub Actions Summary (`summary.md`)**: Renders markdown tables formatted for `$GITHUB_STEP_SUMMARY`, surfacing headline metrics, routing topologies, per-method latency in milliseconds, and divergence diffs.
* **Shutdown Hook**: Listens on `ContextClosedEvent` (and implements `DisposableBean`) to guarantee export on process shutdown when `exportReports=true` and parity events exist.

### 3.6 Deployment Manifest Generation (`DeploymentManifestGenerator`)

* **Single-JAR Architecture**: Both the main orchestrator and extracted workers run the identical JAR binary and container image. Worker processes specify `--modulith.bridge.host-only=<module>` on startup to prune other modules before initialization.
* **Docker Compose (`docker-compose.strangler.yml`)**: Dynamically configures PostgreSQL 16 healthchecks, extracted worker containers with JVM virtual thread flags, and the main monolith container wired with worker HTTP endpoints.
* **Kubernetes Orchestration (`k8s-strangler.yaml`)**: Generates independent `Deployment` and `Service` resources for workers and monolith instances, assigning custom replica counts and resource requests/limits.

---

## 4. Reference Domain (`grid-settlement-modulith-demo`)

### 4.1 Domain Model & Modulith Boundaries

Wholesale electricity markets settle energy across **288 five-minute dispatch intervals** per 24-hour trading day:

1. **`contract` Module (`com.builtbyjuls.grid.contract`)**:
   * **Public API (`@NamedInterface`)**: `ContractApi` (`findConfirmedDeclarations(LocalDate)`), `ContractDeclarationDto`.
   * **Internal**: `MarketParticipant`, `BilateralContract`, `ContractDeclaration`, `IntervalQuantity`, `ContractModuleService`.
2. **`metering` Module (`com.builtbyjuls.grid.metering`)**:
   * **Public API (`@NamedInterface`)**: `MeteringApi` (`aggregateDailyMeterVolumes(LocalDate)`), `DailyMeterVolumeDto`.
   * **Internal**: `MeterReadingHeader`, `MeterIntervalReading` (288 five-minute telemetry intervals per meter), `MeteringAggregationService`.
3. **`settlement` Module (`com.builtbyjuls.grid.settlement`)**:
   * **Entry Point**: `GridDailySettlementService.settleTradingDay(LocalDate)` orchestrates `ContractApi` and `MeteringApi` to compute daily MWh imbalance and settlement positions per contract without touching internal repositories of `contract` or `metering`.

### 4.2 Verification Scenarios

* **Scenario A (`LOCAL` Monolith Mode)**:
  * Runs `GridDailySettlementService.settleTradingDay(LocalDate)` with all modules in `LOCAL` mode inside a single JVM and verifies zero HTTP bridge traffic.
* **Scenario B (`REMOTE` Extracted Worker + Runtime Slicing)**:
  * Boots a second Spring Boot context from the same codebase with `--modulith.bridge.host-only=metering` on a local port, verifies that `settlement` and `contract` beans are absent from the worker context, and routes `MeteringApi` calls from the main context over HTTP in `REMOTE` mode.
* **Scenario C (`SHADOW` Parity & Local Fallback)**:
  * Runs `metering` in `SHADOW` mode to verify 100% parity across 5,760 five-minute interval readings, verifies mismatch detection when remote data drifts, and verifies automatic local fallback when the remote worker is unreachable.

---

## 5. Key Engineering Trade-offs

| Decision Area | Chosen Approach | Rejected Alternative | Why We Chose It & Trade-off |
| --- | --- | --- | --- |
| **Runtime Slicing** | **`BeanDefinitionRegistryPostProcessor`** (`host-only`) | Separate Maven modules per microservice | **Why**: Preserves a single codebase, single JAR build, and single Docker image while stripping non-hosted module beans at startup.<br><br>**Trade-off**: Classes from other modules remain on the JAR classpath on disk even though they are never instantiated in heap memory. |
| **Proxy Mechanism** | **JDK Dynamic Proxy** over `@NamedInterface` | Bytecode weaving (AspectJ / ByteBuddy) | **Why**: Aligns with Spring Modulith's interface-based module API design and works cleanly with Spring AOT.<br><br>**Trade-off**: Requires cross-module synchronous calls to go through a Java interface. |
| **Transport Protocol** | **Spring `RestClient` + Jackson JSON** | Mandatory gRPC / `.proto` codegen | **Why**: Zero build-plugin or schema-compilation friction for Spring Boot developers.<br><br>**Trade-off**: Higher wire payload size than binary Protobuf. |
| **Parity Comparison** | **Recursive `JsonNode` + `BigDecimal.compareTo()`** | `Objects.equals()` or raw JSON string comparison | **Why**: Avoids false mismatches caused by JSON key ordering or `BigDecimal` trailing zeros (`10.0` vs `10.000000`).<br><br>**Trade-off**: Incurs JSON tree conversion cost during `SHADOW` mode. |

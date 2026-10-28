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

### 3.1 Runtime Module Slicing (`ModuleSliceRegistryPostProcessor` & `ModuleSliceEntityPostProcessor`)

To allow the exact same JAR artifact to run either as the full monolith or as a stripped single-module microservice:

* **Pre-Instantiation Bean Pruning**: `ModuleSliceRegistryPostProcessor` implements `BeanDefinitionRegistryPostProcessor` and `EnvironmentAware`. It inspects `modulith.bridge.host-only` (e.g., `metering`) and `modulith.bridge.base-package` (e.g., `com.builtbyjuls.grid`). When `host-only=metering` is configured, bean definitions whose bean class or factory method resides in another module sub-package (`contract`, `settlement`) are removed from the `BeanDefinitionRegistry` before Spring creates singletons.
* **Hibernate Entity Slicing**: `ModuleSliceEntityPostProcessor` intercepts `LocalContainerEntityManagerFactoryBean` before initialization and attaches a `PersistenceUnitPostProcessor`. In `host-only` mode, it removes managed entity class names from `PersistenceUnitInfo.getManagedClassNames()` belonging to non-hosted modules, ensuring Hibernate builds metamodels only for the hosted slice. On the main node, internal entities of `REMOTE` modules with `fallback-to-local=false` are likewise pruned.

### 3.2 Dynamic Proxy Routing (`ModulithBridgeBeanPostProcessor`)

* **Interface Detection**: Wraps Spring beans that implement a `@NamedInterface` or `@ModulithBridgeTarget` interface in a JDK Dynamic Proxy.
* **Mode Dispatch (`ModulithBridgeInvocationHandler`)**:
  * **`LOCAL`**: Invokes `method.invoke(localTarget, args)` directly.
  * **`REMOTE`**: Dispatches a `BridgeInvocationRequest` (containing `moduleName`, `interfaceName`, `methodName`, `parameterTypes`, and serialized JSON arguments) via `BridgeHttpClient`. If `fallbackToLocal` is enabled and the remote call throws a transport exception, the handler logs the failure and invokes `localTarget`.
  * **`SHADOW`**: Executes `localTarget` synchronously to return the authoritative result, dispatches the remote request on a Virtual Thread (or synchronously when `shadowAwait=true` in deterministic tests), and passes both outputs to `ShadowParityVerifier`.
* **Smart Error Classification**: When a remote bean throws a domain or validation exception (`InvocationTargetException`), the inbound controller flags the response as a `businessError`. `BridgeHttpClient` raises `BridgeBusinessException`, which `ModulithBridgeInvocationHandler` catches and immediately rethrows to the caller without triggering local fallback or recording fallback metrics. Local fallback is strictly reserved for infrastructure and transport failures (timeouts, connection refused, HTTP 5xx).

### 3.3 Inbound Bridge Adapter (`ModulithBridgeInboundController`)

* **Reflective Method Resolution**: Exposes `POST /internal/modulith-bridge/invoke`. Resolves the target Spring bean by interface class and invokes the exact overloaded method matching `parameterTypes`.
* **Typed Argument Deserialization**: Deserializes each JSON argument node against the target method's generic `Type` (`Method.getGenericParameterTypes()`) using Jackson `ObjectMapper` so collections, records, `LocalDate`, and `BigDecimal` values reconstruct accurately.
* **Interface Whitelisting**: Verifies that the requested target is an interface annotated with `@NamedInterface` or `@ModulithBridgeTarget`. Invocations targeting unannotated interfaces or concrete classes are rejected with `HTTP 403 Forbidden`.
* **Pre-Shared Secret Authentication**: When `modulith.bridge.secret-token` is configured, inbound invocations require a matching `X-Modulith-Bridge-Secret` header verified via constant-time comparison (`MessageDigest.isEqual`). Missing or invalid tokens return `HTTP 401 Unauthorized`. `BridgeHttpClient` automatically attaches this header to all outbound bridge requests.

### 3.4 Shadow Parity Verification (`ShadowParityVerifier`)

* **Scale-Normalized Comparison**: Converts both `LOCAL` and `REMOTE` return values into Jackson `JsonNode` trees and compares them recursively. Numeric nodes are compared via `BigDecimal.compareTo()` so scale differences (`1250.50` vs `1250.500000`) evaluate as equal.
* **Audit Recording**: Stores `ShadowParityResult` entries in a thread-safe registry (`matched`, `localDurationMs`, `remoteDurationMs`, `diffSummary`) for verification in integration tests.

### 3.5 CI-Agnostic Parity Reporting & Quality Gate (`StranglerReportExporter`)

Reporting is designed to be completely CI-platform agnostic, emitting native artifacts for every major pipeline system:

* **Structured JSON (`report.json`)**: Emits `generatedAt` timestamp, active `hostOnly` slice, invocation totals, match and mismatch counts, fallback counts, calculated parity rate, configured module routing maps, and a complete audit log of `ShadowParityResult` records for `jq` CLI quality gates and observability ingestion.
* **JUnit XML (`junit-parity.xml`)**: Exports standard `<testsuite>` and `<testcase>` records with `<failure type="ShadowParityMismatch">` for mismatches, natively parsed by GitLab CI (`artifacts:reports:junit`), Jenkins (`junit`), Azure DevOps, CircleCI, and Bitbucket Pipelines without custom plugins.
* **Summary Markdown (`summary.md`)**: Renders markdown tables formatted for GitHub Actions (`$GITHUB_STEP_SUMMARY`), GitLab merge request notes, or Azure DevOps release summaries.
* **Console ASCII Summary & Parity Gate**: Formats a screenshot-ready ASCII banner directly to standard console output (`modulith.bridge.log-console-summary=true`). When `modulith.bridge.fail-on-mismatch=true` (or via `verifyParityGate()`), halts execution with `ShadowParityMismatchException` to prevent deploying regressions.
* **Shutdown Hook**: Listens on `ContextClosedEvent` (and implements `DisposableBean`) to guarantee export on process shutdown when `exportReports=true` and parity events exist.

### 3.6 Deployment Manifest Generation (`DeploymentManifestGenerator`)

* **Single-JAR Architecture**: Both the main orchestrator and extracted workers run the identical JAR binary and container image. Worker processes specify `--modulith.bridge.host-only=<module>` on startup to prune other modules before initialization.
* **Docker Compose (`docker-compose.strangler.yml`)**: Dynamically configures PostgreSQL 16 healthchecks, extracted worker containers with JVM virtual thread flags, and the main monolith container wired with worker HTTP endpoints.
* **Kubernetes Orchestration (`k8s-strangler.yaml`)**: Generates independent `Deployment` and `Service` resources for workers and monolith instances, assigning custom replica counts and resource requests/limits.

### 3.7 Startup Contract Validation (`ModuleContractValidator`)

* **Startup Safety Check**: Enabled by default (`modulith.bridge.validate-contracts=true`), `ModuleContractValidator` executes during application startup as a `SmartInitializingSingleton`, inspecting all `@NamedInterface` and `@ModulithBridgeTarget` interfaces under `basePackage`.
* **Leaked JPA Entity Rejection**: Recursively inspects method parameters and generic return types (unwrapping `Optional<T>`, `Collection<T>`, `Map<K, V>`, and arrays `T[]`). Rejects any type annotated with `@jakarta.persistence.Entity` or `@javax.persistence.Entity` with `InvalidModuleContractException` to prevent remote serialization failures, lazy-loading breaks, and database coupling across nodes.
* **Non-Serializable Stream Rejection**: Rejects non-serializable streaming and servlet types (`InputStream`, `OutputStream`, `Reader`, `Writer`, `Stream`, `ServletRequest`, `ServletResponse`).

### 3.8 Shadow Transaction Rollback & Traffic Sampling

* **Dry-Run Writes (`X-Modulith-Shadow: true`)**: When an interface method is invoked in `SHADOW` mode, `BridgeHttpClient` attaches the HTTP header `X-Modulith-Shadow: true` to the outbound shadow dispatch. On the extracted worker node, `ModulithBridgeInboundController` detects this header:
  * When `modulith.bridge.shadow-rollback-writes=true` (default `true`) and a `PlatformTransactionManager` bean exists, it executes the target bean method within a `TransactionTemplate` configured with `TransactionDefinition.PROPAGATION_REQUIRES_NEW`.
  * If an `EntityManager` bean is present, it explicitly triggers `em.flush()` to ensure SQL constraints, triggers, and foreign keys are validated against the database.
  * In a `finally` block, it calls `status.setRollbackOnly()`, guaranteeing that all remote database mutations are discarded while returning the computed DTO response (or propagating business exceptions) for parity verification.
* **Traffic Sampling (`shadow-sample-rate`)**: Controls the proportion of calls that trigger shadow verification via `modulith.bridge.shadow-sample-rate` (`0.0` to `1.0`, default `1.0`). Individual modules can override this value via `modulith.bridge.modules.<module>.shadow-sample-rate` to throttle high-throughput production paths without modifying code.

### 3.9 Zero-Restart Live Mode Switching (`ModulithBridgeAdminController`)

* **Runtime Route Reconfiguration**: Exposes `GET` and `POST /internal/modulith-bridge/routes/{moduleName}` to inspect and modify module routing topology (`BridgeMode.LOCAL`, `SHADOW`, `REMOTE`), endpoints, timeout values, local fallback policies, and shadow sample rates in memory.
* **Thread-Safe Instant Cutover**: Module configurations are maintained in a `ConcurrentHashMap` with `volatile` fields. Existing JDK dynamic proxies inspect the updated configuration on their next invocation, allowing instantaneous zero-downtime cutover (`LOCAL -> SHADOW -> REMOTE`) without restarting JVM containers.
* **Administrative Authentication**: Requires `X-Modulith-Bridge-Secret` matching `modulith.bridge.secret-token` via constant-time comparison when authentication is enabled.

### 3.10 Chatty RPC Loop Detection (`BridgeCallBudgetGuard`)

* **N+1 RPC Anti-Pattern Detection**: Inside a modular monolith, executing `@NamedInterface` methods within loops (`for (var c : contracts) meteringApi.getVolume(c.id())`) takes sub-millisecond in-memory time, masking latency issues. Once extracted to `REMOTE`, this results in catastrophic sequential HTTP round-trips.
* **Sliding-Window Call Budget**: `BridgeCallBudgetGuard` maintains per-thread invocation history within a sliding window (`modulith.bridge.call-window-ms`, default `250ms`). If calls to any `module#method` exceed `modulith.bridge.max-calls-per-window` (default `10`), the guard increments a violation counter.
* **Early Defect Prevention**: When `modulith.bridge.fail-on-chatty-boundary=true`, the guard throws `ChattyModuleBoundaryException` directly to the caller, highlighting the exact module, interface, method, and call count while advising refactoring to batch operations (`Collection<ID>`) or Spring Modulith `@Externalized` async events.

### 3.11 Fast-Fail Circuit Breaker (`BridgeCircuitBreaker`)

* **Cascading Latency Elimination**: Without circuit breaking, when a remote worker node is unreachable, every single outbound invocation blocks for the full transport timeout (e.g., `5,000ms`) before falling back to `LOCAL`, quickly exhausting caller worker threads.
* **Lock-Free State Machine**: `BridgeCircuitBreaker` manages module health across `CLOSED -> OPEN -> HALF_OPEN` states using atomic primitives:
  * **`CLOSED`**: All invocations proceed to the remote endpoint.
  * **`OPEN`**: Tripped when consecutive transport failures reach `modulith.bridge.circuit-failure-threshold` (default `3`). Calls bypass the network entirely, routing directly to `LOCAL` fallback in `0ms` (recording `"Circuit breaker OPEN"` in `ShadowParityRegistry`) or throwing `BridgeRemoteInvocationException` if fallback is disabled.
  * **`HALF_OPEN`**: After `modulith.bridge.circuit-reset-timeout-ms` (default `30,000ms`), the circuit permits a single probe request. Success resets failure counters and restores `CLOSED` state; failure immediately trips the circuit back to `OPEN`.
* **Business Exception Exemption**: Legitimate domain exceptions (`BridgeBusinessException`) do not count toward transport failure thresholds, ensuring valid client validation errors never trip the circuit breaker.

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
| **Error Fallback** | **Smart Error Classification** (`BridgeBusinessException`) | Blanket fallback to local on any error | **Why**: Prevents re-executing invalid domain mutations locally when remote validation legitimately fails.<br><br>**Trade-off**: Requires inbound controller and client to distinguish business exceptions from transport faults. |
| **Bridge Security** | **Interface Whitelisting & Pre-Shared Secret** | Open reflective dispatch | **Why**: Protects inbound worker endpoints from unauthorized callers and arbitrary bean invocation.<br><br>**Trade-off**: Shared secret must be distributed to communicating services via environment or secret manager. |
| **Shadow Writes** | **Rollback-Only Transaction (`PROPAGATION_REQUIRES_NEW`)** | Restricting shadow mode to read-only APIs | **Why**: Enables real-world shadow parity verification for write operations by testing inserts/updates and flushing SQL constraints without persisting changes.<br><br>**Trade-off**: Requires target method to execute in an isolated transaction on the worker node. |
| **Mode Switching** | **In-Memory Admin REST Controller (`ConcurrentHashMap`)** | External config servers (Spring Cloud Config, Consul) | **Why**: Provides zero-restart runtime cutover with zero external infrastructure dependencies.<br><br>**Trade-off**: Multi-replica deployments require triggering the admin endpoint across each instance or integrating with an ingress orchestrator. |
| **Loop Detection** | **Per-Thread Sliding Window Guard** (`BridgeCallBudgetGuard`) | Centralized Redis token bucket or rate limiter | **Why**: Detects per-thread caller loop anti-patterns in-memory with zero network overhead or external infrastructure.<br><br>**Trade-off**: Scoped per thread rather than aggregating across multiple concurrent request threads. |
| **Circuit Breaking** | **In-Memory Atomic State Machine** (`BridgeCircuitBreaker`) | Heavy external resilience frameworks (Resilience4j, Envoy) | **Why**: Zero external dependencies and instantaneous 0ms local fallback directly integrated into dynamic proxy invocation handler.<br><br>**Trade-off**: Circuit state is local to each JVM process rather than shared across clustered nodes. |

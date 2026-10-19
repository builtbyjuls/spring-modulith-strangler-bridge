package com.builtbyjuls.strangler.slice;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.NamedInterface;

import com.builtbyjuls.strangler.annotation.ModulithBridgeTarget;

class ModuleLayoutInspectorTest {

    private ModuleLayoutInspector inspector;
    private static final String BASE_PACKAGE = "com.builtbyjuls.grid";

    @BeforeEach
    void setUp() {
        inspector = new ModuleLayoutInspector();
    }

    @Test
    @DisplayName("Should extract module name from sub-package relative to base package")
    void shouldExtractModuleNameFromClass() {
        Optional<String> meteringModule = inspector.resolveModuleName(
                "com.builtbyjuls.grid.metering.internal.MeteringAggregationService", BASE_PACKAGE);
        assertThat(meteringModule).contains("metering");

        Optional<String> contractModule = inspector.resolveModuleName(
                "com.builtbyjuls.grid.contract.ContractApi", BASE_PACKAGE);
        assertThat(contractModule).contains("contract");

        Optional<String> settlementModule = inspector.resolveModuleName(
                "com.builtbyjuls.grid.settlement.GridDailySettlementService", BASE_PACKAGE);
        assertThat(settlementModule).contains("settlement");
    }

    @Test
    @DisplayName("Should handle trailing dot in base package gracefully")
    void shouldHandleTrailingDotInBasePackage() {
        Optional<String> module = inspector.resolveModuleName(
                "com.builtbyjuls.grid.metering.MeteringService", "com.builtbyjuls.grid.");
        assertThat(module).contains("metering");
    }

    @Test
    @DisplayName("Should return empty for classes directly in root base package")
    void shouldReturnEmptyForRootClass() {
        Optional<String> rootModule = inspector.resolveModuleName(
                "com.builtbyjuls.grid.GridSettlementModulithApplication", BASE_PACKAGE);
        assertThat(rootModule).isEmpty();
    }

    @Test
    @DisplayName("Should return empty for foreign classes outside base package")
    void shouldReturnEmptyForForeignClass() {
        Optional<String> springModule = inspector.resolveModuleName(
                "org.springframework.boot.SpringApplication", BASE_PACKAGE);
        assertThat(springModule).isEmpty();

        Optional<String> starterModule = inspector.resolveModuleName(
                "com.builtbyjuls.strangler.slice.ModuleLayoutInspector", BASE_PACKAGE);
        assertThat(starterModule).isEmpty();
    }

    @Test
    @DisplayName("Should return empty when class name or base package is null or blank")
    void shouldReturnEmptyForInvalidInputs() {
        assertThat(inspector.resolveModuleName((String) null, BASE_PACKAGE)).isEmpty();
        assertThat(inspector.resolveModuleName((Class<?>) null, BASE_PACKAGE)).isEmpty();
        assertThat(inspector.resolveModuleName("", BASE_PACKAGE)).isEmpty();
        assertThat(inspector.resolveModuleName("com.builtbyjuls.grid.metering.Service", null)).isEmpty();
        assertThat(inspector.resolveModuleName("com.builtbyjuls.grid.metering.Service", "  ")).isEmpty();
    }

    @Test
    @DisplayName("Should correctly identify root classes directly under base package")
    void shouldIdentifyRootClass() {
        assertThat(inspector.isRootClass("com.builtbyjuls.grid.GridSettlementModulithApplication", BASE_PACKAGE))
                .isTrue();
        assertThat(inspector.isRootClass("com.builtbyjuls.grid.TestAppConfig", BASE_PACKAGE))
                .isTrue();
        assertThat(inspector.isRootClass("com.builtbyjuls.grid.metering.MeteringApi", BASE_PACKAGE))
                .isFalse();
        assertThat(inspector.isRootClass("org.springframework.boot.SpringApplication", BASE_PACKAGE))
                .isFalse();
        assertThat(inspector.isRootClass((String) null, BASE_PACKAGE))
                .isFalse();
        assertThat(inspector.isRootClass((Class<?>) null, BASE_PACKAGE))
                .isFalse();
        assertThat(inspector.isRootClass("com.builtbyjuls.grid.GridSettlementModulithApplication", null))
                .isFalse();
    }

    @Test
    @DisplayName("Should identify internal packages correctly")
    void shouldIdentifyInternalPackages() {
        assertThat(inspector.isInternal("com.builtbyjuls.grid.metering.internal.MeteringAggregationService"))
                .isTrue();
        assertThat(inspector.isInternal("com.builtbyjuls.grid.contract.internal.repository.ContractRepo"))
                .isTrue();
        assertThat(inspector.isInternal("com.builtbyjuls.grid.metering.MeteringApi"))
                .isFalse();
        assertThat(inspector.isInternal("com.builtbyjuls.grid.metering.InternalLikeService"))
                .isFalse();
        assertThat(inspector.isInternal((String) null))
                .isFalse();
    }

    @Test
    @DisplayName("Should recognize @NamedInterface and @ModulithBridgeTarget interfaces as module APIs")
    void shouldRecognizeModuleApiInterfaces() {
        assertThat(inspector.isModuleApi(SampleNamedApi.class)).isTrue();
        assertThat(inspector.isModuleApi(SampleBridgeTargetApi.class)).isTrue();
        assertThat(inspector.isModuleApi(SamplePlainInterface.class)).isFalse();
        assertThat(inspector.isModuleApi(SampleRegularClass.class)).isFalse();
        assertThat(inspector.isModuleApi(null)).isFalse();
    }

    @Test
    @DisplayName("Should find module API interface for direct and inherited class hierarchies")
    void shouldFindModuleApiInterfaceInHierarchy() {
        assertThat(inspector.findModuleApiInterface(DirectNamedImpl.class)).contains(SampleNamedApi.class);
        assertThat(inspector.findModuleApiInterface(InheritedNamedImpl.class)).contains(SampleNamedApi.class);
        assertThat(inspector.findModuleApiInterface(DirectBridgeTargetImpl.class)).contains(SampleBridgeTargetApi.class);
        assertThat(inspector.findModuleApiInterface(SamplePlainImpl.class)).isEmpty();
        assertThat(inspector.findModuleApiInterface(SampleRegularClass.class)).isEmpty();
        assertThat(inspector.findModuleApiInterface(null)).isEmpty();
    }

    @NamedInterface("sample")
    interface SampleNamedApi {
        void execute();
    }

    @ModulithBridgeTarget
    interface SampleBridgeTargetApi {
        void execute();
    }

    interface SamplePlainInterface {
        void execute();
    }

    static class SampleRegularClass {
        void execute() {}
    }

    static class DirectNamedImpl implements SampleNamedApi {
        @Override
        public void execute() {}
    }

    static class InheritedNamedImpl extends DirectNamedImpl {}

    static class DirectBridgeTargetImpl implements SampleBridgeTargetApi {
        @Override
        public void execute() {}
    }

    static class SamplePlainImpl implements SamplePlainInterface {
        @Override
        public void execute() {}
    }
}

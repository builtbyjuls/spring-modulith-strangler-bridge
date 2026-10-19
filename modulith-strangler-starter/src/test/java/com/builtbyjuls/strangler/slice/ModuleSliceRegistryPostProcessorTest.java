package com.builtbyjuls.strangler.slice;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import com.builtbyjuls.grid.RootTestConfiguration;
import com.builtbyjuls.grid.contract.ContractPublicBean;
import com.builtbyjuls.grid.contract.internal.ContractInternalBean;
import com.builtbyjuls.grid.metering.MeteringPublicBean;
import com.builtbyjuls.grid.metering.internal.MeteringInternalBean;
import com.builtbyjuls.grid.settlement.SettlementPublicBean;
import com.builtbyjuls.grid.settlement.internal.SettlementInternalBean;
import com.builtbyjuls.strangler.annotation.EnableModulithBridge;
import com.builtbyjuls.strangler.config.ModulithBridgeAutoConfiguration;
import com.builtbyjuls.strangler.config.ModulithBridgeProperties;

class ModuleSliceRegistryPostProcessorTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ModulithBridgeAutoConfiguration.class))
            .withUserConfiguration(
                    RootTestConfiguration.class,
                    ContractPublicBean.class,
                    ContractInternalBean.class,
                    MeteringPublicBean.class,
                    MeteringInternalBean.class,
                    SettlementPublicBean.class,
                    SettlementInternalBean.class
            );

    @Test
    @DisplayName("Should prune non-hosted module beans when host-only is configured")
    void shouldPruneNonHostedModulesWhenHostOnlyIsSet() {
        contextRunner
                .withPropertyValues(
                        "modulith.bridge.base-package=com.builtbyjuls.grid",
                        "modulith.bridge.host-only=metering"
                )
                .run(context -> {
                    // Hosted module beans must be present
                    assertThat(context).hasSingleBean(MeteringPublicBean.class);
                    assertThat(context).hasSingleBean(MeteringInternalBean.class);

                    // Shared root beans must be preserved
                    assertThat(context).hasBean("rootBean");

                    // Other module beans must be pruned
                    assertThat(context).doesNotHaveBean(ContractPublicBean.class);
                    assertThat(context).doesNotHaveBean(ContractInternalBean.class);
                    assertThat(context).doesNotHaveBean(SettlementPublicBean.class);
                    assertThat(context).doesNotHaveBean(SettlementInternalBean.class);
                });
    }

    @Test
    @DisplayName("Should keep all beans in monolith mode when host-only is not set")
    void shouldKeepAllBeansWhenHostOnlyIsNotSet() {
        contextRunner
                .withPropertyValues("modulith.bridge.base-package=com.builtbyjuls.grid")
                .run(context -> {
                    assertThat(context).hasSingleBean(MeteringPublicBean.class);
                    assertThat(context).hasSingleBean(MeteringInternalBean.class);
                    assertThat(context).hasSingleBean(ContractPublicBean.class);
                    assertThat(context).hasSingleBean(ContractInternalBean.class);
                    assertThat(context).hasSingleBean(SettlementPublicBean.class);
                    assertThat(context).hasSingleBean(SettlementInternalBean.class);
                    assertThat(context).hasBean("rootBean");
                });
    }

    @Test
    @DisplayName("Should prune internal beans for remote module when fallback-to-local is false")
    void shouldPruneInternalBeansWhenModuleRemoteAndFallbackDisabled() {
        contextRunner
                .withPropertyValues(
                        "modulith.bridge.base-package=com.builtbyjuls.grid",
                        "modulith.bridge.modules.contract.mode=REMOTE",
                        "modulith.bridge.modules.contract.fallback-to-local=false"
                )
                .run(context -> {
                    // Contract internal bean pruned, but public bean kept
                    assertThat(context).doesNotHaveBean(ContractInternalBean.class);
                    assertThat(context).hasSingleBean(ContractPublicBean.class);

                    // Other modules remain untouched
                    assertThat(context).hasSingleBean(MeteringPublicBean.class);
                    assertThat(context).hasSingleBean(MeteringInternalBean.class);
                    assertThat(context).hasSingleBean(SettlementPublicBean.class);
                    assertThat(context).hasSingleBean(SettlementInternalBean.class);
                });
    }

    @Test
    @DisplayName("Should keep internal beans for remote module when fallback-to-local is true")
    void shouldKeepInternalBeansWhenModuleRemoteAndFallbackEnabled() {
        contextRunner
                .withPropertyValues(
                        "modulith.bridge.base-package=com.builtbyjuls.grid",
                        "modulith.bridge.modules.contract.mode=REMOTE",
                        "modulith.bridge.modules.contract.fallback-to-local=true"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(ContractPublicBean.class);
                    assertThat(context).hasSingleBean(ContractInternalBean.class);
                    assertThat(context).hasSingleBean(MeteringPublicBean.class);
                    assertThat(context).hasSingleBean(MeteringInternalBean.class);
                });
    }

    @Test
    @DisplayName("Should retain internal beans of hosted module on worker node even if configured remote")
    void shouldRetainInternalBeansOnWorkerNodeEvenIfConfiguredRemote() {
        contextRunner
                .withPropertyValues(
                        "modulith.bridge.base-package=com.builtbyjuls.grid",
                        "modulith.bridge.host-only=metering",
                        "modulith.bridge.modules.metering.mode=REMOTE",
                        "modulith.bridge.modules.metering.fallback-to-local=false"
                )
                .run(context -> {
                    // Metering internal bean must remain on worker node hosting metering
                    assertThat(context).hasSingleBean(MeteringPublicBean.class);
                    assertThat(context).hasSingleBean(MeteringInternalBean.class);

                    // Other modules remain pruned
                    assertThat(context).doesNotHaveBean(ContractPublicBean.class);
                    assertThat(context).doesNotHaveBean(ContractInternalBean.class);
                    assertThat(context).doesNotHaveBean(SettlementPublicBean.class);
                    assertThat(context).doesNotHaveBean(SettlementInternalBean.class);
                });
    }

    @Test
    @DisplayName("Should honor @EnableModulithBridge annotation overrides")
    void shouldHonorEnableModulithBridgeAnnotation() {
        new ApplicationContextRunner()
                .withUserConfiguration(
                        AnnotatedSettlementHostConfig.class,
                        ContractPublicBean.class,
                        ContractInternalBean.class,
                        MeteringPublicBean.class,
                        MeteringInternalBean.class,
                        SettlementPublicBean.class,
                        SettlementInternalBean.class
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(SettlementPublicBean.class);
                    assertThat(context).hasSingleBean(SettlementInternalBean.class);
                    assertThat(context).doesNotHaveBean(ContractPublicBean.class);
                    assertThat(context).doesNotHaveBean(ContractInternalBean.class);
                    assertThat(context).doesNotHaveBean(MeteringPublicBean.class);
                    assertThat(context).doesNotHaveBean(MeteringInternalBean.class);
                });
    }

    @Test
    @DisplayName("Should register ModulithBridgeProperties and ModuleLayoutInspector beans in context")
    void shouldRegisterAutoConfigurationBeans() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ModulithBridgeProperties.class);
            assertThat(context).hasSingleBean(ModuleLayoutInspector.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableModulithBridge(basePackage = "com.builtbyjuls.grid", hostOnly = "settlement")
    static class AnnotatedSettlementHostConfig {
    }
}

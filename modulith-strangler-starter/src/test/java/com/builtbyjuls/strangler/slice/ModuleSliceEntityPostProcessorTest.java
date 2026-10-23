package com.builtbyjuls.strangler.slice;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.orm.jpa.persistenceunit.MutablePersistenceUnitInfo;

import com.builtbyjuls.strangler.config.BridgeMode;
import com.builtbyjuls.strangler.config.ModulithBridgeProperties;

import static org.assertj.core.api.Assertions.assertThat;

class ModuleSliceEntityPostProcessorTest {

    private ModuleLayoutInspector inspector;
    private ModulithBridgeProperties properties;

    @BeforeEach
    void setUp() {
        inspector = new ModuleLayoutInspector();
        properties = new ModulithBridgeProperties();
        properties.setBasePackage("com.builtbyjuls.grid");
    }

    @Test
    @DisplayName("Host-only metering mode prunes non-metering entities from MutablePersistenceUnitInfo")
    void shouldPruneNonHostedEntitiesInHostOnlyMode() {
        properties.setHostOnly("metering");
        ModuleSliceEntityPostProcessor postProcessor = new ModuleSliceEntityPostProcessor(inspector, properties);

        MutablePersistenceUnitInfo pui = new MutablePersistenceUnitInfo();
        pui.addManagedClassName("com.builtbyjuls.grid.metering.internal.MeterReadingHeader");
        pui.addManagedClassName("com.builtbyjuls.grid.metering.internal.MeterIntervalReading");
        pui.addManagedClassName("com.builtbyjuls.grid.contract.internal.BilateralContract");
        pui.addManagedClassName("com.builtbyjuls.grid.contract.internal.ContractDeclaration");
        pui.addManagedClassName("com.builtbyjuls.grid.settlement.internal.SettlementRecord");
        pui.addManagedClassName("com.builtbyjuls.grid.SharedEntity");
        pui.addManagedClassName("org.example.external.ExternalEntity");

        postProcessor.postProcessPersistenceUnitInfo(pui);

        assertThat(pui.getManagedClassNames()).containsExactlyInAnyOrder(
                "com.builtbyjuls.grid.metering.internal.MeterReadingHeader",
                "com.builtbyjuls.grid.metering.internal.MeterIntervalReading",
                "com.builtbyjuls.grid.SharedEntity",
                "org.example.external.ExternalEntity"
        );
    }

    @Test
    @DisplayName("pruneManagedClasses directly prunes list in place and returns pruned list")
    void shouldPruneManagedClassesDirectly() {
        properties.setHostOnly("metering");
        ModuleSliceEntityPostProcessor postProcessor = new ModuleSliceEntityPostProcessor(inspector, properties);

        List<String> managedClasses = new ArrayList<>(List.of(
                "com.builtbyjuls.grid.metering.internal.MeterReadingHeader",
                "com.builtbyjuls.grid.contract.internal.BilateralContract",
                "com.builtbyjuls.grid.settlement.internal.SettlementRecord"
        ));

        List<String> result = postProcessor.pruneManagedClasses(managedClasses);

        assertThat(result).isSameAs(managedClasses);
        assertThat(result).containsExactly("com.builtbyjuls.grid.metering.internal.MeterReadingHeader");
    }

    @Test
    @DisplayName("Main node prunes internal entities of remote non-fallback module")
    void shouldPruneInternalEntitiesOfRemoteNonFallbackModule() {
        // Main node: hostOnly is not set
        properties.setHostOnly(null);

        ModulithBridgeProperties.ModuleRouteConfig meteringConfig = new ModulithBridgeProperties.ModuleRouteConfig();
        meteringConfig.setMode(BridgeMode.REMOTE);
        meteringConfig.setFallbackToLocal(false);
        properties.getModules().put("metering", meteringConfig);

        ModuleSliceEntityPostProcessor postProcessor = new ModuleSliceEntityPostProcessor(inspector, properties);

        List<String> managedClasses = new ArrayList<>(List.of(
                "com.builtbyjuls.grid.metering.internal.MeterReadingHeader",
                "com.builtbyjuls.grid.metering.MeteringPublicEntity",
                "com.builtbyjuls.grid.contract.internal.BilateralContract"
        ));

        postProcessor.pruneManagedClasses(managedClasses);

        assertThat(managedClasses).containsExactlyInAnyOrder(
                "com.builtbyjuls.grid.metering.MeteringPublicEntity",
                "com.builtbyjuls.grid.contract.internal.BilateralContract"
        );
    }

    @Test
    @DisplayName("Main node keeps internal entities of remote module when fallbackToLocal is true")
    void shouldKeepInternalEntitiesWhenFallbackToLocalIsTrue() {
        // Main node: hostOnly is not set
        properties.setHostOnly(null);

        ModulithBridgeProperties.ModuleRouteConfig meteringConfig = new ModulithBridgeProperties.ModuleRouteConfig();
        meteringConfig.setMode(BridgeMode.REMOTE);
        meteringConfig.setFallbackToLocal(true);
        properties.getModules().put("metering", meteringConfig);

        ModuleSliceEntityPostProcessor postProcessor = new ModuleSliceEntityPostProcessor(inspector, properties);

        List<String> managedClasses = new ArrayList<>(List.of(
                "com.builtbyjuls.grid.metering.internal.MeterReadingHeader",
                "com.builtbyjuls.grid.contract.internal.BilateralContract"
        ));

        postProcessor.pruneManagedClasses(managedClasses);

        assertThat(managedClasses).containsExactlyInAnyOrder(
                "com.builtbyjuls.grid.metering.internal.MeterReadingHeader",
                "com.builtbyjuls.grid.contract.internal.BilateralContract"
        );
    }
}

package com.builtbyjuls.strangler.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import com.builtbyjuls.strangler.slice.ModuleLayoutInspector;
import com.builtbyjuls.strangler.slice.ModuleSliceRegistryPostProcessor;

/**
 * Autoconfiguration for Spring Modulith Strangler Bridge.
 */
@AutoConfiguration
@EnableConfigurationProperties(ModulithBridgeProperties.class)
@ConditionalOnProperty(name = "modulith.bridge.enabled", havingValue = "true", matchIfMissing = true)
public class ModulithBridgeAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ModuleLayoutInspector moduleLayoutInspector() {
        return new ModuleLayoutInspector();
    }

    @Bean
    @ConditionalOnMissingBean
    public static ModuleSliceRegistryPostProcessor moduleSliceRegistryPostProcessor() {
        return new ModuleSliceRegistryPostProcessor();
    }
}

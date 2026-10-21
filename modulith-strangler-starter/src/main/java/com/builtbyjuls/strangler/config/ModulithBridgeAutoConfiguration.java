package com.builtbyjuls.strangler.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Role;
import org.springframework.web.client.RestClient;

import com.builtbyjuls.strangler.proxy.ModulithBridgeBeanPostProcessor;
import com.builtbyjuls.strangler.slice.ModuleLayoutInspector;
import com.builtbyjuls.strangler.slice.ModuleSliceRegistryPostProcessor;
import com.builtbyjuls.strangler.transport.BridgeHttpClient;
import com.builtbyjuls.strangler.transport.ModulithBridgeInboundController;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Autoconfiguration for Spring Modulith Strangler Bridge.
 */
@AutoConfiguration
@EnableConfigurationProperties(ModulithBridgeProperties.class)
@ConditionalOnProperty(name = "modulith.bridge.enabled", havingValue = "true", matchIfMissing = true)
public class ModulithBridgeAutoConfiguration {

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    @ConditionalOnMissingBean
    public static ModuleLayoutInspector moduleLayoutInspector() {
        return new ModuleLayoutInspector();
    }

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    @ConditionalOnMissingBean
    public static ModuleSliceRegistryPostProcessor moduleSliceRegistryPostProcessor() {
        return new ModuleSliceRegistryPostProcessor();
    }

    @Bean
    @ConditionalOnMissingBean
    public BridgeHttpClient bridgeHttpClient(
            ObjectProvider<RestClient.Builder> restClientBuilderProvider,
            ObjectProvider<ObjectMapper> objectMapperProvider
    ) {
        RestClient.Builder builder = restClientBuilderProvider.getIfAvailable(RestClient::builder);
        ObjectMapper mapper = objectMapperProvider.getIfAvailable();
        return new BridgeHttpClient(builder, mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnWebApplication
    public ModulithBridgeInboundController modulithBridgeInboundController(
            ApplicationContext applicationContext,
            ModuleLayoutInspector moduleLayoutInspector,
            ModulithBridgeProperties properties,
            ObjectProvider<ObjectMapper> objectMapperProvider
    ) {
        ObjectMapper mapper = objectMapperProvider.getIfAvailable();
        return new ModulithBridgeInboundController(applicationContext, moduleLayoutInspector, properties, mapper);
    }

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    @ConditionalOnMissingBean
    public static ModulithBridgeBeanPostProcessor modulithBridgeBeanPostProcessor(
            ObjectProvider<ModuleLayoutInspector> moduleLayoutInspectorProvider,
            ObjectProvider<ModulithBridgeProperties> propertiesProvider,
            ObjectProvider<BridgeHttpClient> bridgeHttpClientProvider,
            ObjectProvider<ObjectMapper> objectMapperProvider
    ) {
        return new ModulithBridgeBeanPostProcessor(
                moduleLayoutInspectorProvider,
                propertiesProvider,
                bridgeHttpClientProvider,
                objectMapperProvider
        );
    }
}

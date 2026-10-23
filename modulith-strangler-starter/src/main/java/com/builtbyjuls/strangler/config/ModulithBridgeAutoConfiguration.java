package com.builtbyjuls.strangler.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Role;
import org.springframework.web.client.RestClient;

import com.builtbyjuls.strangler.contract.ModuleContractValidator;
import com.builtbyjuls.strangler.parity.ShadowParityRegistry;
import com.builtbyjuls.strangler.parity.ShadowParityVerifier;
import com.builtbyjuls.strangler.proxy.ModulithBridgeBeanPostProcessor;
import com.builtbyjuls.strangler.report.DeploymentManifestGenerator;
import com.builtbyjuls.strangler.report.StranglerReportExporter;
import com.builtbyjuls.strangler.slice.ModuleLayoutInspector;
import com.builtbyjuls.strangler.slice.ModuleSliceEntityPostProcessor;
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
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    @ConditionalOnClass(name = "org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean")
    @ConditionalOnMissingBean
    public static ModuleSliceEntityPostProcessor moduleSliceEntityPostProcessor(
            ObjectProvider<ModuleLayoutInspector> moduleLayoutInspectorProvider,
            ObjectProvider<ModulithBridgeProperties> propertiesProvider
    ) {
        return new ModuleSliceEntityPostProcessor(moduleLayoutInspectorProvider, propertiesProvider);
    }

    @Bean
    @ConditionalOnMissingBean
    public ModuleContractValidator moduleContractValidator(
            ApplicationContext applicationContext,
            ModuleLayoutInspector moduleLayoutInspector,
            ModulithBridgeProperties properties
    ) {
        return new ModuleContractValidator(applicationContext, moduleLayoutInspector, properties);
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
    @ConditionalOnMissingBean
    public ShadowParityVerifier shadowParityVerifier(ObjectProvider<ObjectMapper> objectMapperProvider) {
        ObjectMapper mapper = objectMapperProvider.getIfAvailable();
        return new ShadowParityVerifier(mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public ShadowParityRegistry shadowParityRegistry() {
        return new ShadowParityRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public DeploymentManifestGenerator deploymentManifestGenerator(ModulithBridgeProperties properties) {
        return new DeploymentManifestGenerator(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public StranglerReportExporter stranglerReportExporter(
            ShadowParityRegistry shadowParityRegistry,
            ModulithBridgeProperties properties,
            ObjectProvider<ObjectMapper> objectMapperProvider,
            ObjectProvider<DeploymentManifestGenerator> manifestGeneratorProvider
    ) {
        ObjectMapper mapper = objectMapperProvider.getIfAvailable();
        DeploymentManifestGenerator generator = manifestGeneratorProvider.getIfAvailable();
        return new StranglerReportExporter(shadowParityRegistry, properties, mapper, generator);
    }

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    @ConditionalOnMissingBean
    public static ModulithBridgeBeanPostProcessor modulithBridgeBeanPostProcessor(
            ObjectProvider<ModuleLayoutInspector> moduleLayoutInspectorProvider,
            ObjectProvider<ModulithBridgeProperties> propertiesProvider,
            ObjectProvider<BridgeHttpClient> bridgeHttpClientProvider,
            ObjectProvider<ObjectMapper> objectMapperProvider,
            ObjectProvider<ShadowParityVerifier> shadowParityVerifierProvider,
            ObjectProvider<ShadowParityRegistry> shadowParityRegistryProvider
    ) {
        return new ModulithBridgeBeanPostProcessor(
                moduleLayoutInspectorProvider,
                propertiesProvider,
                bridgeHttpClientProvider,
                objectMapperProvider,
                shadowParityVerifierProvider,
                shadowParityRegistryProvider
        );
    }
}

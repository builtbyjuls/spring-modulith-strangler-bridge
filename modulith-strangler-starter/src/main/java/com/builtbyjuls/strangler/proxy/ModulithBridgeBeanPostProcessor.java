package com.builtbyjuls.strangler.proxy;

import java.lang.reflect.Proxy;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.Ordered;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import com.builtbyjuls.strangler.config.ModulithBridgeProperties;
import com.builtbyjuls.strangler.parity.ShadowParityRegistry;
import com.builtbyjuls.strangler.parity.ShadowParityVerifier;
import com.builtbyjuls.strangler.slice.ModuleLayoutInspector;
import com.builtbyjuls.strangler.transport.BridgeHttpClient;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Spring BeanPostProcessor that wraps module API service beans (@NamedInterface or @ModulithBridgeTarget)
 * in a dynamic routing JDK proxy (LOCAL, REMOTE, or SHADOW).
 */
public class ModulithBridgeBeanPostProcessor implements BeanPostProcessor, ApplicationContextAware, Ordered {

    private static final Logger log = LoggerFactory.getLogger(ModulithBridgeBeanPostProcessor.class);

    private final ObjectProvider<ModuleLayoutInspector> inspectorProvider;
    private final ObjectProvider<ModulithBridgeProperties> propertiesProvider;
    private final ObjectProvider<BridgeHttpClient> httpClientProvider;
    private final ObjectProvider<ObjectMapper> objectMapperProvider;
    private final ObjectProvider<ShadowParityVerifier> verifierProvider;
    private final ObjectProvider<ShadowParityRegistry> registryProvider;

    private ApplicationContext applicationContext;
    private String resolvedBasePackage;
    private ModuleLayoutInspector cachedInspector;

    public ModulithBridgeBeanPostProcessor(
            ObjectProvider<ModuleLayoutInspector> inspectorProvider,
            ObjectProvider<ModulithBridgeProperties> propertiesProvider,
            ObjectProvider<BridgeHttpClient> httpClientProvider,
            ObjectProvider<ObjectMapper> objectMapperProvider
    ) {
        this(inspectorProvider, propertiesProvider, httpClientProvider, objectMapperProvider, null, null);
    }

    public ModulithBridgeBeanPostProcessor(
            ObjectProvider<ModuleLayoutInspector> inspectorProvider,
            ObjectProvider<ModulithBridgeProperties> propertiesProvider,
            ObjectProvider<BridgeHttpClient> httpClientProvider,
            ObjectProvider<ObjectMapper> objectMapperProvider,
            ObjectProvider<ShadowParityVerifier> verifierProvider,
            ObjectProvider<ShadowParityRegistry> registryProvider
    ) {
        this.inspectorProvider = inspectorProvider;
        this.propertiesProvider = propertiesProvider;
        this.httpClientProvider = httpClientProvider;
        this.objectMapperProvider = objectMapperProvider;
        this.verifierProvider = verifierProvider;
        this.registryProvider = registryProvider;
    }

    public ModulithBridgeBeanPostProcessor(
            ModuleLayoutInspector inspector,
            ModulithBridgeProperties properties,
            BridgeHttpClient httpClient,
            ObjectMapper objectMapper
    ) {
        this(
                new SingleObjectProvider<>(inspector),
                new SingleObjectProvider<>(properties),
                new SingleObjectProvider<>(httpClient),
                new SingleObjectProvider<>(objectMapper),
                null,
                null
        );
    }

    public ModulithBridgeBeanPostProcessor(
            ModuleLayoutInspector inspector,
            ModulithBridgeProperties properties,
            BridgeHttpClient httpClient,
            ObjectMapper objectMapper,
            ShadowParityVerifier verifier,
            ShadowParityRegistry registry
    ) {
        this(
                new SingleObjectProvider<>(inspector),
                new SingleObjectProvider<>(properties),
                new SingleObjectProvider<>(httpClient),
                new SingleObjectProvider<>(objectMapper),
                new SingleObjectProvider<>(verifier),
                new SingleObjectProvider<>(registry)
        );
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean == null) {
            return null;
        }

        if (bean instanceof ModuleLayoutInspector
                || bean instanceof ModulithBridgeProperties
                || bean instanceof BridgeHttpClient
                || bean instanceof ObjectMapper
                || bean instanceof ShadowParityVerifier
                || bean instanceof ShadowParityRegistry) {
            return bean;
        }

        // Avoid double-wrapping
        if (Proxy.isProxyClass(bean.getClass())) {
            try {
                if (Proxy.getInvocationHandler(bean) instanceof ModulithBridgeInvocationHandler) {
                    return bean;
                }
            } catch (Exception ignored) {
            }
        }

        ModuleLayoutInspector inspector = getInspector();
        Class<?> targetClass = AopUtils.getTargetClass(bean);
        Optional<Class<?>> moduleApiInterface = inspector.findModuleApiInterface(targetClass);
        if (moduleApiInterface.isEmpty()) {
            return bean;
        }

        String basePackage = resolveBasePackage();
        if (!StringUtils.hasText(basePackage)) {
            return bean;
        }

        Optional<String> moduleNameOpt = inspector.resolveModuleName(targetClass, basePackage);
        if (moduleNameOpt.isEmpty()) {
            return bean;
        }

        String moduleName = moduleNameOpt.get();
        Class<?> apiInterface = moduleApiInterface.get();

        BridgeHttpClient httpClient = httpClientProvider != null ? httpClientProvider.getIfAvailable() : null;
        ObjectMapper objectMapper = objectMapperProvider != null ? objectMapperProvider.getIfAvailable() : null;
        ShadowParityVerifier verifier = verifierProvider != null ? verifierProvider.getIfAvailable() : null;
        ShadowParityRegistry registry = registryProvider != null ? registryProvider.getIfAvailable() : null;

        ModulithBridgeInvocationHandler handler = new ModulithBridgeInvocationHandler(
                bean,
                moduleName,
                apiInterface,
                getProperties(),
                httpClient,
                objectMapper,
                verifier,
                registry
        );

        Class<?>[] allInterfaces = ClassUtils.getAllInterfacesForClass(bean.getClass());
        ClassLoader classLoader = apiInterface.getClassLoader() != null
                ? apiInterface.getClassLoader()
                : ClassUtils.getDefaultClassLoader();

        log.info("Wrapped bean '{}' (module: '{}', interface: '{}') in ModulithBridge proxy",
                beanName, moduleName, apiInterface.getSimpleName());

        return Proxy.newProxyInstance(classLoader, allInterfaces, handler);
    }

    private ModuleLayoutInspector getInspector() {
        if (this.cachedInspector != null) {
            return this.cachedInspector;
        }
        if (this.inspectorProvider != null) {
            ModuleLayoutInspector found = this.inspectorProvider.getIfAvailable();
            if (found != null) {
                this.cachedInspector = found;
                return found;
            }
        }
        return new ModuleLayoutInspector();
    }

    private ModulithBridgeProperties getProperties() {
        return propertiesProvider != null && propertiesProvider.getIfAvailable() != null
                ? propertiesProvider.getIfAvailable()
                : new ModulithBridgeProperties();
    }

    private String resolveBasePackage() {
        if (StringUtils.hasText(resolvedBasePackage)) {
            return resolvedBasePackage;
        }
        ModulithBridgeProperties props = getProperties();
        if (StringUtils.hasText(props.getBasePackage())) {
            resolvedBasePackage = props.getBasePackage();
            return resolvedBasePackage;
        }
        if (applicationContext != null) {
            for (String name : applicationContext.getBeanDefinitionNames()) {
                try {
                    Class<?> type = applicationContext.getType(name);
                    if (type != null && (type.isAnnotationPresent(org.springframework.boot.autoconfigure.SpringBootApplication.class)
                            || type.isAnnotationPresent(org.springframework.boot.SpringBootConfiguration.class))) {
                        resolvedBasePackage = ClassUtils.getPackageName(type);
                        props.setBasePackage(resolvedBasePackage);
                        return resolvedBasePackage;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    private static class SingleObjectProvider<T> implements ObjectProvider<T> {
        private final T value;

        SingleObjectProvider(T value) {
            this.value = value;
        }

        @Override
        public T getObject(Object... args) throws BeansException {
            return value;
        }

        @Override
        public T getIfAvailable() throws BeansException {
            return value;
        }

        @Override
        public T getIfUnique() throws BeansException {
            return value;
        }

        @Override
        public T getObject() throws BeansException {
            return value;
        }
    }
}

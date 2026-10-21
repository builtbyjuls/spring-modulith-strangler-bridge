package com.builtbyjuls.strangler.slice;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.beans.PropertyValue;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.ConstructorArgumentValues;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.config.TypedStringValue;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.Ordered;
import org.springframework.core.ResolvableType;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.MethodMetadata;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import com.builtbyjuls.strangler.annotation.EnableModulithBridge;
import com.builtbyjuls.strangler.config.BridgeMode;
import com.builtbyjuls.strangler.config.ModulithBridgeProperties;
import com.builtbyjuls.strangler.proxy.RemoteBridgeProxyFactoryBean;

/**
 * Prunes bean definitions belonging to non-hosted application modules before singleton instantiation.
 */
public class ModuleSliceRegistryPostProcessor implements BeanDefinitionRegistryPostProcessor, EnvironmentAware, BeanClassLoaderAware, Ordered {

    private static final Logger log = LoggerFactory.getLogger(ModuleSliceRegistryPostProcessor.class);

    private final ModuleLayoutInspector inspector;
    private Environment environment;
    private ClassLoader beanClassLoader;
    private ModulithBridgeProperties properties;
    private String basePackage;
    private String hostOnly;
    private boolean enabled = true;

    public ModuleSliceRegistryPostProcessor() {
        this.inspector = new ModuleLayoutInspector();
    }

    public ModuleSliceRegistryPostProcessor(String basePackage, String hostOnly) {
        this();
        this.basePackage = basePackage;
        this.hostOnly = hostOnly;
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void setBeanClassLoader(ClassLoader classLoader) {
        this.beanClassLoader = classLoader;
    }

    public void setBasePackage(String basePackage) {
        this.basePackage = basePackage;
    }

    public void setHostOnly(String hostOnly) {
        this.hostOnly = hostOnly;
    }

    public void setProperties(ModulithBridgeProperties properties) {
        this.properties = properties;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
        pruneBeans(registry);
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        if (beanFactory instanceof BeanDefinitionRegistry registry) {
            pruneBeans(registry);
        }
    }

    private void pruneBeans(BeanDefinitionRegistry registry) {
        resolveConfiguration(registry);

        if (!enabled || !StringUtils.hasText(this.basePackage)) {
            return;
        }

        Set<String> remoteNonFallbackModules = new HashSet<>();
        if (this.properties != null && this.properties.getModules() != null) {
            for (Map.Entry<String, ModulithBridgeProperties.ModuleRouteConfig> entry : this.properties.getModules().entrySet()) {
                ModulithBridgeProperties.ModuleRouteConfig cfg = entry.getValue();
                if (cfg != null && cfg.getMode() == BridgeMode.REMOTE && !cfg.isFallbackToLocal()) {
                    remoteNonFallbackModules.add(entry.getKey().toLowerCase());
                }
            }
        }

        boolean hasHostOnly = StringUtils.hasText(this.hostOnly);
        String activeHostModule = hasHostOnly ? this.hostOnly.trim().toLowerCase() : null;
        Map<Class<?>, String> remoteProxyInterfaces = new LinkedHashMap<>();

        for (String beanName : registry.getBeanDefinitionNames()) {
            if (!registry.containsBeanDefinition(beanName)) {
                continue;
            }

            BeanDefinition bd = registry.getBeanDefinition(beanName);
            Set<String> candidateClasses = extractCandidateClasses(bd);
            if (candidateClasses.isEmpty()) {
                continue;
            }

            boolean shouldRemove = false;
            boolean isRemotePruned = false;
            String remoteModuleName = null;
            for (String className : candidateClasses) {
                if (inspector.isRootClass(className, this.basePackage)) {
                    continue;
                }

                Optional<String> moduleOpt = inspector.resolveModuleName(className, this.basePackage);
                if (moduleOpt.isEmpty()) {
                    continue;
                }

                String moduleName = moduleOpt.get().toLowerCase();

                // 1. Host-only pruning: remove beans residing in other modules
                if (hasHostOnly && !moduleName.equals(activeHostModule)) {
                    shouldRemove = true;
                    break;
                }

                // 2. Remote non-fallback pruning: remove internal beans of remote module on the main node
                if (!hasHostOnly && remoteNonFallbackModules.contains(moduleName) && inspector.isInternal(className)) {
                    shouldRemove = true;
                    isRemotePruned = true;
                    remoteModuleName = moduleName;
                    break;
                }
            }

            if (shouldRemove) {
                if (isRemotePruned && remoteModuleName != null) {
                    collectRemoteApiInterfaces(candidateClasses, remoteModuleName, remoteProxyInterfaces);
                }
                log.info("Pruned bean definition '{}' (classes: {}) for module slicing", beanName, candidateClasses);
                for (String alias : registry.getAliases(beanName)) {
                    registry.removeAlias(alias);
                }
                if (bd.getPropertyValues() != null && bd.getPropertyValues().contains("repositoryFragments")) {
                    PropertyValue fragmentsPv = bd.getPropertyValues().getPropertyValue("repositoryFragments");
                    if (fragmentsPv != null && fragmentsPv.getValue() instanceof RuntimeBeanReference rbr) {
                        if (registry.containsBeanDefinition(rbr.getBeanName())) {
                            log.info("Pruned repository fragments bean '{}'", rbr.getBeanName());
                            registry.removeBeanDefinition(rbr.getBeanName());
                        }
                    }
                }
                registry.removeBeanDefinition(beanName);
            }
        }

        // Register remote-only proxy FactoryBeans for pruned module interfaces
        for (Map.Entry<Class<?>, String> entry : remoteProxyInterfaces.entrySet()) {
            Class<?> iface = entry.getKey();
            String modName = entry.getValue();
            String proxyBeanName = StringUtils.uncapitalize(iface.getSimpleName());
            if (!registry.containsBeanDefinition(proxyBeanName)) {
                RootBeanDefinition proxyBd = new RootBeanDefinition(RemoteBridgeProxyFactoryBean.class);
                proxyBd.setTargetType(iface);
                proxyBd.setAttribute(FactoryBean.OBJECT_TYPE_ATTRIBUTE, iface);
                proxyBd.getPropertyValues().add("interfaceType", iface);
                proxyBd.getPropertyValues().add("moduleName", modName);
                registry.registerBeanDefinition(proxyBeanName, proxyBd);
                log.info("Registered remote-only proxy FactoryBean '{}' for interface '{}' (module: '{}')",
                        proxyBeanName, iface.getName(), modName);
            }
        }
    }

    private void collectRemoteApiInterfaces(
            Set<String> candidateClasses,
            String moduleName,
            Map<Class<?>, String> remoteProxyInterfaces
    ) {
        ClassLoader cl = this.beanClassLoader != null ? this.beanClassLoader : ClassUtils.getDefaultClassLoader();
        for (String className : candidateClasses) {
            try {
                Class<?> clazz = ClassUtils.forName(className, cl);
                Optional<Class<?>> apiIface = inspector.findModuleApiInterface(clazz);
                apiIface.ifPresent(iface -> remoteProxyInterfaces.put(iface, moduleName));
            } catch (Throwable ignored) {
            }
        }
    }

    private void resolveConfiguration(BeanDefinitionRegistry registry) {
        if (this.environment != null) {
            Boolean enabledProp = this.environment.getProperty("modulith.bridge.enabled", Boolean.class);
            if (Boolean.FALSE.equals(enabledProp)) {
                this.enabled = false;
                return;
            }

            if (!StringUtils.hasText(this.basePackage)) {
                this.basePackage = this.environment.getProperty("modulith.bridge.base-package");
                if (!StringUtils.hasText(this.basePackage)) {
                    this.basePackage = this.environment.getProperty("modulith.bridge.basePackage");
                }
            }

            if (!StringUtils.hasText(this.hostOnly)) {
                this.hostOnly = this.environment.getProperty("modulith.bridge.host-only");
                if (!StringUtils.hasText(this.hostOnly)) {
                    this.hostOnly = this.environment.getProperty("modulith.bridge.hostOnly");
                }
            }

            if (this.properties == null) {
                try {
                    this.properties = Binder.get(this.environment)
                            .bind("modulith.bridge", ModulithBridgeProperties.class)
                            .orElseGet(ModulithBridgeProperties::new);
                } catch (Exception e) {
                    this.properties = new ModulithBridgeProperties();
                }
            }

            if (!StringUtils.hasText(this.basePackage) && StringUtils.hasText(this.properties.getBasePackage())) {
                this.basePackage = this.properties.getBasePackage();
            }

            if (!StringUtils.hasText(this.hostOnly) && StringUtils.hasText(this.properties.getHostOnly())) {
                this.hostOnly = this.properties.getHostOnly();
            }
        }

        String discoveredBasePackage = null;
        for (String beanName : registry.getBeanDefinitionNames()) {
            BeanDefinition bd = registry.getBeanDefinition(beanName);
            if (bd instanceof AnnotatedBeanDefinition abd) {
                AnnotationMetadata metadata = abd.getMetadata();
                if (metadata.hasAnnotation(EnableModulithBridge.class.getName())) {
                    Map<String, Object> attrs = metadata.getAnnotationAttributes(EnableModulithBridge.class.getName());
                    if (attrs != null) {
                        String bp = (String) attrs.get("basePackage");
                        if (StringUtils.hasText(bp) && !StringUtils.hasText(this.basePackage)) {
                            this.basePackage = bp;
                        }
                        String ho = (String) attrs.get("hostOnly");
                        if (StringUtils.hasText(ho) && !StringUtils.hasText(this.hostOnly)) {
                            this.hostOnly = ho;
                        }
                    }
                    if (!StringUtils.hasText(this.basePackage)) {
                        this.basePackage = ClassUtils.getPackageName(metadata.getClassName());
                    }
                }

                if (discoveredBasePackage == null && (
                        metadata.hasAnnotation("org.springframework.boot.autoconfigure.SpringBootApplication")
                        || metadata.hasAnnotation("org.springframework.boot.SpringBootConfiguration"))) {
                    discoveredBasePackage = ClassUtils.getPackageName(metadata.getClassName());
                }
            }
        }

        if (!StringUtils.hasText(this.basePackage) && StringUtils.hasText(discoveredBasePackage)) {
            this.basePackage = discoveredBasePackage;
        }

        if (this.properties != null && !StringUtils.hasText(this.properties.getBasePackage()) && StringUtils.hasText(this.basePackage)) {
            this.properties.setBasePackage(this.basePackage);
        }
    }

    private Set<String> extractCandidateClasses(BeanDefinition bd) {
        Set<String> classes = new LinkedHashSet<>();
        if (StringUtils.hasText(bd.getBeanClassName())) {
            classes.add(bd.getBeanClassName());
        }
        if (bd instanceof AnnotatedBeanDefinition abd) {
            AnnotationMetadata metadata = abd.getMetadata();
            if (metadata != null && StringUtils.hasText(metadata.getClassName())) {
                classes.add(metadata.getClassName());
            }
            MethodMetadata factoryMethod = abd.getFactoryMethodMetadata();
            if (factoryMethod != null) {
                if (StringUtils.hasText(factoryMethod.getReturnTypeName())) {
                    classes.add(factoryMethod.getReturnTypeName());
                }
                if (StringUtils.hasText(factoryMethod.getDeclaringClassName())) {
                    classes.add(factoryMethod.getDeclaringClassName());
                }
            }
        }
        if (bd instanceof AbstractBeanDefinition abd && abd.hasBeanClass()) {
            try {
                classes.add(abd.getBeanClass().getName());
            } catch (IllegalStateException ignored) {
            }
        }
        ResolvableType resolvableType = bd.getResolvableType();
        if (resolvableType != null && resolvableType != ResolvableType.NONE) {
            Class<?> resolved = resolvableType.resolve();
            if (resolved != null) {
                classes.add(resolved.getName());
            }
        }
        Object factoryBeanObjectType = bd.getAttribute(FactoryBean.OBJECT_TYPE_ATTRIBUTE);
        addCandidateValue(factoryBeanObjectType, classes);

        Object repoConfig = bd.getAttribute("org.springframework.data.repository.config.RepositoryConfiguration");
        if (repoConfig != null) {
            try {
                Method getRepoInterface = repoConfig.getClass().getMethod("getRepositoryInterface");
                Object repoIfce = getRepoInterface.invoke(repoConfig);
                addCandidateValue(repoIfce, classes);
            } catch (Exception ignored) {
            }
        }

        if (bd.hasConstructorArgumentValues()) {
            for (ConstructorArgumentValues.ValueHolder vh : bd.getConstructorArgumentValues().getGenericArgumentValues()) {
                addCandidateValue(vh.getValue(), classes);
            }
            for (ConstructorArgumentValues.ValueHolder vh : bd.getConstructorArgumentValues().getIndexedArgumentValues().values()) {
                addCandidateValue(vh.getValue(), classes);
            }
        }

        if (bd.getPropertyValues() != null) {
            Object repoInterface = null;
            if (bd.getPropertyValues() instanceof MutablePropertyValues mpv) {
                repoInterface = mpv.get("repositoryInterface");
            } else if (bd.getPropertyValues().contains("repositoryInterface")) {
                PropertyValue pv = bd.getPropertyValues().getPropertyValue("repositoryInterface");
                repoInterface = (pv != null) ? pv.getValue() : null;
            }
            addCandidateValue(repoInterface, classes);
        }
        if (bd.getOriginatingBeanDefinition() != null) {
            classes.addAll(extractCandidateClasses(bd.getOriginatingBeanDefinition()));
        }
        return classes;
    }

    private void addCandidateValue(Object value, Set<String> classes) {
        if (value == null) {
            return;
        }
        if (value instanceof Class<?> clazz) {
            classes.add(clazz.getName());
        } else if (value instanceof ResolvableType rt) {
            Class<?> resolved = rt.resolve();
            if (resolved != null) {
                classes.add(resolved.getName());
            }
        } else if (value instanceof String s && StringUtils.hasText(s)) {
            classes.add(s);
        } else if (value instanceof TypedStringValue tsv && StringUtils.hasText(tsv.getValue())) {
            classes.add(tsv.getValue());
        } else if (StringUtils.hasText(value.toString())) {
            classes.add(value.toString());
        }
    }
}

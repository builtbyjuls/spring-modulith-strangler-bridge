package com.builtbyjuls.strangler.slice;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
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

/**
 * Prunes bean definitions belonging to non-hosted application modules before singleton instantiation.
 */
public class ModuleSliceRegistryPostProcessor implements BeanDefinitionRegistryPostProcessor, EnvironmentAware, Ordered {

    private static final Logger log = LoggerFactory.getLogger(ModuleSliceRegistryPostProcessor.class);

    private final ModuleLayoutInspector inspector;
    private Environment environment;
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

    public void setBasePackage(String basePackage) {
        this.basePackage = basePackage;
    }

    public void setHostOnly(String hostOnly) {
        this.hostOnly = hostOnly;
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
                    break;
                }
            }

            if (shouldRemove) {
                log.info("Pruned bean definition '{}' (classes: {}) for module slicing", beanName, candidateClasses);
                for (String alias : registry.getAliases(beanName)) {
                    registry.removeAlias(alias);
                }
                registry.removeBeanDefinition(beanName);
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
        if (bd.getOriginatingBeanDefinition() != null) {
            classes.addAll(extractCandidateClasses(bd.getOriginatingBeanDefinition()));
        }
        return classes;
    }
}

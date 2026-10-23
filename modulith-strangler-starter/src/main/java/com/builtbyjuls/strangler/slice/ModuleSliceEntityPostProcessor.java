package com.builtbyjuls.strangler.slice;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.persistenceunit.DefaultPersistenceUnitManager;
import org.springframework.orm.jpa.persistenceunit.MutablePersistenceUnitInfo;
import org.springframework.orm.jpa.persistenceunit.PersistenceUnitPostProcessor;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import com.builtbyjuls.strangler.config.BridgeMode;
import com.builtbyjuls.strangler.config.ModulithBridgeProperties;

/**
 * Prunes managed JPA entities belonging to non-hosted application modules from Hibernate metamodel
 * construction before EntityManagerFactory initialization.
 */
public class ModuleSliceEntityPostProcessor implements BeanPostProcessor, PersistenceUnitPostProcessor, EnvironmentAware, ApplicationContextAware {

    private static final Logger log = LoggerFactory.getLogger(ModuleSliceEntityPostProcessor.class);

    private final ObjectProvider<ModuleLayoutInspector> inspectorProvider;
    private final ObjectProvider<ModulithBridgeProperties> propertiesProvider;
    private ModuleLayoutInspector cachedInspector;
    private ModulithBridgeProperties properties;
    private Environment environment;
    private ApplicationContext applicationContext;
    private String basePackage;
    private String hostOnly;

    public ModuleSliceEntityPostProcessor() {
        this.inspectorProvider = null;
        this.propertiesProvider = null;
        this.cachedInspector = new ModuleLayoutInspector();
    }

    public ModuleSliceEntityPostProcessor(ModuleLayoutInspector inspector, ModulithBridgeProperties properties) {
        this.inspectorProvider = null;
        this.propertiesProvider = null;
        this.cachedInspector = inspector != null ? inspector : new ModuleLayoutInspector();
        this.properties = properties;
    }

    public ModuleSliceEntityPostProcessor(
            ObjectProvider<ModuleLayoutInspector> inspectorProvider,
            ObjectProvider<ModulithBridgeProperties> propertiesProvider
    ) {
        this.inspectorProvider = inspectorProvider;
        this.propertiesProvider = propertiesProvider;
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
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
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof LocalContainerEntityManagerFactoryBean emfBean) {
            attachPostProcessor(emfBean);
        }
        return bean;
    }

    @Override
    public void postProcessPersistenceUnitInfo(MutablePersistenceUnitInfo pui) {
        if (pui != null && pui.getManagedClassNames() != null) {
            pruneManagedClasses(pui.getManagedClassNames());
        }
    }

    /**
     * Prunes managed entity class names in-place according to active module slicing configuration.
     *
     * @param managedClassNames list of fully qualified entity class names
     * @return the same list after in-place pruning
     */
    public List<String> pruneManagedClasses(List<String> managedClassNames) {
        if (managedClassNames == null || managedClassNames.isEmpty()) {
            return managedClassNames;
        }

        String effectiveBasePackage = resolveBasePackage();
        if (!StringUtils.hasText(effectiveBasePackage)) {
            return managedClassNames;
        }

        String effectiveHostOnly = resolveHostOnly();
        boolean hasHostOnly = StringUtils.hasText(effectiveHostOnly);
        String activeHostModule = hasHostOnly ? effectiveHostOnly.trim().toLowerCase() : null;

        Set<String> remoteNonFallbackModules = new HashSet<>();
        ModulithBridgeProperties props = getEffectiveProperties();
        if (props != null && props.getModules() != null) {
            for (Map.Entry<String, ModulithBridgeProperties.ModuleRouteConfig> entry : props.getModules().entrySet()) {
                ModulithBridgeProperties.ModuleRouteConfig cfg = entry.getValue();
                if (cfg != null && cfg.getMode() == BridgeMode.REMOTE && !cfg.isFallbackToLocal()) {
                    remoteNonFallbackModules.add(entry.getKey().toLowerCase());
                }
            }
        }

        ModuleLayoutInspector layoutInspector = getInspector();

        Iterator<String> iterator = managedClassNames.iterator();
        while (iterator.hasNext()) {
            String className = iterator.next();

            if (layoutInspector.isRootClass(className, effectiveBasePackage)) {
                continue;
            }

            Optional<String> moduleOpt = layoutInspector.resolveModuleName(className, effectiveBasePackage);
            if (moduleOpt.isEmpty()) {
                // External or root class outside application modules
                continue;
            }

            String moduleName = moduleOpt.get().toLowerCase();
            boolean shouldPrune = false;

            // 1. Host-only pruning: remove entities residing in other modules
            if (hasHostOnly && !moduleName.equals(activeHostModule)) {
                shouldPrune = true;
            }

            // 2. Remote non-fallback pruning: remove internal entities of remote module on main node
            if (!hasHostOnly && remoteNonFallbackModules.contains(moduleName) && layoutInspector.isInternal(className)) {
                shouldPrune = true;
            }

            if (shouldPrune) {
                iterator.remove();
                log.info("Pruned entity class '{}' from persistence unit for module slicing", className);
            }
        }

        return managedClassNames;
    }

    private void attachPostProcessor(LocalContainerEntityManagerFactoryBean emfBean) {
        try {
            Field externalField = LocalContainerEntityManagerFactoryBean.class.getDeclaredField("persistenceUnitManager");
            externalField.setAccessible(true);
            Object externalManager = externalField.get(emfBean);
            if (externalManager instanceof DefaultPersistenceUnitManager defaultManager) {
                addPostProcessorToManager(defaultManager);
                return;
            }

            Field field = LocalContainerEntityManagerFactoryBean.class.getDeclaredField("internalPersistenceUnitManager");
            field.setAccessible(true);
            DefaultPersistenceUnitManager manager = (DefaultPersistenceUnitManager) field.get(emfBean);
            if (manager != null) {
                addPostProcessorToManager(manager);
                return;
            }
        } catch (Throwable t) {
            log.debug("Could not reflectively attach to PersistenceUnitManager, invoking setPersistenceUnitPostProcessors", t);
        }

        emfBean.setPersistenceUnitPostProcessors(this);
    }

    private void addPostProcessorToManager(DefaultPersistenceUnitManager manager) {
        PersistenceUnitPostProcessor[] existing = manager.getPersistenceUnitPostProcessors();
        List<PersistenceUnitPostProcessor> list = new ArrayList<>();
        if (existing != null) {
            Collections.addAll(list, existing);
        }
        if (!list.contains(this)) {
            list.add(this);
        }
        manager.setPersistenceUnitPostProcessors(list.toArray(new PersistenceUnitPostProcessor[0]));
    }

    private ModuleLayoutInspector getInspector() {
        if (this.cachedInspector != null) {
            return this.cachedInspector;
        }
        if (this.inspectorProvider != null) {
            this.cachedInspector = this.inspectorProvider.getIfAvailable(ModuleLayoutInspector::new);
            return this.cachedInspector;
        }
        this.cachedInspector = new ModuleLayoutInspector();
        return this.cachedInspector;
    }

    private String resolveBasePackage() {
        if (StringUtils.hasText(this.basePackage)) {
            return this.basePackage;
        }
        ModulithBridgeProperties props = getEffectiveProperties();
        if (props != null && StringUtils.hasText(props.getBasePackage())) {
            this.basePackage = props.getBasePackage();
            return this.basePackage;
        }
        if (this.environment != null) {
            String prop = this.environment.getProperty("modulith.bridge.base-package");
            if (StringUtils.hasText(prop)) {
                this.basePackage = prop;
                return this.basePackage;
            }
            prop = this.environment.getProperty("modulith.bridge.basePackage");
            if (StringUtils.hasText(prop)) {
                this.basePackage = prop;
                return this.basePackage;
            }
        }
        if (this.applicationContext != null) {
            try {
                if (AutoConfigurationPackages.has(this.applicationContext)) {
                    List<String> packages = AutoConfigurationPackages.get(this.applicationContext);
                    if (packages != null && !packages.isEmpty()) {
                        this.basePackage = packages.get(0);
                        return this.basePackage;
                    }
                }
            } catch (Exception ignored) {
            }
            for (String name : this.applicationContext.getBeanDefinitionNames()) {
                try {
                    Class<?> type = this.applicationContext.getType(name);
                    if (type != null && (type.isAnnotationPresent(org.springframework.boot.autoconfigure.SpringBootApplication.class)
                            || type.isAnnotationPresent(org.springframework.boot.SpringBootConfiguration.class))) {
                        this.basePackage = ClassUtils.getPackageName(type);
                        if (props != null && !StringUtils.hasText(props.getBasePackage())) {
                            props.setBasePackage(this.basePackage);
                        }
                        return this.basePackage;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    private String resolveHostOnly() {
        if (StringUtils.hasText(this.hostOnly)) {
            return this.hostOnly;
        }
        ModulithBridgeProperties props = getEffectiveProperties();
        if (props != null && StringUtils.hasText(props.getHostOnly())) {
            return props.getHostOnly();
        }
        if (this.environment != null) {
            String prop = this.environment.getProperty("modulith.bridge.host-only");
            if (StringUtils.hasText(prop)) {
                return prop;
            }
            return this.environment.getProperty("modulith.bridge.hostOnly");
        }
        return null;
    }

    private ModulithBridgeProperties getEffectiveProperties() {
        if (this.properties != null) {
            return this.properties;
        }
        if (this.propertiesProvider != null) {
            return this.propertiesProvider.getIfAvailable();
        }
        return null;
    }
}

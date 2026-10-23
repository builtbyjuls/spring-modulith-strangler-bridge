package com.builtbyjuls.strangler.contract;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.lang.annotation.Annotation;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import com.builtbyjuls.strangler.config.ModulithBridgeProperties;
import com.builtbyjuls.strangler.slice.ModuleLayoutInspector;

/**
 * Validates at startup that Spring Modulith boundary interfaces expose clean, remotable contracts
 * without leaked JPA entities or non-serializable streaming types.
 */
public class ModuleContractValidator implements SmartInitializingSingleton, ApplicationContextAware, EnvironmentAware {

    private static final Logger log = LoggerFactory.getLogger(ModuleContractValidator.class);

    private static final String JAKARTA_ENTITY = "jakarta.persistence.Entity";
    private static final String JAVAX_ENTITY = "javax.persistence.Entity";

    private ApplicationContext applicationContext;
    private Environment environment;
    private final ModuleLayoutInspector inspector;
    private final ModulithBridgeProperties properties;

    public ModuleContractValidator(
            ApplicationContext applicationContext,
            ModuleLayoutInspector inspector,
            ModulithBridgeProperties properties
    ) {
        this.applicationContext = applicationContext;
        this.inspector = inspector != null ? inspector : new ModuleLayoutInspector();
        this.properties = properties;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (!isValidationEnabled()) {
            log.info("Startup module contract validation is disabled (modulith.bridge.validate-contracts=false)");
            return;
        }

        validateAllModuleInterfaces();
    }

    private boolean isValidationEnabled() {
        if (this.properties != null && !this.properties.isValidateContracts()) {
            return false;
        }
        if (this.environment != null) {
            Boolean prop = this.environment.getProperty("modulith.bridge.validate-contracts", Boolean.class);
            if (Boolean.FALSE.equals(prop)) {
                return false;
            }
            prop = this.environment.getProperty("modulith.bridge.validateContracts", Boolean.class);
            if (Boolean.FALSE.equals(prop)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Inspects all module boundary interfaces discovered in the application context.
     */
    public void validateAllModuleInterfaces() {
        if (this.applicationContext == null) {
            return;
        }

        Set<Class<?>> validatedInterfaces = new HashSet<>();
        for (String beanName : this.applicationContext.getBeanDefinitionNames()) {
            try {
                Class<?> beanType = this.applicationContext.getType(beanName);
                if (beanType == null) {
                    continue;
                }

                if (beanType.isInterface() && this.inspector.isModuleApi(beanType)) {
                    if (shouldValidate(beanType) && validatedInterfaces.add(beanType)) {
                        validateInterface(beanType);
                    }
                }

                for (Class<?> iface : ClassUtils.getAllInterfacesForClass(beanType)) {
                    if (this.inspector.isModuleApi(iface) && shouldValidate(iface) && validatedInterfaces.add(iface)) {
                        validateInterface(iface);
                    }
                }
            } catch (BeansException ignored) {
            }
        }
    }

    /**
     * Validates a specific module interface contract.
     *
     * @param apiInterface interface to validate
     * @throws InvalidModuleContractException if contract uses leaked JPA entities or non-serializable streams
     */
    public void validateInterface(Class<?> apiInterface) {
        if (apiInterface == null || !apiInterface.isInterface()) {
            return;
        }

        for (Method method : apiInterface.getMethods()) {
            if (method.getDeclaringClass() == Object.class) {
                continue;
            }

            // Inspect generic return type
            validateType(method.getGenericReturnType(), apiInterface, method, new HashSet<>());

            // Inspect generic parameter types
            for (Type paramType : method.getGenericParameterTypes()) {
                validateType(paramType, apiInterface, method, new HashSet<>());
            }
        }
    }

    private void validateType(Type type, Class<?> apiInterface, Method method, Set<Type> visited) {
        if (type == null || type == void.class || type == Void.class) {
            return;
        }

        if (!visited.add(type)) {
            return;
        }

        if (type instanceof Class<?> clazz) {
            if (clazz.isArray()) {
                validateType(clazz.getComponentType(), apiInterface, method, visited);
                return;
            }
            checkClass(clazz, apiInterface, method);
        } else if (type instanceof ParameterizedType parameterizedType) {
            Type rawType = parameterizedType.getRawType();
            validateType(rawType, apiInterface, method, visited);
            for (Type arg : parameterizedType.getActualTypeArguments()) {
                validateType(arg, apiInterface, method, visited);
            }
        } else if (type instanceof GenericArrayType genericArrayType) {
            validateType(genericArrayType.getGenericComponentType(), apiInterface, method, visited);
        } else if (type instanceof WildcardType wildcardType) {
            for (Type upper : wildcardType.getUpperBounds()) {
                validateType(upper, apiInterface, method, visited);
            }
            for (Type lower : wildcardType.getLowerBounds()) {
                validateType(lower, apiInterface, method, visited);
            }
        }
    }

    private void checkClass(Class<?> clazz, Class<?> apiInterface, Method method) {
        if (isJpaEntity(clazz)) {
            throw new InvalidModuleContractException(
                    String.format("Interface '%s', method '%s' exposes JPA entity type '%s' in its contract. "
                                    + "JPA entities must not cross module boundaries.",
                            apiInterface.getName(), method.getName(), clazz.getName()),
                    apiInterface,
                    method.getName(),
                    clazz
            );
        }

        if (isStreamingOrServletType(clazz)) {
            throw new InvalidModuleContractException(
                    String.format("Interface '%s', method '%s' exposes non-serializable streaming or servlet type '%s' "
                                    + "in its contract.",
                            apiInterface.getName(), method.getName(), clazz.getName()),
                    apiInterface,
                    method.getName(),
                    clazz
            );
        }
    }

    private boolean isJpaEntity(Class<?> clazz) {
        if (clazz == null || clazz.isPrimitive() || clazz.getName().startsWith("java.")) {
            return false;
        }

        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Annotation annotation : current.getAnnotations()) {
                String annName = annotation.annotationType().getName();
                if (JAKARTA_ENTITY.equals(annName) || JAVAX_ENTITY.equals(annName)) {
                    return true;
                }
            }
            current = current.getSuperclass();
        }

        return false;
    }

    private boolean isStreamingOrServletType(Class<?> clazz) {
        if (clazz == null) {
            return false;
        }

        if (InputStream.class.isAssignableFrom(clazz)
                || OutputStream.class.isAssignableFrom(clazz)
                || Reader.class.isAssignableFrom(clazz)
                || Writer.class.isAssignableFrom(clazz)
                || Stream.class.isAssignableFrom(clazz)) {
            return true;
        }

        return isSubtypeOf(clazz, "jakarta.servlet.ServletRequest")
                || isSubtypeOf(clazz, "jakarta.servlet.ServletResponse")
                || isSubtypeOf(clazz, "javax.servlet.ServletRequest")
                || isSubtypeOf(clazz, "javax.servlet.ServletResponse");
    }

    private boolean isSubtypeOf(Class<?> clazz, String className) {
        try {
            Class<?> target = ClassUtils.forName(className, clazz.getClassLoader());
            return target.isAssignableFrom(clazz);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean shouldValidate(Class<?> iface) {
        if (this.properties == null || !StringUtils.hasText(this.properties.getBasePackage())) {
            return true;
        }
        String cleanBase = this.properties.getBasePackage().trim();
        while (cleanBase.endsWith(".")) {
            cleanBase = cleanBase.substring(0, cleanBase.length() - 1);
        }
        return iface.getName().startsWith(cleanBase + ".");
    }
}

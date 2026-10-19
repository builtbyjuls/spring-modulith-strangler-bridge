package com.builtbyjuls.strangler.slice;

import java.util.Optional;

import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.modulith.NamedInterface;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import com.builtbyjuls.strangler.annotation.ModulithBridgeTarget;

/**
 * Inspects package structures and interface contracts for Spring Modulith modules.
 */
public class ModuleLayoutInspector {

    private static final String INTERNAL_SEGMENT = ".internal.";
    private static final String INTERNAL_SUFFIX = ".internal";
    private static final String INTERNAL_PREFIX = "internal.";

    /**
     * Resolves the module name for a class relative to the specified base package.
     *
     * @param className   fully qualified class name
     * @param basePackage root application base package (e.g. com.builtbyjuls.grid)
     * @return module name if class belongs to a sub-package module, or empty
     */
    public Optional<String> resolveModuleName(String className, String basePackage) {
        if (!StringUtils.hasText(className) || !StringUtils.hasText(basePackage)) {
            return Optional.empty();
        }

        String cleanBase = normalizeBasePackage(basePackage);
        String prefix = cleanBase + ".";

        if (!className.startsWith(prefix)) {
            return Optional.empty();
        }

        String remainder = className.substring(prefix.length());
        int firstDot = remainder.indexOf('.');
        if (firstDot <= 0) {
            // Root class directly under base package
            return Optional.empty();
        }

        return Optional.of(remainder.substring(0, firstDot));
    }

    /**
     * Resolves the module name for a Class object relative to the base package.
     */
    public Optional<String> resolveModuleName(Class<?> clazz, String basePackage) {
        if (clazz == null) {
            return Optional.empty();
        }
        return resolveModuleName(clazz.getName(), basePackage);
    }

    /**
     * Checks whether a class resides directly in the root base package rather than a module.
     */
    public boolean isRootClass(String className, String basePackage) {
        if (!StringUtils.hasText(className) || !StringUtils.hasText(basePackage)) {
            return false;
        }

        String cleanBase = normalizeBasePackage(basePackage);
        String prefix = cleanBase + ".";

        if (!className.startsWith(prefix)) {
            return false;
        }

        String remainder = className.substring(prefix.length());
        return StringUtils.hasText(remainder) && !remainder.contains(".");
    }

    /**
     * Checks whether a Class resides directly in the root base package.
     */
    public boolean isRootClass(Class<?> clazz, String basePackage) {
        if (clazz == null) {
            return false;
        }
        return isRootClass(clazz.getName(), basePackage);
    }

    /**
     * Checks whether a class resides inside an internal package according to Spring Modulith conventions.
     */
    public boolean isInternal(String className) {
        if (!StringUtils.hasText(className)) {
            return false;
        }
        return className.contains(INTERNAL_SEGMENT)
                || className.endsWith(INTERNAL_SUFFIX)
                || className.startsWith(INTERNAL_PREFIX);
    }

    /**
     * Checks whether a Class resides inside an internal package.
     */
    public boolean isInternal(Class<?> clazz) {
        if (clazz == null) {
            return false;
        }
        return isInternal(clazz.getName());
    }

    /**
     * Checks whether an interface is a module boundary API (@NamedInterface or @ModulithBridgeTarget).
     */
    public boolean isModuleApi(Class<?> type) {
        if (type == null || !type.isInterface()) {
            return false;
        }

        if (AnnotatedElementUtils.hasAnnotation(type, NamedInterface.class)) {
            return true;
        }

        if (AnnotatedElementUtils.hasAnnotation(type, ModulithBridgeTarget.class)) {
            return true;
        }

        Package pkg = type.getPackage();
        return pkg != null && AnnotatedElementUtils.hasAnnotation(pkg, NamedInterface.class);
    }

    /**
     * Finds the first module API interface implemented by a class, if any.
     */
    public Optional<Class<?>> findModuleApiInterface(Class<?> clazz) {
        if (clazz == null) {
            return Optional.empty();
        }
        Class<?>[] interfaces = ClassUtils.getAllInterfacesForClass(clazz);
        for (Class<?> iface : interfaces) {
            if (isModuleApi(iface)) {
                return Optional.of(iface);
            }
        }
        return Optional.empty();
    }

    private String normalizeBasePackage(String basePackage) {
        String trimmed = basePackage.trim();
        while (trimmed.endsWith(".")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}

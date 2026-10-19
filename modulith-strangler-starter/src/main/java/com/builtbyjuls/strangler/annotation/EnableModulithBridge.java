package com.builtbyjuls.strangler.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.context.annotation.Import;

import com.builtbyjuls.strangler.config.ModulithBridgeAutoConfiguration;

/**
 * Enables Spring Modulith Strangler Bridge support and module slicing.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(ModulithBridgeAutoConfiguration.class)
public @interface EnableModulithBridge {

    /**
     * Root package containing application modules. If omitted, defaults to the
     * package of the annotated class or modulith.bridge.base-package.
     */
    String basePackage() default "";

    /**
     * Optional module name to host exclusively in this JVM process.
     */
    String hostOnly() default "";
}

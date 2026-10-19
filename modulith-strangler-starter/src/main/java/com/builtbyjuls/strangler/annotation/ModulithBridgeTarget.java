package com.builtbyjuls.strangler.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marker annotation for module boundary interfaces alongside Spring Modulith @NamedInterface.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ModulithBridgeTarget {

    /**
     * Optional explicit module name qualifier.
     */
    String value() default "";
}

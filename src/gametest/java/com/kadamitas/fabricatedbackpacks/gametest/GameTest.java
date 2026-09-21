package com.kadamitas.fabricatedbackpacks.gametest;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Loader-neutral test specification; NativeGameTests registers every annotated function. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface GameTest {
    String structure();
    int maxTicks() default 100;
    int padding() default 0;
}

package com.fluidnotions.camunda7support;



import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface CamundaSubscription {
    String topic();

    String[] arguments() default {};

    String qualifier() default "";

    String result();

    String returnValueProperty() default "";

    Class[] argumentTypes() default {};
}




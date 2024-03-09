package com.fluidnotions.camundaflow.annotations;



import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface CamundaSubscription {
    String topic();

    Argument[] arguments();

    String qualifier() default "";

    String result();

    String returnValueProperty() default "";

}




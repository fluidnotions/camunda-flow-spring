package com.fluidnotions.camunda7support;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.client.ExternalTaskClient;
import org.camunda.bpm.client.task.ExternalTask;
import org.camunda.bpm.engine.variable.VariableMap;
import org.camunda.bpm.engine.variable.Variables;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.beans.BeanInfo;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
@Component
public class CamundaSubscriptionInitializer implements ApplicationListener<ApplicationReadyEvent> {

    @Value("${camunda.bpm.client.base-url}")
    private String camundaBaseUrl;

    @Value("${cam.service-task.lock-duration:30000}")
    private Long lockDuration;

    private final ExternalTaskClient taskClient;

    @Override
    public void onApplicationEvent(final ApplicationReadyEvent event) {
        ApplicationContext applicationContext = event.getApplicationContext();
        Map<String, Object> beans = applicationContext.getBeansWithAnnotation(CamundaWorker.class);
        if(isCamundaAccessible()){
            for (Object bean : beans.values()) {
                Class<?> targetClass = (AopUtils.isAopProxy(bean) ? AopUtils.getTargetClass(bean) : bean.getClass());
                log.debug("Checking bean: " + targetClass.getName());
                Method[] methods = targetClass.getMethods();
                for (Method method : methods) {
                    CamundaSubscription annotation = method.getAnnotation(CamundaSubscription.class);
                    if (annotation != null) {
                        log.debug("Wrapping method in camunda subscription handler: " + method.getName());
                        String topic = annotation.topic();
                        String[] arguments = annotation.arguments();
                        taskClient.subscribe(topic).lockDuration(lockDuration).handler((externalTask, externalTaskService) -> {
                            try {
                                var args = retrievePropertyValues(externalTask, arguments);
                                Object result = method.invoke(bean, args);
                                VariableMap variableMap = toVariableMap(result);
                                externalTaskService.complete(externalTask, variableMap);
                            } catch (Throwable e) {
                                log.error("Task triggered by subscription to topic {} failed".formatted(topic), e);
                                externalTaskService.handleFailure(externalTask, "Task triggered by subscription to topic %s failed".formatted(topic), e.getMessage(), 0, 0);
                            }
                        }).open();
                    }
                }
            }
        }else{
            log.warn("Camunda {} is not accessible, subscription set initialization aborted", camundaBaseUrl);
        }
    }

    private <T> VariableMap toVariableMap(T object) {
        VariableMap variableMap = Variables.createVariables();
        try{

            if(object instanceof Map){
                variableMap = Variables.fromMap((Map<String, Object>) object);
            }else {
                BeanInfo info = Introspector.getBeanInfo(object.getClass());
                for (PropertyDescriptor pd : info.getPropertyDescriptors()) {
                    Method reader = pd.getReadMethod();
                    if (reader != null && !pd.getName().equals("class")) {
                        Object value = reader.invoke(object);
                        if (value != null) {
                            variableMap.putValue(pd.getName(), reader.invoke(object));
                        }
                    }
                }
            }
        }catch (Exception e){
            throw new RuntimeException("Return value of the method should be a Map or a POJO", e);
        }
        return  variableMap;
    }

    private Object[] retrievePropertyValues(ExternalTask externalTask, String[] propertyNames) {
        var varMap = externalTask.getAllVariablesTyped();
        List<Object> values = Arrays.stream(propertyNames)
                .map(varMap::get)
                .collect(Collectors.toList());
        return values.toArray();
    }

    private boolean isCamundaAccessible() {
        try {
            URL url = new URL(camundaBaseUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.getResponseCode();
            conn.disconnect();
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}


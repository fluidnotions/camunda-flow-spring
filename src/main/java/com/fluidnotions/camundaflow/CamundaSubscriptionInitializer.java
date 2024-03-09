package com.fluidnotions.camundaflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluidnotions.camundaflow.annotations.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.client.ExternalTaskClient;
import org.camunda.bpm.client.impl.EngineClientException;
import org.camunda.bpm.client.task.ExternalTask;
import org.camunda.bpm.client.variable.ClientValues;
import org.camunda.bpm.engine.variable.VariableMap;
import org.camunda.bpm.engine.variable.Variables;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@RequiredArgsConstructor
@Component
public class CamundaSubscriptionInitializer implements ApplicationListener<ApplicationReadyEvent> {

    @Value("${camunda.bpm.client.base-url}")
    private String camundaBaseUrl;

    @Value("${cam.service-task.lock-duration:30000}")
    private Long lockDuration;

    @Value("${camunda.bpm.client.json-value-transient:true}")
    private String jsonValueTransient;

    @Value("${camunda.bpm.client.base-url}")
    private String basePath;

    private final ObjectMapper objectMapper;
    private Map<String, Object> beans;
    private ExternalTaskClient taskClient;

    @Override
    public void onApplicationEvent(final ApplicationReadyEvent event) {
        ApplicationContext applicationContext = event.getApplicationContext();
        beans = applicationContext.getBeansWithAnnotation(CamundaWorker.class);
        try {
            taskClient = ExternalTaskClient.create().baseUrl(basePath).asyncResponseTimeout(10000).build();
            retryUntilSuccess(taskClient);
        } catch (EngineClientException e) {
            log.error("Will execute retryUntilSuccess and try setup again", e);
            retryUntilSuccess(taskClient);
        }
    }



    private CompletableFuture<?> retryUntilSuccess(ExternalTaskClient taskClient) {
        return CompletableFuture.runAsync(() -> {
            scanBeansForFlowAnnotations(taskClient);
        }).thenApply(v -> null).exceptionally(e -> {
            try {
                TimeUnit.SECONDS.sleep(5);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            return retryUntilSuccess(taskClient).join();
        });
    }

    public void scanBeansForFlowAnnotations(ExternalTaskClient taskClient) {
        if (isCamundaAccessible()) {
            log.info("Camunda %s is accessible, scanning beans for flow annotations".formatted(camundaBaseUrl));
            for (Object bean : beans.values()) {
                Class<?> targetClass = (AopUtils.isAopProxy(bean) ? AopUtils.getTargetClass(bean) : bean.getClass());
                log.debug("Checking bean: " + targetClass.getName());
                Method[] methods = targetClass.getMethods();
                for (Method method : methods) {
                    this.createSubscription(method, bean, taskClient);
                }
            }
        }
        else {
            log.warn("Camunda %s is not accessible, subscription set initialization aborted, will retry".formatted(camundaBaseUrl));
            throw new RuntimeException("Camunda %s is not accessible, subscription set initialization aborted, will retry".formatted(camundaBaseUrl));
        }
    }

    public void createSubscription(Method method, Object bean, ExternalTaskClient taskClient) {
        CamundaSubscription annotation = method.getAnnotation(CamundaSubscription.class);
        if (annotation != null) {
            log.debug("Wrapping method in camunda subscription handler: " + method.getName());
            String topic = annotation.topic();
            Argument[] arguments = annotation.arguments();
            QualifierConditional qualifierConditional = buildQualifierConditional(annotation);
            String resultVariableName = annotation.result();
            String returnValueProperty = annotation.returnValueProperty();

            taskClient.subscribe(topic).lockDuration(lockDuration).handler((externalTask, externalTaskService) -> {
                try {
                    if (!evalQualifierConditional(qualifierConditional, externalTask)) {
                        log.debug("Task triggered by subscription to topic {} ignored because qualifier {} does not match", topic, qualifierConditional);
                        return;
                    }
                    var args = convertArguments(arguments, externalTask.getAllVariablesTyped());

                    Object result = method.invoke(bean, args);
                    if (!returnValueProperty.isEmpty()) {
                        result = getFieldValue(result, returnValueProperty);
                    }
                    var variableMap = returnValueToVariableMap(result, resultVariableName);
                    externalTaskService.complete(externalTask, variableMap);
                } catch (Throwable e) {
                    log.error("Task triggered by subscription to topic " + topic + " failed", e);
                    externalTaskService.handleFailure(externalTask, "Task triggered by subscription to topic %s failed".formatted(topic), e.getMessage(), 0, 0);
                }
            }).open();

        }
    }

    record QualifierConditional(String variable, String[] values, Boolean equals) {}

    private QualifierConditional buildQualifierConditional(CamundaSubscription annotation) {
        try {
            if (!annotation.qualifier().contains("!=")) {
                String variable = annotation.qualifier().split("=")[0];
                String[] values = annotation.qualifier().split("=")[1].split(",");
                return new QualifierConditional(variable, values, true);
            }else{
                String variable = annotation.qualifier().split("!=")[0];
                String[] values = annotation.qualifier().split("!=")[1].split(",");
                return new QualifierConditional(variable, values, false);
            }

        } catch (Exception e) {
            log.error("Error building qualifier conditional", e);
            return null;
        }
    }

    private Boolean evalQualifierConditional(QualifierConditional qualifierConditional, ExternalTask externalTask) {
        if (qualifierConditional == null) {
            return true;
        }
        try {
            Object taskVariableValue;
            long longOfValue;
            try {
                if (!qualifierConditional.variable().contains(".")) {
                    taskVariableValue = externalTask.getVariable(qualifierConditional.variable());
                }else{
                    String[] parts = qualifierConditional.variable().split("\\.");
                    taskVariableValue = externalTask.getVariable(parts[0]);
                    for(int i = 1; i < parts.length; i++){
                        if(taskVariableValue instanceof Map map){
                            taskVariableValue = map.get(parts[i]);
                        }
                    }
                }

                longOfValue = Long.parseLong(taskVariableValue.toString());
            } catch (NullPointerException e) {
                longOfValue = 0L;
            }
            Boolean eq = qualifierConditional.equals();
            Long finalLongOfValue = longOfValue;
            return Arrays.stream(qualifierConditional.values()).anyMatch(value -> {
                Long longValue = value.equals("null")? 0L: Long.valueOf(value);
                if (eq) {
                    return longValue.equals(finalLongOfValue);
                } else {
                    return !longValue.equals(finalLongOfValue);
                }
            });
        } catch (Exception e) {
            log.error("Error evaluating qualifier conditional", e);
            return true;

        }
    }

    public VariableMap returnValueToVariableMap(Object result, String resultVariableName) throws JsonProcessingException {
        VariableMap variableMap;
        if (result == null) {
            variableMap = Variables.createVariables().putValue(resultVariableName, null);
        }
        else if (result.getClass() == byte[].class) {
            variableMap = Variables.createVariables().putValue(resultVariableName, ClientValues.byteArrayValue((byte[]) result));
        } else if (result instanceof String) {
            variableMap = Variables.createVariables().putValue(resultVariableName, ClientValues.stringValue((String) result));
        } else if (result instanceof Long) {
            variableMap = Variables.createVariables().putValue(resultVariableName, ClientValues.longValue((Long) result));
        } else {
            String json = objectMapper.writeValueAsString(result);
            variableMap = Variables.createVariables().putValue(resultVariableName, ClientValues.jsonValue(json, jsonValueTransient.equals("true")));
        }
        return variableMap;
    }


    public Object[] convertArguments(Argument[] arguments, Map<String, Object> variableMap) {
        List<Object> convertedArgs = new ArrayList<>(arguments.length);

        for (Argument argument : arguments) {
            String argName = argument.name();
            Object argValue = variableMap.get(argName);
            log.debug("argName {}, argValue {}. variableMap.has {}", argName, argValue, variableMap.containsKey(argName));

            Object convertedArgValue = convertArgumentValue(argument, argValue);
            convertedArgs.add(convertedArgValue);
        }

        return convertedArgs.toArray();
    }

    private Object convertArgumentValue(Argument argument, Object argValue) {
        switch (argument.parsingType()) {
            case BYTES_TO_STRING:
                return new String((byte[]) argValue);
            case BASE64_TO_STRING:
                return new String(Base64.getDecoder().decode((String) argValue));
            case STRING_TO_POJO:
            case BYTES_TO_POJO:
                return deserialize(argument, argValue);
            case NUMBER_TO_STRING:
                return String.valueOf(argValue);
            case DEFAULT:
                // Handle the case where argValue is a Number differently to avoid reassignment
                if (argValue instanceof Number) {
                    log.warn("No conversion found for argument {}, {}. But since it is a Number, assuming it's a Long", argument.parsingType(), argument.name());
                    return Long.valueOf(argValue.toString());
                }
        }
        log.debug("Argument has no conversion, therefore conversion skipped");
        return argValue;
    }


    public Object deserialize(Argument argument, Object argValue) {
        ArgumentParsingType conversionType = argument.parsingType();
        String argName = argument.name();
        Class<?> initialClazz = argument.convertToClass();
        Class<?> clazz = (initialClazz == UnsetClass.class)
                ? Map.class
                : initialClazz;

        if (clazz == Map.class) {
            log.warn("({}) No class found for argument {}. Assuming it's a map", conversionType, argName);
        }
        try {
            if (conversionType == ArgumentParsingType.BYTES_TO_POJO) {
                return objectMapper.readValue((byte[]) argValue, clazz);
            } else {
                return objectMapper.readValue((String) argValue, clazz);
            }
        } catch (IOException e) {
            log.error("Error while converting {} to object for {}:{}", conversionType, argName, conversionType, e);
            return null;
        }
    }


    private Object getFieldValue(Object obj, String fieldName) {
        try {
            Field field = obj.getClass().getDeclaredField(fieldName);
            field.setAccessible(true); // Necessary for accessing private fields
            return field.get(obj);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            log.error("Error while getting field value", e);
            return null;
        }
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


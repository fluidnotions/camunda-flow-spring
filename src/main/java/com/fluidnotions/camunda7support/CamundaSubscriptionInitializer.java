package com.fluidnotions.camunda7support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.client.ExternalTaskClient;
import org.camunda.bpm.client.impl.EngineClientException;
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
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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

    private ObjectMapper objectMapper = new ObjectMapper();



    @Override
    public void onApplicationEvent(final ApplicationReadyEvent event) {
        ApplicationContext applicationContext = event.getApplicationContext();
        beans = applicationContext.getBeansWithAnnotation(CamundaWorker.class);
        ExternalTaskClient taskClient = null;
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
            log.debug("Camunda %s is accessible, scanning beans for flow annotations".formatted(camundaBaseUrl));
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
            String[] arguments = annotation.arguments();
            Class[] argumentTypes = annotation.argumentTypes();
            Map<String, Class<?>> argumentNamesAndTypes = this.zipArrays(arguments, argumentTypes);
            String qualifier = annotation.qualifier();
            String resultVariableName = annotation.result();
            String returnValueProperty = annotation.returnValueProperty();

            taskClient.subscribe(topic).lockDuration(lockDuration).handler((externalTask, externalTaskService) -> {
                try {
                    String taskVariableQualifier = externalTask.getVariable("qualifier");
                    if (!qualifier.isEmpty() && taskVariableQualifier != null && !taskVariableQualifier.isEmpty() && !qualifier.equals(taskVariableQualifier)) {
                        log.debug("Task triggered by subscription to topic {} ignored because qualifier {} does not match {}", topic, taskVariableQualifier, qualifier);
                        return;
                    }
                    var args = convertArguments(argumentNamesAndTypes, externalTask.getAllVariablesTyped());

                    Object result = method.invoke(bean, args);
                    if (!returnValueProperty.isEmpty()) {
                        result = getFieldValue(result, returnValueProperty);
                    }
                    var variableMap = returnValueToVariableMap(result, resultVariableName);
                    externalTaskService.complete(externalTask, variableMap);
                } catch (Throwable e) {
                    log.error("Task triggered by subscription to topic {} failed".formatted(topic), e);
                    externalTaskService.handleFailure(externalTask, "Task triggered by subscription to topic %s failed".formatted(topic), e.getMessage(), 0, 0);
                }
            }).open();

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


    public Object[] convertArguments(Map<String, Class<?>> arguments, Map<String, Object> variableMap) {
        Map<String, String> argAndConversionTypes = arguments.entrySet().stream()
                .collect(Collectors.toMap(
                        entry -> {
                            String[] parts = entry.getKey().split(":", 2);
                            return parts[0];
                        },
                        entry -> {
                            String[] parts = entry.getKey().split(":", 2);
                            return parts[1];
                        },
                        (existingValue, newValue) -> existingValue,
                        LinkedHashMap::new
                ));

        List<Object> convertedArgs = new ArrayList<>(arguments.size());

        for (Map.Entry<String, String> argAndConversionType : argAndConversionTypes.entrySet()) {
            String argName = argAndConversionType.getKey();
            String conversionType = argAndConversionType.getValue();
            Object argValue = variableMap.get(argName);
            log.debug("argName {}, argValue {}. variableMap.has {}", argName, argValue, variableMap.containsKey(argName));

            Object convertedArgValue = null;
            switch (conversionType) {
                case "bytes->string":
                    convertedArgValue = new String((byte[]) argValue);
                    break;
                case "base64->string":
                    convertedArgValue = Base64.getDecoder().decode((String) argValue);
                    break;
                case "string->pojo":
                case "bytes->pojo":
                    Class<?> clazz = arguments.get(argName + ":" + conversionType);
                    if (clazz == null) {
                        log.warn("({}) No class found for argument {}. Assuming it's a map", conversionType, argName);
                        clazz = Map.class;
                    }
                    try {
                        if (conversionType.equals("jsonBytes")) {
                            convertedArgValue = objectMapper.readValue((byte[]) argValue, clazz);
                        } else {
                            convertedArgValue = objectMapper.readValue((String) argValue, clazz);
                        }
                    } catch (IOException e) {
                        log.error("Error while converting {} to object for {}:{}", conversionType, argName, conversionType, e);
                    }
                    break;
                case "number->string":
                    convertedArgValue = String.valueOf(argValue);
                    break;
                default:
                    if (argValue instanceof Number) {
                        argValue = Long.valueOf(argValue.toString());
                        log.warn("No conversion found for argument {}. But since it is a Number, assuming it's a Long", conversionType, argName);
                    }
                    else {
                        log.debug("Argument has no conversion, therefore conversion skipped");
                    }
                    convertedArgValue = argValue;
            }

            convertedArgs.add(convertedArgValue);
        }

        return convertedArgs.toArray(new Object[convertedArgs.size()]);
    }

    private Object getFieldValue(Object obj, String fieldName) {
        try {
            Field field = obj.getClass().getDeclaredField(fieldName);
            field.setAccessible(true); // Necessary for accessing private fields
            return field.get(obj);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            e.printStackTrace();
            return null;
        }
    }

    private Map<String, Class<?>> zipArrays(String[] names, Class<?>[] classes) {
        Map<String, Class<?>> map = new LinkedHashMap<>();
        IntStream.range(0, names.length)
                .forEach(i -> {
                    if (i < classes.length) {
                        map.put(names[i], classes[i]);
                    } else {
                        map.put(names[i], null);
                    }
                });
        return map;
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


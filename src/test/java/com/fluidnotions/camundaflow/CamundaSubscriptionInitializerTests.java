package com.fluidnotions.camundaflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.camunda.bpm.engine.variable.VariableMap;
import org.camunda.bpm.engine.variable.Variables;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

public class CamundaSubscriptionInitializerTests {

    static final Logger log = LoggerFactory.getLogger(CamundaSubscriptionInitializerTests.class);

    record TestRecord(String test){}

    @Test
    void convertArguments() throws JsonProcessingException {
        CamundaSubscriptionInitializer camundaSubscriptionInitializer = new CamundaSubscriptionInitializer(null);
        ObjectMapper objectMapper = new ObjectMapper();
        Map<String, Class<?>> arguments = new HashMap<>();
        arguments.put("test3:json", TestClass.class);
        arguments.put("test", null);
        arguments.put("test2:base64", null);
        VariableMap variableMap = Variables.createVariables();
        variableMap.put("test", "test");
        variableMap.put("test2", this.generateByteArray());
        var json = objectMapper.writeValueAsString(new TestRecord("test"));
        log.info("json: {}", json);
        variableMap.put("test3", json);
        var args = camundaSubscriptionInitializer.convertArguments(arguments, variableMap);
        for(var arg : args){
            log.info("arg class name: {}", arg.getClass().getName());
        }
    }

    private String generateByteArray(){
        byte[] bytes = {72, 101, 108, 108, 111, 44, 32, 87, 111, 114, 108, 100, 33};
        // Encode the byte array to Base64 string
        String base64String = Base64.getEncoder().encodeToString(bytes);
        log.info("Base64 String: " + base64String);
        byte[] decodedBytes = Base64.getDecoder().decode(base64String);
        var decoded = "Decoded Bytes: ";
        for (byte b : decodedBytes) {
            decoded += b + " ";
        }
        return base64String;
    }



    @Test
     void deserializePojo(){
        CamundaSubscriptionInitializer camundaSubscriptionInitializer = new CamundaSubscriptionInitializer(null);
        String argName = "test";
        String conversionType = "string->pojo";
        Object argValue = """
                          {"supplierkey":9,"supplierreference":"239473847289","quotenumber":"VOF021046-VOD-DFA_BOQ_V14_20231020","versione":1,"createdby":-1,"orderrequestkey":13147,"cancellationrequested":false,"id":34013,"orderrequesttypekey":16,"createdon":"2023-10-20T17:53:19.7264258","quotetypekey":14,"buildcost":1508531}
                          """;
        Map<String, Class<?>> arguments = Map.of(argName + ":" + conversionType, TestClass.ProcessContextQuote.class);
        var pcq = camundaSubscriptionInitializer.deserialize(arguments, argName, conversionType, argValue);
        log.info("pcq: {}", pcq);
        assert pcq instanceof TestClass.ProcessContextQuote;
    }
}

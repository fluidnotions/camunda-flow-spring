package camunda7support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluidnotions.camunda7support.CamundaSubscriptionInitializer;
import org.camunda.bpm.engine.variable.VariableMap;
import org.camunda.bpm.engine.variable.Variables;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

public class CamundaSubscriptionInitializerTest {

    static final Logger log = LoggerFactory.getLogger(CamundaSubscriptionInitializerTest.class);

    @Test
    public void testConvertArguments() throws JsonProcessingException {
        CamundaSubscriptionInitializer camundaSubscriptionInitializer = new CamundaSubscriptionInitializer(null);
        ObjectMapper objectMapper = new ObjectMapper();
        Map<String, Class<?>> arguments = new HashMap<>();
        arguments.put("test3:json", TestClass.class);
        arguments.put("test", null);
        arguments.put("test2:base64", null);
        VariableMap variableMap = Variables.createVariables();
        variableMap.put("test", "test");
        variableMap.put("test2", this.generateByteArray());
        var json = objectMapper.writeValueAsString(new TestClass("test"));
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

    public void testConvertReturnValueToString(){
        CamundaSubscriptionInitializer camundaSubscriptionInitializer = new CamundaSubscriptionInitializer(null);
    }
}

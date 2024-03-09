package com.fluidnotions.camundaflow;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fluidnotions.camundaflow.annotations.Argument;
import com.fluidnotions.camundaflow.annotations.ArgumentParsingType;
import com.fluidnotions.camundaflow.annotations.CamundaSubscription;
import com.fluidnotions.camundaflow.annotations.CamundaWorker;


public class UsageExample {

    @CamundaWorker
    static class ExampleBean {

        @CamundaSubscription(topic = "test", arguments = {
                @Argument(name = "arg1", parsingType = ArgumentParsingType.STRING_TO_POJO, convertToClass = ObjectNode.class)
        }, result = "result")
        public void test(ObjectNode arg1) {}
    }
}

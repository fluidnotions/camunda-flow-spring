# Enhanced Camunda Subscription Annotations

Introducing a seamless way to integrate Camunda service tasks without tightly binding your code to the Camunda API by having to define handlers for service tasks. This approach improves how you can manage your service tasks, making them more maintainable, unit testable, readable, and reducing boilerplate.
It currently uses camunda's External Task Client to handle the subscription and execution of the service task. But in future we hope to add support for kafka and other messaging systems.

### Usage:

* provide `camunda.bpm.client.base-url` in application.properties

## Key Advantages:
1. **Simplicity in Code**: Just annotate your existing methods within `@Service`, `@Component`, or any Spring bean, without having to refactor or modify them to fit Camunda's requirements.
2. **Automatic Handler Creation**: At startup, our initializer scans the application and automatically wraps the annotated method with a Camunda-compatible handler. This eliminates the need for manually crafting Camunda-specific handlers.
3. **Reduced Testing Overhead**: No more writing extra tests for Camunda-specific handlers. Since your business logic remains unchanged, only standard tests for those methods are needed.
4. **Preserved Method Usability**: Despite being wrapped for Camunda, the methods can still be invoked traditionally, ensuring that the original functionality remains untouched.

By using this library, you ensure a cleaner integration with Camunda, minimizing the disruption to your existing codebase and maximizing your development efficiency.
This approach allows for a seamless integration with Camunda, letting developers focus on business logic rather than the intricacies of the Camunda API.

### Example usage:

```java
@CamundaSubscription(
        topic = "send-email-task",
        qualifier = "requestType",
        result = "response",
        arguments = {
                @Argument(name = "requestData", parsingType = ArgumentParsingType.BYTES_TO_POJO, convertToClass = JsonNode.class),
                @Argument(name = "attachmentBytes"),
                @Argument(name = "recipientEmail"),
                @Argument(name = "templateName")
        }
)
public byte[] sendEmail(JsonNode requestData, String attachmentBytes, String recipientEmail, String templateName) {
  Long requestTypeId = requestData.get("requestType").get("id").asLong();
  String[] recipients = emailRecipients.contains(";") ? emailRecipients.split(";") : new String[]{emailRecipients};
  String emailSubject = determineSubject(requestTypeId, templateName);
  var emailAttachment = new EmailClient.EmailAttachment("quote-attachment", attachmentBase64);
  var uuid = UUID.randomUUID().toString();
  var emailRequest = new EmailClient.EmailRequest(uuid, emailSubject, recipientEmail, recipients, new EmailClient.EmailAttachment[]{emailAttachment}, null, null);
  var response = send(emailRequest);

  logger.info("Response received: " + response);

  return response.getBytes();
}

```

### Annotation Explanation:

- `@CamundaSubscription`: This is the main annotation that signifies that the method is tied to a Camunda subscription.

    - `topic`: Specifies the Camunda topic that this subscription listens to.
    - `qualifier`: Used to filter below the topic level. Multiple methods may listen to a topic but in some cases business logic requires that only some are invoked. This is in the form of `variable=value`,`variable=value1,value2` or `variable!=value`,`variable!=value1,value2` . For example, if the topic is `send-email-task` and the qualifier is `requestTypeKey=100`, then the subscription will only be invoked if the variable `requestType` is equal to `100` or in the case of `requestTypeKey!=100` the variable `requestType` is not equal to `100`. Or a comma seperated list of values, which are assumed to be numbers of type Long
    - `result`: Defines the variable name in which the method’s return will be stored in Camunda.
    - `arguments`: An array that defines the parameters being passed to the method. It's a way to map input data to method arguments. With some basic parsing, using the @Argument nested annotation.

### Argument Nested Annotation:

The `arguments` attribute of the `@CamundaSubscription` annotation allows for detailed specification of how each argument should be parsed before being passed to the method. This is achieved using the `@Argument` annotation within the `arguments` array. Each `@Argument` can specify the name of the variable, the parsing type, and optionally the class type to which it should be converted.

- `@Argument(name = "argName", parsingType = ArgumentParsingType.BYTES_TO_STRING)`:
  - Specifies that the argument named `argName` should be parsed from a byte array (`byte[]`) to a `String`.

- `@Argument(name = "argName", parsingType = ArgumentParsingType.BASE64_TO_STRING)`:
  - Indicates that the argument named `argName` should be decoded from a Base64 encoded string to its original string value.

- `@Argument(name = "argName", parsingType = ArgumentParsingType.STRING_TO_POJO, convertToClass = TargetClass.class)` and `@Argument(name = "argName", parsingType = ArgumentParsingType.BYTES_TO_POJO, convertToClass = TargetClass.class)`:
  - These configurations are used to convert a string or byte array respectively to an instance of `TargetClass`. The `convertToClass` attribute specifies the target class for conversion. If `convertToClass` is not specified, a `Map` is assumed by default.

- `@Argument(name = "argName", parsingType = ArgumentParsingType.NUMBER_TO_STRING)`:
  - Converts a number (in string or numerical format) to its string representation.

- `@Argument(name = "argName")`:
  - If the `argValue` is a number, converts it to a `Long` and logs a warning.
  - Otherwise, it logs conversion has been skipped.

### Caveats:

- The library is a bit ruff around the edges and will be improved over time.
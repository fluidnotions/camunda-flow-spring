# Enhanced Camunda Subscription Annotations

Introducing a seamless way to integrate Camunda service tasks without tightly binding your code to the Camunda API by having to define handlers for service tasks. This approach improves how you can manage your service tasks, making them more maintainable, unit testable, readable, and reducing boilerplate.
It currently uses camunda's External Task Client to handle the subscription and execution of the service task. But in future we hope to add support for kafka and other messaging systems.

## Key Advantages:
1. **Simplicity in Code**: Just annotate your existing methods within `@Service`, `@Component`, or any Spring bean, without having to refactor or modify them to fit Camunda's requirements.
2. **Automatic Handler Creation**: At startup, our initializer scans the application and automatically wraps the annotated method with a Camunda-compatible handler. This eliminates the need for manually crafting Camunda-specific handlers.
3. **Reduced Testing Overhead**: No more writing extra tests for Camunda-specific handlers. Since your business logic remains unchanged, only standard tests for those methods are needed.
4. **Preserved Method Usability**: Despite being wrapped for Camunda, the methods can still be invoked traditionally, ensuring that the original functionality remains untouched.

By using this library, you ensure a cleaner integration with Camunda, minimizing the disruption to your existing codebase and maximizing your development efficiency.

### Example usage:

```java
@CamundaSubscription(
    topic = "send-email-task", 
    qualifier = "requestType", 
    result = "response", 
    arguments = {"requestData:jsonBytes", "attachmentBytes", "recipientEmail", "templateName"}, 
    argumentTypes = {JsonNode.class}
)
public byte[] sendEmail(JsonNode requestData, byte[] attachmentBytes, byte[] recipientEmail, String templateName) {
    Long requestTypeId = requestData.get("requestType").get("id").asLong();
    
    // Convert the attachment bytes to Base64 string
    var attachmentBase64 = new String(Base64.getEncoder().encode(attachmentBytes));
    
    // Determine recipients based on environment
    var emailRecipients = determineRecipients();
    var recipients = emailRecipients.indexOf(";") > -1 ? emailRecipients.split(";") : new String[]{emailRecipients};
    
    // Determine email subject based on environment
    String emailSubject = determineSubject(requestTypeId, templateName);
    
    var emailAttachment = new EmailClient.EmailAttachment("quote-attachment", attachmentBase64);
    var uuid = UUID.randomUUID().toString();
    var emailRequest = new EmailClient.EmailRequest(uuid, emailSubject, new String(recipientEmail), recipients, new EmailClient.EmailAttachment[]{emailAttachment}, null, null);
    var response = send(emailRequest);

    logger.info("Response received: " + response);

    return response.getBytes();
}
```

### Annotation Explanation:

- `@CamundaSubscription`: This is the main annotation that signifies that the method is tied to a Camunda subscription.

    - `topic`: Specifies the Camunda topic that this subscription listens to.
    - `qualifier`: Used to filter below the topic level. Multiple methods may listen to a topic but in some cases business logic requires that only some are invoked.
    - `result`: Defines the variable name in which the method’s return will be stored in Camunda.
    - `arguments`: An array that defines the parameters being passed to the method. It's a way to map input data to method arguments. With some basic parsing. For example `:jsonBytes` indicates that the argument should be passed into a string but was stored as bytes due to it's length.
    - `argumentTypes`: An array that specifies the data types of each argument. This is used in the case of deserialization to a specific object type. Given the limitations of Annotations these correlate with the arguments array by index.

This approach allows for a seamless integration with Camunda, letting developers focus on business logic rather than the intricacies of the Camunda API.

### Caveats:

- The library is in early development and is not yet available on Maven Central. To use it, you must clone the repository and build it locally.
- The library is currently only compatible with Spring Boot applications.
- The library is a bit ruff around the edges and will be improved over time.
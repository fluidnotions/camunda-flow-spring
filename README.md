# Alternative to default Camunda Subscription Annotations

* The advantage to this implementation is you just anoint the existing method in the @Service, @Component or any spring bean. 
* The initializer scans the app at startup and wraps the method creating a handler around it. 
* So you don't need extra handlers for camunda or extra tests for them. 
* The method can still be called normally as well.
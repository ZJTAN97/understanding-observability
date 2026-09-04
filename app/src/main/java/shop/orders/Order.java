package shop.orders;

// Travels over AMQP as JSON. Nothing here mentions a trace; the traceparent
// rides in the message *headers*, which this record never sees.
public record Order(String id, String sku, int qty, String channel, String createdAt) {}

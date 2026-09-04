package shop.orders;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import jakarta.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Profile;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;

@Profile("worker")
@Component
class OrderListener {

  private static final Logger log = LoggerFactory.getLogger(OrderListener.class);
  private static final AttributeKey<String> STATUS = AttributeKey.stringKey("status");

  private final MongoTemplate mongo;
  private final MinioClient minio;
  private final AppProperties cfg;
  private final Telemetry telemetry;

  // Deliberate break #2: messages are parked on a queue and drained by a thread
  // started at boot, outside the listener call stack. The agent propagates
  // context across Executors it recognises - it cannot follow a hand-rolled
  // producer/consumer handoff, so every worker span becomes an orphan root.
  private final BlockingQueue<Order> pending = new LinkedBlockingQueue<>();

  OrderListener(MongoTemplate mongo, MinioClient minio, AppProperties cfg, Telemetry telemetry) {
    this.mongo = mongo;
    this.minio = minio;
    this.cfg = cfg;
    this.telemetry = telemetry;
  }

  @PostConstruct
  void startDrainThread() {
    if (!cfg.detachContext()) {
      return;
    }
    log.warn("APP_DETACH_CONTEXT=true - processing outside the listener call stack");
    Thread.ofPlatform().daemon().name("detached-drain").start(() -> {
      while (true) {
        try {
          handle(pending.take());
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    });
  }

  // The agent extracts `traceparent` from the message headers and makes the
  // CONSUMER span current for the duration of this method. Everything called
  // from inside inherits it. Everything handed to another thread does not.
  @RabbitListener(queues = "${app.queue}")
  void onOrder(Order order) {
    if (cfg.detachContext()) {
      pending.add(order);
      return;
    }
    handle(order);
  }

  private void handle(Order order) {
    long started = System.nanoTime();
    String status = "ok";
    try {
      // 1. insert  2. put receipt  3. update - three CLIENT spans, in this order
      var doc = new Document("id", order.id())
          .append("sku", order.sku())
          .append("qty", order.qty())
          .append("channel", order.channel())
          .append("createdAt", order.createdAt())
          .append("status", "received");
      mongo.getCollection("orders").insertOne(doc);

      String key = order.id() + ".json";
      byte[] receipt = ("{\"order.id\":\"" + order.id() + "\"}").getBytes(StandardCharsets.UTF_8);
      minio.putObject(PutObjectArgs.builder()
          .bucket(cfg.s3().bucket())
          .object(key)
          .stream(new ByteArrayInputStream(receipt), receipt.length, -1L)
          .contentType("application/json")
          .build());

      // A cheap way to make some traces interesting: oversized orders fail late,
      // after the receipt was already written.
      if (order.qty() > 90) {
        throw new IllegalStateException("qty " + order.qty() + " exceeds per-order limit");
      }

      mongo.getCollection("orders").updateOne(
          new Document("_id", doc.get("_id")),
          new Document("$set", new Document("status", "receipted").append("receiptKey", key)));
      log.info("order receipted order.id={} receipt.key={}", order.id(), key);
    } catch (Exception e) {
      status = "error";
      // The agent marks a span ERROR only when the method it instrumented throws.
      // This exception is caught here, so `orders.created process` would end UNSET
      // and the failure would exist only in the log line and the metric label -
      // invisible to {status=error} in Tempo. A caught exception is not a control
      // flow the agent can see, and these two lines are the whole remedy.
      Span span = Span.current();
      span.recordException(e);
      span.setStatus(StatusCode.ERROR, e.getMessage());
      log.error("order processing failed order.id={}", order.id(), e);
    } finally {
      double seconds = (System.nanoTime() - started) / 1e9;
      telemetry.orderProcessing.record(seconds, Attributes.of(STATUS, status));
    }
  }
}

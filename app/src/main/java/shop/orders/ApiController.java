package shop.orders;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Profile("api")
@RestController
class ApiController {

  private static final Logger log = LoggerFactory.getLogger(ApiController.class);
  private static final AttributeKey<String> CHANNEL = AttributeKey.stringKey("channel");
  // Deliberate break #4: order.id is unique per request. One label, one series
  // per order, forever. See phase-3/README.md.
  private static final AttributeKey<String> ORDER_ID = AttributeKey.stringKey("order.id");

  private final RabbitTemplate rabbit;
  private final AppProperties cfg;
  private final Telemetry telemetry;

  ApiController(RabbitTemplate rabbit, AppProperties cfg, Telemetry telemetry) {
    this.rabbit = rabbit;
    this.cfg = cfg;
    this.telemetry = telemetry;
  }

  @PostMapping("/orders")
  ResponseEntity<Map<String, String>> create(@RequestBody OrderRequest req) {
    // A deliberate 400 path so the dashboards have a non-zero error rate.
    if (req.sku() == null || req.sku().isBlank() || req.qty() == null || req.qty() <= 0) {
      return ResponseEntity.badRequest().body(Map.of("error", "sku and positive qty are required"));
    }

    var order = new Order(
        UUID.randomUUID().toString(),
        req.sku(),
        req.qty(),
        req.channel() == null ? "web" : req.channel(),
        Instant.now().toString());

    // The agent's rabbitmq instrumentation injects `traceparent` into these
    // message headers on publish. That header is the entire mechanism by which
    // the worker's spans end up in this trace instead of starting their own.
    rabbit.convertAndSend(cfg.exchange(), cfg.routingKey(), order);

    telemetry.ordersCreated.add(1, cfg.highCardinalityLabel()
        ? Attributes.of(CHANNEL, order.channel(), ORDER_ID, order.id())
        : Attributes.of(CHANNEL, order.channel()));
    log.info("published orders.created order.id={} order.sku={}", order.id(), order.sku());

    return ResponseEntity.accepted().body(Map.of("id", order.id()));
  }
}

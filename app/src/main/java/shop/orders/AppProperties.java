package shop.orders;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app")
public record AppProperties(
    String exchange,
    String routingKey,
    String queue,
    // "classic" or "quorum". Phase 3 clusters RabbitMQ and switches to quorum.
    String queueType,
    S3 s3,
    // Set to true for deliberate break #2. See OrderListener.
    boolean detachContext,
    // Set to true for phase 3's deliberate break #4. See ApiController.
    boolean highCardinalityLabel) {

  public record S3(String endpoint, String bucket, String accessKey, String secretKey) {}
}

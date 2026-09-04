package shop.orders;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app")
public record AppProperties(
    String exchange,
    String routingKey,
    String queue,
    S3 s3,
    // Set to true for deliberate break #2. See OrderListener.
    boolean detachContext) {

  public record S3(String endpoint, String bucket, String accessKey, String secretKey) {}
}

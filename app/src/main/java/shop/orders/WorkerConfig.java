package shop.orders;

import io.minio.MinioClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Profile("worker")
@Configuration
class WorkerConfig {

  // The MinIO client speaks S3 but is not the AWS SDK, so the agent has no
  // aws-sdk instrumentation to apply. What it does have is the HTTP client
  // MinIO uses underneath - see the span name in the waterfall.
  @Bean
  MinioClient minioClient(AppProperties cfg) {
    return MinioClient.builder()
        .endpoint(cfg.s3().endpoint())
        .credentials(cfg.s3().accessKey(), cfg.s3().secretKey())
        .build();
  }
}

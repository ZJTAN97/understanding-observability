package shop.orders;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

// One jar, two services. Which one it is comes from SPRING_PROFILES_ACTIVE,
// and the telemetry identity comes from OTEL_SERVICE_NAME - neither is in the
// code, which is the whole reason api and worker share this build.
@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class OrdersApplication {
  public static void main(String[] args) {
    SpringApplication.run(OrdersApplication.class, args);
  }
}

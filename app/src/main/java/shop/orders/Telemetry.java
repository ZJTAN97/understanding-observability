package shop.orders;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import org.springframework.stereotype.Component;

// The agent installs the real SDK into GlobalOpenTelemetry before Spring starts.
// Without -javaagent this resolves to a no-op implementation: the code still
// runs, the numbers go nowhere, and nothing throws. That is break #1's signature.
@Component
class Telemetry {

  private final Meter meter = GlobalOpenTelemetry.get()
      .getMeterProvider()
      .meterBuilder("shop")
      .setInstrumentationVersion("0.1.0")
      .build();

  // Counter: how many. Labels are deliberately low-cardinality - `channel` has
  // three values forever. `order.id` here would be one series per order.
  final LongCounter ordersCreated = meter.counterBuilder("orders.created")
      .setDescription("Orders accepted by the API")
      .setUnit("{order}")
      .build();

  // Histogram: how long. Buckets are chosen by the SDK; the label set is again
  // bounded - status is ok|error, nothing else.
  final DoubleHistogram orderProcessing = meter.histogramBuilder("order.processing.duration")
      .setDescription("Time from consume to final Mongo update")
      .setUnit("s")
      .build();
}

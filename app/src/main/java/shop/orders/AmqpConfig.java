package shop.orders;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// RabbitAdmin declares any Exchange/Queue/Binding bean it finds on startup, so
// both services converge on the same topology no matter which starts first.
@Configuration
class AmqpConfig {

  @Bean
  TopicExchange ordersExchange(AppProperties cfg) {
    return new TopicExchange(cfg.exchange(), true, false);
  }

  // A quorum queue is replicated by Raft across the cluster; a classic queue
  // lives on one node and dies with it. The type is fixed at declaration time
  // and cannot be changed afterwards, so switching it needs a fresh broker.
  @Bean
  Queue ordersQueue(AppProperties cfg) {
    QueueBuilder b = QueueBuilder.durable(cfg.queue());
    return "quorum".equals(cfg.queueType()) ? b.quorum().build() : b.build();
  }

  @Bean
  Binding ordersBinding(AppProperties cfg, TopicExchange ordersExchange, Queue ordersQueue) {
    return BindingBuilder.bind(ordersQueue).to(ordersExchange).with(cfg.exchange() + ".*");
  }

  @Bean
  MessageConverter jsonMessageConverter() {
    return new Jackson2JsonMessageConverter();
  }
}

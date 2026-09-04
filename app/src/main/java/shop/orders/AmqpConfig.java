package shop.orders;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
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

  @Bean
  Queue ordersQueue(AppProperties cfg) {
    return new Queue(cfg.queue(), true);
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

/*
 * Copyright 2026 iQKV Foundation Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.iqkv.docintelligence.catalogservice.infrastructure.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ configuration for this service.
 * Declares the tenant provisioning queue and binds it to the shared platform events exchange.
 *
 */
@Configuration
@ConditionalOnProperty(name = "iqkv.messaging.rabbitmq.enabled", havingValue = "true")
public class RabbitMQConfig {

  // -------------------------------------------------------------------------
  // Shared exchange (all services publish here)
  // -------------------------------------------------------------------------
  public static final String EVENTS_EXCHANGE = "iqkv.events";
  public static final String DLX_EXCHANGE = "iqkv.dlx";

  public static final String TENANT_PROVISIONING_QUEUE = "docintelligence.catalog.tenant.provisioning";
  public static final String SERVICE_DLQ = "docintelligence.catalog.tenant.provisioning.dlq";

  // -------------------------------------------------------------------------
  // Routing keys
  // -------------------------------------------------------------------------
  public static final String ROUTING_TENANT_CREATED = "tenant.created";
  public static final String ROUTING_TENANT_UPDATED = "tenant.updated";

  private static final long TTL_24H_MS = 86_400_000L;

  // -------------------------------------------------------------------------
  // Beans
  // -------------------------------------------------------------------------

  @Bean
  public TopicExchange eventsExchange() {
    return new TopicExchange(EVENTS_EXCHANGE, true, false);
  }

  @Bean
  public TopicExchange deadLetterExchange() {
    return new TopicExchange(DLX_EXCHANGE, true, false);
  }

  @Bean
  public Queue tenantProvisioningQueue() {
    return QueueBuilder.durable(TENANT_PROVISIONING_QUEUE)
        .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
        // Without an explicit routing key the dead-lettered message keeps its original
        // routing key (tenant.created / tenant.updated) and would never match this
        // service's DLQ binding below, so the message would be silently dropped.
        .withArgument("x-dead-letter-routing-key", SERVICE_DLQ)
        .withArgument("x-message-ttl", TTL_24H_MS)
        .build();
  }

  @Bean
  public Queue serviceDeadLetterQueue() {
    return new Queue(SERVICE_DLQ, true);
  }

  @Bean
  public Binding tenantProvisioningBinding(final Queue tenantProvisioningQueue,
                                           final TopicExchange eventsExchange) {
    return BindingBuilder.bind(tenantProvisioningQueue).to(eventsExchange)
        .with(ROUTING_TENANT_CREATED);
  }

  @Bean
  public Binding tenantUpdateBinding(final Queue tenantProvisioningQueue,
                                     final TopicExchange eventsExchange) {
    return BindingBuilder.bind(tenantProvisioningQueue).to(eventsExchange)
        .with(ROUTING_TENANT_UPDATED);
  }

  @Bean
  public Binding serviceDlqBinding(final Queue serviceDeadLetterQueue,
                                   final TopicExchange deadLetterExchange) {
    return BindingBuilder.bind(serviceDeadLetterQueue).to(deadLetterExchange).with(SERVICE_DLQ);
  }
}

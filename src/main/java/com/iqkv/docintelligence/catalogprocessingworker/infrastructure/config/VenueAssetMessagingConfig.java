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

package com.iqkv.docintelligence.catalogprocessingworker.infrastructure.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ topology for the venue asset processing work.
 *
 * <p>Declares the shared {@code iqkv.events} topic exchange (also declared by
 * {@code docintelligence-catalog-service}; re-declaring it is idempotent), binds this worker's own
 * queue to the {@code venue.asset.uploaded} routing key, and dead-letters poison messages to a
 * dedicated DLQ on the shared {@code iqkv.dlx}.
 */
@Configuration
@ConditionalOnProperty(name = "iqkv.messaging.rabbitmq.enabled", havingValue = "true")
public class VenueAssetMessagingConfig {

  public static final String EVENTS_EXCHANGE = "iqkv.events";
  public static final String DLX_EXCHANGE = "iqkv.dlx";

  public static final String VENUE_ASSET_QUEUE = "docintelligence.catalog.processing.venue-asset";
  public static final String VENUE_ASSET_DLQ = "docintelligence.catalog.processing.venue-asset.dlq";

  /**
   * Published by {@code docintelligence-catalog-service} when an asset upload is confirmed.
   */
  public static final String ROUTING_VENUE_ASSET_UPLOADED = "venue.asset.uploaded";

  private static final long TTL_24H_MS = 86_400_000L;

  @Bean
  public TopicExchange eventsExchange() {
    return new TopicExchange(EVENTS_EXCHANGE, true, false);
  }

  @Bean
  public TopicExchange deadLetterExchange() {
    return new TopicExchange(DLX_EXCHANGE, true, false);
  }

  @Bean
  public Queue venueAssetQueue() {
    return QueueBuilder.durable(VENUE_ASSET_QUEUE)
        .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
        // Without an explicit routing key the dead-lettered message keeps
        // 'venue.asset.uploaded' and would never match the DLQ binding below.
        .withArgument("x-dead-letter-routing-key", VENUE_ASSET_DLQ)
        .withArgument("x-message-ttl", TTL_24H_MS)
        .build();
  }

  @Bean
  public Queue venueAssetDeadLetterQueue() {
    return new Queue(VENUE_ASSET_DLQ, true);
  }

  @Bean
  public Binding venueAssetBinding(final Queue venueAssetQueue, final TopicExchange eventsExchange) {
    return BindingBuilder.bind(venueAssetQueue).to(eventsExchange)
        .with(ROUTING_VENUE_ASSET_UPLOADED);
  }

  @Bean
  public Binding venueAssetDlqBinding(final Queue venueAssetDeadLetterQueue,
                                      final TopicExchange deadLetterExchange) {
    return BindingBuilder.bind(venueAssetDeadLetterQueue).to(deadLetterExchange)
        .with(VENUE_ASSET_DLQ);
  }
}

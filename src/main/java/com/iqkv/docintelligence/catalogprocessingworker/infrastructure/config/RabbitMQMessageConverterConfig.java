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

import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * Configures RabbitMQ message serialization using Jackson 3.x.
 *
 * <p>Replaces the default {@code SimpleMessageConverter} (String / byte[] / Serializable)
 * with {@link JacksonJsonMessageConverter} so that event POJOs are automatically
 * serialized to and deserialized from JSON.
 *
 * <p>Reuses the shared {@link JsonMapper} from {@link JacksonJsonMapperConfig} to ensure
 * consistent JSON handling across REST responses and messaging payloads.
 *
 * <p>Activated only when {@code iqkv.messaging.rabbitmq.enabled=true} so the converter
 * is never registered when there is no RabbitMQ connection.
 */
@Configuration
@ConditionalOnProperty(name = "iqkv.messaging.rabbitmq.enabled", havingValue = "true")
class RabbitMQMessageConverterConfig {

  @Bean
  MessageConverter rabbitMessageConverter(final JsonMapper jsonMapper) {
    return new JacksonJsonMessageConverter(jsonMapper);
  }
}

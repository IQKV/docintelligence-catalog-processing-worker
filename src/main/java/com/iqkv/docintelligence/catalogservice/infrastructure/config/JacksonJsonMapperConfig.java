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

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Configures the shared Jackson 3.x {@link JsonMapper} bean.
 *
 * <p>Shared by REST serialization (Spring MVC), RabbitMQ message conversion
 * ({@link RabbitMQMessageConverterConfig}), and any component that injects
 * {@code JsonMapper} directly (e.g. {@code TenantExtractionFilter} for RFC 7807
 * error responses).
 */
@Configuration(proxyBeanMethods = false)
class JacksonJsonMapperConfig {

  @Bean
  JsonMapper jacksonJsonMapper() {
    final var builder = JsonMapper.builder();
    builder
        .changeDefaultPropertyInclusion(include -> include.withValueInclusion(JsonInclude.Include.NON_NULL))
        .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
        .disable(
            DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
            DeserializationFeature.FAIL_ON_IGNORED_PROPERTIES)
        .enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
        .findAndAddModules();
    return builder.build();
  }
}

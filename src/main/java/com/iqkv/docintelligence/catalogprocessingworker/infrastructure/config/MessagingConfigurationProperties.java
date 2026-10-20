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

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Binds {@code iqkv.messaging.rabbitmq.*} from {@code application.yml}.
 *
 * <p>{@code enabled} drives {@code @ConditionalOnProperty} in {@link RabbitMQConfig}
 * — set to {@code false} in the base config and flipped to {@code true} per profile
 * (local, sit, uat, prd).
 */
@Validated
@ConfigurationProperties(prefix = "iqkv.messaging.rabbitmq")
public record MessagingConfigurationProperties(boolean enabled) {
}

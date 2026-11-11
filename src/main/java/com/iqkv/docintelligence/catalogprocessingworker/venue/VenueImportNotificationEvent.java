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

package com.iqkv.docintelligence.catalogprocessingworker.venue;

import java.util.Map;
import java.util.UUID;

/**
 * RabbitMQ message published to {@code notification.iam.push} on the shared {@code iqkv.events}
 * exchange. Mirrors the JSON shape of {@code foundation-iam-service}'s
 * {@code UserPushNotificationEvent} so the IAM consumer can deserialise it without a shared
 * dependency.
 *
 * <p>The publisher (this service) resolves {@code title} and {@code message} into the final
 * display text — the IAM consumer stores and delivers them as-is with no i18n lookup.
 * {@code severity} must be one of {@code INFO}, {@code SUCCESS}, {@code WARNING}, {@code ERROR}.
 */
public record VenueImportNotificationEvent(
    UUID targetUserId,
    String title,
    String message,
    String severity,
    String type,
    Map<String, Object> payload) {
}

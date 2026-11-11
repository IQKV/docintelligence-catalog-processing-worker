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

import com.iqkv.docintelligence.catalogprocessingworker.infrastructure.config.VenueAssetMessagingConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code venue.asset.uploaded} events and hands each to {@link VenueAssetProcessingService}.
 *
 * <p>The event is consumed as a {@code Map} on purpose: it is published by
 * {@code docintelligence-catalog-service} as JSON, and the message converter's inferred type
 * precedence makes the listener parameter type win over the {@code __TypeId__} header. Only
 * {@code tenantKey} and {@code assetId} are read here — the processing service re-reads the asset
 * from the database, which is the source of truth.
 *
 * <p>A processing failure propagates so the listener container's bounded retry applies and a
 * message that keeps failing is dead-lettered to the queue's DLQ rather than looping.
 */
@Component
@ConditionalOnProperty(name = "iqkv.messaging.rabbitmq.enabled", havingValue = "true")
class VenueAssetUploadedListener {

  private static final Logger log = LoggerFactory.getLogger(VenueAssetUploadedListener.class);

  private final VenueAssetProcessingService processingService;

  VenueAssetUploadedListener(final VenueAssetProcessingService processingService) {
    this.processingService = processingService;
  }

  @RabbitListener(queues = VenueAssetMessagingConfig.VENUE_ASSET_QUEUE)
  public void onVenueAssetUploaded(final Map<String, Object> event) {
    final String tenantKey = (String) event.get("tenantKey");
    final String assetId = (String) event.get("assetId");
    if (tenantKey == null || tenantKey.isBlank() || assetId == null || assetId.isBlank()) {
      log.warn("Ignoring venue asset uploaded event with missing tenantKey or assetId: {}", event);
      return;
    }
    log.info("Received venue asset uploaded event: tenantKey={}, assetId={}", tenantKey, assetId);
    processingService.process(tenantKey, UUID.fromString(assetId));
  }
}

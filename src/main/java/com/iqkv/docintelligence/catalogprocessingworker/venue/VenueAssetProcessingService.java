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
import java.util.Optional;
import java.util.UUID;

import com.iqkv.docintelligence.catalogprocessingworker.infrastructure.config.VenueAssetMessagingConfig;
import com.iqkv.docintelligence.venue.Venue;
import com.iqkv.docintelligence.venue.VenueAsset;
import com.iqkv.docintelligence.venue.VenueAssetMapper;
import com.iqkv.docintelligence.venue.VenueMapper;
import com.iqkv.foundation.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the venue-less asset import pipeline.
 *
 * <p>This service owns the high-level flow: load the asset, guard against double-processing,
 * select and invoke the appropriate {@link VenueExtractionStrategy} via
 * {@link VenueExtractionStrategyRouter}, persist the derived venue, embed it, and notify the
 * uploader. All extraction logic (download, text parsing, LLM classification, metadata
 * building) lives in the strategy implementations, keeping this class as a thin coordinator.
 *
 * <p>Processing steps:
 * <ol>
 *   <li>Set tenant context — {@code MyBatisSchemaInterceptor} routes to {@code t_{tenantKey}}</li>
 *   <li>Load the asset; skip when not found or already linked to a venue</li>
 *   <li>Transition status {@code UPLOADED → PARSING}</li>
 *   <li>Select the extraction strategy by content-type and derive the {@link Venue}</li>
 *   <li>Insert the venue and link the asset (status → {@code EXTRACTED})</li>
 *   <li>Trigger embedding via {@link VenueEmbeddingService}</li>
 *   <li>Publish a WebSocket push notification to the uploader</li>
 * </ol>
 *
 * <p>Any failure in steps 4–7 marks the asset {@code FAILED} with the exception message,
 * publishes a failure notification, and rethrows as {@code IllegalStateException} so the
 * listener's bounded retry and dead-letter policy apply.
 */
@Service
class VenueAssetProcessingService {

  private static final Logger log = LoggerFactory.getLogger(VenueAssetProcessingService.class);

  private final VenueMapper venueMapper;
  private final VenueAssetMapper assetMapper;
  private final VenueEmbeddingService embeddingService;
  private final VenueExtractionStrategyRouter strategyRouter;
  /**
   * Null when messaging is disabled — notification publish is best-effort and skipped when absent.
   */
  private final RabbitTemplate rabbitTemplate;

  VenueAssetProcessingService(final VenueMapper venueMapper,
                              final VenueAssetMapper assetMapper,
                              final VenueEmbeddingService embeddingService,
                              final VenueExtractionStrategyRouter strategyRouter,
                              @Autowired(required = false) final RabbitTemplate rabbitTemplate) {
    this.venueMapper = venueMapper;
    this.assetMapper = assetMapper;
    this.embeddingService = embeddingService;
    this.strategyRouter = strategyRouter;
    this.rabbitTemplate = rabbitTemplate;
  }

  /**
   * Processes one confirmed venue-less asset: derives a venue from the stored file and links it
   * back to the asset.
   *
   * @param tenantKey the tenant the asset belongs to
   * @param assetId   the asset to process
   * @throws IllegalStateException when the extraction fails; the asset is left {@code FAILED}
   *                               so the listener's retry policy applies
   */
  void process(final String tenantKey, final UUID assetId) {
    TenantContext.setCurrentTenant(tenantKey);
    try {
      final Optional<VenueAsset> found = assetMapper.findById(assetId);
      if (found.isEmpty()) {
        log.warn("venue asset {} not found in tenant {} — skipping", assetId, tenantKey);
        return;
      }
      final VenueAsset asset = found.get();
      if (asset.getVenueId() != null) {
        // Already linked (e.g. a photo attached to an existing venue): nothing to derive.
        log.info("venue asset {} already belongs to venue {} — skipping", assetId, asset.getVenueId());
        return;
      }

      assetMapper.updateStatus(assetId, VenueAsset.STATUS_PARSING, null);
      try {
        final VenueExtractionStrategy strategy = strategyRouter.select(asset.getContentType());
        log.info("processing venue asset {} (tenant {}, contentType={}, file={}) via {}",
            assetId, tenantKey, asset.getContentType(), asset.getFileName(),
            strategy.getClass().getSimpleName());
        final Venue venue = strategy.derive(asset);
        venueMapper.insert(venue);
        assetMapper.linkVenue(assetId, venue.getId());
        embeddingService.embed(venue);
        publishNotification(asset.getUploadedBy(), "VENUE_IMPORT_EXTRACTED",
            Map.of("assetId", assetId.toString(), "venueId", venue.getId().toString()));
        log.info("venue {} created from asset {} (tenant {}) via {}",
            venue.getId(), assetId, tenantKey, strategy.getClass().getSimpleName());
      } catch (final Exception ex) {
        final String reason = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
        log.error("failed to process venue asset {} (tenant {}, file={}): {}",
            assetId, tenantKey, asset.getFileName(), reason, ex);
        assetMapper.updateStatus(assetId, VenueAsset.STATUS_FAILED, reason);
        publishNotification(asset.getUploadedBy(), "VENUE_IMPORT_FAILED",
            Map.of("assetId", assetId.toString(), "failureReason", reason));
        throw new IllegalStateException("venue asset processing failed: assetId=" + assetId, ex);
      }
    } finally {
      TenantContext.clear();
    }
  }

  private void publishNotification(final UUID targetUserId, final String type,
                                   final Map<String, Object> payload) {
    if (rabbitTemplate == null || targetUserId == null) {
      return;
    }
    final boolean success = "VENUE_IMPORT_EXTRACTED".equals(type);
    final String title = success ? "Venue created" : "Venue import failed";
    final String message = success
        ? "Your document has been processed and the venue is ready in the catalog."
        : "Could not extract venue data from the uploaded document.";
    final String severity = success ? "SUCCESS" : "ERROR";
    try {
      rabbitTemplate.convertAndSend(
          VenueAssetMessagingConfig.EVENTS_EXCHANGE,
          VenueAssetMessagingConfig.ROUTING_NOTIFICATION_IAM_PUSH,
          new VenueImportNotificationEvent(targetUserId, title, message, severity, type, payload));
    } catch (final Exception ex) {
      log.warn("failed to publish {} notification for user {}: {}", type, targetUserId, ex.getMessage());
    }
  }
}

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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.iqkv.docintelligence.catalogprocessingworker.infrastructure.config.VenueAssetMessagingConfig;
import com.iqkv.docintelligence.venue.VenueAsset;
import com.iqkv.docintelligence.venue.VenueAssetMapper;
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
 * {@link VenueExtractionStrategyRouter}, then run the extracted venue through the ordered
 * {@link VenueProcessingFilter} chain, and notify the uploader. All extraction logic lives in
 * the strategy implementations; each post-extraction step lives in its own filter.
 *
 * <p>Processing steps:
 * <ol>
 *   <li>Set tenant context — {@code MyBatisSchemaInterceptor} routes to {@code t_{tenantKey}}</li>
 *   <li>Load the asset; skip when not found. An asset already linked to a venue takes the
 *       text-only path described below</li>
 *   <li>Transition status {@code UPLOADED → PARSING}</li>
 *   <li>Select the extraction strategy by content-type and derive the {@link com.iqkv.docintelligence.venue.Venue}</li>
 *   <li>Run the {@link VenueProcessingFilter} chain (ordered by {@code @Order}):
 *       {@link MasterVenueMatchFilter} → {@link VenuePersistFilter} →
 *       {@link VenueEmbedFilter} → {@link TextContentPersistFilter}</li>
 *   <li>Publish a WebSocket push notification to the uploader</li>
 * </ol>
 *
 * <p>Any failure in steps 4–5 marks the asset {@code FAILED} with the exception message,
 * publishes a failure notification, and rethrows as {@code IllegalStateException} so the
 * listener's bounded retry and dead-letter policy apply.
 *
 * <p>An asset that already belongs to a venue (an attachment on an existing venue) takes a
 * shorter path: no venue is derived, but a text-typed document (PDF, Word, plain text, Markdown,
 * tabular) is mined for text and saved as a {@link com.iqkv.docintelligence.venue.VenueTextContent}
 * row linked to that venue. The asset row and its status are left untouched and the extraction is
 * best-effort — the attachment stays valid even when its text cannot be read.
 */
@Service
class VenueAssetProcessingService {

  private static final Logger log = LoggerFactory.getLogger(VenueAssetProcessingService.class);

  private final VenueAssetMapper assetMapper;
  private final VenueExtractionStrategyRouter strategyRouter;
  private final List<VenueProcessingFilter> filters;
  private final VenueTextContentPersister textContentPersister;
  /**
   * Null when messaging is disabled — notification publish is best-effort and skipped when absent.
   */
  private final RabbitTemplate rabbitTemplate;

  VenueAssetProcessingService(final VenueAssetMapper assetMapper,
                              final VenueExtractionStrategyRouter strategyRouter,
                              final List<VenueProcessingFilter> filters,
                              final VenueTextContentPersister textContentPersister,
                              @Autowired(required = false) final RabbitTemplate rabbitTemplate) {
    this.assetMapper = assetMapper;
    this.strategyRouter = strategyRouter;
    this.filters = filters;
    this.textContentPersister = textContentPersister;
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
        // Already linked (e.g. a document attached to an existing venue): no venue to derive,
        // but mine the file for text and save it as a VenueTextContent row.
        extractTextForLinkedVenue(tenantKey, asset);
        return;
      }

      assetMapper.updateStatus(assetId, VenueAsset.STATUS_PARSING, null);
      try {
        final VenueExtractionStrategy strategy = strategyRouter.select(asset.getContentType());
        log.info("processing venue asset {} (tenant {}, contentType={}, file={}) via {}",
            assetId, tenantKey, asset.getContentType(), asset.getFileName(),
            strategy.getClass().getSimpleName());
        final VenueExtractionStrategy.VenueExtractionResult result = strategy.derive(asset);
        final var ctx = new VenueProcessingContext(asset, result.venue(), result.rawText(), true);
        for (final VenueProcessingFilter filter : filters) {
          filter.apply(ctx);
        }
        publishNotification(asset.getUploadedBy(), "VENUE_IMPORT_EXTRACTED",
            Map.of("assetId", assetId.toString(), "venueId", result.venue().getId().toString()));
        log.info("venue {} created from asset {} (tenant {}) via {}",
            result.venue().getId(), assetId, tenantKey, strategy.getClass().getSimpleName());
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

  /**
   * Best-effort text mining for an asset that already belongs to a venue.
   *
   * <p>No venue is derived and the asset row and its status are left untouched — the attachment
   * stays valid even when its text cannot be read. A text-typed document (PDF, Word, plain text,
   * Markdown, tabular) is saved as a {@link com.iqkv.docintelligence.venue.VenueTextContent} row
   * linked to the asset's venue; idempotency is handled by the persister.
   */
  private void extractTextForLinkedVenue(final String tenantKey, final VenueAsset asset) {
    final UUID assetId = asset.getId();
    try {
      final VenueExtractionStrategy strategy = strategyRouter.select(asset.getContentType());
      final Optional<String> text = strategy.extractText(asset);
      if (text.isEmpty()) {
        log.info("no text extracted from venue asset {} (tenant {}, contentType={}) — skipping",
            assetId, tenantKey, asset.getContentType());
        return;
      }
      log.info("extracting text from venue asset {} (tenant {}, file={}) via {}",
          assetId, tenantKey, asset.getFileName(), strategy.getClass().getSimpleName());
      textContentPersister.persistIfAbsent(asset.getVenueId(), assetId, asset.getFileName(), text.get());
    } catch (final Exception ex) {
      log.warn("text extraction failed for venue asset {} (tenant {}, file={}): {}",
          assetId, tenantKey, asset.getFileName(), ex.getMessage());
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

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

import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iqkv.docintelligence.catalogprocessingworker.infrastructure.config.ObjectStorageConfigurationProperties;
import com.iqkv.docintelligence.catalogprocessingworker.infrastructure.config.VenueAssetMessagingConfig;
import com.iqkv.docintelligence.venue.Venue;
import com.iqkv.docintelligence.venue.VenueAsset;
import com.iqkv.docintelligence.venue.VenueAssetMapper;
import com.iqkv.docintelligence.venue.VenueMapper;
import com.iqkv.foundation.tenancy.TenantContext;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

/**
 * Turns an uploaded venue-less asset into a tenant {@code Venue}.
 *
 * <p>The background half of the venue-asset pipeline: {@code docintelligence-catalog-service}
 * persists the confirmed asset with a {@code null} venue and status {@code UPLOADED} and publishes
 * {@code venue.asset.uploaded}; this service reads the object, extracts its text with Apache Tika,
 * uses an LLM to derive structured venue data from it, and persists the venue. The derived venue
 * gets an embedding through {@link VenueEmbeddingService} so it is immediately searchable.
 *
 * <p>LLM extraction uses Spring AI's {@code ChatClient.entity()} to have Ollama populate a
 * {@link VenueExtraction} record from the document text. When the LLM call fails or returns
 * unusable data a heuristic fallback chain applies:
 * <ol>
 *   <li>name — LLM result, then first non-blank Tika line, then filename without extension
 *   <li>address / city — LLM result only; {@code null} when unavailable
 *   <li>confidence — LLM result clamped to [0.0, 1.0], then {@code 0.3} as the low-confidence default
 * </ol>
 *
 * <p>An asset that already has a venue (a photo attached to an existing venue, say) needs no
 * extraction and is skipped. A failure marks the asset {@code FAILED} with the cause and rethrows,
 * so the listener's retry policy applies and a poison message eventually dead-letters.
 *
 * <p>Tenant-scoped: it sets {@link TenantContext} for the delivery's tenant so the
 * {@code MyBatisSchemaInterceptor} routes every mapper call to that tenant's {@code t_{tenantKey}}
 * schema, and clears it in a finally block.
 */
@Service
class VenueAssetProcessingService {

  private static final Logger log = LoggerFactory.getLogger(VenueAssetProcessingService.class);

  private static final String SOURCE_FILE_IMPORT = "FILE_IMPORT";
  private static final BigDecimal CONFIDENCE_LOW = new BigDecimal("0.3");
  private static final BigDecimal CONFIDENCE_MIN = BigDecimal.ZERO;
  private static final BigDecimal CONFIDENCE_MAX = BigDecimal.ONE;

  /**
   * Maximum characters of document text sent to the LLM — avoids exceeding context windows.
   * shortcut: fixed cap; upgrade to dynamic chunking when processing large decks.
   */
  private static final int TEXT_MAX_CHARS = 8_000;

  /**
   * {@code venues.name} is {@code VARCHAR(255)}.
   */
  private static final int NAME_MAX_LENGTH = 255;

  /**
   * Prompt template: instructs the model to return only a JSON object conforming to
   * {@link VenueExtraction}, using null for any field not present in the text.
   */
  private static final String EXTRACTION_PROMPT = """
      You are extracting venue information from a venue brochure, flyer, or deck.
      Return ONLY a valid JSON object — no explanation, no markdown, no code fences.
      Use null for any field you cannot clearly find in the text.
      Set confidence between 0.0 and 1.0 based on how confidently you found the data.

      Fields to extract:
      name, address, city, locationLat, locationLng, confidence,
      description, maxCapacity, standingCapacity, seatedCapacity,
      venueType, currency, priceRangeHint, priceMethod,
      wifi, airConditioning, naturalLight, privateBar, privateEntrance, privateToilets,
      parkingAvailable, builtInAv, projector, flatscreenTv, whiteboard,
      noiseRestrictionAfter, alcoholLicenceUntil,
      cateringPolicy, veganAvailable, vegetarianAvailable, glutenFreeAvailable

      Document text:
      {text}
      """;

  private final VenueMapper venueMapper;
  private final VenueAssetMapper assetMapper;
  private final MinioClient minioClient;
  private final ObjectStorageConfigurationProperties storageProps;
  private final VenueEmbeddingService embeddingService;
  private final ChatClient chatClient;
  private final ObjectMapper objectMapper;
  /**
   * Null when messaging is disabled — notification publish is best-effort and skipped when absent.
   */
  private final RabbitTemplate rabbitTemplate;

  VenueAssetProcessingService(final VenueMapper venueMapper,
                              final VenueAssetMapper assetMapper,
                              final MinioClient minioClient,
                              final ObjectStorageConfigurationProperties storageProps,
                              final VenueEmbeddingService embeddingService,
                              final ChatClient.Builder chatClientBuilder,
                              final ObjectMapper objectMapper,
                              @Autowired(required = false) final RabbitTemplate rabbitTemplate) {
    this.venueMapper = venueMapper;
    this.assetMapper = assetMapper;
    this.minioClient = minioClient;
    this.storageProps = storageProps;
    this.embeddingService = embeddingService;
    this.chatClient = chatClientBuilder.build();
    this.objectMapper = objectMapper;
    this.rabbitTemplate = rabbitTemplate;
  }

  /**
   * Processes one confirmed venue-less asset: derives a venue from the stored file and links it
   * back to the asset.
   *
   * @param tenantKey the tenant the asset belongs to
   * @param assetId   the asset to process
   * @throws IllegalStateException when the file cannot be read or parsed; the asset is left
   *                               {@code FAILED} with the cause so the listener can retry it
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
        // Already belongs to a venue (for example a photo attached to an existing one): nothing to
        // derive, so the pipeline is done for this asset.
        log.info("venue asset {} already belongs to venue {} — skipping", assetId, asset.getVenueId());
        return;
      }

      assetMapper.updateStatus(assetId, VenueAsset.STATUS_PARSING, null);
      try {
        final Venue venue = deriveVenue(asset);
        venueMapper.insert(venue);
        assetMapper.linkVenue(assetId, venue.getId());
        embeddingService.embed(venue);
        publishNotification(asset.getUploadedBy(), "VENUE_IMPORT_EXTRACTED",
            Map.of("assetId", assetId.toString(), "venueId", venue.getId().toString()));
        log.info("venue {} created from asset {} (tenant {})", venue.getId(), assetId, tenantKey);
      } catch (final Exception ex) {
        final String reason = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
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
      // best-effort — do not let a notification failure affect the processing result
      log.warn("failed to publish {} notification for user {}: {}", type, targetUserId, ex.getMessage());
    }
  }

  private Venue deriveVenue(final VenueAsset asset) throws Exception {
    final String text = extractText(asset.getS3Key());
    final VenueExtraction extraction = extractWithLlm(text, asset.getFileName());

    final Venue venue = new Venue();
    venue.setId(UUID.randomUUID());
    venue.setName(resolveName(extraction, text, asset.getFileName()));
    venue.setAddress(extraction != null ? extraction.address() : null);
    venue.setCity(extraction != null ? extraction.city() : null);
    venue.setLocationLat(toDecimal(extraction != null ? extraction.locationLat() : null));
    venue.setLocationLng(toDecimal(extraction != null ? extraction.locationLng() : null));
    venue.setConfidence(resolveConfidence(extraction));
    venue.setMetadata(buildMetadata(extraction));
    venue.setSource(SOURCE_FILE_IMPORT);
    return venue;
  }

  /**
   * Calls the LLM to extract structured venue data from the document text.
   * Returns {@code null} when the model call fails or produces unparseable output — callers
   * apply the heuristic fallback chain.
   */
  VenueExtraction extractWithLlm(final String text, final String fileName) {
    if (text == null || text.isBlank()) {
      return null;
    }
    final String truncated = text.length() > TEXT_MAX_CHARS ? text.substring(0, TEXT_MAX_CHARS) : text;
    try {
      final VenueExtraction result = chatClient
          .prompt(EXTRACTION_PROMPT.replace("{text}", truncated))
          .call()
          .entity(VenueExtraction.class);
      log.debug("LLM extracted venue from {}: name={}, confidence={}", fileName,
          result != null ? result.name() : null,
          result != null ? result.confidence() : null);
      return result;
    } catch (final Exception ex) {
      log.warn("LLM extraction failed for {} — falling back to heuristics: {}", fileName, ex.getMessage());
      return null;
    }
  }

  /**
   * Reads and parses the stored object into its plain text.
   *
   * @param s3Key the tenant-scoped object key
   * @return the extracted text, empty when the file carries none
   * @throws Exception when the object cannot be read or parsed
   */
  private String extractText(final String s3Key) throws Exception {
    final byte[] bytes = download(s3Key);
    if (bytes.length == 0) {
      return "";
    }
    final Resource resource = new ByteArrayResource(bytes);
    return new TikaDocumentReader(resource).get().stream()
        .map(Document::getText)
        .collect(Collectors.joining("\n"));
  }

  /**
   * Downloads the stored object. Package-private so a test can stand in for MinIO.
   *
   * @param s3Key the tenant-scoped object key
   * @return the object bytes
   * @throws Exception when MinIO is unreachable or the object is missing
   */
  byte[] download(final String s3Key) throws Exception {
    try (InputStream in = minioClient.getObject(
        GetObjectArgs.builder().bucket(storageProps.bucketName()).object(s3Key).build())) {
      return in.readAllBytes();
    }
  }

  /**
   * Resolves the venue name via the fallback chain:
   * LLM name → first non-blank Tika line → filename without extension.
   */
  static String resolveName(final VenueExtraction extraction, final String text, final String fileName) {
    if (extraction != null && extraction.name() != null && !extraction.name().isBlank()) {
      return truncate(extraction.name().strip());
    }
    if (text != null) {
      for (final String line : text.split("\\R")) {
        final String candidate = line.strip();
        if (!candidate.isEmpty()) {
          return truncate(candidate);
        }
      }
    }
    return truncate(fileNameBase(fileName));
  }

  /**
   * Resolves confidence: LLM result clamped to [0.0, 1.0], falling back to {@value} 0.3.
   */
  private static BigDecimal resolveConfidence(final VenueExtraction extraction) {
    if (extraction != null && extraction.confidence() != null) {
      final BigDecimal raw = BigDecimal.valueOf(extraction.confidence()).setScale(4, RoundingMode.HALF_UP);
      return raw.max(CONFIDENCE_MIN).min(CONFIDENCE_MAX);
    }
    return CONFIDENCE_LOW;
  }

  private static BigDecimal toDecimal(final Double value) {
    return value != null ? BigDecimal.valueOf(value) : null;
  }

  /**
   * Builds the metadata JSONB document from the LLM extraction.
   * Populates the same {@code {"group","field","value"}} item schema used by the master-venue
   * catalog so downstream matching and display logic works without schema changes.
   * Returns the minimal {@code {"_schema_version":1}} skeleton when extraction is null.
   */
  String buildMetadata(final VenueExtraction ex) {
    final List<MetadataItem> items = new ArrayList<>();
    if (ex != null) {
      addItem(items, "capacity", "max_total", ex.maxCapacity());
      addItem(items, "capacity", "configurations.standing", ex.standingCapacity());
      addItem(items, "capacity", "configurations.seated", ex.seatedCapacity());
      addItem(items, "venue_type", "venue_type", ex.venueType());
      addItem(items, "catering", "policy", ex.cateringPolicy());
      addItem(items, "catering", "vegan_available", ex.veganAvailable());
      addItem(items, "catering", "vegetarian_available", ex.vegetarianAvailable());
      addItem(items, "catering", "gluten_free_available", ex.glutenFreeAvailable());
      addItem(items, "catering", "alcohol_licence_until", ex.alcoholLicenceUntil());
      addItem(items, "av_tech", "built_in_av", ex.builtInAv());
      addItem(items, "av_tech", "wifi", ex.wifi());
      addItem(items, "av_tech", "projector", ex.projector());
      addItem(items, "av_tech", "flatscreen_tv", ex.flatscreenTv());
      addItem(items, "av_tech", "whiteboard", ex.whiteboard());
      addItem(items, "logistics", "air_conditioning", ex.airConditioning());
      addItem(items, "logistics", "natural_light", ex.naturalLight());
      addItem(items, "logistics", "private_bar", ex.privateBar());
      addItem(items, "logistics", "private_entrance", ex.privateEntrance());
      addItem(items, "logistics", "private_toilets", ex.privateToilets());
      addItem(items, "logistics", "parking_available", ex.parkingAvailable());
      addItem(items, "logistics", "noise_restriction_after", ex.noiseRestrictionAfter());
      addItem(items, "pricing", "currency", ex.currency());
      addItem(items, "pricing", "price_range_hint", ex.priceRangeHint());
      addItem(items, "pricing", "price_method", ex.priceMethod());
      if (ex.description() != null && !ex.description().isBlank()) {
        addItem(items, "description", "description", ex.description());
      }
    }
    try {
      final var root = objectMapper.createObjectNode();
      root.put("_schema_version", 1);
      if (!items.isEmpty()) {
        root.set("items", objectMapper.valueToTree(items));
      }
      return objectMapper.writeValueAsString(root);
    } catch (final Exception e) {
      log.warn("failed to serialise metadata — using skeleton: {}", e.getMessage());
      return "{\"_schema_version\":1}";
    }
  }

  private static void addItem(final List<MetadataItem> items,
                              final String group, final String field, final Object value) {
    if (value != null) {
      items.add(new MetadataItem(group, field, value));
    }
  }

  /**
   * One entry in the {@code metadata.items} array — matches the master-venue canonical schema.
   */
  private record MetadataItem(String group, String field, Object value) {
  }

  private static String fileNameBase(final String fileName) {
    final int dot = fileName.lastIndexOf('.');
    return dot > 0 ? fileName.substring(0, dot) : fileName;
  }

  private static String truncate(final String value) {
    return value.length() > NAME_MAX_LENGTH ? value.substring(0, NAME_MAX_LENGTH) : value;
  }
}

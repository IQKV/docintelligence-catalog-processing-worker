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
import java.util.UUID;
import java.util.stream.Collectors;

import com.iqkv.docintelligence.catalogprocessingworker.infrastructure.config.ObjectStorageConfigurationProperties;
import com.iqkv.docintelligence.venue.Venue;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared extraction utilities used by all {@link VenueExtractionStrategy} implementations.
 *
 * <p>Owns: MinIO download, Apache Tika text extraction, name/confidence/metadata derivation
 * from a {@link VenueExtraction}, and the common venue-skeleton builder. Strategies call
 * these helpers instead of duplicating them.
 */
@Component
class VenueExtractionContext {

  private static final Logger log = LoggerFactory.getLogger(VenueExtractionContext.class);

  static final String SOURCE_FILE_IMPORT = "FILE_IMPORT";
  static final BigDecimal CONFIDENCE_NONE = new BigDecimal("0.1");
  private static final BigDecimal CONFIDENCE_LOW = new BigDecimal("0.3");
  private static final BigDecimal CONFIDENCE_MIN = BigDecimal.ZERO;
  private static final BigDecimal CONFIDENCE_MAX = BigDecimal.ONE;
  static final int NAME_MAX_LENGTH = 255;

  private final MinioClient minioClient;
  private final ObjectStorageConfigurationProperties storageProps;
  private final JsonMapper jsonMapper;

  VenueExtractionContext(final MinioClient minioClient,
                         final ObjectStorageConfigurationProperties storageProps,
                         final JsonMapper jsonMapper) {
    this.minioClient = minioClient;
    this.storageProps = storageProps;
    this.jsonMapper = jsonMapper;
  }

  /**
   * Downloads the stored object from MinIO.
   *
   * @param s3Key the tenant-scoped object key
   * @return the raw bytes
   * @throws Exception when MinIO is unreachable or the object is missing
   */
  byte[] download(final String s3Key) throws Exception {
    try (InputStream in = minioClient.getObject(
        GetObjectArgs.builder().bucket(storageProps.bucketName()).object(s3Key).build())) {
      return in.readAllBytes();
    }
  }

  /**
   * Extracts plain text from raw file bytes using Apache Tika.
   *
   * @param bytes the raw file content; empty arrays return an empty string
   * @return the extracted text joined by newlines, never null
   * @throws Exception when Tika cannot parse the content
   */
  String extractText(final byte[] bytes) throws Exception {
    if (bytes.length == 0) {
      return "";
    }
    return new TikaDocumentReader(new ByteArrayResource(bytes)).get().stream()
        .map(Document::getText)
        .collect(Collectors.joining("\n"));
  }

  /**
   * Builds a minimal {@link Venue} skeleton with only id and source set.
   * Strategies call this then populate further fields.
   */
  Venue newVenueSkeleton() {
    final Venue venue = new Venue();
    venue.setId(UUID.randomUUID());
    venue.setSource(SOURCE_FILE_IMPORT);
    return venue;
  }

  /**
   * Resolves the venue name via the fallback chain:
   * LLM name → first non-blank Tika line → filename without extension.
   */
  static String resolveName(final VenueExtraction extraction, final String text,
                             final String fileName) {
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
   * Resolves confidence: LLM result clamped to [0.0, 1.0]; falls back to 0.3 when absent.
   */
  static BigDecimal resolveConfidence(final VenueExtraction extraction) {
    if (extraction != null && extraction.confidence() != null) {
      final BigDecimal raw = BigDecimal.valueOf(extraction.confidence()).setScale(4, RoundingMode.HALF_UP);
      return raw.max(CONFIDENCE_MIN).min(CONFIDENCE_MAX);
    }
    return CONFIDENCE_LOW;
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
      final var root = jsonMapper.createObjectNode();
      root.put("_schema_version", 1);
      if (!items.isEmpty()) {
        root.set("items", jsonMapper.valueToTree(items));
      }
      return jsonMapper.writeValueAsString(root);
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

  private record MetadataItem(String group, String field, Object value) {
  }

  static String fileNameBase(final String fileName) {
    final int dot = fileName.lastIndexOf('.');
    return dot > 0 ? fileName.substring(0, dot) : fileName;
  }

  static String truncate(final String value) {
    return value.length() > NAME_MAX_LENGTH ? value.substring(0, NAME_MAX_LENGTH) : value;
  }
}

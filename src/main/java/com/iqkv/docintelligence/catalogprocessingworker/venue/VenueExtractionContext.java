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
      final byte[] bytes = in.readAllBytes();
      log.debug("downloaded {} bytes from {}", bytes.length, s3Key);
      return bytes;
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
    final String text = new TikaDocumentReader(new ByteArrayResource(bytes)).get().stream()
        .map(Document::getText)
        .collect(Collectors.joining("\n"));
    log.debug("extracted {} characters of text from {} bytes", text.length(), bytes.length);
    return text;
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
   * Field keys are aligned with the front-end metadata spec ({@code metadata-spec.ts}).
   * Returns the minimal {@code {"_schema_version":1}} skeleton when extraction is null.
   */
  String buildMetadata(final VenueExtraction ex) {
    final List<MetadataItem> items = new ArrayList<>();
    if (ex != null) {
      // ── Basic info ──────────────────────────────────────────────────────────
      addItem(items, "basic", "location_notes", ex.locationNotes());
      addItem(items, "basic", "amenities", splitAmenities(ex.amenities()));

      // ── Venue type ──────────────────────────────────────────────────────────
      addItem(items, "venue_type", "venue_type", ex.venueType() != null
          ? List.of(ex.venueType()) : null);

      // ── Capacity ────────────────────────────────────────────────────────────
      addItem(items, "capacity", "max_total", ex.maxCapacity());
      addItem(items, "capacity", "configurations.cocktail", ex.standingCapacity());
      addItem(items, "capacity", "configurations.banquet", ex.seatedCapacity());
      addItem(items, "capacity", "configurations.classroom", ex.classroomCapacity());
      addItem(items, "capacity", "configurations.conference", ex.conferenceCapacity());

      // ── Catering ────────────────────────────────────────────────────────────
      addItem(items, "catering", "policy", ex.cateringPolicy());
      addItem(items, "catering", "kosher_available", ex.kosherAvailable());
      addItem(items, "catering", "halal_available", ex.halalAvailable());
      addItem(items, "catering", "vegan_available", ex.veganAvailable());

      // ── AV & technology ─────────────────────────────────────────────────────
      addItem(items, "av_tech", "built_in_av", ex.builtInAv());
      addItem(items, "av_tech", "wifi", ex.wifi());
      addItem(items, "av_tech", "rigging_points", ex.riggingPoints());
      addItem(items, "av_tech", "internet_bandwidth_mbps", ex.internetBandwidthMbps());

      // ── Accessibility ───────────────────────────────────────────────────────
      addItem(items, "accessibility", "ada_compliant", ex.adaCompliant());
      addItem(items, "accessibility", "elevator_access", ex.elevatorAccess());
      addItem(items, "accessibility", "accessible_restrooms", ex.accessibleRestrooms());
      addItem(items, "accessibility", "wheelchair_stage_access", ex.wheelchairStageAccess());

      // ── Outdoor space ───────────────────────────────────────────────────────
      addItem(items, "outdoor_space", "available", ex.outdoorSpaceAvailable());
      addItem(items, "outdoor_space", "covered", ex.outdoorSpaceCovered());
      addItem(items, "outdoor_space", "sqm", ex.outdoorSpaceSqm());

      // ── Exclusivity ─────────────────────────────────────────────────────────
      addItem(items, "exclusivity", "exclusive_use", ex.exclusiveUse());
      addItem(items, "exclusivity", "shared_space", ex.sharedSpace());

      // ── Logistics ───────────────────────────────────────────────────────────
      addItem(items, "logistics", "load_in_access", ex.loadInAccess());
      addItem(items, "logistics", "parking_spaces", ex.parkingSpaces());
      addItem(items, "logistics", "valet_available", ex.valetAvailable());
      addItem(items, "logistics", "curfew_time", ex.curfewTime());
      addItem(items, "logistics", "setup_hours_before", ex.setupHoursBefore());
      addItem(items, "logistics", "teardown_hours_after", ex.teardownHoursAfter());

      // ── Pricing ─────────────────────────────────────────────────────────────
      addItem(items, "pricing", "currency", ex.currency());
      addItem(items, "pricing", "minimum_spend", ex.minimumSpend());
      addItem(items, "pricing", "rental_fee_indicative", ex.rentalFeeIndicative());

      // ── Social & online ─────────────────────────────────────────────────────
      addItem(items, "social", "instagram_handle", ex.instagramHandle());
      addItem(items, "ratings", "google_rating", ex.googleRating());
      addItem(items, "ratings", "google_review_count", ex.googleReviewCount());
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

  /**
   * Splits a comma-separated amenities string into a list for the string_array spec field.
   * Returns null when blank so {@code addItem} skips it.
   */
  private static List<String> splitAmenities(final String amenities) {
    if (amenities == null || amenities.isBlank()) {
      return null;
    }
    final List<String> result = new ArrayList<>();
    for (final String part : amenities.split(",")) {
      final String trimmed = part.strip();
      if (!trimmed.isEmpty()) {
        result.add(trimmed);
      }
    }
    return result.isEmpty() ? null : result;
  }

  private static void addItem(final List<MetadataItem> items,
                              final String group, final String field, final Object value) {
    if (value != null) {
      items.add(new MetadataItem(group, field, value));
    }
  }

  private record MetadataItem(String group, String field, Object value) {
  }

  /**
   * Normalises extracted text and truncates it to {@link DocumentClassifier#TEXT_MAX_CHARS}
   * characters at a word boundary. Called by extraction strategies before passing text to
   * {@link DocumentClassifier#classify} to maximise signal density within the context window.
   *
   * <p>Normalisation: collapses Tika form-feed page breaks, horizontal whitespace runs, and
   * excessive blank lines — matching the approach used in
   * {@code VenueEnrichmentAssistantService} in the catalog service.
   */
  static String normaliseAndTruncate(final String text) {
    if (text == null || text.isBlank()) {
      return text == null ? "" : text;
    }
    final String normalised = text
        .replaceAll("\\s*\\f\\s*", " ")       // Tika page-break form-feeds
        .replaceAll("[ \\t]{2,}", " ")         // horizontal whitespace runs
        .replaceAll("(\\R){3,}", "\n\n")       // 3+ blank lines → paragraph break
        .strip();
    return truncateAtWordBoundary(normalised, DocumentClassifier.TEXT_MAX_CHARS);
  }

  /**
   * Truncates to {@code max} characters at a word boundary so the model never receives a
   * split token at the prompt boundary.
   */
  private static String truncateAtWordBoundary(final String value, final int max) {
    if (value.length() <= max) {
      return value;
    }
    int cut = max;
    while (cut > 0 && !Character.isWhitespace(value.charAt(cut))) {
      cut--;
    }
    return cut > 0 ? value.substring(0, cut) : value.substring(0, max);
  }

  static String fileNameBase(final String fileName) {
    final int dot = fileName.lastIndexOf('.');
    return dot > 0 ? fileName.substring(0, dot) : fileName;
  }

  static String truncate(final String value) {
    return value.length() > NAME_MAX_LENGTH ? value.substring(0, NAME_MAX_LENGTH) : value;
  }
}

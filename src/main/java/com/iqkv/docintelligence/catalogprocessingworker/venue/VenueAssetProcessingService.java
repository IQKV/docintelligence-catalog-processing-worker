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
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import com.iqkv.docintelligence.catalogprocessingworker.infrastructure.config.ObjectStorageConfigurationProperties;
import com.iqkv.docintelligence.venue.Venue;
import com.iqkv.docintelligence.venue.VenueAsset;
import com.iqkv.docintelligence.venue.VenueAssetMapper;
import com.iqkv.docintelligence.venue.VenueMapper;
import com.iqkv.foundation.tenancy.TenantContext;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

/**
 * Turns an uploaded venue-less asset into a tenant {@code Venue}.
 *
 * <p>The background half of the venue-asset pipeline: {@code docintelligence-catalog-service}
 * persists the confirmed asset with a {@code null} venue and status {@code UPLOADED} and publishes
 * {@code venue.asset.uploaded}; this service reads the object, extracts its text with Apache Tika,
 * derives a venue from it, links the asset and marks it {@code EXTRACTED}. The derived venue gets
 * an embedding through {@link VenueEmbeddingService} so it is immediately searchable.
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
  private static final String DEFAULT_METADATA = "{\"_schema_version\":1}";
  /**
   * {@code venues.name} is {@code VARCHAR(255)}.
   */
  private static final int NAME_MAX_LENGTH = 255;

  private final VenueMapper venueMapper;
  private final VenueAssetMapper assetMapper;
  private final MinioClient minioClient;
  private final ObjectStorageConfigurationProperties storageProps;
  private final VenueEmbeddingService embeddingService;

  VenueAssetProcessingService(final VenueMapper venueMapper,
                              final VenueAssetMapper assetMapper,
                              final MinioClient minioClient,
                              final ObjectStorageConfigurationProperties storageProps,
                              final VenueEmbeddingService embeddingService) {
    this.venueMapper = venueMapper;
    this.assetMapper = assetMapper;
    this.minioClient = minioClient;
    this.storageProps = storageProps;
    this.embeddingService = embeddingService;
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
        log.info("venue {} created from asset {} (tenant {})", venue.getId(), assetId, tenantKey);
      } catch (final Exception ex) {
        final String reason = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
        assetMapper.updateStatus(assetId, VenueAsset.STATUS_FAILED, reason);
        throw new IllegalStateException("venue asset processing failed: assetId=" + assetId, ex);
      }
    } finally {
      TenantContext.clear();
    }
  }

  private Venue deriveVenue(final VenueAsset asset) throws Exception {
    final Venue venue = new Venue();
    venue.setId(UUID.randomUUID());
    venue.setName(deriveName(asset.getFileName(), extractText(asset.getS3Key())));
    venue.setMetadata(DEFAULT_METADATA);
    venue.setSource(SOURCE_FILE_IMPORT);
    return venue;
  }

  /**
   * Reads and parses the stored object into its plain text.
   *
   * @param s3Key the tenant-scoped object key
   * @return the extracted text, empty when the file carries none
   * @throws Exception when the object cannot be read or parsed
   */
  private String extractText(final String s3Key) throws Exception {
    final Resource resource = new ByteArrayResource(download(s3Key));
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
   * Derives the venue name from the file: the first non-blank line of the extracted text, falling
   * back to the file name without its extension when the file has no readable text.
   * shortcut: no field extraction yet — the venue is named from the document's first line.
   *
   * @param fileName the stored file name
   * @param text     the text extracted from the file; nullable
   * @return a non-null name, at most {@value #NAME_MAX_LENGTH} characters
   */
  static String deriveName(final String fileName, final String text) {
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

  private static String fileNameBase(final String fileName) {
    final int dot = fileName.lastIndexOf('.');
    return dot > 0 ? fileName.substring(0, dot) : fileName;
  }

  private static String truncate(final String value) {
    return value.length() > NAME_MAX_LENGTH ? value.substring(0, NAME_MAX_LENGTH) : value;
  }
}

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

import java.util.UUID;

import com.iqkv.docintelligence.venue.VenueTextContent;
import com.iqkv.docintelligence.venue.VenueTextContentMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Persists a {@link VenueTextContent} row for an asset and embeds it — the write half of the
 * text-content path, shared by {@link TextContentPersistFilter} (venue import) and the
 * venue-scoped text extraction in {@link VenueAssetProcessingService}.
 *
 * <p>Idempotent per source asset: an asset that already has a text-content row is left untouched,
 * so a redelivered event never duplicates the row. Any failure is logged and swallowed — a missing
 * text-content row never fails the pipeline.
 */
@Component
class VenueTextContentPersister {

  private static final Logger log = LoggerFactory.getLogger(VenueTextContentPersister.class);

  private final VenueTextContentMapper textContentMapper;
  private final VenueEmbeddingService embeddingService;

  VenueTextContentPersister(final VenueTextContentMapper textContentMapper,
                            final VenueEmbeddingService embeddingService) {
    this.textContentMapper = textContentMapper;
    this.embeddingService = embeddingService;
  }

  /**
   * Creates a text-content row for the asset and embeds it, unless one already exists for that
   * asset. Does nothing when {@code content} is {@code null} or blank.
   *
   * @param venueId the venue the text belongs to
   * @param assetId the asset the text was extracted from
   * @param label   human-readable name of the source
   * @param content the extracted text
   * @return {@code true} when a row was created
   */
  boolean persistIfAbsent(final UUID venueId, final UUID assetId,
                          final String label, final String content) {
    if (content == null || content.isBlank()) {
      return false;
    }
    try {
      if (!textContentMapper.findBySourceAssetId(assetId).isEmpty()) {
        log.debug("VenueTextContent already exists for asset {} — skipping", assetId);
        return false;
      }
      final VenueTextContent tc = new VenueTextContent();
      tc.setId(UUID.randomUUID());
      tc.setVenueId(venueId);
      tc.setSourceAssetId(assetId);
      tc.setLabel(label);
      tc.setContent(content);
      textContentMapper.insert(tc);
      try {
        final float[] vector = embeddingService.embedText(content);
        textContentMapper.updateEmbedding(tc.getId(), vector);
        log.debug("embedded VenueTextContent {} for venue {}", tc.getId(), venueId);
      } catch (final RuntimeException ex) {
        log.warn("VenueTextContent embedding skipped for venue {} — Ollama unavailable: {}",
            venueId, ex.getMessage());
      }
      log.info("VenueTextContent {} created for venue {} ({} chars)", tc.getId(), venueId, content.length());
      return true;
    } catch (final RuntimeException ex) {
      log.warn("failed to persist VenueTextContent for venue {}: {}", venueId, ex.getMessage());
      return false;
    }
  }
}

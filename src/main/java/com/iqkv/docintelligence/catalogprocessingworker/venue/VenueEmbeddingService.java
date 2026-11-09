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

import com.iqkv.docintelligence.venue.Venue;
import com.iqkv.docintelligence.venue.VenueEmbeddingText;
import com.iqkv.docintelligence.venue.VenueMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

/**
 * Embeds a tenant {@code Venue} and writes the vector to the database.
 *
 * <p>The background counterpart of {@code docintelligence-catalog-service}'s synchronous
 * embed-on-write path: both feed the model the same text via
 * {@link com.iqkv.docintelligence.venue.VenueEmbeddingText}, so a venue gets an identical vector
 * whichever path last wrote it.
 *
 * <p>Failures are logged and swallowed: a slow or unavailable Ollama instance never aborts a
 * backfill pass; the row is left with a {@code NULL} embedding and retried on the next pass.
 */
@Service
class VenueEmbeddingService {

  private static final Logger log = LoggerFactory.getLogger(VenueEmbeddingService.class);

  private final EmbeddingModel embeddingModel;

  private final VenueMapper venueMapper;

  VenueEmbeddingService(final EmbeddingModel embeddingModel, final VenueMapper venueMapper) {
    this.embeddingModel = embeddingModel;
    this.venueMapper = venueMapper;
  }

  /**
   * Embeds {@code venue} and writes the vector to the database.
   * Swallows any {@link RuntimeException} so a slow or unavailable Ollama instance
   * never blocks the backfill.
   *
   * @param venue the venue to embed; must have a non-null {@code id}
   * @return {@code true} when the vector reached the database
   */
  boolean embed(final Venue venue) {
    try {
      final float[] vector = embeddingModel.embed(VenueEmbeddingText.build(venue));
      venueMapper.updateEmbedding(venue.getId(), vector);
      log.debug("embedded venue {} ({} dims)", venue.getId(), vector.length);
      return true;
    } catch (final RuntimeException ex) {
      log.warn("embedding skipped for venue {} — Ollama unavailable or slow: {}",
          venue.getId(), ex.getMessage());
      return false;
    }
  }
}

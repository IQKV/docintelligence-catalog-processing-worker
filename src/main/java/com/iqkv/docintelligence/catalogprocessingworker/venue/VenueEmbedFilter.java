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

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Embeds the persisted venue via Ollama and writes the vector to the database.
 * Failures are swallowed inside {@link VenueEmbeddingService#embed} — a missing vector is
 * retried on the next backfill pass.
 */
@Component
@Order(30)
class VenueEmbedFilter implements VenueProcessingFilter {

  private final VenueEmbeddingService embeddingService;

  VenueEmbedFilter(final VenueEmbeddingService embeddingService) {
    this.embeddingService = embeddingService;
  }

  @Override
  public void apply(final VenueProcessingContext ctx) {
    embeddingService.embed(ctx.venue());
  }
}

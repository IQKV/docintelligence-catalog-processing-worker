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
 * Inserts a {@link com.iqkv.docintelligence.venue.VenueTextContent} row and embeds it when the
 * extraction produced raw text. Skipped for image assets where {@code rawText} is {@code null} or
 * blank. Delegates the write to {@link VenueTextContentPersister}; a failure there is logged and
 * swallowed — a missing text-content row never fails the pipeline.
 */
@Component
@Order(40)
class TextContentPersistFilter implements VenueProcessingFilter {

  private final VenueTextContentPersister persister;

  TextContentPersistFilter(final VenueTextContentPersister persister) {
    this.persister = persister;
  }

  @Override
  public void apply(final VenueProcessingContext ctx) {
    persister.persistIfAbsent(ctx.venue().getId(), ctx.asset().getId(),
        ctx.asset().getFileName(), ctx.rawText());
  }
}

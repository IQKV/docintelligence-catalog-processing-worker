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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Selects the appropriate {@link VenueExtractionStrategy} for an asset's content-type.
 *
 * <p>Strategies are evaluated in priority order:
 * <ol>
 *   <li>{@link RichDocumentExtractionStrategy} — PDF, Word, plain text</li>
 *   <li>{@link TabularDataExtractionStrategy} — CSV, XLS, XLSX</li>
 *   <li>{@link NoInferenceStrategy} — images and all other types (always matches)</li>
 * </ol>
 * The first strategy whose {@code supports()} returns {@code true} is used.
 * {@link NoInferenceStrategy} is always last and always matches, so this method
 * never returns {@code null}.
 */
@Component
class VenueExtractionStrategyRouter {

  private static final Logger log = LoggerFactory.getLogger(VenueExtractionStrategyRouter.class);

  private final RichDocumentExtractionStrategy richDocument;
  private final TabularDataExtractionStrategy tabularData;
  private final NoInferenceStrategy noInference;

  VenueExtractionStrategyRouter(final RichDocumentExtractionStrategy richDocument,
                                 final TabularDataExtractionStrategy tabularData,
                                 final NoInferenceStrategy noInference) {
    this.richDocument = richDocument;
    this.tabularData = tabularData;
    this.noInference = noInference;
  }

  /**
   * Returns the strategy to use for the given MIME content-type.
   *
   * @param contentType the asset's MIME type; may be null (treated as unknown)
   * @return the selected strategy, never null
   */
  VenueExtractionStrategy select(final String contentType) {
    if (richDocument.supports(contentType)) {
      log.debug("selected RichDocumentExtractionStrategy for content-type={}", contentType);
      return richDocument;
    }
    if (tabularData.supports(contentType)) {
      log.debug("selected TabularDataExtractionStrategy for content-type={}", contentType);
      return tabularData;
    }
    log.debug("selected NoInferenceStrategy for content-type={}", contentType);
    return noInference;
  }
}

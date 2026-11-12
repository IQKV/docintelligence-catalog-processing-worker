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
import com.iqkv.docintelligence.venue.VenueAsset;

/**
 * Strategy for deriving a tenant {@link Venue} from an uploaded document asset.
 *
 * <p>Implementations are selected by {@link VenueExtractionStrategyRouter} based on the
 * asset's {@code contentType}. Each strategy is responsible for downloading, parsing, and
 * classifying the file in whatever way is appropriate for its content type.
 */
interface VenueExtractionStrategy {

  /**
   * Returns {@code true} when this strategy can handle the given MIME content-type.
   *
   * @param contentType the asset's MIME type as stored (e.g. {@code application/pdf})
   */
  boolean supports(String contentType);

  /**
   * Derives a fully populated {@link Venue} and the raw text extracted from the asset.
   *
   * <p>The returned venue has a fresh random id and {@code source = "FILE_IMPORT"} but is
   * not yet persisted. The caller (the processing service) handles persistence, embedding,
   * and notification after this method returns.
   *
   * <p>{@code rawText} is {@code null} when the strategy cannot extract text (e.g. images).
   * The caller creates a {@code VenueTextContent} row only when {@code rawText} is non-blank.
   *
   * @param asset the confirmed upload asset — provides s3Key, fileName, contentType
   * @return extraction result containing the derived venue and the raw text (nullable)
   * @throws Exception when the file cannot be read, parsed, or classified fatally
   */
  VenueExtractionResult derive(VenueAsset asset) throws Exception;

  /**
   * Pairs the derived {@link Venue} with the raw text that produced it.
   *
   * @param venue   the derived venue, never {@code null}
   * @param rawText the full text extracted from the source document, or {@code null} when no
   *                text extraction was possible (e.g. image assets)
   */
  record VenueExtractionResult(Venue venue, String rawText) {
  }
}

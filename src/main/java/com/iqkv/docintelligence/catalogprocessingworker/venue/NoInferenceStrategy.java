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
import org.springframework.stereotype.Component;

/**
 * Fallback extraction strategy for content types that carry no structured text suitable for
 * LLM inference: images (JPEG, PNG, WebP, TIFF, GIF) and any other unrecognised MIME type.
 *
 * <p>No file is downloaded, no Tika parsing runs, and no LLM is called. The venue is created
 * with the filename (sans extension) as its name and a low confidence score ({@code 0.1}) to
 * signal that no machine-readable data was extracted. The user is expected to edit the record
 * manually after the asset is linked.
 *
 * <p>This strategy always reports {@code supports()} as {@code true} and is registered last
 * in {@link VenueExtractionStrategyRouter} so it acts as the catch-all.
 */
@Component
class NoInferenceStrategy implements VenueExtractionStrategy {

  private final VenueExtractionContext ctx;

  NoInferenceStrategy(final VenueExtractionContext ctx) {
    this.ctx = ctx;
  }

  /**
   * Always returns {@code true} — this is the catch-all fallback.
   */
  @Override
  public boolean supports(final String contentType) {
    return true;
  }

  @Override
  public VenueExtractionResult derive(final VenueAsset asset) {
    final Venue venue = ctx.newVenueSkeleton();
    venue.setName(VenueExtractionContext.truncate(
        VenueExtractionContext.fileNameBase(asset.getFileName())));
    venue.setConfidence(VenueExtractionContext.CONFIDENCE_NONE);
    venue.setMetadata("{\"_schema_version\":1}");
    // No text extraction for image/unknown assets — rawText is null.
    return new VenueExtractionResult(venue, null);
  }
}

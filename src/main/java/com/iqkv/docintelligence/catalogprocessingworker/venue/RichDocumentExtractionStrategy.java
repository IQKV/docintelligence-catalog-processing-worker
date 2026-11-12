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

import java.util.Set;

import com.iqkv.docintelligence.venue.Venue;
import com.iqkv.docintelligence.venue.VenueAsset;
import org.springframework.stereotype.Component;

/**
 * Extraction strategy for rich text documents: PDF, Word (DOC/DOCX), and plain text.
 *
 * <p>Downloads the file, extracts full prose text with Apache Tika, and runs the full
 * {@link DocumentClassifier} pipeline to populate all venue fields. This strategy produces
 * the highest-quality extractions because rich documents typically contain complete venue
 * descriptions, pricing, capacity tables, and amenity lists.
 */
@Component
class RichDocumentExtractionStrategy implements VenueExtractionStrategy {

  private static final Set<String> SUPPORTED = Set.of(
      "application/pdf",
      "application/msword",
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
      "text/plain"
  );

  private final VenueExtractionContext ctx;
  private final DocumentClassifier classifier;

  RichDocumentExtractionStrategy(final VenueExtractionContext ctx,
                                  final DocumentClassifier classifier) {
    this.ctx = ctx;
    this.classifier = classifier;
  }

  @Override
  public boolean supports(final String contentType) {
    return contentType != null && SUPPORTED.contains(contentType);
  }

  @Override
  public VenueExtractionResult derive(final VenueAsset asset) throws Exception {
    final byte[] bytes = ctx.download(asset.getS3Key());
    final String text = ctx.extractText(bytes);
    final VenueExtraction extraction = classifier.classify(text, asset.getFileName());

    final Venue venue = ctx.newVenueSkeleton();
    venue.setName(VenueExtractionContext.resolveName(extraction, text, asset.getFileName()));
    venue.setAddress(extraction != null ? extraction.address() : null);
    venue.setCity(extraction != null ? extraction.city() : null);
    venue.setLocationLat(extraction != null && extraction.locationLat() != null
        ? java.math.BigDecimal.valueOf(extraction.locationLat()) : null);
    venue.setLocationLng(extraction != null && extraction.locationLng() != null
        ? java.math.BigDecimal.valueOf(extraction.locationLng()) : null);
    venue.setConfidence(VenueExtractionContext.resolveConfidence(extraction));
    venue.setMetadata(ctx.buildMetadata(extraction));
    return new VenueExtractionResult(venue, text.isBlank() ? null : text);
  }
}

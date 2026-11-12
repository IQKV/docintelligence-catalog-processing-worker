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
 * Extraction strategy for tabular data: CSV, XLS, and XLSX.
 *
 * <p>Downloads the file, converts it to text with Apache Tika, and passes it to
 * {@link DocumentClassifier} with a structural hint that tells the model the content
 * came from a spreadsheet. This improves extraction quality because tabular documents
 * typically encode capacity configurations, pricing tiers, and room layouts as rows/columns
 * rather than prose.
 */
@Component
class TabularDataExtractionStrategy implements VenueExtractionStrategy {

  private static final Set<String> SUPPORTED = Set.of(
      "text/csv",
      "application/csv",
      "application/vnd.ms-excel",
      "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
  );

  /**
   * Prepended to the user message so the model knows the content is tabular.
   * This avoids creating a separate system prompt; the preamble provides context inline.
   */
  private static final String TABULAR_HINT =
      "The following text was extracted from a spreadsheet or CSV file. "
      + "Rows may represent rooms, packages, or configurations. "
      + "Column headers often label field names. "
      + "Extract the overall venue record — not individual room rows.";

  private final VenueExtractionContext ctx;
  private final DocumentClassifier classifier;

  TabularDataExtractionStrategy(final VenueExtractionContext ctx,
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
    final VenueExtraction extraction = classifier.classify(text, asset.getFileName(), TABULAR_HINT);

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

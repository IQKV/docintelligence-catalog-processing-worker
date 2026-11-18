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
 * Shared state passed through the {@link VenueProcessingFilter} chain.
 *
 * @param asset    the asset being processed
 * @param venue    the venue derived by the extraction strategy
 * @param rawText  the raw text extracted from the asset, or {@code null} when unavailable
 * @param newVenue {@code true} when the venue is being created for the first time from this
 *                 asset; {@code false} when the asset is an attachment on an already-existing
 *                 venue. Filters that only make sense at venue-creation time (e.g. web
 *                 enrichment) should short-circuit when this is {@code false}.
 */
record VenueProcessingContext(VenueAsset asset, Venue venue, String rawText, boolean newVenue) {
}

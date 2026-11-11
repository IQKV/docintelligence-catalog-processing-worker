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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Structured extraction result produced by the LLM from a venue document.
 *
 * <p>All fields are nullable — the model returns {@code null} for any field it cannot locate
 * in the source text. The caller applies a heuristic fallback chain for required fields
 * ({@code name}) before persisting.
 *
 * <p>{@code confidence} is the model's self-assessed extraction quality on a 0–1 scale; callers
 * clamp it to [0.0, 1.0] and fall back to {@code 0.3} when the model omits it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record VenueExtraction(
    String name,
    String address,
    String city,
    Double locationLat,
    Double locationLng,
    Double confidence,
    String description,
    Integer maxCapacity,
    Integer standingCapacity,
    Integer seatedCapacity,
    String venueType,
    String currency,
    String priceRangeHint,
    String priceMethod,
    Boolean wifi,
    Boolean airConditioning,
    Boolean naturalLight,
    Boolean privateBar,
    Boolean privateEntrance,
    Boolean privateToilets,
    Boolean parkingAvailable,
    Boolean builtInAv,
    Boolean projector,
    Boolean flatscreenTv,
    Boolean whiteboard,
    String noiseRestrictionAfter,
    String alcoholLicenceUntil,
    String cateringPolicy,
    Boolean veganAvailable,
    Boolean vegetarianAvailable,
    Boolean glutenFreeAvailable
) {
}

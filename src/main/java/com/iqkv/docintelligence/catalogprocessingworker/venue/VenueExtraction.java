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
 *
 * <p>Field names and allowed values are aligned with the front-end metadata spec
 * ({@code metadata-spec.ts}) so extracted values map directly to displayable metadata rows.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record VenueExtraction(
    // ── Core venue identity ───────────────────────────────────────────────────
    String name,
    String address,
    String city,
    Double locationLat,
    Double locationLng,
    Double confidence,
    String description,
    /** Free-text location context not captured by the address (e.g. transport access). */
    String locationNotes,
    /** Comma-separated amenity tags (e.g. "Rooftop terrace, Built-in bar"). */
    String amenities,

    // ── Capacity ──────────────────────────────────────────────────────────────
    Integer maxCapacity,
    Integer standingCapacity,
    Integer seatedCapacity,
    Integer classroomCapacity,
    Integer conferenceCapacity,

    // ── Venue type (must match spec enum exactly) ─────────────────────────────
    /**
     * One of the 22 allowed values from the metadata spec, e.g. "Bar or lounge",
     * "Rooftop", "Conference centre". Use "Unclassified" when none fits.
     */
    String venueType,

    // ── Catering ──────────────────────────────────────────────────────────────
    /**
     * One of: "In-house catering only", "External catering only",
     * "In-house and external catering both allowed", "Catering not specified".
     */
    String cateringPolicy,
    Boolean kosherAvailable,
    Boolean halalAvailable,
    Boolean veganAvailable,

    // ── AV & technology ───────────────────────────────────────────────────────
    Boolean builtInAv,
    Boolean wifi,
    Boolean projector,
    Boolean riggingPoints,
    Integer internetBandwidthMbps,

    // ── Accessibility ─────────────────────────────────────────────────────────
    Boolean adaCompliant,
    Boolean elevatorAccess,
    Boolean accessibleRestrooms,
    Boolean wheelchairStageAccess,

    // ── Outdoor space ─────────────────────────────────────────────────────────
    Boolean outdoorSpaceAvailable,
    Boolean outdoorSpaceCovered,
    Integer outdoorSpaceSqm,

    // ── Exclusivity ───────────────────────────────────────────────────────────
    Boolean exclusiveUse,
    Boolean sharedSpace,

    // ── Logistics ─────────────────────────────────────────────────────────────
    /** Noise curfew in 24-hour HH:mm format (e.g. "23:00"). */
    String curfewTime,
    String loadInAccess,
    Integer parkingSpaces,
    Boolean valetAvailable,
    Double setupHoursBefore,
    Double teardownHoursAfter,

    // ── Pricing ───────────────────────────────────────────────────────────────
    /** ISO 4217 code derived from symbol when not explicit: £→GBP, €→EUR, $→USD. */
    String currency,
    Double minimumSpend,
    Double rentalFeeIndicative,

    // ── Online / social ───────────────────────────────────────────────────────
    /** Instagram handle including the @ prefix (e.g. "@venuename"). */
    String instagramHandle,
    Double googleRating,
    Integer googleReviewCount
) {
}

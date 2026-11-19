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
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * LLM-based classifier that extracts structured venue data from plain document text.
 *
 * <p>This component owns all prompt-engineering, field-mapping rules and schema constraints
 * for the venue extraction task. {@link VenueAssetProcessingService} delegates to it and
 * treats the result as a nullable {@link VenueExtraction} — this class never throws; it returns
 * {@code null} when the model call fails or produces unusable output so callers can apply
 * the heuristic fallback chain.
 *
 * <h2>Field alignment</h2>
 * <p>All enum values and field names in the system prompt match the front-end metadata spec
 * ({@code metadata-spec.ts}) verbatim so extracted values map directly to displayable metadata
 * rows without a translation layer.
 *
 * <h2>Text handling</h2>
 * <p>The caller must normalise and truncate the text before passing it here (see
 * {@link VenueExtractionContext#normaliseAndTruncate}). The first {@value #TEXT_MAX_CHARS}
 * characters after normalisation are sent to the model.
 * shortcut: fixed character cap; upgrade to sliding-window chunking for large decks.
 */
@Component
class DocumentClassifier {

  private static final Logger log = LoggerFactory.getLogger(DocumentClassifier.class);

  /**
   * Maximum characters of normalised document text forwarded to the model.
   * Keeps requests within typical Ollama context windows (~8 k tokens ≈ 32 k chars).
   */
  static final int TEXT_MAX_CHARS = 8_000;

  private static final String SYSTEM_PROMPT = """
      You are a structured data extraction assistant specialised in venue brochures,
      capacity sheets, rate cards, and event-space flyers.
      Extract venue information from the document text into a single JSON object.

      OUTPUT CONTRACT
      ───────────────
      • Return ONLY a valid JSON object — no markdown, no code fences, no explanation.
      • Use JSON null (not the string "null") for any field you cannot find.
      • Never invent data; only extract what is explicitly stated or clearly implied.
      • All string values must be trimmed of leading/trailing whitespace.

      FIELD RULES
      ───────────
      name           : Trading name of the whole venue (not a room or event). First large heading.
                       Max 255 chars.
      address        : Full street address as written. Include building name. Max 500 chars.
      city           : City only — no county, no country.
      locationLat    : Decimal degrees latitude from an embedded map or coordinates block.
      locationLng    : Decimal degrees longitude.
      confidence     : Float 0.0–1.0. How completely the document describes ONE physical venue.
                       Deduct for multi-room-only decks, missing address, or ambiguous names.
      description    : ONE "About" paragraph — 1–2 sentences, max 300 chars — saying what the
                       venue IS, what it OFFERS and its CHARACTER, in the style of a Google Maps
                       place description. No room detail, prices, or contact info.
      category       : EXACTLY one of:
                       Restaurant | Hotel | Bar or lounge | Rooftop | Gallery |
                       Warehouse or industrial space | Studio |
                       Outdoor garden or terrace | Town hall or community centre |
                       Conference centre | Stadium or arena | Museum |
                       Theatre or cinema | Boat or yacht | Marquee, tipi or barn |
                       Park, zoo or gardens | Place of worship |
                       Private members club | Nightclub |
                       Historic house or estate | Beach or coastal venue | Unclassified
                       Pick the closest match; use Unclassified when none fits.
      country        : Country name only, max 100 chars.
      positioningLine: One concise client-facing positioning line, max 160 chars.
      searchTags     : JSON array of concise, comma-free search tags. Use [] when none are stated.
      locationNotes  : Context not captured by the address (e.g. transport access, freight). Max 200 chars.
      amenities      : Comma-separated amenity tags visible in the document
                       (e.g. "Rooftop terrace, Built-in bar, Green room").

      maxCapacity    : Largest total headcount for the whole venue. Positive integer.
      standingCapacity : Standing/cocktail figure when stated.
      seatedCapacity   : Seated/banquet figure when stated.
      classroomCapacity : Classroom layout figure when stated.
      conferenceCapacity : Boardroom/conference layout figure when stated.
      cocktailCapacity : Cocktail configuration capacity when stated.
      rooms          : JSON array of room objects with name, seated, standing and notes.
                       Use [] when the document has no room breakdown.
      floorPlanAccess: Vehicle access, loading, and floor-plan constraints, max 1000 chars.

      cateringPolicy : EXACTLY one of:
                       In-house catering only | External catering only |
                       In-house and external catering both allowed | Catering not specified
      cateringNotes  : Kitchen facilities, alcohol permissions, and service arrangements, max 1500 chars.
      cateringFormats: JSON array of the catering formats stated in the document. Use [] when none.

      kosherAvailable, halalAvailable, veganAvailable,
      builtInAv, wifi, projector, riggingPoints,
      adaCompliant, elevatorAccess, accessibleRestrooms, wheelchairStageAccess,
      outdoorSpaceAvailable, outdoorSpaceCovered,
      exclusiveUse, sharedSpace,
      valetAvailable :
                       true when confirmed, false when explicitly denied, null when not mentioned.

      internetBandwidthMbps : Integer Mbps when stated.
      outdoorSpaceSqm       : Outdoor area in square metres when stated.
      technicalNotes        : Production capabilities, restrictions, and supplier arrangements, max 1500 chars.
      technicalSpecifications: JSON array of objects with name and value. Use [] when none are listed.
      parkingSpaces         : Number of parking spaces when stated.
      setupHoursBefore      : Setup time in hours before the event (decimal).
      teardownHoursAfter    : Teardown time in hours after the event (decimal).

      curfewTime     : Noise curfew in 24-hour HH:mm (e.g. 23:00).
      loadInAccess   : Load-in access description (e.g. "Freight elevator, rear access").

      currency       : ISO 4217 code. Derive from symbol: £→GBP, €→EUR, $→USD.
      minimumSpend   : Minimum F&B or hire spend as a number (no currency symbol).
      rentalFeeIndicative : Indicative hire fee as a number (no currency symbol).
      priceLines     : JSON array of objects with name, amount and unit. Use [] when no price lines
                       are stated. Amount must be numeric and unit should preserve wording such as
                       "per day" or "per hour".
      commercialTerms: Commercial terms that affect the hire price, max 1500 chars.

      instagramHandle     : Instagram handle including @ (e.g. @venuename).
      googleRating        : Numeric rating 0.0–5.0.
      googleReviewCount   : Integer review count.
      """;

  private final ChatClient chatClient;

  DocumentClassifier(final ChatClient.Builder chatClientBuilder) {
    this.chatClient = chatClientBuilder.build();
  }

  /**
   * Classifies the venue document text and returns a structured {@link VenueExtraction}.
   *
   * <p>The caller must pass already-normalised and truncated text (see
   * {@link VenueExtractionContext#normaliseAndTruncate}).
   * Returns {@code null} when the text is blank, the model throws, or the output cannot
   * be deserialised. The caller applies the heuristic fallback chain.
   *
   * @param text     normalised plain text from the document; may be null or blank
   * @param fileName original file name, used only for log context
   * @return a populated {@link VenueExtraction}, or {@code null} on failure
   */
  VenueExtraction classify(final String text, final String fileName) {
    return classify(text, fileName, null);
  }

  /**
   * Classifies the venue document text with an optional context hint prepended to the user
   * message. Use this overload when the document type provides structural context the model
   * benefits from knowing (e.g. "The text below was extracted from a CSV spreadsheet").
   *
   * @param text     normalised plain text from the document; may be null or blank
   * @param fileName original file name, used only for log context
   * @param hint     optional preamble prepended to the user message; {@code null} to omit
   * @return a populated {@link VenueExtraction}, or {@code null} on failure
   */
  VenueExtraction classify(final String text, final String fileName, final String hint) {
    if (text == null || text.isBlank()) {
      log.debug("skipping LLM classification for {} — no extractable text", fileName);
      return null;
    }

    final String input = hint != null && !hint.isBlank() ? hint + "\n\n" + text : text;
    // Safety-net truncation: strategies call normaliseAndTruncate before classify, but
    // callers that bypass the strategy (e.g. tests) should still be bounded correctly.
    final String bounded = VenueExtractionContext.normaliseAndTruncate(input);
    try {
      final VenueExtraction result = chatClient
          .prompt()
          .system(SYSTEM_PROMPT)
          .user(bounded)
          .call()
          .entity(VenueExtraction.class);

      log.debug("classified {}: name={}, category={}, confidence={}",
          fileName,
          result != null ? result.name() : null,
          result != null ? result.category() : null,
          result != null ? result.confidence() : null);
      return result;
    } catch (final Exception ex) {
      log.warn("LLM classification failed for {} — falling back to heuristics: {}",
          fileName, ex.getMessage());
      return null;
    }
  }
}

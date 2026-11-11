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
 * <h2>Extraction rules (encoded in the prompt)</h2>
 * <ul>
 *   <li><b>name</b> — the venue's trading name, not a room or event name. Prefer the
 *       largest heading on the first page. Max 255 characters.</li>
 *   <li><b>address</b> — full street address as written. Include building name when present.
 *       Omit country when already in city. Max 500 characters.</li>
 *   <li><b>city</b> — city only, no county or country.</li>
 *   <li><b>locationLat / locationLng</b> — decimal degrees from an embedded map or
 *       coordinates block; {@code null} when not present.</li>
 *   <li><b>confidence</b> — 0.0–1.0 reflecting how completely the document describes a
 *       single physical venue. Deduct for multi-room decks, missing address, or ambiguous
 *       names.</li>
 *   <li><b>description</b> — marketing prose describing the venue as a whole. Max 2 000
 *       characters. Omit room-level descriptions.</li>
 *   <li><b>maxCapacity</b> — largest headcount figure stated for the whole venue.
 *       Positive integer or {@code null}.</li>
 *   <li><b>standingCapacity / seatedCapacity</b> — configuration-specific figures when
 *       stated; {@code null} otherwise.</li>
 *   <li><b>venueType</b> — one of:
 *       {@code Restaurant}, {@code Bar}, {@code Hotel}, {@code Conference Centre},
 *       {@code Event Space}, {@code Outdoor}, {@code Museum}, {@code Gallery},
 *       {@code Sports Venue}, {@code Rooftop}, {@code Other}.
 *       Pick the closest match; use {@code Other} when none fits.</li>
 *   <li><b>currency</b> — ISO 4217 code (e.g. {@code GBP}, {@code EUR}, {@code USD});
 *       derive from symbol (£→GBP, €→EUR, $→USD) when the code is not explicit.</li>
 *   <li><b>priceRangeHint</b> — the price statement as written (e.g.
 *       "from £10 per person"). Max 100 characters.</li>
 *   <li><b>priceMethod</b> — one of: {@code Per person}, {@code Flat rate},
 *       {@code Per hour}, {@code Per day}, {@code Minimum spend}; derive from context.</li>
 *   <li><b>noiseRestrictionAfter / alcoholLicenceUntil</b> — 24-hour time string
 *       {@code HH:mm} (e.g. {@code 23:00}); {@code null} when not stated.</li>
 *   <li><b>cateringPolicy</b> — one of: {@code In-house catering only},
 *       {@code External catering allowed}, {@code BYO allowed}, {@code No catering};
 *       derive from policy text.</li>
 *   <li><b>Boolean fields</b> ({@code wifi}, {@code airConditioning}, {@code naturalLight},
 *       {@code privateBar}, {@code privateEntrance}, {@code privateToilets},
 *       {@code parkingAvailable}, {@code builtInAv}, {@code projector},
 *       {@code flatscreenTv}, {@code whiteboard}, {@code veganAvailable},
 *       {@code vegetarianAvailable}, {@code glutenFreeAvailable}) —
 *       {@code true} when the document confirms the feature, {@code false} when explicitly
 *       denied, {@code null} when not mentioned.</li>
 * </ul>
 *
 * <h2>Text handling</h2>
 * <p>The first {@value #TEXT_MAX_CHARS} characters of the extracted text are sent to the
 * model. If the document produces less, the full text is used. The truncation point is
 * chosen to avoid cutting mid-word.
 * shortcut: fixed character cap; upgrade to sliding-window chunking for large decks.
 */
@Component
class DocumentClassifier {

  private static final Logger log = LoggerFactory.getLogger(DocumentClassifier.class);

  /**
   * Maximum characters of document text forwarded to the model.
   * Keeps requests within typical Ollama context windows (~8 k tokens ≈ 32 k chars).
   */
  static final int TEXT_MAX_CHARS = 8_000;

  /**
   * System message that sets the model's role and output contract.
   */
  private static final String SYSTEM_PROMPT = """
      You are a structured data extraction assistant specialised in venue brochures,
      capacity sheets, rate cards, and event-space flyers.
      Your task is to read the document text provided by the user and extract venue information
      into a single JSON object.

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
      description    : Marketing prose for the whole venue. Max 2000 chars. No room descriptions.
      maxCapacity    : Largest total headcount for the whole venue. Positive integer.
      standingCapacity : Standing-only figure when stated.
      seatedCapacity   : Seated/banquet figure when stated.
      venueType      : EXACTLY one of: Restaurant | Bar | Hotel | Conference Centre |
                       Event Space | Outdoor | Museum | Gallery | Sports Venue | Rooftop | Other
      currency       : ISO 4217 code. Derive from symbol: £→GBP, €→EUR, $→USD.
      priceRangeHint : Price statement as written. Max 100 chars.
      priceMethod    : EXACTLY one of: Per person | Flat rate | Per hour | Per day |
                       Minimum spend
      noiseRestrictionAfter : 24-hour HH:mm (e.g. 23:00).
      alcoholLicenceUntil   : 24-hour HH:mm (e.g. 01:30).
      cateringPolicy : EXACTLY one of: In-house catering only | External catering allowed |
                       BYO allowed | No catering
      wifi, airConditioning, naturalLight, privateBar, privateEntrance, privateToilets,
      parkingAvailable, builtInAv, projector, flatscreenTv, whiteboard,
      veganAvailable, vegetarianAvailable, glutenFreeAvailable :
                       true when confirmed, false when explicitly denied, null when not mentioned.
      """;

  private final ChatClient chatClient;

  DocumentClassifier(final ChatClient.Builder chatClientBuilder) {
    this.chatClient = chatClientBuilder.build();
  }

  /**
   * Classifies the venue document text and returns a structured {@link VenueExtraction}.
   *
   * <p>Returns {@code null} when the text is blank, the model throws, or the output cannot
   * be deserialised. The caller applies the heuristic fallback chain.
   *
   * @param text     plain text from the document; may be null or blank
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
   * @param text     plain text from the document; may be null or blank
   * @param fileName original file name, used only for log context
   * @param hint     optional preamble prepended to the user message; {@code null} to omit
   * @return a populated {@link VenueExtraction}, or {@code null} on failure
   */
  VenueExtraction classify(final String text, final String fileName, final String hint) {
    if (text == null || text.isBlank()) {
      log.debug("skipping LLM classification for {} — no extractable text", fileName);
      return null;
    }

    final String truncated = truncate(text);
    final String input = hint != null && !hint.isBlank() ? hint + "\n\n" + truncated : truncated;
    try {
      final VenueExtraction result = chatClient
          .prompt()
          .system(SYSTEM_PROMPT)
          .user(input)
          .call()
          .entity(VenueExtraction.class);

      log.debug("classified {}: name={}, venueType={}, confidence={}",
          fileName,
          result != null ? result.name() : null,
          result != null ? result.venueType() : null,
          result != null ? result.confidence() : null);
      return result;
    } catch (final Exception ex) {
      log.warn("LLM classification failed for {} — falling back to heuristics: {}",
          fileName, ex.getMessage());
      return null;
    }
  }

  /**
   * Truncates the text to {@value #TEXT_MAX_CHARS} characters without splitting a word.
   */
  private static String truncate(final String text) {
    if (text.length() <= TEXT_MAX_CHARS) {
      return text;
    }
    // Walk back from the cap to the nearest whitespace so we don't cut mid-word.
    int cut = TEXT_MAX_CHARS;
    while (cut > 0 && !Character.isWhitespace(text.charAt(cut))) {
      cut--;
    }
    return cut > 0 ? text.substring(0, cut) : text.substring(0, TEXT_MAX_CHARS);
  }
}

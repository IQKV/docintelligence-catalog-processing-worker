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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Verifies extracted fields are serialized using the venue UI metadata row format.
 */
@DisplayName("VenueExtractionContext metadata mapping")
class VenueExtractionContextTest {

  private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

  @Test
  @DisplayName("writes profile, repeatable, and client-facing metadata rows")
  void writesNewVenueDetailFieldsAsMetadataRows() throws Exception {
    final VenueExtractionContext context = new VenueExtractionContext(null, null, JSON_MAPPER);
    final String extractionJson = """
        {
          "category": "Warehouse or industrial space",
          "country": "Romania",
          "positioningLine": "Raw brick and steel for product launches",
          "searchTags": ["industrial", "large scale"],
          "rooms": [
            {"name": "Main Floor", "seated": 300, "standing": 800, "notes": "Uncolumned"},
            {"name": "Mezzanine", "seated": 60, "standing": 120, "notes": "Overlooks the main floor"}
          ],
          "floorPlanAccess": "3.2 x 3.6 m roller door",
          "cateringNotes": "Permanent service kitchen",
          "cateringFormats": ["Food trucks", "Seated dinner"],
          "technicalNotes": "Bare-bones technical",
          "technicalSpecifications": [
            {"name": "Clearance", "value": "9 m under trusses"}
          ],
          "priceLines": [
            {"name": "Main floor, exclusive", "amount": 4200, "unit": "per day"}
          ],
          "commercialTerms": "Multi-day builds are charged at 50% from the second day."
        }
        """;
    final VenueExtraction extraction =
        JSON_MAPPER.readValue(extractionJson, VenueExtraction.class);

    final JsonNode items = JSON_MAPPER.readTree(context.buildMetadata(extraction)).path("items");

    assertThat(value(items, "profile", "category").asText())
        .isEqualTo("Warehouse or industrial space");
    assertThat(values(items, "profile", "search_tags")).containsExactly("industrial", "large scale");
    assertThat(values(items, "catering", "formats")).containsExactly("Food trucks", "Seated dinner");
    assertThat(matchingRows(items, "capacity", "rooms")).hasSize(2);
    assertThat(value(items, "capacity", "rooms").path("name").asText()).isEqualTo("Main Floor");
    assertThat(value(items, "technical", "specifications").path("value").asText())
        .isEqualTo("9 m under trusses");
    assertThat(value(items, "pricing", "price_lines").path("amount").asDouble()).isEqualTo(4200);
    assertThat(value(items, "pricing", "commercial_terms").asText())
        .contains("50% from the second day");
  }

  private static JsonNode value(final JsonNode items, final String group, final String field) {
    return matchingRows(items, group, field).getFirst().path("value");
  }

  private static List<JsonNode> matchingRows(
      final JsonNode items, final String group, final String field) {
    return StreamSupport.stream(items.spliterator(), false)
        .filter(item -> group.equals(item.path("group").asText())
            && field.equals(item.path("field").asText()))
        .toList();
  }

  private static List<String> values(final JsonNode items, final String group, final String field) {
    return matchingRows(items, group, field).stream()
        .map(item -> item.path("value").asText())
        .toList();
  }
}

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
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import com.iqkv.docintelligence.venue.Venue;
import com.iqkv.docintelligence.venue.VenueAsset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

@ExtendWith(MockitoExtension.class)
@DisplayName("GoogleSearchEnrichFilter Unit Tests")
class GoogleSearchEnrichFilterTest {

  // A minimal HTML fragment that matches both SNIPPET_PATTERN and ADDRESS_PATTERN.
  private static final String GOOGLE_HTML =
      "<div class=\"BNeawe tAd8D AP7Wnd\">A luxury hotel in the heart of the city with stunning views.</div>"
          + "<div class=\"BNeawe\">123 Main Street, Vienna, Austria</div>";

  @Mock
  private RestClient restClient;
  @Mock
  private RestClient.RequestHeadersUriSpec<?> uriSpec;
  @Mock
  private RestClient.RequestHeadersSpec<?> headersSpec;
  @Mock
  private RestClient.ResponseSpec responseSpec;

  private GoogleSearchEnrichFilter filter;

  @BeforeEach
  void setUp() {
    filter = new GoogleSearchEnrichFilter(restClient);
  }

  private static VenueProcessingContext ctx(final Venue venue) {
    final VenueAsset asset = new VenueAsset();
    asset.setId(UUID.randomUUID());
    return new VenueProcessingContext(asset, venue, null);
  }

  private static Venue venue(final String name, final String city) {
    final Venue v = new Venue();
    v.setId(UUID.randomUUID());
    v.setName(name);
    v.setCity(city);
    return v;
  }

  @SuppressWarnings("unchecked")
  private void stubHtml(final String html) {
    when(restClient.get()).thenReturn((RestClient.RequestHeadersUriSpec) uriSpec);
    when(uriSpec.uri(anyString())).thenReturn((RestClient.RequestHeadersSpec) headersSpec);
    when(headersSpec.header(anyString(), anyString())).thenReturn((RestClient.RequestHeadersSpec) headersSpec);
    when(headersSpec.retrieve()).thenReturn(responseSpec);
    when(responseSpec.body(String.class)).thenReturn(html);
  }

  // ── skip conditions ───────────────────────────────────────────────────────

  @Test
  @DisplayName("skips when venue name is blank")
  void skipsOnBlankName() {
    final Venue v = venue("", "Vienna");
    filter.apply(ctx(v));
    verify(restClient, never()).get();
  }

  @Test
  @DisplayName("skips when venue name is null")
  void skipsOnNullName() {
    final Venue v = venue(null, "Vienna");
    filter.apply(ctx(v));
    verify(restClient, never()).get();
  }

  // ── happy path ────────────────────────────────────────────────────────────

  @Test
  @DisplayName("fills blank description from Google snippet")
  void fillsBlankDescription() {
    stubHtml(GOOGLE_HTML);
    final Venue v = venue("Grand Hotel", "Vienna");

    filter.apply(ctx(v));

    assertThat(v.getDescription()).isEqualTo(
        "A luxury hotel in the heart of the city with stunning views.");
  }

  @Test
  @DisplayName("fills blank address from Google snippet")
  void fillsBlankAddress() {
    stubHtml(GOOGLE_HTML);
    final Venue v = venue("Grand Hotel", "Vienna");

    filter.apply(ctx(v));

    assertThat(v.getAddress()).isEqualTo("123 Main Street, Vienna, Austria");
  }

  @Test
  @DisplayName("does not overwrite an already-set description")
  void doesNotOverwriteExistingDescription() {
    stubHtml(GOOGLE_HTML);
    final Venue v = venue("Grand Hotel", "Vienna");
    v.setDescription("Existing description.");

    filter.apply(ctx(v));

    assertThat(v.getDescription()).isEqualTo("Existing description.");
  }

  @Test
  @DisplayName("does not overwrite an already-set address")
  void doesNotOverwriteExistingAddress() {
    stubHtml(GOOGLE_HTML);
    final Venue v = venue("Grand Hotel", "Vienna");
    v.setAddress("99 Known Street, Vienna");

    filter.apply(ctx(v));

    assertThat(v.getAddress()).isEqualTo("99 Known Street, Vienna");
  }

  @Test
  @DisplayName("builds query without city when city is blank")
  void buildsQueryWithoutCity() {
    stubHtml(GOOGLE_HTML);
    final Venue v = venue("Grand Hotel", null);

    assertThatNoException().isThrownBy(() -> filter.apply(ctx(v)));
    assertThat(v.getDescription()).isNotBlank();
  }

  // ── failure resilience ────────────────────────────────────────────────────

  @Test
  @DisplayName("swallows HTTP errors without throwing")
  void swallowsHttpError() {
    when(restClient.get()).thenReturn((RestClient.RequestHeadersUriSpec) uriSpec);
    when(uriSpec.uri(anyString())).thenReturn((RestClient.RequestHeadersSpec) headersSpec);
    when(headersSpec.header(anyString(), anyString())).thenReturn((RestClient.RequestHeadersSpec) headersSpec);
    when(headersSpec.retrieve()).thenThrow(new RuntimeException("connection refused"));

    final Venue v = venue("Grand Hotel", "Vienna");
    assertThatNoException().isThrownBy(() -> filter.apply(ctx(v)));
    assertThat(v.getDescription()).isNull();
  }
}

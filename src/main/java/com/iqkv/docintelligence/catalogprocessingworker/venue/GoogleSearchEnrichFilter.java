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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.iqkv.docintelligence.venue.Venue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Best-effort enrichment step that fires a plain Google search for the venue name and city,
 * scrapes the HTML snippet for a description and address, and back-fills blank fields only.
 *
 * <p>Runs at {@code @Order(15)} — after {@link MasterVenueMatchFilter} (10) so the master
 * catalog has already filled what it can, and before {@link VenuePersistFilter} (20) so the
 * enriched values are persisted in the same insert.
 *
 * <p>Any failure (network, parse, rate-limit) is logged at WARN and swallowed — this filter
 * must never abort the pipeline.
 *
 * <p>shortcut: raw HTML scrape, no API key — Google may rate-limit under load; upgrade to
 * Custom Search JSON API when that becomes a problem.
 */
@Component
@Order(15)
class GoogleSearchEnrichFilter implements VenueProcessingFilter {

  private static final Logger log = LoggerFactory.getLogger(GoogleSearchEnrichFilter.class);

  private static final String SEARCH_URL = "https://www.google.com/search?q=";
  // Mimics a real browser so Google returns the full HTML snippet instead of a CAPTCHA page.
  private static final String USER_AGENT =
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
          + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

  // Matches the short description line Google renders under the result title.
  // shortcut: class name changes with Google's obfuscated CSS — re-verify periodically.
  private static final Pattern SNIPPET_PATTERN =
      Pattern.compile("<div class=\"BNeawe[^\"]*\"[^>]*>([^<]{20,300})</div>");

  // Matches a simple "Street, City" or "Street, City, Country" address in the snippet area.
  private static final Pattern ADDRESS_PATTERN =
      Pattern.compile("(\\d+[^<,]{3,60},\\s*[A-Za-z][^<,]{2,40}(?:,\\s*[A-Za-z][^<]{2,40})?)");

  private final RestClient restClient;

  GoogleSearchEnrichFilter() {
    this.restClient = RestClient.create();
  }

  // Test-only constructor — allows injecting a mock RestClient.
  GoogleSearchEnrichFilter(final RestClient restClient) {
    this.restClient = restClient;
  }

  @Override
  public void apply(final VenueProcessingContext ctx) {
    if (!ctx.newVenue()) {
      return;
    }
    final Venue venue = ctx.venue();
    if (venue.getName() == null || venue.getName().isBlank()) {
      return;
    }
    try {
      final String html = fetchSearchPage(venue.getName(), venue.getCity());
      if (html == null || html.isBlank()) {
        return;
      }
      if (venue.getDescription() == null || venue.getDescription().isBlank()) {
        final String snippet = firstMatch(SNIPPET_PATTERN, html);
        if (snippet != null) {
          venue.setDescription(snippet);
          log.debug("Google enriched description for venue '{}'", venue.getName());
        }
      }
      if (venue.getAddress() == null || venue.getAddress().isBlank()) {
        final String address = firstMatch(ADDRESS_PATTERN, html);
        if (address != null) {
          venue.setAddress(address);
          log.debug("Google enriched address for venue '{}'", venue.getName());
        }
      }
    } catch (final Exception ex) {
      log.warn("Google search enrichment skipped for venue '{}': {}", venue.getName(), ex.getMessage());
    }
  }

  private String fetchSearchPage(final String name, final String city) {
    final String query = URLEncoder.encode(
        city != null && !city.isBlank() ? name + " " + city : name,
        StandardCharsets.UTF_8);
    return restClient.get()
        .uri(SEARCH_URL + query)
        .header("User-Agent", USER_AGENT)
        .retrieve()
        .body(String.class);
  }

  private static String firstMatch(final Pattern pattern, final String input) {
    final Matcher m = pattern.matcher(input);
    return m.find() ? m.group(1).strip() : null;
  }
}

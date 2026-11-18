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
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Best-effort web research filter that searches DuckDuckGo Lite for a Cvent listing of the
 * newly derived venue, fetches the Cvent page when found, and stores the full page text as
 * a {@link com.iqkv.docintelligence.venue.VenueTextContent} row attached to the venue.
 *
 * <p>Steps:
 * <ol>
 *   <li>POST to {@code https://duckduckgo.com/lite/} with {@code q=cvent+{name}} — the Lite
 *       endpoint returns plain HTML with no JS, reliably accessible from servers.</li>
 *   <li>Parse the first result anchor whose href contains {@code cvent.com}.</li>
 *   <li>Fetch the Cvent page and strip HTML tags to produce plain prose text.</li>
 *   <li>Wrap the prose in Markdown with a heading and source attribution line.</li>
 *   <li>Persist via {@link VenueTextContentPersister#persistIfAbsent}, keyed on a
 *       deterministic synthetic asset UUID so a redelivered event is a no-op.</li>
 * </ol>
 *
 * <p>Runs at {@code @Order(45)} — after {@link TextContentPersistFilter} (40) so the
 * document text is already saved before the web source is added.
 *
 * <p>Only runs when {@link VenueProcessingContext#newVenue()} is {@code true}. Any failure
 * (network, parse, rate-limit) is logged at WARN and swallowed — this filter must never
 * abort the pipeline.
 *
 * <p>shortcut: synthetic sourceAssetId derived from venueId+"cvent" for idempotency — upgrade
 * to a real asset row if web sources ever need to be tracked as first-class assets.
 */
@Component
@Order(45)
class PrivateWebResearchSourceFileEnrichFilter implements VenueProcessingFilter {

  private static final Logger log = LoggerFactory.getLogger(PrivateWebResearchSourceFileEnrichFilter.class);

  private static final String DDG_LITE_URL = "https://lite.duckduckgo.com/lite/";
  private static final String CVENT_HOST = "cvent.com";
  private static final int TIMEOUT_MS = 30_000;

  // Mimics a real browser to avoid bot-detection rejections.
  private static final String USER_AGENT =
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
          + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

  // Matches result anchors in the DuckDuckGo Lite HTML table: <a class="result-link" href="...">
  private static final Pattern DDG_RESULT_HREF =
      Pattern.compile("<a[^>]+href=\"(https?://[^\"]*" + CVENT_HOST + "[^\"]*)\"");

  // Strips every HTML tag, leaving only text nodes. Applied after collapsing whitespace.
  private static final Pattern HTML_TAG = Pattern.compile("<[^>]{1,500}>");

  // Collapses runs of whitespace (including newlines from stripped tags) to single spaces.
  private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s{2,}");

  // Deterministic namespace UUID used to derive a synthetic sourceAssetId per venue+source.

  private final VenueTextContentPersister persister;
  private final RestClient restClient;

  @Autowired
  PrivateWebResearchSourceFileEnrichFilter(final VenueTextContentPersister persister) {
    this.persister = persister;
    this.restClient = buildRestClient();
  }

  // Test-only constructor — allows injecting a mock RestClient.
  PrivateWebResearchSourceFileEnrichFilter(final VenueTextContentPersister persister,
                                           final RestClient restClient) {
    this.persister = persister;
    this.restClient = restClient;
  }

  private static RestClient buildRestClient() {
    final var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofMillis(TIMEOUT_MS));
    factory.setReadTimeout(Duration.ofMillis(TIMEOUT_MS));
    return RestClient.builder().requestFactory(factory).build();
  }

  @Override
  public void apply(final VenueProcessingContext ctx) {
    if (!ctx.newVenue()) {
      return;
    }
    final String name = ctx.venue().getName();
    if (name == null || name.isBlank()) {
      return;
    }
    try {
      final String cventUrl = findCventUrl(name);
      if (cventUrl == null) {
        log.debug("no Cvent listing found for venue '{}'", name);
        return;
      }
      log.info("Cvent listing found for venue '{}': {}", name, cventUrl);
      final String pageHtml = fetchPage(cventUrl);
      if (pageHtml == null || pageHtml.isBlank()) {
        return;
      }
      final String markdown = toMarkdown(name, cventUrl, pageHtml);
      // Deterministic synthetic UUID: same venue + source always produces the same key,
      // so persistIfAbsent skips the insert on redelivery without a real asset row.
      final UUID syntheticAssetId = deterministicUuid(ctx.venue().getId(), CVENT_HOST);
      persister.persistIfAbsent(ctx.venue().getId(), syntheticAssetId,
          "cvent: " + name, markdown);
    } catch (final Exception ex) {
      log.warn("Cvent web research skipped for venue '{}': {}", name, ex.getMessage());
    }
  }

  private String findCventUrl(final String venueName) {
    final String body = "q=" + URLEncoder.encode("cvent " + venueName, StandardCharsets.UTF_8);
    final String html = restClient.post()
        .uri(DDG_LITE_URL)
        .header(HttpHeaders.USER_AGENT, USER_AGENT)
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .body(body)
        .retrieve()
        .body(String.class);
    if (html == null || html.isBlank()) {
      return null;
    }
    final Matcher m = DDG_RESULT_HREF.matcher(html);
    return m.find() ? m.group(1) : null;
  }

  private String fetchPage(final String url) {
    return restClient.get()
        .uri(url)
        .header(HttpHeaders.USER_AGENT, USER_AGENT)
        .retrieve()
        .body(String.class);
  }

  private static String toMarkdown(final String venueName, final String sourceUrl,
                                   final String html) {
    // Strip script/style blocks first so their contents don't pollute the prose.
    String text = html
        .replaceAll("(?si)<script[^>]*>.*?</script>", " ")
        .replaceAll("(?si)<style[^>]*>.*?</style>", " ");
    text = HTML_TAG.matcher(text).replaceAll(" ");
    text = WHITESPACE_RUN.matcher(text).replaceAll(" ").strip();
    return "# " + venueName + "\n\n"
        + "> Source: " + sourceUrl + "\n\n"
        + text;
  }

  /**
   * Derives a deterministic UUID from a venue UUID and a source label string using
   * XOR-folding of their combined bytes — not a standards-compliant UUID v5, but
   * sufficient for a stable idempotency key within this application.
   */
  private static UUID deterministicUuid(final UUID venueId, final String source) {
    final byte[] nameBytes = (venueId.toString() + ":" + source)
        .getBytes(StandardCharsets.UTF_8);
    long msb = 0L;
    long lsb = 0L;
    for (int i = 0; i < nameBytes.length; i++) {
      if (i % 2 == 0) {
        msb ^= ((long) nameBytes[i]) << ((i % 8) * 8);
      } else {
        lsb ^= ((long) nameBytes[i]) << ((i % 8) * 8);
      }
    }
    return new UUID(msb, lsb);
  }
}

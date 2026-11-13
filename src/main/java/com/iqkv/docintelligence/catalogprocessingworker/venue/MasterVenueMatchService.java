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

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.iqkv.docintelligence.venue.MasterVenue;
import com.iqkv.docintelligence.venue.MasterVenueMapper;
import com.iqkv.docintelligence.venue.Venue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Links a freshly derived tenant {@link Venue} to a similar {@code public.master_venue} row and
 * back-fills its blank scalar fields from it — the MC_INHERIT gap-fill step that runs after the
 * AI extraction and before the venue is persisted.
 *
 * <p>Resolution is two-stage. An exact, case-insensitive name lookup
 * ({@link MasterVenueMapper#findByNameIgnoreCase}) wins first. When that misses, the name is
 * matched heuristically: candidate masters are fetched through
 * {@link MasterVenueMapper#search(String, String, String, String, int, int)} — by the full name,
 * then by its longest token — scored by the Dice coefficient over normalized name tokens plus a
 * small bonus when the cities agree, and the best candidate is accepted only when it clears
 * {@link #MIN_SCORE} and beats the runner-up by {@link #AMBIGUITY_MARGIN}. A weak or ambiguous
 * match therefore leaves the venue unlinked rather than binding the wrong master. Only blank
 * fields are copied, so nothing the extraction produced is overwritten. The master catalog is
 * read-only here: this service never writes it, and a lookup failure is swallowed so it can never
 * fail venue creation.
 *
 * <p>shortcut: token-level matching only, no character-level fuzziness or geo distance — upgrade
 * to trigram or vector matching when that slice lands.
 */
@Service
class MasterVenueMatchService {

  private static final Logger log = LoggerFactory.getLogger(MasterVenueMatchService.class);

  /** Maximum master rows pulled in for heuristic scoring. */
  private static final int CANDIDATE_LIMIT = 20;
  /** Minimum Dice score for a heuristic match to be accepted. */
  private static final double MIN_SCORE = 0.6;
  /** How far the best candidate must beat the runner-up before the match is deemed certain. */
  private static final double AMBIGUITY_MARGIN = 0.1;
  /** Bonus applied when the venue and candidate cities agree. */
  private static final double CITY_BONUS = 0.1;
  /** Glue words dropped before comparing names so they do not dilute the overlap. */
  private static final Set<String> STOPWORDS = Set.of("the", "of", "and", "a", "an", "at", "in");

  private final MasterVenueMapper masterVenueMapper;

  MasterVenueMatchService(final MasterVenueMapper masterVenueMapper) {
    this.masterVenueMapper = masterVenueMapper;
  }

  /**
   * Matches {@code venue} against the master catalog and fills its blank fields in place.
   * No-op when the name is blank, no confident master venue matches, or the lookup fails.
   *
   * @param venue the freshly derived tenant venue; mutated in place
   */
  void match(final Venue venue) {
    if (isBlank(venue.getName())) {
      return;
    }
    try {
      MasterVenue master = findExact(venue.getName());
      if (master == null) {
        master = findBestCandidate(venue);
      }
      if (master != null) {
        link(venue, master);
      }
    } catch (final RuntimeException ex) {
      log.warn("master venue match skipped for '{}': {}", venue.getName(), ex.getMessage());
    }
  }

  private MasterVenue findExact(final String name) {
    final List<MasterVenue> matches = masterVenueMapper.findByNameIgnoreCase(name);
    return matches.isEmpty() ? null : matches.get(0);
  }

  /**
   * Heuristic fallback for the exact-name miss: fetch candidates by the full name, then by the
   * longest token, and return the best-scoring one when it is confident enough.
   */
  private MasterVenue findBestCandidate(final Venue venue) {
    final Set<String> venueTokens = tokens(venue.getName());
    if (venueTokens.isEmpty()) {
      return null;
    }
    List<MasterVenue> candidates = search(venue.getName());
    if (candidates.isEmpty()) {
      candidates = search(longestToken(venueTokens));
    }
    MasterVenue best = null;
    double bestScore = 0;
    double runnerUpScore = 0;
    for (final MasterVenue candidate : candidates) {
      final double score = score(venueTokens, venue.getCity(), candidate);
      if (score > bestScore) {
        runnerUpScore = bestScore;
        bestScore = score;
        best = candidate;
      } else if (score > runnerUpScore) {
        runnerUpScore = score;
      }
    }
    if (best == null || bestScore < MIN_SCORE || bestScore - runnerUpScore < AMBIGUITY_MARGIN) {
      log.info("no confident master venue match for '{}' (best={}, runnerUp={})",
          venue.getName(), bestScore, runnerUpScore);
      return null;
    }
    return best;
  }

  private List<MasterVenue> search(final String keyword) {
    return masterVenueMapper.search(keyword, null, null, null, CANDIDATE_LIMIT, 0);
  }

  private void link(final Venue venue, final MasterVenue master) {
    venue.setMasterVenueId(master.getId());
    if (isBlank(venue.getAddress())) {
      venue.setAddress(master.getAddress());
    }
    if (isBlank(venue.getCity())) {
      venue.setCity(master.getCity());
    }
    if (venue.getLocationLat() == null) {
      venue.setLocationLat(master.getLocationLat());
    }
    if (venue.getLocationLng() == null) {
      venue.setLocationLng(master.getLocationLng());
    }
    log.info("matched venue '{}' to master venue {} — back-filled blank fields",
        venue.getName(), master.getId());
  }

  private static double score(final Set<String> venueTokens, final String venueCity,
                              final MasterVenue candidate) {
    final double nameScore = dice(venueTokens, tokens(candidate.getName()));
    if (isBlank(venueCity) || isBlank(candidate.getCity())
        || !normalize(venueCity).equals(normalize(candidate.getCity()))) {
      return nameScore;
    }
    return Math.min(1.0, nameScore + CITY_BONUS);
  }

  /** Dice coefficient over the two token sets — symmetric, so extra qualifiers on either side cost. */
  private static double dice(final Set<String> left, final Set<String> right) {
    if (left.isEmpty() || right.isEmpty()) {
      return 0;
    }
    final Set<String> shared = new HashSet<>(left);
    shared.retainAll(right);
    return 2.0 * shared.size() / (left.size() + right.size());
  }

  private static Set<String> tokens(final String value) {
    final String normalized = normalize(value);
    if (normalized.isEmpty()) {
      return Set.of();
    }
    final Set<String> tokens = new HashSet<>();
    for (final String token : normalized.split(" ")) {
      if (!STOPWORDS.contains(token)) {
        tokens.add(token);
      }
    }
    return tokens;
  }

  private static String longestToken(final Set<String> tokens) {
    String longest = "";
    for (final String token : tokens) {
      if (token.length() > longest.length()) {
        longest = token;
      }
    }
    return longest;
  }

  /** Lowercases and reduces every run of non-alphanumeric characters to a single space. */
  private static String normalize(final String value) {
    return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
  }

  private static boolean isBlank(final String value) {
    return value == null || value.isBlank();
  }
}

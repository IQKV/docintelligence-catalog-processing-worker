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

import java.util.List;

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
 * <p>Matching is exact and case-insensitive on the venue name via
 * {@link MasterVenueMapper#findByNameIgnoreCase}, the deduplication primitive available before the
 * pg_trgm similarity slice lands. Only blank fields are copied, so nothing the extraction produced
 * is overwritten. The master catalog is read-only here: this service never writes it, and a lookup
 * failure is swallowed so it can never fail venue creation.
 *
 * <p>shortcut: exact-name match only, no fuzzy or geo matching — upgrade to trigram or vector
 * matching when that slice lands.
 */
@Service
class MasterVenueMatchService {

  private static final Logger log = LoggerFactory.getLogger(MasterVenueMatchService.class);

  private final MasterVenueMapper masterVenueMapper;

  MasterVenueMatchService(final MasterVenueMapper masterVenueMapper) {
    this.masterVenueMapper = masterVenueMapper;
  }

  /**
   * Matches {@code venue} against the master catalog and fills its blank fields in place.
   * No-op when the name is blank, no master venue matches, or the lookup fails.
   *
   * @param venue the freshly derived tenant venue; mutated in place
   */
  void match(final Venue venue) {
    if (venue.getName() == null || venue.getName().isBlank()) {
      return;
    }
    try {
      final List<MasterVenue> matches = masterVenueMapper.findByNameIgnoreCase(venue.getName());
      if (matches.isEmpty()) {
        return;
      }
      final MasterVenue master = matches.get(0);
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
    } catch (final RuntimeException ex) {
      log.warn("master venue match skipped for '{}': {}", venue.getName(), ex.getMessage());
    }
  }

  private static boolean isBlank(final String value) {
    return value == null || value.isBlank();
  }
}

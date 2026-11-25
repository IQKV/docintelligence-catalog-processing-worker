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

import com.iqkv.docintelligence.venue.Venue;
import com.iqkv.docintelligence.venue.VenueAssetMapper;
import com.iqkv.docintelligence.venue.VenueMapper;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Inserts the derived venue and links the asset to it (status → {@code EXTRACTED}).
 */
@Component
@Order(20)
class VenuePersistFilter implements VenueProcessingFilter {

  private final VenueMapper venueMapper;
  private final VenueAssetMapper assetMapper;

  VenuePersistFilter(final VenueMapper venueMapper, final VenueAssetMapper assetMapper) {
    this.venueMapper = venueMapper;
    this.assetMapper = assetMapper;
  }

  @Override
  public void apply(final VenueProcessingContext ctx) {
    // Set ownership fields before insert: the uploader owns the derived venue,
    // and FILE_IMPORT venues are public by default (org-tenant behaviour).
    final Venue venue = ctx.venue();
    venue.setCreatedBy(ctx.asset().getUploadedBy());
    venue.setVisibility(Venue.VISIBILITY_PUBLIC);
    venueMapper.insert(venue);
    assetMapper.linkVenue(ctx.asset().getId(), venue.getId());
  }
}

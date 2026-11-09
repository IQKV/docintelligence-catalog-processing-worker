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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.iqkv.docintelligence.catalogprocessingworker.infrastructure.config.AllTenantsKeyProvider;
import com.iqkv.docintelligence.venue.Venue;
import com.iqkv.docintelligence.venue.VenueMapper;
import com.iqkv.foundation.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("VenueEmbeddingBackfillService Unit Tests")
class VenueEmbeddingBackfillServiceTest {

  @Mock
  private VenueMapper venueMapper;

  @Mock
  private VenueEmbeddingService embeddingService;

  @Mock
  private AllTenantsKeyProvider tenantsKeyProvider;

  private VenueEmbeddingBackfillService service;

  @BeforeEach
  void setUp() {
    service = new VenueEmbeddingBackfillService(venueMapper, embeddingService, tenantsKeyProvider);
  }

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  @Test
  @DisplayName("backfillAll iterates tenants and embeds pending venues")
  void backfillAllIteratesTenantsAndEmbedsPending() {
    // Arrange
    final UUID id = UUID.randomUUID();
    final Venue venue = new Venue();
    venue.setId(id);
    venue.setName("Grand Hall");
    when(tenantsKeyProvider.findAllTenantKeys()).thenReturn(List.of("acme0001"));
    when(venueMapper.findUnembeddedIds(anyInt())).thenReturn(List.of(id), List.of());
    when(venueMapper.findById(id)).thenReturn(Optional.of(venue));
    when(embeddingService.embed(venue)).thenReturn(true);

    // Act
    service.backfillAll();

    // Assert
    verify(embeddingService).embed(venue);
  }

  @Test
  @DisplayName("backfillTenant stops when a batch makes no progress")
  void backfillTenantStopsOnNoProgress() {
    // Arrange — findUnembeddedIds keeps returning the same id but embed keeps failing
    final UUID id = UUID.randomUUID();
    final Venue venue = new Venue();
    venue.setId(id);
    when(venueMapper.findUnembeddedIds(anyInt())).thenReturn(List.of(id));
    when(venueMapper.findById(id)).thenReturn(Optional.of(venue));
    when(embeddingService.embed(venue)).thenReturn(false);

    // Act
    final int embedded = service.backfillTenant();

    // Assert
    assertThat(embedded).isZero();
    verify(embeddingService).embed(venue);
  }
}

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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.iqkv.docintelligence.venue.MasterVenue;
import com.iqkv.docintelligence.venue.MasterVenueMapper;
import com.iqkv.docintelligence.venue.Venue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("MasterVenueMatchService Unit Tests")
class MasterVenueMatchServiceTest {

  @Mock
  private MasterVenueMapper masterVenueMapper;

  private MasterVenueMatchService service;

  @BeforeEach
  void setUp() {
    service = new MasterVenueMatchService(masterVenueMapper);
  }

  private static Venue venue(final String name) {
    final Venue v = new Venue();
    v.setId(UUID.randomUUID());
    v.setName(name);
    return v;
  }

  private static MasterVenue master(final UUID id) {
    final MasterVenue m = new MasterVenue();
    m.setId(id);
    m.setName("Grand Hall");
    m.setAddress("Master Street 1");
    m.setCity("Frankfurt");
    m.setLocationLat(new BigDecimal("52.5200"));
    m.setLocationLng(new BigDecimal("13.4050"));
    return m;
  }

  @Test
  @DisplayName("match links the venue and back-fills only blank fields")
  void matchLinksAndFillsBlankFields() {
    // Arrange
    final Venue tenant = venue("Grand Hall");
    tenant.setCity("Berlin");
    final MasterVenue matched = master(UUID.randomUUID());
    when(masterVenueMapper.findByNameIgnoreCase("Grand Hall")).thenReturn(List.of(matched));

    // Act
    service.match(tenant);

    // Assert
    assertThat(tenant.getMasterVenueId()).isEqualTo(matched.getId());
    assertThat(tenant.getAddress()).isEqualTo("Master Street 1");
    assertThat(tenant.getLocationLat()).isEqualByComparingTo(new BigDecimal("52.5200"));
    assertThat(tenant.getLocationLng()).isEqualByComparingTo(new BigDecimal("13.4050"));
    assertThat(tenant.getCity()).isEqualTo("Berlin");
  }

  @Test
  @DisplayName("match leaves the venue untouched when no master venue matches")
  void matchLeavesVenueUntouchedWithoutCandidate() {
    final Venue tenant = venue("Unknown Hall");
    when(masterVenueMapper.findByNameIgnoreCase("Unknown Hall")).thenReturn(List.of());

    service.match(tenant);

    assertThat(tenant.getMasterVenueId()).isNull();
    assertThat(tenant.getAddress()).isNull();
    assertThat(tenant.getCity()).isNull();
  }

  @Test
  @DisplayName("match skips the lookup when the venue name is blank")
  void matchSkipsBlankName() {
    final Venue tenant = venue("   ");

    service.match(tenant);

    verify(masterVenueMapper, never()).findByNameIgnoreCase(anyString());
    assertThat(tenant.getMasterVenueId()).isNull();
  }

  @Test
  @DisplayName("match swallows a lookup failure so venue creation is never aborted")
  void matchSwallowsLookupFailure() {
    final Venue tenant = venue("Grand Hall");
    when(masterVenueMapper.findByNameIgnoreCase("Grand Hall"))
        .thenThrow(new RuntimeException("db down"));

    service.match(tenant);

    assertThat(tenant.getMasterVenueId()).isNull();
  }
}

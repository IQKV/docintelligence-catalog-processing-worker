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

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("VenueAssetUploadedListener Unit Tests")
class VenueAssetUploadedListenerTest {

  private static final String TENANT_KEY = "xk7f2b9a";

  @Mock
  private VenueAssetProcessingService processingService;

  private VenueAssetUploadedListener listener;

  @BeforeEach
  void setUp() {
    listener = new VenueAssetUploadedListener(processingService);
  }

  @Test
  @DisplayName("onVenueAssetUploaded delegates the tenant key and asset id to the processing service")
  void delegatesToProcessingService() {
    // Arrange
    final UUID assetId = UUID.randomUUID();

    // Act
    listener.onVenueAssetUploaded(Map.of("tenantKey", TENANT_KEY, "assetId", assetId.toString()));

    // Assert
    verify(processingService).process(TENANT_KEY, assetId);
  }

  @Test
  @DisplayName("onVenueAssetUploaded ignores an event without a tenant key")
  void ignoresEventWithoutTenantKey() {
    // Act
    listener.onVenueAssetUploaded(Map.of("assetId", UUID.randomUUID().toString()));

    // Assert
    verifyNoInteractions(processingService);
  }

  @Test
  @DisplayName("onVenueAssetUploaded ignores an event without an asset id")
  void ignoresEventWithoutAssetId() {
    // Act
    listener.onVenueAssetUploaded(Map.of("tenantKey", TENANT_KEY));

    // Assert
    verifyNoInteractions(processingService);
  }
}

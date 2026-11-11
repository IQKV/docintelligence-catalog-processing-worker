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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.iqkv.docintelligence.catalogprocessingworker.infrastructure.config.ObjectStorageConfigurationProperties;
import com.iqkv.docintelligence.venue.Venue;
import com.iqkv.docintelligence.venue.VenueAsset;
import com.iqkv.docintelligence.venue.VenueAssetMapper;
import com.iqkv.docintelligence.venue.VenueMapper;
import com.iqkv.foundation.tenancy.TenantContext;
import io.minio.MinioClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("VenueAssetProcessingService Unit Tests")
class VenueAssetProcessingServiceTest {

  private static final String TENANT_KEY = "xk7f2b9a";
  private static final String S3_KEY = "docintelligence/tenant/" + TENANT_KEY + "/assets/a/asset.pdf";

  @Mock
  private VenueMapper venueMapper;
  @Mock
  private VenueAssetMapper assetMapper;
  @Mock
  private MinioClient minioClient;
  @Mock
  private VenueEmbeddingService embeddingService;

  private VenueAssetProcessingService service;

  @BeforeEach
  void setUp() {
    final ObjectStorageConfigurationProperties props = new ObjectStorageConfigurationProperties(
        "http://localhost:9000", "", "iqkv", "pw", "catalog-assets", "us-east-1", false,
        new ObjectStorageConfigurationProperties.Upload(
            5_242_880L, 60, List.of("application/pdf")));
    service = spy(new VenueAssetProcessingService(
        venueMapper, assetMapper, minioClient, props, embeddingService));
  }

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  private static VenueAsset asset(final UUID id, final UUID venueId) {
    final VenueAsset asset = new VenueAsset();
    asset.setId(id);
    asset.setVenueId(venueId);
    asset.setFileName("asset.pdf");
    asset.setContentType("application/pdf");
    asset.setS3Key(S3_KEY);
    asset.setStatus(VenueAsset.STATUS_UPLOADED);
    return asset;
  }

  @Test
  @DisplayName("process skips an unknown asset and clears the tenant context")
  void processSkipsUnknownAsset() {
    // Arrange
    final UUID assetId = UUID.randomUUID();
    when(assetMapper.findById(assetId)).thenReturn(Optional.empty());

    // Act
    service.process(TENANT_KEY, assetId);

    // Assert
    verify(assetMapper, never()).updateStatus(any(), any(), any());
    verify(venueMapper, never()).insert(any(Venue.class));
    assertThatThrownBy(TenantContext::getCurrentTenant).isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("process skips an asset that already belongs to a venue")
  void processSkipsAlreadyLinkedAsset() {
    // Arrange
    final UUID assetId = UUID.randomUUID();
    when(assetMapper.findById(assetId)).thenReturn(Optional.of(asset(assetId, UUID.randomUUID())));

    // Act
    service.process(TENANT_KEY, assetId);

    // Assert
    verify(assetMapper, never()).updateStatus(any(), any(), any());
    verify(venueMapper, never()).insert(any(Venue.class));
  }

  @Test
  @DisplayName("process derives the venue from the file, links the asset and embeds")
  void processDerivesVenueFromFile() throws Exception {
    // Arrange
    final UUID assetId = UUID.randomUUID();
    when(assetMapper.findById(assetId)).thenReturn(Optional.of(asset(assetId, null)));
    doReturn("Grand Hall".getBytes(StandardCharsets.UTF_8)).when(service).download(S3_KEY);

    // Act
    service.process(TENANT_KEY, assetId);

    // Assert
    final ArgumentCaptor<Venue> captor = ArgumentCaptor.forClass(Venue.class);
    verify(venueMapper).insert(captor.capture());
    final Venue venue = captor.getValue();
    assertThat(venue.getId()).isNotNull();
    assertThat(venue.getName()).isEqualTo("Grand Hall");
    assertThat(venue.getSource()).isEqualTo("FILE_IMPORT");
    assertThat(venue.getMetadata()).contains("_schema_version");

    verify(assetMapper).updateStatus(assetId, VenueAsset.STATUS_PARSING, null);
    verify(assetMapper).linkVenue(assetId, venue.getId());
    verify(embeddingService).embed(venue);
    assertThatThrownBy(TenantContext::getCurrentTenant).isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("process marks the asset FAILED and rethrows when the file cannot be read")
  void processMarksFailedAndRethrows() throws Exception {
    // Arrange
    final UUID assetId = UUID.randomUUID();
    when(assetMapper.findById(assetId)).thenReturn(Optional.of(asset(assetId, null)));
    doThrow(new RuntimeException("minio down")).when(service).download(S3_KEY);

    // Act & Assert
    assertThatThrownBy(() -> service.process(TENANT_KEY, assetId))
        .isInstanceOf(IllegalStateException.class);
    verify(assetMapper).updateStatus(assetId, VenueAsset.STATUS_FAILED, "minio down");
    verify(venueMapper, never()).insert(any(Venue.class));
    assertThatThrownBy(TenantContext::getCurrentTenant).isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("deriveName uses the first non-blank line of the extracted text")
  void deriveNameUsesFirstNonBlankLine() {
    assertThat(VenueAssetProcessingService.deriveName("scan.pdf", "\n\n  Grand Hall \n1 Rue"))
        .isEqualTo("Grand Hall");
  }

  @Test
  @DisplayName("deriveName falls back to the file name without its extension")
  void deriveNameFallsBackToFileName() {
    assertThat(VenueAssetProcessingService.deriveName("menu.pdf", "   \n  ")).isEqualTo("menu");
  }

  @Test
  @DisplayName("deriveName truncates to the name column width")
  void deriveNameTruncates() {
    final String longLine = "x".repeat(300);
    assertThat(VenueAssetProcessingService.deriveName("a.pdf", longLine)).hasSize(255);
  }
}

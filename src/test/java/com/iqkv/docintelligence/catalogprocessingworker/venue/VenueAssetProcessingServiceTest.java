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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iqkv.docintelligence.catalogprocessingworker.infrastructure.config.ObjectStorageConfigurationProperties;
import com.iqkv.docintelligence.catalogprocessingworker.infrastructure.config.VenueAssetMessagingConfig;
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
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

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
  @Mock
  private ChatClient.Builder chatClientBuilder;
  @Mock
  private ChatClient chatClient;
  @Mock
  private RabbitTemplate rabbitTemplate;

  private VenueAssetProcessingService service;
  private final ObjectMapper objectMapper = new ObjectMapper();

  @BeforeEach
  void setUp() {
    when(chatClientBuilder.build()).thenReturn(chatClient);
    final ObjectStorageConfigurationProperties props = new ObjectStorageConfigurationProperties(
        "http://localhost:9000", "", "iqkv", "pw", "catalog-assets", "us-east-1", false,
        new ObjectStorageConfigurationProperties.Upload(
            5_242_880L, 60, List.of("application/pdf")));
    service = spy(new VenueAssetProcessingService(
        venueMapper, assetMapper, minioClient, props, embeddingService, chatClientBuilder, objectMapper,
        rabbitTemplate));
  }

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  private static final UUID UPLOADED_BY = UUID.fromString("00000000-0000-0000-0000-000000000099");

  private static VenueAsset asset(final UUID id, final UUID venueId) {
    final VenueAsset asset = new VenueAsset();
    asset.setId(id);
    asset.setVenueId(venueId);
    asset.setUploadedBy(UPLOADED_BY);
    asset.setFileName("asset.pdf");
    asset.setContentType("application/pdf");
    asset.setS3Key(S3_KEY);
    asset.setStatus(VenueAsset.STATUS_UPLOADED);
    return asset;
  }

  // ── process guard-rails ───────────────────────────────────────────────────

  @Test
  @DisplayName("process skips an unknown asset and clears the tenant context")
  void processSkipsUnknownAsset() {
    final UUID assetId = UUID.randomUUID();
    when(assetMapper.findById(assetId)).thenReturn(Optional.empty());

    service.process(TENANT_KEY, assetId);

    verify(assetMapper, never()).updateStatus(any(), any(), any());
    verify(venueMapper, never()).insert(any(Venue.class));
    assertThatThrownBy(TenantContext::getCurrentTenant).isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("process skips an asset that already belongs to a venue")
  void processSkipsAlreadyLinkedAsset() {
    final UUID assetId = UUID.randomUUID();
    when(assetMapper.findById(assetId)).thenReturn(Optional.of(asset(assetId, UUID.randomUUID())));

    service.process(TENANT_KEY, assetId);

    verify(assetMapper, never()).updateStatus(any(), any(), any());
    verify(venueMapper, never()).insert(any(Venue.class));
  }

  @Test
  @DisplayName("process marks the asset FAILED and rethrows when the file cannot be read")
  void processMarksFailedAndRethrows() throws Exception {
    final UUID assetId = UUID.randomUUID();
    when(assetMapper.findById(assetId)).thenReturn(Optional.of(asset(assetId, null)));
    doThrow(new RuntimeException("minio down")).when(service).download(S3_KEY);

    assertThatThrownBy(() -> service.process(TENANT_KEY, assetId))
        .isInstanceOf(IllegalStateException.class);
    verify(assetMapper).updateStatus(assetId, VenueAsset.STATUS_FAILED, "minio down");
    verify(venueMapper, never()).insert(any(Venue.class));
    verify(rabbitTemplate).convertAndSend(
        eq(VenueAssetMessagingConfig.EVENTS_EXCHANGE),
        eq(VenueAssetMessagingConfig.ROUTING_NOTIFICATION_IAM_PUSH),
        any(VenueImportNotificationEvent.class));
    assertThatThrownBy(TenantContext::getCurrentTenant).isInstanceOf(IllegalStateException.class);
  }

  // ── LLM-extracted venue ───────────────────────────────────────────────────

  @Test
  @DisplayName("process uses LLM-extracted fields when available")
  void processUsesLlmExtraction() throws Exception {
    // Arrange
    final UUID assetId = UUID.randomUUID();
    when(assetMapper.findById(assetId)).thenReturn(Optional.of(asset(assetId, null)));
    doReturn("Grand Hall\n171 Bermondsey Street, London".getBytes(StandardCharsets.UTF_8))
        .when(service).download(S3_KEY);
    final VenueExtraction extraction = new VenueExtraction(
        "Grand Hall", "171 Bermondsey Street", "London",
        51.499, -0.080, 0.85, "A great venue", 90, 90, 60,
        "Restaurant", "GBP", "from £10pp", "Per person",
        true, true, true, true, true, true, true,
        true, true, true, true,
        "23:00", "01:30",
        "In-house catering only", true, true, true);
    doReturn(extraction).when(service).extractWithLlm(anyString(), anyString());

    // Act
    service.process(TENANT_KEY, assetId);

    // Assert
    final ArgumentCaptor<Venue> captor = ArgumentCaptor.forClass(Venue.class);
    verify(venueMapper).insert(captor.capture());
    final Venue venue = captor.getValue();
    assertThat(venue.getName()).isEqualTo("Grand Hall");
    assertThat(venue.getAddress()).isEqualTo("171 Bermondsey Street");
    assertThat(venue.getCity()).isEqualTo("London");
    assertThat(venue.getLocationLat()).isEqualByComparingTo(BigDecimal.valueOf(51.499));
    assertThat(venue.getLocationLng()).isEqualByComparingTo(BigDecimal.valueOf(-0.080));
    assertThat(venue.getConfidence()).isEqualByComparingTo(new BigDecimal("0.8500"));
    assertThat(venue.getMetadata()).contains("max_total").contains("wifi").contains("capacity");
    assertThat(venue.getSource()).isEqualTo("FILE_IMPORT");

    verify(assetMapper).updateStatus(assetId, VenueAsset.STATUS_PARSING, null);
    verify(assetMapper).linkVenue(assetId, venue.getId());
    verify(embeddingService).embed(venue);
    verify(rabbitTemplate).convertAndSend(
        eq(VenueAssetMessagingConfig.EVENTS_EXCHANGE),
        eq(VenueAssetMessagingConfig.ROUTING_NOTIFICATION_IAM_PUSH),
        any(VenueImportNotificationEvent.class));
  }

  @Test
  @DisplayName("process falls back to Tika first-line name when LLM returns null extraction")
  void processFallsBackToTikaNameOnNullExtraction() throws Exception {
    final UUID assetId = UUID.randomUUID();
    when(assetMapper.findById(assetId)).thenReturn(Optional.of(asset(assetId, null)));
    doReturn("Grand Hall\nSome more text".getBytes(StandardCharsets.UTF_8))
        .when(service).download(S3_KEY);
    doReturn(null).when(service).extractWithLlm(anyString(), anyString());

    service.process(TENANT_KEY, assetId);

    final ArgumentCaptor<Venue> captor = ArgumentCaptor.forClass(Venue.class);
    verify(venueMapper).insert(captor.capture());
    assertThat(captor.getValue().getName()).isEqualTo("Grand Hall");
    assertThat(captor.getValue().getConfidence()).isEqualByComparingTo(new BigDecimal("0.3"));
    assertThat(captor.getValue().getAddress()).isNull();
    assertThat(captor.getValue().getMetadata()).isEqualTo("{\"_schema_version\":1}");
  }

  @Test
  @DisplayName("process falls back to filename when LLM and Tika text are both blank")
  void processFallsBackToFilename() throws Exception {
    final UUID assetId = UUID.randomUUID();
    when(assetMapper.findById(assetId)).thenReturn(Optional.of(asset(assetId, null)));
    doReturn(new byte[0]).when(service).download(S3_KEY);
    doReturn(null).when(service).extractWithLlm(anyString(), anyString());

    service.process(TENANT_KEY, assetId);

    final ArgumentCaptor<Venue> captor = ArgumentCaptor.forClass(Venue.class);
    verify(venueMapper).insert(captor.capture());
    assertThat(captor.getValue().getName()).isEqualTo("asset");
  }

  // ── resolveName ───────────────────────────────────────────────────────────

  @Test
  @DisplayName("resolveName prefers the LLM name when present")
  void resolveNamePrefersLlm() {
    final VenueExtraction ex = extraction("The Last Talisman");
    assertThat(VenueAssetProcessingService.resolveName(ex, "\nSomething else", "scan.pdf"))
        .isEqualTo("The Last Talisman");
  }

  @Test
  @DisplayName("resolveName falls back to first non-blank Tika line when LLM name is null")
  void resolveNameFallsBackToTikaLine() {
    assertThat(VenueAssetProcessingService.resolveName(null, "\n\n  Grand Hall \n1 Rue", "scan.pdf"))
        .isEqualTo("Grand Hall");
  }

  @Test
  @DisplayName("resolveName falls back to filename without extension")
  void resolveNameFallsBackToFileName() {
    assertThat(VenueAssetProcessingService.resolveName(null, "   \n  ", "menu.pdf"))
        .isEqualTo("menu");
  }

  @Test
  @DisplayName("resolveName truncates to the name column width")
  void resolveNameTruncates() {
    final String longLine = "x".repeat(300);
    assertThat(VenueAssetProcessingService.resolveName(null, longLine, "a.pdf")).hasSize(255);
  }

  // ── buildMetadata ─────────────────────────────────────────────────────────

  @Test
  @DisplayName("buildMetadata returns skeleton JSON when extraction is null")
  void buildMetadataSkeletonOnNull() {
    assertThat(service.buildMetadata(null)).isEqualTo("{\"_schema_version\":1}");
  }

  @Test
  @DisplayName("buildMetadata populates items from extraction fields")
  void buildMetadataPopulatesItems() {
    final VenueExtraction ex = new VenueExtraction(
        "X", null, null, null, null, null, null,
        90, 90, 60, "Restaurant", "GBP", null, null,
        true, null, null, null, null, null, null,
        null, null, null, null,
        "23:00", null, null, null, null, null);

    final String metadata = service.buildMetadata(ex);
    assertThat(metadata)
        .contains("\"_schema_version\":1")
        .contains("\"group\":\"capacity\"")
        .contains("\"field\":\"max_total\"")
        .contains("\"value\":90")
        .contains("\"group\":\"venue_type\"")
        .contains("\"group\":\"av_tech\"")
        .contains("\"field\":\"wifi\"")
        .contains("\"group\":\"logistics\"")
        .contains("\"field\":\"noise_restriction_after\"");
  }

  @Test
  @DisplayName("buildMetadata omits items with null values")
  void buildMetadataOmitsNullValues() {
    final VenueExtraction ex = new VenueExtraction(
        "X", null, null, null, null, null, null,
        null, null, null, null, null, null, null,
        null, null, null, null, null, null, null,
        null, null, null, null,
        null, null, null, null, null, null);

    assertThat(service.buildMetadata(ex)).doesNotContain("items");
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private static VenueExtraction extraction(final String name) {
    return new VenueExtraction(name, null, null, null, null, null, null,
        null, null, null, null, null, null, null,
        null, null, null, null, null, null, null,
        null, null, null, null,
        null, null, null, null, null, null);
  }
}

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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import com.iqkv.docintelligence.catalogprocessingworker.infrastructure.config.VenueAssetMessagingConfig;
import com.iqkv.docintelligence.venue.Venue;
import com.iqkv.docintelligence.venue.VenueAsset;
import com.iqkv.docintelligence.venue.VenueAssetMapper;
import com.iqkv.docintelligence.venue.VenueMapper;
import com.iqkv.docintelligence.venue.VenueTextContentMapper;
import com.iqkv.foundation.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

@ExtendWith(MockitoExtension.class)
@DisplayName("VenueAssetProcessingService Unit Tests")
class VenueAssetProcessingServiceTest {

  private static final String TENANT_KEY = "xk7f2b9a";
  private static final UUID UPLOADED_BY = UUID.fromString("00000000-0000-0000-0000-000000000099");

  @Mock
  private VenueMapper venueMapper;
  @Mock
  private VenueAssetMapper assetMapper;
  @Mock
  private VenueTextContentMapper textContentMapper;
  @Mock
  private VenueEmbeddingService embeddingService;
  @Mock
  private VenueExtractionStrategyRouter strategyRouter;
  @Mock
  private MasterVenueMatchService masterVenueMatchService;
  @Mock
  private VenueExtractionStrategy strategy;
  @Mock
  private RabbitTemplate rabbitTemplate;

  private VenueAssetProcessingService service;

  @BeforeEach
  void setUp() {
    service = new VenueAssetProcessingService(
        venueMapper, assetMapper, textContentMapper, embeddingService, strategyRouter,
        masterVenueMatchService, rabbitTemplate);
  }

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  private static VenueAsset asset(final UUID id, final UUID venueId) {
    final VenueAsset a = new VenueAsset();
    a.setId(id);
    a.setVenueId(venueId);
    a.setUploadedBy(UPLOADED_BY);
    a.setFileName("asset.pdf");
    a.setContentType("application/pdf");
    a.setS3Key("docintelligence/tenant/" + TENANT_KEY + "/assets/a/asset.pdf");
    a.setStatus(VenueAsset.STATUS_UPLOADED);
    return a;
  }

  private static Venue venueFor(final String name) {
    final Venue v = new Venue();
    v.setId(UUID.randomUUID());
    v.setName(name);
    v.setSource("FILE_IMPORT");
    v.setConfidence(new BigDecimal("0.85"));
    v.setMetadata("{\"_schema_version\":1}");
    return v;
  }

  // ── guard-rails ───────────────────────────────────────────────────────────

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
  @DisplayName("process skips an asset that is already linked to a venue")
  void processSkipsAlreadyLinkedAsset() {
    final UUID assetId = UUID.randomUUID();
    when(assetMapper.findById(assetId)).thenReturn(Optional.of(asset(assetId, UUID.randomUUID())));

    service.process(TENANT_KEY, assetId);

    verify(assetMapper, never()).updateStatus(any(), any(), any());
    verify(venueMapper, never()).insert(any(Venue.class));
  }

  // ── happy path ────────────────────────────────────────────────────────────

  @Test
  @DisplayName("process routes to the strategy, inserts venue, links asset, embeds, notifies")
  void processFullHappyPath() throws Exception {
    // Arrange
    final UUID assetId = UUID.randomUUID();
    final VenueAsset asset = asset(assetId, null);
    final Venue derived = venueFor("Grand Hall");

    when(assetMapper.findById(assetId)).thenReturn(Optional.of(asset));
    when(strategyRouter.select("application/pdf")).thenReturn(strategy);
    when(strategy.derive(asset)).thenReturn(new VenueExtractionStrategy.VenueExtractionResult(derived, "raw text from pdf"));

    // Act
    service.process(TENANT_KEY, assetId);

    // Assert
    verify(assetMapper).updateStatus(assetId, VenueAsset.STATUS_PARSING, null);
    final ArgumentCaptor<Venue> captor = ArgumentCaptor.forClass(Venue.class);
    verify(venueMapper).insert(captor.capture());
    assertThat(captor.getValue().getName()).isEqualTo("Grand Hall");
    assertThat(captor.getValue().getSource()).isEqualTo("FILE_IMPORT");

    verify(assetMapper).linkVenue(assetId, derived.getId());
    verify(embeddingService).embed(derived);
    verify(rabbitTemplate).convertAndSend(
        eq(VenueAssetMessagingConfig.EVENTS_EXCHANGE),
        eq(VenueAssetMessagingConfig.ROUTING_NOTIFICATION_IAM_PUSH),
        any(VenueImportNotificationEvent.class));
    assertThatThrownBy(TenantContext::getCurrentTenant).isInstanceOf(IllegalStateException.class);
  }

  // ── failure path ──────────────────────────────────────────────────────────

  @Test
  @DisplayName("process marks FAILED and rethrows when the strategy throws")
  void processMarksFailedOnStrategyException() throws Exception {
    final UUID assetId = UUID.randomUUID();
    final VenueAsset asset = asset(assetId, null);

    when(assetMapper.findById(assetId)).thenReturn(Optional.of(asset));
    when(strategyRouter.select("application/pdf")).thenReturn(strategy);
    when(strategy.derive(asset)).thenThrow(new RuntimeException("minio down"));

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
}

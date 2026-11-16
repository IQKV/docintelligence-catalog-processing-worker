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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.iqkv.docintelligence.catalogprocessingworker.infrastructure.config.VenueAssetMessagingConfig;
import com.iqkv.docintelligence.venue.Venue;
import com.iqkv.docintelligence.venue.VenueAsset;
import com.iqkv.docintelligence.venue.VenueAssetMapper;
import com.iqkv.foundation.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

@ExtendWith(MockitoExtension.class)
@DisplayName("VenueAssetProcessingService Unit Tests")
class VenueAssetProcessingServiceTest {

  private static final String TENANT_KEY = "xk7f2b9a";
  private static final UUID UPLOADED_BY = UUID.fromString("00000000-0000-0000-0000-000000000099");

  @Mock
  private VenueAssetMapper assetMapper;
  @Mock
  private VenueExtractionStrategyRouter strategyRouter;
  @Mock
  private VenueProcessingFilter filterA;
  @Mock
  private VenueProcessingFilter filterB;
  @Mock
  private VenueExtractionStrategy strategy;
  @Mock
  private VenueTextContentPersister textContentPersister;
  @Mock
  private RabbitTemplate rabbitTemplate;

  private VenueAssetProcessingService service;

  @BeforeEach
  void setUp() {
    service = new VenueAssetProcessingService(
        assetMapper, strategyRouter, List.of(filterA, filterB), textContentPersister, rabbitTemplate);
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
    assertThatThrownBy(TenantContext::getCurrentTenant).isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("process mines text from an already-linked text asset and leaves its status untouched")
  void processExtractsTextForLinkedAsset() throws Exception {
    // Arrange
    final UUID assetId = UUID.randomUUID();
    final UUID venueId = UUID.randomUUID();
    final VenueAsset asset = asset(assetId, venueId);

    when(assetMapper.findById(assetId)).thenReturn(Optional.of(asset));
    when(strategyRouter.select("application/pdf")).thenReturn(strategy);
    when(strategy.extractText(asset)).thenReturn(Optional.of("raw text from pdf"));

    // Act
    service.process(TENANT_KEY, assetId);

    // Assert — text saved against the existing venue, no venue derivation, no status change
    verify(textContentPersister).persistIfAbsent(venueId, assetId, "asset.pdf", "raw text from pdf");
    verify(strategy, never()).derive(any());
    verify(assetMapper, never()).updateStatus(any(), any(), any());
    verify(rabbitTemplate, never()).convertAndSend(any(), any(), any(Object.class));
    assertThatThrownBy(TenantContext::getCurrentTenant).isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("process does not persist text for a linked asset that yields no text")
  void processLinkedAssetWithoutTextSkipsPersist() throws Exception {
    // Arrange
    final UUID assetId = UUID.randomUUID();
    final VenueAsset asset = asset(assetId, UUID.randomUUID());

    when(assetMapper.findById(assetId)).thenReturn(Optional.of(asset));
    when(strategyRouter.select("application/pdf")).thenReturn(strategy);
    when(strategy.extractText(asset)).thenReturn(Optional.empty());

    // Act
    service.process(TENANT_KEY, assetId);

    // Assert
    verify(textContentPersister, never()).persistIfAbsent(any(), any(), any(), any());
    verify(assetMapper, never()).updateStatus(any(), any(), any());
  }

  @Test
  @DisplayName("process swallows a text extraction failure for a linked asset")
  void processLinkedAssetSwallowsExtractionFailure() throws Exception {
    // Arrange
    final UUID assetId = UUID.randomUUID();
    final VenueAsset asset = asset(assetId, UUID.randomUUID());

    when(assetMapper.findById(assetId)).thenReturn(Optional.of(asset));
    when(strategyRouter.select("application/pdf")).thenReturn(strategy);
    when(strategy.extractText(asset)).thenThrow(new RuntimeException("minio down"));

    // Act
    service.process(TENANT_KEY, assetId);

    // Assert — best-effort: no status change, no rethrow
    verify(textContentPersister, never()).persistIfAbsent(any(), any(), any(), any());
    verify(assetMapper, never()).updateStatus(any(), any(), any());
  }

  // ── happy path ────────────────────────────────────────────────────────────

  @Test
  @DisplayName("process runs all filters in order then notifies on success")
  void processRunsFiltersInOrderAndNotifies() throws Exception {
    // Arrange
    final UUID assetId = UUID.randomUUID();
    final VenueAsset asset = asset(assetId, null);
    final Venue derived = venueFor("Grand Hall");

    when(assetMapper.findById(assetId)).thenReturn(Optional.of(asset));
    when(strategyRouter.select("application/pdf")).thenReturn(strategy);
    when(strategy.derive(asset)).thenReturn(
        new VenueExtractionStrategy.VenueExtractionResult(derived, "raw text from pdf"));

    // Act
    service.process(TENANT_KEY, assetId);

    // Assert — filters run in declaration order
    final var order = inOrder(filterA, filterB);
    order.verify(filterA).apply(any(VenueProcessingContext.class));
    order.verify(filterB).apply(any(VenueProcessingContext.class));

    verify(assetMapper).updateStatus(assetId, VenueAsset.STATUS_PARSING, null);
    verify(rabbitTemplate).convertAndSend(
        eq(VenueAssetMessagingConfig.EVENTS_EXCHANGE),
        eq(VenueAssetMessagingConfig.ROUTING_NOTIFICATION_IAM_PUSH),
        any(VenueImportNotificationEvent.class));
    assertThatThrownBy(TenantContext::getCurrentTenant).isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("process passes the correct context to each filter")
  void processPassesCorrectContext() throws Exception {
    // Arrange
    final UUID assetId = UUID.randomUUID();
    final VenueAsset asset = asset(assetId, null);
    final Venue derived = venueFor("Grand Hall");
    final String rawText = "raw text from pdf";

    when(assetMapper.findById(assetId)).thenReturn(Optional.of(asset));
    when(strategyRouter.select("application/pdf")).thenReturn(strategy);
    when(strategy.derive(asset)).thenReturn(
        new VenueExtractionStrategy.VenueExtractionResult(derived, rawText));

    // Act
    service.process(TENANT_KEY, assetId);

    // Assert — capture what filterA received
    final var captor = org.mockito.ArgumentCaptor.forClass(VenueProcessingContext.class);
    verify(filterA).apply(captor.capture());
    final VenueProcessingContext ctx = captor.getValue();
    assertThat(ctx.asset()).isSameAs(asset);
    assertThat(ctx.venue()).isSameAs(derived);
    assertThat(ctx.rawText()).isEqualTo(rawText);
  }

  // ── failure path ──────────────────────────────────────────────────────────

  @Test
  @DisplayName("process marks FAILED and rethrows when a filter throws")
  void processMarksFailedWhenFilterThrows() throws Exception {
    final UUID assetId = UUID.randomUUID();
    final VenueAsset asset = asset(assetId, null);
    final Venue derived = venueFor("Grand Hall");

    when(assetMapper.findById(assetId)).thenReturn(Optional.of(asset));
    when(strategyRouter.select("application/pdf")).thenReturn(strategy);
    when(strategy.derive(asset)).thenReturn(
        new VenueExtractionStrategy.VenueExtractionResult(derived, null));
    doThrow(new RuntimeException("minio down")).when(filterA).apply(any());

    assertThatThrownBy(() -> service.process(TENANT_KEY, assetId))
        .isInstanceOf(IllegalStateException.class);

    verify(assetMapper).updateStatus(assetId, VenueAsset.STATUS_FAILED, "minio down");
    verify(rabbitTemplate).convertAndSend(
        eq(VenueAssetMessagingConfig.EVENTS_EXCHANGE),
        eq(VenueAssetMessagingConfig.ROUTING_NOTIFICATION_IAM_PUSH),
        any(VenueImportNotificationEvent.class));
    assertThatThrownBy(TenantContext::getCurrentTenant).isInstanceOf(IllegalStateException.class);
  }

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
    verify(rabbitTemplate).convertAndSend(
        eq(VenueAssetMessagingConfig.EVENTS_EXCHANGE),
        eq(VenueAssetMessagingConfig.ROUTING_NOTIFICATION_IAM_PUSH),
        any(VenueImportNotificationEvent.class));
    assertThatThrownBy(TenantContext::getCurrentTenant).isInstanceOf(IllegalStateException.class);
  }
}

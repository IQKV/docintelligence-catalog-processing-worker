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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import com.iqkv.docintelligence.venue.VenueTextContent;
import com.iqkv.docintelligence.venue.VenueTextContentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("VenueTextContentPersister Unit Tests")
class VenueTextContentPersisterTest {

  private static final UUID VENUE_ID = UUID.randomUUID();
  private static final UUID ASSET_ID = UUID.randomUUID();

  @Mock
  private VenueTextContentMapper textContentMapper;
  @Mock
  private VenueEmbeddingService embeddingService;

  private VenueTextContentPersister persister;

  @BeforeEach
  void setUp() {
    persister = new VenueTextContentPersister(textContentMapper, embeddingService);
  }

  // ── blank content guards ──────────────────────────────────────────────────

  @Test
  @DisplayName("persistIfAbsent does nothing for null content")
  void persistIfAbsentSkipsNullContent() {
    final boolean created = persister.persistIfAbsent(VENUE_ID, ASSET_ID, "asset.pdf", null);

    assertThat(created).isFalse();
    verify(textContentMapper, never()).insert(any());
  }

  @Test
  @DisplayName("persistIfAbsent does nothing for blank content")
  void persistIfAbsentSkipsBlankContent() {
    final boolean created = persister.persistIfAbsent(VENUE_ID, ASSET_ID, "asset.pdf", "   \n");

    assertThat(created).isFalse();
    verify(textContentMapper, never()).insert(any());
  }

  // ── happy path ────────────────────────────────────────────────────────────

  @Test
  @DisplayName("persistIfAbsent inserts the row and embeds the content")
  void persistIfAbsentInsertsAndEmbeds() {
    // Arrange
    final float[] vector = new float[] {0.1f, 0.2f, 0.3f};
    when(textContentMapper.findBySourceAssetId(ASSET_ID)).thenReturn(List.of());
    when(embeddingService.embedText("Grand Hall prose")).thenReturn(vector);

    // Act
    final boolean created = persister.persistIfAbsent(VENUE_ID, ASSET_ID, "asset.pdf", "Grand Hall prose");

    // Assert
    assertThat(created).isTrue();
    final ArgumentCaptor<VenueTextContent> captor = ArgumentCaptor.forClass(VenueTextContent.class);
    verify(textContentMapper).insert(captor.capture());
    final VenueTextContent row = captor.getValue();
    assertThat(row.getId()).isNotNull();
    assertThat(row.getVenueId()).isEqualTo(VENUE_ID);
    assertThat(row.getSourceAssetId()).isEqualTo(ASSET_ID);
    assertThat(row.getLabel()).isEqualTo("asset.pdf");
    assertThat(row.getContent()).isEqualTo("Grand Hall prose");
    verify(textContentMapper).updateEmbedding(eq(row.getId()), eq(vector));
  }

  @Test
  @DisplayName("persistIfAbsent is a no-op when a row already exists for the asset")
  void persistIfAbsentSkipsWhenRowExists() {
    // Arrange
    when(textContentMapper.findBySourceAssetId(ASSET_ID))
        .thenReturn(List.of(new VenueTextContent()));

    // Act
    final boolean created = persister.persistIfAbsent(VENUE_ID, ASSET_ID, "asset.pdf", "Grand Hall prose");

    // Assert
    assertThat(created).isFalse();
    verify(textContentMapper, never()).insert(any());
    verify(embeddingService, never()).embedText(any());
  }

  // ── failure tolerance ─────────────────────────────────────────────────────

  @Test
  @DisplayName("persistIfAbsent keeps the row when embedding fails")
  void persistIfAbsentSwallowsEmbeddingFailure() {
    // Arrange
    when(textContentMapper.findBySourceAssetId(ASSET_ID)).thenReturn(List.of());
    when(embeddingService.embedText(any())).thenThrow(new RuntimeException("Ollama down"));

    // Act
    final boolean created = persister.persistIfAbsent(VENUE_ID, ASSET_ID, "asset.pdf", "Grand Hall prose");

    // Assert — row created, embedding left null for the backfill to retry
    assertThat(created).isTrue();
    verify(textContentMapper).insert(any());
    verify(textContentMapper, never()).updateEmbedding(any(), any());
  }

  @Test
  @DisplayName("persistIfAbsent swallows a mapper failure and returns false")
  void persistIfAbsentSwallowsMapperFailure() {
    // Arrange
    when(textContentMapper.findBySourceAssetId(ASSET_ID)).thenReturn(List.of());
    doThrow(new RuntimeException("db down")).when(textContentMapper).insert(any());

    // Act
    final boolean created = persister.persistIfAbsent(VENUE_ID, ASSET_ID, "asset.pdf", "Grand Hall prose");

    // Assert
    assertThat(created).isFalse();
    verify(embeddingService, never()).embedText(any());
  }
}

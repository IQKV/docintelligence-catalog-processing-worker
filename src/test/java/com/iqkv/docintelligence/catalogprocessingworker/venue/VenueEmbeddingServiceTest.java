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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import com.iqkv.docintelligence.venue.Venue;
import com.iqkv.docintelligence.venue.VenueEmbeddingText;
import com.iqkv.docintelligence.venue.VenueMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;

@ExtendWith(MockitoExtension.class)
@DisplayName("VenueEmbeddingService Unit Tests")
class VenueEmbeddingServiceTest {

  @Mock
  private EmbeddingModel embeddingModel;

  @Mock
  private VenueMapper venueMapper;

  private VenueEmbeddingService service;

  @BeforeEach
  void setUp() {
    service = new VenueEmbeddingService(embeddingModel, venueMapper);
  }

  @Test
  @DisplayName("embed writes the vector when the model succeeds")
  void embedWritesVectorWhenModelSucceeds() {
    // Arrange
    final UUID id = UUID.randomUUID();
    final Venue venue = new Venue();
    venue.setId(id);
    venue.setName("Grand Hall");
    when(embeddingModel.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f, 0.3f});

    // Act
    final boolean result = service.embed(venue);

    // Assert
    assertThat(result).isTrue();
    verify(venueMapper).updateEmbedding(eq(id), any(float[].class));
  }

  @Test
  @DisplayName("embed swallows a model failure and never writes")
  void embedSwallowsModelFailure() {
    // Arrange
    final Venue venue = new Venue();
    venue.setId(UUID.randomUUID());
    venue.setName("Grand Hall");
    when(embeddingModel.embed(anyString())).thenThrow(new RuntimeException("ollama down"));

    // Act
    final boolean result = service.embed(venue);

    // Assert
    assertThat(result).isFalse();
    verify(venueMapper, never()).updateEmbedding(any(UUID.class), any(float[].class));
  }

  @Test
  @DisplayName("VenueEmbeddingText concatenates name, address, city and metadata")
  void buildEmbeddingTextConcatenatesFields() {
    // Arrange
    final Venue venue = new Venue();
    venue.setName("Grand Hall");
    venue.setAddress("1 Rue de la Paix");
    venue.setCity("Paris");
    venue.setMetadata("{\"_schema_version\":1}");

    // Act
    final String text = VenueEmbeddingText.build(venue);

    // Assert
    assertThat(text).isEqualTo("Grand Hall. 1 Rue de la Paix. Paris. {\"_schema_version\":1}");
  }
}

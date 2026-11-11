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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.CallResponseSpec;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;

@ExtendWith(MockitoExtension.class)
@DisplayName("DocumentClassifier Unit Tests")
class DocumentClassifierTest {

  @Mock
  private ChatClient.Builder chatClientBuilder;
  @Mock
  private ChatClient chatClient;

  private DocumentClassifier classifier;

  @BeforeEach
  void setUp() {
    when(chatClientBuilder.build()).thenReturn(chatClient);
    classifier = new DocumentClassifier(chatClientBuilder);
  }

  // ── blank / null input guards ─────────────────────────────────────────────

  @Test
  @DisplayName("classify returns null for null text without calling the model")
  void classifyReturnsNullForNullText() {
    final var result = classifier.classify(null, "venue.pdf");

    assertThat(result).isNull();
    verify(chatClient, never()).prompt();
  }

  @Test
  @DisplayName("classify returns null for blank text without calling the model")
  void classifyReturnsNullForBlankText() {
    final var result = classifier.classify("   \n  ", "venue.pdf");

    assertThat(result).isNull();
    verify(chatClient, never()).prompt();
  }

  // ── model failure handling ────────────────────────────────────────────────

  @Test
  @DisplayName("classify returns null and does not throw when the model call fails")
  void classifyReturnsNullOnModelException() {
    final var requestSpec = mock(ChatClientRequestSpec.class);
    final var responseSpec = mock(CallResponseSpec.class);

    when(chatClient.prompt()).thenReturn(requestSpec);
    when(requestSpec.system(any(String.class))).thenReturn(requestSpec);
    when(requestSpec.user(any(String.class))).thenReturn(requestSpec);
    when(requestSpec.call()).thenReturn(responseSpec);
    when(responseSpec.entity(VenueExtraction.class)).thenThrow(new RuntimeException("Ollama down"));

    final var result = classifier.classify("Grand Hall\n1 Bermondsey Street", "venue.pdf");

    assertThat(result).isNull();
  }

  // ── truncation ────────────────────────────────────────────────────────────

  @Test
  @DisplayName("classify truncates text to TEXT_MAX_CHARS at a word boundary")
  void classifyTruncatesAtWordBoundary() {
    // 7999 'a' chars + space + 'overflow' — total exceeds TEXT_MAX_CHARS (8000)
    final String longText = "a".repeat(7_999) + " overflow";

    final var requestSpec = mock(ChatClientRequestSpec.class);
    final var responseSpec = mock(CallResponseSpec.class);

    when(chatClient.prompt()).thenReturn(requestSpec);
    when(requestSpec.system(any(String.class))).thenReturn(requestSpec);
    when(requestSpec.user(any(String.class))).thenAnswer(inv -> {
      final String sent = inv.getArgument(0);
      assertThat(sent.length()).isLessThanOrEqualTo(DocumentClassifier.TEXT_MAX_CHARS);
      assertThat(sent).doesNotContain("overflow");
      return requestSpec;
    });
    when(requestSpec.call()).thenReturn(responseSpec);
    when(responseSpec.entity(VenueExtraction.class)).thenReturn(null);

    classifier.classify(longText, "big.pdf");
  }

  @Test
  @DisplayName("classify does not truncate text shorter than TEXT_MAX_CHARS")
  void classifyDoesNotTruncateShortText() {
    final String shortText = "Grand Hall\n1 Bermondsey Street, London";

    final var requestSpec = mock(ChatClientRequestSpec.class);
    final var responseSpec = mock(CallResponseSpec.class);

    when(chatClient.prompt()).thenReturn(requestSpec);
    when(requestSpec.system(any(String.class))).thenReturn(requestSpec);
    when(requestSpec.user(any(String.class))).thenAnswer(inv -> {
      final String sent = inv.getArgument(0);
      assertThat(sent).isEqualTo(shortText);
      return requestSpec;
    });
    when(requestSpec.call()).thenReturn(responseSpec);
    when(responseSpec.entity(VenueExtraction.class)).thenReturn(null);

    classifier.classify(shortText, "venue.pdf");
  }
}

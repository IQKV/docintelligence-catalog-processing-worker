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

/**
 * One step in the venue post-extraction processing chain.
 *
 * <p>Implementations are Spring {@code @Component} beans ordered with {@code @Order}.
 * {@link VenueAssetProcessingService} injects the full {@code List<VenueProcessingFilter>}
 * and runs them in order inside the extraction try-block.
 *
 * <p>A filter may throw any exception to abort the chain; the caller marks the asset
 * {@code FAILED} and rethrows as {@link IllegalStateException}.
 */
interface VenueProcessingFilter {

  /**
   * Applies this step to the processing context.
   *
   * @param ctx shared state for the current pipeline run
   * @throws Exception on unrecoverable failure — aborts the chain
   */
  void apply(VenueProcessingContext ctx) throws Exception;
}

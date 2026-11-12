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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.iqkv.docintelligence.catalogprocessingworker.infrastructure.config.AllTenantsKeyProvider;
import com.iqkv.docintelligence.venue.VenueTextContent;
import com.iqkv.docintelligence.venue.VenueTextContentMapper;
import com.iqkv.foundation.tenancy.TenantContext;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Recovers tenant venue text-content rows whose {@code embedding} column is still {@code NULL}.
 *
 * <p>Mirrors {@link VenueEmbeddingBackfillService} for the {@code venue_text_contents} table:
 * a row written during an Ollama outage stays invisible to vector search until this scheduler
 * re-embeds it. Iterates every provisioned tenant schema via {@link AllTenantsKeyProvider}, sets
 * the tenant context so {@code MyBatisSchemaInterceptor} routes mapper calls to
 * {@code t_{tenantKey}}, and embeds pending rows oldest-first. Stops a tenant's pass as soon as a
 * whole batch makes no progress. ShedLock keeps a single instance running across the cluster.
 */
@Service
class VenueTextContentEmbeddingBackfillService {

  private static final Logger log = LoggerFactory.getLogger(VenueTextContentEmbeddingBackfillService.class);
  private static final int BACKFILL_BATCH = 50;

  private final VenueTextContentMapper mapper;
  private final EmbeddingModel embeddingModel;
  private final AllTenantsKeyProvider tenantsKeyProvider;

  VenueTextContentEmbeddingBackfillService(final VenueTextContentMapper mapper,
                                           final EmbeddingModel embeddingModel,
                                           final AllTenantsKeyProvider tenantsKeyProvider) {
    this.mapper = mapper;
    this.embeddingModel = embeddingModel;
    this.tenantsKeyProvider = tenantsKeyProvider;
  }

  /**
   * Embeds every tenant venue text-content row whose {@code embedding} column is still
   * {@code NULL}, across all provisioned tenants. Runs after the previous invocation completes,
   * at most one instance at a time across the cluster.
   */
  @Scheduled(fixedDelayString = "${iqkv.catalog.venue-text-content-embedding.backfill-interval-ms:600000}")
  @SchedulerLock(name = "venue-text-content-embedding-backfill")
  void backfillAll() {
    final List<String> tenantKeys = tenantsKeyProvider.findAllTenantKeys();
    log.debug("venue text-content embedding backfill: scanning {} tenant schema(s)", tenantKeys.size());
    for (final String tenantKey : tenantKeys) {
      try {
        TenantContext.setCurrentTenant(tenantKey);
        backfillTenant();
      } catch (final RuntimeException ex) {
        log.warn("venue text-content embedding backfill failed for tenant {}: {}", tenantKey, ex.getMessage());
      } finally {
        TenantContext.clear();
      }
    }
  }

  /**
   * Embeds the pending rows of the active tenant until none remain or a batch makes no progress.
   *
   * @return the number of rows embedded
   */
  int backfillTenant() {
    int embedded = 0;
    while (true) {
      final List<UUID> pending = mapper.findUnembeddedIds(BACKFILL_BATCH);
      if (pending.isEmpty()) {
        break;
      }
      final int embeddedBefore = embedded;
      for (final UUID id : pending) {
        final Optional<VenueTextContent> row = mapper.findById(id);
        if (row.isPresent() && embedQuietly(row.get())) {
          embedded++;
        }
      }
      if (embedded == embeddedBefore) {
        // No progress this batch — Ollama likely unavailable; stop to avoid spinning.
        break;
      }
    }
    if (embedded > 0) {
      log.info("venue text-content embedding backfill: embedded {} row(s)", embedded);
    }
    return embedded;
  }

  private boolean embedQuietly(final VenueTextContent tc) {
    try {
      final float[] vector = embeddingModel.embed(tc.getContent());
      mapper.updateEmbedding(tc.getId(), vector);
      log.debug("embedded VenueTextContent {} ({} dims)", tc.getId(), vector.length);
      return true;
    } catch (final RuntimeException ex) {
      log.warn("embedding skipped for VenueTextContent {} — Ollama unavailable or slow: {}",
          tc.getId(), ex.getMessage());
      return false;
    }
  }
}

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
import com.iqkv.docintelligence.venue.Venue;
import com.iqkv.docintelligence.venue.VenueMapper;
import com.iqkv.foundation.tenancy.TenantContext;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Recovers tenant venues whose {@code embedding} column is still {@code NULL}.
 *
 * <p>A venue only ever gets an embedding at the moment it is written (by
 * {@code docintelligence-catalog-service}'s {@code VenueEmbeddingService} on an admin
 * create/replace/patch), so a row written during an Ollama outage stays invisible to vector search
 * forever. This worker is its recovery net — the asynchronous half of the catalog, which owns no
 * request handling.
 *
 * <p>Iterates every provisioned tenant schema (via {@link AllTenantsKeyProvider}), sets the tenant
 * context for each so the {@code MyBatisSchemaInterceptor} routes the mapper calls to that
 * tenant's {@code t_{tenantKey}} schema, and embeds pending rows oldest-first. Stops a tenant's
 * pass as soon as a whole batch fails, so an unreachable Ollama cannot make the scheduler spin on
 * the same rows forever. ShedLock keeps a single instance running the backfill across the cluster.
 */
@Service
class VenueEmbeddingBackfillService {

  private static final Logger log = LoggerFactory.getLogger(VenueEmbeddingBackfillService.class);
  private static final int BACKFILL_BATCH = 50;

  private final VenueMapper venueMapper;

  private final VenueEmbeddingService embeddingService;

  private final AllTenantsKeyProvider tenantsKeyProvider;

  VenueEmbeddingBackfillService(final VenueMapper venueMapper,
                                final VenueEmbeddingService embeddingService,
                                final AllTenantsKeyProvider tenantsKeyProvider) {
    this.venueMapper = venueMapper;
    this.embeddingService = embeddingService;
    this.tenantsKeyProvider = tenantsKeyProvider;
  }

  /**
   * Embeds every tenant venue whose {@code embedding} column is still {@code NULL}, across all
   * provisioned tenants. Runs after the previous invocation completes, at most one instance at a
   * time across the cluster.
   */
  @Scheduled(fixedDelayString = "${iqkv.catalog.venue-embedding.backfill-interval-ms:600000}")
  @SchedulerLock(name = "venue-embedding-backfill")
  void backfillAll() {
    final List<String> tenantKeys = tenantsKeyProvider.findAllTenantKeys();
    log.debug("venue embedding backfill: scanning {} tenant schema(s)", tenantKeys.size());
    for (final String tenantKey : tenantKeys) {
      try {
        TenantContext.setCurrentTenant(tenantKey);
        backfillTenant();
      } catch (final RuntimeException ex) {
        log.warn("venue embedding backfill failed for tenant {}: {}", tenantKey, ex.getMessage());
      } finally {
        TenantContext.clear();
      }
    }
  }

  /**
   * Embeds the pending venues of the active tenant until none remain or a batch makes no progress.
   *
   * @return the number of venues embedded
   */
  int backfillTenant() {
    int embedded = 0;
    while (true) {
      final List<UUID> pending = venueMapper.findUnembeddedIds(BACKFILL_BATCH);
      if (pending.isEmpty()) {
        break;
      }
      final int embeddedBefore = embedded;
      for (final UUID id : pending) {
        final Optional<Venue> venue = venueMapper.findById(id);
        if (venue.isPresent() && embeddingService.embed(venue.get())) {
          embedded++;
        }
      }
      if (embedded == embeddedBefore) {
        break;
      }
    }
    if (embedded > 0) {
      log.info("venue embedding backfill: embedded {} venue(s)", embedded);
    }
    return embedded;
  }
}

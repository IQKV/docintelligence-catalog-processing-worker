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

package com.iqkv.docintelligence.catalogservice.ping;

import java.time.Instant;

/**
 * DTOs for the ping API surface.
 *
 * <p>All types are immutable records. The response shapes are intentionally minimal — they
 * exist to demonstrate the response-record convention used across foundation services, not
 * to carry operational data (that is {@code /actuator/health}'s job).
 */
public final class PingDtos {

  private PingDtos() {
  }

  /**
   * Response returned by the unauthenticated public ping endpoint.
   *
   * @param message   always {@code "pong"}
   * @param timestamp server time at the moment the request was processed
   */
  public record PublicPongResponse(
      String message,
      Instant timestamp) {
  }

  /**
   * Response returned by the authenticated tenant-scoped ping endpoint.
   *
   * <p>Echoes the resolved tenant key back to the caller so a client can verify
   * that the {@code X-Tenant-ID} header (or JWT {@code tenant_id} claim) was
   * resolved correctly by the {@code TenantExtractionFilter}.
   *
   * @param message   always {@code "pong"}
   * @param tenantKey the tenant key resolved from the current request context
   * @param timestamp server time at the moment the request was processed
   */
  public record TenantPongResponse(
      String message,
      String tenantKey,
      Instant timestamp) {
  }

  /**
   * Response returned by the platform-admin ping endpoint.
   *
   * <p>Echoes the caller's user ID so an operator can confirm their JWT is being
   * decoded correctly and that their account carries the {@code PLATFORM_ADMIN} authority.
   *
   * @param message   always {@code "pong"}
   * @param actorId   the {@code user_id} claim extracted from the caller's JWT
   * @param timestamp server time at the moment the request was processed
   */
  public record AdminPongResponse(
      String message,
      String actorId,
      Instant timestamp) {
  }
}

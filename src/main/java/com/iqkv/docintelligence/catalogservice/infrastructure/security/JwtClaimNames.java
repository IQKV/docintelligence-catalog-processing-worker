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

package com.iqkv.docintelligence.catalogservice.infrastructure.security;

/**
 * JWT claim name constants for this service.
 *
 * <p>Tokens are issued by {@code foundation-iam-service} using RSA-256. The full canonical
 * claim set is defined there. Only declare the claims this service actually reads.
 *
 * <p>The IAM access token carries these platform claims (snake_case throughout):
 * <ul>
 *   <li>{@code sub} — user email (standard claim)</li>
 *   <li>{@code iss} — {@code "foundation-iam-service"} (standard claim)</li>
 *   <li>{@code iat}, {@code exp}, {@code jti} — standard claims</li>
 *   <li>{@code type} — {@code "access"} or {@code "refresh"}</li>
 *   <li>{@code user_id} — UUID string</li>
 *   <li>{@code email} — user email</li>
 *   <li>{@code first_name}, {@code last_name}</li>
 *   <li>{@code tenant_id} — 8-character NanoID; absent on platform-admin tokens</li>
 *   <li>{@code authorities} — list of granted authority strings</li>
 *   <li>{@code email_verified}, {@code onboarding_completed}, {@code profile_completed}</li>
 *   <li>{@code plan_code} — optional; absent when no active subscription</li>
 * </ul>
 *
 * <p>Most services only need {@code tenant_id} and {@code authorities} directly — the rest
 * arrive via headers injected by the gateway ({@code X-User-ID}, {@code X-User-Email},
 * {@code X-Tenant-ID}, {@code X-Plan-Code}, {@code X-User-Authorities}).
 */
public final class JwtClaimNames {

  /**
   * Tenant key (8-character NanoID).
   * Used by {@code TenantExtractionFilter} as the fallback source when the
   * {@code X-Tenant-ID} header is absent.
   * Absent on platform-admin tokens — those operate cross-tenant.
   */
  public static final String TENANT_ID = "tenant_id";

  /**
   * User UUID string.
   * Identifies the authenticated user across all foundation services.
   * Present on all access tokens, including platform-admin tokens.
   */
  public static final String USER_ID = "user_id";

  /**
   * User first name.
   */
  public static final String FIRST_NAME = "first_name";

  /**
   * User last name.
   */
  public static final String LAST_NAME = "last_name";

  /**
   * Active billing plan code, e.g. {@code "pro"} or {@code "starter"}.
   * Absent when the user/tenant has no active subscription.
   */
  public static final String PLAN_CODE = "plan_code";

  /**
   * Granted authority strings, e.g. {@code ["ROLE_USER", "TENANT_OWNER"]}.
   * Mapped to Spring Security {@code GrantedAuthority} instances by the
   * {@code JwtAuthenticationConverter}.
   */
  public static final String AUTHORITIES = "authorities";

  private JwtClaimNames() {
  }
}

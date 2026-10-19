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

package com.iqkv.docintelligence.catalogservice.infrastructure.config;

/**
 * Platform-wide tenancy rollout mode.
 *
 * <p>Must be kept in sync with the IAM service declaration — all core services
 * (IAM, Billing, CMS, etc.) read the same {@code ROLLOUT_MODE} environment variable
 * and must behave consistently.
 *
 * <ul>
 *   <li>{@link #MULTI_TENANT} — each user signup creates a new tenant schema.
 *       Tenant context is resolved from the {@code X-Tenant-ID} header or the
 *       {@code tenant_id} JWT claim on every request.</li>
 *   <li>{@link #SINGLE_TENANT} — all users share a single pre-provisioned tenant
 *       schema. Tenant context is resolved from the default tenant key configured
 *       in {@code iqkv.tenancy.default-tenant-key}.</li>
 * </ul>
 */
public enum RolloutMode {
  MULTI_TENANT,
  SINGLE_TENANT
}

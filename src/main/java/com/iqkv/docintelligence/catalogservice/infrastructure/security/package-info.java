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

/**
 * Security-related infrastructure: servlet filters, JWT utilities, and claim name constants.
 *
 * <p>Key classes:
 * <ul>
 *   <li>{@link com.iqkv.docintelligence.catalogservice.infrastructure.security.CorrelationIdFilter} —
 *       propagates / generates {@code X-Correlation-ID} and populates MDC.</li>
 *   <li>{@link com.iqkv.docintelligence.catalogservice.infrastructure.security.TenantExtractionFilter} —
 *       resolves tenant key from header or JWT claim and sets {@code TenantContext}.</li>
 *   <li>{@link com.iqkv.docintelligence.catalogservice.infrastructure.security.JwtClaimNames} —
 *       custom JWT claim name constants shared with the IAM service.</li>
 * </ul>
 *
 * <p>This package must not contain business logic or domain model references.
 */

package com.iqkv.docintelligence.catalogservice.infrastructure.security;

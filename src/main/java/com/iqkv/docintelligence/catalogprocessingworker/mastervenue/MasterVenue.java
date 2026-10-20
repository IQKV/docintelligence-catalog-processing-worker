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

package com.iqkv.docintelligence.catalogprocessingworker.mastervenue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A platform-owned master venue — one row of the {@code public.master_venue} reference dataset.
 *
 * <p>Platform-level data, not tenant-scoped: it lives in the {@code public} schema and is never
 * copied into a tenant schema. {@code metadata} is the raw JSONB canonical metadata document
 * and carries a mandatory {@code _schema_version} key.
 *
 * <p>The table is created and owned by {@code docintelligence-catalog-service}; this service only
 * reads and writes it over the shared database and never runs migrations of its own.
 */
public class MasterVenue {

  private UUID id;
  private String name;
  private String address;
  private String city;
  private BigDecimal locationLat;
  private BigDecimal locationLng;
  private String metadata;
  private BigDecimal confidence;
  private String source;
  private Instant createdAt;
  private Instant updatedAt;

  public UUID getId() {
    return id;
  }

  public void setId(final UUID id) {
    this.id = id;
  }

  public String getName() {
    return name;
  }

  public void setName(final String name) {
    this.name = name;
  }

  public String getAddress() {
    return address;
  }

  public void setAddress(final String address) {
    this.address = address;
  }

  public String getCity() {
    return city;
  }

  public void setCity(final String city) {
    this.city = city;
  }

  public BigDecimal getLocationLat() {
    return locationLat;
  }

  public void setLocationLat(final BigDecimal locationLat) {
    this.locationLat = locationLat;
  }

  public BigDecimal getLocationLng() {
    return locationLng;
  }

  public void setLocationLng(final BigDecimal locationLng) {
    this.locationLng = locationLng;
  }

  public String getMetadata() {
    return metadata;
  }

  public void setMetadata(final String metadata) {
    this.metadata = metadata;
  }

  public BigDecimal getConfidence() {
    return confidence;
  }

  public void setConfidence(final BigDecimal confidence) {
    this.confidence = confidence;
  }

  public String getSource() {
    return source;
  }

  public void setSource(final String source) {
    this.source = source;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(final Instant createdAt) {
    this.createdAt = createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(final Instant updatedAt) {
    this.updatedAt = updatedAt;
  }
}

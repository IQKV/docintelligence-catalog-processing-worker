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

package com.iqkv.docintelligence.catalogservice.infrastructure.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

import com.iqkv.foundation.tenancy.TenantKeyProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Implementation of {@link TenantKeyProvider} for this service.
 *
 * <p>Discovers tenant schemas already provisioned in this service's database by querying
 * {@code information_schema.schemata} for schemas whose names start with the {@code t_} prefix.
 * This avoids any cross-service dependency on the IAM tenant registry.
 *
 * <p>On startup, {@link com.iqkv.foundation.tenancy.TenantLiquibaseRunner} will apply any pending
 * Liquibase changesets to each discovered schema, keeping all tenant schemas current after a deployment.
 */
@Component
public class AllTenantsKeyProvider implements TenantKeyProvider {

  private static final Logger log = LoggerFactory.getLogger(AllTenantsKeyProvider.class);

  private static final String QUERY =
      "SELECT schema_name FROM information_schema.schemata "
      + "WHERE schema_name LIKE 't_%' ORDER BY schema_name ASC";

  private static final String SCHEMA_PREFIX = "t_";

  private final DataSource dataSource;

  public AllTenantsKeyProvider(final DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @Override
  public List<String> findAllTenantKeys() {
    final List<String> keys = new ArrayList<>();
    try (final Connection connection = dataSource.getConnection();
         final PreparedStatement ps = connection.prepareStatement(QUERY);
         final ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        keys.add(rs.getString(1).substring(SCHEMA_PREFIX.length()));
      }
    } catch (final SQLException e) {
      log.error("Failed to query tenant schemas from information_schema, returning empty list", e);
    }
    log.debug("AllTenantsKeyProvider resolved {} tenant key(s) for schema upgrade", keys.size());
    return keys;
  }
}

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

import javax.sql.DataSource;

import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Wires ShedLock to its JDBC provider backed by the {@code public.shedlock} table.
 *
 * <p>The table is created by the Liquibase migration
 * {@code db/changelog/system/20261013120000-create-shedlock-table.xml}.
 *
 * <p>{@code usingDbTime()} ensures lock expiry is evaluated using the database clock,
 * which prevents split-brain issues on hosts with clock skew.
 *
 * <p>To add a scheduled job protected by ShedLock:
 * <pre>{@code
 * @Scheduled(cron = "0 0 * * * *")
 * @SchedulerLock(name = "myJob", lockAtMostFor = "PT10M", lockAtLeastFor = "PT5M")
 * public void myJob() { ... }
 * }</pre>
 */
@Configuration
public class ShedLockConfig {

  @Bean
  public JdbcTemplateLockProvider lockProvider(final DataSource dataSource) {
    return new JdbcTemplateLockProvider(
        JdbcTemplateLockProvider.Configuration.builder()
            .withJdbcTemplate(new JdbcTemplate(dataSource))
            .usingDbTime()
            .build()
    );
  }
}

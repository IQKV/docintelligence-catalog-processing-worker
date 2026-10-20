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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * Master venue reads and writes against {@code public.master_venue}.
 *
 * <p>This is one of the few mappers allowed to name the {@code public} schema explicitly —
 * master venue rows are platform-owned reference data, never tenant-scoped.
 * {@code created_at} / {@code updated_at} are maintained by the database.
 */
@Mapper
public interface MasterVenueMapper {

  void insert(MasterVenue venue);

  void update(MasterVenue venue);

  void deleteById(@Param("id") UUID id);

  Optional<MasterVenue> findById(@Param("id") UUID id);

  List<MasterVenue> findAll(@Param("limit") int limit, @Param("offset") int offset);

  long countAll();

  /**
   * Exact-name lookup ignoring case — the deduplication primitive available before the
   * pg_trgm similarity slice lands.
   *
   * @param name the venue name to match, compared case-insensitively
   * @return all master venues whose name matches, ordered by name
   */
  List<MasterVenue> findByNameIgnoreCase(@Param("name") String name);
}

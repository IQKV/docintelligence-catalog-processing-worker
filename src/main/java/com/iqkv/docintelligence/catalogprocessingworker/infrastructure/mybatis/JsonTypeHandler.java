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

package com.iqkv.docintelligence.catalogprocessingworker.infrastructure.mybatis;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

/**
 * MyBatis type handler for JSON/JSONB columns mapped to a raw JSON {@link String}.
 *
 * <p>The parameter is bound as {@link Types#OTHER} so the driver sends it untyped and PostgreSQL
 * coerces it to the target {@code jsonb} column — this keeps the handler free of a compile-time
 * dependency on the PostgreSQL driver, which is declared at {@code runtime} scope.
 */
public class JsonTypeHandler extends BaseTypeHandler<String> {

  @Override
  public void setNonNullParameter(final PreparedStatement ps, final int i, final String parameter,
                                  final JdbcType jdbcType) throws SQLException {
    ps.setObject(i, parameter, Types.OTHER);
  }

  @Override
  public String getNullableResult(final ResultSet rs, final String columnName) throws SQLException {
    return rs.getString(columnName);
  }

  @Override
  public String getNullableResult(final ResultSet rs, final int columnIndex) throws SQLException {
    return rs.getString(columnIndex);
  }

  @Override
  public String getNullableResult(final CallableStatement cs, final int columnIndex) throws SQLException {
    return cs.getString(columnIndex);
  }
}

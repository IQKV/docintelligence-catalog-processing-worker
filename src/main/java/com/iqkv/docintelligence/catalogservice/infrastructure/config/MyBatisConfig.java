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

import java.util.UUID;
import javax.sql.DataSource;

import com.iqkv.docintelligence.catalogservice.infrastructure.mybatis.UuidTypeHandler;
import com.iqkv.foundation.tenancy.MyBatisSchemaInterceptor;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * MyBatis configuration: registers the schema interceptor and configures mapper locations.
 */
@Configuration
@MapperScan(
    basePackages = {
        "com.iqkv.docintelligence.catalogservice.chat"
    },
    annotationClass = Mapper.class
)
public class MyBatisConfig {

  @Bean
  public SqlSessionFactory sqlSessionFactory(final DataSource dataSource) throws Exception {
    final SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
    factory.setDataSource(dataSource);
    factory.setMapperLocations(
        new PathMatchingResourcePatternResolver()
            .getResources("classpath:mappers/**/*.xml")
    );

    final org.apache.ibatis.session.Configuration config =
        new org.apache.ibatis.session.Configuration();
    config.setMapUnderscoreToCamelCase(true);
    config.addInterceptor(new MyBatisSchemaInterceptor());
    config.getTypeHandlerRegistry().register(UUID.class, UuidTypeHandler.class);
    factory.setConfiguration(config);

    return factory.getObject();
  }
}

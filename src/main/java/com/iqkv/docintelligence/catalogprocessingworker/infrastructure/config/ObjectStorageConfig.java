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

package com.iqkv.docintelligence.catalogprocessingworker.infrastructure.config;

import io.minio.MinioClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class ObjectStorageConfig {

  /**
   * Primary MinioClient used for internal operations (object download, upload, delete, etc.).
   * Connects to the internal cluster endpoint.
   */
  @Bean
  @Primary
  public MinioClient minioClient(final ObjectStorageConfigurationProperties props) {
    return MinioClient.builder()
        .endpoint(props.endpoint())
        .credentials(props.accessKey(), props.secretKey())
        .build();
  }

  /**
   * MinioClient used exclusively for generating presigned URLs.
   *
   * <p>When {@code publicEndpoint} is set, this client is built with the public-facing URL
   * so the MinIO SDK embeds the correct host AND computes the HMAC signature against it.
   * Presigned URLs signed with the internal endpoint cannot be used with the public endpoint
   * because the host is part of the signed canonical request — a mismatch causes 403.
   *
   * <p>Falls back to the primary client's endpoint when {@code publicEndpoint} is blank
   * (local dev, AWS S3 — no rewrite needed).
   */
  @Bean
  public MinioClient presigningMinioClient(final ObjectStorageConfigurationProperties props) {
    final String presigningEndpoint =
        (props.publicEndpoint() != null && !props.publicEndpoint().isBlank())
            ? props.publicEndpoint()
            : props.endpoint();
    return MinioClient.builder()
        .endpoint(presigningEndpoint)
        .credentials(props.accessKey(), props.secretKey())
        .build();
  }
}

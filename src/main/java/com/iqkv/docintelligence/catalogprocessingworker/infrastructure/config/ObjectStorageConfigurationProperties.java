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

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Binds {@code iqkv.objectstorage.*} from application.yml.
 *
 * <p>Supports both MinIO (SIT/UAT) and AWS S3 (production) via the same S3-compatible API.
 * The {@code endpoint} field is ignored by the AWS SDK when using the default S3 region resolver,
 * so set it to {@code https://s3.amazonaws.com} for AWS or to the MinIO service URL otherwise.
 *
 * <p>The worker shares the {@code catalog-assets} bucket with docintelligence-catalog-service: it
 * reads the objects the service presigns and writes back derived artefacts, so the bucket name and
 * credentials must match the service's configuration.
 *
 * <p>{@code publicEndpoint} is the URL reachable by external clients (browsers, mobile apps).
 * When set, presigned URLs have their host rewritten from {@code endpoint} to {@code publicEndpoint}
 * before being returned to callers — necessary when MinIO runs on an in-cluster hostname that
 * external clients cannot resolve.  Leave blank to use {@code endpoint} as-is (local dev).
 */
@Validated
@ConfigurationProperties(prefix = "iqkv.objectstorage")
public record ObjectStorageConfigurationProperties(
    // S3-compatible endpoint URL — MinIO in-cluster or https://s3.amazonaws.com for AWS
    @NotBlank @Pattern(regexp = "^https?://.+", message = "endpoint must be a valid http or https URL") String endpoint,
    // Public-facing URL used to rewrite presigned URLs returned to external clients.
    // Optional — when blank, presigned URLs are returned unchanged.
    String publicEndpoint,
    @NotBlank String accessKey,
    @NotBlank String secretKey,
    @NotBlank String bucketName,
    // AWS region identifier, e.g. "us-east-1". Required by the S3 client even for MinIO.
    @NotBlank String region,
    boolean ssl,
    @Valid @NotNull Upload upload
) {

  /**
   * Upload policy constraints applied before the object is sent to storage.
   */
  public record Upload(
      // Maximum allowed file size in bytes. Defaults to 5 MB (5_242_880).
      @Min(1) long maxFileSizeBytes,
      // Lifetime of a pre-signed PUT/GET URL in minutes.
      @Positive int presignedUrlExpirationMinutes,
      // Comma-separated list of accepted MIME types, e.g. "application/pdf,image/png".
      @NotNull List<String> allowedMimeTypes
  ) {
  }
}

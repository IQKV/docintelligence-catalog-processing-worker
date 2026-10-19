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

import jakarta.annotation.PostConstruct;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Binds {@code iqkv.auth.*} from {@code application.yml}.
 *
 * <p>This service is a JWT resource server — it only needs to verify tokens issued by the IAM
 * service. It does not issue tokens and therefore does not hold a private key.
 *
 * <p>Two mutually-exclusive verification strategies are supported:
 * <ul>
 *   <li><b>JWKS URI</b> ({@code jwks-uri}) — preferred for deployed (K8s) environments.
 *       Keys are fetched from {@code foundation-iam-service/.well-known/jwks.json} and cached
 *       locally. New keys are picked up automatically on key rotation without redeployment.</li>
 *   <li><b>Local PEM</b> ({@code public-key-path}) — for local development and tests.
 *       Accepts {@code classpath:} or {@code file:} resources. Use this when the IAM service
 *       is not reachable (e.g. unit/integration tests running in isolation).</li>
 * </ul>
 *
 * <p>Exactly one of {@code jwks-uri} or {@code public-key-path} must be non-blank. An
 * {@link IllegalStateException} is thrown at startup if both or neither are configured.
 *
 * <p>The full auth config (expiry, refresh, security, password-reset, magic-link) lives in the
 * IAM service. Add those sub-records here only if this service ever needs to issue its own tokens.
 */
@Validated
@ConfigurationProperties(prefix = "iqkv.auth")
public record AuthConfigurationProperties(
    @Valid @NotNull Jwt jwt
) {

  @PostConstruct
  public void validate() {
    if (jwt != null) {
      final boolean hasJwksUri = jwt.jwksUri() != null && !jwt.jwksUri().isBlank();
      final boolean hasPublicKeyPath = jwt.publicKeyPath() != null && !jwt.publicKeyPath().isBlank();
      if (hasJwksUri && hasPublicKeyPath) {
        throw new IllegalStateException(
            "iqkv.auth.jwt: set either jwks-uri or public-key-path, not both. "
            + "Use jwks-uri in deployed (K8s) environments and public-key-path for local dev/tests.");
      }
      if (!hasJwksUri && !hasPublicKeyPath) {
        throw new IllegalStateException(
            "iqkv.auth.jwt: one of jwks-uri or public-key-path must be configured. "
            + "Set JWT_JWKS_URI (K8s) or JWT_PUBLIC_KEY_PATH (local dev).");
      }
    }
  }

  public record Jwt(
      // --- Deployed environments (K8s) ---
      // JWKS URI of the IAM service. Keys are fetched and cached; new keys are discovered
      // automatically. Set via JWT_JWKS_URI env var in Helm values / K8s configmap.
      // Example: http://foundation-iam-service/.well-known/jwks.json
      // Leave blank (or unset) when using public-key-path for local dev / tests.
      String jwksUri,

      // --- Local development and tests ---
      // Path to the RSA public key PEM used to verify tokens from the IAM service.
      // Accepts classpath: (test) or file: (deployed) resources.
      // Ignored when jwks-uri is set.
      String publicKeyPath
  ) {
  }
}

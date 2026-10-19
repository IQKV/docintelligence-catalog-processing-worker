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

package com.iqkv.docintelligence.catalogservice.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.UUID;

import com.iqkv.foundation.tenancy.TenantContext;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Servlet filter that resolves the tenant key from the {@code X-Tenant-ID} header (priority 1)
 * or the JWT {@code tenant_id} claim (priority 2) and sets {@link TenantContext}.
 *
 * <p>Returns a RFC 7807 {@code application/problem+json} 400 response when the tenant
 * cannot be resolved. Always clears the tenant context in a {@code finally} block.
 *
 * <p>Admin endpoints ({@code /api/v1/catalog/admin/**}) are exempt from tenant extraction.
 * Those paths are restricted to {@code PLATFORM_ADMIN} authority and operate across all
 * tenants for oversight purposes; tenant context is applied per-use-case within those handlers.
 *
 * <p>The public ping endpoint ({@code /api/v1/catalog/ping}) is also exempt — it requires
 * no authentication or tenant context and is used as an unauthenticated reachability probe.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class TenantExtractionFilter extends OncePerRequestFilter {

  private static final String TENANT_HEADER = "X-Tenant-ID";
  private static final String MDC_CORRELATION_ID = "correlationId";

  private final JwtDecoder jwtDecoder;
  private final JsonMapper objectMapper;

  public TenantExtractionFilter(final JwtDecoder jwtDecoder, final JsonMapper objectMapper) {
    this.jwtDecoder = jwtDecoder;
    this.objectMapper = objectMapper;
  }

  @Override
  protected void doFilterInternal(final HttpServletRequest request,
                                  final HttpServletResponse response,
                                  final FilterChain filterChain)
      throws ServletException, IOException {
    try {
      final String tenantId = resolveTenantId(request);
      if (tenantId == null) {
        writeProblemDetail(response, request, HttpServletResponse.SC_BAD_REQUEST,
            "Tenant ID Required",
            "Request must include a tenant identifier via the X-Tenant-ID header or a JWT with a tenant_id claim.");
        return;
      }
      TenantContext.setCurrentTenant(tenantId);
      filterChain.doFilter(request, response);
    } finally {
      TenantContext.clear();
    }
  }

  @Override
  protected boolean shouldNotFilter(final HttpServletRequest request) {
    final String path = request.getRequestURI();

    // Infrastructure / docs — always skip
    return path.startsWith("/actuator/")
           || path.equals("/api-docs")
           || path.startsWith("/api-docs/")
           || path.startsWith("/swagger-ui/")
           // Public ping — no tenant context required.
           || path.equals("/api/v1/catalog/ping")
           // Platform-admin paths — cross-tenant by design, no tenant context ever required.
           || path.startsWith("/api/v1/catalog/admin/");
  }

  private String resolveTenantId(final HttpServletRequest request) {
    // Priority 1: X-Tenant-ID header
    final String header = request.getHeader(TENANT_HEADER);
    if (header != null && !header.isBlank()) {
      return header;
    }

    // Priority 2: JWT tenant_id claim from Bearer token
    final String authorization = request.getHeader("Authorization");
    if (authorization != null && authorization.startsWith("Bearer ")) {
      try {
        final Jwt jwt = jwtDecoder.decode(authorization.substring(7));
        return jwt.getClaimAsString(JwtClaimNames.TENANT_ID);
      } catch (final JwtException e) {
        return null;
      }
    }

    return null;
  }

  /**
   * Writes a RFC 7807 ProblemDetail response, consistent with {@code GlobalExceptionHandler}.
   */
  private void writeProblemDetail(final HttpServletResponse response,
                                  final HttpServletRequest request,
                                  final int status,
                                  final String title,
                                  final String detail) throws IOException {
    final ProblemDetail pd = ProblemDetail.forStatus(status);
    pd.setType(URI.create("about:blank"));
    pd.setTitle(title);
    pd.setDetail(detail);
    pd.setInstance(URI.create(request.getRequestURI()));
    pd.setProperty("correlationId", MDC.get(MDC_CORRELATION_ID));
    pd.setProperty("requestId", "req-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));

    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    objectMapper.writeValue(response.getWriter(), pd);
  }
}

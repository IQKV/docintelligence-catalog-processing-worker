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
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Servlet filter that propagates or generates a correlation ID for each request.
 *
 * <p>Reads the {@code X-Correlation-ID} request header if present, otherwise generates
 * a fresh UUID. The value is stored in the SLF4J MDC under the {@code correlationId} key
 * (available in log patterns as {@code %X{correlationId}}) and echoed back in the response
 * header so callers can correlate log entries across services.
 *
 * <p>Runs at {@link Ordered#HIGHEST_PRECEDENCE} — before authentication filters — so that
 * every log line including auth failures carries the correlation ID.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

  private static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
  private static final String MDC_KEY = "correlationId";

  @Override
  protected void doFilterInternal(final HttpServletRequest request,
                                  final HttpServletResponse response,
                                  final FilterChain filterChain)
      throws ServletException, IOException {
    final String correlationId = resolveCorrelationId(request);
    MDC.put(MDC_KEY, correlationId);
    response.setHeader(CORRELATION_ID_HEADER, correlationId);
    try {
      filterChain.doFilter(request, response);
    } finally {
      MDC.remove(MDC_KEY);
    }
  }

  private String resolveCorrelationId(final HttpServletRequest request) {
    final String header = request.getHeader(CORRELATION_ID_HEADER);
    if (header != null && !header.isBlank()) {
      return header;
    }
    return UUID.randomUUID().toString();
  }
}

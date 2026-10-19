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

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.UUID;

import com.iqkv.foundation.entitlement.plan.BillingServiceException;
import com.iqkv.foundation.entitlement.plan.PlanFeatureNotAvailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
  private static final String MDC_CORRELATION_ID = "correlationId";

  private ProblemDetail problem(final String type,
                                final String title,
                                final int status,
                                final String detail,
                                final HttpServletRequest request) {
    final ProblemDetail pd = ProblemDetail.forStatus(status);
    pd.setType(URI.create(type));
    pd.setTitle(title);
    pd.setDetail(detail);
    pd.setInstance(URI.create(request.getRequestURI()));
    pd.setProperty("correlationId", MDC.get(MDC_CORRELATION_ID));
    pd.setProperty("requestId", "req-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
    return pd;
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ProblemDetail> handleValidation(final MethodArgumentNotValidException ex,
                                                        final HttpServletRequest request) {
    log.warn("Validation failed: {}", ex.getMessage());
    final ProblemDetail pd = problem("about:blank", "Validation Failed", 400,
        "Request validation failed", request);
    return ResponseEntity.badRequest().body(pd);
  }

  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<ProblemDetail> handleConstraintViolation(final ConstraintViolationException ex,
                                                                 final HttpServletRequest request) {
    log.warn("Constraint violation: {}", ex.getMessage());
    final ProblemDetail pd = problem("about:blank", "Constraint Violation", 400,
        ex.getMessage(), request);
    return ResponseEntity.badRequest().body(pd);
  }

  @ExceptionHandler(AuthenticationException.class)
  public ResponseEntity<ProblemDetail> handleAuthentication(final AuthenticationException ex,
                                                            final HttpServletRequest request) {
    log.warn("Authentication failed: {}", ex.getMessage());
    final ProblemDetail pd = problem("about:blank", "Unauthorized", 401,
        ex.getMessage(), request);
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(pd);
  }

  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ProblemDetail> handleAccessDenied(final AccessDeniedException ex,
                                                          final HttpServletRequest request) {
    log.warn("Access denied: {}", ex.getMessage());
    final ProblemDetail pd = problem("about:blank", "Forbidden", 403,
        ex.getMessage(), request);
    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(pd);
  }

  @ExceptionHandler(PlanFeatureNotAvailableException.class)
  public ResponseEntity<ProblemDetail> handlePlanFeatureNotAvailable(final PlanFeatureNotAvailableException ex,
                                                                     final HttpServletRequest request) {
    log.warn("Plan feature not available: featureCode={}, planMessage={}", ex.getFeatureCode(), ex.getMessage());
    final ProblemDetail pd = problem("about:blank", "Plan upgrade required", 403,
        ex.getMessage(), request);
    pd.setProperty("featureCode", ex.getFeatureCode());
    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(pd);
  }

  @ExceptionHandler(BillingServiceException.class)
  public ResponseEntity<ProblemDetail> handleBillingServiceError(final BillingServiceException ex,
                                                                 final HttpServletRequest request) {
    log.error("Billing service error: status={}, detail={}", ex.getHttpStatus(), ex.getDetail());
    final ProblemDetail pd = problem("about:blank", "Upstream Service Error", 502,
        ex.getDetail(), request);
    pd.setProperty("upstreamService", "foundation-billing-service");
    pd.setProperty("upstreamStatus", ex.getHttpStatus().value());
    ex.getProperties().forEach(pd::setProperty);
    return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(pd);
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<ProblemDetail> handleMethodNotSupported(final HttpRequestMethodNotSupportedException ex,
                                                                final HttpServletRequest request) {
    log.warn("Method not supported: {}", ex.getMessage());
    final ProblemDetail pd = problem("about:blank", "Method Not Allowed", 405,
        ex.getMessage(), request);
    return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(pd);
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  public ResponseEntity<ProblemDetail> handleMediaTypeNotSupported(final HttpMediaTypeNotSupportedException ex,
                                                                   final HttpServletRequest request) {
    log.warn("Media type not supported: {}", ex.getMessage());
    final ProblemDetail pd = problem("about:blank", "Unsupported Media Type", 415,
        ex.getMessage(), request);
    return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(pd);
  }

  @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
  public ResponseEntity<ProblemDetail> handleMediaTypeNotAcceptable(final HttpMediaTypeNotAcceptableException ex,
                                                                    final HttpServletRequest request) {
    log.warn("Media type not acceptable: {}", ex.getMessage());
    final ProblemDetail pd = problem("about:blank", "Not Acceptable", 406,
        ex.getMessage(), request);
    return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).body(pd);
  }

  @ExceptionHandler(MissingServletRequestParameterException.class)
  public ResponseEntity<ProblemDetail> handleMissingParameter(final MissingServletRequestParameterException ex,
                                                              final HttpServletRequest request) {
    log.warn("Missing parameter: {}", ex.getMessage());
    final ProblemDetail pd = problem("about:blank", "Bad Request", 400,
        ex.getMessage(), request);
    return ResponseEntity.badRequest().body(pd);
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<ProblemDetail> handleTypeMismatch(final MethodArgumentTypeMismatchException ex,
                                                          final HttpServletRequest request) {
    log.warn("Type mismatch: {}", ex.getMessage());
    final ProblemDetail pd = problem("about:blank", "Bad Request", 400,
        ex.getMessage(), request);
    return ResponseEntity.badRequest().body(pd);
  }

  @ExceptionHandler(NoHandlerFoundException.class)
  public ResponseEntity<ProblemDetail> handleNoHandlerFound(final NoHandlerFoundException ex,
                                                            final HttpServletRequest request) {
    log.warn("No handler found: {}", ex.getMessage());
    final ProblemDetail pd = problem("about:blank", "Not Found", 404,
        "No resource found at " + request.getRequestURI(), request);
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(pd);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ProblemDetail> handleGeneral(final Exception ex,
                                                     final HttpServletRequest request) {
    log.error("Unhandled exception: {}", ex.getMessage(), ex);
    final ProblemDetail pd = problem("about:blank", "Internal Server Error", 500,
        "An unexpected error occurred", request);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(pd);
  }
}

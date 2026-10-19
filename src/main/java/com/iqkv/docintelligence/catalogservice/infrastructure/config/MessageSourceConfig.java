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

import java.util.Locale;

import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;

/**
 * i18n configuration.
 *
 * <p>Locale is resolved from the {@code Accept-Language} HTTP header on every request.
 * Falls back to {@link Locale#ENGLISH} when the header is absent or the requested locale
 * has no matching message bundle.
 */
@Configuration
public class MessageSourceConfig implements WebMvcConfigurer {

  @Bean
  public MessageSource messageSource() {
    final var source = new ReloadableResourceBundleMessageSource();
    source.setBasenames("classpath:i18n/messages", "classpath:i18n/ValidationMessages");
    source.setDefaultEncoding("UTF-8");
    source.setCacheSeconds(3600);
    source.setFallbackToSystemLocale(false);
    return source;
  }

  /**
   * Wires Jakarta Bean Validation to use the same message bundle under {@code i18n/}.
   * This replaces the default classpath-root lookup for {@code ValidationMessages.properties}.
   */
  @Bean
  public LocalValidatorFactoryBean validator(final MessageSource messageSource) {
    final var factory = new LocalValidatorFactoryBean();
    factory.setValidationMessageSource(messageSource);
    return factory;
  }

  /**
   * Exposes the validator as the standard {@link jakarta.validation.Validator} bean used by Spring MVC.
   */
  @Override
  public org.springframework.validation.Validator getValidator() {
    return validator(messageSource());
  }

  /**
   * Resolves locale from the {@code Accept-Language} request header.
   * Defaults to English when the header is missing.
   */
  @Bean
  public LocaleResolver localeResolver() {
    final var resolver = new AcceptHeaderLocaleResolver();
    resolver.setDefaultLocale(Locale.ENGLISH);
    return resolver;
  }

  /**
   * Allows switching locale via a {@code lang} query parameter (e.g. {@code ?lang=fr}).
   * This is optional and useful for testing; production traffic should rely on
   * the {@code Accept-Language} header.
   */
  @Bean
  public LocaleChangeInterceptor localeChangeInterceptor() {
    final var interceptor = new LocaleChangeInterceptor();
    interceptor.setParamName("lang");
    return interceptor;
  }

  @Override
  public void addInterceptors(final InterceptorRegistry registry) {
    registry.addInterceptor(localeChangeInterceptor());
  }
}

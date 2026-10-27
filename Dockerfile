# Multi-stage build for Catalog Processing Worker with optimized caching
FROM eclipse-temurin:25-jdk-alpine AS builder

# Install Maven for build optimization
RUN apk add --no-cache maven

# Set working directory
WORKDIR /app

# Copy Maven configuration for dependency caching
COPY pom.xml .

# Download dependencies in separate layer for better caching
RUN mvn dependency:go-offline -B

# Copy source code
COPY src src

# Build the application and extract layers (Spring Boot 4.x jarmode)
# Rename to application.jar first so the extracted application/ layer contains application.jar
RUN mvn clean package -DskipTests -B && \
    cp target/$(ls target/*.jar | grep -v plain | xargs -n1 basename) target/application.jar && \
    java -Djarmode=tools -jar target/application.jar extract --layers --destination target/extracted

# Production runtime stage with security hardening
FROM eclipse-temurin:25-jre-alpine

# Injected by CI as --build-arg (commit SHA / branch-or-tag).
ARG VCS_REFERENCE
ARG BUILD_VERSION_REFERENCE
ENV APPLICATION_VCS_REFERENCE=${VCS_REFERENCE}
ENV APPLICATION_BUILD_VERSION=${BUILD_VERSION_REFERENCE}

# Security: Install only essential packages
RUN apk add --no-cache curl tzdata && \
    rm -rf /var/cache/apk/* && \
    addgroup -g 1001 -S appuser && \
    adduser -u 1001 -S appuser -G appuser -s /bin/false -h /app

WORKDIR /app
RUN mkdir -p /app/logs /app/tmp && \
    chown -R appuser:appuser /app

# Copy application layers for optimal caching
COPY --from=builder --chown=appuser:appuser /app/target/extracted/dependencies/ ./
COPY --from=builder --chown=appuser:appuser /app/target/extracted/spring-boot-loader/ ./
COPY --from=builder --chown=appuser:appuser /app/target/extracted/snapshot-dependencies/ ./
COPY --from=builder --chown=appuser:appuser /app/target/extracted/application/ ./

USER appuser

EXPOSE 8080
EXPOSE 8081

HEALTHCHECK --interval=30s --timeout=10s --start-period=90s --retries=3 \
    CMD curl -f http://localhost:8081/actuator/health/readiness || exit 1

ENV JAVA_OPTS="-XX:+UseContainerSupport \
    -XX:MaxRAMPercentage=75.0 \
    -XX:+UseG1GC \
    -XX:+UseStringDeduplication \
    -XX:+OptimizeStringConcat \
    -Djava.security.egd=file:/dev/./urandom \
    -Dspring.backgroundpreinitializer.ignore=true \
    -Dlogging.config=classpath:logback-spring.xml"

ENV JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=/app/tmp"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS $JAVA_TOOL_OPTIONS -jar application.jar"]

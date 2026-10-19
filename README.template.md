# Document Intelligence Catalog Service 📇

> **Template skeleton.** Replace this content when you create your repository from the template.
> Repository development details live in [`README.md`](./README.md).

A multi-tenant, JWT-secured microservice on the iQ Key Value platform, backed by PostgreSQL, RabbitMQ, and Ollama.

## About

This service provides:

- **Ollama connectivity** — LLM client pre-wired to a local Ollama instance via `spring.ai.ollama.*` (base URL and model configurable per environment)
- **Multi-tenant isolation** — tenant context resolved from the `X-Tenant-ID` header or the JWT `tenant_id` claim before every request
- **Persistence** — data stored in PostgreSQL via MyBatis; Liquibase manages system and per-tenant schema migrations
- **Security** — stateless JWT resource server (RS256); public endpoints can be exposed while admin endpoints require `PLATFORM_ADMIN` authority
- **Messaging** — RabbitMQ consumer for IAM tenant provisioning events (`tenant.created` / `tenant.updated`) on `docintelligence.catalog.tenant.provisioning`, gated by `iqkv.messaging.rabbitmq.enabled`

## Quick Links

- [API Documentation](./docs/api/README.md)
- [Architecture Overview](./docs/architecture/README.md)
- [Deployment Guide](./docs/deployment/README.md)
- [Contributing Guidelines](.github/CONTRIBUTING.md)

## API

Base path: `/api/v1/catalog`

| Method | Path           | Auth                 | Description                                                           |
| :----- | :------------- | :------------------- | :-------------------------------------------------------------------- |
| `GET`  | `/ping`        | public               | Unauthenticated reachability probe — no JWT or tenant header required |
| `GET`  | `/tenant/ping` | JWT + `X-Tenant-ID`  | Tenant-scoped probe — echoes the resolved tenant key                  |
| `GET`  | `/admin/ping`  | JWT `PLATFORM_ADMIN` | Admin probe — echoes the caller's `user_id` claim                     |

> Auth legend: `public` = no token required; `JWT` = valid Bearer token required; `JWT PLATFORM_ADMIN` = platform administrator authority required.

## Tech Stack

- Java 25 / Spring Boot (latest via parent POM)
- Spring AI Ollama starter (local LLM connectivity)
- MyBatis 3.x (no JPA) + PostgreSQL 17
- Liquibase for schema migrations
- RabbitMQ for async messaging
- Spring Security + OAuth2 Resource Server (RS256 JWT)
- Micrometer + Prometheus

## Observability

- **Actuator endpoints** — `health` (liveness + readiness probes), `info`, `metrics`, and `prometheus` exposed on the management port (`8081`)
- **Structured logging** — JSON log output via the Logstash encoder; every request carries a correlation ID set by `CorrelationIdFilter`
- **Metrics** — no service-specific custom meters yet; the Micrometer/Prometheus registry exposes the standard JVM, HTTP, and Spring Boot meters

## Prerequisites

- JDK 25 (Eclipse Temurin)
- Maven 3.9+
- Node.js >= 22.15.0 & pnpm >= 10.33.2 (git hooks)
- Docker & Docker Compose
- [Ollama](https://ollama.com) running locally with at least one model pulled (e.g. `ollama pull llama3.2`)

## Quick Start

```bash
# Clone the repository
git clone https://github.com/IQKV/docintelligence-catalog-service.git
cd docintelligence-catalog-service

# Install git hooks
pnpm install

# Pull a model into Ollama (one-time)
ollama pull llama3.2

# Copy environment variables
cp .env.example .env.local
# Defaults point to Ollama on localhost:11434 — no edits needed for first-run

# Start infrastructure dependencies (PostgreSQL, RabbitMQ, MailHog)
docker compose up -d

# Run the service
./mvnw spring-boot:run -Pdev
# → API:      http://localhost:8080
# → Actuator: http://localhost:8081/actuator/health
# → Swagger:  http://localhost:8080/swagger-ui.html
```

## Environment Variables

| Variable                              | Default                  | Description                                   |
| :------------------------------------ | :----------------------- | :-------------------------------------------- |
| `DB_HOST`                             | `localhost`              | PostgreSQL host                               |
| `DB_PORT`                             | `5432`                   | PostgreSQL port                               |
| `DB_NAME`                             | `catalogservice`         | Database name                                 |
| `DB_USERNAME`                         | `svc_catalog_dba`        | Database user                                 |
| `DB_PASSWORD`                         | `svc_catalog_dba`        | Database password                             |
| `RABBITMQ_HOST`                       | `localhost`              | RabbitMQ host                                 |
| `RABBITMQ_PORT`                       | `5672`                   | RabbitMQ AMQP port                            |
| `RABBITMQ_USERNAME`                   | `svc_catalog_rmq`        | RabbitMQ user                                 |
| `RABBITMQ_PASSWORD`                   | `svc_catalog_rmq`        | RabbitMQ password                             |
| `MAIL_HOST`                           | `localhost`              | SMTP host                                     |
| `MAIL_PORT`                           | `1025`                   | SMTP port (MailHog default)                   |
| `MAIL_FROM`                           | `noreply@iqkv.dev`       | Sender address                                |
| `SPRING_PROFILES_ACTIVE`              | `local`                  | Active Spring profile                         |
| `SPRING_AI_OLLAMA_BASE_URL`           | `http://localhost:11434` | Ollama server URL                             |
| `SPRING_AI_OLLAMA_CHAT_OPTIONS_MODEL` | `llama3.2`               | Model to use (must be pulled in Ollama first) |
| `ROLLOUT_MODE`                        | `MULTI_TENANT`           | Platform rollout mode                         |

> Copy `.env.example` to `.env.local` / `.env.uat` / `.env.prd` and fill in values per environment. The defaults point to a local Ollama instance — no API key needed for local development.

## Maven Commands

```bash
# Build and test (skip Checkstyle during development)
./mvnw clean verify -Dcheckstyle.skip=true

# Run tests only
./mvnw test -Dcheckstyle.skip=true

# Explicit Checkstyle check
./mvnw checkstyle:check

# Coverage report → target/site/jacoco/index.html
./mvnw jacoco:report

# Production build
./mvnw clean package -Pproduction
```

## Docker

```bash
# Build image
docker build -t iqkv/docintelligence-catalog-service:latest .

# Run with full platform stack
docker compose -f compose.container.yaml up -d
```

## Monitoring

| Endpoint                   | Description                      |
| :------------------------- | :------------------------------- |
| `GET /actuator/health`     | Liveness + readiness probes      |
| `GET /actuator/info`       | Build info and platform metadata |
| `GET /actuator/metrics`    | Application metrics              |
| `GET /actuator/prometheus` | Prometheus scrape endpoint       |
| `GET /swagger-ui.html`     | API documentation                |

## Project Structure

```
src/main/java/com/iqkv/docintelligence/catalogservice/
├── ping/             # Example feature module — reachability probes across the three security tiers
├── infrastructure/   # Technical concerns: Spring config, RabbitMQ, Security, MyBatis
├── shared/           # Common exceptions, utilities, value objects
└── CatalogServiceApplication.java
```

## License

This project is licensed under the Apache License. See the [LICENSE](LICENSE) file for details.

## Contributing

Please read our [Contributing Guidelines](.github/CONTRIBUTING.md) and [Code of Conduct](.github/CODE_OF_CONDUCT.md).

---

## 🧩 Architecture

- **Ollama**: LLM connectivity configured via `spring.ai.ollama.*` (`SPRING_AI_OLLAMA_BASE_URL`, `SPRING_AI_OLLAMA_CHAT_OPTIONS_MODEL`)
- **Persistence**: MyBatis with XML mappers + PostgreSQL; Liquibase manages system and per-tenant schema migrations
- **Messaging**: RabbitMQ consumer for IAM tenant provisioning events (`tenant.created` / `tenant.updated`) bound to the shared `iqkv.events` exchange; `iqkv.messaging.rabbitmq.enabled` toggle — disabled in base profile, enabled per environment
- **Security**: Spring Security + OAuth2 Resource Server; RS256 JWT validated via public key or JWKS URI; `@PreAuthorize` on protected endpoints; tenant context resolved before request handling
- **Multi-tenancy**: `ROLLOUT_MODE` (`MULTI_TENANT` | `SINGLE_TENANT`) — must be identical across all platform services
- **Observability**: Micrometer + Prometheus; structured JSON logging with Logstash encoder; health probes for Kubernetes readiness/liveness
- **Quality Tools**: Checkstyle, JaCoCo (60% gate), ArchUnit, commit convention enforcement

> See [AGENTS.md](AGENTS.md) for repository structure, DDD patterns, and agent guidelines.

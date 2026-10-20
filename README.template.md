# Document Intelligence Catalog Processing Worker 📇

> **Template skeleton.** Replace this content when you create your repository from the template.
> Repository development details live in [`README.md`](./README.md).

A microservice on the iQ Key Value platform, backed by PostgreSQL, RabbitMQ, and Ollama.

## About

This service provides:

- **Ollama connectivity** — LLM client pre-wired to a local Ollama instance via `spring.ai.ollama.*` (base URL and model configurable per environment)
- **Persistence** — data stored in PostgreSQL via MyBatis
- **Messaging** — RabbitMQ exchange, queue and dead-letter wiring bound to the shared `iqkv.events` exchange, gated by `iqkv.messaging.rabbitmq.enabled`

## Quick Links

- [Architecture Overview](./docs/architecture/README.md)
- [Deployment Guide](./docs/deployment/README.md)
- [Contributing Guidelines](.github/CONTRIBUTING.md)

## Tech Stack

- Java 25 / Spring Boot (latest via parent POM)
- Spring AI Ollama starter (local LLM connectivity)
- MyBatis 3.x (no JPA) + PostgreSQL 17
- RabbitMQ for async messaging
- Micrometer + Prometheus
- Spring Web — Actuator endpoints only

## Observability

- **Actuator endpoints** — `health` (liveness + readiness probes), `info`, `metrics`, and `prometheus` exposed on the management port (`8081`)
- **Structured logging** — JSON log output via the Logstash encoder
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
git clone https://github.com/IQKV/docintelligence-catalog-processing-worker.git
cd docintelligence-catalog-processing-worker

# Install git hooks
pnpm install

# Pull a model into Ollama (one-time)
ollama pull llama3.2

# Copy environment variables
cp .env.example .env.local
# Defaults point to Ollama on localhost:11434 — no edits needed for first-run

# Start infrastructure dependencies (PostgreSQL, RabbitMQ)
docker compose up -d

# Run the service
./mvnw spring-boot:run -Pdev
# → Actuator: http://localhost:8081/actuator/health
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
| `SPRING_PROFILES_ACTIVE`              | `local`                  | Active Spring profile                         |
| `SPRING_AI_OLLAMA_BASE_URL`           | `http://localhost:11434` | Ollama server URL                             |
| `SPRING_AI_OLLAMA_CHAT_OPTIONS_MODEL` | `llama3.2`               | Model to use (must be pulled in Ollama first) |

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
docker build -t iqkv/docintelligence-catalog-processing-worker:latest .

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

## Project Structure

```
src/main/java/com/iqkv/docintelligence/catalogprocessingworker/
├── infrastructure/   # Technical concerns: Spring config, RabbitMQ, MyBatis
├── shared/           # Common utilities, value objects
└── CatalogProcessingWorkerApplication.java
```

## License

This project is licensed under the Apache License. See the [LICENSE](LICENSE) file for details.

## Contributing

Please read our [Contributing Guidelines](.github/CONTRIBUTING.md) and [Code of Conduct](.github/CODE_OF_CONDUCT.md).

---

## 🧩 Architecture

- **Ollama**: LLM connectivity configured via `spring.ai.ollama.*` (`SPRING_AI_OLLAMA_BASE_URL`, `SPRING_AI_OLLAMA_CHAT_OPTIONS_MODEL`)
- **Persistence**: MyBatis with XML mappers + PostgreSQL
- **Messaging**: RabbitMQ exchange, queue and dead-letter wiring bound to the shared `iqkv.events` exchange; `iqkv.messaging.rabbitmq.enabled` toggle — disabled in base profile, enabled per environment
- **Web**: Spring Web is present only to serve Actuator HTTP endpoints (management port 8081)
- **Observability**: Micrometer + Prometheus; structured JSON logging with Logstash encoder; health probes for Kubernetes readiness/liveness
- **Quality Tools**: Checkstyle, JaCoCo (60% gate), ArchUnit, commit convention enforcement

> See [AGENTS.md](AGENTS.md) for repository structure, DDD patterns, and agent guidelines.

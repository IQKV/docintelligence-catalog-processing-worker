# Document Intelligence Catalog Service 🚀

GitHub template for the iQ Key Value platform. Extends the standard `foundation-microservice-project-layout` with a service backed by PostgreSQL, RabbitMQ, and Ollama.

## About

This template provides:

- **Maven single-module layout** — standard `src/main/java` structure with MyBatis, PostgreSQL, and RabbitMQ wired up
- **Ollama connectivity** — LLM client wired to a local Ollama instance via `spring.ai.ollama.*` (base URL and model configurable per environment)
- **Multi-profile Spring config** — `local`, `sit`, `uat`, `prd` profiles with correct defaults per environment
- **Docker Compose** — `compose.base.yaml` with PostgreSQL, RabbitMQ, DbGate, Ollama, SonarQube, Prometheus, and Grafana; `compose.container.yaml` for full runtime stack including the service container
- **Dockerfile** — multi-stage build with layered JAR extraction, non-root `appuser`, and JVM tuning
- **Database Initialization** — `docker/postgres/init-catalog.sql` for automated schema and extension setup
- **Messaging** — RabbitMQ exchange, queue and dead-letter wiring pre-configured on `iqkv.events`
- **Observability** — Micrometer + Prometheus + structured JSON logging (Logstash encoder); Actuator HTTP endpoints (the only use of Spring Web)
- **Quality tools** — Checkstyle, JaCoCo, ArchUnit, Husky git hooks, commitlint

## Template Usage

1. Click **[Use this template](https://github.com/IQKV/docintelligence-catalog-processing-worker/generate)** to create your repository
2. Replace all `catalog` / `CatalogService` tokens with your service name (see the token map in `foundation-microservice-project-layout`)
3. Update `pom.xml` — `artifactId`, `name`, `description`, `start-class`
4. Rename the Java package from `com.iqkv.docintelligence.catalogprocessingworker` to your package
5. Add your own feature modules under the root package

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

## Prerequisites

- JDK 25 (Eclipse Temurin)
- Maven 3.9+
- Node.js >= 22.15.0 & pnpm >= 10.33.2 (git hooks)
- Docker & Docker Compose
- [Ollama](https://ollama.com) running locally with at least one model pulled (e.g. `ollama pull llama3.2`)

## Quick Start

```bash
# Clone or use as template
git clone https://github.com/IQKV/docintelligence-catalog-processing-worker.git my-service
cd my-service

# Install git hooks
pnpm install

# Pull a model into Ollama (one-time)
ollama pull llama3.2

# Copy environment variables
cp .env.example .env.local
# Defaults point to Ollama on localhost:11434 — no API key needed for local dev

# Start dependencies (PostgreSQL, RabbitMQ, etc.)
docker compose up -d

# Run the service
./mvnw spring-boot:run -Pdev
# → Actuator: http://localhost:8081/actuator/health
```

## Environment Variables

| Variable                              | Default                  | Description                           |
| ------------------------------------- | ------------------------ | ------------------------------------- |
| `DB_HOST`                             | `localhost`              | PostgreSQL host                       |
| `DB_PORT`                             | `5432`                   | PostgreSQL port                       |
| `DB_NAME`                             | `catalogservice`         | Database name                         |
| `DB_USERNAME`                         | `svc_catalog_dba`        | Database user                         |
| `DB_PASSWORD`                         | `svc_catalog_dba`        | Database password                     |
| `RABBITMQ_HOST`                       | `localhost`              | RabbitMQ host                         |
| `RABBITMQ_PORT`                       | `5672`                   | RabbitMQ AMQP port                    |
| `RABBITMQ_USERNAME`                   | `svc_catalog_rmq`        | RabbitMQ user                         |
| `RABBITMQ_PASSWORD`                   | `svc_catalog_rmq`        | RabbitMQ password                     |
| `SPRING_AI_OLLAMA_BASE_URL`           | `http://localhost:11434` | Ollama server URL                     |
| `SPRING_AI_OLLAMA_CHAT_OPTIONS_MODEL` | `llama3.2`               | Model name (must be pulled in Ollama) |

> Copy `.env.example` to `.env.local` / `.env.uat` / `.env.prd` and fill in values per environment.

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

# Run full stack (service + dependencies)
docker compose -f compose.container.yaml up -d
```

## Monitoring

| Endpoint                   | Description                 |
| -------------------------- | --------------------------- |
| `GET /actuator/health`     | Liveness + readiness probes |
| `GET /actuator/metrics`    | Application metrics         |
| `GET /actuator/prometheus` | Prometheus scrape endpoint  |

## Project Structure

```
.
├── docker/
│   ├── postgres/       # DB initialization scripts (init-catalog.sql)
│   ├── prometheus/     # Prometheus configuration
│   └── grafana/        # Grafana dashboards and datasources
├── src/main/java/com/iqkv/docintelligence/catalogprocessingworker/
│   ├── infrastructure/ # Spring config, MyBatis, RabbitMQ setup
│   └── shared/         # Common utilities, value objects
```

## License

This project is licensed under the Apache License. See the [LICENSE](LICENSE) file for details.

## Branch Strategy

> **Template note:** The strategy below is the default shipped with this template. Replace it with whatever model fits your team — update branch names, CI triggers, and protection rules to match.

This template uses **scaled trunk-based development**:

- `dev` is the trunk — always stable and deployable
- Short-lived feature/bugfix branches merge into `dev` via pull request
- Releases are cut from `dev` as annotated tags (`v1.2.0`) — no separate release branch

```
dev  ← trunk
├── feature/*
├── bugfix/*
├── improvement/*
├── hotfix/*
└── rfc/*
```

## Contributing

Please read our [Contributing Guidelines](.github/CONTRIBUTING.md) and [Code of Conduct](.github/CODE_OF_CONDUCT.md).

---

## 🧩 Architecture

- **Ollama**: LLM connectivity configured via `spring.ai.ollama.*` (`SPRING_AI_OLLAMA_BASE_URL`, `SPRING_AI_OLLAMA_CHAT_OPTIONS_MODEL`)
- **Persistence**: MyBatis with XML mappers + PostgreSQL
- **Messaging**: RabbitMQ exchange, queue and dead-letter wiring bound to the shared `iqkv.events` exchange; `iqkv.messaging.rabbitmq.enabled` toggle — disabled in base, enabled per profile
- **Web**: Spring Web is present only to serve Actuator HTTP endpoints (management port 8081)
- **Credential convention**: DB users follow `svc_{service}_dba`, RabbitMQ users follow `svc_{service}_rmq`
- **Observability**: Micrometer + Prometheus; structured JSON logging with Logstash encoder; health probes for Kubernetes
- **GitHub Integration**: Issue templates, labels, Dependabot, and CI workflows
- **Quality Tools**: Checkstyle, JaCoCo (60% gate), ArchUnit, commit convention enforcement

> See [AGENTS.md](AGENTS.md) for repository structure, DDD patterns, and agent guidelines.

# Document Intelligence Catalog Processing Worker 🚀

GitHub template for the iQ Key Value platform. Extends the standard `foundation-microservice-project-layout` with an event-driven companion worker backed by PostgreSQL, RabbitMQ, and Ollama.

## Service Role

This is the asynchronous half of the Document Intelligence catalog pair — the **companion worker** (asynchronous sibling service) to `docintelligence-catalog-service`, not a sidecar. It exposes no inbound HTTP API — every trigger arrives as a message.

1. **Consume** — `docintelligence-catalog-service` publishes a `venue.asset.uploaded` event when an asset upload is confirmed; this worker subscribes to it on `docintelligence.catalog.processing.venue-asset`.
2. **Process** — the asset is downloaded from the shared MinIO bucket and run through a content-type extraction strategy, with Spring AI against Ollama classifying the extracted text.
3. **Feed back** — the derived venue, its embedding and any extracted text are written to the shared catalog tables, and a push notification is published upstream so the uploader sees the result.

### Shared Infrastructure

The worker deliberately shares the PostgreSQL schema and tables and the RabbitMQ broker with `docintelligence-catalog-service`. This is a conscious exception to the platform's self-contained-service rule, traded for simplicity and per-message performance inside a single product boundary. The catalog service owns the schema migrations; this worker only reads and writes the shared tables.

What the two share is data, not a host: they run as separate deployables with independent lifecycles and scale on different axes (API traffic for the catalog, queue depth for the worker). That is what makes this a **companion service** rather than a sidecar.

## Venue Asset Processing

`VenueAssetUploadedListener` consumes `venue.asset.uploaded` from `docintelligence.catalog.processing.venue-asset` on the shared `iqkv.events` topic exchange. It reads only `tenantKey` and `assetId` — `VenueAssetProcessingService` re-reads the asset from the database, which is the source of truth.

`VenueAssetProcessingService` orchestrates each message:

1. Sets the tenant context so `MyBatisSchemaInterceptor` routes mapper calls to `t_{tenantKey}`.
2. Loads the asset; skips when it is missing or already linked to a venue.
3. Transitions the asset status `UPLOADED → PARSING`.
4. Selects a `VenueExtractionStrategy` by content type and derives the venue.
5. Inserts the venue, links the asset, and sets the status to `EXTRACTED`.
6. When the strategy extracted text, inserts a linked `VenueTextContent` row and embeds it.
7. Embeds the venue via `VenueEmbeddingService`.
8. Publishes a `notification.iam.push` event to the uploader (`VENUE_IMPORT_EXTRACTED`, or `VENUE_IMPORT_FAILED` with the reason).

Any failure marks the asset `FAILED` with the exception message, publishes a failure notification, and rethrows as `IllegalStateException` so the listener's bounded retry applies and a poison message is dead-lettered instead of looping.

### Extraction strategies

`VenueExtractionStrategyRouter` selects the first strategy whose `supports()` matches the asset's MIME type; `NoInferenceStrategy` is registered last and always matches, so selection never fails.

| Content types                                                                      | Strategy                        | Behaviour                                                                                       |
| :--------------------------------------------------------------------------------- | :------------------------------ | :---------------------------------------------------------------------------------------------- |
| `application/pdf`, `application/msword`, `…wordprocessingml.document`, `text/plain` | `RichDocumentExtractionStrategy` | Download from MinIO, extract prose with Apache Tika, run the full `DocumentClassifier` pipeline |
| `text/csv`, `application/csv`, `application/vnd.ms-excel`, `…spreadsheetml.sheet`  | `TabularDataExtractionStrategy`  | Tika text plus a spreadsheet structural hint prepended to the model message                     |
| images and any other type                                                          | `NoInferenceStrategy`            | No download, no Tika, no LLM — the venue is named from the filename with confidence `0.1`       |

`DocumentClassifier` sends the first 8 000 characters of extracted text (truncated at a word boundary) to Ollama and maps the JSON reply onto `VenueExtraction`, returning `null` on blank input or model failure so the caller can fall back to heuristics. Name resolution is LLM name → first non-blank text line → filename without extension; confidence is clamped to `[0.0, 1.0]` (default `0.3`). `VenueExtractionContext` builds the metadata document with the same `{"group","field","value"}` item schema the master-venue catalog uses.

Embedding failures are logged and swallowed, so the venue is still written and simply left with a `NULL` embedding for the scheduled backfill to recover.

## About

This template provides:

- **Maven single-module layout** — standard `src/main/java` structure with MyBatis, PostgreSQL, and RabbitMQ wired up
- **Venue asset processing pipeline** — consumes `venue.asset.uploaded` events and derives a tenant venue from the uploaded document: download, text extraction, LLM classification, persistence, embedding, text-content capture, and an uploader notification
- **Content-type extraction strategies** — routed by MIME type: PDF/Word/plain text and CSV/XLS/XLSX go through Apache Tika plus `DocumentClassifier`, while images and unknown types use a no-inference fallback that names the venue from the filename
- **Ollama connectivity** — LLM client wired to a local Ollama instance via `spring.ai.ollama.*` (base URL and models configurable per environment), used for both chat classification and embeddings
- **Scheduled embedding backfill** — ShedLock-guarded jobs embed tenant venues and venue text-content rows whose `embedding` column is still `NULL`, iterating every `t_*` schema against the pgvector column
- **Multi-profile Spring config** — `local`, `sit`, `uat`, `prd` profiles with correct defaults per environment
- **Docker Compose** — `compose.base.yaml` with PostgreSQL, RabbitMQ, DbGate, Ollama, SonarQube, Prometheus, and Grafana; `compose.container.yaml` for full runtime stack including the service container
- **Dockerfile** — multi-stage build with layered JAR extraction, non-root `appuser`, and JVM tuning
- **Database Initialization** — `docker/postgres/init-catalog.sql` for automated schema and extension setup
- **Messaging** — RabbitMQ consumer with a bounded listener retry and a dead-letter queue, gated by `iqkv.messaging.rabbitmq.enabled`
- **Object storage** — MinIO (S3-compatible) client bound to the shared private `catalog-assets` bucket; the worker downloads the objects the catalog service presigns
- **Observability** — Micrometer + Prometheus + structured JSON logging (Logstash encoder); Actuator HTTP endpoints (the only use of Spring Web)
- **Quality tools** — Checkstyle, JaCoCo, ArchUnit, Husky git hooks, commitlint

## Quick Links

- [Architecture Overview](./docs/architecture/README.md)
- [Deployment Guide](./docs/deployment/README.md)
- [Contributing Guidelines](.github/CONTRIBUTING.md)

## Tech Stack

- Java 25 / Spring Boot (latest via parent POM)
- Spring AI Ollama starter (chat classification + embeddings)
- Apache Tika for document text extraction
- MyBatis 3.x (no JPA) + PostgreSQL 17
- pgvector for embeddings; ShedLock (JDBC) for single-flight scheduled jobs
- RabbitMQ for async messaging
- Micrometer + Prometheus
- Spring Web — Actuator endpoints only

## Prerequisites

- JDK 25 (Eclipse Temurin)
- Maven 3.9+
- Node.js >= 22.15.0 & pnpm >= 10.33.2 (git hooks)
- Docker & Docker Compose
- PostgreSQL 17 with the `pgvector` extension (provided by the compose stack)
- MinIO / S3-compatible object storage sharing the `catalog-assets` bucket with `docintelligence-catalog-service`
- [Ollama](https://ollama.com) running locally with the chat and embedding models pulled (e.g. `ollama pull llama3.2` and `ollama pull nomic-embed-text`)

## Quick Start

```bash
# Clone or use as template
git clone https://github.com/IQKV/docintelligence-catalog-processing-worker.git my-service
cd my-service

# Install git hooks
pnpm install

# Pull the chat and embedding models into Ollama (one-time)
ollama pull llama3.2
ollama pull nomic-embed-text

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

| Variable                                                         | Default                  | Description                                        |
| :--------------------------------------------------------------- | :----------------------- | :------------------------------------------------- |
| `DB_HOST`                                                        | `localhost`              | PostgreSQL host                                    |
| `DB_PORT`                                                        | `5432`                   | PostgreSQL port                                    |
| `DB_NAME`                                                        | `catalogservice`         | Database name                                      |
| `DB_USERNAME`                                                    | `svc_catalog_dba`        | Database user                                      |
| `DB_PASSWORD`                                                    | `svc_catalog_dba`        | Database password                                  |
| `RABBITMQ_HOST`                                                  | `localhost`              | RabbitMQ host                                      |
| `RABBITMQ_PORT`                                                  | `5672`                   | RabbitMQ AMQP port                                 |
| `RABBITMQ_USERNAME`                                              | `svc_catalog_rmq`        | RabbitMQ user                                      |
| `RABBITMQ_PASSWORD`                                              | `svc_catalog_rmq`        | RabbitMQ password                                  |
| `MESSAGING_ENABLED`                                              | `false`                  | Enables the RabbitMQ consumer and topology         |
| `OBJECTSTORAGE_ENDPOINT`                                         | `http://localhost:9000`  | MinIO / S3 endpoint                                |
| `OBJECTSTORAGE_ACCESS_KEY`                                       | `iqkv`                   | Object storage access key                          |
| `OBJECTSTORAGE_SECRET_KEY`                                       | `iqkv_password`          | Object storage secret key                          |
| `OBJECTSTORAGE_BUCKET_NAME`                                      | `catalog-assets`         | Shared private bucket (must match the catalog)     |
| `SPRING_PROFILES_ACTIVE`                                         | `local`                  | Active Spring profile                              |
| `SPRING_AI_OLLAMA_BASE_URL`                                      | `http://localhost:11434` | Ollama server URL                                  |
| `SPRING_AI_OLLAMA_CHAT_OPTIONS_MODEL`                            | `llama3.2`               | Chat model used for document classification        |
| `SPRING_AI_OLLAMA_EMBEDDING_MODEL`                               | `nomic-embed-text`       | Embedding model (must be pulled in Ollama first)   |
| `IQKV_CATALOG_VENUE_EMBEDDING_BACKFILL_INTERVAL_MS`              | `600000`                 | Delay (ms) between venue embedding backfill passes |
| `IQKV_CATALOG_VENUE_TEXT_CONTENT_EMBEDDING_BACKFILL_INTERVAL_MS` | `600000`                 | Delay (ms) between text-content embedding passes   |

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
│   ├── venue/          # Vertical slice: venue asset processing + venue/text-content embedding backfill
│   ├── infrastructure/ # Spring config, MyBatis, RabbitMQ, ShedLock setup
│   ├── shared/         # Common utilities, value objects
│   └── CatalogProcessingWorkerApplication.java
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

- **Ollama**: chat classification and embedding configured via `spring.ai.ollama.*` (`SPRING_AI_OLLAMA_BASE_URL`, `SPRING_AI_OLLAMA_CHAT_OPTIONS_MODEL`, `SPRING_AI_OLLAMA_EMBEDDING_MODEL`)
- **Persistence**: MyBatis with XML mappers + PostgreSQL; the venue models and mappers come from the shared `com.iqkv:docintelligence-venue` artifact, so this service ships no mapper XML
- **Object storage**: MinIO (S3-compatible) client bound to the shared private `catalog-assets` bucket — the worker downloads the objects the catalog service presigns
- **Asset processing**: `VenueAssetUploadedListener` consumes `venue.asset.uploaded`, `VenueAssetProcessingService` drives the pipeline, and `VenueExtractionStrategyRouter` picks the extraction strategy by MIME type (rich document / tabular / no-inference)
- **Scheduled backfill**: `VenueEmbeddingBackfillService` and `VenueTextContentEmbeddingBackfillService` embed tenant rows whose `embedding` is `NULL` on an interval (`iqkv.catalog.venue-embedding.backfill-interval-ms` / `iqkv.catalog.venue-text-content-embedding.backfill-interval-ms`, default 10 min), iterating every `t_*` schema; ShedLock against the shared `public.shedlock` table keeps each single-flight across replicas
- **Messaging**: RabbitMQ connection, Jackson JSON message converter and bounded listener retry (3 attempts, exponential backoff) with dead-lettering to `docintelligence.catalog.processing.venue-asset.dlq`; `iqkv.messaging.rabbitmq.enabled` toggle — disabled in the base profile, enabled per environment
- **Web**: Spring Web is present only to serve Actuator HTTP endpoints (management port 8081)
- **Credential convention**: DB users follow `svc_{service}_dba`, RabbitMQ users follow `svc_{service}_rmq`
- **Observability**: Micrometer + Prometheus; structured JSON logging with Logstash encoder; health probes for Kubernetes
- **GitHub Integration**: Issue templates, labels, Dependabot, and CI workflows
- **Quality Tools**: Checkstyle, JaCoCo (60% gate), ArchUnit, commit convention enforcement

> See [AGENTS.md](AGENTS.md) for repository structure, DDD patterns, and agent guidelines.

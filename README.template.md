# Document Intelligence Catalog Processing Worker 📇

An event-driven companion worker on the iQ Key Value platform, backed by PostgreSQL, RabbitMQ, and Ollama.

## Service Role

The service is the **companion worker** (asynchronous sibling service) to the service that owns the control-plane API — not a sidecar. It exposes no inbound HTTP API — every trigger arrives as a message or a schedule tick. It consumes work from a queue, processes assets from a shared folder / S3, and publishes feedback events upstream so the producer can make progress. Reads and writes go through MyBatis.

It is expected to share its PostgreSQL schema and RabbitMQ broker with the service that hands it work, rather than owning them outright — a deliberate exception to the platform's self-contained-service rule, traded for simplicity and per-message performance. What the two share is data, not a host: they run as separate deployables, with independent lifecycles and their own scaling axes.

## Venue Asset Processing

A confirmed venue-less asset upload emits a `venue.asset.uploaded` event that this worker consumes from `docintelligence.catalog.processing.venue-asset` on the shared `iqkv.events` topic exchange. `VenueAssetUploadedListener` reads only `tenantKey` and `assetId` — the processing service re-reads the asset from the database, which is the source of truth.

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

| Content types                                                                                          | Strategy                        | Behaviour                                                                                       |
| :----------------------------------------------------------------------------------------------------- | :------------------------------ | :---------------------------------------------------------------------------------------------- |
| `application/pdf`, `application/msword`, `…wordprocessingml.document`, `text/plain`                    | `RichDocumentExtractionStrategy` | Download from MinIO, extract prose with Apache Tika, run the full `DocumentClassifier` pipeline |
| `text/csv`, `application/csv`, `application/vnd.ms-excel`, `…spreadsheetml.sheet`                      | `TabularDataExtractionStrategy`  | Tika text plus a spreadsheet structural hint prepended to the model message                     |
| images and any other type                                                                              | `NoInferenceStrategy`            | No download, no Tika, no LLM — the venue is named from the filename with confidence `0.1`       |

`DocumentClassifier` sends the first 8 000 characters of extracted text (truncated at a word boundary) to Ollama and maps the JSON reply onto `VenueExtraction`, returning `null` on blank input or model failure so the caller can fall back to heuristics. Name resolution is LLM name → first non-blank text line → filename without extension; confidence is clamped to `[0.0, 1.0]` (default `0.3`). `VenueExtractionContext` builds the metadata document with the same `{"group","field","value"}` item schema the master-venue catalog uses.

Embedding failures are logged and swallowed, so the venue is still written and simply left with a `NULL` embedding for the backfill to recover.

## About

This service provides:

- **Venue asset processing pipeline** — consumes `venue.asset.uploaded` events and derives a tenant venue from the uploaded document: download, text extraction, LLM classification, persistence, embedding, text-content capture, and an uploader notification
- **Content-type extraction strategies** — routed by MIME type: PDF/Word/plain text and CSV/XLS/XLSX go through Apache Tika plus `DocumentClassifier`, while images and unknown types use a no-inference fallback that names the venue from the filename
- **Ollama connectivity** — LLM client pre-wired to a local Ollama instance via `spring.ai.ollama.*` (base URL and models configurable per environment)
- **Scheduled embedding backfill** — ShedLock-guarded jobs embed tenant venues and venue text-content rows whose `embedding` column is still `NULL`, iterating every `t_*` schema against the pgvector column
- **Persistence** — data stored in PostgreSQL via MyBatis
- **Object storage** — MinIO (S3-compatible) client bound to the shared private `catalog-assets` bucket; the worker downloads the objects the catalog service presigns
- **Messaging** — RabbitMQ consumer with a bounded listener retry and a dead-letter queue, gated by `iqkv.messaging.rabbitmq.enabled`

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

## Observability

- **Actuator endpoints** — `health` (liveness + readiness probes), `info`, `metrics`, and `prometheus` exposed on the management port (`8081`)
- **Structured logging** — JSON log output via the Logstash encoder
- **Metrics** — no service-specific custom meters yet; the Micrometer/Prometheus registry exposes the standard JVM, HTTP, and Spring Boot meters

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
# Clone the repository
git clone https://github.com/IQKV/docintelligence-catalog-processing-worker.git
cd docintelligence-catalog-processing-worker

# Install git hooks
pnpm install

# Pull the chat and embedding models into Ollama (one-time)
ollama pull llama3.2
ollama pull nomic-embed-text

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

| Variable                                            | Default                  | Description                                        |
| :-------------------------------------------------- | :----------------------- | :------------------------------------------------- |
| `DB_HOST`                                           | `localhost`              | PostgreSQL host                                    |
| `DB_PORT`                                           | `5432`                   | PostgreSQL port                                    |
| `DB_NAME`                                           | `catalogservice`         | Database name                                      |
| `DB_USERNAME`                                       | `svc_catalog_dba`        | Database user                                      |
| `DB_PASSWORD`                                       | `svc_catalog_dba`        | Database password                                  |
| `RABBITMQ_HOST`                                     | `localhost`              | RabbitMQ host                                      |
| `RABBITMQ_PORT`                                     | `5672`                   | RabbitMQ AMQP port                                 |
| `RABBITMQ_USERNAME`                                 | `svc_catalog_rmq`        | RabbitMQ user                                      |
| `RABBITMQ_PASSWORD`                                 | `svc_catalog_rmq`        | RabbitMQ password                                  |
| `MESSAGING_ENABLED`                                 | `false`                  | Enables the RabbitMQ consumer and topology         |
| `OBJECTSTORAGE_ENDPOINT`                            | `http://localhost:9000`  | MinIO / S3 endpoint                                |
| `OBJECTSTORAGE_ACCESS_KEY`                          | `iqkv`                   | Object storage access key                          |
| `OBJECTSTORAGE_SECRET_KEY`                          | `iqkv_password`          | Object storage secret key                          |
| `OBJECTSTORAGE_BUCKET_NAME`                         | `catalog-assets`         | Shared private bucket (must match the catalog)     |
| `SPRING_PROFILES_ACTIVE`                            | `local`                  | Active Spring profile                              |
| `SPRING_AI_OLLAMA_BASE_URL`                         | `http://localhost:11434` | Ollama server URL                                  |
| `SPRING_AI_OLLAMA_CHAT_OPTIONS_MODEL`               | `llama3.2`               | Chat model used for document classification        |
| `SPRING_AI_OLLAMA_EMBEDDING_MODEL`                  | `nomic-embed-text`       | Embedding model (must be pulled in Ollama first)   |
| `IQKV_CATALOG_VENUE_EMBEDDING_BACKFILL_INTERVAL_MS` | `600000`                 | Delay (ms) between venue embedding backfill passes |
| `IQKV_CATALOG_VENUE_TEXT_CONTENT_EMBEDDING_BACKFILL_INTERVAL_MS` | `600000`       | Delay (ms) between text-content embedding passes   |

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
├── venue/            # Vertical slice: venue asset processing + venue/text-content embedding backfill
│   ├── VenueAssetUploadedListener.java               # @RabbitListener for venue.asset.uploaded
│   ├── VenueAssetProcessingService.java              # pipeline orchestrator
│   ├── VenueExtractionStrategy.java                  # strategy contract + result record
│   ├── VenueExtractionStrategyRouter.java            # content-type → strategy selection
│   ├── RichDocumentExtractionStrategy.java           # PDF / Word / plain text
│   ├── TabularDataExtractionStrategy.java            # CSV / XLS / XLSX
│   ├── NoInferenceStrategy.java                      # images + unknown types (catch-all)
│   ├── DocumentClassifier.java                       # LLM field extraction (prompt + parsing)
│   ├── VenueExtractionContext.java                   # download, Tika, name/confidence/metadata helpers
│   ├── VenueExtraction.java                          # LLM extraction result record
│   ├── VenueImportNotificationEvent.java             # notification.iam.push payload
│   ├── VenueEmbeddingService.java                    # Ollama embed + vector write
│   ├── VenueEmbeddingBackfillService.java            # @Scheduled + @SchedulerLock sweep
│   └── VenueTextContentEmbeddingBackfillService.java # @Scheduled + @SchedulerLock sweep
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

- **Ollama**: chat classification and embedding configured via `spring.ai.ollama.*` (`SPRING_AI_OLLAMA_BASE_URL`, `SPRING_AI_OLLAMA_CHAT_OPTIONS_MODEL`, `SPRING_AI_OLLAMA_EMBEDDING_MODEL`)
- **Persistence**: MyBatis with XML mappers + PostgreSQL; the venue models and mappers come from the shared `com.iqkv:docintelligence-venue` artifact, so this service ships no mapper XML
- **Object storage**: MinIO (S3-compatible) client bound to the shared private `catalog-assets` bucket — the worker downloads the objects the catalog service presigns
- **Asset processing**: `VenueAssetUploadedListener` consumes `venue.asset.uploaded`, `VenueAssetProcessingService` drives the pipeline, and `VenueExtractionStrategyRouter` picks the extraction strategy by MIME type (rich document / tabular / no-inference)
- **Scheduled backfill**: `VenueEmbeddingBackfillService` and `VenueTextContentEmbeddingBackfillService` embed tenant rows whose `embedding` is `NULL` on an interval (`iqkv.catalog.venue-embedding.backfill-interval-ms` / `iqkv.catalog.venue-text-content-embedding.backfill-interval-ms`, default 10 min), iterating every `t_*` schema; ShedLock against the shared `public.shedlock` table keeps each single-flight across replicas
- **Messaging**: RabbitMQ connection, Jackson JSON message converter and bounded listener retry (3 attempts, exponential backoff) with dead-lettering to `docintelligence.catalog.processing.venue-asset.dlq`; `iqkv.messaging.rabbitmq.enabled` toggle — disabled in the base profile, enabled per environment
- **Web**: Spring Web is present only to serve Actuator HTTP endpoints (management port 8081)
- **Observability**: Micrometer + Prometheus; structured JSON logging with Logstash encoder; health probes for Kubernetes readiness/liveness
- **Quality Tools**: Checkstyle, JaCoCo (60% gate), ArchUnit, commit convention enforcement

> See [AGENTS.md](AGENTS.md) for repository structure, DDD patterns, and agent guidelines.

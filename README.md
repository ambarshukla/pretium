# pretium

*pretium* (Latin: "price"): a platform that collects what it really costs to send money
abroad, provider by provider, checks every number, and serves it with the evidence
attached.

> **Independent personal project**, built entirely from open data on personal time and
> equipment. Not affiliated with or endorsed by any data publisher or provider named in the
> data. Fetched data is never committed. Sources, licences and attribution:
> [`docs/PROVENANCE.md`](docs/PROVENANCE.md).

## What it does

- **Collects** four real sources on their own schedules: the World Bank's *Remittance
  Prices Worldwide* survey (about 200,000 provider prices across hundreds of corridors
  since 2016) and the ECB's euro reference rates through three channels (an SDMX API, an
  XML file, and the public web page, scraped with CSS selectors).
- **Collects politely**: it names itself in its User-Agent, reads robots.txt first, waits
  between requests, asks conditionally (ETag), and treats a refusal as final.
- **Keeps the evidence**: every response is stored exactly as received, named by its
  SHA-256 hash. Every published number carries that hash and the run that produced it.
- **Checks every price** before publishing it, using identities the numbers must satisfy
  (total cost = fee + FX margin; FX margin = the gap from the interbank rate), with
  tolerances derived from the precision the source actually publishes.
- **Reconciles** the three ECB channels to the last digit, and keeps every value a source
  later revises.
- **Serves** a comparison API and a web front end: cheapest provider first, the checks each
  price passed, and its proof.

## Architecture

```
 sources ──▶ collector ──Kafka──▶ catalog ──▶ Postgres ──▶ comparison API ──▶ web (Next.js)
 (HTTP)      robots, ETag          checks,                  /api/v1/...
             raw evidence (sha256)  reconciliation,
             extractors (recipes)   revisions
```

| Module | What it is |
|---|---|
| `events` | The messages that cross Kafka (Java records), shared by producer and consumer |
| `collector` | Spring Boot service: source specs, polite fetcher, raw store, extractors, scheduler |
| `catalog` | Spring Boot service: Kafka consumers, quality checks, reconciliation, Postgres, REST API |
| `web` | Next.js (React, TypeScript) front end: comparison page and operations page |
| `deploy` | Docker Compose for a single small host |

Adding a source of an existing format is a YAML file in
[`collector/src/main/resources/sources/`](collector/src/main/resources/sources), not code.

## Stack

Java 21, Spring Boot 4.1, Apache Kafka 4.1 (KRaft), PostgreSQL 17 with Flyway, jsoup,
fastexcel, Next.js 16 / React 19 / TypeScript, Docker Compose, Gradle 9.7, JUnit 5,
Testcontainers, GitHub Actions.

## Running it locally

Needs JDK 21, Docker and Node 24.

```bash
docker compose -f infra/docker-compose.yml up -d          # Postgres + Kafka
./gradlew :catalog:bootRun                                 # API on :8082
PRETIUM_COLLECT_ON_STARTUP=true ./gradlew :collector:bootRun   # collects every source once
cd web && npm ci && CATALOG_URL=http://localhost:8082 npm run dev   # site on :3000
```

The first run downloads about 55 MB (the World Bank file and ten years of ECB rates) and
takes a couple of minutes.

## Documentation

- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md): how a number travels from source to page
- [`docs/DECISIONS.md`](docs/DECISIONS.md): the choices and their trade-offs
- [`docs/BUILD_LOG.md`](docs/BUILD_LOG.md): what was built, in order, and what the real data showed
- [`docs/PROVENANCE.md`](docs/PROVENANCE.md): sources, licences, attribution
- [`docs/SLO.md`](docs/SLO.md) and [`docs/RUNBOOK.md`](docs/RUNBOOK.md): what "working" means, and what to do when it is not

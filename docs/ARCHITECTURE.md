# Architecture

How one number travels from a source to the page, and what can go wrong at each step.

```
                 ┌───────────────────────── collector ─────────────────────────┐
 source spec ──▶ │ scheduler ─▶ polite fetcher ─▶ raw store ─▶ extractor ─▶ sink │
 (YAML)          │  (cron)      robots, ETag,     sha256-named   recipe     Kafka │
                 │              gap, refusals     evidence                 producer
                 └──────────────────────────────────────────────────────────────┘
                                         │ pretium.reference-rates.v1
                                         │ pretium.provider-quotes.v1
                                         │ pretium.collection-runs.v1
                 ┌───────────────────────▼────── catalog ──────────────────────┐
                 │ batch listeners ─▶ checks ─▶ Postgres ─▶ reconciler         │
                 │                    (verdict  (upsert,    (channels vs        │
                 │                    per price) revisions)  reference)        │
                 │                                   │                         │
                 │                     REST API: /api/v1/comparisons, /ops/... │
                 └───────────────────────────────────┬─────────────────────────┘
                                                     │ private network
                                              web (Next.js) ──▶ reverse proxy ──▶ reader
```

## 1. Source specs

One YAML file per source (`collector/src/main/resources/sources/`): id, URL, an optional
backfill URL for the first run, the format (which extractor reads it), a cron schedule in
the source's own time zone, the licence, and for scraped pages a versioned **recipe** of
CSS selectors. Specs are validated at startup; a bad one stops the service with the file
name in the message.

## 2. Fetching

`PoliteFetcher` reads robots.txt (RFC 9309 matching, cached for a day), waits at least five
seconds between requests to one host, sends `If-None-Match` with the last ETag, and
classifies the response:

| Outcome | Meaning | What happens |
|---|---|---|
| `OK` | a body arrived | stored and extracted |
| `NOT_MODIFIED` | 304: our copy is current | re-extracted from evidence only if never extracted successfully with this recipe |
| `BLOCKED` | robots.txt, 401/403/451, bot challenge | recorded, never retried or bypassed |
| `FAILED` | timeout, 429, 5xx | recorded; the next scheduled run tries again |

## 3. Evidence

`RawStore` hashes the body, stores it as `<source>/<sha256>.<ext>`, and appends a line to
the source's `index.jsonl`. The hash travels with every observation to the database and
the API, so any number on the page leads back to one file.

## 4. Extraction

One extractor per format. All stream into an `ObservationSink` rather than building a list,
because the World Bank sheet is about 300 MB of XML once unzipped. Columns are found by
header name; selectors come from the recipe. An `ExtractionException` names the column or
selector that stopped matching, and the run is reported as `EXTRACTION_FAILED`.

## 5. Kafka

Three topics, one per message type, versioned in the name. Observations are keyed by their
natural key (source, record, date) so every version of one observation lands on one
partition, in order. The producer uses `acks=all` with idempotence; the run summary is sent
after the observations it describes. The broker has automatic topic creation switched off.

## 6. Checks and storage

The catalog consumes in batches and writes each batch in one transaction, committing
offsets afterwards: a crash re-delivers the batch, and upserts make that harmless. Each
price is judged by `QuoteChecks` (see [DECISIONS](DECISIONS.md) D-007 to D-009) and stored
with its verdict and findings. Postgres triggers write a `value_revision` row whenever a
source changes a value it gave before.

## 7. Reconciliation

After each batch of rates, `Reconciler` compares every channel with the reference channel
for the affected dates and records `VALUE_MISMATCH` and `MISSING` breaks, closing them
when the channels agree again.

## 8. Serving

The REST API serves corridors, comparisons (quarantined prices excluded), corridor history,
cross rates, and operations views (source freshness against a per-source target, checks by
rule, quarantine, breaks, revisions, runs). The Next.js front end renders on the server and
reaches the catalog over the private network only.

## Data model

| Table | Key | Holds |
|---|---|---|
| `collection_run` | run id | every attempt, with outcome, HTTP status, bytes, hash, recipe version |
| `reference_rate` | channel, base, quote, date | rates with run id and evidence hash |
| `provider_quote` | source, record, amount | prices with verdict, findings (JSON), run id, evidence hash |
| `value_revision` | serial | old and new value whenever a stored value changes |
| `reconciliation_break` | pair, date, channel, reference, kind | disagreements, with detected and resolved times |

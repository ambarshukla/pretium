# Service level objectives

What "working" means for pretium, measured from data it already records. Written before
there is much traffic to measure, so each target states the reasoning that set it.

## Who depends on what

| Consumer | Needs | Worst failure |
|---|---|---|
| A reader of the comparison page | the right price, recent enough to act on | a **wrong** price shown as fact |
| An analyst using corridor history | complete, consistent history | a silent gap or a silent revision |
| The operations page | an honest view of the platform | a healthy-looking page over broken collection |

A wrong price is worse than a stale one, which is worse than a missing one. The objectives
are ordered the same way.

## Objectives

### 1. Accuracy: no wrong price is served

- **Indicator:** prices served by the comparison API that later turn out to break an
  identity check, or that a source later revises by more than 1 percentage point.
- **Objective:** zero known-wrong prices served; every quarantine decision explainable from
  the stored findings.
- **Measured by:** `provider_quote.findings`, `value_revision`.
- **Why zero:** a comparison page is a public claim about named companies. One wrong price
  is an incident, not a budget item.

### 2. Freshness: each source is as recent as its publisher

| Source | Publisher cadence | Target (age of last good run) |
|---|---|---|
| `ecb-sdmx-exr`, `ecb-eurofxref-daily`, `ecb-reference-html` | TARGET business days, ~16:00 CET | 96 h (a weekend plus one holiday) |
| `cnb-fixing-txt`, `cnb-fixing-html` | Czech working days, 14:30 Prague time | 96 h |
| `wb-rpw` | quarterly | 8 days (weekly check) |

- **Indicator:** `now − max(finished_at)` of `SUCCEEDED` or `UNCHANGED` runs per source.
- **Objective:** within target 99% of the time over 30 days.
- **Measured by:** `/api/v1/ops/sources` (`ageHours`, `fresh`), shown on the Operations page.

### 3. Channel agreement: the scraped page matches the structured channels

- **Indicator:** open `reconciliation_break` rows older than one collection cycle.
- **Objective:** zero breaks open longer than 24 hours.
- **Why it matters beyond the ECB:** this is the acceptance test for automated scraper
  repair. If it is noisy, repair cannot rely on it (see DECISIONS D-014).

### 4. Availability of the comparison API

- **Indicator:** share of `/api/v1/comparisons` requests answered 2xx within 1 second.
- **Objective:** 99.5% over 30 days.
- **Why not higher:** one host, one database, deliberately. The data changes at most daily
  and the page caches for five minutes; a few minutes of outage cost little. Raising this
  means a second host, which is a cost decision, not a code change.

## Error budget policy

- **Accuracy has no budget.** A wrong price served → stop the line: quarantine the affected
  prices (or the source), write a postmortem, add the check that would have caught it.
- **Freshness or agreement budget exhausted** → new sources and features pause until the
  cause is fixed and a regression test exists.
- **Availability budget exhausted** → reliability work (restarts, memory, a second host)
  takes priority over features for the next cycle.

## Not yet measured

Availability and latency need request metrics. The services now expose Micrometer metrics
(`/actuator/prometheus`) and `infra/` has a local Prometheus and Grafana, but nothing scrapes
the hosted stack, so objectives 1 to 3 are still checked from the database and the Operations
page. Freshness is already exported per source (`pretium_source_age_seconds` against
`pretium_source_freshness_target_seconds`).

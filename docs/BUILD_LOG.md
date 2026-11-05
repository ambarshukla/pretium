# Build log

What was built, in order, and what the real data showed. Predictions are written before
a run and checked after it.

## 2026-11-04 · First slice: collect, check, serve

**Built.** The `events`, `collector` and `catalog` modules, the Next.js front end, local and
hosted Docker Compose, CI. Four sources: World Bank RPW and three ECB channels.

**Probed before coding.**

- The ECB's three channels all answered, and robots.txt allows them.
- The World Bank survey site answered with a Cloudflare challenge (HTTP 403). Not pursued
  (D-006). The data catalogue serves the same dataset as a plain 50 MB file, with an ETag.
- The workbook holds two data sheets with different layouts; the current one ("from Q2
  2016") has 42 columns, prices at two amounts per row, and Excel doubles such as
  `8.4000000000000005E-2` for a typed 0.084.

**Predicted before the first run.** XML ≈ 30 rates for 2026-11-03; SDMX backfill ≈ 95,000
rates; the scraped page equal to the XML with no breaks; RPW 150,000 to 250,000 services
with fewer than 2% of prices quarantined.

**Observed.**

| | Predicted | Observed |
|---|---|---|
| XML | ≈ 30 | 29 rates, 2026-11-03 |
| Scraped page | equal to XML | 29 rates, identical to the last digit; 0 breaks |
| SDMX backfill | ≈ 95,000 | 90,818 rates, 2016-01-04 to 2026-11-03 |
| RPW services | 150k to 250k | 204,469 services, 407,851 prices (two amounts each) |
| Quarantined | < 2% | 1.0% on the first rules; 0.06% after the two corrections below |

**What the real data taught.**

1. **Negative costs are real.** The first rules quarantined every total cost below zero:
   3,282 prices. Inspection showed first-transfer promotions (for example a zero-fee
   Canada → India service at 58.25 against an interbank 57.73) and parallel-market rates
   (Thailand → Myanmar, 47% better than the official rate). Now published with a
   `better-than-interbank` warning (D-008).
2. **Published precision limits what can be checked.** 7,709 prices failed the margin
   identity, nearly all in corridors with tiny rates (won to dong, yen to peso) that the
   file rounds to one or two significant digits. The tolerance now follows each rate's
   published digits, and 56,357 prices are marked `margin-unverifiable` instead of wrong
   (D-009).
3. **What is left is genuinely wrong.** Of the 230 still quarantined: 36 totals above 100%
   (e.g. a TRY 661 fee on TRY 570 sent: a fee in the wrong currency), 53 totals that
   contradict their own fee and margin, and 142 margins that contradict their rates,
   including a Thailand → India rate of 0.4444 against an interbank 2.2645, which is the
   interbank rate inverted (1 ÷ 2.2645 = 0.4416).
4. **The rule change was applied by replaying Kafka** under a new consumer group (D-010),
   re-judging all 407,851 prices with the new code in about two minutes.

**Final state of the first slice.** 388,906 prices published clean (95.4%), 18,715 with
warnings (4.6%), 230 quarantined (0.06%). All three ECB channels agree on all 29
currencies.

## 2026-11-04 · Hosted

**Built.** The stack runs on one 2-CPU, 4 GB host behind the existing Caddy proxy, from a
`git archive` of one commit, with images built on the host one at a time. Memory at rest
after the full first load: Kafka 358 MB, catalog 268 MB, collector 233 MB, Postgres
109 MB, web 50 MB: about 1 GB for the whole stack.

**Observed.** The hosted first load produced exactly the local counts (407,851 prices:
388,906 / 18,715 / 230). It also produced **14 reconciliation breaks of kind `MISSING`,
all of which resolved themselves within the load**: reconciliation runs per Kafka batch,
and the three partitions of the rates topic deliver one day's currencies at different
moments, so for a moment a channel looks as if it lacks a currency it actually sent.
Harmless, because the breaks close themselves, but it is noise in exactly the signal that
selector repair will rely on. Next step: reconcile once per completed collection run.

## 2026-11-04 · Reconcile settled dates only

**Built.** Each batch of rates now only marks its dates as touched; a scheduled task
reconciles a date once nothing has touched it for 30 seconds (`SettledReconciliation`).

**Verified.** A fresh catalog replaying every topic into an empty database: 90,876 rates,
**0 breaks** (previously 14 transient ones on the same load).

## 2026-11-04 · Selector repair, second round

**Built.** The first evaluation healed 0 of 36 archived pages: the model kept choosing
elements with no date in them and never reached the rows. Finding a date in text is a
pattern match, so a program now does it (`PageDigest.dateHint`: the first heading whose own
text contains "26 January 2017"), and the model proposes only the row, currency and rate
selectors. The prompt also says class names change between versions and to prefer visible
structure.

**Observed.** Same 36 real pages (2017 to 2019), same local model, replayed newest first:

| | before | after |
|---|---|---|
| Pages read correctly | 0 | **35 of 36** (33 by a recipe already known, 2 healed) |
| Model proposals | 72 | 4 |
| Model time | 5,439 s | 299 s |
| Wrong numbers accepted | 0 | 0 |

Three recipes were enough for 2017 to 2019. The one failure (April 2019) is a snapshot that
holds only 13 of 36 currency rows: the archived copy is truncated, and the verifier correctly
refused to accept a partial read. Not yet shown: pages from 2020 onwards, and a model that
proposes a wrong selector (none did, so the "blocked wrong numbers" path is still untested
on real data).

## 2026-11-05 · Selector repair over the full archived history

**Observed.** All 119 archived copies of the rates page (2017 to 2026), replayed newest
first against the same local model, with every read checked against the official data file:

| | |
|---|---|
| Pages read correctly | **118 of 119** (116 by a recipe already known, 2 healed by the model) |
| Needed a person | 1 (the truncated April 2019 snapshot with 13 of 36 rows) |
| Model proposals | 4 (2 accepted), about 74 s each |
| Wrong numbers accepted or published | 0 |

Three recipes cover nine years of the page: the layout changed twice (by December 2020 and again by January 2019), and
the model was needed only at those two points. Since 2021 the page has not changed.
Not yet shown: a model that proposes a wrong selector (none did, so the path that blocks
bad numbers is still untested on real data), and breakage in the future, which can only be
observed live.

## 2026-11-05 · Nothing is published until an extraction has finished

**Problem.** An extractor streams what it reads straight to Kafka. If a page failed on row 80
of 100, rows 1 to 79 had already gone out, so a half-read page looked like a whole one.

**Change.** Observations now wait in a temporary file (a file rather than memory, because the
remittance dataset is several hundred thousand records) and are sent only after the extractor
has read the whole file without error. A failed extraction publishes nothing and the run is
reported as `EXTRACTION_FAILED`. Test: five rows of the real ECB file are read, the extractor
then fails, and the held rows are discarded together with their file.

**Also.** The verifier now has a test with recipes a model could plausibly propose against the
real page (a real date that is not the rates date, half of the rows, names where codes belong).
All are rejected, and none can reach publication.

## 2026-11-05 · Metrics

Both services expose `/actuator/prometheus` (Micrometer). Collector: runs by source and
status, observations extracted, run duration. Catalog: runs, rates and quotes stored,
quarantined prices, and per source the age of the last good collection against its freshness
target (refreshed once a minute, so a dashboard cannot load the database). `infra/` has
Prometheus and Grafana behind a compose profile (`docker compose --profile metrics up -d`)
with a provisioned dashboard; they are not deployed to the 4 GB host, which has no room for
them. The catalog integration test asserts the counters.

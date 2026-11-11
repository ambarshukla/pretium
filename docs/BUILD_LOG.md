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

## 2026-11-05 · The wrong-numbers path, on real data

**Gap.** Earlier entries noted that no model had proposed a selector that read wrong numbers,
so the path that blocks them had never been seen working on real data.

**Shown.** The 3 November page also carries the date "29 October 2026" in a news caption. A
recipe that takes its date from there reads all 29 rates correctly and labels them with the
wrong day: real, well-formed numbers, nothing visibly broken. Checked against the ECB's real
rates for 29 October, **29 of 29 differ** (USD 1.1179 against 1.113), and the recipe is
rejected with each wrong value listed. This is the failure a repair without an answer key
would publish silently.

**Also fixed.** An extractor that threw an unchecked exception (jsoup does, for a malformed
selector) escaped the run without any report, so the failure never reached the operations
page. It is now an `EXTRACTION_FAILED` run like any other. A new service-level test runs the
whole collection path (raw store, extractor, holding file, run report, metrics) with only the
network and Kafka replaced.

## 2026-11-05 · Following the remittance file to its next address

**Problem.** The World Bank's remittance file is published at a new address each quarter,
with a new resource id in the path, so the next address cannot be guessed. The old address
keeps answering. A fixed URL would therefore go on reading the old file with every run
"succeeding", and the freshness check (age of the last good run) would never notice.

**Change.** A source can now name a catalogue entry to consult before each run. For
`wb-rpw` that is the World Bank's data catalogue API, which lists the dataset's files by
name; the collector takes the one named *Remittance Prices Worldwide (Complete Dataset)*.
Guard rails: only an https address on the same host as the configured file is accepted, and
any problem (the catalogue rate-limited us several times on the day this was built) falls
back to the configured URL with a warning, so a failed lookup never stops a collection.
Tested against a real catalogue response.

## 2026-11-05 · Metrics checked against a real run, two flaws fixed

The local stack (Kafka, Postgres, both services, Prometheus, Grafana) was run against the
real sources: three runs, all `SUCCEEDED`, including the World Bank file (204,469 quotes,
about 80 s, read through the new holding file). Every dashboard query was then evaluated.

- **First runs were invisible.** A counter is created on its first increment, so Prometheus
  first sees it already at 1, with no earlier sample to subtract from, and `increase()` reads
  0. With weekly sources and a restart on every deploy, that would hide most runs. Every
  source's counters are now created at zero at startup (checked: all present at 0 before any
  run).
- **The quarantine rate mixed units.** It divided quarantined *prices* (one per quote and
  amount tier) by stored *quotes*, overstating the rate about twofold. A `prices.stored`
  counter now gives the right denominator: 0.056%, which matches the database (230 of
  407,851 prices).

The duration panel now averages over a day, since an hourly window is empty for sources that
run daily or weekly.

## 2026-11-05 · The survey's market rate, checked against the ECB

**Question.** Every FX margin the site shows is measured against the World Bank survey's own
"interbank rate". Is that rate right? The ECB publishes the same quantity independently for
about thirty currencies.

**Result, over the whole history** (37 quarterly surveys, 2016 to 2025, 78,820 quotes, 69,814
between currencies the ECB publishes):

| | Quotes |
|---|---|
| Rate into the receiving country's currency, within 2% of the ECB | 58,270 |
| Rate into another currency the transfer pays out in (US dollars, euros, or the currency sent) | 10,859 |
| Local rate written the wrong way round | 80 |
| Fits nothing (flagged) | 605 (0.9%) |

Local-currency rates sit a median 0.07% to 0.29% from the ECB's, quarter by quarter (0.09% in
Q3 2025, with 98% within 0.5%). The flagged quotes cluster in a few corridors and years:
Israel to Morocco 2016 to 2020 (the rate given is dollars to dirhams), Russia to the Baltic
states 2016 to 2019, Thailand to China and India in 2016 and 2017 (written the wrong way
round), and lira corridors in 2021, when the lira fell fast. That looks like recording
conventions in particular survey rounds, not random error.

**What a plain comparison got wrong.** The first version measured every quote against the
local currency and flagged 12%. Looking at the outliers showed that most are transfers that
pay out in dollars or euros (Japan to Indonesia at 0.00676 is yen to dollars), which the
survey records correctly. Two more details came from the data: a few rates are written to four
decimals ("0.0008" for won to dollars) and only match once rounding is allowed for.

**Also.** The query first took 4 seconds: the survey's currency column is `text` and the rates'
is `char(3)`, so Postgres converted the indexed column and could not use the index. A cast on
the other side brought it to 26 ms. The result is on the Operations page, and at
`/api/v1/ops/benchmark?period=`.

## 2026-11-06 · Repairs, reviewed by people; a parser lab

The repair evaluation now writes a casebook alongside its report: every page the recipe in use
could not read, what each recipe and each model proposal read on it, currency by currency, next
to the ECB's own rate for that day, and the model's reply exactly as it came back. Re-running it
over the 119 archived pages gave the same result as on 5 November (temperature 0): 116 pages read
by a known recipe, 2 repaired by the model and verified, 1 needing a person, 0 wrong numbers.

The site shows the casebook at `/repairs`. The case that needs a person is the copy archived on
15 April 2019, which ends inside the rates table: the recipe of the time and both model
proposals read the same 13 of 36 rates exactly and nothing else. A person can dismiss it
(the page is broken, not the recipe), and a recipe a person writes for it is refused the same
way the model's were: 13 of 36 is not a reading.

`/lab` runs a parser against a raw page and shows the result next to the reference: a recipe
that once worked, a hand-written one, or a local model shown the page the way the repair loop
shows it. Asked about the January 2017 page, `qwen2.5-coder:7b` proposed a recipe in 71 s
(2,611 tokens in, 63 out) that read all 31 rates on it exactly. Nothing run in the lab is
stored. Model runs and hand-written recipes need a review key; see DECISIONS D-018.

## 2026-11-06 · The parser lab as a workbench

The lab and the case pages now show the raw page itself, rendered and as source, with what a
recipe's selectors matched outlined in colour (date, rows, currency cells, rate cells) and
highlighted by line in the source. A recipe is shown as the Java the extractor runs with it,
and a repair as a diff of that code. A run is traced row by row. On the copy archived on 15
April 2019 the trace shows exactly why it cannot be read: the row selector matches 14 rows,
13 are read exactly, and the 14th (HRK) has a currency cell but no rate cell, because the
archived file ends there.

The repair evaluation was re-run to record each prompt as it was sent. Outcomes, recipes and
readings are the same as before; one reply differed only in the date keys, which the program
overwrites. Through the lab locally, `qwen2.5-coder:7b` proposed a recipe for the January
2019 page in 66 s that read 32 of the 36 rates the ECB's feed has for that day, all exactly, and
was accepted.

## 2026-11-06 · Fix the parser, or read the numbers? Measured

`ModelBench` runs two uses of a model on the same 8 archived copies of the ECB page (one
every 15, 2017 to 2025), with the same 6,000-character digest: propose selectors from a
recipe that fails there (one try, verified), or read every rate and the date directly.
Every number is scored against the SDMX feed for the page's true date.

| Model | Repaired, first try | Wrong numbers published by repair | Read directly: exact | Read directly: wrong or made up | Dates right |
|---|---|---|---|---|---|
| `qwen2.5-coder:3b` | 6 of 8 | 0 | 107 | 34 | 2 of 8 |
| `qwen2.5-coder:7b` | 8 of 8 | 0 | 120 | 2 | 4 of 8 |

The digest holds 15 or 16 of the 30 to 36 currencies. Selectors found in it still read the
whole table on the full page; a model reading directly can only type what it saw, and on the
January 2017 page the 3B model typed 28 rates it could not see, all wrong. On the four pages
before 2022 the date is not in the digest at all; both models gave one anyway (the 7B model
said 14 April 2023 every time). The 3B model's two failed repairs dropped a dot from a class
selector (`td.spot number`), which matched nothing: visible, and harmless.

The first run also found a bug, not in the models: the program that finds the date for a
repair took the first date on the page, which since 2022 is often a news item's. On three
pages both models' table selectors were right and the rates were read under the wrong day; the
verifier rejected them as wrong numbers. Fixed (the last date before the table: right on 118 of
118 verified pages, against 74), and those three pages re-run.

Not done: the 14B model and a run that shows the direct reading the whole table instead of the
digest. Started, but a 9 GB model next to the rest of the stack ran the laptop out of memory.

## 2026-11-07 · A second publisher, and its page's twenty years replayed

The Czech National Bank's daily fixing is collected twice: the text file it publishes, and
its public web page read by the same HTML extractor as the ECB's page with a different recipe
(`quote: CZK`, and an amount column: some rates are per 100 or 1,000 units). Reconciliation is
now per publisher: the CNB's page is checked against the CNB's file, never against the ECB
(EUR/CZK on 3 November 2026: 24.340 at the CNB, 24.343 at the ECB, both right).

`RepairEval` then replayed 29 archived copies of the CNB page (2006 to 2025) with
`qwen2.5-coder:7b`, each checked against the CNB's yearly files for its day.

| Run | Read correctly | Model proposals | Accepted | Wrong numbers accepted |
|---|---|---|---|---|
| First | 0 of 29 | 58 | 0 | 0 |
| After the date fix | 5 of 29 (1 worked, 2 healed, 2 reused) | 44 | 2 | 0 |

The first run found a gap in the program that finds a page's date before the model is asked:
it knew only the ECB's "6 November 2026". The CNB writes "04 Jan 2019", and "24.Jul 2009" before
2010, so the model was left to guess the format and got it wrong every time. The date finder
now knows both shapes; it tries the ECB's first, so ECB pages get the same hints as before.

With the date found, the 2021 to 2024 pages are read. The other 24 fail on the table: the 7B
model counts the columns wrongly (the currency code is the fourth cell; it chose the first or
second 25 times, reading country names, currency names or empty cells). The verifier rejected
every one, and those pages wait for a person. Three proposals timed out.

Not done: a date-only repair (keep the table selectors, replace only the date settings, verify)
before the model is asked. It would read the July 2025 page, whose table is unchanged.

## 2026-11-11 · Recovery: replay from the evidence, and a nightly dump

**Built.** `POST /sources/{id}/replay` and `POST /replay` in the collector: every distinct
stored file of a source is re-extracted with the current recipe, oldest first, with one run
report per file (`replay 3 of 29` on the Operations page), and published again through the
same spool and Kafka path as a live run. A file named in the index but missing from the disk is
a reported `EXTRACTION_FAILED`, not a crash, and the replay carries on. `deploy/backup.sh` dumps
the database nightly and keeps a week; RUNBOOK "Losing data" gives the order (dump, Kafka,
evidence) and what each loss costs. Decision D-023.

**Predicted.** Replaying a healthy system changes nothing: the catalog upserts by natural key,
so the counts of rates, prices and breaks should be the same before and after, and the only new
rows should be the run reports.

**Observed.** Not yet run on the host; this entry is completed after the first replay there.

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

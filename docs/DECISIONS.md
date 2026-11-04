# Decisions

The choices that shape pretium, why they were made, and what was given up. Newest last.

## D-001 · Real, open data only

- **Choice:** every number comes from a real, openly licensed source. No generated or
  simulated data anywhere, including tests (test rows are real rows, copied verbatim).
- **Why:** the hard problems in price collection (inconsistent sources, precision loss,
  promotional rates, refusals) only show up in real data. The first run proved it: two of
  the checks were wrong in ways no invented dataset would have exposed (D-008, D-009).
- **Given up:** a controllable test bed. Source changes cannot be staged on demand, so the
  failure paths are tested with real responses altered in one named place.

## D-002 · Collector and catalog are separate services joined by Kafka

- **Choice:** the collector fetches and extracts and owns no database; the catalog checks,
  stores and serves. Every observation crosses Kafka as an event.
- **Why:** collection and serving fail differently and scale differently. A slow or broken
  source must never slow the API, and a bad deploy of the checks must be repairable without
  touching any source again: rewind the consumer and replay (D-010).
- **Given up:** simplicity. One process writing straight to Postgres would be less code.

## D-003 · Keep the raw response, named by its hash

- **Choice:** each body is stored exactly as received at `<source>/<sha256>.<ext>`, and
  every observation carries that hash and its run id.
- **Why:** "where did this price come from?" must have a one-file answer, for an analyst, a
  compliance reviewer, or a re-run of the extractor after a fix. Content addressing also
  deduplicates for free.
- **Given up:** disk. Acceptable: a year of daily ECB files is a few megabytes; the World
  Bank file changes quarterly.

## D-004 · "Unchanged" means already extracted successfully, with this recipe

- **Choice:** a run is skipped as `UNCHANGED` only if the same content (hash) was already
  extracted successfully by the same recipe version.
- **Why:** comparing with the last download alone would mean content that failed to
  extract, or that an older recipe read, is never read again after the recipe is fixed.

## D-005 · Columns by name, selectors by recipe

- **Choice:** CSV and spreadsheet columns are located by header text; HTML values by CSS
  selectors held in the source's YAML recipe, which is versioned. Every extraction failure
  names the column or selector that stopped matching.
- **Why:** sources reorder and rename. Positional parsing breaks silently or, worse, reads
  the wrong column; named lookup breaks loudly with the repair already pointed at.

## D-006 · Collect politely, and take no for an answer

- **Choice:** identifying User-Agent, robots.txt first, a per-host gap, conditional
  requests, and a refusal is final (`BLOCKED`, no retry).
- **Why:** a collector that only works by evading the site it reads is a liability, not an
  asset. The World Bank's survey site serves a bot challenge; the same data is published
  on its data catalogue, which is where it is collected from.

## D-007 · Identities, not ranges, as the main checks

- **Choice:** a price is checked against relationships its own numbers must satisfy:
  total cost = fee as % of amount + FX margin; FX margin = (1 − provider rate ÷ interbank
  rate) × 100. Breach by more than rounding → `WARN`; by more than 1 percentage point
  (plus rounding) → `ERROR` and quarantine.
- **Why:** an identity needs no second source and no historical baseline, and its failure
  message tells an analyst exactly which number is inconsistent.

## D-008 · A price better than interbank is real (correcting the first version)

- **Choice:** a negative FX margin is published with a `better-than-interbank` warning,
  not quarantined. Only totals outside ±100% are treated as impossible.
- **Why:** the first version quarantined every negative total cost as impossible. On the
  real data that held back 3,282 genuine prices: first-transfer promotions (e.g. a zero-fee,
  better-than-interbank rate) and parallel-market rates (e.g. Myanmar, Nigeria, where the
  rate on the street differs from the official one). They are real prices. A reader does
  need to know they are not everyday ones, so they are flagged.

## D-009 · Tolerances follow the digits the source publishes (correcting the first version)

- **Choice:** the margin check's tolerance is widened by how much the published rounding
  of both rates can move the implied margin; if that alone exceeds 1 pp, the check is
  skipped and the price marked `margin-unverifiable`.
- **Why:** the source computes margins from full-precision rates but publishes rates
  rounded. Korean won to Vietnamese dong appears as 0.0007 for both rates, which can mean
  anything from 0.00065 to 0.00075, a 7-point spread. A fixed tolerance flagged 7,709
  prices whose only fault was the file's rounding.

## D-010 · Fix checks by replaying Kafka, not by patching rows

- **Choice:** when a check changes, the catalog re-reads the topics from the beginning
  under a new consumer group and every row is re-judged by the new code.
- **Why:** the verdict on every price then comes from one version of the rules, provably,
  and the procedure is the same one that rebuilds a lost database. Kafka keeps 90 days.

## D-011 · Quarantined prices are never served by default

- **Choice:** the comparison API excludes `QUARANTINED` prices unless explicitly asked.
- **Why:** a wrong price on a comparison page is worse than a missing one: the reader cannot
  tell which it is. The Operations page lists what was held back and why.

## D-012 · One reference channel for reconciliation, compared to the last digit

- **Choice:** the ECB SDMX API is the reference; the XML and HTML channels must match it
  exactly. Mismatches and missing currencies become breaks that close themselves when the
  channels agree again.
- **Why:** reference rates are published to fixed decimals, so any difference at all is a
  real difference. The scraped channel being checkable against two structured channels is
  what will make automated selector repair safe to accept.

## D-013 · Server-rendered front end; the catalog stays private

- **Choice:** Next.js server components call the catalog over the private Docker network.
  The browser never reaches the catalog; only the web container joins the proxy network.
- **Why:** the API's shape can change without breaking anyone's browser cache, and Postgres,
  Kafka and the services have no public surface at all.

## D-014 · Reconcile a date once it has settled

- **Choice:** batches mark dates as touched; a date is reconciled after 30 seconds with no
  new rates for it.
- **Why:** per-batch reconciliation raised false `MISSING` breaks while one day's currencies
  were still arriving on other partitions. Ordering across partitions is not guaranteed, so
  "wait until it is quiet" is the honest condition. Breaks must be signal, because selector
  repair will act on them.
- **Given up:** up to 40 seconds of delay before a real break appears. Irrelevant for daily
  data.

## D-015 · Selector repair: the model proposes where, the reference decides

- **Choice:** when a scraped recipe fails, a model is shown the old recipe, the error and a
  digest of the new page, and asked for new **selectors**, never values. The candidate is
  accepted only if every rate it reads equals the structured channel's rate for that day
  exactly, with at least 90% of currencies found. Otherwise a person is asked.
- **Why:** a selector can be verified; a number produced by a model cannot be verified
  against anything but itself. With an exact-match oracle, a false repair cannot be
  accepted by construction, and the interesting metric becomes how many proposals *would*
  have published wrong numbers without it.
- **Model:** local (Ollama), at temperature 0 so a proposal is repeatable. No data leaves
  the machine, and the marginal cost of a repair is CPU time.
- **Evaluated** on the page's real history: monthly archived copies from 2017 onwards
  (Internet Archive), with the true values from the SDMX history.

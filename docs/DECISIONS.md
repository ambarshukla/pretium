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
  exactly, with at least 80% of currencies found (the SDMX API also carries indicative rates that never appear on the page). Otherwise a person is asked.
- **Why:** a selector can be verified; a number produced by a model cannot be verified
  against anything but itself. With an exact-match oracle, a false repair cannot be
  accepted by construction, and the interesting metric becomes how many proposals *would*
  have published wrong numbers without it.
- **Model:** local (Ollama), at temperature 0 so a proposal is repeatable. No data leaves
  the machine, and the marginal cost of a repair is CPU time.
- **Evaluated** on the page's real history: monthly archived copies from 2017 onwards
  (Internet Archive), with the true values from the SDMX history.

## D-016 · Merging to main deploys, through a key that can do only that

- **Choice:** the `deploy` job in CI runs only on `main`, only after the Java and web checks
  pass, and asks the host for one thing, `deploy`. The SSH key it holds is pinned on the
  host to a forced command, so it cannot open a shell or run anything else.
- **Why:** deploying by hand (copy files, rebuild, restart) depends on someone remembering
  every step, can ship code that never passed the checks, and leaves no record of what is
  running. Handing CI a general-purpose key to the host would make a compromised workflow
  equal to a compromised host; a forced-command key limits the damage to "redeploy main".
- **The script is not in the repository** so that a change to `main` cannot widen what the
  key may do. Its checksum is logged on every run.
- **Also changed:** CI no longer cancels a run on `main` when a newer push arrives (it still
  does for pull requests), because cancelling could stop a deploy half way. Deploys queue.
- **Cost:** the host builds the images itself, one at a time (about 4 GB shared with other
  projects), so a deploy takes several minutes. A container registry would be faster and is
  not worth its cost at this size.

## D-017 · Check the survey's market rate against the ECB, and explain before flagging

- **Choice:** for every survey quote between two currencies the ECB publishes, compare the
  survey's "interbank rate" with the ECB cross rate on the day the quote was observed (or up to
  four days before, for weekends and holidays). Classify each quote by which rate it actually
  fits: the receiving country's currency, US dollars, euros, the currency sent (a rate of 1), or
  the local rate written the wrong way round. Only the wrong-way-round and the unexplained are
  flagged for a person.
- **Why:** every FX margin on the site is measured against the survey's interbank rate, so it is
  the number most worth checking, and the ECB is an independent publisher of the same quantity.
  A plain "difference from the ECB" would have flagged 12% of quotes; almost all of them are
  transfers that pay out in dollars or euros, which the survey records correctly.
- **Tolerance 2%,** or the ECB rate rounded to the decimals the survey wrote. The ECB fixes once a
  day; the survey records the rate at collection time, and on volatile days (the lira in 2021)
  the two differ by more than 1% with neither wrong. Local-currency quotes are in fact a median
  0.07% to 0.29% from the ECB, quarter by quarter.
- **Receiving currency** comes from a table built from the official ISO 4217 lists, with validity
  dates for the euro changeovers, and checked against them in a test. Countries with two legal
  tenders, one of them ECB-quoted (El Salvador, Panama, Haiti, Namibia, Lesotho), are left out
  rather than guessed.

## D-018 · Repairs are reviewed by people, and a person's recipe meets the model's bar

- **Choice:** every repair evaluation writes a casebook (JSON): for each page the recipe in use
  could not read, every rate each recipe read next to the ECB's rate for that day, and the
  model's replies verbatim. The casebook ships with the collector; the site shows it at
  `/repairs`, case by case, old recipe against new. A person can confirm or reject a repair,
  write a recipe for a page nothing could read, or dismiss a page that cannot be read (a broken
  archive copy). The parser lab (`/lab`) runs any known recipe, a hand-written one, or a local
  model against any raw page the server holds, and shows the result without storing it.
- **A person's recipe is verified like the model's:** it is recorded only if every rate it reads
  matches the reference exactly. The verifier, not the author, decides whether a recipe reads a
  page correctly. A person's judgement is for what the verifier cannot know: a page that is
  itself broken, or a repair that passes but is too brittle to keep (both need a note).
- **Decisions are an append-only file in the collector's data volume,** not a database table:
  the collector owns the pages, the reference and the recipes, so it is the one that can check a
  decision, and a later decision supersedes an earlier one without erasing it.
- **Writes, model runs and hand-written recipes need a review key** (`PRETIUM_REVIEW_KEY`, sent
  as `X-Review-Key`, compared in constant time). Without one the pages are read-only. The site
  has no user accounts, and the key keeps a public page from spending CPU on a model or writing
  to the record. The deployed server has no model runner (4 GB of memory), so the lab there runs
  recipes only.
- **Not yet:** an accepted human recipe does not reach the running collector by itself; it is
  recorded with its verification, for a person to put into the source's recipe.

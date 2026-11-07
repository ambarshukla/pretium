# Operating it

How work comes in, who decides what, and what happens when something breaks. The
[runbook](RUNBOOK.md) has the step-by-step fixes and the [SLOs](SLO.md) say what "working"
means; this page is the layer above: the rules a team running this would agree on.

The principle behind all of it: **a wrong price is worse than a stale one, which is worse
than a missing one** (SLO 1). Every rule below follows from that order.

## 1. Asking for something new

Requests come in three kinds. Each is written up the same way: *what decision it supports,
who uses it, how fresh it must be, and what happens if it is wrong.*

| Request | Example | What it takes | Who decides |
|---|---|---|---|
| A new corridor or amount from a source already collected | "Spain → Morocco at 500 USD" | Nothing, if the source has it: the comparison API serves every corridor in the file | Whoever asks; it is a query |
| A new source in a format already supported | another central bank's rates as SDMX or XML | A YAML spec in `collector/src/main/resources/sources/`, a real captured response as a test fixture, a provenance entry | The team, after the licence check below |
| A new source in a new format, or a new kind of data | a provider's own price page | An extractor, a reference to verify it against, and the questions in section 2 answered first | The team and whoever owns the risk of publishing it |

Triage order: **anything that affects a published number first** (a wrong or stale price),
then requests that unblock a decision someone is waiting on, then reach (new sources,
corridors, amounts), then everything else.

## 2. Before a source is collected

Answered in writing, in [`PROVENANCE.md`](PROVENANCE.md), before the first fetch:

- **May we?** Licence or terms of use, and robots.txt. If either says no, we do not
  collect it, whatever its value. The fetcher enforces robots.txt at run time too.
- **What do we cite?** The attribution the publisher asks for, shown wherever its numbers
  appear.
- **What can we check it against?** A source with no second channel cannot have its parser
  repaired automatically (a repair is only accepted if it matches the second channel), so a
  person repairs it, and that is planned for before it is collected.
- **What is the worst plausible error?** That sets which checks it needs before its numbers
  are published.

## 3. Who approves what

| Change | Approved by | Rule |
|---|---|---|
| A price is published | the checks, automatically | Identity checks pass (total = fee + margin, margin = gap from interbank) within the tolerance the source's precision allows. Anything else is held back, with the reason, on the Operations page |
| A model's parser repair is used | the verifier, automatically | Every number the repaired parser reads equals the reference channel exactly, and it reads at least 80% of the currencies. A near miss is a wrong number |
| A model's repair is kept for good | a person, on the [Repairs](https://pretium.ambarshukla.dev/repairs) page | Confirm or reject, with a reason. Rejections are kept as part of the record |
| A parser a person writes | the verifier, the same rule as for a model | A person's recipe that does not read the page exactly is refused, whoever wrote it |
| A page is declared unreadable | a person, with a reason | e.g. the archived copy is truncated. Recorded, never silently skipped |
| A stored value is changed | nobody | Values are never edited. Fix the reader, re-extract from the stored evidence |

Decisions are append-only (`DecisionLog`): who decided, what, why and when, so any number
on the site can be traced to the decision that let it through.

## 4. When something breaks

| Signal | Where it shows | Urgency | First move |
|---|---|---|---|
| A price failed a check | Operations → Held back | Next working day: it is not published | [Runbook: many prices quarantined](RUNBOOK.md#many-prices-quarantined-after-a-source-update) |
| The ECB page's parser failed | Operations → Sources (`EXTRACTION_FAILED`), then Repairs | Same day: the other two ECB channels still serve the rate | Let repair run; if it needs a person, the Repairs page says so. [Runbook](RUNBOOK.md#a-source-shows-extraction_failed) |
| Channels disagree | Operations → Channel breaks | Same day: one channel is wrong, and we do not yet know which | [Runbook: reconciliation breaks](RUNBOOK.md#reconciliation-breaks-are-open) |
| A source is stale | Operations → Sources (`fresh = false`) | Depends on the publisher's own schedule (SLO 2) | [Runbook: stale](RUNBOOK.md#a-source-is-stale-fresh--false) |
| A source refuses us | Operations → Sources (`BLOCKED`) | Do not retry around it | [Runbook: blocked](RUNBOOK.md#a-source-shows-blocked) |

After any incident that let a wrong number reach a reader: a short written review (what
happened, why the checks did not catch it, which check now does), and a test with the real
response that caused it.

## 5. Using models safely

- A model **proposes, code decides**. Models write selectors, never numbers; the numbers are
  read by code and checked against an independent source. The
  [model comparison](https://pretium.ambarshukla.dev/models) shows why: read directly, models
  type rates they could not see.
- Every prompt and reply is stored with the case, so a decision can be reviewed later, and a
  model or prompt change can be re-run against the same pages before it is adopted
  (`ModelBench`, `RepairEval`).
- A model is changed the way code is: on a branch, measured on the archived pages against the
  current one, adopted only if it does not publish more wrong numbers and costs no more than
  it saves.

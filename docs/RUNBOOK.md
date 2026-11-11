# Runbook

What to do when something looks wrong. Each entry: the symptom, how to confirm it, what
to do, and what not to do. Commands run in `deploy/` on the host (`docker compose ...`) or
locally against `infra/`.

## A source shows `EXTRACTION_FAILED`

**Meaning.** The response arrived but the recipe could not read it: the source changed.

1. Read the run's message on the Operations page. It names the selector or column that
   stopped matching.
2. Open the evidence: `data/raw/<source>/<sha256>.<ext>` (the hash is in the run row).
3. For an HTML source, run selector repair on that file (see `collector/.../repair/`).
   Accept a new recipe only if the verifier passes it against the reference channel.
4. Bump the recipe `version` in the source's YAML, deploy, and re-extract from evidence:
   `docker compose exec collector curl -s -X POST localhost:8091/sources/<id>/runs?force=true`.

**Do not** edit stored values by hand. Fix the reader and re-extract.

## A source shows `BLOCKED`

**Meaning.** robots.txt disallows the URL, or the site refused (401/403/451) or served a
bot challenge.

1. Check the message: robots, HTTP status, or "bot challenge served".
2. Look for an official route to the same data (an API, a data catalogue, a file
   download). The World Bank survey is collected from its data catalogue for this reason.
3. If there is none, the source is unavailable: remove its spec (the evidence stays) and
   record why in DECISIONS.

**Do not** retry with another User-Agent, proxies or a headless browser to get past a
refusal.

## A source is stale (`fresh = false`)

1. Is it a publisher holiday? ECB rates are not published on TARGET closing days.
2. Look at the last runs for that source: `FETCH_FAILED` (network, 5xx: usually transient),
   `PUBLISH_FAILED` (the source was read but Kafka did not take every message: check the
   broker, then collect again; nothing was marked as extracted, so the same evidence is re-sent)
   or nothing at all (the collector is down: `docker compose ps`, `logs collector`).
3. Collect now: `POST /sources/<id>/runs`.

## Reconciliation breaks are open

1. `VALUE_MISMATCH`: two channels give different numbers for the same day. Compare the
   evidence files of both runs. A publisher correction shows up as a `value_revision` on
   one channel.
2. `MISSING`: a channel lacks a currency the reference has. Normal for currencies the
   reference publishes and the channel never shows (indicative rates); otherwise suspect
   the scraper's recipe.
3. Breaks close themselves when the channels agree again.

## Many prices quarantined after a source update

1. `/api/v1/ops/quality` → which rule.
2. Sample the rows (`/api/v1/ops/quarantine`). Decide: is the data wrong, or the rule?
3. If the rule is wrong: fix it, add a test with the real row, deploy, and **replay** (below).
   This happened on the first run (DECISIONS D-008, D-009).

## Replay every event through the current checks

After changing a check:

```bash
docker compose stop catalog
docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --group pretium-catalog \
  --reset-offsets --to-earliest --all-topics --execute
docker compose start catalog
```

Upserts make the replay safe. Kafka keeps 90 days; for anything older, replay from the
evidence (below).

## Losing data

What is where, and which copy is the one that matters:

| Data | Where | Is it the only copy? |
|---|---|---|
| Raw evidence (every response, by hash) and the review decision log | `${PRETIUM_HOST_DATA}/raw` on the host | **Yes.** Everything else is derived from it. Copy it off the host (below). |
| Kafka topics | `${PRETIUM_HOST_DATA}/kafka` | A 90-day buffer between collector and catalog. Rebuilt by replaying the evidence. |
| The database (verdicts, rates, breaks, revisions) | `${PRETIUM_HOST_DATA}/postgres` | A view the checks derive from the evidence. Dumped nightly; rebuilt by replay. |
| Archived pages for the lab | `${PRETIUM_HOST_DATA}/archive` | Copies of public archives, re-downloadable. |

**Copies.** `deploy/backup.sh` dumps the database nightly (`pg_dump`, custom format) into
`/opt/pretium/backups` and keeps a week. The raw store and the dumps are copied off the
host by hand, from the operator's machine, into a directory of their own:

```bash
rsync -az HOST:/opt/pretium/data/raw/ ~/pretium-offsite/raw/
rsync -az HOST:/opt/pretium/backups/ ~/pretium-offsite/backups/
```

Do this after any week in which something new was collected for the first time (a new
source, a backfill). The evidence changes by a few files a day otherwise, all of which the
publishers still serve.

**The database is lost or wrong** (a bad migration, a disk error on one volume):

1. Fastest: restore last night's dump. Stop the catalog, then
   `docker compose exec -T postgres pg_restore -U pretium -d pretium --clean --if-exists --no-owner < /opt/pretium/backups/pretium-<stamp>.dump`,
   then start the catalog. It picks up where its consumer group left off, so the day since
   the dump arrives from Kafka.
2. No dump, Kafka intact: start the catalog on an empty database (Flyway creates the
   schema) and replay the topics (above). Covers the last 90 days.
3. Neither: replay from the evidence (next).

**Replay from the evidence** re-extracts every stored file of a source with the recipe in
use now, oldest first, one run report per file, and publishes the results as if freshly
collected. The catalog upserts, so replaying what is already there changes nothing.

```bash
docker compose exec collector curl -s -X POST localhost:8091/sources/<id>/replay   # one source
docker compose exec collector curl -s -X POST localhost:8091/replay                # all of them
```

The answer lists how many files each source will replay; progress is in the collector's log
and on the Operations page (`replay 3 of 29`). A replay holds the source's lock, so a
scheduled run that falls due meanwhile is refused and tries again next time. The World Bank
file is the slow one: each stored edition is 400,000 prices through the checks.

**The whole host is lost.** Follow "First install" in `deploy/README.md` on a new host; copy
the off-site `raw/` back to `${PRETIUM_HOST_DATA}/raw` (owner uid 10001); start the stack
with `PRETIUM_COLLECT_ON_STARTUP=false`; `POST /replay`. If a dump exists, restore it first
and replay only what the dump postdates (the replay is still safe, only slower).

**What cannot be recovered:** nothing published, as long as the raw store survives. Without
it, anything older than Kafka's 90 days and the last dump is gone, and the publishers' own
history endpoints (the ECB's and the Bank of Canada's full series, the CNB's yearly files)
are the only way back; the World Bank's older editions are not re-published.

## The host is short of memory

`docker stats --no-stream`. Limits are set per container in `docker-compose.yml`. The
largest consumer during a first load is the catalog checking 400,000 prices; at rest the
stack uses about 1 GB.

## Debugging the HTML parser by hand

Open `/lab`, pick the page (anything the collector fetched, or an archived copy), pick a
recipe, and run it. The trace says where it stopped: no element for the date selector, a date
the pattern does not match, a row selector that matches nothing, or rows without a currency
or rate cell. "Edit a copy" turns any recipe into editable fields, and the outline on the page
follows the fields as they change. A recipe that reads the page exactly can be recorded on the
page's repair case (review key), then put into the source's YAML with its version bumped.

Archived copies live in `${PRETIUM_HOST_DATA}/archive/<timestamp>.html` on the host, mounted
read-only into the collector. Adding one is a file copy; the lab lists it on the next request.

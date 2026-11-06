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
2. Look at the last runs for that source: `FETCH_FAILED` (network, 5xx: usually transient)
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

After changing a check, or to rebuild a lost database:

```bash
docker compose stop catalog
docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --group pretium-catalog \
  --reset-offsets --to-earliest --all-topics --execute
docker compose start catalog
```

Upserts make the replay safe. Kafka keeps 90 days; older history is rebuilt by re-extracting
the evidence (`?force=true`).

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

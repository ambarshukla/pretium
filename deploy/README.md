# deploy/

How pretium runs on one small Linux host (2 CPUs, 4 GB), behind an existing reverse proxy.

```
internet ──443──▶ reverse proxy (Caddy, TLS) ──▶ web :3000 ──private──▶ catalog :8082
                                                                         │
                     collector ──▶ kafka ──▶ catalog ──▶ postgres ◀──────┘
                         │
                     raw evidence on the host
```

Only `web` is on the proxy's network. Postgres, Kafka, the collector and the catalog
publish no ports.

## First install

```bash
sudo mkdir -p /opt/pretium/src /opt/pretium/data/{postgres,kafka,raw}
sudo chown -R "$USER" /opt/pretium
sudo chown 1000:1000 /opt/pretium/data/kafka     # the Kafka image runs as uid 1000
sudo chown 10001:10001 /opt/pretium/data/raw     # the collector runs as uid 10001

# from a clone: ship exactly one commit
git archive HEAD | ssh HOST 'tar -x -C /opt/pretium/src'

# on the host
cd /opt/pretium/src/deploy
cp .env.example .env && chmod 600 .env            # set DB_PASSWORD and PROXY_NETWORK
docker compose build collector && docker compose build catalog && docker compose build web
docker compose up -d
```

Build the images one at a time on a 4 GB host: three parallel Gradle and npm builds do not
fit. With `PRETIUM_COLLECT_ON_STARTUP=true` the first start collects every source (about
55 MB; the catalog takes a few minutes to check all 400,000 prices); set it back to
`false` afterwards.

Proxy block (Caddy; certificates are automatic):

```
pretium.example.com {
	encode gzip
	reverse_proxy pretium-web:3000
}
```

## Operating

| Task | Command (in `deploy/`) |
|---|---|
| Status | `docker compose ps` |
| Logs | `docker compose logs -f collector catalog` |
| Collect one source now | `docker compose exec collector curl -s -X POST localhost:8091/sources/ecb-sdmx-exr/runs` |
| Re-extract even if unchanged | add `?force=true` |
| Replay every event through the checks | stop `catalog`, then `docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --group pretium-catalog --reset-offsets --to-earliest --all-topics --execute`, then start `catalog` |
| Database shell | `docker compose exec postgres psql -U pretium` |

## Updating

Merging to `main` deploys, once the checks have passed: the `deploy` job in
[`ci.yml`](../.github/workflows/ci.yml) connects to the host and asks for `deploy`, then
checks that the public site answers (decision D-016).

The key it uses cannot get a shell on the host. `~/.ssh/authorized_keys` pins it to one
script with a forced command, so whatever the client asks to run, sshd runs that script
instead:

```
command="/opt/pretium/bin/deploy.sh",restrict ssh-ed25519 AAAA... pretium-deploy
```

`/opt/pretium/bin/deploy.sh` is root-owned and deliberately **not** part of this
repository: if it were, a push to `main` could change what the key is permitted to do, and
the forced command would stop being a boundary. What it does:

```bash
case "${SSH_ORIGINAL_COMMAND:-}" in
  deploy)
    flock /opt/pretium/.deploy.lock                   # one deploy at a time
    cd /opt/pretium/src && git fetch origin main && git reset --hard origin/main
    cd deploy
    for s in collector catalog web; do docker compose build --quiet "$s"; done   # one by one: memory
    docker compose up -d                              # recreates only what changed
    docker inspect ... pretium-{postgres,kafka,collector,catalog,web}   # all must be running
    docker exec pretium-web node -e "fetch('http://127.0.0.1:3000/ops')..."     # and serving
    git rev-parse HEAD > /opt/pretium/DEPLOYED_COMMIT
    ;;
  *) exit 1 ;;                                        # anything else is refused and logged
esac
```

A container that reports `running` is not necessarily serving, so the script also waits
(up to a minute) for the web server to answer a real page, and the workflow then waits for
the public URL. A deploy that cannot be reached fails rather than being recorded.

Two repository secrets drive it: `PRETIUM_DEPLOY_KEY` (the private half) and
`PRETIUM_DEPLOY_TARGET` (`user@host`; the host is not public, so it is a secret too).

A deploy ships code, not data: collection schedules and the database are untouched.

By hand, if the automatic deploy is unavailable:

```bash
ssh HOST 'cd /opt/pretium/src && git fetch origin main && git reset --hard origin/main \
  && cd deploy && docker compose build collector && docker compose build catalog \
  && docker compose build web && docker compose up -d'
```

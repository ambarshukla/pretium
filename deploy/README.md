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

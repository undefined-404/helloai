# HelloAI Monitoring Stack (Phase 1: Prometheus + Grafana metrics)

Port of the verified monitoring implementation from `E:\yhzx\1027\maticube\deploy\monitoring` (a full ELK + Prometheus + Grafana setup proven in the maticube project) to a minimal metrics-only stack for HelloAI.

## Coverage

| Target | Collection | Dashboard panels |
| --- | --- | --- |
| HelloAI app | Actuator `/actuator/prometheus` (micrometer-registry-prometheus) | JVM heap/non-heap, system CPU, live threads, HTTP RT P50/P95/P99, QPS, GC pause, Top 5 slow URIs |
| RabbitMQ | Built-in rabbitmq_prometheus plugin in the management image (15692, no extra exporter) | Ready/unacked by queue, fill ratio vs x-max-length (v1.2 capacity limits), DLX dead-letter depth |
| PostgreSQL | postgres-exporter (read-only monitoring account `helloai_monitor`) | Connections, transaction rate, mq_dead_letter_archive row count (V60 dead-letter ledger) |
| Redis | redis-exporter | Memory used, connected clients, commands/s, keyspace hits/misses |
| Scrape health | Prometheus `up` metric | Healthy/unhealthy target counts, target status table |

## Directory Layout

```
deploy/monitoring/
|-- docker-compose.monitoring.yml   # Monitoring stack (prometheus + grafana + redis-exporter + postgres-exporter)
|-- prometheus/
|   |-- prometheus.yml              # Server config (scrapes app:6565 by service name)
|   `-- prometheus-local.yml        # Local config (scrapes host.docker.internal:6565)
`-- grafana/
    |-- datasources/datasources.yml # Prometheus datasource (provisioning auto-load)
    `-- dashboards/
        |-- dashboards.yml          # Dashboard provider (provisioning auto-load)
        `-- helloai-overview.json   # "HelloAI Overview" dashboard
```

## First Deployment

### 1. Create the PostgreSQL monitoring account (read-only, execute manually)

Run against the `helloai` database (once on local DB and once on server DB; idempotent, confirm before re-running):

```sql
CREATE ROLE helloai_monitor LOGIN PASSWORD 'HelloAI_monitor_2026';
GRANT CONNECT ON DATABASE helloai TO helloai_monitor;
GRANT pg_monitor TO helloai_monitor;
```

- `pg_monitor` is the built-in read-only monitoring role of PostgreSQL 10+ (can read `pg_stat_*` views), with no table data read/write privileges.
- If the password is changed, sync the `DATA_SOURCE_NAME` of `postgres-exporter` in `docker-compose.monitoring.yml`.

### 2. Start the monitoring stack

Prerequisite: the main stack is up (local `docker-compose.yml` or server `docker-compose.server.yml`), and the shared external network `helloai_default` already exists.

Local (app runs in IDEA, scrapes `host.docker.internal:6565` by default):

```powershell
docker compose -f deploy/monitoring/docker-compose.monitoring.yml up -d
```

Server (app is a compose container, scrapes internal `app:6565`; expose Grafana on the public NIC and set a strong admin password):

```bash
export PROMETHEUS_CONFIG=./prometheus/prometheus.yml
export GRAFANA_BIND=0.0.0.0        # expose Grafana on the public NIC
export GRAFANA_ADMIN_PASSWORD=<strong-password>  # required once Grafana is public
# Optional: export PROMETHEUS_BIND=0.0.0.0 to expose Prometheus too
cd /home/admin/helloai/deploy/monitoring
# run the compose file from this directory (internal mounts are relative paths)
docker compose -f docker-compose.monitoring.yml up -d
```

Access: `http://<server-ip>:3000` (admin / the password set above).

### 3. Verification

- Prometheus: `http://localhost:9090/targets` (reachable via SSH tunnel on the server; all ports bind to 127.0.0.1)
- Grafana: `http://localhost:3000`, account `admin/admin`, the "HelloAI Overview" dashboard auto-loads
- Check app metrics from the command line: `curl http://localhost:6565/actuator/prometheus | Select-Object -First 20`
- RabbitMQ plugin check: `curl http://localhost:25673/api/overview -u guest:guest` or simply verify the rabbitmq job is UP in Prometheus targets

## Operations Notes

- Port binding: prometheus/grafana bind to `127.0.0.1` by default, not exposed publicly. To access Grafana from the internet, set `GRAFANA_BIND=0.0.0.0` (and set `GRAFANA_ADMIN_PASSWORD` to a strong password first); optionally `PROMETHEUS_BIND=0.0.0.0` for Prometheus. Restrict the Aliyun security group source IP if possible instead of `0.0.0.0/0`.
- Data retention: Prometheus `--storage.tsdb.retention.time=30d` (a few tens of MB/day, negligible).
- Stopping the monitoring stack does not affect the main stack: `docker compose -f deploy/monitoring/docker-compose.monitoring.yml down`
- After editing dashboards/datasources: `docker compose -f deploy/monitoring/docker-compose.monitoring.yml restart grafana` (provisioning re-applies within 30s).

## Phase 2 (Reserved, not implemented)

- Log aggregation: Loki (lightweight) or the maticube filebeat -> logstash -> ES/Kibana pipeline (HelloAI is a single-instance monolith with small log volume; Loki is sufficient; full ELK only fits multi-instance distributed scenarios).
- Alerting: Grafana Alerting / Alertmanager with DingTalk or WeCom webhook.

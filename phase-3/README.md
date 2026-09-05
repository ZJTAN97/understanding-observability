# Phase 3 — Infrastructure signals

Phase 2 traced the application's *calls* to MongoDB, RabbitMQ and MinIO. It did
not observe the three systems at all. This phase does, and promotes each of them
to the topology it would actually run in:

```
MongoDB    3-node replica set     rs0, keyFile auth, mongo1 priority 2
RabbitMQ   3-node cluster         classic_config discovery, pause_minority, quorum queue
MinIO      4-node erasure set     server http://minio{1...4}/data, EC parity 2
```

None of them speaks OTLP and none of them ever will. The Collector's job in this
phase is to *fetch and translate* — it stops being a pipe and becomes a scraper,
a log shipper and a translator. Phase 2's config had one receiver; this one has
five.

Read [`docs/phase-3-infrastructure-signals.html`](../docs/phase-3-infrastructure-signals.html) first.

```
              push                     pull                      push
  app ──OTLP──▶ ┌──────────────────────────────────────────────┐
                │                  Collector                    │
  rabbitmq ◀────┤ prometheus receiver  :15692 /metrics          │
  minio    ◀────┤                      /metrics/detailed        │──▶ Prometheus
  mongo-exp ◀───┤                      /minio/v2/metrics/*      │──▶ Loki
  mongo    ◀────┤ mongodb receiver     serverStatus, dbStats    │──▶ Tempo
                │                                               │
  mongo1 log ──▶│ file_log receiver    tail /var/log/mongodb    │
  everything ──▶│ fluentforward        :24224 (docker driver)   │
                └──────────────────────────────────────────────┘
```

## Run

```sh
docker compose up -d          # ~90 s to a fully formed cluster of clusters
open http://localhost:3000
```

Three dashboards are provisioned: **Phase 3 — MongoDB replica set**,
**RabbitMQ cluster**, **MinIO erasure set**.

## Verify

### The topologies are real

```sh
docker compose exec mongo1 mongosh --quiet -u root -p rootpw \
  --authenticationDatabase admin --eval 'rs.status().members.map(m=>m.name+" "+m.stateStr).join("\n")'
docker compose exec rabbitmq1 rabbitmqctl list_queues name type leader members
docker compose exec rabbitmq1 rabbitmq-diagnostics -q cluster_status | sed -n '/Running Nodes/,/^$/p'
```

```
mongo1:27017 PRIMARY
mongo2:27017 SECONDARY
mongo3:27017 SECONDARY

name    type    leader            members
orders  quorum  rabbit@rabbitmq1  [rabbit@rabbitmq1, rabbit@rabbitmq2, rabbit@rabbitmq3]

Running Nodes
rabbit@rabbitmq1
rabbit@rabbitmq2
rabbit@rabbitmq3
```

```sh
curl -s http://localhost:9000/minio/v2/metrics/cluster | grep -E '^minio_cluster_(drive_online_total|health_erasure_set_status|write_quorum)'
```

```
minio_cluster_drive_online_total{server="minio1:9000"} 4
minio_cluster_health_erasure_set_status{pool="0",server="minio1:9000",set="0"} 1
minio_cluster_write_quorum{server="minio1:9000"} 3
```

### Five signal sources, one Prometheus, one Loki

```sh
curl -sG localhost:9090/api/v1/label/job/values
curl -sG localhost:3100/loki/api/v1/label/service_name/values
curl -sG localhost:3100/loki/api/v1/label/service_instance_id/values
```

```
["minio","mongodb","otelcol-contrib","rabbitmq","shop/order-api","shop/order-worker"]
["minio","mongodb","order-api","order-worker","rabbitmq"]
["minio1","minio2","minio3","minio4","mongo1:27017","mongo2","mongo3",
 "rabbitmq1","rabbitmq2","rabbitmq3", <two SDK uuids for the app>]
```

`job` is *not* the scrape job. The Prometheus receiver sets `service.name` from
the job name, so `rabbitmq` and `rabbitmq-detailed` would be two services, and
`minio-cluster` and `minio-node` another two. `transform/normalise-infra`
collapses them to one name per system and moves the node identity to
`service.instance.id`, which Prometheus's OTLP receiver maps to `instance`.
Do this or every dashboard needs an `or` between two spellings of one system.

### The application trace still works, through all of it

```sh
TID=$(curl -sG localhost:3200/api/search \
       --data-urlencode 'q={name="update shop.orders"}' --data-urlencode 'limit=1' \
      | python3 -c 'import sys,json;print(json.load(sys.stdin)["traces"][0]["traceID"].zfill(32))')
curl -s "localhost:3200/api/v2/traces/$TID"
```

```
trace_id = 19caf2fdacf5b38278fbf34a7a125b4a
  +   0.0ms  order-api     SERVER    POST /orders               7.83ms  tomcat-10.0
  +   3.3ms  order-api     PRODUCER  orders publish             0.37ms  rabbitmq-2.7
  +  10.5ms  order-worker  CONSUMER  orders process             0.16ms  rabbitmq-2.7
  +  10.7ms  order-worker  CONSUMER  orders.created process    16.83ms  spring-rabbit-1.0
  +  12.9ms  order-worker  CLIENT    insert shop.orders         2.41ms  mongo-4.0
  +  17.6ms  order-worker  CLIENT    PUT                        6.48ms  okhttp-3.0
  +  25.0ms  order-worker  CLIENT    update shop.orders         1.99ms  mongo-4.0
```

Identical to phase 2, against a replica set, a cluster and an erasure set. The
`zfill(32)` matters: Tempo's search API returns the trace ID with leading zeros
stripped, Loki stores the full 32 hex characters, and the correlation query
silently returns nothing if you paste one into the other.

```sh
curl -sG localhost:3100/loki/api/v1/query_range \
  --data-urlencode "query={service_name=~\"order-.*\"} | trace_id=\"$TID\""
```

```
order-api     published orders.created order.id=357a4627-... order.sku=SKU-7
order-worker  order receipted order.id=357a4627-... receipt.key=357a4627-....json
```

### Two log-collection mechanisms, side by side

`docker compose logs mongo1` is **empty** past startup — mongod was given
`--logpath`, so nothing goes to stdout. mongo2 and mongo3 log to stdout and are
picked up by the Docker `fluentd` driver. Both end up under
`{service_name="mongodb"}` in Loki, and they do not look the same:

```
instance: mongo1:27017            (file_log receiver, json_parser)
  body   : Successfully authenticated
  labels : s=I  severity_number=9  severity_text=I  detected_level=I
           c=ACCESS  ctx=conn370  id=5286306  log_file_name=mongod.log
           t_date=2026-09-04T10:23:24.214+00:00
           attr_user=otel  attr_db=admin  attr_mechanism=SCRAM-SHA-256
           attr_client=172.19.0.17:60592  attr_metrics_conversation_duration_micros=2623
           attr_metrics_conversation_duration_summary_0_step=1  ... (20 more)

instance: mongo3                  (fluentforward receiver, no parsing)
  body   : {"t":{"$date":"2026-09-04T10:23:29.048+00:00"},"s":"I","c":"NETWORK",...}
  labels : fluent_tag=mongo3  log_iostream=stdout  detected_level=unknown
```

The parsed one is queryable — `severity_text`, `ctx`, the operation's own
fields. It is also expensive: mongod's `attr` object is an open set, and every
key in it became a piece of structured metadata. The unparsed one is one string
and one label. Neither is right; the choice is which cost you want.

Which mechanism to reach for:

| | `fluentforward` + Docker driver | `file_log` |
|---|---|---|
| host paths needed | none | the log file must be mountable |
| works on macOS | yes | only for files a container writes to a volume |
| container start | needs `fluentd-async: "true"` or it blocks | unaffected |
| Collector restart | records emitted meanwhile are lost | resumes from a `file_storage` checkpoint |
| phase 5 | not available | this is how `/var/log/pods` is read |

`/var/lib/docker/containers` cannot be bind-mounted on macOS — it lives inside
the VM. That is the whole reason the fluentd driver exists in this compose file.

## Cardinality

The two most expensive words in this phase are `--collect-all`. Series count in
Prometheus with `filter/cardinality` removed from `metrics/infra`, and with it
restored:

```sh
curl -sG localhost:9090/api/v1/query --data-urlencode 'query=count by (job) ({__name__=~".+"})'
```

| job | unfiltered | filtered | dropped |
|---|---:|---:|---:|
| mongodb | 6520 | 2730 | 58% |
| rabbitmq | 5268 | 1068 | **80%** |
| minio | 709 | 547 | 23% |
| otelcol-contrib | 199 | 100 | 50% |
| shop/order-api + order-worker | 250 | 250 | 0 |
| **total** | **13061** | **4810** | **63%** |

Nine OTTL rules, a third of the storage. What they drop:

| rule | what it is | series |
|---|---|---:|
| `^(go_\|promhttp_\|process_)` | Go runtime of every exporter and MinIO node | 44 + 35×4 |
| `^erlang_vm_(allocators\|msacc\|dist\|atom\|ets_tables)` | Erlang VM internals × 3 brokers | 4200 |
| `^mongodb_ss_metrics_commands_` | one series per MongoDB command name | 862 |
| `^mongodb_ss_wt_` minus three | WiredTiger internals | 1166 |
| `^mongodb_top_` | 18 metrics × every collection, system ones included | 450 |
| `^mongodb_oplog_stats_` | WiredTiger internals *of the oplog collection* | 324 |
| `_collection_stats_` | the same, for `config.image_collection` | 631 |
| `^mongodb_sys_` | host CPU/memory/disk out of `/proc` | 313 |
| `^minio_node_drive_latency_us$` | per-drive, per-storage-API latency | 7 × 4 |

Two of those are the curriculum's per-collection case arriving where nobody
looks for it. `mongodb_top_*` is genuinely per-collection and grows with the
schema. `mongodb_oplog_stats_*` and `_collection_stats_` are 955 series
describing the WiredTiger block manager of two *internal* collections — the
oplog and `config.image_collection` — and neither is anything a person would
choose to store. They exist because `--collect-all` means what it says.

The oplog numbers that matter, `mongodb_mongod_replset_oplog_head_timestamp`
and `..._tail_timestamp`, are not in that family and stay.

The last rule is the one to copy the reasoning from rather than the regex:
`mongodb_sys_*` is 313 series of host CPU, memory and disk, read out of `/proc`
by a MongoDB exporter. It is not wrong, it is *someone else's job* —
`hostmetrics` here, `kubeletstats` in phase 5 — and collecting it twice under
two naming schemes is how a metrics bill doubles without anyone deciding to.

The Collector will tell you the rate itself, which is how you know a rule is
doing anything:

```sh
curl -s http://localhost:8888/metrics | grep -E 'filter_datapoints_filtered|receiver_accepted_metric_points'
```

```
otelcol_processor_filter_datapoints_filtered_total{filter="filter/cardinality"}  58297
otelcol_receiver_accepted_metric_points_total{receiver="prometheus"}             89022
otelcol_receiver_accepted_metric_points_total{receiver="mongodb"}                  340
otelcol_receiver_accepted_metric_points_total{receiver="otlp"}                     220
```

65% of everything pulled is discarded before it reaches the exporter. A rule
that matches nothing looks exactly like a rule that works, and this counter is
the difference.

Two cheaper controls than a filter processor are used here too, and both are
better because the data never crosses the network:

- **Not asking.** `prometheus.return_per_object_metrics = false` keeps
  RabbitMQ's `/metrics` at constant cardinality however many queues exist.
  Per-queue numbers come from `/metrics/detailed` with exactly three families
  named in the scrape config.
- **Not scraping.** MinIO's `/minio/v2/metrics/bucket` is never fetched. Bucket
  count is user input; it does not belong in a metric label at all.

## Two metric sources for MongoDB, on purpose

| | `mongodb` receiver | Percona `mongodb_exporter` |
|---|---|---|
| speaks | the Mongo wire protocol, directly | the Mongo wire protocol, then Prometheus text |
| names | OTel semconv — `mongodb.document.operation.count` | `mongodb_ss_*` (serverStatus verbatim) and `mongodb_rs_*` |
| resource | one, for the whole set, no port | one, the exporter itself |
| **replication lag** | **not available** | `mongodb_mongod_replset_member_replication_lag` |
| **oplog window** | **not available** | `oplog_head_timestamp - oplog_tail_timestamp` |
| elections | not available | `mongodb_rs_term`, `mongodb_rs_electionCandidateMetrics_*` |
| series | ~90 | 6446 before filtering |

The receiver is clean, well-named and cannot answer the two questions a replica
set exists to raise. Running both is not indecision; it is the honest cost of
the fixed metric set a purpose-built receiver gives you.

The exporter is one instance for all three nodes: `replSetGetStatus` on any
member describes every member. It needs `--no-mongodb.direct-connect` — the
flag defaults to true, which is invalid with a multi-host URI, and the only
symptom is `mongodb_up 0`.

## Breaks

Each was run; the observed column is what actually happened.

### `docker kill mongo1` — the primary

| Predict | Observed |
|---|---|
| an election, a new primary, lag on the returning node | exactly that, plus a series-identity problem |

```
before                                                     after 45 s
mongodb_rs_term = 1                                        mongodb_rs_term = 2
mongo1:27017 PRIMARY    = 1                                mongo3:27017 PRIMARY = 1
mongo2:27017 SECONDARY  = 2                                mongo2:27017 SECONDARY = 2
mongo3:27017 SECONDARY  = 2                                mongo1:27017 (not reachable/healthy) = 8
```

`docker start mongo1` and it is re-elected primary within 40 s — it has
`priority: 2`. The worker logged 26 driver errors during the ~10 s window, all
of the same shape, and recovered without a restart:

```
com.mongodb.MongoSocketReadException: Prematurely reached end of stream
org.mongodb.driver.cluster: Waiting for server to become available ...
  topology description: {type=REPLICA_SET, servers=[
    {address=mongo3:27017, type=REPLICA_SET_SECONDARY, state=CONNECTED},
    {address=mongo2:27017, type=REPLICA_SET_SECONDARY, state=CONNECTED},
    {address=mongo1:27017, type=UNKNOWN, state=CONNECTING,
     exception={com.mongodb.MongoSocketOpenException}, caused by {java.net.ConnectException}}]}
```

**The part worth staring at.** The exporter puts the member's state in a
*label*, so a state change does not move a series — it retires one and starts
another:

```sh
curl -sG localhost:9090/api/v1/query \
  --data-urlencode 'query=count_over_time(mongodb_rs_members_state[10m])'
```

```
mongo1:27017 PRIMARY                    samples: 19
mongo2:27017 SECONDARY                  samples: 23
mongo3:27017 SECONDARY                  samples: 19
mongo1:27017 (not reachable/healthy)    samples: 4
mongo3:27017 PRIMARY                    samples: 4
```

Three members, five series, one election. `rs_state` is a label on *every*
`mongodb_ss_*` series too, so a failover briefly doubles 4462 of them. This is
the phase 0 cardinality lesson arriving from a direction nobody plans for: not
your label, someone else's, on data you only scraped.

### Stop `order-worker`, keep publishing

| Predict | Observed |
|---|---|
| backlog climbs, consumers goes to zero | yes, and the drain rate on recovery is the interesting number |

```
                       before    after 2 min
messages_ready              0             98
consumers                   1              0
messages_bytes              0         12 677
queue process memory   69 836         69 836
publish rate            0.90/s        0.90/s
deliver rate            0.90/s        0.00/s
```

`docker compose start order-worker`, and 50 s later:

```
messages_ready              0
consumers                   1
deliver rate            1.51/s        # publish rate is 0.90/s — this is catch-up
```

The memory alarm never fired: 98 messages is 12 KB and the watermark is 50% of
512 MB. To see an alarm, leave it stopped for an hour or drop
`vm_memory_high_watermark.relative` to something absurd. Consumer count at zero
with a rising `messages_ready` is the alert you would actually write; the memory
alarm is what fires once the alert has been ignored for long enough.

### `docker kill minio4` — one drive

| Predict | Observed |
|---|---|
| a drive-offline metric, reads and writes continue, healing on return | yes, with one honest wrinkle |

```
                                    before   killed   restarted
minio_cluster_drive_online_total         4        3           4
minio_cluster_drive_offline_total        0        1           0
erasure_set_online_drives                4        3           4
erasure_set_status                       1        1           1
putobject rate                       0.88/s   0.88/s      0.88/s
minio_s3_requests_errors_total rate       0        0           0
```

Zero application errors. Write quorum is 3 of 4, so the receipt writes never
noticed. On restart, healing ran and finished:

```
minio_heal_objects_total       74 3 3 43 1
minio_heal_objects_heal_total  74 3 3 43 1
```

The wrinkle: immediately after the kill, `minio_cluster_nodes_online_total` read
`4` from one surviving node and `3` from the other two. Cluster-wide state is
gossiped, and for a few scrape intervals the four nodes disagree. A single-value
panel over a multi-node gauge is a lie during exactly the window you are looking
at it; the dashboard uses the cluster endpoint (one node) for cluster facts and
the node endpoint for node facts.

### `APP_HIGH_CARDINALITY_LABEL: "true"` on `order-api`

One extra attribute on one counter — `order.id`, unique per request:

```java
telemetry.ordersCreated.add(1, cfg.highCardinalityLabel()
    ? Attributes.of(CHANNEL, order.channel(), ORDER_ID, order.id())
    : Attributes.of(CHANNEL, order.channel()));
```

| Predict | Observed |
|---|---|
| series count climbs without bound | 2 → 107 in three minutes |

```sh
curl -sG localhost:9090/api/v1/query --data-urlencode 'query=count(count_over_time(orders_created_total[5m]))'
curl -s localhost:9090/api/v1/status/tsdb
```

```
orders_created_total series      2  ->  107        after 3 minutes at 0.9 orders/s
total active series           6495  ->  6788
```

`/api/v1/status/tsdb` then ranks it third in the whole database, above every
metric of the three infrastructure systems except Erlang's allocator table:

```
erlang_vm_allocators                        2736
http_server_request_duration_seconds_bucket  135
orders_created_total                         107
```

At one order per second that is 86 400 new series a day, none of which is ever
queried twice, and no `filter` processor can help: the label is on a metric you
want, with a value you cannot enumerate. The fix is at the keyboard, not in the
pipeline. `order.id` belongs on a *span*, where it costs one attribute on one
trace, and it is already there.

After each edit: `docker compose up -d <service>`.

## Tear down

```sh
docker compose down -v
```

## Ports

| | |
|---|---|
| 3001 | order-api |
| 4317 / 4318 / 13133 | Collector OTLP gRPC / HTTP / health |
| 24224 | Collector fluentforward |
| 8888 | Collector's own metrics |
| 3000 | Grafana |
| 3200 / 3100 / 9090 | Tempo / Loki / Prometheus |
| 5672 / 15672 / 15692 | RabbitMQ AMQP / management (guest:guest) / prometheus |
| 27017 | mongo1 |
| 9216 | mongodb_exporter |
| 9000 / 9001 | MinIO S3 / console (minioadmin:minioadmin) |

rabbitmq2/3 and minio2/3/4 publish nothing. They are reachable by service name
inside the compose network, which is all the Collector needs.

## Memory

`docker stats` steady state, ~2.9 GB across 19 containers:

```
mongo1     168MiB   mongo2     167MiB   mongo3     171MiB   mongodb-exporter  39MiB
rabbitmq1  128MiB   rabbitmq2  130MiB   rabbitmq3  125MiB
minio1     146MiB   minio2     107MiB   minio3     109MiB   minio4           104MiB
order-api  246MiB   order-worker 274MiB  otelcol   167MiB
grafana    283MiB   tempo      292MiB   loki      104MiB    prometheus        83MiB
```

Nine more containers than phase 2 for about 1 GB more. The Collector doubled
(74 → 167 MiB) — it is now holding a Prometheus scrape manager, six scrape
targets, a Mongo driver and a log tailer.

## Notes

- **`user: "0:0"` on the Collector.** The image runs as uid 10001, which can
  read neither `mongod.log` (mode 0600, owned by the mongodb user) nor a fresh
  root-owned named volume for `file_storage`. Every log-collecting agent hits
  this. In phase 5 the DaemonSet Collector runs as root for exactly the same
  reason, to read `/var/log/pods`.
- **`resourcedetection` uses `detectors: [env]`, not `system`.** Inside a
  container the `system` detector reports the *container ID* as `host.name`.
  It was tried: the ID changed on every Collector restart and every series
  forked. `host.name=laptop` comes from `OTEL_RESOURCE_ATTRIBUTES` instead.
- **The Erlang cookie must match on all three brokers.** Without it they form
  three one-node clusters and say nothing about it.
- **`--keyFile` needs mode 400 owned by uid 999**, which a macOS bind mount
  cannot provide. `mongo-keygen` writes it into a volume with the right
  ownership and exits.
- **`MINIO_PROMETHEUS_AUTH_TYPE=public`** or every scrape is a 401. The
  production answer is `mc admin prometheus generate` and a bearer token in the
  scrape config.
- **`fluentd-async: "true"`** on every service using the logging driver.
  Without it a container fails to start when the Collector is not yet
  listening, which on a cold `up` is all of them.
- The RabbitMQ `orders` queue is a **quorum** queue. Queue type is fixed at
  declaration; the app builds it from `QUEUE_TYPE`, and switching the value
  against an existing broker fails with `PRECONDITION_FAILED`. Use a fresh
  volume.
- `rabbitmq_raft_term` on the wire arrives as `rabbitmq_raft_term_total` in
  Prometheus. The OTLP-to-Prometheus counter renaming from phase 1 applies to
  metrics that were already Prometheus metrics, because they became OTLP in
  between.

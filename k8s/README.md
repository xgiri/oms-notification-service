# notification-service on Kubernetes

Phase 5 (infra parity) of this service's own build plan — see
`../notification-service-plan-status.md` §12. Web/worker split, same
reasoning as oms-main's own k8s setup — this service has 3
`@KafkaListener` consumers AND `NotificationRetryScheduler`, so unlike
customer-service/product-service (no consumers at all → single
`Deployment`), there's a real backlog-driven workload worth scaling
independently of the REST API.

## Files

| File | What it is |
|---|---|
| `00-configmap.yaml` | Non-secret env vars, shared by both roles |
| `01-secret.example.yaml` | **Template only** — copy it, fill in real values out of band, don't apply as-is. Plain values, not Vault-resolved — this service has no Vault integration (see that file's note) |
| `02-deployment-web.yaml` | REST API only — `APP_PROCESS_ROLE=web` stops the 3 consumers and the retry scheduler from starting |
| `03-service-web.yaml` | ClusterIP in front of the web Deployment only — the worker gets none |
| `04-hpa-web.yaml` | Scales the web role on CPU utilization |
| `05-deployment-worker.yaml` | The 3 consumers + `NotificationRetryScheduler` — `APP_PROCESS_ROLE=worker` |
| `06-scaledobject-worker.yaml` | KEDA — scales the worker role on Kafka consumer lag (3 triggers) + FAILED-notification backlog (1 trigger). Requires KEDA installed |
| `07-pdb.yaml` | PodDisruptionBudgets for both roles |
| `08-podmonitor.yaml` | Optional — Prometheus Operator scrape config (not in `kustomization.yaml` by default; see below) |
| `09-grafana-dashboard.yaml` | Optional — ConfigMap that auto-imports the notification-service Overview dashboard into Grafana via kube-prometheus-stack's sidecar (not in `kustomization.yaml` by default; see below). Includes sent/failed/dead-lettered by channel and type, retry-queue depth, and provider send latency — backed by `NotificationMetrics` |
| `kustomization.yaml` | Ties it all together; `kustomize edit set image` to point at your build |

No ingress manifest — external routing is oms-gateway's job, and this
service has no externally-routed endpoints wired up yet anyway (see this
repo's README "Scope" section: pure consumer-first, no `NotificationClient`
anywhere in OMS calls into it).

## Prerequisites

- **metrics-server** — required for `04-hpa-web.yaml` (CPU-based HPA).
  Most managed clusters (EKS, GKE, AKS) already run this.
- **KEDA** ([keda.sh](https://keda.sh)) — required for
  `06-scaledobject-worker.yaml`. Without it, the worker Deployment just
  stays at its `replicas: 1` floor and never scales up under load — see
  that manifest's own comment on why CPU alone wouldn't capture the right
  signal here (consumer lag and retry backlog aren't CPU-bound).
- **Postgres and Kafka reachable from the cluster** — `00-configmap.yaml`
  points at `notification-db` / `kafka` as in-cluster Service names by
  default. `notification-db` is this service's OWN database — not
  oms-main's, not customer-service's.
- **oms-main and customer-service reachable from the cluster** —
  `AUTH_SERVICE_JWKS_URI`/`ORDER_SERVICE_BASE_URL` point at oms-main's
  in-cluster Service (`oms-web`); `CUSTOMER_SERVICE_URL` points at
  customer-service's. Update the hosts if either isn't named that, or
  lives in a different namespace, in your cluster.
- **A real SMTP relay and real Twilio credentials** — `00-configmap.yaml`'s
  `SMTP_HOST` and `01-secret.example.yaml`'s `TWILIO_*` keys are
  placeholders. Mailpit (docker-compose.snippet.yml) is local-dev-only and
  has no place in a real deployment.

## Applying

```bash
# 1. Copy and fill in real secrets — never apply 01-secret.example.yaml directly
cp 01-secret.example.yaml 01-secret.yaml
# edit 01-secret.yaml with real values, then either:
kubectl apply -f 01-secret.yaml
# ...or add it to kustomization.yaml's resources list once filled in.

# 2. Point the image at your real build
kustomize edit set image your-registry.example.com/notification-service=your-registry.example.com/notification-service:$GIT_SHA

# 3. Apply everything else
kubectl apply -k .
```

## What scales on what

- **Web**: CPU utilization via a standard HPA (`04-hpa-web.yaml`),
  `minReplicas: 2` / `maxReplicas: 8`.
- **Worker**: KEDA (`06-scaledobject-worker.yaml`), `minReplicaCount: 1`
  (never zero — someone has to consume events and drain the retry queue)
  / `maxReplicaCount: 6`, driven by whichever of 4 triggers reports the
  highest demand:
  - Consumer lag on `oms-notification-service` (order events)
  - Consumer lag on `oms-notification-service-payment` (payment events,
    same topic, separate group — see `application.properties`' own
    comment on why)
  - Consumer lag on `oms-notification-service-customer` (customer events)
  - `FAILED` row count in the `notifications` table — a genuinely
    different signal from the 3 Kafka triggers: a provider outage can
    spike this with zero consumer lag, since ingestion isn't the
    bottleneck, sending is

## Metrics

Same shape as every other service in this system: a second container
port, `metrics` (8081) — `management.server.port` in
`application.properties`. Health and info live there too, which is why
the probes target `metrics`, not `http`. Never part of `03-service-web.yaml`,
so unreachable from outside the cluster, and the worker Deployment has no
Service at all — Prometheus reaches it by scraping the pod directly.

Two ways to scrape it:

- **Prometheus Operator**: apply `08-podmonitor.yaml`. Note its
  `podTargetLabels` — that's what lets the dashboard's "Web instances up"
  / "Worker instances up" panels tell the two roles apart on the
  synthetic `up` metric, which carries no Micrometer tags of its own.
- **Plain Prometheus** with pod discovery: already covered by the
  `prometheus.io/scrape`/`port`/`path` annotations on both Deployments'
  pod templates.

Every metric carries an `application` tag AND a `role` tag
(`management.metrics.tags.application`/`.role` in `application.properties`,
the latter set from `APP_PROCESS_ROLE`) — the `role` tag is what lets one
dashboard show both web and worker series without them being
indistinguishable.

### Grafana dashboard

`09-grafana-dashboard.yaml` gets you a **notification-service Overview**
dashboard — HTTP traffic, Kafka consumer lag by client, JVM heap, DB pool,
all filtered to `application="notification-service"` so it never mixes
with the other services' panels on the same Grafana instance.

A dedicated **Notifications** row covers what the original plan's §10
explicitly asked for: sent/failed/dead-lettered rate broken out by channel
and type, retry-queue depth (`notifications.pending.failed`, the same
`FAILED`-count `06-scaledobject-worker.yaml`'s KEDA trigger queries
directly — now visible in Grafana too, not just to KEDA), and provider
send-duration percentiles by channel. All backed by `NotificationMetrics`
— see that class's own Javadoc for the exact meter names/tags.

**One thing still not here**, per that same Javadoc: a time-to-delivery
panel (event received → notification sent). Closing that needs the
event's own receipt time threaded through
`NotificationService#processEvent`'s signature — a public-interface
change touching all 3 `@KafkaListener` consumers and their tests, not
something folded into `NotificationMetrics` itself.

Once kube-prometheus-stack and `08-podmonitor.yaml` are both applied:

```bash
kubectl apply -f 08-podmonitor.yaml
kubectl apply -f 09-grafana-dashboard.yaml
```

## What this doesn't change

No application code changes, except the small `application.properties` fix
that made `NotificationRetryScheduler`'s poll-interval/batch-size/max-attempts
env-overridable (they were hardcoded literals before this — this
ConfigMap couldn't have tuned them otherwise) — everything else here is
purely the orchestrator-side piece.

## Tuning before production use

Everything marked with a comment in the manifests is a starting point:

- `resources.requests`/`limits` on both Deployments (placeholder values)
- Web HPA `averageUtilization: 70`, `minReplicas`/`maxReplicas`
- Worker KEDA `lagThreshold: "50"` (all 3 Kafka triggers) and
  `targetQueryValue: "100"` (the FAILED-backlog trigger) — untuned
  starting points, same as oms-main's own KEDA triggers
- `NOTIFICATION_RETRY_MAX_ATTEMPTS`/`_BATCH_SIZE`/`_POLL_INTERVAL_MS` in
  `00-configmap.yaml` — see `application.properties`' own comment: picked
  as a starting point, not derived from measured failure data
- The Vault gap noted in `01-secret.example.yaml` — worth closing before
  this ever holds production credentials
- The missing time-to-delivery panel noted above — the `processEvent`
  signature change needed to close it, not something this k8s directory
  can do alone

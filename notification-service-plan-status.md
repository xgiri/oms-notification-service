# notification-service — Build Plan & Status

This maps every one of the 12 sections from the original notification-service
plan to what's actually been built, as of Phase 4 (SMS), the
`CustomerWelcome` and `ShipmentNotificationConsumer` consumers (**Phase 3
is now fully complete**), real per-type opt-out enforcement
(**Phase 2 is now fully complete**), the `CustomerClient`/`OrderClient`
contract tests, and the §6 retry/DLQ scheduler.
Status tags: **✅ Done**, **🟡 Partial**, **⬜ Not started**.

---

## §1 — Scope: what this service owns

**Plan:** Owns composing/delivering notifications, per-recipient channel
preferences, delivery status/history, retry and provider fallback, and
unsubscribe/opt-out state. Does **not** own the business decision of *when*
to notify (implicit in event subscriptions), customer contact info as
source of truth (reads from customer-service), or the channel
infrastructure itself (delegates to providers). Designed as a pure
consumer-first service — nothing else in OMS calls *into* it synchronously.

**Status: ✅ Done**
- ✅ Composing/delivering, delivery history, unsubscribe/opt-out state
- ✅ No `NotificationClient` needed anywhere else in OMS — holds as designed
- ✅ Retry — both the in-call resilience4j piece and the scheduled
  retry/DLQ piece (`NotificationRetryScheduler`) are now built (see §6)
- ⬜ Provider fallback isn't built — but that's a deliberate deferral per
  the plan's own §6 guidance ("don't build fallback logic before that's
  actually decided"), not a gap

## §2 — Event consumption: what triggers a notification

**Plan:** Subscribe to `oms.order.events`, `oms.payment.events` (if it
exists separately), `oms.shipment.events`, `oms.customer.events` as an
independent consumer group. Idempotency via a `processed_events` table
keyed on `(event_id, notification_type)`, checked/inserted in the same
transaction as sending.

**Status: ✅ Done**
- ✅ `OrderConfirmed`, `OrderCancelled`, `PaymentConfirmed`, `PaymentFailed`,
  `CustomerCreated` (→ `CustomerWelcome`), `ShipmentShipped`,
  `ShipmentDelivered`, `ShipmentReturned` — all eight event types wired,
  across four Kafka consumer groups (`OrderNotificationConsumer`,
  `PaymentNotificationConsumer`, `CustomerWelcomeConsumer`,
  `ShipmentNotificationConsumer`)
- ⚠️ Deviation from plan: `oms.payment.events` doesn't exist as its own
  topic yet, so `PaymentNotificationConsumer` currently reads a second,
  independent copy of `oms.order.events` instead
- ✅ `processed_events` idempotency table — built exactly as specified,
  same transaction as the `notifications` row
- ✅ `oms.customer.events` — subscribed (`CustomerWelcomeConsumer`), own
  dedicated consumer group even though it's currently the only listener on
  that topic — see `application.properties`' own comment on why
- ✅ `oms.shipment.events` — subscribed (`ShipmentNotificationConsumer`),
  own dedicated consumer group, same reasoning as `oms.customer.events`
  above. All three event types (`ShipmentShipped`/`Delivered`/`Returned`)
  handled in one `@KafkaListener` method — same "one logical concern, one
  group" shape as `OrderNotificationConsumer`'s own grouping of
  `OrderConfirmed`/`OrderCancelled`. **This was the last unwired topic —
  §2 is now fully done.**

## §3 — Recipient resolution: don't duplicate customer data

**Plan:** Two options — (a) synchronous `CustomerClient` call per event
(simpler, couples availability), or (b) a locally cached read model kept in
sync via `CustomerCreated`/`CustomerUpdated` events (more resilient, more
moving parts). Recommendation: start with (a), revisit (b) only if
customer-service's availability becomes a measured problem.

**Status: ✅ Done — matches the plan's own recommendation**
- `CustomerClient` built with the same resilient-client shape as the rest
  of the fleet (Resilience4j circuit breaker/retry/timeout)
- Phase 4 extended this to resolve `phone` alongside `email`, still via
  the same single client call
- Option (b), the local cache, deliberately not built — correct per the
  plan's own "don't build it preemptively" guidance
- One consumer (`CustomerWelcomeConsumer`) doesn't have the customerId gap
  at all — `CustomerCreatedEvent` carries `customerId` directly, since
  Customer is that event's own aggregate. `NotificationServiceImpl` still
  re-resolves the recipient via `CustomerClient` for this caller too,
  a known/accepted redundant hop kept for consistency — see that
  consumer's own Javadoc.

## §4 — Composition: templates, not hardcoded strings

**Plan:** One template per `(notification_type, channel, locale)`, locale
modeled from day one even if only `en` ships. A `NotificationComposer`
takes the event + resolved recipient and produces a channel-appropriate
payload.

**Status: ✅ Done**
- Thymeleaf templates, `<type>_<channel>_<locale>` naming, exactly as
  specified
- `locale` carried as a required parameter since Phase 1, even before a
  second locale existed — per the plan's own reasoning
- Phase 4 extended `NotificationComposer#compose` to take a `channel`
  param: EMAIL renders `.html`+`.txt`, SMS renders `.txt` only

## §5 — Provider abstraction: swappable, testable

**Plan:** `NotificationProvider` interface; one implementation per
channel/provider (`SesEmailProvider`, `TwilioSmsProvider`,
`FcmPushProvider`); never call a provider SDK directly from business
logic; each provider gets its own Resilience4j circuit breaker/retry
instance.

**Status: 🟡 Partial**
- ✅ `NotificationProvider` interface, `SmtpEmailProvider` (Phase 1),
  `TwilioSmsProvider` (Phase 4) — each with its own resilience4j instance
- ✅ Followed the plan's explicit checklist item: `minimum-number-of-calls`
  set explicitly for `twilioSmsProvider`, learned from the gap found twice
  before
- ⬜ No push provider (`FcmPushProvider` or equivalent) yet

## §6 — Delivery reliability

**Plan:** In-call Resilience4j retry for transient failures, **plus** a
separate notification-level retry/DLQ — a `status` column
(PENDING/SENT/FAILED/DEAD_LETTERED) and a scheduled retrier mirroring
`OutboxPublisher`'s poll-and-retry shape, distinct from Kafka's own
retry/DLT (which is for message-processing failures, not downstream-send
failures). Provider fallback (email → SMS on failure) explicitly flagged
as a product decision to make later, not to build speculatively.

**Status: ✅ Done**
- ✅ In-call resilience4j retry — done for both providers, including the
  permanent-vs-transient classification for `TwilioSmsProvider` (a bad
  phone number doesn't retry; a 5xx/429 does)
- ✅ **The scheduled retry/DLQ piece is now built** —
  `NotificationRetryScheduler`, same `FOR UPDATE SKIP LOCKED` /
  whole-batch-in-one-transaction shape as `OutboxPublisher`
  (customer-service/oms-main). Polls `FAILED` rows, reuses
  `NotificationService#resend`'s existing compose-send-record logic rather
  than duplicating it, and transitions a row to `DEAD_LETTERED` once its
  `retry_count` reaches a configurable `max-attempts` (default 5). Kafka's
  own retry/DLT deliberately stays out of this — this scheduler exists
  specifically so a provider outage doesn't turn into indefinite Kafka
  message redelivery for every event type sharing that partition. Built
  against — and verified compatible with — this service's source as of
  the `CustomerWelcome`/contract-test/`ApiException`-fix work above; no
  conflicts with any of it (`Notification`, `NotificationStatus`, and the
  V1 migration were all untouched by that work)
- ✅ Provider fallback correctly *not* built — matches the plan's own
  "don't over-engineer this speculatively" guidance
- ✅ **Fixed: `resend`'s missing precondition check.** Found (flagged, not
  fixed) while wiring up `NotificationRetryScheduler` — the interface
  Javadoc claimed "only valid from FAILED or DEAD_LETTERED" but the
  implementation didn't enforce it, so `POST /notifications/{id}/resend`
  would silently attempt (and record) a real send on a `PENDING` or
  already-`SENT` row too. Now throws a new
  `IllegalNotificationStateException`
  (`ErrorCode.ILLEGAL_NOTIFICATION_STATE`, `NT102`, `409 CONFLICT`) before
  any provider call or repository write — same shape as oms-main's
  `IllegalOrderStateException`/`IllegalPaymentStateException` for the same
  kind of situation. Didn't affect `NotificationRetryScheduler` itself (it
  only ever calls `resend` on rows it already confirmed are `FAILED`, so
  this precondition can never reject its own calls — verified by reading
  that scheduler's query), only the public endpoint's own guarantee to
  callers. Covered by a new `Resend` nested test class in
  `NotificationServiceImplTest` (rejects PENDING/SENT without touching the
  provider or repository; proceeds normally for FAILED/DEAD_LETTERED).

## §7 — Preferences & compliance

**Plan:** Per-customer, per-type, per-channel opt-in/opt-out. Unsubscribe
must work even if the rest of the system is down. Transactional vs.
marketing distinction modeled from the start, not retrofitted.

**Status: ✅ Done**
- ✅ `notification_preferences` table, real for both EMAIL and SMS as of
  Phase 4
- ✅ Unsubscribe endpoint — signed HMAC token, stateless, not
  JWT-authenticated, works independent of Kafka/other services being up —
  built to spec
- ✅ `transactional: true/false` modeled on `NotificationType` from day one
- ✅ Enforcement is now real, not a placeholder — `CUSTOMER_WELCOME` is
  `transactional = false` and its opt-out is genuinely enforced
  (`NotificationPreferenceServiceImplTest` covers the no-row/opted-in
  default, a stored opt-out, and a stored opt-in). Every
  order/payment/shipment type stays `transactional = true`, matching
  CAN-SPAM's own carve-out for transactional/relationship messages.
  ⚠️ This classification is the common industry-standard reading, applied
  as a starting position — it is NOT a substitute for actual legal
  sign-off before relying on it in a real deployment. See
  `NotificationType`'s own Javadoc for the full reasoning.

## §8 — Data model

**Plan:** Own dedicated database. `notifications` (one row per attempted
send), `processed_events` (idempotency), `notification_preferences`. No
outbox table needed unless something downstream needs to react to
`NotificationSent`/`NotificationFailed`.

**Status: ✅ Done**
- All three tables built exactly as specified
- No outbox table — correctly deferred; no real consumer for one exists
  yet, matching the plan's own "don't build it until there's a real
  consumer" guidance

## §9 — API surface: deliberately minimal

**Plan:** `GET /notifications?customerId=`, `GET /notifications/{id}`,
`POST /notifications/{id}/resend`, preference management endpoints, and a
token-based (not JWT) unsubscribe endpoint.

**Status: ✅ Done**
- All five endpoint shapes implemented in `NotificationController`
- One known limitation: `resend` reconstructs template variables from
  stored columns only — fine for `ORDER_CONFIRMED`, would under-populate a
  richer future notification type (documented on `resend`'s own Javadoc)

## §10 — Observability

**Plan:** OpenTelemetry + Tempo matching the fleet. Prometheus metrics +
a Grafana dashboard from day one. A notification-specific metric the other
services don't need: time-to-delivery (event received → notification
sent).

**Status: ✅ Done** *(one deliberate exception, see below)*
- ✅ OTel/Tempo wired to the same OTLP endpoint as the rest of OMS
- ✅ Prometheus scrape target — `k8s/08-podmonitor.yaml` (Prometheus
  Operator) plus the `prometheus.io/*` pod annotations already on both
  Deployments (plain-Prometheus fallback), same convention as the rest of
  the fleet
- ✅ Grafana dashboard — `k8s/09-grafana-dashboard.yaml` (HTTP traffic,
  Kafka consumer lag by client, JVM heap, DB pool, web/worker instance
  counts)
- ✅ **What §10 actually asked for is now backed by real metrics** —
  `NotificationMetrics` (the equivalent of customer-service/oms-main's
  `OutboxMetrics`) instruments `notifications.sent`/`.failed`/
  `.dead_lettered` (tagged by channel + type), `notifications.pending.failed`
  (retry-queue depth, a live gauge), and `notifications.send.duration`
  (provider latency, tagged by channel). Wired into
  `NotificationServiceImpl#sendAndRecord`/`#resend` and
  `NotificationRetryScheduler`. The dashboard's new "Notifications" row
  surfaces all of it
- ✅ Retry-queue depth is now visible to **both** KEDA (queries the
  `FAILED` count directly, `k8s/06-scaledobject-worker.yaml`) **and**
  Grafana/Prometheus (`notifications.pending.failed`) — same number, two
  consumers, same pattern as `OutboxMetrics`' own pending gauge
- ⬜ **Time-to-delivery is the one metric still not built**, by deliberate
  choice, not oversight: it needs the event's own receipt time threaded
  through `NotificationService#processEvent`'s signature (from each
  `@KafkaListener`'s `ConsumerRecord#timestamp()`) — a public-interface
  change touching all 3 consumers and their tests, not something that
  belonged folded silently into `NotificationMetrics`. Flagged in that
  class's own Javadoc as a follow-up

## §11 — Testing strategy

**Plan:** Unit tests for composer (template rendering) and
preference/opt-out logic. WireMock-based contract tests per provider —
called out as *critical*, since a provider API's response shape drifting
silently breaks delivery. An idempotency test (same event delivered twice
→ exactly one send) — explicitly flagged as the one test category to
prioritize writing *first*. Template rendering tests per locale/channel.

**Status: 🟡 Partial**
- ✅ Composer unit tests — both EMAIL and SMS, using a real
  `SpringTemplateEngine` against real template files (not mocked), which
  is what actually catches a template-resolution regression
- ✅ Idempotency test — built and prioritized exactly as the plan asked,
  its own nested test class
- ✅ Preference/opt-out logic — covered twice now, at two different
  layers: `PreferenceEnforcement` (in `NotificationServiceImplTest`)
  covers the orchestration behavior against a *mocked*
  `NotificationPreferenceService`; `NotificationPreferenceServiceImplTest`
  (new) is the first test of that service's own real branching logic —
  the transactional short-circuit (zero repository calls) and
  `CUSTOMER_WELCOME`'s genuine opt-out enforcement, now that a real
  non-transactional type exists to exercise it against.
- 🟡 Provider testing is real but **not literally WireMock-based** as the
  plan specified: `TwilioSmsProviderTest` exercises the resilience/retry
  logic against a mocked `SmsSender` seam instead. **This was actively
  evaluated this session, not left as an unexamined gap** — decided against
  for two reasons: (1) it's genuinely hard to do cleanly — twilio-java's
  `Request` always resolves its target host internally from a fixed
  region/edge template with no supported arbitrary-base-URL override (see
  [twilio-java#310](https://github.com/twilio/twilio-java/issues/310),
  open and unresolved since 2016, still true in the `12.1.1` pinned here);
  the only way in is a custom `HttpClient` that rewrites the already-built
  request's host before dispatching, plus refactoring `TwilioSmsSender` to
  accept an injected `TwilioRestClient` instead of the static `Twilio.init()`
  singleton it uses today — real production-code surgery, not test-only
  work. (2) **More importantly, it wouldn't buy much even if built** —
  unlike `OrderClientResponse`/`CustomerClientResponse` (hand-rolled DTOs
  this service maintains against endpoints another team owns — real drift
  risk), `TwilioSmsSender`'s response deserialization happens entirely
  inside the Twilio SDK, which Twilio itself tests. A WireMock test here
  would mostly re-verify Twilio's own SDK talks to Twilio's own API
  correctly, not this codebase's risk surface. Full reasoning now lives on
  `SmsSender`'s own Javadoc, including the officially-supported alternative
  (Twilio's test credentials/magic numbers) for anyone who wants genuine
  end-to-end confidence later, cheaper than reimplementing the transport
  layer — not built as part of this pass.
  - **Fixed a compile break in this test.** twilio-java `12.1.1` (pinned in
    `pom.xml`) removed `ApiException`'s old 5-arg constructor
    `(message, code, moreInfo, status, cause)` entirely — not deprecated,
    gone — in favor of an 8-arg one
    `(message, code, moreInfo, status, httpStatusCode, params, userError, cause)`.
    All five `new ApiException(...)` call sites in this test updated to the
    new constructor; also worth noting `getStatusCode()` (what
    `TwilioSmsProvider#isPermanentFailure` reads) returns the 4th arg
    (`status`), not the 5th (`httpStatusCode`) — they're now genuinely
    different fields, not renamed copies of each other. Was breaking
    `./mvnw test` for the whole module, not just this class.
- ✅ **`CustomerClient` contract/resilience tests — built.**
  `CustomerClientContractTest` (real WireMock, real deserialization: happy
  path, unknown-fields tolerance, a missing `phone`, 404 handling, and the
  `X-Internal-Service-Key` header actually being sent) +
  `CustomerClientResilienceTest` (retry-then-fail on 500, no retry on 404,
  timeout handling, circuit breaker opening after enough failures, 404s not
  counting toward the breaker).
- ✅ **`OrderClient` contract/resilience tests — built, same pattern.**
  `OrderClientContractTest` + `OrderClientResilienceTest`, same coverage
  shape as `CustomerClient`'s (this service's own `orderclient` package has
  its own copy of `OrderClientResponse`/`InternalServiceAuthInterceptor`,
  not oms-main's or shipment-service's — see those classes' own Javadoc —
  so this needed its own test, not a reuse of `CustomerClient`'s). Both
  clients now mirror `shipment-service`'s own
  `OrderClientContractTest`/`OrderClientResilienceTest` pattern exactly —
  same `*TestSupport` helper class shape, same resilience4j thresholds
  copied from `application.properties`, same real-HTTP-not-mocked
  philosophy. Both of this service's outbound clients now have this
  coverage.
- ✅ **`CustomerWelcomeConsumerTest` — built**, same dispatch-logic shape
  as `OrderNotificationConsumerTest`/`PaymentNotificationConsumerTest`
  (ignore other event types, tolerate unknown JSON fields), minus any
  `OrderClient`/`CustomerClient` mock — this consumer needs neither (see
  `CustomerWelcomeConsumer`'s own Javadoc).
- ✅ **`ShipmentNotificationConsumerTest` — built**, same shape again —
  three event types dispatched from one `@KafkaListener`, so three
  happy-path tests (one per type) plus the shared ignore/tolerate/
  propagate coverage, same as `OrderNotificationConsumerTest`'s own
  two-type version.

## §12 — Build phases

The plan's own 5-phase rollout sequence, and where each stands:

1. **Scaffold + one channel, one event type** — ✅ Done. Email, `OrderConfirmed` only, proved the skeleton end to end.
2. **Preferences + opt-out, before more event types** — ✅ Done. Signed unsubscribe token done; per-type enforcement is now real (`CUSTOMER_WELCOME` opt-out-able, order/payment/shipment stay required) — see §7.
3. **Remaining event types on email** — ✅ Done. `OrderCancelled`, `PaymentConfirmed`, `PaymentFailed`, `CustomerWelcome`, `ShipmentShipped`, `ShipmentDelivered`, `ShipmentReturned` all done — every event type in the plan's original §2 table is now wired.
4. **Additional channels (SMS, push)** — 🟡 Partial. SMS fully done (`TwilioSmsProvider`, templates, multi-channel fan-out in `NotificationServiceImpl`). Push not started — blocked on a `customer-service` schema change (no push-token field exists yet).
5. **Infra parity** — ✅ Done. k8s manifests (web/worker split, KEDA-driven worker autoscaling on consumer lag + retry backlog, PDBs, PodMonitor), Prometheus scrape target, and a Grafana dashboard with real notification-specific metrics (sent/failed/dead-lettered by channel and type, retry-queue depth, provider send latency, via the new `NotificationMetrics` class) are all built — see `k8s/README.md`. Only exception: no time-to-delivery panel, a deliberate deferral pending a `processEvent` signature change — see §10.

---

## Summary

| # | Section | Status |
|---|---|---|
| 1 | Scope | ✅ Done |
| 2 | Event consumption | ✅ Done |
| 3 | Recipient resolution | ✅ Done |
| 4 | Composition | ✅ Done |
| 5 | Provider abstraction | 🟡 Partial |
| 6 | Delivery reliability | ✅ Done |
| 7 | Preferences & compliance | ✅ Done |
| 8 | Data model | ✅ Done |
| 9 | API surface | ✅ Done |
| 10 | Observability | ✅ Done (time-to-delivery deliberately deferred) |
| 11 | Testing strategy | 🟡 Partial |
| 12 | Build phases | 🟡 Partial (4 of 5 phases fully closed) |

**§6's retry/DLQ gap is now closed** — `NotificationRetryScheduler` polls
`FAILED` notifications and either retries them (via the existing `resend`
logic) or dead-letters them once `retry_count` hits the configurable
`max-attempts`, same `FOR UPDATE SKIP LOCKED` shape as `OutboxPublisher`.

**Phase 5 (infra parity) is now fully built**, including the metrics gap
that pass initially left open — k8s manifests, a Prometheus scrape
target, and a Grafana dashboard all exist (see `k8s/README.md`). Three
things worth flagging about this work overall:
1. It surfaced (and fixed) two small pre-existing infra gaps unrelated to
   what was asked for: `NotificationRetryScheduler`'s config was hardcoded
   rather than env-overridable (couldn't have been tuned via the new
   ConfigMap otherwise), and `docker-compose.snippet.yml` was never
   updated with `TWILIO_*` env vars back when SMS shipped in Phase 4 — a
   real gap, now closed.
2. **`NotificationMetrics` (new) closes the dashboard gap for real** — the
   equivalent of customer-service/oms-main's `OutboxMetrics`, instrumented
   into `NotificationServiceImpl#sendAndRecord`/`#resend` and
   `NotificationRetryScheduler`. The Grafana dashboard's new "Notifications"
   row now shows sent/failed/dead-lettered by channel and type,
   retry-queue depth as a live gauge, and provider send-duration
   percentiles — genuinely backed by Prometheus series, not placeholder
   panels.
3. One metric was deliberately NOT built as part of this: time-to-delivery.
   Closing it needs a `NotificationService#processEvent` signature
   change (threading the Kafka record's own timestamp through from each
   consumer) — a public-interface change touching all 3 consumers and
   their tests, judged too invasive to fold in silently alongside a
   metrics class. Flagged as a named follow-up in `NotificationMetrics`'
   own Javadoc rather than either done without asking or dropped
   unmentioned.

**What's newly the most consequential open item:** §5's missing push
provider is now the last real blocker on Phase 4, and — unlike everything
closed in this session, which stayed within this repo — it's a cross-repo
change (a `customer-service` schema addition). The
`NotificationService#resend` precondition bug (found while building §6) is
now fixed — see §6 above. The time-to-delivery metric (found while
building §10) remains open, by choice, as a named follow-up rather than a
silent gap.
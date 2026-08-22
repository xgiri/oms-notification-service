# notification-service — Build Plan & Status

This maps every one of the 12 sections from the original notification-service
plan to what's actually been built, as of Phase 4 (SMS, and push — fully
built here and its `customer-service` dependency now shipped too, see §5),
the
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

**Status: ✅ Done** *(functionally inert until a `customer-service` change — see below)*
- ✅ `NotificationProvider` interface, `SmtpEmailProvider` (Phase 1),
  `TwilioSmsProvider` (Phase 4) — each with its own resilience4j instance
- ✅ Followed the plan's explicit checklist item: `minimum-number-of-calls`
  set explicitly for `twilioSmsProvider`, learned from the gap found twice
  before
- ✅ **`FcmPushProvider` — built AND tested, deliberately gated off**
  (`app.notification.push.enabled=false` by default). Built as far as
  this side of the system can be built without a `customer-service`
  change — see the paragraph below this table. Structurally a full mirror
  of `TwilioSmsProvider` (`PushSender`/`FcmPushSender` split, same
  programmatic resilience4j composition, same permanent-vs-transient
  classification done inside `doSend` rather than via
  `ignore-exceptions`, own `fcmPushProvider` resilience4j instance).
  `FcmConfig` initializes `FirebaseApp` at startup, gated behind the same
  property so an unconfigured deployment never needs real FCM credentials
  just to boot. All 8 wired `NotificationType`s now have a
  `<type>_push_en.txt` template (title comes from
  `NotificationComposerImpl#subjectFor`, reused as the push notification's
  title — no `NotificationRequest` field needed just for push).
  `CustomerClientResponse` gained a `pushToken` field ahead of the schema
  change that will populate it — see that record's own Javadoc.
  `NotificationServiceImpl#recipientAddressFor`'s PUSH case now returns
  `customer.pushToken()` instead of throwing, so this degrades safely
  (skipped, same as SMS-without-a-phone) rather than crashing the moment
  someone enables the flag before the upstream field exists.
  **`FcmPushProviderTest` (new) closes what was previously called
  "genuinely untestable end to end"** — that framing turned out to be
  imprecise: the resilience/classification logic (the actual thing worth
  testing, same as `TwilioSmsProviderTest`) *was* testable via the
  `PushSender` seam, which already existed specifically for this — it
  just hadn't been written yet. One real wrinkle `TwilioSmsProviderTest`
  didn't have: `FirebaseMessagingException` has no public constructor at
  all (verified against firebase-admin `9.10.0`'s actual source — even
  its `@VisibleForTesting` constructor is package-private and doesn't
  accept a `MessagingErrorCode`), so every test case mocks the exception
  itself (`Mockito.mock(FirebaseMessagingException.class)`) rather than
  constructing a real one — see that test's own Javadoc for the full
  reasoning and why this still faithfully exercises
  `FcmPushProvider#isPermanentFailure`. Covers all 4 permanent FCM error
  codes (`UNREGISTERED`, `INVALID_ARGUMENT`, `SENDER_ID_MISMATCH`,
  `THIRD_PARTY_AUTH_ERROR`) not retrying, a transient one (`QUOTA_EXCEEDED`)
  retrying, and a null error code (no structured FCM response at all)
  treated as transient.
  **What was the last genuine gap — a device push token to send to at
  all — is now closed on the upstream side too.** `customer-service`
  shipped `pushToken` (`V4__add_push_token_to_customers.sql`, exposed on
  `CustomerResponse`, set via its own dedicated
  `PUT /customers/{id}/push-token` endpoint — deliberately not folded
  into the general customer update, same reasoning as this service's own
  `TwilioSmsProvider`/`FcmPushProvider` split into single-purpose pieces).
  Push is still gated off here (`app.notification.push.enabled=false`,
  both in `application.properties` and `k8s/00-configmap.yaml`) — but
  that's now a deliberate, reversible deploy-time decision (flip the flag
  once real FCM credentials are configured), not a wait on missing data.
  See this doc's closing summary.

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

**Status: ✅ Done**
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
- ✅ **Time-to-delivery — now built.** `NotificationService#processEvent`
  gained a new `eventTimestampMillis` param (the triggering Kafka record's
  own `ConsumerRecord#timestamp()` — broker-assigned `CreateTime` by
  default, not this method's own call time), threaded through from all 4
  `@KafkaListener` consumers (`OrderNotificationConsumer`,
  `PaymentNotificationConsumer`, `CustomerWelcomeConsumer`,
  `ShipmentNotificationConsumer` — this doc's own count was stale at "3"
  before `ShipmentNotificationConsumer` shipped, now fixed here too) at
  all 8 of their `processEvent` call sites. `NotificationMetrics` gained
  `recordTimeToDelivery`, called from `sendAndRecord`'s success branch
  only — measures "did the customer actually get notified promptly," this
  service's own plan's stated reason for wanting this metric, and
  deliberately distinct from `notifications.send.duration` (which only
  measures the provider call itself — a message sitting in a consumer-lag
  backlog for 10 minutes before this service even picks it up would show
  fast provider latency but slow time-to-delivery). Deliberately **NOT**
  wired into `resend`/`NotificationRetryScheduler` — there's no original
  event timestamp persisted on the `Notification` row for a retry to
  recover, and this metric is specifically about the FIRST delivery
  attempt; a retried notification's timing is already visible via
  `notifications.pending.failed`/`notifications.dead_lettered`, a distinct
  signal. Documented on `resend`'s own interface Javadoc. Covered by a new
  `Metrics` nested test class in `NotificationServiceImplTest` (asserts the
  exact duration via a fixed `Clock`, and that a failed send does NOT
  record it). A Grafana panel for it (`Time to delivery (p50 / p95 / p99)
  by channel`, same shape as the existing send-duration panel) is now in
  the "Notifications" row too — `k8s/09-grafana-dashboard.yaml`'s own
  header comment updated accordingly.

## §11 — Testing strategy

**Plan:** Unit tests for composer (template rendering) and
preference/opt-out logic. WireMock-based contract tests per provider —
called out as *critical*, since a provider API's response shape drifting
silently breaks delivery. An idempotency test (same event delivered twice
→ exactly one send) — explicitly flagged as the one test category to
prioritize writing *first*. Template rendering tests per locale/channel.

**Status: ✅ Done** *(one deliberate exception, see below — same shape as §10)*
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
- ✅ **`FcmPushProviderTest` (new) — closes the last provider-testing
  gap.** Same mocked-collaborator shape as `TwilioSmsProviderTest`, real
  (not mocked) resilience4j registries, but with a genuine wrinkle that
  test didn't have: `FirebaseMessagingException` has no public
  constructor at all in firebase-admin `9.10.0` (confirmed against its
  actual source), so every exception here is
  `Mockito.mock(FirebaseMessagingException.class)` with
  `getMessagingErrorCode()` stubbed, not constructed for real. Covers all
  4 permanent FCM error codes, one transient code, and a null error code
  (raw connection failure) treated as transient — mirrors
  `TwilioSmsProviderTest`'s coverage shape exactly. See §5.
  **Correction:** this file initially failed 8/8 of its own mocked-exception
  tests with `UnfinishedStubbingException` on a real `mvn test` run — every
  failing test called `mockException(...)` nested inside an already-open
  `when(pushSender.send(...)).thenThrow(...)` chain, which corrupts
  Mockito's stubbing state (calling `when()` a second time before the
  first one's `.thenReturn()/.thenThrow()` completes). Fixed by extracting
  the mocked exception to a local variable before opening the outer
  `when()`, in all 8 affected tests — now noted in this file's own class
  Javadoc as a pitfall for future edits. Caught only because a real build
  was run outside this environment (no Maven Central access here to catch
  it directly) — a reminder that "brace-balanced and diff-clean" was never
  a substitute for an actual compile/test run on anything delivered from
  this environment.

## §12 — Build phases

The plan's own 5-phase rollout sequence, and where each stands:

1. **Scaffold + one channel, one event type** — ✅ Done. Email, `OrderConfirmed` only, proved the skeleton end to end.
2. **Preferences + opt-out, before more event types** — ✅ Done. Signed unsubscribe token done; per-type enforcement is now real (`CUSTOMER_WELCOME` opt-out-able, order/payment/shipment stay required) — see §7.
3. **Remaining event types on email** — ✅ Done. `OrderCancelled`, `PaymentConfirmed`, `PaymentFailed`, `CustomerWelcome`, `ShipmentShipped`, `ShipmentDelivered`, `ShipmentReturned` all done — every event type in the plan's original §2 table is now wired.
4. **Additional channels (SMS, push)** — 🟡 Partial. SMS fully done (`TwilioSmsProvider`, templates, multi-channel fan-out in `NotificationServiceImpl`). Push (`FcmPushProvider`) is fully built and its upstream dependency is resolved — `customer-service` now exposes `pushToken` (see §5) — but still deliberately gated off (`app.notification.push.enabled=false`) pending real FCM credentials and a deliberate decision to flip the flag. No code or data blocker remains; this is now purely an operational go/no-go.
5. **Infra parity** — ✅ Done. k8s manifests (web/worker split, KEDA-driven worker autoscaling on consumer lag + retry backlog, PDBs, PodMonitor), Prometheus scrape target, and a Grafana dashboard with real notification-specific metrics (sent/failed/dead-lettered by channel and type, retry-queue depth, provider send latency, and now time-to-delivery, via the `NotificationMetrics` class) are all built — see `k8s/README.md`.

---

## Summary

| # | Section | Status |
|---|---|---|
| 1 | Scope | ✅ Done |
| 2 | Event consumption | ✅ Done |
| 3 | Recipient resolution | ✅ Done |
| 4 | Composition | ✅ Done |
| 5 | Provider abstraction | ✅ Done (push gated off pending FCM credentials/a deploy decision — not missing data) |
| 6 | Delivery reliability | ✅ Done |
| 7 | Preferences & compliance | ✅ Done |
| 8 | Data model | ✅ Done |
| 9 | API surface | ✅ Done |
| 10 | Observability | ✅ Done |
| 11 | Testing strategy | ✅ Done (WireMock-for-providers deliberately declined, see §11) |
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
3. Time-to-delivery, the one metric deliberately NOT built in that earlier
   pass, is now built too — see §10. It needed exactly the
   `NotificationService#processEvent` signature change flagged as the
   reason for deferring it (threading the Kafka record's own timestamp
   through from all 4 consumers), done as a separate, later pass rather
   than folded into the metrics-class work above — including its own
   Grafana panel, added in the same pass.

**What's newly the most consequential open item:** §5's push provider is
now built AND tested as far as this repo alone can take it, AND its
cross-repo blocker is resolved — `customer-service` shipped `pushToken`
(schema, endpoint, and response field), closing the one piece that
genuinely couldn't be built from this repo alone. §11's last gap
(`FcmPushProviderTest`) is closed the same way `TwilioSmsProviderTest`
closed its SMS equivalent — see §5/§11 above for the one real difference
between them (no public `FirebaseMessagingException` constructor to work
with). The `NotificationService#resend` precondition bug (found while
building §6) is now fixed — see §6 above. The time-to-delivery metric
(found while building §10) is now built too, including its own Grafana
panel — see §10. With all of that closed, **there is no remaining code or
data gap anywhere in this doc** — Phase 4's only open item is now a
deliberate operational decision (flip `app.notification.push.enabled`
once real FCM credentials are configured), not a wait on more work.
Everything else still open (§5's push-fallback question, §11's
WireMock-for-Twilio/FCM decision) is a deliberate product decision, not a
gap.
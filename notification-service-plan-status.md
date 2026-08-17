# notification-service — Build Plan & Status

This maps every one of the 12 sections from the original notification-service
plan to what's actually been built, as of Phase 4 (SMS) plus the newly
added `CustomerWelcome` consumer (Phase 3's last email-channel event type).
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

**Status: 🟡 Partial**
- ✅ Composing/delivering, delivery history, unsubscribe/opt-out state
- ✅ No `NotificationClient` needed anywhere else in OMS — holds as designed
- ⬜ Provider fallback and the scheduled retry/DLQ piece of "retry" aren't
  built yet (see §6)

## §2 — Event consumption: what triggers a notification

**Plan:** Subscribe to `oms.order.events`, `oms.payment.events` (if it
exists separately), `oms.shipment.events`, `oms.customer.events` as an
independent consumer group. Idempotency via a `processed_events` table
keyed on `(event_id, notification_type)`, checked/inserted in the same
transaction as sending.

**Status: 🟡 Partial**
- ✅ `OrderConfirmed`, `OrderCancelled`, `PaymentConfirmed`, `PaymentFailed`,
  `CustomerCreated` (→ `CustomerWelcome`) all wired — three Kafka consumer
  groups (`OrderNotificationConsumer`, `PaymentNotificationConsumer`,
  `CustomerWelcomeConsumer`)
- ⚠️ Deviation from plan: `oms.payment.events` doesn't exist as its own
  topic yet, so `PaymentNotificationConsumer` currently reads a second,
  independent copy of `oms.order.events` instead
- ✅ `processed_events` idempotency table — built exactly as specified,
  same transaction as the `notifications` row
- ✅ `oms.customer.events` — subscribed (`CustomerWelcomeConsumer`), own
  dedicated consumer group even though it's currently the only listener on
  that topic — see `application.properties`' own comment on why
- ⬜ `oms.shipment.events` — not subscribed to yet; blocked on
  `shipment-service`'s event shapes

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

**Status: 🟡 Partial — this is the clearest open gap**
- ✅ In-call resilience4j retry — done for both providers, including the
  permanent-vs-transient classification for `TwilioSmsProvider` (a bad
  phone number doesn't retry; a 5xx/429 does)
- ⬜ **The scheduled retry/DLQ piece isn't built.** A `FAILED` notification
  today has no independent retrier — this was flagged explicitly as a gap
  early in Phase 4 and hasn't been picked up since
- ✅ Provider fallback correctly *not* built — matches the plan's own
  "don't over-engineer this speculatively" guidance

## §7 — Preferences & compliance

**Plan:** Per-customer, per-type, per-channel opt-in/opt-out. Unsubscribe
must work even if the rest of the system is down. Transactional vs.
marketing distinction modeled from the start, not retrofitted.

**Status: 🟡 Partial**
- ✅ `notification_preferences` table, real for both EMAIL and SMS as of
  Phase 4
- ✅ Unsubscribe endpoint — signed HMAC token, stateless, not
  JWT-authenticated, works independent of Kafka/other services being up —
  built to spec
- ✅ `transactional: true/false` modeled on `NotificationType` from day one
- ⬜ Every type is currently `transactional = true` as a placeholder —
  opt-out enforcement is short-circuited to "always send" regardless of a
  stored preference, pending real legal sign-off on which types can
  actually be opted out of

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

**Status: 🟡 Partial**
- ✅ OTel/Tempo wired to the same OTLP endpoint as the rest of OMS
- ⬜ No Prometheus scrape target or Grafana dashboard yet — this is Phase
  5 (infra parity), not started
- ⬜ No time-to-delivery metric yet

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
- ✅ Preference/opt-out logic — covered (`PreferenceEnforcement` nested
  class)
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

## §12 — Build phases

The plan's own 5-phase rollout sequence, and where each stands:

1. **Scaffold + one channel, one event type** — ✅ Done. Email, `OrderConfirmed` only, proved the skeleton end to end.
2. **Preferences + opt-out, before more event types** — 🟡 Partial. Signed unsubscribe token done; per-type enforcement still a placeholder pending legal sign-off.
3. **Remaining event types on email** — 🟡 Partial. `OrderCancelled`, `PaymentConfirmed`, `PaymentFailed`, `CustomerWelcome` done. Shipment lifecycle events still open, blocked on `shipment-service`'s event shape.
4. **Additional channels (SMS, push)** — 🟡 Partial. SMS fully done (`TwilioSmsProvider`, templates, multi-channel fan-out in `NotificationServiceImpl`). Push not started — blocked on a `customer-service` schema change (no push-token field exists yet).
5. **Infra parity** — ⬜ Not started. No k8s manifests, no Prometheus scrape target, no Grafana dashboard.

---

## Summary

| # | Section | Status |
|---|---|---|
| 1 | Scope | 🟡 Partial |
| 2 | Event consumption | 🟡 Partial |
| 3 | Recipient resolution | ✅ Done |
| 4 | Composition | ✅ Done |
| 5 | Provider abstraction | 🟡 Partial |
| 6 | Delivery reliability | 🟡 Partial |
| 7 | Preferences & compliance | 🟡 Partial |
| 8 | Data model | ✅ Done |
| 9 | API surface | ✅ Done |
| 10 | Observability | 🟡 Partial |
| 11 | Testing strategy | 🟡 Partial |
| 12 | Build phases | 🟡 Partial (1 of 5 phases fully closed) |

**The single most consequential open item** is §6's scheduled retry/DLQ —
every other gap here is either a deferred phase (push, infra) or a
placeholder pending an external decision (legal sign-off on opt-out). The
retry/DLQ gap is different: it's inside Phase 1's own scope and means a
`FAILED` notification today has no path back to `SENT` without a human
manually hitting `POST /resend`.
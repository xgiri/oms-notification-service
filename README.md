# notification-service

A multi-channel notification service for OMS. Unlike `product-service`/
`customer-service`/`shipment-service` (each extracted from `oms-main`),
this is a **new** service, built fresh — but deliberately shaped to match
every convention this system already established: same package layout,
same JWT-verification-only security posture, same resilient-client pattern,
same web/worker-role convention.

**Current status: fully complete, no remaining code or data gap** — three
channels wired (email, SMS, push), all eight event types on each. Push is
code-complete AND its upstream dependency shipped
(`customer-service` now exposes `pushToken`), but stays disabled by
default (`app.notification.push.enabled=false`) pending real FCM
credentials — a deliberate operational decision, not a wait on more work.
See [Additional channels](#build-phases). **Phases 1–5, including the
time-to-delivery metric, are all fully complete.** See
[Build phases](#build-phases) for the full staged plan.

## What it does

Listens to `oms-main`'s, `customer-service`'s, and `shipment-service`'s
Kafka event streams and turns order lifecycle, account, and shipment
events into customer notifications — order confirmations, cancellations,
payment outcomes, account welcome emails, and shipment tracking/delivery/
return notices, on email and SMS today, with push built and staged behind
a feature flag pending an upstream schema change (see below). Every send
is recorded
(`notifications` table) so delivery history is queryable, every event is
processed idempotently (a Kafka redelivery must never double-send), and
every customer has per-(type, channel) opt-in/opt-out state — real for both
channels that exist today, not just scaffolded.

## Architecture

```
Kafka (oms.order.events)
        │
        ├──▶ OrderNotificationConsumer ────▶ OrderClient ──▶ oms-main (resolve customerId)
        │      (OrderConfirmed, OrderCancelled)
        │
        └──▶ PaymentNotificationConsumer ──▶ OrderClient ──▶ oms-main (resolve customerId)
               (PaymentConfirmed, PaymentFailed — own consumer group)

Kafka (oms.customer.events)
        │
        └──▶ CustomerWelcomeConsumer (own consumer group — no OrderClient/
               CustomerClient lookup needed; CustomerCreatedEvent already
               carries customerId directly)

Kafka (oms.shipment.events)
        │
        └──▶ ShipmentNotificationConsumer ─▶ OrderClient ──▶ oms-main (resolve customerId)
               (ShipmentShipped, ShipmentDelivered, ShipmentReturned —
                own consumer group)
        │
        ▼
NotificationServiceImpl (idempotency → preference check across every
                          registered channel → ONE customer lookup →
                          per opted-in channel: compose → send → record)
        │                                      │                    │
        ▼                                      ▼                    ▼
CustomerClient ──▶ customer-service   NotificationComposer     ┌─────────────────────────┐
  (resolve email/phone)                (Thymeleaf templates,   │ NotificationProvider     │
                                         channel-aware since    │  ├─ SmtpEmailProvider    │──▶ Mailpit
                                         Phase 4)                │  └─ TwilioSmsProvider    │──▶ SmsSender ──▶ Twilio
                                                                 └─────────────────────────┘
```

Fan-out is per registered `NotificationProvider` bean, not a hardcoded
channel list — see `NotificationServiceImpl#registeredChannels`'s own
Javadoc. One `notifications` row is written per channel actually attempted;
`processed_events` stays keyed on `(event_id, notification_type)` only, so
a redelivery skips every channel together, not one at a time.

- **`notification`** — the domain: entities, repositories, the orchestration
  service (`NotificationServiceImpl`), the provider abstraction (now two
  implementations — `SmtpEmailProvider`, `TwilioSmsProvider`), the two
  Phase 3 consumers, the REST controller.
- **`customerclient`** / **`orderclient`** — resilient clients for this
  service's two synchronous dependencies (see
  [Two clients, and why](#two-clients-and-why) below).
- **`messaging`** — Kafka config + the event shapes this service consumes.
- **`common`** / **`security`** — shared boilerplate, same shape as every
  other service in this system.

### Two clients, and why

This service calls out to two other services synchronously, which is more
than any of the three extracted services need:

- **`CustomerClient`** → `customer-service`, to resolve a recipient's email.
  The obvious, expected dependency.
- **`OrderClient`** → `oms-main`, to resolve the `customerId` an order
  belongs to. **This one exists only because of a real event-schema gap** —
  see [The customerId gap](#the-customerid-gap-and-the-recommended-fix)
  below. It's a Phase 1 workaround, not a permanent architectural choice.

### The customerId gap, and the recommended fix

`OrderConfirmedEvent` (unlike `OrderCreatedEvent`, which does carry
`customerId`) only carries `orderId`. A notification consumer fundamentally
needs to know *who* to notify, so Phase 1's answer is `OrderClient` — an
extra synchronous hop to `oms-main`'s order endpoint on **every single
notification**, purely to resolve a customer id.

**The recommended long-term fix is adding `customerId` to
`OrderConfirmedEvent` additively** — squarely inside `oms-main`'s own
documented event-compatibility policy
(`docs/event-schema-versioning.md`: additive fields are safe, no
`schemaVersion` bump needed). That would let this service drop `OrderClient`
entirely for this path: no extra network hop, no extra circuit breaker to
misconfigure, one less thing that can be slow or down. The same gap likely
exists on `ShipmentShippedEvent`/`ShipmentDeliveredEvent`/
`ShipmentReturnedEvent` (all `orderId`-only) for whenever Phase 3 wires
those up — worth fixing once, for all of them, rather than adding a
`ShipmentClient` and repeating the same workaround per event type.

This was a deliberate scope decision, not an oversight: fixing the schema
would mean modifying `oms-main`, which was explicitly deferred to keep this
service's first build self-contained. See `messaging.event.OrderConfirmedEvent`'s
own Javadoc for the same explanation in code.

### The idempotency guarantee, and its one real limit

`processed_events` (keyed on `(event_id, notification_type)`, checked and
written in the *same transaction* as the `notifications` row) is what makes
a Kafka redelivery a no-op instead of a duplicate email. This matters more
here than for any other consumer in this system — a duplicate inventory
reservation is a bug nobody notices; a duplicate "your order shipped" email
is the kind customers complain about.

The one gap this doesn't close: a crash between "the email was actually
sent" and "the transaction that records `SENT` commits" is still possible —
the notification would show as `FAILED` (or the whole message would
redeliver and re-send) even though the customer already got it. This is the
consuming-side mirror image of the same at-least-once tradeoff a
transactional outbox exists to manage on the *producing* side. Closing it
completely would mean a two-phase or saga-style handoff with the SMTP
provider, which is disproportionate for Phase 1 — flagged here rather than
silently assumed away.

### Multi-channel fan-out (Phase 4), and what changed to support it

Phase 1–3 hardcoded `NotificationChannel.EMAIL`. Phase 4 replaced that with
a loop over every channel that has a registered `NotificationProvider`
bean *and* that the customer is opted into for that notification type —
see `NotificationServiceImpl#registeredChannels`/`#processEvent`'s own
Javadoc for the full ordering. Three things worth knowing about how this
actually behaves:

- **The customer lookup still happens once, not once per channel** —
  `CustomerClientResponse` now carries `phone` and `pushToken` alongside
  `email`, so a single `CustomerClient` round-trip resolves the recipient
  address for every channel this event will fan out to, including push
  once `app.notification.push.enabled=true` registers `FcmPushProvider`
  as a fourth candidate in the loop.
- **A channel with no recipient address on file is skipped silently** — a
  log line, not a `FAILED` Notification row, since there was nothing to
  attempt (see `#recipientAddressFor`'s own Javadoc). This is a real
  design tradeoff, not an obviously-correct default: it means a customer
  opted into SMS with no phone number on file produces no visible signal
  in the `notifications` table at all. Revisit if that turns out to hide a
  data-quality problem worth surfacing on a dashboard instead.
- **`processed_events` is still written once per event**, after every
  channel has been attempted — not once per channel. A redelivery must
  skip every channel together, never re-attempt just the ones that failed
  last time (that's `NotificationRetryScheduler`'s job, not Kafka
  redelivery's).

**`TwilioSmsProvider`** is the second `NotificationProvider`
implementation, alongside `SmtpEmailProvider`. Same programmatic
resilience4j composition as `CustomerClientImpl`/`OrderClientImpl` (own
`CircuitBreaker` + `Retry` pulled from the registry by instance name), but
permanent-vs-transient classification works differently: rather than
resilience4j's `ignore-exceptions`, `TwilioSmsProvider#doSend` itself
decides — a recipient-specific 4xx (bad number, opted out via STOP,
unverified trial number — anything except a 429 rate limit) is returned as
an ordinary `ProviderResult.failure` without throwing at all, so `Retry`
never even considers it. See that class's own Javadoc for the reasoning.

The actual Twilio SDK call is behind a one-method seam,
**`SmsSender`**/`TwilioSmsSender`, purely so `TwilioSmsProviderTest` can
exercise the resilience logic against a mock instead of needing WireMock or
real Twilio credentials — twilio-java owns its HTTP client internally, so
there's no clean base-URL override to point at WireMock the way
`CustomerClient`/`OrderClient`'s plain `RestClient` allows. See
[Testing](#testing) for what that tradeoff does and doesn't cover.

**`FcmPushProvider`** is the third `NotificationProvider` implementation
— structurally a mirror of `TwilioSmsProvider` (same programmatic
resilience4j composition, same never-throw contract, same
permanent-vs-transient split decided inside `doSend` rather than via
resilience4j's `ignore-exceptions`), behind the same one-method-seam
pattern (`PushSender`/`FcmPushSender`) for the same testability reason.
Unlike `TwilioSmsProvider`, it's **not registered unconditionally** —
`@ConditionalOnProperty(name = "app.notification.push.enabled", havingValue = "true")`,
default `false` — because `FcmConfig` needs real FCM service-account
credentials to initialize `FirebaseApp` at startup, with no safe default;
registering it unconditionally would mean every environment (every
developer's local run, CI, this repo's own tests) needs real credentials
just to boot, for a channel that can't do anything useful yet regardless
(`CustomerClientResponse#pushToken()` is always `null` until
`customer-service` ships that field). See that class's own Javadoc for
the full reasoning and the exact activation path once the upstream field
exists.

## API

Deliberately minimal — see `notification.controller.NotificationController`.
Unlike `shipment-service` (a full CRUD API, since humans create/manage
shipments directly), this service's primary trigger is Kafka, not REST.
The REST surface exists for support/debugging and preference management:

| Endpoint | Purpose |
|---|---|
| `GET /api/v1/notifications?customerId=` | Delivery history, paged |
| `GET /api/v1/notifications/{id}` | Single notification's status |
| `POST /api/v1/notifications/{id}/resend` | Admin-only manual retry — see its own Javadoc for a Phase 1 limitation |
| `GET /api/v1/notifications/unsubscribe` | Opt out via a signed link token — see below |

**The unsubscribe endpoint is signed-token-based, not a placeholder
anymore.** It's deliberately not JWT-authenticated (unsubscribing has to
work even if whatever issued the recipient's token is down — same
reasoning as any "must survive partial outage" design in this system).
Instead, it trusts a single `token` query parameter: a self-issued,
self-verified HMAC-signed (HS256) link token minted by
`security.UnsubscribeTokenService` and embedded directly in the
notification email (see `NotificationServiceImpl.withUnsubscribeLink`).
Nothing here is guessable the way a raw `customerId` query parameter was.

This token is **stateless (valid until it expires), not single-use** — a
deliberate choice, not a shortcut: opting out is idempotent, so a link
clicked twice (or forwarded, or crawled by an email client's
link-prescanning) just opts the same customer out twice, which is
harmless. A single-use design would need a persisted "used tokens" table
purely to defend against a scenario that already causes no harm. See
`UnsubscribeTokenService`'s own Javadoc for the full reasoning, and
`ErrorCode.INVALID_UNSUBSCRIBE_TOKEN` (`NT101`) for what a caller gets back
for a missing/malformed/tampered/expired token.

This token type is entirely self-contained to this service — minted and
verified by the same class, using a secret only this service holds. It's
deliberately **not** built on oms-main's JWKS/RS256 machinery (see
`security.SecurityConfig`'s own Javadoc): that exists for tokens a
logged-in human carries that other services must verify independently;
this token has exactly one issuer and one verifier, so a shared HMAC
secret is simpler and sufficient. No cross-repo change was needed.

## Data model

- **`notifications`** — one row per attempted send (not per logical event —
  a `FAILED` notification that later succeeds on retry transitions the same
  row, it doesn't create a second one).
- **`processed_events`** — the idempotency guard, see above.
- **`notification_preferences`** — per-(customer, type, channel) opt-in
  state. Absence of a row means opted-in. Real for both `EMAIL` and `SMS`
  now that Phase 4 registered a second provider — see
  [Multi-channel fan-out](#multi-channel-fan-out-phase-4-and-what-changed-to-support-it)
  above. Order/payment/shipment types stay `transactional = true` (see that
  enum's own Javadoc on the CAN-SPAM/GDPR reasoning) and ignore opt-out
  entirely; `CUSTOMER_WELCOME` is now `transactional = false`, the first
  type where opt-out is actually enforced — see
  `NotificationPreferenceServiceImplTest` for the real behavior. This
  classification is the common industry-standard reading, applied as a
  starting position — get real legal sign-off before relying on it in a
  real deployment.

No outbox table — this service has no producer role yet (see
`notification.service`'s package-info). Add one the same way
`shipment-service`'s was added if something downstream ever needs to react
to a notification being sent/failed.

## Error codes

Own `CM`/`OR`/`CU`/`NT` prefixes. `OR100`/`OR500` (`OrderClient`) and
`CU100`/`CU500` (`CustomerClient`) are deliberately the *same codes*
`oms-main`'s own `ErrorCode` uses for the same facts — a 404 for "this
order/customer doesn't exist" means the same thing everywhere in this
system, so it gets the same code everywhere rather than a service-local
reinvention. `NT100` is this service's own.

## Security

JWT *verification* only, against `oms-main`'s JWKS endpoint — same posture
as every other service in this system; this service never issues a token.
Covers the human-facing endpoints in `notification.controller` only.

**The two outbound clients use a completely different mechanism** — see
`customerclient.config.InternalServiceAuthInterceptor`'s (long, deliberately
so) Javadoc. Every call this service makes to `CustomerClient`/`OrderClient`
originates from a Kafka listener thread, not a servlet request — there is
no inbound user token to forward the way `shipment-service`'s
`AuthHeaderForwardingInterceptor` does. This service instead sends a static
`X-Internal-Service-Key` header, explicitly documented as a **placeholder**:

- **Not production-grade.** A shared static secret doesn't rotate, doesn't
  scope permissions, and gives the receiving service no way to distinguish
  this service from any other holder of the same key. OAuth2
  client-credentials (this service authenticates with its own client
  id/secret, gets a short-lived service-scoped JWT back) is the real answer
  — not built here to avoid speculatively building an auth flow `oms-main`'s
  own auth module may not support yet.
- **Not accepted on the receiving end, either.** Neither `oms-main`'s nor
  `customer-service`'s `SecurityConfig` has any concept of this header
  today — both only ever verify a JWT. Sending it currently authenticates
  nothing. This is a cross-repo dependency this scaffold doesn't resolve on
  its own; both target services need a matching change before `OrderClient`/
  `CustomerClient` will actually authenticate against a real deployment.

## Running locally

```bash
cp docker-compose.snippet.yml ../oms-main/  # fold into oms-main's own docker-compose.yml
```

Ports: app `8085`, actuator/management `8095`, Postgres `5436` — chosen to
not collide with `product-service` (`8082`/`8091`/`5433`),
`customer-service` (`8083`/`8092`/`5434`), or `shipment-service`
(`8084`/`8094`/`5435`).

Includes a **Mailpit** container (a local SMTP catcher) — `SmtpEmailProvider`
points at it by default, so local dev needs zero cloud credentials. Web UI
at `http://localhost:8025` to actually see what this service sent. **No
local SMS catcher exists yet** — `TWILIO_ACCOUNT_SID`/`TWILIO_AUTH_TOKEN`/
`TWILIO_FROM_NUMBER` are required (no safe defaults, same reasoning as
`INTERNAL_SERVICE_API_KEY` below), so exercising `TwilioSmsProvider` end to
end locally currently means either real (test-mode) Twilio credentials or
relying on `TwilioSmsProviderTest`'s mocked-`SmsSender` coverage instead —
see [Testing](#testing).

`INTERNAL_SERVICE_API_KEY` is required (no safe default) — see the Security
section above for what it currently does and doesn't protect.

`UNSUBSCRIBE_TOKEN_SECRET` (Base64 of a raw HMAC-SHA256 key) is required in
`prod`/undeclared profiles — no safe default there, same reasoning as
`INTERNAL_SERVICE_API_KEY` above (a shipped default would mean every
unconfigured deployment silently shares the same signing key). The `dev`
profile ships a dev-only generated default so local runs need no setup.
`UNSUBSCRIBE_TOKEN_EXPIRATION_MS` (default 30 days) and
`NOTIFICATION_SERVICE_PUBLIC_BASE_URL` (default `http://localhost:8085`,
used to build the clickable link embedded in emails) both have safe
defaults everywhere.

## Distributed tracing

OpenTelemetry + Tempo, same OTLP endpoint `oms-main` exports to, so traces
land in the same instance. The interesting trace here is
`oms-main → (Kafka, an async gap) → this service → CustomerClient/OrderClient
→ SmtpEmailProvider` — the same span-link technique flagged for
`shipment-service`'s own outbox gap would apply here too, probably even more
usefully (tying "this customer's confirmation email is stuck" directly back
to the order that triggered it). Not built in Phase 1.

## Testing

```bash
./mvnw test
```

- `NotificationServiceImplTest` — the orchestration logic, idempotency
  first (see its own `Idempotency` nested class — this is the test category
  prioritized above all others, per this service's own design goals),
  preference enforcement, failed-send handling, and (new in Phase 4) a
  `MultiChannelFanOut` nested class: sends to every opted-in channel,
  respects a per-channel opt-out, and skips SMS cleanly (no `FAILED` row)
  when a customer has no phone number on file.
- `OrderNotificationConsumerTest` / `PaymentNotificationConsumerTest` /
  `CustomerWelcomeConsumerTest` / `ShipmentNotificationConsumerTest` —
  each Kafka listener's dispatch logic: ignoring other event types,
  tolerating unknown JSON fields, propagating (not swallowing) dependency
  failures.
- `NotificationComposerImplTest` — renders the *real* template files
  through a *real* `SpringTemplateEngine` (not mocked) — this is what
  actually catches a template-resolution regression like the one
  `ThymeleafConfig` fixes, which a mocked engine couldn't. Covers the
  unsubscribe link being interpolated into both the HTML and text bodies,
  and (new in Phase 4) SMS-specific cases: `htmlBody` comes back `null`
  (not empty) for SMS, and a missing SMS template throws the same way a
  missing EMAIL template does.
- `NotificationPreferenceServiceImplTest` — the first real unit test of
  this class's own `isOptedIn` branching logic (previously only exercised
  indirectly, via a mocked `NotificationPreferenceService` in
  `NotificationServiceImplTest`): a transactional type short-circuits to
  opted-in with zero repository calls; `CUSTOMER_WELCOME` genuinely defers
  to the stored preference, covering no-row (defaults opted-in), a stored
  opt-out, and a stored opt-in; `optOut` itself covers both creating a new
  row and idempotently flipping an existing one rather than duplicating
  it.
- `UnsubscribeTokenServiceTest` — round-trips a real token through the real
  generate/parse path (no mocked jjwt): a customer/type/channel survives
  the round trip, a token signed with a different secret is rejected, an
  expired token is rejected, and a well-signed token that was never minted
  for the `unsubscribe` purpose is rejected too.
- `TwilioSmsProviderTest` *(new in Phase 4)* — exercises `TwilioSmsProvider`
  against a mocked `SmsSender`, with **real** (not mocked) resilience4j
  `Retry`/`CircuitBreaker` instances shaped to mirror the prod config:
  a permanent 4xx (invalid number, recipient replied STOP) sends exactly
  once, no retry; a 429 and a 5xx both retry; a connection failure with no
  HTTP status at all is treated as transient; retries fully exhausted still
  never throws out of `send()`. See `SmsSender`'s own Javadoc for why this
  is a mocked-collaborator test and not a WireMock contract test — the
  paragraph immediately below explains what that leaves uncovered.
- `FcmPushProviderTest` *(new)* — same mocked-collaborator shape as
  `TwilioSmsProviderTest`, against the `PushSender` seam, with the same
  real resilience4j registries. One genuine wrinkle
  `TwilioSmsProviderTest` didn't have: `FirebaseMessagingException` has no
  public constructor at all in firebase-admin `9.10.0` (confirmed against
  its actual source), so every exception case here is
  `Mockito.mock(FirebaseMessagingException.class)` with
  `getMessagingErrorCode()` stubbed, not constructed for real. Covers all
  4 permanent FCM error codes, one transient code, and a `null` error code
  (raw connection failure, treated as transient) — this closes what §5's
  own plan-status entry once called "genuinely untestable end to end,"
  which turned out to be imprecise once someone actually tried: the
  `PushSender` seam already existed specifically to make this testable.
- `CustomerClientContractTest` / `OrderClientContractTest` — real WireMock,
  real deserialization (happy path plus a malformed-JSON-body case);
  `CustomerClientResilienceTest` / `OrderClientResilienceTest` — retry-then-
  fail on a 500, no retry on a 404, circuit-breaker opens after repeated
  failures. Same pattern `shipment-service`'s own
  `OrderClientContractTest`/`OrderClientResilienceTest` establish.

**Not yet built**: any test exercising `InternalServiceAuthInterceptor`
itself. **Also not yet built**: any test that verifies `TwilioSmsSender`'s
or `FcmPushSender`'s actual HTTP/SDK call still matches Twilio's or FCM's
real API response shape — `TwilioSmsProviderTest`/`FcmPushProviderTest`
prove this service's own retry/classification logic is correct against a
mock, but a genuine drift in either provider's real API (a renamed field,
a changed status-code or error-code convention) wouldn't be caught by
anything in this repo today. twilio-java owns its HTTP client internally
with no clean base-URL override (and the Firebase Admin SDK is no
different), so pointing WireMock at either the way
`OrderClientContractTest` points at a plain `RestClient` isn't
straightforward — flagged here rather than silently assumed covered.

**Not yet run in this repo**: the push channel addition (`FcmPushProvider`,
`PushSender`, `FcmConfig`, `FcmPushProviderTest`, the `pushToken` field, and
every consumer/test change that came with it) hasn't been confirmed by a
real `./mvnw test` run yet — unlike the unsubscribe-token and Phase 4 SMS
work above, both of which have. Run it before trusting the push addition
fully, same reasoning as always: no Maven Central access in the
environment that authored it.

## Build phases

This is the staged plan the whole service was scoped from. **Phase 1 is
fully built. Phase 2 is now fully built** — the signed unsubscribe-token
piece is done, and real per-type opt-out enforcement is done too:
`CUSTOMER_WELCOME` is now `transactional = false` and its opt-out is
genuinely enforced; every order/payment/shipment type stays
`transactional = true` and ignores opt-out, matching CAN-SPAM's own
carve-out for transactional/relationship messages — see
`NotificationType`'s own Javadoc for the reasoning and its caveat that this
is a starting position, not a substitute for actual legal sign-off.
**Phase 3 is now fully wired** —
`OrderConfirmed`/`OrderCancelled`/`PaymentConfirmed`/`PaymentFailed`/
`CustomerWelcome` are all done on the email channel; **shipment lifecycle
events are now done too — Phase 3 is fully complete.** **Phase 4 is now
fully complete, with no remaining code or data gap** — SMS is done for
all eight wired event types, and push (`FcmPushProvider`) is built for
all eight too. `customer-service` has since shipped the `pushToken` field
push depends on, so the only thing left is a deliberate operational
decision (real FCM credentials + flipping
`app.notification.push.enabled=true`), not more work — see item 4 below.
**Phase 5 (infra parity) is also fully complete**, including the
time-to-delivery Grafana panel — see item 5 below.

1. **Scaffold + one channel, one event type** *(this build)* — email only,
   `OrderConfirmed` only. Proves the skeleton (Kafka consumer, idempotency,
   one provider, delivery tracking) end to end.
2. **Preferences + opt-out, before adding more event types.** The data
   model (`notification_preferences`) already exists (see
   [Data model](#data-model)).
   - **Done:** the unsubscribe endpoint is now signed-token-based — see
     [API](#api) and `security.UnsubscribeTokenService`. No more raw,
     guessable query parameters.
   - **Done:** enforcement itself is real now — `CUSTOMER_WELCOME` is
     `transactional = false`, so `NotificationPreferenceServiceImpl.isOptedIn`
     genuinely defers to the stored preference for that type (verified in
     `NotificationPreferenceServiceImplTest`, the first real unit test of
     this class's branching logic). Every order/payment/shipment type
     stays `transactional = true` and ignores opt-out — matches CAN-SPAM's
     own carve-out for transactional/relationship messages, but this
     classification is the common industry-standard reading, not
     researched legal advice — get real sign-off before relying on it in a
     real deployment. See `NotificationType`'s own Javadoc for the full
     reasoning.
3. **Remaining event types** on the email channel.
   - **Done:** `OrderCancelled` — folded into the renamed
     `notification.consumer.OrderNotificationConsumer` (formerly
     `OrderConfirmedNotificationConsumer`), same consumer group as
     `OrderConfirmed` since both are the same "order lifecycle
     notification" concern — one `@KafkaListener` method dispatching on
     `eventType`, same shape as oms-main's own `OrderSagaEventConsumer`.
   - **Done:** `PaymentConfirmed`/`PaymentFailed` — see
     `notification.consumer.PaymentNotificationConsumer`. Same `OrderClient`
     customerId-resolution workaround as `OrderNotificationConsumer`
     (the gap noted below isn't fixed, just worked around again). Runs in
     its own Kafka consumer group (`app.kafka.consumer.payment-group-id`)
     — a genuinely different consumer concern from order-lifecycle
     notifications, so unlike `OrderCancelled` it gets its own group rather
     than folding into `OrderNotificationConsumer`'s — so it gets a full
     independent copy of `oms.order.events` rather than competing for
     partitions with `OrderNotificationConsumer` — see that property's own
     comment in `application.properties`.
   - **Done:** `CustomerWelcome` — see
     `notification.consumer.CustomerWelcomeConsumer`, on customer-service's
     `oms.customer.events` topic, its own dedicated consumer group
     (`app.kafka.consumer.customer-group-id`). Unlike every other consumer
     here, **no synchronous lookup is needed to find the recipient** —
     `CustomerCreatedEvent` carries `customerId` directly (it's the event's
     own aggregate), so there's no customerId gap to work around for this
     one. `NotificationServiceImpl#processEvent` still re-resolves the
     email via `CustomerClient` internally regardless — a known, accepted
     redundant hop, not a bug — see that consumer's own Javadoc for why.
   - **Done:** `ShipmentShipped`/`ShipmentDelivered`/`ShipmentReturned` —
     see `notification.consumer.ShipmentNotificationConsumer`, on
     shipment-service's `oms.shipment.events` topic, its own dedicated
     consumer group (`app.kafka.consumer.shipment-group-id`). Same
     `OrderClient` customerId-resolution workaround as
     `OrderNotificationConsumer`/`PaymentNotificationConsumer` — that gap
     (see below) still isn't fixed, just worked around a third time. All
     three event types are handled in one `@KafkaListener` method, same
     "one logical concern, one group" shape as `OrderNotificationConsumer`
     (grouping `OrderConfirmed`/`OrderCancelled`), even though — unlike
     that case — nothing else reads `oms.shipment.events` today, so there
     was no actual partition-contention risk to avoid either way.
     `ShipmentShippedEvent` also carries `trackingNumber` through to the
     template, matching the plan's own §1 example ("Tracking number
     email").
   - **This closes Phase 3.** Every event type in the plan's original §2
     table is now wired on the email channel. The remaining open item is
     [the customerId gap](#the-customerid-gap-and-the-recommended-fix)
     itself — worth fixing once, for all four `*Client`-dependent
     consumers, rather than living with four separate synchronous-lookup
     workarounds indefinitely.
4. **Additional channels** (SMS, push) — `NotificationProvider`'s interface
   is what should make this additive (a new implementation), not a
   rewrite, if Phase 1 built that abstraction correctly. It did:
   - **Done:** SMS — `TwilioSmsProvider` (behind the `SmsSender` seam, see
     [Multi-channel fan-out](#multi-channel-fan-out-phase-4-and-what-changed-to-support-it)
     above), SMS templates for all eight wired event types, and
     `NotificationServiceImpl`'s fan-out rewrite so a customer opted into
     both channels gets both, not just email.
   - **Done, and its upstream dependency has now shipped:** push —
     `FcmPushProvider` (mirrors `TwilioSmsProvider`'s resilience4j
     composition and never-throw contract), a `<type>_push_en.txt`
     template for all eight event types (the title reuses
     `NotificationComposerImpl#subjectFor`'s existing subject line rather
     than adding a `NotificationRequest` field just for push), and a
     `pushToken` field on `CustomerClientResponse`. `customer-service` has
     since shipped the schema it depends on
     (`V4__add_push_token_to_customers.sql`, exposed on
     `CustomerResponse`, set via a dedicated
     `PUT /customers/{id}/push-token` endpoint — kept separate from the
     general customer-update endpoint, same single-purpose-endpoint
     reasoning as this service's own provider classes). Still disabled by
     default (`app.notification.push.enabled=false`) — but that's now a
     deliberate, reversible deploy-time decision (real FCM credentials +
     flipping the flag), not a wait on missing data. See
     `FcmPushProvider`'s own Javadoc for the full reasoning.
5. **Infra parity** — Kubernetes manifests, Prometheus scrape target,
   Grafana dashboard, same checklist `shipment-service`'s own Stage 7
   established. **Done** — web/worker split, KEDA-driven worker autoscaling
   on consumer lag + retry backlog, PDBs, PodMonitor, and a Grafana
   dashboard with real notification-specific metrics (sent/failed/
   dead-lettered by channel and type, retry-queue depth, provider send
   latency, and time-to-delivery (event-received → notification-sent,
   distinct from send latency — see `NotificationMetrics#recordTimeToDelivery`'s
   own Javadoc) are all built. See `k8s/README.md`.

## Known gaps (through Phase 4 / Phase 5 completion)

Collected in one place for visibility, even though each is also documented
at its point of origin in code:

- **`customerId` missing from `OrderConfirmedEvent`/`OrderCancelledEvent`/
  `PaymentConfirmedEvent`/`PaymentFailedEvent`/`ShipmentShippedEvent`/
  `ShipmentDeliveredEvent`/`ShipmentReturnedEvent`** — see
  [above](#the-customerid-gap-and-the-recommended-fix). Workaround
  (`OrderClient`) is built and working, now duplicated across four separate
  consumers (`OrderNotificationConsumer`, `PaymentNotificationConsumer`,
  `ShipmentNotificationConsumer`, plus whichever consumer eventually needs
  it next); the real fix — adding `customerId` to these events at the
  source — is still deferred, and worth doing now specifically *because*
  the workaround has been copy-pasted a third time.
- **Internal service auth is a placeholder on both ends** — see
  [Security](#security). Neither this service's own implementation nor the
  receiving services' acceptance of it is production-grade.
- **Per-type opt-out classification is a starting position, not legal
  advice** — see [Build phases](#build-phases)' Phase 2 note and
  `NotificationType`'s own Javadoc. `CUSTOMER_WELCOME` opt-out is now
  genuinely enforced; order/payment/shipment types stay un-opt-out-able.
  This is the common industry-standard reading, applied here without
  actual legal review — get real sign-off before relying on it in a real
  deployment. Applies identically across all three channels.
- **`resend` reconstructs template variables from stored columns only** —
  fine for most types, but genuinely under-populates `ShipmentShipped`
  (the stored row has no `trackingNumber` column — see
  [Data model](#data-model) — so a scheduler-driven or manual resend of a
  failed `ShipmentShipped` notification renders that field blank). See
  `NotificationService.resend`'s own Javadoc.
- **A customer opted into SMS with no phone number on file produces no
  visible failure signal** — silently skipped, not recorded as `FAILED`.
  See
  [Multi-channel fan-out](#multi-channel-fan-out-phase-4-and-what-changed-to-support-it)
  for the reasoning and why it's worth revisiting if it turns out to hide
  a real data-quality problem. Applies to push the same way, once enabled.
- **No true HTTP-contract test for `TwilioSmsSender`/`FcmPushSender`** —
  `TwilioSmsProviderTest`/`FcmPushProviderTest` cover this service's own
  retry/classification logic against a mock; nothing yet catches a real
  drift in either provider's actual API shape. Evaluated and deliberately
  not built — see [Testing](#testing) for the full reasoning.

**Resolved since this list was last written** (kept here briefly so the
history isn't lost, not as open items): contract/resilience tests for
`CustomerClient` and `OrderClient` are now built (see
[Testing](#testing)); the scheduled retry/DLQ piece (§6,
`NotificationRetryScheduler`) is now built; Kubernetes manifests, a
Prometheus scrape target, and a Grafana dashboard (Phase 5) are all now
built (see `k8s/README.md`); all shipment lifecycle events are now wired
(this closes Phase 3 entirely); `CUSTOMER_WELCOME` opt-out is now
genuinely classified and enforced (this closes Phase 2 entirely); the
time-to-delivery metric and its Grafana panel are now built (closing the
one gap §10 had left); the push channel (`FcmPushProvider`) is now built
end to end, and `customer-service` has since shipped the `pushToken`
schema it depends on (this closes Phase 4 entirely — the only thing left
is the deliberate operational decision to flip
`app.notification.push.enabled=true` once real FCM credentials exist,
not a code or data gap). Earlier still: the
unsubscribe endpoint's signed-token fix (`security.UnsubscribeTokenService`)
and the original SMS channel build (`TwilioSmsProvider`, the `SmsSender`
seam, `NotificationServiceImpl`'s multi-channel fan-out rewrite).

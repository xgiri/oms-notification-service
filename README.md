# notification-service

A multi-channel notification service for OMS. Unlike `product-service`/
`customer-service`/`shipment-service` (each extracted from `oms-main`),
this is a **new** service, built fresh — but deliberately shaped to match
every convention this system already established: same package layout,
same JWT-verification-only security posture, same resilient-client pattern,
same web/worker-role convention.

**Current status: Phase 3 in progress — one channel (email), four event
types wired (`OrderConfirmed`, `OrderCancelled`, `PaymentConfirmed`,
`PaymentFailed`).** See [Build phases](#build-phases) for the full staged
plan and exactly what's implemented vs. planned.

## What it does

Listens to `oms-main`'s Kafka event stream and turns order lifecycle events
into customer notifications — order confirmations today, with payment and
shipment lifecycle events staged for later phases. Every send is recorded
(`notifications` table) so delivery history is queryable, every event is
processed idempotently (a Kafka redelivery must never double-send), and
every customer has per-(type, channel) opt-in/opt-out state from day one
even though only email exists to opt in/out of yet.

## Architecture

```
Kafka (oms.order.events)
        │
        ├──▶ OrderNotificationConsumer ────▶ OrderClient ──▶ oms-main (resolve customerId)
        │      (OrderConfirmed, OrderCancelled)
        │
        └──▶ PaymentNotificationConsumer ──▶ OrderClient ──▶ oms-main (resolve customerId)
               (PaymentConfirmed, PaymentFailed — own consumer group)
        │
        ▼
NotificationServiceImpl (idempotency → preference check → compose → send → record)
        │                                      │                    │
        ▼                                      ▼                    ▼
CustomerClient ──▶ customer-service   NotificationComposer   NotificationProvider
  (resolve email)                      (Thymeleaf templates)   (SmtpEmailProvider → Mailpit)
```

- **`notification`** — the domain: entities, repositories, the orchestration
  service (`NotificationServiceImpl`), the provider abstraction, the one
  Phase 1 consumer, the REST controller.
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
  state. Absence of a row means opted-in. Every `NotificationType` today is
  `transactional = true` (see that enum's own Javadoc on the CAN-SPAM/GDPR
  reasoning), which currently means opt-out is ignored for all of them —
  get real legal sign-off on which types genuinely can't be opted out of
  before relying on that placeholder stance in a real deployment.

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
at `http://localhost:8025` to actually see what this service sent.

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
  preference enforcement, and failed-send handling.
- `OrderNotificationConsumerTest` / `PaymentNotificationConsumerTest` —
  each Kafka listener's dispatch logic: ignoring other event types,
  tolerating unknown JSON fields, propagating (not swallowing) dependency
  failures.
- `NotificationComposerImplTest` — renders the *real* template files
  through a *real* `SpringTemplateEngine` (not mocked) — this is what
  actually catches a template-resolution regression like the one
  `ThymeleafConfig` fixes, which a mocked engine couldn't. Also covers the
  unsubscribe link being interpolated into both the HTML and text bodies.
- `UnsubscribeTokenServiceTest` — round-trips a real token through the real
  generate/parse path (no mocked jjwt): a customer/type/channel survives
  the round trip, a token signed with a different secret is rejected, an
  expired token is rejected, and a well-signed token that was never minted
  for the `unsubscribe` purpose is rejected too.

**Not yet built**: a WireMock-based contract test for `CustomerClient`/
`OrderClient` (same pattern `shipment-service`'s `OrderClientContractTest`/
`OrderClientResilienceTest` establish — retry/circuit-breaker behavior under
real HTTP failures), and any test exercising `InternalServiceAuthInterceptor`
itself.

**Not yet run**: the unsubscribe-token change (code, tests, `pom.xml`'s new
jjwt dependency) hasn't been compiled/run in this environment — no Maven
Central access here. Run `./mvnw test` before trusting it fully.

## Build phases

This is the staged plan the whole service was scoped from. **Phase 1 is
fully built. Phase 2 is partially built** — the signed unsubscribe-token
piece is done; the other Phase 2 item (real per-type opt-out enforcement,
currently short-circuited for every type since all are `transactional`)
is still open, pending legal sign-off on which types genuinely can't be
opted out of. **Phase 3 is in progress** — `PaymentConfirmed`/`PaymentFailed`
are wired; `OrderCancelled`, the shipment lifecycle events, and
`CustomerWelcome` are still open. Phases 4–5 are the roadmap, not
implemented.

1. **Scaffold + one channel, one event type** *(this build)* — email only,
   `OrderConfirmed` only. Proves the skeleton (Kafka consumer, idempotency,
   one provider, delivery tracking) end to end.
2. **Preferences + opt-out, before adding more event types.** The data
   model (`notification_preferences`) already exists (see
   [Data model](#data-model)).
   - **Done:** the unsubscribe endpoint is now signed-token-based — see
     [API](#api) and `security.UnsubscribeTokenService`. No more raw,
     guessable query parameters.
   - **Still open:** enforcement itself is a placeholder — every
     `NotificationType` is `transactional = true`, so
     `NotificationPreferenceServiceImpl.isOptedIn` short-circuits to `true`
     regardless of a stored preference. Get real legal sign-off (CAN-SPAM/
     GDPR) on which types genuinely can't be opted out of before relying on
     this in a real deployment — see that class's own Javadoc.
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
   - **Still open:** `ShipmentShipped`, `ShipmentDelivered`,
     `ShipmentReturned`, `CustomerWelcome` — need `shipment-service`/
     `customer-service` event shapes, not yet available. Also the natural
     point to revisit
     [the customerId gap](#the-customerid-gap-and-the-recommended-fix) —
     worth fixing once, for all of them, rather than adding yet another
     `*Client` per remaining event type.
4. **Additional channels** (SMS, push) — `NotificationProvider`'s interface
   is what should make this additive (a new implementation), not a rewrite,
   if Phase 1 built that abstraction correctly.
5. **Infra parity** — Kubernetes manifests, Prometheus scrape target,
   Grafana dashboard, same checklist `shipment-service`'s own Stage 7
   established. Not started.

## Known gaps (Phase 1 + partial Phase 2)

Collected in one place for visibility, even though each is also documented
at its point of origin in code:

- **`customerId` missing from `OrderConfirmedEvent`** — see
  [above](#the-customerid-gap-and-the-recommended-fix). Workaround
  (`OrderClient`) is built and working; the real fix is deferred.
- **Internal service auth is a placeholder on both ends** — see
  [Security](#security). Neither this service's own implementation nor the
  receiving services' acceptance of it is production-grade.
- **Every notification type is un-opt-out-able (transactional) as a
  placeholder stance** — see [Build phases](#build-phases)' Phase 2 note and
  `NotificationPreferenceServiceImpl.isOptedIn`. Real per-type legal
  sign-off is still needed; the unsubscribe *token* itself is fixed (see
  below), but opting out currently has no effect for any type that exists
  today.
- **`resend` reconstructs template variables from stored columns only** —
  fine for `ORDER_CONFIRMED`, would under-populate a richer future type. See
  `NotificationService.resend`'s own Javadoc.
- **No contract/resilience tests for either client yet** — see
  [Testing](#testing).
- **No infra (Kubernetes/monitoring) at all** — Phase 5, not started.

**Fixed this session:** the unsubscribe endpoint's signed-token gap — see
[API](#api) and `security.UnsubscribeTokenService`.

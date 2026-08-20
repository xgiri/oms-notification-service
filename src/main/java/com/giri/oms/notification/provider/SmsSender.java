package com.giri.oms.notification.provider;

import com.twilio.exception.ApiException;

/**
 * The one seam between {@link TwilioSmsProvider}'s own logic (retry
 * classification, circuit breaker, permanent-vs-transient handling — all
 * ours, all worth testing directly) and the Twilio SDK's static
 * {@code Message.creator(...).create()} call, which isn't itself
 * injectable. Introduced specifically so {@code TwilioSmsProviderTest} can
 * exercise the resilience logic against a mocked {@link SmsSender} instead
 * of needing WireMock or real Twilio credentials.
 * <p>
 * <b>A WireMock-based contract test for this class was deliberately
 * evaluated and NOT built</b> — this is a considered decision, not an
 * unaddressed gap. Two separate reasons, not one:
 * <ol>
 *   <li>It's genuinely hard to do cleanly: unlike a plain {@code RestClient}
 *   (see {@code OrderClientContractTest}), twilio-java's {@code Request}
 *   always resolves its target host internally from a fixed region/edge
 *   template — there's no supported "point this at an arbitrary base URL"
 *   option (see <a href="https://github.com/twilio/twilio-java/issues/310">
 *   twilio-java#310</a>, open and unresolved since 2016, still true as of
 *   the {@code 12.1.1} version pinned in {@code pom.xml}). The only way in
 *   is a custom {@code com.twilio.http.HttpClient} that rewrites the
 *   already-built request's host before dispatching — real interception,
 *   not configuration.</li>
 *   <li><b>More importantly: it wouldn't buy much even if built.</b>
 *   {@code OrderClientContractTest}/{@code CustomerClientContractTest}
 *   matter because {@code OrderClientResponse}/{@code CustomerClientResponse}
 *   are hand-rolled DTOs this service maintains, matching endpoints another
 *   team maintains independently — real, everyday drift risk.
 *   {@link TwilioSmsSender} has no equivalent: response deserialization
 *   into a {@code Message} object happens entirely inside the Twilio SDK,
 *   which Twilio itself tests extensively. A WireMock test here would
 *   mostly re-verify that Twilio's own SDK talks to Twilio's own API
 *   correctly — not this codebase's job.</li>
 * </ol>
 * If genuine end-to-end confidence in the SDK-to-Twilio path is ever
 * wanted, Twilio's own
 * <a href="https://www.twilio.com/docs/iam/test-credentials">test
 * credentials/magic numbers</a> (e.g. {@code +15005550006}) exercise the
 * real SDK against Twilio's real API without sending an actual SMS or
 * touching account funds — the officially-supported way to get that
 * confidence, and a much smaller lift than reimplementing the transport
 * layer. Not built as part of this pass.
 * <p>
 * Deliberately narrow — three primitives in, a provider message id out,
 * {@link ApiException} propagating on failure — not a general-purpose SMS
 * abstraction. If a second SMS provider is ever added, THIS is not the
 * seam to reuse for it; {@link NotificationProvider} already is that
 * seam.
 */
public interface SmsSender {

    String send(String to, String from, String body) throws ApiException;
}

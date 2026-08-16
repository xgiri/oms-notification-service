package com.giri.oms.notification.provider.config;

import com.twilio.Twilio;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Initializes twilio-java's global static client once at startup —
 * {@code Twilio.init(...)} sets a process-wide singleton the SDK's
 * {@code Message.creator(...)} call reads implicitly, so this has to run
 * before {@link com.giri.oms.notification.provider.TwilioSmsProvider}'s
 * first send, not per-call. This is the one place in this service that
 * deviates from the RestClient-per-client convention every other outbound
 * call here follows (CustomerClientConfig, OrderClientConfig) — the Twilio
 * SDK owns its own HTTP client internally, so there's no RestClient bean to
 * configure here, only credentials.
 * <p>
 * No safe default for either credential, same reasoning as
 * app.customerclient.service-api-key in application.properties — a default
 * here would mean every unconfigured deployment silently shares the same
 * Twilio account.
 */
@Configuration
public class TwilioConfig {

    public TwilioConfig(@Value("${app.notification.sms.account-sid}") String accountSid,
                         @Value("${app.notification.sms.auth-token}") String authToken) {
        Twilio.init(accountSid, authToken);
    }
}

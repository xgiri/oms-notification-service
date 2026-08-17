package com.giri.oms.notification.provider;

import com.twilio.exception.ApiException;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;
import org.springframework.stereotype.Component;

/**
 * The only class in this service that actually calls the Twilio SDK — see
 * {@link SmsSender}'s own Javadoc for why this exists as a separate class
 * from {@link TwilioSmsProvider} rather than being inlined back into it.
 */
@Component
public class TwilioSmsSender implements SmsSender {

    @Override
    public String send(String to, String from, String body) throws ApiException {
        Message message = Message.creator(new PhoneNumber(to), new PhoneNumber(from), body).create();
        return message.getSid();
    }
}

package com.giri.oms.notification.service.impl;

import com.giri.oms.notification.entity.NotificationType;
import com.giri.oms.notification.provider.NotificationRequest;
import com.giri.oms.notification.service.NotificationComposer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.util.Locale;
import java.util.Map;

/**
 * Template naming convention: {@code <type>_email_<locale>} for the HTML
 * body (e.g. {@code order-confirmed_email_en.html}), resolved via
 * Thymeleaf's normal template resolver — see application.properties'
 * {@code spring.thymeleaf.*} for the prefix/suffix this relies on. A plain
 * {@code .txt} sibling under the same name-locale pair is the multipart
 * text fallback SmtpEmailProvider's MimeMessageHelper.setText(text, html)
 * call needs. Subject lines are NOT in the template files themselves — see
 * {@link #subjectFor} — since a subject is a single line that doesn't
 * benefit from Thymeleaf's own templating and keeping it in code makes it
 * one place, not two, to check when adding a type.
 * <p>
 * SMS/push channels aren't implemented here yet (see NotificationType's own
 * "Phase 1" note) — when they are, this class's {@code compose} still
 * returns the same shared {@link NotificationRequest} shape; only
 * {@code textBody} would be populated for those channels, and a channel
 * parameter would need adding here to pick the right template set. Not
 * built now to avoid a speculative channel parameter with only one real
 * caller (EMAIL) to validate it against.
 */
@Service
@RequiredArgsConstructor
public class NotificationComposerImpl implements NotificationComposer {

    private final SpringTemplateEngine templateEngine;

    @Override
    public NotificationRequest compose(NotificationType type, String locale, String recipientAddress,
                                        Map<String, Object> templateVariables) {
        String templateBaseName = type.name().toLowerCase().replace('_', '-') + "_email_" + locale;

        Context context = new Context(Locale.forLanguageTag(locale));
        context.setVariables(templateVariables);

        String htmlBody = templateEngine.process(templateBaseName + ".html", context);
        String textBody = templateEngine.process(templateBaseName + ".txt", context);

        return new NotificationRequest(recipientAddress, subjectFor(type, locale, templateVariables), htmlBody, textBody);
    }

    private String subjectFor(NotificationType type, String locale, Map<String, Object> templateVariables) {
        return switch (type) {
            case ORDER_CONFIRMED -> "Your order #" + templateVariables.get("orderId") + " is confirmed";
            case ORDER_CANCELLED -> "Your order #" + templateVariables.get("orderId") + " has been cancelled";
            case PAYMENT_CONFIRMED -> "Payment received for order #" + templateVariables.get("orderId");
            case PAYMENT_FAILED -> "Payment failed for order #" + templateVariables.get("orderId");
            case SHIPMENT_SHIPPED -> "Your order #" + templateVariables.get("orderId") + " has shipped";
            case SHIPMENT_DELIVERED -> "Your order #" + templateVariables.get("orderId") + " was delivered";
            case SHIPMENT_RETURNED -> "Your return for order #" + templateVariables.get("orderId") + " was received";
            case CUSTOMER_WELCOME -> "Welcome!";
        };
    }
}

package com.giri.oms.notification.service.impl;

import com.giri.oms.notification.entity.NotificationChannel;
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
 * Template naming convention: {@code <type>_<channel>_<locale>}, resolved
 * via Thymeleaf's normal template resolver — see application.properties'
 * {@code spring.thymeleaf.*} for the prefix/suffix this relies on.
 * <ul>
 *   <li>EMAIL has both an {@code .html} body and a plain {@code .txt}
 *   sibling — the latter is the multipart text fallback
 *   SmtpEmailProvider's {@code MimeMessageHelper.setText(text, html)} call
 *   needs, e.g. {@code order-confirmed_email_en.html} /
 *   {@code order-confirmed_email_en.txt}.</li>
 *   <li>SMS has {@code .txt} only — no HTML concept for an SMS body — e.g.
 *   {@code order-confirmed_sms_en.txt}. {@link NotificationRequest#htmlBody()}
 *   is {@code null} for this channel; see that record's own Javadoc on why
 *   that's fine for callers.</li>
 * </ul>
 * Subject lines are NOT in the template files themselves — see
 * {@link #subjectFor} — since a subject is a single line that doesn't
 * benefit from Thymeleaf's own templating and keeping it in code makes it
 * one place, not two, to check when adding a type. SMS providers ignore
 * {@code subject} (see NotificationRequest's Javadoc) but this method still
 * computes it uniformly rather than branching on channel, since it costs
 * nothing to compute and keeps this class's per-channel logic confined to
 * template resolution, where it actually matters.
 */
@Service
@RequiredArgsConstructor
public class NotificationComposerImpl implements NotificationComposer {

    private final SpringTemplateEngine templateEngine;

    @Override
    public NotificationRequest compose(NotificationType type, NotificationChannel channel, String locale,
                                        String recipientAddress, Map<String, Object> templateVariables) {
        String templateBaseName = type.name().toLowerCase().replace('_', '-')
                + "_" + channel.name().toLowerCase() + "_" + locale;

        Context context = new Context(Locale.forLanguageTag(locale));
        context.setVariables(templateVariables);

        String subject = subjectFor(type, templateVariables);
        String textBody = templateEngine.process(templateBaseName + ".txt", context);

        // Only EMAIL has an HTML sibling to render — see class Javadoc.
        // Resolving a non-existent "<base>.html" for SMS would throw the
        // same way an actually-missing template does (see
        // NotificationComposerImplTest's SHIPMENT_SHIPPED case), so this
        // branches on channel rather than trying and catching.
        String htmlBody = channel == NotificationChannel.EMAIL
                ? templateEngine.process(templateBaseName + ".html", context)
                : null;

        return new NotificationRequest(recipientAddress, subject, htmlBody, textBody);
    }

    private String subjectFor(NotificationType type, Map<String, Object> templateVariables) {
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

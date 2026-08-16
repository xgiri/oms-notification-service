package com.giri.oms.notification.provider;

import com.giri.oms.notification.entity.NotificationChannel;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Phase 1's only channel — see this service's README. SMTP via
 * {@code JavaMailSender}, pointed at a local dev catcher (Mailpit — see
 * application.properties/docker-compose.snippet.yml) rather than a real
 * provider account, so local dev needs no cloud credentials. Swapping to a
 * real transactional-email provider (SES, SendGrid, ...) later means a new
 * NotificationProvider implementation — see package-info — not a change
 * here or upstream.
 * <p>
 * No provider-level retry here — see NotificationServiceImpl's own
 * retry/DLQ handling (a notification-level concern, deliberately separate
 * from a single send attempt's own resilience4j-style retry, which this
 * simple SMTP implementation doesn't have yet either; add one here first if
 * "retry a single send 2-3 times before giving up on it" turns out to
 * matter more than the outer notification-level retry loop already
 * covers).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SmtpEmailProvider implements NotificationProvider {

    private final JavaMailSender mailSender;

    @Value("${app.notification.email.from-address}")
    private String fromAddress;

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.EMAIL;
    }

    @Override
    public ProviderResult send(NotificationRequest request) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(request.recipientAddress());
            helper.setSubject(request.subject());
            helper.setText(request.textBody(), request.htmlBody());

            mailSender.send(message);

            return ProviderResult.success("smtp-" + UUID.randomUUID());
        } catch (MailException | jakarta.mail.MessagingException ex) {
            log.warn("Failed to send email to {}: {}", request.recipientAddress(), ex.getMessage());
            return ProviderResult.failure(ex.getMessage());
        }
    }
}

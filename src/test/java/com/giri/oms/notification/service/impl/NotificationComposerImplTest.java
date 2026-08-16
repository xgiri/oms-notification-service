package com.giri.oms.notification.service.impl;

import com.giri.oms.notification.entity.NotificationChannel;
import com.giri.oms.notification.entity.NotificationType;
import com.giri.oms.notification.provider.NotificationRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.templateresolver.ITemplateResolver;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Builds a real {@link SpringTemplateEngine} with both an HTML and a TEXT
 * resolver — mirroring exactly what Spring Boot's autoconfiguration plus
 * {@code common.config.ThymeleafConfig} wire up together in the real app —
 * rather than mocking the engine. A mocked engine would only prove this
 * class calls {@code process(...)} with some arguments; it couldn't catch
 * the actual bug ThymeleafConfig exists to fix (the {@code .txt} template
 * failing to resolve under HTML mode) the way rendering the real template
 * files under src/main/resources/templates does.
 */
class NotificationComposerImplTest {

    private NotificationComposerImpl composer;

    @BeforeEach
    void setUp() {
        ITemplateResolver htmlResolver = templateResolver(".html", TemplateMode.HTML, 1);
        ITemplateResolver textResolver = templateResolver(".txt", TemplateMode.TEXT, 2);

        SpringTemplateEngine templateEngine = new SpringTemplateEngine();
        templateEngine.setTemplateResolvers(java.util.Set.of(htmlResolver, textResolver));

        composer = new NotificationComposerImpl(templateEngine);
    }

    private ITemplateResolver templateResolver(String suffix, TemplateMode mode, int order) {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(suffix);
        resolver.setTemplateMode(mode);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(false);
        resolver.setOrder(order);
        resolver.setCheckExistence(true);
        return resolver;
    }

    @Test
    void rendersBothHtmlAndTextBodies_withTheGivenVariablesInterpolated() {
        NotificationRequest result = composer.compose(
                NotificationType.ORDER_CONFIRMED, NotificationChannel.EMAIL, "en", "jane@example.com",
                Map.of("orderId", 12345, "unsubscribeUrl", "https://notify.example.com/api/v1/notifications/unsubscribe?token=abc"));

        assertThat(result.recipientAddress()).isEqualTo("jane@example.com");
        assertThat(result.htmlBody()).contains("12345");
        assertThat(result.htmlBody()).contains("Your order is confirmed!");
        assertThat(result.textBody()).contains("12345");
        assertThat(result.textBody()).contains("Your order is confirmed!");
    }

    @Test
    void includesTheUnsubscribeLink_inBothHtmlAndTextBodies() {
        // NotificationServiceImpl (not this class — see its own Javadoc) is
        // what actually computes a real unsubscribeUrl; this class's only
        // job is to interpolate whatever it's given, same as any other
        // template variable. See UnsubscribeTokenService for how the real
        // URL is built.
        String unsubscribeUrl = "https://notify.example.com/api/v1/notifications/unsubscribe?token=abc";
        NotificationRequest result = composer.compose(
                NotificationType.ORDER_CONFIRMED, NotificationChannel.EMAIL, "en", "jane@example.com",
                Map.of("orderId", 12345, "unsubscribeUrl", unsubscribeUrl));

        assertThat(result.htmlBody()).contains(unsubscribeUrl);
        assertThat(result.textBody()).contains(unsubscribeUrl);
    }

    @Test
    void setsTheCorrectSubjectLine_perNotificationType() {
        NotificationRequest result = composer.compose(
                NotificationType.ORDER_CONFIRMED, NotificationChannel.EMAIL, "en", "jane@example.com",
                Map.of("orderId", 12345));

        assertThat(result.subject()).isEqualTo("Your order #12345 is confirmed");
    }

    @Test
    void theTextBodyIsPlainText_notHtmlEscapedOrTagLaden() {
        // The concrete regression ThymeleafConfig fixes — before that fix
        // existed, the .txt template either failed to resolve under the
        // HTML-only default engine or got parsed AS html, which would show
        // up here as stray tags/entities in what should be plain text.
        NotificationRequest result = composer.compose(
                NotificationType.ORDER_CONFIRMED, NotificationChannel.EMAIL, "en", "jane@example.com",
                Map.of("orderId", 12345));

        assertThat(result.textBody()).doesNotContain("<", ">");
    }

    @Test
    void throwsAClearError_whenNoTemplateExistsForTheType() {
        // SHIPMENT_SHIPPED has a subject line (NotificationComposerImpl.subjectFor)
        // but no template file yet — see this service's README on which
        // types are implemented vs. planned (shipment/customer event types
        // are blocked on shipment-service/customer-service event shapes).
        // Composing it should fail loudly at template resolution, not
        // silently render blank content.
        assertThatThrownBy(() -> composer.compose(
                NotificationType.SHIPMENT_SHIPPED, NotificationChannel.EMAIL, "en", "jane@example.com",
                Map.of("orderId", 12345)))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void sms_rendersTextBodyOnly_withNoHtmlBody() {
        // The core Phase 4 contract: SMS has no HTML concept, so htmlBody
        // must come back null rather than an empty string or a rendering
        // error — see NotificationRequest's own Javadoc on why null (not
        // "unused") is the deliberate contract here.
        NotificationRequest result = composer.compose(
                NotificationType.ORDER_CONFIRMED, NotificationChannel.SMS, "en", "+15551234567",
                Map.of("orderId", 12345, "unsubscribeUrl", "https://notify.example.com/api/v1/notifications/unsubscribe?token=abc"));

        assertThat(result.recipientAddress()).isEqualTo("+15551234567");
        assertThat(result.htmlBody()).isNull();
        assertThat(result.textBody()).contains("12345");
    }

    @Test
    void sms_setsTheSameSubjectAsEmail_evenThoughProvidersIgnoreIt() {
        // subjectFor doesn't branch on channel (see class Javadoc) — this
        // just pins that down so a future refactor doesn't accidentally
        // start returning null/blank subjects for SMS.
        NotificationRequest result = composer.compose(
                NotificationType.ORDER_CONFIRMED, NotificationChannel.SMS, "en", "+15551234567",
                Map.of("orderId", 12345));

        assertThat(result.subject()).isEqualTo("Your order #12345 is confirmed");
    }

    @Test
    void sms_throwsAClearError_whenNoSmsTemplateExistsForTheType() {
        // Mirrors throwsAClearError_whenNoTemplateExistsForTheType, but for
        // a type/channel pair where the .txt SMS template itself is
        // missing rather than the whole type being unimplemented.
        assertThatThrownBy(() -> composer.compose(
                NotificationType.SHIPMENT_SHIPPED, NotificationChannel.SMS, "en", "+15551234567",
                Map.of("orderId", 12345)))
                .isInstanceOf(RuntimeException.class);
    }
}
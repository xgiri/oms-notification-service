package com.giri.oms.common.config;

import org.springframework.boot.autoconfigure.thymeleaf.ThymeleafProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.templatemode.TemplateMode;

/**
 * Spring Boot's Thymeleaf autoconfiguration only wires up an HTML template
 * resolver by default. NotificationComposerImpl needs BOTH — the
 * {@code .html} body (HTML mode, the autoconfigured resolver already
 * handles this) AND the {@code .txt} multipart-fallback body, which uses
 * Thymeleaf's TEXT mode syntax ({@code [[${var}]]} inline expressions, no
 * tags) — see order-confirmed_email_en.txt. Without this second resolver,
 * processing a {@code .txt} template through the default (HTML-only)
 * engine either fails outright or mangles the plain-text output trying to
 * parse it as HTML.
 * <p>
 * Both resolvers share one {@code SpringTemplateEngine} bean (Spring's
 * default) — a template engine tries each of its resolvers in order and
 * uses whichever one's pattern matches the requested template name, so
 * NotificationComposerImpl's {@code templateEngine.process("...html", ...)}
 * and {@code templateEngine.process("...txt", ...)} calls both resolve
 * correctly through the same injected engine without it needing to know
 * which resolver serves which call.
 */
@Configuration
public class ThymeleafConfig {

    @Bean
    public SpringResourceTemplateResolver textTemplateResolver(ThymeleafProperties properties) {
        SpringResourceTemplateResolver resolver = new SpringResourceTemplateResolver();
        resolver.setPrefix(properties.getPrefix());
        resolver.setSuffix(".txt");
        resolver.setTemplateMode(TemplateMode.TEXT);
        resolver.setCharacterEncoding(properties.getEncoding().name());
        resolver.setCacheable(properties.isCache());
        // Higher order number = lower priority — the autoconfigured HTML
        // resolver (order 1) is tried first; this one (order 2) only
        // matches when the HTML resolver's own suffix (.html) doesn't.
        resolver.setOrder(2);
        resolver.setCheckExistence(true);
        return resolver;
    }
}

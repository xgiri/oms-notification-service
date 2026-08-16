package com.giri.oms.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.resource.OAuth2ResourceServerConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * JWT *verification* only — no signing/issuing here, same posture as
 * product-service/customer-service/shipment-service. Verifies against
 * oms-main's JWKS endpoint (see application.properties'
 * spring.security.oauth2.resourceserver.jwt.jwk-set-uri).
 * <p>
 * This only covers the handful of human-facing endpoints
 * (notification.controller — delivery history, resend, preferences). The
 * SERVICE-to-service calls this app makes OUT (CustomerClient/OrderClient)
 * use a completely different auth mechanism — see
 * customerclient.config.InternalServiceAuthInterceptor's Javadoc — and this
 * class has nothing to do with authenticating those.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/prometheus", "/docs/**", "/v3/api-docs/**").permitAll()
                        // Unsubscribe must work even if the rest of the system
                        // is down — see this service's README on why it's
                        // deliberately token-based, not JWT-authenticated (the
                        // recipient clicking an email link isn't logged in).
                        .requestMatchers("/api/v1/notifications/unsubscribe").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(OAuth2ResourceServerConfigurer::jwt);

        return http.build();
    }

    /**
     * Reads the "authorities" claim (a JSON array of strings, e.g.
     * {@code ["ROLE_ADMIN"]}) that oms-main's JwtService stamps onto every
     * token it issues — NOT the "scope"/"scp" claim Spring's default
     * {@code JwtGrantedAuthoritiesConverter} looks for, since these tokens
     * were never designed with OAuth2 scopes in mind. Same convention as
     * every other service in this system.
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authoritiesConverter = new JwtGrantedAuthoritiesConverter();
        authoritiesConverter.setAuthoritiesClaimName("authorities");
        authoritiesConverter.setAuthorityPrefix("");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);
        return converter;
    }
}

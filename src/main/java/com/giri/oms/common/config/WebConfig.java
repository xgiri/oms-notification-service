package com.giri.oms.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * /api/v1 prefix on every controller in notification.controller — same
 * convention as every other service in this system, so oms-gateway's
 * routing rules stay uniform across services.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix("/api/v1", c -> c.getPackageName().startsWith("com.giri.oms.notification.controller"));
    }
}

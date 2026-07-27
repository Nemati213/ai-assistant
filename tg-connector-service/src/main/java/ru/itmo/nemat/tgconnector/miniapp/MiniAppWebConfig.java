package ru.itmo.nemat.tgconnector.miniapp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class MiniAppWebConfig implements WebMvcConfigurer {

    private final String allowedOrigin;

    public MiniAppWebConfig(
            @Value("${app.mini-app.allowed-origin:}") String allowedOrigin
    ) {
        this.allowedOrigin = allowedOrigin;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (allowedOrigin == null || allowedOrigin.isBlank()) {
            return;
        }
        registry.addMapping("/api/miniapp/**")
                .allowedOrigins(allowedOrigin)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders(
                        "Content-Type",
                        MiniAppController.INIT_DATA_HEADER
                )
                .maxAge(3600);
    }
}

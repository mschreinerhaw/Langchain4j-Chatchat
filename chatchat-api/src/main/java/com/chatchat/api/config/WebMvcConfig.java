package com.chatchat.api.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import com.chatchat.api.security.ObjectAccessInterceptor;
import lombok.RequiredArgsConstructor;

/**
 * CORS and Web configuration for ChatChat API
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {
    private final ObjectAccessInterceptor objectAccess;
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(objectAccess).addPathPatterns("/api/v1/**");
    }

    /**
     * Adds the cors mappings.
     *
     * @param registry the registry value
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
            .allowedOriginPatterns("*")
            .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
            .allowedHeaders("*")
            .allowCredentials(true)
            .maxAge(3600);

        registry.addMapping("/swagger-ui/**")
            .allowedOriginPatterns("*");

        registry.addMapping("/v3/api-docs/**")
            .allowedOriginPatterns("*");
    }
}

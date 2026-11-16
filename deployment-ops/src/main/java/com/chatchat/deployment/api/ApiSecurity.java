package com.chatchat.deployment.api;

import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class ApiSecurity {
    @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
        // /api routes enforce persisted sessions, roles and X-Ops-Request on mutations.
        return http.csrf(csrf->csrf.disable()).authorizeHttpRequests(auth->auth
            .requestMatchers("/actuator/health","/api/**","/","/index.html","/js/**","/css/**","/favicon.ico","/legacy/**").permitAll()
            .anyRequest().denyAll()).build();
    }
}

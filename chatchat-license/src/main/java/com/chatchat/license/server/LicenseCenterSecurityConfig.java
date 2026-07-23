package com.chatchat.license.server;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class LicenseCenterSecurityConfig {

    @Bean
    SecurityFilterChain licenseCenterSecurity(HttpSecurity http) throws Exception {
        return http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
            .httpBasic(Customizer.withDefaults())
            .formLogin(Customizer.withDefaults())
            .build();
    }

    @Bean
    UserDetailsService licenseCenterUsers(LicenseCenterProperties properties) {
        if (properties.getPassword() == null || properties.getPassword().isBlank()) {
            throw new IllegalStateException("必须配置 CHATCHAT_LICENSE_CENTER_PASSWORD");
        }
        String encoded = properties.getPassword().startsWith("{")
            ? properties.getPassword() : "{noop}" + properties.getPassword();
        return new InMemoryUserDetailsManager(User.withUsername(properties.getUsername())
            .password(encoded).roles("LICENSE_ADMIN").build());
    }
}

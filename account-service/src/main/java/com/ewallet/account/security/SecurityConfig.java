package com.ewallet.account.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, BearerTokenFilter bearerTokenFilter) throws Exception {
        return http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.POST, "/api/auth/pin/verify").authenticated()
                .requestMatchers("/actuator/health", "/api/auth/**", "/error").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/accounts").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/accounts/*/deposit").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/accounts/*/withdraw").hasAnyRole("USER", "ADMIN")
                .requestMatchers("/internal/ai/**").hasRole("ADMIN")
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/audit/**", "/api/audit").hasRole("ADMIN")
                .requestMatchers("/api/reconciliation/**", "/api/reconciliation").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            .addFilterBefore(bearerTokenFilter, UsernamePasswordAuthenticationFilter.class)
            .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}

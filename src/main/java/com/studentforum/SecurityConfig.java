package com.studentforum;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

@Configuration
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
    @Bean HttpSessionSecurityContextRepository contextRepository() { return new HttpSessionSecurityContextRepository(); }
    @Bean SecurityFilterChain security(HttpSecurity http, HttpSessionSecurityContextRepository repository) throws Exception {
        http.securityContext(config -> config.securityContextRepository(repository))
            .csrf(config -> config.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()))
            .authorizeHttpRequests(config -> config.requestMatchers("/api/admin/**").hasAnyRole("ADMIN", "MODERATOR")
                .requestMatchers(HttpMethod.GET, "/api/groups/**", "/api/boards/**", "/api/posts/*", "/api/posts/*/replies", "/api/posts/*/vote", "/api/posts/*/bounty", "/api/posts/*/revisions/**", "/api/search", "/api/tags/**", "/api/levels", "/api/users/**", "/uploads/**").permitAll()
                .requestMatchers("/api/auth/register", "/api/auth/login", "/api/csrf", "/", "/index.html", "/assets/**").permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(config -> config.authenticationEntryPoint((request,response,error) -> response.sendError(HttpServletResponse.SC_UNAUTHORIZED)))
            .addFilterAfter(new OncePerRequestFilter() {
                @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
                    CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
                    if (token != null) token.getToken();
                    chain.doFilter(request,response);
                }
            }, org.springframework.security.web.csrf.CsrfFilter.class);
        return http.build();
    }
}
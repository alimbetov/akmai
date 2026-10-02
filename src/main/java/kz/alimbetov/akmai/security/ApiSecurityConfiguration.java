package kz.alimbetov.akmai.security;

import kz.alimbetov.akmai.config.SecurityProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class ApiSecurityConfiguration {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            SecurityProperties properties,
            ApiKeyAuthenticationFilter apiKeyFilter
    ) throws Exception {
        http.csrf(csrf -> csrf.disable());
        http.sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
        );
        http.authorizeHttpRequests(auth -> {
            auth.requestMatchers("/actuator/health/**").permitAll();
            if (properties.enabled()) {
                auth.requestMatchers("/api/**").hasRole("API");
            } else {
                auth.requestMatchers("/api/**").permitAll();
            }
            auth.anyRequest().permitAll();
        });
        http.addFilterBefore(
                apiKeyFilter,
                UsernamePasswordAuthenticationFilter.class
        );
        return http.build();
    }
}

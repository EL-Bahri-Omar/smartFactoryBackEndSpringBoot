package com.smartfactory.config;

import com.smartfactory.security.CustomAccessDeniedHandler;
import com.smartfactory.security.JwtAuthenticationEntryPoint;
import com.smartfactory.security.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;
    private final CustomAccessDeniedHandler customAccessDeniedHandler;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint,
                          CustomAccessDeniedHandler customAccessDeniedHandler) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.jwtAuthenticationEntryPoint = jwtAuthenticationEntryPoint;
        this.customAccessDeniedHandler = customAccessDeniedHandler;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(jwtAuthenticationEntryPoint)
                        .accessDeniedHandler(customAccessDeniedHandler)
                )
                .authorizeHttpRequests(auth -> auth
                        // Public endpoints
                        .requestMatchers("/api/auth/login").permitAll()
                        .requestMatchers("/api/auth/logout").permitAll()
                        .requestMatchers("/api/auth/register").permitAll()
                        .requestMatchers("/api/auth/verify-email").permitAll()
                        .requestMatchers("/api/auth/resend-verification").permitAll()
                        .requestMatchers("/api/auth/forgot-password").permitAll()
                        .requestMatchers("/api/auth/verify-reset-otp").permitAll()
                        .requestMatchers("/api/auth/reset-password").permitAll()
                        .requestMatchers("/ws/**").permitAll()

                        // Self-service profile & catalog - any authenticated user
                        .requestMatchers(HttpMethod.PUT, "/api/users/me/**").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/users/permissions/catalog").authenticated()

                        // User management - fine-grained authority check + @PreAuthorize
                        .requestMatchers(HttpMethod.GET, "/api/users/**").hasAnyAuthority("ROLE_ADMIN", "USER_READ")
                        .requestMatchers(HttpMethod.POST, "/api/users/**").hasAnyAuthority("ROLE_ADMIN", "USER_WRITE", "USER_CREATE")
                        .requestMatchers(HttpMethod.PUT, "/api/users/**").hasAnyAuthority("ROLE_ADMIN", "USER_WRITE", "USER_UPDATE", "USER_PERMISSIONS_MANAGE")
                        .requestMatchers(HttpMethod.DELETE, "/api/users/**").hasAnyAuthority("ROLE_ADMIN", "USER_WRITE", "USER_DELETE")

                        // Role management - dynamic RBAC (Part C)
                        .requestMatchers(HttpMethod.GET, "/api/roles/**").hasAnyAuthority("ROLE_ADMIN", "ROLE_READ")
                        .requestMatchers(HttpMethod.POST, "/api/roles/**").hasAnyAuthority("ROLE_ADMIN", "ROLE_CREATE", "ROLE_UPDATE")
                        .requestMatchers(HttpMethod.PUT, "/api/roles/**").hasAnyAuthority("ROLE_ADMIN", "ROLE_UPDATE")
                        .requestMatchers(HttpMethod.DELETE, "/api/roles/**").hasAnyAuthority("ROLE_ADMIN", "ROLE_DELETE")

                        // Machines & thresholds & readings: fine-grained authorities
                        .requestMatchers(HttpMethod.POST, "/api/machines/**").hasAnyAuthority("ROLE_ADMIN", "MACHINE_WRITE", "MACHINE_CREATE", "THRESHOLD_WRITE")
                        .requestMatchers(HttpMethod.PUT, "/api/machines/**").hasAnyAuthority("ROLE_ADMIN", "MACHINE_WRITE", "MACHINE_UPDATE", "THRESHOLD_WRITE")
                        .requestMatchers(HttpMethod.DELETE, "/api/machines/**").hasAnyAuthority("ROLE_ADMIN", "MACHINE_WRITE", "MACHINE_DELETE", "THRESHOLD_WRITE")
                        .requestMatchers(HttpMethod.GET, "/api/machines/**").hasAnyAuthority("ROLE_ADMIN", "MACHINE_READ", "THRESHOLD_READ", "READINGS_READ", "SENSOR_READ", "SENSOR_HISTORY_READ")

                        // Zones: write requires ZONE_WRITE, read requires ZONE_READ
                        .requestMatchers(HttpMethod.POST, "/api/zones/**").hasAnyAuthority("ROLE_ADMIN", "ZONE_WRITE")
                        .requestMatchers(HttpMethod.PUT, "/api/zones/**").hasAnyAuthority("ROLE_ADMIN", "ZONE_WRITE")
                        .requestMatchers(HttpMethod.DELETE, "/api/zones/**").hasAnyAuthority("ROLE_ADMIN", "ZONE_WRITE")
                        .requestMatchers(HttpMethod.GET, "/api/zones/**").hasAnyAuthority("ROLE_ADMIN", "ZONE_READ")

                        // Groups: write requires GROUP_WRITE, read requires GROUP_READ
                        .requestMatchers(HttpMethod.POST, "/api/groups/**").hasAnyAuthority("ROLE_ADMIN", "GROUP_WRITE")
                        .requestMatchers(HttpMethod.PUT, "/api/groups/**").hasAnyAuthority("ROLE_ADMIN", "GROUP_WRITE")
                        .requestMatchers(HttpMethod.DELETE, "/api/groups/**").hasAnyAuthority("ROLE_ADMIN", "GROUP_WRITE")
                        .requestMatchers(HttpMethod.GET, "/api/groups/**").hasAnyAuthority("ROLE_ADMIN", "GROUP_READ")

                        // Functional modules & privileges (Partie 2.4)
                        .requestMatchers(HttpMethod.GET, "/api/modules/**").hasAnyAuthority("ROLE_ADMIN", "ROLE_READ", "USER_READ")
                        .requestMatchers(HttpMethod.POST, "/api/modules/**").hasAnyAuthority("ROLE_ADMIN", "ROLE_CREATE", "ROLE_UPDATE")
                        .requestMatchers(HttpMethod.PUT, "/api/modules/**").hasAnyAuthority("ROLE_ADMIN", "ROLE_UPDATE")
                        .requestMatchers(HttpMethod.DELETE, "/api/modules/**").hasAnyAuthority("ROLE_ADMIN", "ROLE_DELETE")

                        // Weekly history databases & retention (Partie 2.2)
                        .requestMatchers(HttpMethod.GET, "/api/history/**").hasAnyAuthority("ROLE_ADMIN", "READINGS_READ", "DASHBOARD_READ")
                        .requestMatchers(HttpMethod.POST, "/api/history/**").hasAuthority("ROLE_ADMIN")

                        // Alerts & Anomalies (Sprint 5)
                        .requestMatchers(HttpMethod.GET, "/api/alerts/**", "/api/anomalies/**").hasAnyAuthority("ROLE_ADMIN", "ALERT_READ", "ALERT:CONSULTER", "TECHNICIAN", "OPERATOR", "RESPONSABLE_INDUSTRIEL")
                        .requestMatchers(HttpMethod.POST, "/api/alerts/**").hasAnyAuthority("ROLE_ADMIN", "ALERT_WRITE", "ALERT:ACQUITTER", "ALERT:MODIFIER", "TECHNICIAN", "RESPONSABLE_INDUSTRIEL")
                        .requestMatchers(HttpMethod.PUT, "/api/alerts/**", "/api/anomalies/**").hasAnyAuthority("ROLE_ADMIN", "ALERT_ACKNOWLEDGE", "ALERT_WRITE", "ALERT:MODIFIER", "TECHNICIAN", "RESPONSABLE_INDUSTRIEL")

                        // Maintenance & Interventions (Sprint 6)
                        .requestMatchers(HttpMethod.GET, "/api/maintenance/**").hasAnyAuthority("ROLE_ADMIN", "MAINTENANCE_READ", "READINGS_READ", "TECHNICIAN", "RESPONSABLE_INDUSTRIEL")
                        .requestMatchers(HttpMethod.POST, "/api/maintenance/**").hasAnyAuthority("ROLE_ADMIN", "MAINTENANCE_WRITE", "TECHNICIAN", "RESPONSABLE_INDUSTRIEL")
                        .requestMatchers(HttpMethod.PUT, "/api/maintenance/**").hasAnyAuthority("ROLE_ADMIN", "MAINTENANCE_WRITE", "TECHNICIAN", "RESPONSABLE_INDUSTRIEL")

                        // Notifications & device tokens
                        .requestMatchers("/api/notifications/**").authenticated()

                        // Any other request requires authentication
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of("*"));
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(Arrays.asList("Authorization", "Content-Type", "X-Requested-With", "Accept"));
        configuration.setExposedHeaders(List.of("Authorization"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}

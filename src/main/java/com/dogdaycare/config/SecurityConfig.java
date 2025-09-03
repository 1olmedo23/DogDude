package com.dogdaycare.config;

import com.dogdaycare.service.CustomAuthenticationSuccessHandler;
import com.dogdaycare.service.CustomUserDetailsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity // enables @PreAuthorize in controllers/services
public class SecurityConfig {

    private final CustomUserDetailsService userDetailsService;
    private final CustomAuthenticationSuccessHandler customAuthenticationSuccessHandler;

    public SecurityConfig(CustomUserDetailsService userDetailsService,
                          CustomAuthenticationSuccessHandler customAuthenticationSuccessHandler) {
        this.userDetailsService = userDetailsService;
        this.customAuthenticationSuccessHandler = customAuthenticationSuccessHandler;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public DaoAuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // Authorization rules
                .authorizeHttpRequests(auth -> auth
                        // Public pages & static assets
                        .requestMatchers("/", "/login", "/evaluation", "/evaluation/**", "/services", "/about",
                                "/css/**", "/js/**", "/images/**").permitAll()

                        // Admin area
                        .requestMatchers("/admin/**").hasRole("ADMIN")

                        // Customer area
                        .requestMatchers("/booking/**").hasRole("CUSTOMER")
                        .requestMatchers("/uploads/**").hasRole("CUSTOMER")

                        // Everything else requires auth
                        .anyRequest().authenticated()
                )

                // Form login
                .formLogin(form -> form
                        .loginPage("/login")
                        .failureUrl("/login?error=true")
                        .successHandler(customAuthenticationSuccessHandler)
                        .permitAll()
                )

                // Logout
                .logout(logout -> logout
                        .logoutSuccessUrl("/?logout")
                        .permitAll()
                );

        // CSRF remains enabled by default (good for forms), no extra config needed
        return http.build();
    }
}

package com.mengwei.localgrowth.config;
import org.springframework.context.annotation.*; import org.springframework.security.config.annotation.web.builders.HttpSecurity; import org.springframework.security.web.SecurityFilterChain;
@Configuration public class SecurityConfiguration { @Bean SecurityFilterChain filterChain(HttpSecurity http)throws Exception{return http.csrf(c->c.disable()).authorizeHttpRequests(a->a.requestMatchers("/api/v1/**","/swagger-ui/**","/v3/api-docs/**").permitAll().anyRequest().permitAll()).build();} }


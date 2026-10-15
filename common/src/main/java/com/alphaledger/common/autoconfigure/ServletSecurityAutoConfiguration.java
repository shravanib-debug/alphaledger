package com.alphaledger.common.autoconfigure;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

import tools.jackson.databind.json.JsonMapper;

import com.alphaledger.common.security.JwtProperties;
import com.alphaledger.common.security.JwtSupport;
import com.alphaledger.common.security.SecurityErrorResponses;
import com.alphaledger.common.security.SecurityExceptionHandler;
import com.alphaledger.common.security.SecurityPathsProperties;

/**
 * Stateless JWT security for every servlet service that has the OAuth2 resource-server starter.
 * Defense in depth (architecture.md §8): the gateway validates the token, and each service validates it
 * again and enforces roles with {@code @PreAuthorize}.
 */
@AutoConfiguration(beforeName = {
        "org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration",
        "org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration",
        "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration",
        "org.springframework.boot.security.oauth2.server.resource.autoconfigure.servlet.OAuth2ResourceServerAutoConfiguration"
})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({SecurityFilterChain.class, JwtDecoder.class})
@EnableConfigurationProperties({JwtProperties.class, SecurityPathsProperties.class})
@EnableMethodSecurity
public class ServletSecurityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public JwtDecoder jwtDecoder(JwtProperties properties) {
        return JwtSupport.decoder(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public SecurityErrorResponses securityErrorResponses(ObjectProvider<JsonMapper> jsonMapper) {
        return new SecurityErrorResponses(jsonMapper.getIfAvailable(JsonMapper::new));
    }

    @Bean
    @ConditionalOnMissingBean
    public SecurityExceptionHandler securityExceptionHandler() {
        return new SecurityExceptionHandler();
    }

    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    public SecurityFilterChain jwtSecurityFilterChain(HttpSecurity http, SecurityPathsProperties paths,
                                                      SecurityErrorResponses errors) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(paths.allPublicPaths()).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(JwtSupport.authenticationConverter()))
                        .authenticationEntryPoint(errors.authenticationEntryPoint())
                        .accessDeniedHandler(errors.accessDeniedHandler()))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(errors.authenticationEntryPoint())
                        .accessDeniedHandler(errors.accessDeniedHandler()));
        return http.build();
    }
}

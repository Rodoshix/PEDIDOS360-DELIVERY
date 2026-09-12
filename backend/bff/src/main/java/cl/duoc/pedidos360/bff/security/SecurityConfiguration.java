package cl.duoc.pedidos360.bff.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/** JWT opt-in: sin configuración de Entra, todo salvo salud permanece cerrado. */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
            org.springframework.core.env.Environment env,
            org.springframework.beans.factory.ObjectProvider<org.springframework.security.oauth2.jwt.JwtDecoder> decoders) throws Exception {
        boolean enabled = env.getProperty("entra.enabled", Boolean.class, false);
        if (enabled) {
            http.oauth2ResourceServer(resource -> resource.jwt(jwt -> jwt.decoder(decoders.getObject())
                    .jwtAuthenticationConverter(token -> new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(
                            token, EntraConfiguration.authorities(token)))));
        }
        return http
                .cors(org.springframework.security.config.Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers("/actuator/health").permitAll();
                    auth.dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll();
                    if (enabled) auth.requestMatchers("/usuarios", "/usuarios/**", "/restaurantes", "/restaurantes/**",
                            "/productos", "/productos/**", "/carrito", "/carrito/**").access((authentication, context) -> {
                        var authorities = authentication.get().getAuthorities().stream()
                                .map(org.springframework.security.core.GrantedAuthority::getAuthority).toList();
                        return new org.springframework.security.authorization.AuthorizationDecision(
                                authorities.contains("SCOPE_access_as_user")
                                && (authorities.contains("ROLE_CLIENTE") || authorities.contains("ROLE_ADMIN")));
                    });
                    auth.anyRequest().denyAll();
                })
                .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, error) -> {
                    response.setStatus(401);
                    response.setContentType("application/problem+json");
                    response.getWriter().write("{\"status\":401,\"title\":\"Se requiere autenticación\"}");
                }))
                .build();
    }
}

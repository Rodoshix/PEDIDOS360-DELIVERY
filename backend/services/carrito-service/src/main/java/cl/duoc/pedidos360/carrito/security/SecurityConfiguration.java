package cl.duoc.pedidos360.carrito.security;

import java.io.IOException;
import java.net.URI;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
            ObjectProvider<IdentidadUsuario> identidadLocal, JsonMapper mapper,
            org.springframework.core.env.Environment env,
            ObjectProvider<org.springframework.security.oauth2.jwt.JwtDecoder> decoders) throws Exception {
        var identidad = identidadLocal.getIfAvailable();
        boolean enabled = env.getProperty("entra.enabled", Boolean.class, false);
        if (enabled && identidad != null) throw new IllegalStateException("JWT e identidad local no pueden coexistir.");
        if (enabled) {
            http.oauth2ResourceServer(resource -> resource.jwt(jwt -> jwt.decoder(decoders.getObject())
                    .jwtAuthenticationConverter(token -> new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(
                            token, EntraConfiguration.authorities(token)))));
        }
        http.csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    auth.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                            .requestMatchers("/actuator/health", "/actuator/health/**").permitAll();
                    if (enabled) {
                        auth.requestMatchers("/carrito", "/carrito/**").access((authentication, context) -> {
                            var authorities = authentication.get().getAuthorities().stream()
                                    .map(org.springframework.security.core.GrantedAuthority::getAuthority).toList();
                            return new org.springframework.security.authorization.AuthorizationDecision(
                                    authorities.contains("SCOPE_access_as_user")
                                    && (authorities.contains("ROLE_CLIENTE") || authorities.contains("ROLE_ADMIN")));
                        });
                    } else if (identidad != null) {
                        auth.requestMatchers("/carrito", "/carrito/**").authenticated();
                    }
                    auth.anyRequest().denyAll();
                })
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) ->
                                escribirError(mapper, request, response, HttpStatus.UNAUTHORIZED,
                                        "Se requiere autenticación."))
                        .accessDeniedHandler((request, response, exception) ->
                                escribirError(mapper, request, response, HttpStatus.FORBIDDEN,
                                        "No tienes permiso para esta operación.")));
        if (identidad != null) {
            http.addFilterBefore(new LocalIdentityFilter(identidad), AnonymousAuthenticationFilter.class);
        }
        return http.build();
    }

    private void escribirError(JsonMapper mapper, HttpServletRequest request,
            HttpServletResponse response, HttpStatus status, String detail) throws IOException {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setInstance(URI.create(request.getRequestURI()));
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), problem);
    }
}

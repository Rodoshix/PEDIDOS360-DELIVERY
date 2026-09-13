package cl.duoc.pedidos360.pedidos.security;

import java.io.IOException;
import java.net.URI;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Dos políticas independientes:
 * <ol>
 *   <li><b>Interna</b> ({@code /internal/**}): token de aplicación del worker de Pagos
 *       (rol {@code Pedidos.Confirmar}). No accesible por tokens de usuario ni vía BFF/CORS.</li>
 *   <li><b>Delegada</b> (resto): identidad de usuario validada por el BFF (o identidad local en dev).</li>
 * </ol>
 * Se separan por {@code securityMatcher}, no ampliando un validador común.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfiguration {

    /** Cadena del endpoint interno: solo el worker con el rol de aplicación. */
    @Bean
    @Order(1)
    @ConditionalOnProperty(name = "pedidos.interno.enabled", havingValue = "true")
    SecurityFilterChain cadenaInterna(HttpSecurity http, JwtDecoder jwtDecoderInterno,
            JwtAuthenticationConverter jwtAuthenticationConverterInterno, JsonMapper mapper) throws Exception {
        return http.securityMatcher("/internal/**")
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .oauth2ResourceServer(resource -> resource.jwt(jwt -> jwt
                        .decoder(jwtDecoderInterno)
                        .jwtAuthenticationConverter(jwtAuthenticationConverterInterno)))
                .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("Pedidos.Confirmar"))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) ->
                                escribirError(mapper, request, response, HttpStatus.UNAUTHORIZED,
                                        "Se requiere un token de aplicación con el rol Pedidos.Confirmar."))
                        .accessDeniedHandler((request, response, exception) ->
                                escribirError(mapper, request, response, HttpStatus.FORBIDDEN,
                                        "El token no tiene el rol Pedidos.Confirmar.")))
                .build();
    }

    /** Cadena delegada: las rutas de usuario y la identidad local de desarrollo. */
    @Bean
    @Order(2)
    SecurityFilterChain cadenaDelegada(HttpSecurity http,
            ObjectProvider<IdentidadUsuario> identidadLocal, JsonMapper mapper) throws Exception {
        var identidad = identidadLocal.getIfAvailable();
        http.csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    auth.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                            .requestMatchers("/actuator/health", "/actuator/health/**").permitAll();
                    if (identidad != null) {
                        auth.requestMatchers("/pedidos", "/pedidos/**", "/usuarios/**").authenticated();
                    }
                    auth.anyRequest().denyAll();
                })
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) ->
                                escribirError(mapper, request, response, HttpStatus.UNAUTHORIZED,
                                        "Se requiere autenticación. La identidad la valida el BFF."))
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

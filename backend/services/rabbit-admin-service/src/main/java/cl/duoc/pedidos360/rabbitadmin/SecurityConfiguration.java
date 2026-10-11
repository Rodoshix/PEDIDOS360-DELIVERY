package cl.duoc.pedidos360.rabbitadmin;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods=false)
public class SecurityConfiguration {
    @Bean SecurityFilterChain security(HttpSecurity http,JwtDecoder decoder,JsonMapper json) throws Exception {
        http.oauth2ResourceServer(r -> r.jwt(j -> j.decoder(decoder).jwtAuthenticationConverter(
            token -> new JwtAuthenticationToken(token,EntraConfiguration.authorities(token)))));
        http.csrf(AbstractHttpConfigurer::disable).formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable).logout(AbstractHttpConfigurer::disable)
            .requestCache(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers("/admin/rabbit/**").access((auth,context) -> {
                    var roles=auth.get().getAuthorities().stream().map(x -> x.getAuthority()).toList();
                    return new org.springframework.security.authorization.AuthorizationDecision(
                        roles.contains("SCOPE_access_as_user") && roles.contains("ROLE_ADMIN"));
                }).anyRequest().denyAll());
        org.springframework.security.web.AuthenticationEntryPoint unauthenticated=(req,res,e) -> {
            res.setStatus(401); res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            json.writeValue(res.getOutputStream(),ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED,"Se requiere JWT válido."));
        };
        org.springframework.security.web.access.AccessDeniedHandler denied=(req,res,e) -> {
            res.setStatus(403); res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            json.writeValue(res.getOutputStream(),ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,"Se requiere ADMIN y access_as_user."));
        };
        http.exceptionHandling(e -> e.authenticationEntryPoint(unauthenticated).accessDeniedHandler(denied));
        http.oauth2ResourceServer(r -> r.authenticationEntryPoint(unauthenticated).accessDeniedHandler(denied));
        return http.build();
    }
}

package cl.duoc.pedidos360.bff.config;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration(proxyBeanMethods = false)
public class ConnectionConfiguration {
    public static URI origin(String value) {
        var uri = URI.create(value);
        boolean local = List.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost() == null ? "" : uri.getHost());
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null || !uri.getRawPath().isEmpty()
                || !("https".equals(uri.getScheme()) || (local && "http".equals(uri.getScheme())))
                || uri.getPort() == 0 || uri.getPort() > 65535) {
            throw new IllegalArgumentException("Configurar un origen HTTPS sin ruta, o HTTP loopback local.");
        }
        return uri;
    }

    @Bean(destroyMethod = "close")
    HttpClient upstreamHttpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Bean
    UrlBasedCorsConfigurationSource corsConfigurationSource(Environment env) {
        var configured = env.getProperty("bff.cors-origins", "http://localhost:5173,http://localhost:5180");
        var origins = configured.isBlank() ? List.<String>of()
                : Arrays.stream(configured.split(",", -1)).map(String::strip).map(value -> origin(value).toString()).toList();
        var cors = new CorsConfiguration();
        cors.setAllowedOrigins(origins);
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
        cors.setExposedHeaders(List.of("Location"));
        cors.setAllowCredentials(false);
        cors.setMaxAge(600L);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/usuarios", cors);
        source.registerCorsConfiguration("/usuarios/**", cors);
        for (String path : List.of("/restaurantes", "/productos", "/carrito", "/pedidos", "/pagos")) {
            source.registerCorsConfiguration(path, cors);
            source.registerCorsConfiguration(path + "/**", cors);
        }
        var pagos = new CorsConfiguration(cors);
        pagos.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "Idempotency-Key"));
        source.registerCorsConfiguration("/pagos", pagos);
        return source;
    }
}

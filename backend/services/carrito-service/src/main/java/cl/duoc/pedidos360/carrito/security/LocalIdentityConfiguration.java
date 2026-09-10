package cl.duoc.pedidos360.carrito.security;

import java.util.Set;

import cl.duoc.pedidos360.carrito.service.CatalogoProductos;
import cl.duoc.pedidos360.carrito.service.CatalogoLocal;

import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LocalIdentityProperties.class)
@ConditionalOnProperty(name = "carrito.identidad-local.enabled", havingValue = "true")
public class LocalIdentityConfiguration {

    @Bean
    CatalogoProductos catalogoLocal(IdentidadUsuario identidadLocal) {
        // Depende de la identidad para exigir primero la validación de perfil y loopback.
        return new CatalogoLocal();
    }

    @Bean
    IdentidadUsuario identidadLocal(LocalIdentityProperties properties, Environment environment) {
        String[] profiles = environment.getActiveProfiles();
        String address = environment.getProperty("server.address", "");
        if (profiles.length != 1 || !"local".equals(profiles[0])
                || !Set.of("127.0.0.1", "::1").contains(address)) {
            throw new IllegalStateException(
                    "La identidad de prueba requiere solo el perfil local y escucha en loopback.");
        }
        if (properties.tenantId() == null || properties.objectId() == null
                || properties.roles() == null || properties.roles().isEmpty()) {
            throw new IllegalStateException("La identidad local requiere tenant-id, object-id y roles.");
        }
        LoggerFactory.getLogger(LocalIdentityConfiguration.class)
                .warn("IDENTIDAD LOCAL SIMULADA ACTIVADA: solo para desarrollo, sin tokens Entra ID.");
        return new IdentidadUsuario(properties.tenantId(), properties.objectId(), properties.roles());
    }
}

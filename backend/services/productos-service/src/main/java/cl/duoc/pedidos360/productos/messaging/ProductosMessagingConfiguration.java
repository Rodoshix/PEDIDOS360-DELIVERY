package cl.duoc.pedidos360.productos.messaging;

import cl.duoc.pedidos360.messaging.Domain;
import cl.duoc.pedidos360.messaging.MessagingProperties;
import cl.duoc.pedidos360.messaging.QueryConsumerConfiguration;
import cl.duoc.pedidos360.messaging.QueryMessagingConfiguration;
import cl.duoc.pedidos360.messaging.QueryTopology;
import cl.duoc.pedidos360.messaging.relay.QueryPrecheck;
import cl.duoc.pedidos360.productos.service.ProductoService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import tools.jackson.databind.json.JsonMapper;

/** Configuracion separada del dominio; plataforma #69 posee colas/policies/permisos. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pedidos360.messaging", name = "relay-mode", havingValue = "ACTIVE")
@Import({QueryMessagingConfiguration.class, QueryConsumerConfiguration.class})
public class ProductosMessagingConfiguration {
    @Bean
    QueryTopology queryTopology(MessagingProperties properties) {
        return QueryTopology.of(properties, Domain.PRODUCTOS);
    }

    @Bean
    ProductosQueryProcessor productosQueryProcessor(ProductoService productos, JsonMapper json) {
        return new ProductosQueryProcessor(productos, json);
    }

    @Bean
    @org.springframework.context.annotation.Primary
    QueryPrecheck productosQueryPrecheck() {
        return actor -> {
            if (!actor.tieneScope("access_as_user")
                    || !(actor.tieneRol("CLIENTE") || actor.tieneRol("ADMIN"))) {
                throw new AccessDeniedException("La consulta requiere access_as_user y rol CLIENTE o ADMIN.");
            }
        };
    }
}

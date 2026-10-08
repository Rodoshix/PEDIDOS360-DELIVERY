package cl.duoc.pedidos360.usuarios.messaging;

import cl.duoc.pedidos360.messaging.Domain;
import cl.duoc.pedidos360.messaging.MessagingProperties;
import cl.duoc.pedidos360.messaging.QueryConsumerConfiguration;
import cl.duoc.pedidos360.messaging.QueryMessagingConfiguration;
import cl.duoc.pedidos360.messaging.QueryTopology;
import cl.duoc.pedidos360.messaging.relay.QueryPrecheck;
import cl.duoc.pedidos360.usuarios.service.UsuarioService;
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
public class UsuariosMessagingConfiguration {
    @Bean
    QueryTopology queryTopology(MessagingProperties properties) {
        return QueryTopology.of(properties, Domain.USUARIOS);
    }

    @Bean
    UsuariosQueryProcessor usuariosQueryProcessor(UsuarioService usuarios, JsonMapper json) {
        return new UsuariosQueryProcessor(usuarios, json);
    }

    @Bean
    @org.springframework.context.annotation.Primary
    QueryPrecheck usuariosQueryPrecheck() {
        return actor -> {
            if (!actor.tieneScope("access_as_user")
                    || !(actor.tieneRol("CLIENTE") || actor.tieneRol("ADMIN"))) {
                throw new AccessDeniedException("La consulta requiere access_as_user y rol CLIENTE o ADMIN.");
            }
        };
    }
}

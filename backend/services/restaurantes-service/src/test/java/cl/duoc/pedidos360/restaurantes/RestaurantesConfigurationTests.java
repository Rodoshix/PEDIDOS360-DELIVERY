package cl.duoc.pedidos360.restaurantes;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.access.AccessDeniedException;
import cl.duoc.pedidos360.messaging.actor.ActorContext;
import cl.duoc.pedidos360.messaging.relay.QueryConsumer;
import cl.duoc.pedidos360.restaurantes.messaging.RestaurantesMessagingConfiguration;
import cl.duoc.pedidos360.restaurantes.messaging.RestaurantesQueryProcessor;

class RestaurantesConfigurationTests {
    @Test void defaultDoesNotCreateAnyMessagingBeans() {
        new ApplicationContextRunner().withUserConfiguration(RestaurantesMessagingConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean(QueryConsumer.class)
                            .doesNotHaveBean(RestaurantesQueryProcessor.class)
                            .doesNotHaveBean("queryTopology").doesNotHaveBean("queryListenerFactory");
                });
    }

    ActorContext actor(Set<String> roles, Set<String> scopes) {
        return new ActorContext(UUID.randomUUID(), UUID.randomUUID(), roles, scopes, Instant.now(),
                Instant.now().plusSeconds(4), "p360.restaurantes.consultas.q", UUID.randomUUID());
    }

    @Test void clienteAndAdminWithScopeAreAllowed() throws Exception {
        var method = RestaurantesMessagingConfiguration.class.getDeclaredMethod("restaurantesQueryPrecheck");
        method.setAccessible(true);
        var check = (cl.duoc.pedidos360.messaging.relay.QueryPrecheck) method.invoke(new RestaurantesMessagingConfiguration());
        check.validar(actor(Set.of("CLIENTE"), Set.of("access_as_user")));
        check.validar(actor(Set.of("ADMIN"), Set.of("access_as_user")));
    }

    @Test void scopeAndRoleAreBothRequired() throws Exception {
        var method = RestaurantesMessagingConfiguration.class.getDeclaredMethod("restaurantesQueryPrecheck");
        method.setAccessible(true);
        var check = (cl.duoc.pedidos360.messaging.relay.QueryPrecheck) method.invoke(new RestaurantesMessagingConfiguration());
        assertThatThrownBy(() -> check.validar(actor(Set.of("REPARTIDOR"), Set.of("access_as_user"))))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> check.validar(actor(Set.of("ADMIN"), Set.of())))
                .isInstanceOf(AccessDeniedException.class);
    }
}

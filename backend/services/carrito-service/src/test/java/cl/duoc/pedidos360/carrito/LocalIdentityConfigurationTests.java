package cl.duoc.pedidos360.carrito;

import cl.duoc.pedidos360.carrito.security.IdentidadUsuario;
import cl.duoc.pedidos360.carrito.security.LocalIdentityConfiguration;
import cl.duoc.pedidos360.carrito.service.CatalogoProductos;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class LocalIdentityConfigurationTests {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(LocalIdentityConfiguration.class)
            .withPropertyValues(
                    "carrito.identidad-local.enabled=true",
                    "carrito.identidad-local.tenant-id=11111111-1111-1111-1111-111111111111",
                    "carrito.identidad-local.object-id=22222222-2222-2222-2222-222222222222",
                    "carrito.identidad-local.roles=CLIENTE",
                    "server.address=127.0.0.1");

    @Test
    void noActivaIdentidadSinPerfilLocalOEnProduccion() {
        context.run(result -> assertThat(result).hasFailed());
        context.withPropertyValues("spring.profiles.active=prod")
                .run(result -> assertThat(result).hasFailed());
        context.withPropertyValues("spring.profiles.active=local,prod")
                .run(result -> assertThat(result).hasFailed());
    }

    @Test
    void noPermiteEscucharEnTodasLasInterfacesConIdentidadFalsa() {
        for (String address : new String[] { "0.0.0.0", "::", "localhost", "192.0.2.10", "" }) {
            context.withPropertyValues("spring.profiles.active=local", "server.address=" + address)
                    .run(result -> assertThat(result).hasFailed());
        }
    }

    @Test
    void permiteLocalCompletoYExigeHabilitacionExplicita() {
        context.withPropertyValues("spring.profiles.active=local")
                .run(result -> {
                    assertThat(result).hasSingleBean(IdentidadUsuario.class);
                    assertThat(result).hasSingleBean(CatalogoProductos.class);
                });
        context.withPropertyValues("spring.profiles.active=local", "carrito.identidad-local.enabled=false")
                .run(result -> {
                    assertThat(result).doesNotHaveBean(IdentidadUsuario.class);
                    assertThat(result).doesNotHaveBean(CatalogoProductos.class);
                });
    }

    @Test
    void rechazaIdentidadIncompletaYRolesDesconocidos() {
        context.withPropertyValues("spring.profiles.active=local", "carrito.identidad-local.object-id=")
                .run(result -> assertThat(result).hasFailed());
        context.withPropertyValues("spring.profiles.active=local", "carrito.identidad-local.tenant-id=")
                .run(result -> assertThat(result).hasFailed());
        context.withPropertyValues("spring.profiles.active=local", "carrito.identidad-local.roles=")
                .run(result -> assertThat(result).hasFailed());
        context.withPropertyValues("spring.profiles.active=local", "carrito.identidad-local.roles=SUPERADMIN")
                .run(result -> assertThat(result).hasFailed());
    }
}

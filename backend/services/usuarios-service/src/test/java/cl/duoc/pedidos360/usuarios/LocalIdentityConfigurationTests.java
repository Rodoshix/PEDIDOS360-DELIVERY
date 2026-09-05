package cl.duoc.pedidos360.usuarios;

import cl.duoc.pedidos360.usuarios.security.IdentidadUsuario;
import cl.duoc.pedidos360.usuarios.security.LocalIdentityConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class LocalIdentityConfigurationTests {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(LocalIdentityConfiguration.class)
            .withPropertyValues(
                    "usuarios.identidad-local.enabled=true",
                    "usuarios.identidad-local.tenant-id=11111111-1111-1111-1111-111111111111",
                    "usuarios.identidad-local.object-id=22222222-2222-2222-2222-222222222222",
                    "usuarios.identidad-local.roles=CLIENTE",
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
        context.withPropertyValues("spring.profiles.active=local", "server.address=0.0.0.0")
                .run(result -> assertThat(result).hasFailed());
    }

    @Test
    void permiteLocalCompletoYExigeHabilitacionExplicita() {
        context.withPropertyValues("spring.profiles.active=local")
                .run(result -> assertThat(result).hasSingleBean(IdentidadUsuario.class));
        context.withPropertyValues("spring.profiles.active=local", "usuarios.identidad-local.enabled=false")
                .run(result -> assertThat(result).doesNotHaveBean(IdentidadUsuario.class));
    }
}

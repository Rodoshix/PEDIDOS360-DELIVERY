package cl.duoc.pedidos360.pedidos.security;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;
class TenantIdentityTests {
    static final UUID T=UUID.fromString("11111111-1111-1111-1111-111111111111");
    MockEnvironment local() {
        var env=new MockEnvironment().withProperty("server.address","127.0.0.1");
        env.setActiveProfiles("local"); return env;
    }
    @Test void localRequiresExplicitTenantAndLoopback() {
        var cfg=new LocalIdentityConfiguration();
        assertThatThrownBy(()->cfg.identidadLocal(new LocalIdentityProperties(true,null,10L,Set.of(IdentidadUsuario.Rol.CLIENTE)),local()))
            .isInstanceOf(IllegalStateException.class);
        var props=new LocalIdentityProperties(true,T,10L,Set.of(IdentidadUsuario.Rol.CLIENTE));
        assertThat(cfg.identidadLocal(props,local()).tenantId()).isEqualTo(T);
        assertThatThrownBy(()->cfg.identidadLocal(props,local().withProperty("server.address","0.0.0.0")))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->cfg.identidadLocal(props,new MockEnvironment().withProperty("server.address","127.0.0.1")))
            .isInstanceOf(IllegalStateException.class);
    }
    @Test void workerHasNoTenantDefaultAndRejectsDiscordantConfig() {
        assertThatThrownBy(()->new TenantSistema(new MockEnvironment()).obtener()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->new TenantSistema(new MockEnvironment().withProperty("entra.tenant-id",T.toString())
            .withProperty("pedidos.interno.tenant-id","aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")).obtener())
            .isInstanceOf(IllegalStateException.class);
        assertThat(new TenantSistema(local().withProperty("pedidos.identidad-local.enabled","true")
            .withProperty("pedidos.identidad-local.tenant-id",T.toString())).obtener()).isEqualTo(T);
    }
}

package cl.duoc.pedidos360.pedidos;
import java.util.*;
import cl.duoc.pedidos360.pedidos.dto.*;
import cl.duoc.pedidos360.pedidos.entity.*;
import cl.duoc.pedidos360.pedidos.security.*;
import cl.duoc.pedidos360.pedidos.service.PedidoService;
import cl.duoc.pedidos360.pedidos.exception.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE)
@Import(PostgresTestConfiguration.class)
class PedidoTenantIsolationTests {
    static final UUID T=UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID OTHER=UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static IdentidadUsuario actor(UUID t,long id,IdentidadUsuario.Rol role) {
        return new IdentidadUsuario(t,id,Set.of(role));
    }
    final IdentidadUsuario owner=actor(T,10,IdentidadUsuario.Rol.CLIENTE);
    final IdentidadUsuario admin=actor(T,1,IdentidadUsuario.Rol.ADMIN);
    @Autowired PedidoService service;
    @Autowired JdbcTemplate jdbc;
    @BeforeEach @AfterEach void clean() {
        jdbc.execute("TRUNCATE pedidos.tenant_reconciliation_audit,pedidos.lineas_pedido,pedidos.pedidos RESTART IDENTITY CASCADE");
    }
    CrearPedidoRequest request() { return new CrearPedidoRequest(20L,"Prueba",List.of(new LineaPedidoRequest(101L,1))); }
    long historical() {
        // Fixture simulates pre-migration data only in the disposable Testcontainer.
        jdbc.execute("ALTER TABLE pedidos.pedidos DISABLE TRIGGER guard_tenant_origin");
        try {
            return jdbc.queryForObject("INSERT INTO pedidos.pedidos(usuario_id,restaurante_id,direccion_entrega,estado,total,moneda) VALUES(10,20,'Prueba','CREADO',6990,'CLP') RETURNING id",Long.class);
        } finally { jdbc.execute("ALTER TABLE pedidos.pedidos ENABLE TRIGGER guard_tenant_origin"); }
    }
    void reconcile(long id) {
        jdbc.execute("SELECT pedidos.reconcile_tenant("+id+",0,'"+T+"','"+"a".repeat(64)+"')");
    }
    @Test void matrixOwnerOtherAdminAndExternal() {
        long id=service.crear(owner,request()).pedidoId();
        assertThat(service.obtener(owner,id).pedidoId()).isEqualTo(id);
        assertThat(service.obtener(admin,id).pedidoId()).isEqualTo(id);
        assertThatThrownBy(()->service.obtener(actor(T,99,IdentidadUsuario.Rol.CLIENTE),id))
            .isInstanceOfSatisfying(PedidoException.class,e->assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        for(var role:IdentidadUsuario.Rol.values())
            assertThatThrownBy(()->service.obtener(actor(OTHER,10,role),id)).isInstanceOf(PedidoNoEncontradoException.class);
    }
    @Test void collectionsAlwaysFilterTenantIncludingSameLocalUserId() {
        service.crear(owner,request());
        service.crear(actor(OTHER,10,IdentidadUsuario.Rol.CLIENTE),request());
        assertThat(service.listar(admin)).hasSize(1);
        assertThat(service.listarPropios(owner)).hasSize(1);
        assertThat(service.listarPorUsuario(admin,10L)).hasSize(1);
    }
    @Test void unknownAndAbsentCannotBeReadOrConfirmed() {
        long id=historical();
        for(long resource:List.of(id,999999L)) {
            assertThatThrownBy(()->service.obtener(admin,resource)).isInstanceOf(PedidoNoEncontradoException.class);
            assertThatThrownBy(()->service.resumenParaPago(admin,resource)).isInstanceOf(PedidoNoEncontradoException.class);
            assertThatThrownBy(()->service.confirmarPorPago(T,resource)).isInstanceOf(PedidoNoEncontradoException.class);
        }
        assertThat(service.listar(admin)).isEmpty();
    }
    @Test void reconciledIsReadableButNeverMutableOrAutomaticallyConfirmed() {
        long id=historical(); reconcile(id);
        assertThat(service.obtener(owner,id).pedidoId()).isEqualTo(id);
        assertThat(service.resumenParaPago(admin,id).tenantOrigin()).isEqualTo("RECONCILED_LEGACY");
        assertThatThrownBy(()->service.cambiarEstado(admin,id,EstadoPedido.CONFIRMADO))
            .isInstanceOfSatisfying(PedidoException.class,e->assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThatThrownBy(()->service.confirmarPorPago(T,id)).isInstanceOf(PedidoException.class);
        assertThat(jdbc.queryForObject("SELECT estado FROM pedidos.pedidos WHERE id=?",String.class,id)).isEqualTo("CREADO");
    }
    @Test void systemConfirmationMustHaveMatchingTenantAndOrigin() {
        long id=service.crear(owner,request()).pedidoId();
        assertThatThrownBy(()->service.confirmarPorPago(OTHER,id)).isInstanceOf(PedidoNoEncontradoException.class);
        assertThatThrownBy(()->service.confirmarPorPago(null,id)).isInstanceOf(NullPointerException.class);
        service.confirmarPorPago(T,id);
        service.confirmarPorPago(T,id);
        assertThat(service.obtener(owner,id).estado()).isEqualTo("CONFIRMADO");
    }
    @Test void identityRequiresTenantPositiveIdAndImmutableRoles() {
        assertThatThrownBy(()->new IdentidadUsuario(null,10L,Set.of(IdentidadUsuario.Rol.ADMIN))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new IdentidadUsuario(T,0L,Set.of(IdentidadUsuario.Rol.ADMIN))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new IdentidadUsuario(T,10L,Set.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->owner.roles().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}

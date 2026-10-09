package cl.duoc.pedidos360.pagos;
import java.util.*;
import cl.duoc.pedidos360.pagos.client.PedidoResumen;
import cl.duoc.pedidos360.pagos.dto.CrearPagoRequest;
import cl.duoc.pedidos360.pagos.entity.*;
import cl.duoc.pedidos360.pagos.security.*;
import cl.duoc.pedidos360.pagos.service.PagoService;
import cl.duoc.pedidos360.pagos.exception.*;
import cl.duoc.pedidos360.pagos.repository.PagoRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE)
@Import({PostgresTestConfiguration.class,PedidosStubConfiguration.class})
class PagoTenantIsolationTests {
    static final UUID T=UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID OTHER=UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static IdentidadUsuario actor(UUID t,long id,IdentidadUsuario.Rol role) { return new IdentidadUsuario(t,id,Set.of(role)); }
    final IdentidadUsuario owner=actor(T,10,IdentidadUsuario.Rol.CLIENTE);
    final IdentidadUsuario admin=actor(T,1,IdentidadUsuario.Rol.ADMIN);
    @Autowired PagoService service;
    @Autowired PagoRepository repository;
    @Autowired PedidosClientStub pedidos;
    @Autowired JdbcTemplate jdbc;
    @BeforeEach @AfterEach void clean() {
        jdbc.execute("TRUNCATE pagos.tenant_reconciliation_audit,pagos.confirmacion_outbox,pagos.pagos RESTART IDENTITY CASCADE");
        pedidos.reiniciar();
    }
    CrearPagoRequest request() { return new CrearPagoRequest(500L,MetodoPago.EFECTIVO); }
    long historical(long pedido,String key) {
        jdbc.execute("ALTER TABLE pagos.pagos DISABLE TRIGGER guard_tenant_origin");
        try {
            return jdbc.queryForObject("INSERT INTO pagos.pagos(pedido_id,usuario_id,monto,moneda,metodo,estado,clave_idempotencia) VALUES(?,10,13980,'CLP','EFECTIVO','PENDIENTE',?) RETURNING id",Long.class,pedido,key);
        } finally { jdbc.execute("ALTER TABLE pagos.pagos ENABLE TRIGGER guard_tenant_origin"); }
    }
    void reconcile(long id) { jdbc.execute("SELECT pagos.reconcile_tenant("+id+",0,'"+T+"','"+"a".repeat(64)+"')"); }
    void assert404(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).satisfies(e -> {
            if(e instanceof PagoException p) assertThat(p.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
            else assertThat(e).isInstanceOf(PagoNoEncontradoException.class);
        });
    }
    @Test void matrixAndAdminRegistrarIdentityIsPreserved() {
        long id=service.registrar(admin,"admin",request()).pagoId();
        assertThat(service.obtener(admin,id).usuarioId()).isEqualTo(1L);
        assertThatThrownBy(()->service.obtener(owner,id)).isInstanceOfSatisfying(PagoException.class,e->assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assert404(()->service.obtener(actor(OTHER,1,IdentidadUsuario.Rol.ADMIN),id));
        assert404(()->service.aprobar(actor(OTHER,1,IdentidadUsuario.Rol.ADMIN),id));
        assert404(()->service.registrar(actor(OTHER,1,IdentidadUsuario.Rol.ADMIN),"external",request()));
        assertThat(repository.count()).isEqualTo(1);
    }
    @Test void reconciledPedidoReadableButRejectedBeforePaymentOutboxOrConfirmation() {
        pedidos.registrarResumen(new PedidoResumen(500L,T,10L,"CREADO",13980L,"CLP","RECONCILED_LEGACY"));
        assertThat(service.listarPorPedido(admin,500L)).isEmpty();
        for(var actor:List.of(owner,admin)) {
            assertThatThrownBy(()->service.registrar(actor,"legacy",request()))
                .isInstanceOfSatisfying(PagoException.class,e->assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        }
        assertThat(repository.count()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pagos.confirmacion_outbox",Long.class)).isZero();
        assertThat(pedidos.confirmaciones()).isZero();
    }
    @Test void unknownIdempotencyAndActivePaymentCollisionsAreUniform404() {
        long id=historical(500,"legacy-key");
        assert404(()->service.obtener(admin,id));
        assert404(()->service.registrar(owner,"different-key",request()));
        pedidos.registrarPedido(600,10,"CREADO",13980,"CLP");
        assert404(()->service.registrar(owner,"legacy-key",new CrearPagoRequest(600L,MetodoPago.EFECTIVO)));
        assertThat(repository.count()).isEqualTo(1);
        assertThat(pedidos.confirmaciones()).isZero();
    }
    @Test void reconciledPagoReadOnlyIdempotencyApprovalAndRecoveryNeverReactivate() {
        long id=historical(500,"legacy-key"); reconcile(id);
        assertThat(service.obtener(owner,id).pagoId()).isEqualTo(id);
        assertThat(service.listarPorPedido(admin,500L)).hasSize(1);
        assertThatThrownBy(()->service.aprobar(admin,id)).isInstanceOfSatisfying(PagoException.class,e->assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThatThrownBy(()->service.registrar(owner,"legacy-key",request())).isInstanceOf(PagoException.class);
        assertThat(service.reconciliarConfirmacionesPendientes()).isZero();
        assertThat(pedidos.confirmaciones()).isZero();
        assertThat(repository.findById(id).orElseThrow().getEstado()).isEqualTo(EstadoPago.PENDIENTE);
    }
    @Test void recoveryCannotSelectAnotherTenantsNewPayment() {
        repository.saveAndFlush(new Pago(OTHER,600L,10L,13980L,"CLP",MetodoPago.EFECTIVO,EstadoPago.PENDIENTE,"foreign"));
        assertThat(service.reconciliarConfirmacionesPendientes()).isZero();
        assertThat(pedidos.confirmaciones()).isZero();
    }
    @Test void matchingIdempotencyDoesNotAddWritesOrConfirmation() {
        var first=service.registrar(owner,"same",request());
        int confirmations=pedidos.confirmaciones();
        assertThat(service.registrar(owner,"same",request()).pagoId()).isEqualTo(first.pagoId());
        assertThat(repository.count()).isEqualTo(1);
        assertThat(pedidos.confirmaciones()).isEqualTo(confirmations);
    }
}

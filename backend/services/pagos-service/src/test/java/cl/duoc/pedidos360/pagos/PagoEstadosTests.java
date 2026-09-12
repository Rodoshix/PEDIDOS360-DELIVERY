package cl.duoc.pedidos360.pagos;

import cl.duoc.pedidos360.pagos.entity.EstadoPago;
import cl.duoc.pedidos360.pagos.entity.MetodoPago;
import cl.duoc.pedidos360.pagos.entity.Pago;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PagoEstadosTests {

    @Test
    void tarjetaAprobadaEsFinalYActiva() {
        Pago pago = new Pago(500L, 10L, 13980L, "CLP", MetodoPago.TARJETA,
                EstadoPago.APROBADO, "clave-1");
        assertThat(pago.estaActivo()).isTrue();
        assertThat(EstadoPago.APROBADO.esFinal()).isTrue();
    }

    @Test
    void efectivoPendienteEsActivoYNoFinal() {
        Pago pago = new Pago(500L, 10L, 13980L, "CLP", MetodoPago.EFECTIVO,
                EstadoPago.PENDIENTE, "clave-2");
        assertThat(pago.estaActivo()).isTrue();
        assertThat(EstadoPago.PENDIENTE.esFinal()).isFalse();
    }

    @Test
    void aprobarYRechazarCambianElEstado() {
        Pago pago = new Pago(500L, 10L, 13980L, "CLP", MetodoPago.EFECTIVO,
                EstadoPago.PENDIENTE, "clave-3");
        pago.aprobar();
        assertThat(pago.getEstado()).isEqualTo(EstadoPago.APROBADO);
        assertThat(pago.estaActivo()).isTrue();

        pago.rechazar();
        assertThat(pago.getEstado()).isEqualTo(EstadoPago.RECHAZADO);
        assertThat(pago.estaActivo()).isFalse();
        assertThat(EstadoPago.RECHAZADO.esFinal()).isTrue();
    }
}

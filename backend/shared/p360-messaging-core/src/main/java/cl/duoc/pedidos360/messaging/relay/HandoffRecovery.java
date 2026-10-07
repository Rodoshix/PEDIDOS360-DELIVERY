package cl.duoc.pedidos360.messaging.relay;

/**
 * Recuperacion del consumidor cuando una transferencia quedo sin confirmar.
 *
 * <p>Requiremento del contrato: si el handoff falla, no se confirma el original y se aplica
 * recuperacion controlada con backoff, sin bucle inmediato y sin {@code requeue=true}.
 *
 * <p>Con {@code AcknowledgeMode.MANUAL} una excepcion normal no produce {@code basicNack} ni
 * {@code basicReject}: el mensaje queda sin confirmar y, con {@code prefetch=1}, la ventana del
 * consumidor se llena. La recuperacion real exige cerrar el canal para que el broker reentregue y
 * volver a levantar el consumidor. {@link QueryConsumerRecovery} implementa ese ciclo.
 */
@FunctionalInterface
public interface HandoffRecovery {

    /**
     * Se invoca cuando el request original no pudo confirmarse porque su respuesta o su
     * transferencia quedo sin confirmar.
     *
     * <p>La implementacion no debe confirmar ni rechazar el mensaje original: solo solicita la
     * recuperacion del consumidor.
     */
    void sinConfirmar(String messageId, String correlationId, String destino, Exception causa);

    /**
     * Implementacion de diagnostico puro, sin recuperacion.
     *
     * <p>Deja constancia del fallo y no reinicia nada: con {@code prefetch=1} el consumidor queda
     * detenido hasta intervencion externa. Solo es apropiada en procesos sin listener propio o en
     * pruebas que verifican que el request no se confirma.
     */
    static HandoffRecovery soloDiagnostico() {
        return (messageId, correlationId, destino, causa) -> org.slf4j.LoggerFactory
                .getLogger(HandoffRecovery.class).error(
                        "Handoff no confirmado hacia {} messageId={} correlationId={}; el mensaje permanece SIN CONFIRMAR: {}",
                        destino, messageId, correlationId, causa == null ? "sin causa" : causa.getMessage());
    }
}

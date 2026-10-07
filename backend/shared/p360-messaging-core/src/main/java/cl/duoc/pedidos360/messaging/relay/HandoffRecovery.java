package cl.duoc.pedidos360.messaging.relay;

/**
 * Recuperacion del consumidor cuando una transferencia quedo sin confirmar.
 *
 * <p>Requiremento del contrato: si el handoff falla, no se confirma el original y se aplica
 * recuperacion controlada con backoff, sin bucle inmediato y sin {@code requeue=true}.
 *
 * <p>La implementacion por defecto de la base no reinicia el canal ni hace {@code basicNack}: deja
 * el mensaje sin confirmar y lo registra, de modo que con {@code prefetch=1} el consumidor se
 * detiene hasta intervencion o reinicio, sin perder el mensaje y sin consumir CPU. La activacion
 * de una recuperacion activa (reinicio de canal con espera, supervisada por plataforma) se difiere
 * a #69, que define la politica operativa de indisponibilidad de broker.
 */
@FunctionalInterface
public interface HandoffRecovery {

    void sinConfirmar(String messageId, String correlationId, String destino, Exception causa);

    /** Implementacion por defecto: solo diagnostico. */
    static HandoffRecovery soloDiagnostico() {
        return (messageId, correlationId, destino, causa) -> org.slf4j.LoggerFactory
                .getLogger(HandoffRecovery.class).error(
                        "Handoff no confirmado hacia {} messageId={} correlationId={}; el mensaje permanece SIN CONFIRMAR: {}",
                        destino, messageId, correlationId, causa == null ? "sin causa" : causa.getMessage());
    }
}

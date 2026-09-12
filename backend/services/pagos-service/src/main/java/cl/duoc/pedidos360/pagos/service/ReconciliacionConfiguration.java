package cl.duoc.pedidos360.pagos.service;

import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Habilita el planificador para la reconciliación de confirmaciones pendientes. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class ReconciliacionConfiguration {

    /**
     * Reintenta de forma periódica las confirmaciones de pedido que quedaron pendientes
     * (fallo o pérdida de respuesta de Pedidos). Es idempotente.
     */
    @Component
    @ConditionalOnProperty(name = "pagos.reconciliacion.enabled", havingValue = "true", matchIfMissing = true)
    static class PagoReconciliacionScheduler {

        private final PagoService pagos;

        PagoReconciliacionScheduler(PagoService pagos) {
            this.pagos = pagos;
        }

        @Scheduled(fixedDelayString = "${pagos.reconciliacion.intervalo-ms:30000}")
        void reconciliar() {
            int recuperados = pagos.reconciliarConfirmacionesPendientes();
            if (recuperados > 0) {
                LoggerFactory.getLogger(PagoReconciliacionScheduler.class)
                        .info("Confirmaciones de pedido recuperadas: {}", recuperados);
            }
        }
    }
}

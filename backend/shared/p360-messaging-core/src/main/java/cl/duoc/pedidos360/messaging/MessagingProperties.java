package cl.duoc.pedidos360.messaging;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Configuracion central de la base request/reply.
 *
 * <p>Todos los nombres de exchanges, colas y routing keys viven aqui. Ninguna clase de logica de
 * dominio debe contener nombres literales: el consumidor y el adaptador reciben la topologia ya
 * resuelta por {@link QueryTopology}.
 *
 * <p>Valores iniciales aprobados: presupuesto de 5 s y retry corto de 1 s. El plazo
 * ({@code expiresAt}) es absoluto y no se renueva con un retry.
 *
 * <p>La declaracion de topologia es una herramienta de desarrollo: en la plataforma de #69 las
 * colas ya existen con argumentos minimos y el TTL/DLX de los retries simples vive en policies del
 * broker. Declarar aqui esos argumentos provocaria {@code PRECONDITION_FAILED}, por eso las
 * declaraciones no los incluyen.
 */
@ConfigurationProperties("pedidos360.messaging")
public record MessagingProperties(@NotNull RelayMode relayMode, @NotNull RelayMode.Role role,
        @NotNull Exchanges exchanges, @NotNull Queues queues, @NotNull Naming naming, @NotNull DomainRouting routing,
        @NotNull @Positive Duration deadline, @NotNull @Positive Duration actorTtl, @NotNull Duration retryDelay,
        @NotNull Duration confirmTimeout, @NotNull Duration recoveryBackoff, boolean declareTopology,
        @Min(1) @Max(100_000) int maxPendingCorrelations, @Min(1024) @Max(1_048_576) int maxBodyBytes) {

    /** Valores por defecto de los limites, para que el enlace parcial no los deje en cero. */
    public MessagingProperties {
        if (maxPendingCorrelations == 0) maxPendingCorrelations = 1024;
        if (maxBodyBytes == 0) maxBodyBytes = 262_144;
    }

    /** Exchanges personalizados de la ampliacion. El exchange predeterminado de respuestas no se declara. */
    public record Exchanges(@NotNull String queries, @NotNull String retry, @NotNull String dlx) {}

    /** Cola tecnica compartida de respuestas del BFF. */
    public record Queues(@NotNull String responses) {}

    /**
     * Plantillas de nombre. La composicion queda fuera de la logica de dominio y los valores
     * aprobados (p360.*, sufijos .q / .retry.1s.q / .dlq, sufijos de routing key) se pueden ajustar
     * sin tocar codigo.
     */
    public record Naming(@NotNull String prefix, @NotNull String querySuffix, @NotNull String retrySuffix,
            @NotNull String dlqSuffix, @NotNull String retryKeySuffix, @NotNull String failedKeySuffix) {}

    /**
     * Routing key principal y sufijo base por dominio.
     *
     * <p>Las routing keys de retry y failed se derivan del sufijo base, no de la principal: el
     * inventario aprobado usa {@code usuario.consultar-actual.retry.1s}, sin la version del mensaje.
     *
     * <p>{@code mapNotFound} no vive aqui: un 404 de dominio viaja como respuesta correlacionada
     * cuando la operacion describe un recurso concreto.
     */
    public record DomainRouting(@NotNull String usuario, @NotNull String restaurante, @NotNull String producto,
            @NotNull String pago, @NotNull String usuarioBase, @NotNull String restauranteBase,
            @NotNull String productoBase, @NotNull String pagoBase) {
        public String operacion(Domain domain) {
            return switch (domain) {
                case USUARIOS -> usuario;
                case RESTAURANTES -> restaurante;
                case PRODUCTOS -> producto;
                case PAGOS -> pago;
            };
        }

        public String base(Domain domain) {
            return switch (domain) {
                case USUARIOS -> usuarioBase;
                case RESTAURANTES -> restauranteBase;
                case PRODUCTOS -> productoBase;
                case PAGOS -> pagoBase;
            };
        }
    }

    @AssertTrue(message = "los nombres de exchanges y de la cola de respuestas no pueden estar vacios")
    public boolean isNombresValidos() {
        return exchanges != null && nonBlank(exchanges.queries()) && nonBlank(exchanges.retry())
                && nonBlank(exchanges.dlx()) && queues != null && nonBlank(queues.responses());
    }

    @AssertTrue(message = "las plantillas de nombre y las routing keys por dominio no pueden estar vacias")
    public boolean isPlantillasValidas() {
        if (naming == null || !nonBlank(naming.prefix()) || !nonBlank(naming.querySuffix())
                || !nonBlank(naming.retrySuffix()) || !nonBlank(naming.dlqSuffix())
                || !nonBlank(naming.retryKeySuffix()) || !nonBlank(naming.failedKeySuffix())
                || routing == null) return false;
        for (Domain domain : Domain.values()) {
            if (!nonBlank(routing.operacion(domain)) || !nonBlank(routing.base(domain))) return false;
        }
        return true;
    }

    @AssertTrue(message = "el plazo debe superar el retry corto y la ventana del actor; los tiempos deben ser positivos")
    public boolean isTiemposValidos() {
        if (deadline == null || actorTtl == null || retryDelay == null || confirmTimeout == null
                || recoveryBackoff == null) return false;
        if (!deadline.isPositive() || !actorTtl.isPositive() || !confirmTimeout.isPositive()
                || !retryDelay.isPositive() || recoveryBackoff.isNegative()) return false;
        return deadline.compareTo(retryDelay) > 0 && actorTtl.compareTo(deadline) <= 0;
    }

    @AssertTrue(message = "el limite de correlaciones y el tamano maximo deben ser razonables")
    public boolean isLimitesValidos() {
        return maxPendingCorrelations >= 1 && maxPendingCorrelations <= 100_000
                && maxBodyBytes >= 1024 && maxBodyBytes <= 1_048_576;
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }
}

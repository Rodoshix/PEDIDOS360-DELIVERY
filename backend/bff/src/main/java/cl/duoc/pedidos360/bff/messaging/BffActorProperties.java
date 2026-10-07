package cl.duoc.pedidos360.bff.messaging;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotNull;

/**
 * Configuracion del emisor de contexto de actor del BFF.
 *
 * <p>{@code destinos} es la lista de colas funcionales que el BFF puede atender. Un sobre dirigido a
 * un destino fuera de esa lista no se acepta en el consumidor.
 *
 * <p>{@code ttl} debe ser menor que el presupuesto de la consulta: asi el sobre no sobrevive al
 * plazo y un retry corto siempre encuentra contexto vigente.
 */
@Validated
@ConfigurationProperties("pedidos360.bff.actor")
public record BffActorProperties(@NotNull String emisor, @NotNull Duration ttl, @NotNull java.util.Set<String> destinos) {

    public BffActorProperties {
        destinos = java.util.Set.copyOf(destinos);
        if (destinos.isEmpty()) throw new IllegalArgumentException("se requiere al menos un destino permitido");
    }
}

package cl.duoc.pedidos360.messaging.actor;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Actor verificado que viaja entre el BFF y los microservicios.
 *
 * <p>El BFF valida Entra por HTTP y emite este contexto. Ningun listener RabbitMQ hereda el
 * {@code SecurityContext} HTTP, por lo que el consumidor solo puede confiar en este sobre si su
 * firma, emisor, plazo y destino se verifican correctamente.
 *
 * <p>Reglas del contrato:
 * <ul>
 *   <li>Nunca se transporta el JWT original ni un secreto.</li>
 *   <li>{@code sujetoId} es el identificador de Entra ({@code oid}); no es el id local de perfil.</li>
 *   <li>{@code expiraEn} es absoluto, no se renueva con un retry y nunca supera el plazo del
 *       request. El verificador tambien lo contrasta con el reloj actual.</li>
 *   <li>{@code audiencia} es la cola destino: un sobre emitido para un dominio no es aceptable en
 *       otro. La verificacion de destino es obligatoria en el consumidor.</li>
 *   <li>{@code keyId} es metadata verificada de la cabecera JWS protegida, no un claim de identidad.</li>
 *   <li>{@code roles} y {@code scopes} son autorizacion, no pertenencia: el consumidor debe volver
 *       a aplicar sus propias reglas de tenant, pertenencia y perfil activo.</li>
 * </ul>
 */
public record ActorContext(UUID tenantId, UUID sujetoId, Set<String> roles, Set<String> scopes,
        Instant emitidoEn, Instant expiraEn, String audiencia, UUID keyId) {

    public ActorContext {
        if (tenantId == null) throw new IllegalArgumentException("tenantId requerido");
        if (sujetoId == null) throw new IllegalArgumentException("sujetoId requerido");
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
        if (emitidoEn == null) throw new IllegalArgumentException("emitidoEn requerido");
        if (expiraEn == null) throw new IllegalArgumentException("expiraEn requerido");
        if (audiencia == null || audiencia.isBlank()) throw new IllegalArgumentException("audiencia requerida");
        if (keyId == null) throw new IllegalArgumentException("keyId requerido");
    }

    public boolean tieneRol(String rol) {
        return roles.contains(rol);
    }

    public boolean tieneScope(String scope) {
        return scopes.contains(scope);
    }
}

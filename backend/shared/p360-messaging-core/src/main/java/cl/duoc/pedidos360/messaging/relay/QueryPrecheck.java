package cl.duoc.pedidos360.messaging.relay;

import java.util.Set;

import cl.duoc.pedidos360.messaging.actor.ActorContext;

/**
 * Comprobaciones posteriores a la verificacion criptografica del sobre.
 *
 * <p>Permite que cada dominio reaplique sus propias reglas sin duplicar la verificacion de firma,
 * plazo y destino. Es opcional: si no se declara, la comprobacion del actor queda solo en la firma.
 */
@FunctionalInterface
public interface QueryPrecheck {

    /**
     * @throws RuntimeException si el actor no puede operar sobre esta consulta. Un fallo de
     *     autorizacion es definitivo: no se ejecuta la operacion y no se reintenta.
     */
    void validar(ActorContext actor);

    /** Comprobacion por defecto: exige al menos un rol de la lista y ningun scope adicional. */
    static QueryPrecheck exigeRol(Set<String> rolesPermitidos) {
        return actor -> {
            if (rolesPermitidos == null || rolesPermitidos.isEmpty())
                throw new IllegalArgumentException("se requiere al menos un rol permitido");
            for (String rol : rolesPermitidos) {
                if (actor.tieneRol(rol)) return;
            }
            throw new org.springframework.security.access.AccessDeniedException(
                    "El actor no tiene un rol habilitado para esta consulta.");
        };
    }
}

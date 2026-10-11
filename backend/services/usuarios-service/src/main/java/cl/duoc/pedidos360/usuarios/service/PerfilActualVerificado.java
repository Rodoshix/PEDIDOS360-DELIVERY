package cl.duoc.pedidos360.usuarios.service;

import java.time.Instant;
import java.util.UUID;
import cl.duoc.pedidos360.usuarios.dto.UsuarioResponse;

/** Copia de una única lectura persistente. La actividad puede cambiar después de esta observación. */
public record PerfilActualVerificado(UUID tenantId, UUID entraObjectId, UsuarioResponse perfil,
        Instant perfilVerificadoEn) {
    @Override public String toString() { return "PerfilActualVerificado[redacted]"; }
}

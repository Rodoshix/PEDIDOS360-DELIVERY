package cl.duoc.pedidos360.messaging.identity;

import java.time.Instant;
import java.util.UUID;

/** Atestación puntual de Usuarios; no contiene roles ni autorización de un recurso. */
public record IdentityProof(UUID tenantId, UUID entraObjectId, long usuarioId, Instant perfilVerificadoEn,
        Instant emitidoEn, Instant expiraEn, Instant deadlineOriginal, UUID usuariosRequestId, UUID jti) {
    public static final String TYPE = "p360-identidad-pagos+jws";
    public static final String ISSUER = "usuarios-service";
    public static final String AUDIENCE = "p360.pagos.consultas.q";
    public static final String OPERATION = "pago.consultar.v1";
    public IdentityProof {
        if (tenantId == null || entraObjectId == null || usuarioId < 1 || perfilVerificadoEn == null
                || emitidoEn == null || expiraEn == null || deadlineOriginal == null
                || usuariosRequestId == null || jti == null)
            throw new IdentityProofException(IdentityProofException.Reason.FORMATO);
        Instant limite = perfilVerificadoEn.plusSeconds(4);
        if (deadlineOriginal.isBefore(limite)) limite = deadlineOriginal;
        if (emitidoEn.isBefore(perfilVerificadoEn) || !expiraEn.isAfter(emitidoEn) || !expiraEn.equals(limite))
            throw new IdentityProofException(IdentityProofException.Reason.TIEMPO_INCOHERENTE);
        for (Instant timestamp : new Instant[]{perfilVerificadoEn, emitidoEn, expiraEn, deadlineOriginal})
            if (!timestamp.equals(IdentityProofCodec.millis(timestamp)))
                throw new IdentityProofException(IdentityProofException.Reason.FORMATO);
    }
    @Override public String toString() { return "IdentityProof[redacted]"; }
}

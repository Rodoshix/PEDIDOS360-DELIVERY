package cl.duoc.pedidos360.messaging.identity;

import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Verificador compartido por BFF y futuro Pagos; valida antes de devolver identidad local. */
public final class IdentityProofVerifier {
    private final IdentityProofKeys keys;
    private final Clock clock;
    private final Duration margin;
    public IdentityProofVerifier(IdentityProofKeys keys, Clock clock, Duration margin) {
        this.keys = java.util.Objects.requireNonNull(keys); this.clock = java.util.Objects.requireNonNull(clock);
        if (margin == null || margin.compareTo(Duration.ofMillis(250)) < 0 || margin.compareTo(Duration.ofSeconds(1)) > 0)
            throw new IllegalArgumentException("margen conservador debe estar entre 250 ms y 1 s; nunca se deshabilita");
        this.margin = margin;
    }
    public IdentityProof verify(String jws, UUID tenant, UUID oid, UUID usuariosRequestId, Instant originalDeadline) {
        if (jws == null || jws.length() > 4096) throw new IdentityProofException(IdentityProofException.Reason.FORMATO);
        String[] parts = jws.split("\\.", -1);
        if (parts.length != 3) throw new IdentityProofException(IdentityProofException.Reason.FORMATO);
        var header = IdentityProofCodec.object(IdentityProofCodec.decode(parts[0], 256));
        byte[] payload = IdentityProofCodec.decode(parts[1], 2048);
        byte[] signature = IdentityProofCodec.decode(parts[2], 64);
        if (!IdentityProofCodec.fields(header).equals(Set.of("alg", "typ", "kid"))
                || !"ES256".equals(IdentityProofCodec.text(header,"alg"))
                || !IdentityProof.TYPE.equals(IdentityProofCodec.text(header,"typ")) || signature.length != 64)
            throw new IdentityProofException(IdentityProofException.Reason.FORMATO);
        var key = keys.get(IdentityProofCodec.uuid(IdentityProofCodec.text(header,"kid")));
        try {
            if (!JWSObject.parse(jws).verify(new ECDSAVerifier(key.material())))
                throw new IdentityProofException(IdentityProofException.Reason.FIRMA);
        } catch (java.text.ParseException | com.nimbusds.jose.JOSEException invalid) {
            throw new IdentityProofException(IdentityProofException.Reason.FIRMA);
        }
        IdentityProof proof = IdentityProofCodec.read(IdentityProofCodec.object(payload));
        if (!proof.tenantId().equals(tenant) || !proof.entraObjectId().equals(oid)
                || !proof.usuariosRequestId().equals(usuariosRequestId) || !proof.deadlineOriginal().equals(originalDeadline))
            throw new IdentityProofException(IdentityProofException.Reason.VINCULO);
        Instant now = clock.instant();
        if (proof.emitidoEn().isAfter(now.plus(margin)))
            throw new IdentityProofException(IdentityProofException.Reason.TIEMPO_INCOHERENTE);
        if (!proof.expiraEn().isAfter(now.plus(margin)))
            throw new IdentityProofException(IdentityProofException.Reason.VENCIDA);
        return proof;
    }
    /** Límite conservador para complementar la verificación con un presupuesto monotónico. */
    public Instant usableUntil(IdentityProof proof) { return proof.expiraEn().minus(margin); }
}
